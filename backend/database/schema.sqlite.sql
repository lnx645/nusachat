-- NusaChat schema (SQLite)
-- Timestamps are ISO-8601 UTC strings for portability with the MySQL schema.

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    username TEXT NOT NULL UNIQUE,
    phone TEXT UNIQUE,
    phone_hash TEXT UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    avatar_url TEXT,
    bio TEXT,
    gender TEXT,
    birthday TEXT,
    latitude REAL,
    longitude REAL,
    location_updated_at TEXT,
    discoverable INTEGER NOT NULL DEFAULT 1,
    last_seen_at TEXT,
    token_version INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    google_sub TEXT UNIQUE,
    google_email TEXT
);
CREATE INDEX IF NOT EXISTS idx_users_phone_hash ON users (phone_hash);
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users (last_seen_at);

CREATE TABLE IF NOT EXISTS tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    jti TEXT NOT NULL UNIQUE,
    refresh_hash TEXT NOT NULL,
    device TEXT,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens (user_id);

CREATE TABLE IF NOT EXISTS contacts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    phone_hash TEXT NOT NULL,
    name TEXT,
    matched_user_id INTEGER,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (user_id, phone_hash)
);
CREATE INDEX IF NOT EXISTS idx_contacts_user ON contacts (user_id);

CREATE TABLE IF NOT EXISTS friendships (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    requester_id INTEGER NOT NULL,
    addressee_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (requester_id, addressee_id)
);
CREATE INDEX IF NOT EXISTS idx_friendships_addressee ON friendships (addressee_id, status);
CREATE INDEX IF NOT EXISTS idx_friendships_requester ON friendships (requester_id, status);

CREATE TABLE IF NOT EXISTS conversations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL DEFAULT 'direct',
    title TEXT,
    avatar_url TEXT,
    created_by INTEGER NOT NULL,
    direct_key TEXT UNIQUE,
    only_admins_can_send INTEGER NOT NULL DEFAULT 0,
    only_admins_can_edit INTEGER NOT NULL DEFAULT 0,
    only_admins_can_add INTEGER NOT NULL DEFAULT 0,
    invite_link_enabled INTEGER NOT NULL DEFAULT 1,
    invite_token TEXT UNIQUE,
    approve_new_members INTEGER NOT NULL DEFAULT 0,
    disappearing_days INTEGER NOT NULL DEFAULT 0,
    last_message_id INTEGER,
    last_message_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations (last_message_at);

CREATE TABLE IF NOT EXISTS conversation_members (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    role TEXT NOT NULL DEFAULT 'member',
    joined_at TEXT NOT NULL,
    last_read_message_id INTEGER NOT NULL DEFAULT 0,
    muted INTEGER NOT NULL DEFAULT 0,
    cleared_at TEXT,
    archived INTEGER NOT NULL DEFAULT 0,
    UNIQUE (conversation_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_members_user ON conversation_members (user_id);

CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    conversation_id INTEGER NOT NULL,
    sender_id INTEGER NOT NULL,
    type TEXT NOT NULL DEFAULT 'text',
    body TEXT,
    attachment_url TEXT,
    attachment_meta TEXT,
    reply_to_id INTEGER,
    forwarded_from_id INTEGER,
    mentions TEXT,
    status TEXT NOT NULL DEFAULT 'sent',
    revoked_at TEXT,
    edited_at TEXT,
    client_id TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (sender_id, client_id)
);
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages (conversation_id, id);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages (sender_id);

CREATE TABLE IF NOT EXISTS moments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    user_id INTEGER NOT NULL,
    body TEXT,
    media_json TEXT,
    reshared_from_id INTEGER,
    created_at TEXT NOT NULL,
    deleted_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_moments_user ON moments (user_id, id);

CREATE TABLE IF NOT EXISTS moment_likes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    moment_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (moment_id, user_id)
);

CREATE TABLE IF NOT EXISTS moment_comments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    moment_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    parent_id INTEGER REFERENCES moment_comments(id) ON DELETE CASCADE,
    reply_to_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_comments_moment ON moment_comments (moment_id, id);

CREATE TABLE IF NOT EXISTS message_reactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    emoji TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_reactions_message ON message_reactions (message_id);

CREATE TABLE IF NOT EXISTS conversation_pins (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL,
    message_id INTEGER NOT NULL,
    pinned_by INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (conversation_id, message_id)
);
CREATE INDEX IF NOT EXISTS idx_pins_conversation ON conversation_pins (conversation_id);

CREATE TABLE IF NOT EXISTS group_join_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (conversation_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_join_requests_conversation ON group_join_requests (conversation_id, status);

CREATE TABLE IF NOT EXISTS blocks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    blocked_user_id INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (user_id, blocked_user_id)
);
CREATE INDEX IF NOT EXISTS idx_blocks_user ON blocks (user_id);
CREATE INDEX IF NOT EXISTS idx_blocks_blocked ON blocks (blocked_user_id);

CREATE TABLE IF NOT EXISTS stories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    user_id INTEGER NOT NULL,
    body TEXT,
    media_json TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    deleted_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_stories_user ON stories (user_id, expires_at);

CREATE TABLE IF NOT EXISTS device_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    device_id TEXT NOT NULL,
    public_key TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS conversation_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    device_id VARCHAR(128) NOT NULL DEFAULT '',
    key_fp VARCHAR(64) NOT NULL DEFAULT '',
    wrapped_key TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (conversation_id, user_id, device_id)
);

CREATE TABLE IF NOT EXISTS wallets (
    user_id INTEGER PRIMARY KEY,
    balance INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wallet_txns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    from_user_id INTEGER,
    to_user_id INTEGER,
    amount INTEGER NOT NULL,
    kind TEXT NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_wallet_txns_user ON wallet_txns (from_user_id, to_user_id);

CREATE TABLE IF NOT EXISTS realtime_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    type TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_user ON realtime_events (user_id, id);

CREATE TABLE IF NOT EXISTS rate_limits (
    key_hash TEXT NOT NULL PRIMARY KEY,
    window_start INTEGER NOT NULL,
    count INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS push_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    token TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT 'android',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (user_id, token)
);
CREATE INDEX IF NOT EXISTS idx_push_tokens_user ON push_tokens (user_id);

CREATE TABLE IF NOT EXISTS calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    caller_id INTEGER NOT NULL,
    callee_id INTEGER NOT NULL,
    conversation_id INTEGER,
    kind TEXT NOT NULL DEFAULT 'audio',
    status TEXT NOT NULL DEFAULT 'ringing',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    ended_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_calls_callee ON calls (callee_id, status);
CREATE INDEX IF NOT EXISTS idx_calls_conversation ON calls (conversation_id, status);

CREATE TABLE IF NOT EXISTS call_participants (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    call_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'invited',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (call_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_call_participants_call ON call_participants (call_id, status);

-- Kolom untuk database yang dibuat sebelum fitur chat lanjutan ada.
ALTER TABLE messages ADD COLUMN forwarded_from_id INTEGER;
ALTER TABLE messages ADD COLUMN mentions TEXT;
ALTER TABLE messages ADD COLUMN edited_at TEXT;
ALTER TABLE conversation_members ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversations ADD COLUMN invite_token TEXT;
ALTER TABLE calls ADD COLUMN conversation_id INTEGER;
ALTER TABLE calls ADD COLUMN kind TEXT NOT NULL DEFAULT 'audio';
ALTER TABLE moments ADD COLUMN reshared_from_id INTEGER;
