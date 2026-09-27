CREATE TABLE IF NOT EXISTS change_history (
  number            TEXT PRIMARY KEY,
  sys_id            TEXT,
  type              TEXT,
  category          TEXT,
  cmdb_ci           TEXT,
  assignment_group  TEXT,
  state             TEXT,
  close_code        TEXT,
  approval          TEXT,
  short_description TEXT,
  description       TEXT,
  record_json       TEXT NOT NULL,
  sys_updated_on    TEXT,
  synced_at         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_history_ci ON change_history(cmdb_ci);
CREATE INDEX IF NOT EXISTS idx_history_type ON change_history(type);
