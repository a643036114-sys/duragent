"""Storage backends: SQLite by default, PostgreSQL when you have one.

The SQL is written once, with ``?`` placeholders and portable column types, and
the backend translates it. That keeps every query readable and means the two
implementations cannot drift apart.

Two things the wrapper has to provide on top of a raw driver:

* **thread safety.** A worker's heartbeat thread renews a lease while the main
  thread writes a step result. SQLite connections are not meant for that, and the
  failure is an ``InterfaceError`` at a random call site.
* **materialised rows.** A caller must never hold a half-used cursor while
  another thread writes, so ``execute`` fetches everything before returning.
"""

from __future__ import annotations

import sqlite3
import threading

SCHEMA = [
    """
    CREATE TABLE IF NOT EXISTS step_record (
        run_id        TEXT    NOT NULL,
        step_key      TEXT    NOT NULL,
        status        TEXT    NOT NULL,        -- RUNNING | SUCCEEDED | FAILED
        attempt       INTEGER NOT NULL,
        output_json   TEXT,
        error_text    TEXT,
        started_at    DOUBLE PRECISION NOT NULL,
        finished_at   DOUBLE PRECISION,
        lease_until   DOUBLE PRECISION NOT NULL,
        PRIMARY KEY (run_id, step_key)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS approval (
        id            TEXT NOT NULL,
        run_id        TEXT NOT NULL,
        node          TEXT NOT NULL,
        reason        TEXT,
        status        TEXT NOT NULL,           -- PENDING | APPROVED | REJECTED | EXPIRED
        requested_at  DOUBLE PRECISION NOT NULL,
        decided_at    DOUBLE PRECISION,
        decided_by    TEXT,
        comment       TEXT,
        expires_at    DOUBLE PRECISION,        -- NULL means "wait forever"
        PRIMARY KEY (id)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS budget (
        run_id            TEXT PRIMARY KEY,
        max_tokens        BIGINT,
        max_cost_micros   BIGINT,
        max_steps         INTEGER,
        used_tokens       BIGINT       NOT NULL DEFAULT 0,
        used_cost_micros  BIGINT       NOT NULL DEFAULT 0,
        used_steps        INTEGER      NOT NULL DEFAULT 0,
        started_at        DOUBLE PRECISION NOT NULL,
        updated_at        DOUBLE PRECISION NOT NULL
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS run (
        id          TEXT PRIMARY KEY,
        workflow    TEXT NOT NULL,
        status      TEXT NOT NULL,             -- PENDING | RUNNING | SUSPENDED | COMPLETED | FAILED | CANCELED
        input_json  TEXT,
        created_at  DOUBLE PRECISION NOT NULL,
        updated_at  DOUBLE PRECISION NOT NULL,
        not_before  DOUBLE PRECISION           -- set while a step backs off
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS run_lease (
        run_id          TEXT PRIMARY KEY,
        worker_id       TEXT NOT NULL,
        leased_until    DOUBLE PRECISION NOT NULL,
        claimed_at      DOUBLE PRECISION NOT NULL,
        heartbeat_at    DOUBLE PRECISION NOT NULL
    )
    """,
    "CREATE INDEX IF NOT EXISTS idx_run_status ON run(status)",
]

# Columns added after the first release. ``CREATE TABLE IF NOT EXISTS`` does not
# touch an existing table, so a database created by an older version needs a
# nudge. Each entry is (table, column, type) and the upgrade is a plain
# ADD COLUMN, which both SQLite and PostgreSQL understand.
ADDED_COLUMNS = [
    ("approval", "expires_at", "DOUBLE PRECISION"),
    ("run", "not_before", "DOUBLE PRECISION"),
]


class IntegrityError(Exception):
    """A unique constraint lost a race. The same meaning on every backend."""


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


