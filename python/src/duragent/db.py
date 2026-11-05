"""Storage: the tables, and a connection that is safe to share between threads.

SQLite connections are not meant to be used from several threads at once. A
worker does exactly that - the heartbeat thread renews a lease while the main
thread writes a step result - and the failure mode is ugly: an
``InterfaceError: bad parameter or other API misuse`` thrown at a random call
site, which is why the wrapper below exists.

Every statement runs under one lock and returns materialised rows, so a caller
can never hold a half-used cursor while another thread writes.
"""

from __future__ import annotations

import sqlite3
import threading

STEP_TABLE = """
CREATE TABLE IF NOT EXISTS step_record (
    run_id        TEXT    NOT NULL,
    step_key      TEXT    NOT NULL,
    status        TEXT    NOT NULL,        -- RUNNING | SUCCEEDED | FAILED
    attempt       INTEGER NOT NULL,
    output_json   TEXT,
    error_text    TEXT,
    started_at    REAL    NOT NULL,        -- epoch seconds
    finished_at   REAL,
    lease_until   REAL    NOT NULL,        -- a dead worker's claim expires
    PRIMARY KEY (run_id, step_key)
)
"""

APPROVAL_TABLE = """
CREATE TABLE IF NOT EXISTS approval (
    id            TEXT NOT NULL,
    run_id        TEXT NOT NULL,
    node          TEXT NOT NULL,
    reason        TEXT,
    status        TEXT NOT NULL,           -- PENDING | APPROVED | REJECTED
    requested_at  REAL NOT NULL,
    decided_at    REAL,
    decided_by    TEXT,
    comment       TEXT,
    PRIMARY KEY (id)
)
"""

BUDGET_TABLE = """
CREATE TABLE IF NOT EXISTS budget (
    run_id            TEXT PRIMARY KEY,
    max_tokens        INTEGER,
    max_cost_micros   INTEGER,
    max_steps         INTEGER,
    used_tokens       INTEGER NOT NULL DEFAULT 0,
    used_cost_micros  INTEGER NOT NULL DEFAULT 0,
    used_steps        INTEGER NOT NULL DEFAULT 0,
    started_at        REAL NOT NULL,
    updated_at        REAL NOT NULL
)
"""

RUN_TABLE = """
CREATE TABLE IF NOT EXISTS run (
    id          TEXT PRIMARY KEY,
    workflow    TEXT NOT NULL,
    status      TEXT NOT NULL,             -- PENDING | RUNNING | SUSPENDED | COMPLETED | FAILED | CANCELED
    input_json  TEXT,
    created_at  REAL NOT NULL,
    updated_at  REAL NOT NULL
)
"""

LEASE_TABLE = """
CREATE TABLE IF NOT EXISTS run_lease (
    run_id          TEXT PRIMARY KEY,
    worker_id       TEXT NOT NULL,
    leased_until    REAL NOT NULL,
    claimed_at      REAL NOT NULL,
    heartbeat_at    REAL NOT NULL
)
"""


class Result:
    """Rows already fetched, plus the row count. Safe to keep around."""

    def __init__(self, rows: list, rowcount: int) -> None:
        self._rows = rows
        self.rowcount = rowcount

    def fetchone(self):
        return self._rows[0] if self._rows else None

    def fetchall(self) -> list:
        return list(self._rows)

    def __iter__(self):
        return iter(self._rows)

    def __len__(self) -> int:
        return len(self._rows)


class Connection:
    """A sqlite3 connection that serialises statements across threads."""

    def __init__(self, inner: sqlite3.Connection) -> None:
        self._inner = inner
        self._lock = threading.RLock()
        self.row_factory = inner.row_factory

    def execute(self, sql: str, parameters=()) -> Result:
        with self._lock:
            cursor = self._inner.execute(sql, parameters)
            rows = cursor.fetchall() if cursor.description else []
            return Result(rows, cursor.rowcount)

    def executescript(self, script: str) -> None:
        with self._lock:
            self._inner.executescript(script)

    def commit(self) -> None:
        with self._lock:
            self._inner.commit()

    def close(self) -> None:
        with self._lock:
            self._inner.close()

    def inner(self) -> sqlite3.Connection:
        return self._inner


def connect(path: str = ":memory:") -> Connection:
    """Open a database and make sure the tables exist."""
    inner = sqlite3.connect(path, isolation_level=None, check_same_thread=False)
    inner.row_factory = sqlite3.Row
    # Two processes writing at the same time: wait instead of failing with
    # "database is locked" on the first collision.
    inner.execute("PRAGMA busy_timeout = 5000")
    if path != ":memory:":
        inner.execute("PRAGMA journal_mode=WAL")
    conn = Connection(inner)
    init_schema(conn)
    return conn


def init_schema(conn: Connection) -> None:
    for statement in (STEP_TABLE, APPROVAL_TABLE, BUDGET_TABLE, RUN_TABLE, LEASE_TABLE,
                      "CREATE INDEX IF NOT EXISTS idx_run_status ON run(status)"):
        conn.execute(statement)
