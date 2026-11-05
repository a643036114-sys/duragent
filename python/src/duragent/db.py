"""Storage: two tables, a handful of statements.

SQLite is the default because it makes the demo run with zero setup. The SQL is
plain enough to work on Postgres unchanged; only the driver differs.
"""

from __future__ import annotations

import sqlite3

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


def connect(path: str = ":memory:") -> sqlite3.Connection:
    """Open a database and make sure the tables exist."""
    conn = sqlite3.connect(path, isolation_level=None)  # autocommit: we manage transactions
    conn.row_factory = sqlite3.Row
    if path != ":memory:":
        conn.execute("PRAGMA journal_mode=WAL")
    init_schema(conn)
    return conn


def init_schema(conn: sqlite3.Connection) -> None:
    conn.execute(STEP_TABLE)
    conn.execute(APPROVAL_TABLE)
    conn.execute(BUDGET_TABLE)
