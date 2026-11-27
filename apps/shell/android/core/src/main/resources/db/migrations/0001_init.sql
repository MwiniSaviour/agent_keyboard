-- SPEC §16 data model, schema v1. Forward-only: never edit a shipped migration, add 000N_*.sql.
-- Runs inside one transaction on a SQLCipher database whose key lives in Android Keystore (§17).
-- `chunks_vec` (sqlite-vec) is created by the Part 3 migration that ships the extension.

CREATE TABLE agents (
  id                    TEXT PRIMARY KEY,
  kind                  TEXT NOT NULL CHECK (kind IN ('general', 'custom')),
  name                  TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 40),
  definition_json       TEXT NOT NULL,                -- full AgentDefinition (protocol shape)
  autonomy_ceiling      TEXT NOT NULL CHECK (autonomy_ceiling IN ('off', 'suggest', 'draft', 'auto')),
  version               INTEGER NOT NULL DEFAULT 1,
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL
) WITHOUT ROWID;

-- FR-AG-01: General can never be deleted.
CREATE TRIGGER agents_keep_general BEFORE DELETE ON agents
WHEN OLD.kind = 'general'
BEGIN
  SELECT RAISE(ABORT, 'general agent cannot be deleted');
END;

CREATE TABLE agent_resources (
  id          TEXT PRIMARY KEY,
  agent_id    TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('pdf', 'txt', 'md', 'docx', 'url', 'note')),
  bytes       INTEGER NOT NULL DEFAULT 0,
  status      TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'indexed', 'failed')),
  sha256      TEXT,
  created_at  INTEGER NOT NULL
) WITHOUT ROWID;
CREATE INDEX agent_resources_agent ON agent_resources(agent_id);

CREATE TABLE chunks (
  id           INTEGER PRIMARY KEY,
  resource_id  TEXT REFERENCES agent_resources(id) ON DELETE CASCADE,
  agent_id     TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  ord          INTEGER NOT NULL,
  text         TEXT NOT NULL,
  tokens       INTEGER NOT NULL
);
CREATE INDEX chunks_agent ON chunks(agent_id);

CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='chunks', content_rowid='id', tokenize='unicode61 remove_diacritics 2');
CREATE TRIGGER chunks_ai AFTER INSERT ON chunks BEGIN
  INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
END;
CREATE TRIGGER chunks_ad AFTER DELETE ON chunks BEGIN
  INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES ('delete', old.id, old.text);
END;
CREATE TRIGGER chunks_au AFTER UPDATE ON chunks BEGIN
  INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES ('delete', old.id, old.text);
  INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
END;

CREATE TABLE memory_facts (
  id          INTEGER PRIMARY KEY,
  agent_id    TEXT REFERENCES agents(id) ON DELETE CASCADE,  -- NULL = General/shared memory
  fact        TEXT NOT NULL,
  source      TEXT NOT NULL,                                 -- 'user' | 'inferred'
  confidence  REAL NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0 AND 1),
  created_at  INTEGER NOT NULL,
  last_used   INTEGER
);

CREATE TABLE style_profile (
  scope       TEXT PRIMARY KEY,                              -- 'global' | 'app:<pkg>' | 'contact:<id>'
  stats_json  TEXT NOT NULL,
  updated_at  INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE contacts (
  id           TEXT PRIMARY KEY,                             -- hash(package, conversation key)
  package      TEXT NOT NULL,
  display_name TEXT NOT NULL,
  created_at   INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE conversations (
  id           TEXT PRIMARY KEY,
  package      TEXT NOT NULL,
  title        TEXT NOT NULL,
  contact_id   TEXT REFERENCES contacts(id) ON DELETE SET NULL,
  last_at      INTEGER NOT NULL
) WITHOUT ROWID;

-- Opt-in capture only (SPEC §16). Text has already passed PrivacyFilter.redact.
CREATE TABLE messages (
  id               INTEGER PRIMARY KEY,
  conversation_id  TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  from_me          INTEGER NOT NULL CHECK (from_me IN (0, 1)),
  author           TEXT NOT NULL,
  text             TEXT NOT NULL,
  at               INTEGER NOT NULL
);
CREATE INDEX messages_conversation_at ON messages(conversation_id, at);

-- Agent × app × contact. '*' is the wildcard; the most specific row wins.
CREATE TABLE autonomy_policies (
  agent_id     TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  package      TEXT NOT NULL DEFAULT '*',
  contact_id   TEXT NOT NULL DEFAULT '*',
  level        TEXT NOT NULL CHECK (level IN ('off', 'suggest', 'draft', 'auto')),
  allow        INTEGER NOT NULL DEFAULT 1 CHECK (allow IN (0, 1)),
  background   INTEGER NOT NULL DEFAULT 0 CHECK (background IN (0, 1)),  -- FR-AU-09 RemoteInput opt-in
  signature    TEXT,                                                     -- FR-AU-07
  PRIMARY KEY (agent_id, package, contact_id)
) WITHOUT ROWID;

-- FR-AU-06: append-only; rows older than 90 days are purged by the maintenance job.
CREATE TABLE audit_log (
  id          INTEGER PRIMARY KEY,
  at          INTEGER NOT NULL,
  action      TEXT NOT NULL,
  agent_id    TEXT,
  package     TEXT,
  conversation_id TEXT,
  seen        TEXT,
  sent        TEXT,
  reason      TEXT,
  model       TEXT
);
CREATE INDEX audit_log_at ON audit_log(at);
CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log
BEGIN
  SELECT RAISE(ABORT, 'audit_log is append-only');
END;

CREATE TABLE clipboard_items (
  id          TEXT PRIMARY KEY,
  text        TEXT NOT NULL,
  pinned      INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
  at          INTEGER NOT NULL,
  expires_at  INTEGER                                  -- NULL when pinned (TTL otherwise)
) WITHOUT ROWID;
CREATE INDEX clipboard_items_at ON clipboard_items(at);

CREATE TABLE notes (
  id          TEXT PRIMARY KEY,
  text        TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
) WITHOUT ROWID;

-- Values are additionally encrypted per field with a Keystore key (SPEC §16 "field-level encryption").
CREATE TABLE profile_vault (
  key          TEXT PRIMARY KEY,                       -- autofill hint, e.g. 'emailAddress'
  value_enc    BLOB NOT NULL,
  updated_at   INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE model_registry (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL CHECK (kind IN ('llm', 'vlm', 'stt', 'tts', 'embed')),
  tier         TEXT NOT NULL CHECK (tier IN ('S', 'M', 'L')),
  path         TEXT NOT NULL,
  bytes        INTEGER NOT NULL,
  sha256       TEXT NOT NULL,
  installed_at INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE settings (
  key         TEXT PRIMARY KEY,
  value_json  TEXT NOT NULL,
  updated_at  INTEGER NOT NULL
) WITHOUT ROWID;
