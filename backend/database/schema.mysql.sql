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
    google_sub VARCHAR(64) NULL,
    google_email VARCHAR(190) NULL,
    wallet_pin_hash VARCHAR(255) NULL,
    UNIQUE KEY uq_users_google_sub (google_sub),
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
    revoked_reason VARCHAR(16) NULL,
    last_used_at VARCHAR(32) NULL,
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
    message VARCHAR(160) NULL,
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
    description VARCHAR(300) NULL,
    avatar_url VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    direct_key VARCHAR(40) NULL,
    only_admins_can_send TINYINT(1) NOT NULL DEFAULT 0,
    only_admins_can_edit TINYINT(1) NOT NULL DEFAULT 0,
    only_admins_can_add TINYINT(1) NOT NULL DEFAULT 0,
    invite_link_enabled TINYINT(1) NOT NULL DEFAULT 1,
    invite_token VARCHAR(32) NULL,
    approve_new_members TINYINT(1) NOT NULL DEFAULT 0,
    disappearing_days INT NOT NULL DEFAULT 0,
    last_message_id BIGINT UNSIGNED NULL,
    last_message_at VARCHAR(32) NULL,
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_conversations_uuid (uuid),
    UNIQUE KEY uq_conversations_direct (direct_key),
    UNIQUE KEY uq_conversations_invite (invite_token),
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
    cleared_at VARCHAR(32) NULL,
    archived TINYINT(1) NOT NULL DEFAULT 0,
    pinned TINYINT(1) NOT NULL DEFAULT 0,
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
    forwarded_from_id BIGINT UNSIGNED NULL,
    mentions TEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'sent',
    revoked_at VARCHAR(32) NULL,
    edited_at VARCHAR(32) NULL,
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
    reshared_from_id BIGINT UNSIGNED NULL,
    audience VARCHAR(16) NOT NULL DEFAULT 'friends',
    edited_at VARCHAR(32) NULL,
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
    parent_id BIGINT UNSIGNED NULL,
    reply_to_user_id BIGINT UNSIGNED NULL,
    body VARCHAR(500) NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_comments_moment (moment_id, id),
    KEY idx_comments_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message_reactions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    message_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    emoji VARCHAR(16) NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reactions_msg_user (message_id, user_id),
    KEY idx_reactions_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_pins (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    message_id BIGINT UNSIGNED NOT NULL,
    pinned_by BIGINT UNSIGNED NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pins_conv_msg (conversation_id, message_id),
    KEY idx_pins_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS group_join_requests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_join_conv_user (conversation_id, user_id),
    KEY idx_join_requests_conversation (conversation_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS blocks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    blocked_user_id BIGINT UNSIGNED NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_blocks_pair (user_id, blocked_user_id),
    KEY idx_blocks_user (user_id),
    KEY idx_blocks_blocked (blocked_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS stories (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    uuid CHAR(36) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    body TEXT NULL,
    media_json TEXT NULL,
    style VARCHAR(24) NULL,
    created_at VARCHAR(32) NOT NULL,
    expires_at VARCHAR(32) NOT NULL,
    deleted_at VARCHAR(32) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_stories_uuid (uuid),
    KEY idx_stories_user (user_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS device_keys (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    public_key TEXT NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_device_user (user_id, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_keys (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    device_id VARCHAR(128) NOT NULL DEFAULT '',
    key_fp VARCHAR(64) NOT NULL DEFAULT '',
    wrapped_key TEXT NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_conv_user_device (conversation_id, user_id, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wallets (
    user_id BIGINT UNSIGNED NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wallet_txns (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    from_user_id BIGINT UNSIGNED NULL,
    to_user_id BIGINT UNSIGNED NULL,
    amount BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    note VARCHAR(200) NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_wallet_txns_from (from_user_id),
    KEY idx_wallet_txns_to (to_user_id)
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
    conversation_id BIGINT UNSIGNED NULL,
    kind VARCHAR(8) NOT NULL DEFAULT 'audio',
    status VARCHAR(16) NOT NULL DEFAULT 'ringing',
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    answered_at VARCHAR(32) NULL,
    ended_at VARCHAR(32) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_calls_uuid (uuid),
    KEY idx_calls_callee (callee_id, status),
    KEY idx_calls_conversation (conversation_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS call_participants (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    call_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'invited',
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_call_user (call_id, user_id),
    KEY idx_call_participants_call (call_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS password_resets (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at VARCHAR(32) NOT NULL,
    used_at VARCHAR(32) NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_password_resets_token (token_hash),
    KEY idx_password_resets_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reports (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    reporter_id BIGINT UNSIGNED NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(40) NOT NULL,
    details VARCHAR(500) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_reports_reporter (reporter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS story_views (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    story_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_story_views (story_id, user_id),
    KEY idx_story_views_story (story_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS starred_messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    conversation_id BIGINT UNSIGNED NOT NULL,
    message_id BIGINT UNSIGNED NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_starred (user_id, message_id),
    KEY idx_starred_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hidden_messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    message_id BIGINT UNSIGNED NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hidden (user_id, message_id),
    KEY idx_hidden_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friend_remarks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    friend_id BIGINT UNSIGNED NOT NULL,
    remark VARCHAR(40) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_remarks (user_id, friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wallet_requests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    requester_id BIGINT UNSIGNED NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    amount BIGINT NOT NULL,
    note VARCHAR(200) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at VARCHAR(32) NOT NULL,
    updated_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_wallet_requests_target (target_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS red_packets (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    uuid CHAR(36) NOT NULL,
    conversation_id BIGINT UNSIGNED NOT NULL,
    message_id BIGINT UNSIGNED NULL,
    sender_id BIGINT UNSIGNED NOT NULL,
    total_amount BIGINT NOT NULL,
    total_count INT NOT NULL,
    claimed_count INT NOT NULL DEFAULT 0,
    claimed_amount BIGINT NOT NULL DEFAULT 0,
    note VARCHAR(200) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at VARCHAR(32) NOT NULL,
    expires_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_red_packets_uuid (uuid),
    KEY idx_red_packets_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS red_packet_claims (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    packet_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    amount BIGINT NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_red_packet_claims (packet_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Kolom untuk database yang dibuat sebelum fitur chat lanjutan ada.
ALTER TABLE messages ADD COLUMN forwarded_from_id BIGINT UNSIGNED NULL;
ALTER TABLE messages ADD COLUMN mentions TEXT NULL;
ALTER TABLE messages ADD COLUMN edited_at VARCHAR(32) NULL;
ALTER TABLE conversation_members ADD COLUMN archived TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE conversations ADD COLUMN invite_token VARCHAR(32) NULL;
ALTER TABLE calls ADD COLUMN conversation_id BIGINT UNSIGNED NULL;
ALTER TABLE calls ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'audio';
ALTER TABLE moments ADD COLUMN reshared_from_id BIGINT UNSIGNED NULL;
ALTER TABLE users ADD COLUMN wallet_pin_hash VARCHAR(255) NULL;
ALTER TABLE tokens ADD COLUMN last_used_at VARCHAR(32) NULL;
ALTER TABLE friendships ADD COLUMN message VARCHAR(160) NULL;
ALTER TABLE conversations ADD COLUMN description VARCHAR(300) NULL;
ALTER TABLE conversation_members ADD COLUMN pinned TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE moments ADD COLUMN audience VARCHAR(16) NOT NULL DEFAULT 'friends';
ALTER TABLE moments ADD COLUMN edited_at VARCHAR(32) NULL;
ALTER TABLE calls ADD COLUMN answered_at VARCHAR(32) NULL;
ALTER TABLE stories ADD COLUMN style VARCHAR(24) NULL;
ALTER TABLE tokens ADD COLUMN revoked_reason VARCHAR(16) NULL;

CREATE TABLE IF NOT EXISTS comment_likes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    comment_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_comment_likes (comment_id, user_id),
    KEY idx_comment_likes_comment (comment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
