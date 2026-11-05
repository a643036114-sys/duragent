"""Storage: one table, four statements.

SQLite is the default because it makes the demo run with zero setup. The SQL is
plain enough to work on Postgres unchanged; only the driver differs.
"""

from __future__ import annotations

import sqlite3

SCHEMA = """
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


def connect(path: str = ":memory:") -> sqlite3.Connection:
    """Open a database and make sure the table exists."""
    conn = sqlite3.connect(path, isolation_level=None)  # autocommit: we manage transactions
    conn.row_factory = sqlite3.Row
    if path != ":memory:":
        conn.execute("PRAGMA journal_mode=WAL")
    init_schema(conn)
    return conn


def init_schema(conn: sqlite3.Connection) -> None:
    conn.execute(SCHEMA)
