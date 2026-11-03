-- NusaChat schema (MySQL 5.7+ / 8.x, utf8mb4)
-- The app also works on SQLite; see schema.sqlite.sql.

CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    uuid CHAR(36) NOT NULL,
    username VARCHAR(20) NOT NULL,
    phone VARCHAR(20) NULL,
    phone_hash CHAR(64) NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(40) NOT NULL,
    avatar_url VARCHAR(500) NULL,
    bio VARCHAR(200) NULL,
    gender VARCHAR(10) NULL,
    birthday CHAR(10) NULL,
    latitude DOUBLE NULL,
    longitude DOUBLE NULL,
    location_updated_at VARCHAR(32) NULL,
    discoverable TINYINT(1) NOT NULL DEFAULT 1,
    last_seen_at VARCHAR(32) NULL,
    token_version INT NOT NULL DEFAULT 0,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_uuid (uuid),
    UNIQUE KEY uq_users_username (username),
    UNIQUE KEY uq_users_phone (phone),
    UNIQUE KEY uq_users_phone_hash (phone_hash),
    KEY idx_users_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tokens (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    jti CHAR(32) NOT NULL,
    refresh_hash CHAR(64) NOT NULL,
    device VARCHAR(120) NULL,
    expires_at VARCHAR(32) NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    revoked_at VARCHAR(32) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tokens_jti (jti),
    KEY idx_tokens_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS contacts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    phone_hash CHAR(64) NOT NULL,
    name VARCHAR(100) NULL,
    matched_user_id BIGINT UNSIGNED NULL,
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_contacts_user_phone (user_id, phone_hash),
    KEY idx_contacts_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friendships (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    requester_id BIGINT UNSIGNED NOT NULL,
    addressee_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_friendships_pair (requester_id, addressee_id),
    KEY idx_friendships_addressee (addressee_id, status),
    KEY idx_friendships_requester (requester_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    uuid CHAR(36) NOT NULL,
    type VARCHAR(16) NOT NULL DEFAULT 'direct',
    title VARCHAR(60) NULL,
    avatar_url VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    direct_key VARCHAR(40) NULL,
    last_message_id BIGINT UNSIGNED NULL,
    last_message_at VARCHAR(32) NULL,
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_conversations_uuid (uuid),
    UNIQUE KEY uq_conversations_direct (direct_key),
    KEY idx_conversations_updated (last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_members (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'member',
    joined_at VARCHAR(32) NOT NULL,
    last_read_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    muted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_members_pair (conversation_id, user_id),
    KEY idx_members_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    uuid CHAR(36) NOT NULL,
    conversation_id BIGINT UNSIGNED NOT NULL,
    sender_id BIGINT UNSIGNED NOT NULL,
    type VARCHAR(16) NOT NULL DEFAULT 'text',
    body TEXT NULL,
    attachment_url VARCHAR(500) NULL,
    attachment_meta TEXT NULL,
    reply_to_id BIGINT UNSIGNED NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'sent',
    revoked_at VARCHAR(32) NULL,
    client_id VARCHAR(80) NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_messages_uuid (uuid),
    UNIQUE KEY uq_messages_client (sender_id, client_id),
    KEY idx_messages_conversation (conversation_id, id),
    KEY idx_messages_sender (sender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS moments (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    uuid CHAR(36) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    body TEXT NULL,
    media_json TEXT NULL,
    created_at VARCHAR(32) NOT NULL,
    deleted_at VARCHAR(32) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_moments_uuid (uuid),
    KEY idx_moments_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS moment_likes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    moment_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_moment_likes (moment_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS moment_comments (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    moment_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    body VARCHAR(500) NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_comments_moment (moment_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS realtime_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    type VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_events_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rate_limits (
    key_hash CHAR(64) NOT NULL,
    window_start BIGINT NOT NULL,
    count INT NOT NULL DEFAULT 1,
    PRIMARY KEY (key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS push_tokens (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    token VARCHAR(500) NOT NULL,
    platform VARCHAR(16) NOT NULL DEFAULT 'android',
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_push_user_token (user_id, token(255)),
    KEY idx_push_tokens_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS calls (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    uuid CHAR(36) NOT NULL,
    caller_id BIGINT UNSIGNED NOT NULL,
    callee_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ringing',
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    ended_at VARCHAR(32) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_calls_uuid (uuid),
    KEY idx_calls_callee (callee_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
