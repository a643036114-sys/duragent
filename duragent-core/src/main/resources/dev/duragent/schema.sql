CREATE TABLE IF NOT EXISTS step_record (
    run_id              VARCHAR(64)  NOT NULL,
    step_key            VARCHAR(191) NOT NULL,
    step_name           VARCHAR(191) NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    attempt             INT          NOT NULL,
    output_json         TEXT,
    error_text          TEXT,
    started_at_ms       BIGINT       NOT NULL,
    finished_at_ms      BIGINT,
    lease_expires_at_ms BIGINT       NOT NULL,
    CONSTRAINT pk_step_record PRIMARY KEY (run_id, step_key)
);

CREATE TABLE IF NOT EXISTS run (
    id            VARCHAR(64)  NOT NULL,
    workflow      VARCHAR(191) NOT NULL,
    status        VARCHAR(24)  NOT NULL,
    input_json    TEXT,
    state_json    TEXT,
    cursor_node   VARCHAR(191),
    error_text    TEXT,
    attempt       INT          NOT NULL,
    version       INT          NOT NULL,
    created_at_ms BIGINT       NOT NULL,
    updated_at_ms BIGINT       NOT NULL,
    CONSTRAINT pk_run PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS run_checkpoint (
    run_id        VARCHAR(64)  NOT NULL,
    seq           INT          NOT NULL,
    node          VARCHAR(191) NOT NULL,
    state_json    TEXT,
    created_at_ms BIGINT       NOT NULL,
    CONSTRAINT pk_run_checkpoint PRIMARY KEY (run_id, seq)
);

CREATE TABLE IF NOT EXISTS run_approval (
    id              VARCHAR(64)  NOT NULL,
    run_id          VARCHAR(64)  NOT NULL,
    node            VARCHAR(191) NOT NULL,
    reason          TEXT,
    status          VARCHAR(24)  NOT NULL,
    requested_by    VARCHAR(191),
    decided_by      VARCHAR(191),
    comment_text    TEXT,
    requested_at_ms BIGINT       NOT NULL,
    decided_at_ms   BIGINT,
    CONSTRAINT pk_run_approval PRIMARY KEY (id)
);
