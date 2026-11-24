-- Frozen database schema of Chatbox shared service 0.1.17 (42fd93b).
CREATE TABLE config(instance TEXT PRIMARY KEY, upstream TEXT NOT NULL, launcher_id TEXT);
CREATE TABLE sources(base TEXT PRIMARY KEY);
CREATE TABLE rooms(id TEXT PRIMARY KEY, username TEXT NOT NULL, session TEXT NOT NULL UNIQUE);
CREATE TABLE devices(id TEXT PRIMARY KEY, digest BLOB NOT NULL UNIQUE, room TEXT NOT NULL REFERENCES rooms,
                     owner INTEGER NOT NULL CHECK(owner IN (0,1)));
CREATE TABLE codes(digest BLOB PRIMARY KEY, room TEXT REFERENCES rooms, expires INTEGER NOT NULL,
                   purpose TEXT NOT NULL CHECK(purpose IN ('create','invite','recover')),
                   CHECK((room IS NULL) = (purpose='create')));
CREATE UNIQUE INDEX one_room_code_per_purpose ON codes(room,purpose) WHERE room IS NOT NULL;
CREATE UNIQUE INDEX one_bootstrap ON codes((1)) WHERE room IS NULL;
CREATE TABLE turns(room TEXT NOT NULL REFERENCES rooms, seq INTEGER NOT NULL, id TEXT NOT NULL,
                   question TEXT NOT NULL, answer TEXT NOT NULL,
                   status TEXT NOT NULL CHECK(status IN ('running','complete','unknown')),
                   needs_review INTEGER NOT NULL DEFAULT 0,
                   PRIMARY KEY(room,seq), UNIQUE(room,id));
CREATE UNIQUE INDEX one_running ON turns(room) WHERE status='running';
