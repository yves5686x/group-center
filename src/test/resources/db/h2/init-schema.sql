-- H2-compatible schema for tests (mirrors db/mysql/init-schema.sql).
-- Differences from the MySQL version:
--   * no `group_center.` database prefix (H2 in-memory uses its default schema)
--   * no COMMENT clauses, no inline INDEX / UNIQUE KEY definitions
--   * indexes and the unique constraint are declared separately
-- The MySQL file stays the source of truth for production.

CREATE TABLE IF NOT EXISTS gpu_task_info
(
    id                           INT AUTO_INCREMENT PRIMARY KEY,
    server_name                  VARCHAR(255) NOT NULL,
    server_name_eng              VARCHAR(255) NOT NULL,
    task_id                      VARCHAR(255) NOT NULL,
    message_type                 VARCHAR(255) NOT NULL,
    task_type                    VARCHAR(255) NOT NULL,
    task_status                  VARCHAR(255) NOT NULL,
    task_user                    VARCHAR(255) NOT NULL,
    task_pid                     INT          NOT NULL,
    task_main_memory             INT          NOT NULL,
    gpu_usage_percent            FLOAT        NOT NULL,
    gpu_memory_usage_string      VARCHAR(255) NOT NULL,
    gpu_memory_free_string       VARCHAR(255) NOT NULL,
    gpu_memory_total_string      VARCHAR(255) NOT NULL,
    gpu_memory_percent           FLOAT        NOT NULL,
    task_gpu_id                  INT          NOT NULL,
    task_gpu_name                VARCHAR(255) NOT NULL,
    task_gpu_memory_gb           FLOAT        NOT NULL,
    task_gpu_memory_human        VARCHAR(255) NOT NULL,
    task_gpu_memory_max_gb       FLOAT        NOT NULL,
    is_multi_gpu                 BOOLEAN      NOT NULL,
    multi_device_local_rank      INT          NOT NULL,
    multi_device_world_size      INT          NOT NULL,
    top_python_pid               INT          NOT NULL,
    cuda_root                    VARCHAR(255) NOT NULL,
    cuda_version                 VARCHAR(255) NOT NULL,
    is_debug_mode                BOOLEAN      NOT NULL,
    task_start_time              BIGINT       NOT NULL,
    task_finish_time             BIGINT       NOT NULL,
    task_start_time_obj          TIMESTAMP    NOT NULL,
    task_finish_time_obj         TIMESTAMP    NOT NULL,
    task_running_time_string     VARCHAR(255) NOT NULL,
    task_running_time_in_seconds INT          NOT NULL,
    project_directory            VARCHAR(255) NOT NULL,
    project_name                 VARCHAR(255) NOT NULL,
    screen_session_name          VARCHAR(255) NOT NULL,
    py_file_name                 VARCHAR(255) NOT NULL,
    python_version               VARCHAR(255) NOT NULL,
    command_line                 CLOB         NOT NULL,
    conda_env_name               VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS project_subscription
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id     VARCHAR(255) NOT NULL,
    user_name_eng  VARCHAR(255) NOT NULL,
    user_name      VARCHAR(255) NOT NULL,
    status         VARCHAR(50)  NOT NULL DEFAULT 'pending',
    created_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_time TIMESTAMP    NULL,
    task_id        VARCHAR(255) NULL
);

CREATE INDEX IF NOT EXISTS idx_project_id_status ON project_subscription (project_id, status);
CREATE INDEX IF NOT EXISTS idx_user_name_eng_status ON project_subscription (user_name_eng, status);
CREATE INDEX IF NOT EXISTS idx_task_id ON project_subscription (task_id);
CREATE INDEX IF NOT EXISTS idx_created_time ON project_subscription (created_time);
CREATE INDEX IF NOT EXISTS idx_completed_time ON project_subscription (completed_time);
ALTER TABLE project_subscription ADD CONSTRAINT IF NOT EXISTS uk_project_user UNIQUE (project_id, user_name_eng);
