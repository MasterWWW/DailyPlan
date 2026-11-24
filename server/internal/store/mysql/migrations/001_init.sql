CREATE TABLE IF NOT EXISTS schema_migrations (
  version BIGINT NOT NULL PRIMARY KEY,
  applied_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS datasets (
  dataset_id VARCHAR(80) NOT NULL PRIMARY KEY,
  restore_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sync_state (
  dataset_id VARCHAR(80) NOT NULL PRIMARY KEY,
  last_seq BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_sync_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS tasks (
  dataset_id VARCHAR(80) NOT NULL,
  task_id VARCHAR(80) NOT NULL,
  title VARCHAR(500) NOT NULL,
  title_source VARCHAR(32) NOT NULL,
  created_on DATE NOT NULL,
  due_date DATE NOT NULL,
  due_time CHAR(5) NULL,
  priority VARCHAR(16) NOT NULL,
  completed TINYINT(1) NOT NULL,
  completed_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (dataset_id, task_id),
  KEY idx_tasks_incomplete (dataset_id, completed, due_date),
  CONSTRAINT fk_tasks_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS work_settings (
  dataset_id VARCHAR(80) NOT NULL PRIMARY KEY,
  workdays JSON NOT NULL,
  time_zone VARCHAR(80) NOT NULL,
  work_start CHAR(5) NOT NULL,
  work_end CHAR(5) NOT NULL,
  reminder_lead_minutes INT NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_settings_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS operations (
  op_id VARCHAR(80) NOT NULL PRIMARY KEY,
  dataset_id VARCHAR(80) NOT NULL,
  device_id VARCHAR(80) NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  receipt_json JSON NOT NULL,
  applied_at DATETIME(6) NOT NULL,
  KEY idx_operations_dataset (dataset_id, applied_at),
  CONSTRAINT fk_operations_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS changes (
  dataset_id VARCHAR(80) NOT NULL,
  seq BIGINT NOT NULL,
  change_type VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (dataset_id, seq),
  CONSTRAINT fk_changes_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS attachments (
  dataset_id VARCHAR(80) NOT NULL,
  attachment_id VARCHAR(80) NOT NULL,
  task_id VARCHAR(80) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  media_type VARCHAR(80) NOT NULL,
  byte_size BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  storage_key VARCHAR(255) NOT NULL,
  transcription_status VARCHAR(16) NULL,
  transcript TEXT NULL,
  transcription_error TEXT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (dataset_id, attachment_id),
  KEY idx_attachments_task (dataset_id, task_id),
  CONSTRAINT fk_attachments_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(dataset_id),
  CONSTRAINT fk_attachments_task FOREIGN KEY (dataset_id, task_id) REFERENCES tasks(dataset_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS transcription_jobs (
  dataset_id VARCHAR(80) NOT NULL,
  attachment_id VARCHAR(80) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  available_at DATETIME(6) NOT NULL,
  claimed_at DATETIME(6) NULL,
  last_error TEXT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (dataset_id, attachment_id),
  KEY idx_jobs_ready (dataset_id, status, available_at),
  CONSTRAINT fk_jobs_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(dataset_id),
  CONSTRAINT fk_jobs_attachment FOREIGN KEY (dataset_id, attachment_id) REFERENCES attachments(dataset_id, attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

