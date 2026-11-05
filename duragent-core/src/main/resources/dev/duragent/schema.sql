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