class SqliteBackend:
    name = "sqlite"

    def __init__(self, path: str) -> None:
        self._inner = sqlite3.connect(path, isolation_level=None, check_same_thread=False)
        self._inner.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        # Two processes writing at once: wait instead of failing with
        # "database is locked" on the first collision.
        self._inner.execute("PRAGMA busy_timeout = 5000")
        if path != ":memory:":
            self._inner.execute("PRAGMA journal_mode=WAL")

    def execute(self, sql: str, parameters=()) -> Result:
        with self._lock:
            try:
                cursor = self._inner.execute(sql, parameters)
            except sqlite3.IntegrityError as exc:
                raise IntegrityError(str(exc)) from exc
            rows = cursor.fetchall() if cursor.description else []
            return Result(rows, cursor.rowcount)

    def close(self) -> None:
        with self._lock:
            self._inner.close()


class PostgresBackend:
    name = "postgres"

    def __init__(self, url: str) -> None:
        try:
            import psycopg
            from psycopg.rows import dict_row
        except ImportError as exc:  # pragma: no cover - depends on the environment
            raise RuntimeError(
                "PostgreSQL support needs psycopg: pip install 'duragent[postgres]'"
            ) from exc

        self._psycopg = psycopg
        self._inner = psycopg.connect(url, autocommit=True, row_factory=dict_row)
        self._lock = threading.RLock()

    def execute(self, sql: str, parameters=()) -> Result:
        # Our SQL says "?", Postgres says "%s". One translation, one place.
        translated = sql.replace("?", "%s")
        with self._lock, self._inner.cursor() as cursor:
            try:
                cursor.execute(translated, parameters)
            except self._psycopg.errors.UniqueViolation as exc:
                raise IntegrityError(str(exc)) from exc
            rows = cursor.fetchall() if cursor.description else []
            return Result(rows, cursor.rowcount)

    def close(self) -> None:
        with self._lock:
            self._inner.close()


class Connection:
    """Backend-agnostic connection. Everything in the library talks to this."""

    def __init__(self, backend) -> None:
        self._backend = backend

    @property
    def backend_name(self) -> str:
        return self._backend.name

    def execute(self, sql: str, parameters=()) -> Result:
        return self._backend.execute(sql, parameters)

    def close(self) -> None:
        self._backend.close()

    def __enter__(self) -> "Connection":
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()


def connect(target: str = ":memory:") -> Connection:
    """Open a database.

    ``:memory:`` or a path -> SQLite. A ``postgresql://`` URL -> PostgreSQL.
    """
    if target.startswith(("postgresql://", "postgres://")):
        backend = PostgresBackend(target)
    else:
        backend = SqliteBackend(target)

    conn = Connection(backend)
    for statement in SCHEMA:
        conn.execute(statement)
    _add_missing_columns(conn)
    _verify_schema(conn)
    return conn


def _add_missing_columns(conn: Connection) -> None:
    """Upgrade a database created by an older version.

    ``CREATE TABLE IF NOT EXISTS`` is a no-op when the table exists, so a new
    column has to be added explicitly. There is no version table yet: the column
    simply either exists or it does not, which is enough for additive changes.
    """
    for table, column, ddl in ADDED_COLUMNS:
        try:
            conn.execute(f"SELECT {column} FROM {table} WHERE 1 = 0")
        except Exception:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {ddl}")


def _verify_schema(conn: Connection) -> None:
    """Fail loudly on a table that exists but was created by an older version.

    ``CREATE TABLE IF NOT EXISTS`` is a no-op when the table is already there, so a
    stale schema would otherwise surface as a confusing "column does not exist" at
    the first query. There are no migrations yet; the honest thing is to say so.
    """
    try:
        conn.execute("SELECT started_at FROM step_record WHERE 1 = 0")
    except Exception as exc:
        raise RuntimeError(
            "the database has an incompatible schema, probably created by an older "
            "version. Drop the duragent tables or point at a fresh database."
        ) from exc


def init_schema(conn: Connection) -> None:
    for statement in SCHEMA:
        conn.execute(statement)
