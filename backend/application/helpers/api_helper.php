<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

if (!function_exists('uuid4')) {
    function uuid4(): string
    {
        $bytes = random_bytes(16);
        $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
        $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
        return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($bytes), 4));
    }
}

if (!function_exists('now_iso')) {
    function now_iso(): string
    {
        return gmdate('Y-m-d\TH:i:s\Z');
    }
}

if (!function_exists('iso_plus')) {
    function iso_plus(int $seconds): string
    {
        return gmdate('Y-m-d\TH:i:s\Z', time() + $seconds);
    }
}

if (!function_exists('normalize_phone')) {
    function normalize_phone(string $raw): ?string
    {
        $digits = preg_replace('/\D+/', '', $raw);
        if ($digits === '') {
            return null;
        }
        // Indonesian local format 08xx -> 628xx. Other countries should send
        // an international number (with or without a leading +).
        if (strlen($digits) >= 9 && str_starts_with($digits, '0')) {
            $digits = '62' . substr($digits, 1);
        }
        if (strlen($digits) < 7 || strlen($digits) > 15) {
            return null;
        }
        return $digits;
    }
}

if (!function_exists('phone_hash')) {
    function phone_hash(string $normalizedPhone): string
    {
        $secret = getenv('APP_SECRET') ?: 'dev-secret-change-me';
        return hash_hmac('sha256', 'phone:' . $normalizedPhone, $secret);
    }
}

if (!function_exists('client_ip')) {
    function client_ip(): string
    {
        foreach (['HTTP_CF_CONNECTING_IP', 'HTTP_X_FORWARDED_FOR', 'REMOTE_ADDR'] as $key) {
            if (!empty($_SERVER[$key])) {
                $value = trim(explode(',', $_SERVER[$key])[0]);
                if (filter_var($value, FILTER_VALIDATE_IP)) {
                    return $value;
                }
            }
        }
        return '0.0.0.0';
    }
}

if (!function_exists('absolute_upload_url')) {
    /**
     * Build an absolute URL for a stored upload.
     *
     * APP_URL is honoured when it points at a real public host. When it is
     * missing or still contains a loopback host (the development default),
     * the current request host is used instead. That keeps upload URLs usable
     * from an emulator or a physical device that reaches the API over LAN,
     * without hard-coding the machine's IP address.
     */
    function absolute_upload_url(string $relativePath): string
    {
        $relativePath = '/' . ltrim($relativePath, '/');
        $configured = trim((string) getenv('APP_URL'));

        if ($configured !== '') {
            $configuredHost = parse_url($configured, PHP_URL_HOST);
            $loopback = $configuredHost === null || in_array(
                strtolower($configuredHost),
                ['localhost', '127.0.0.1', '::1'],
                true
            );
            if (!$loopback) {
                return rtrim($configured, '/') . $relativePath;
            }
        }

        $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
        $host = $_SERVER['HTTP_HOST'] ?? 'localhost:8080';
        return $scheme . '://' . $host . $relativePath;
    }
}

if (!function_exists('user_dto')) {
    function user_dto(array $row, bool $self = false): array
    {
        $lastSeen = $row['last_seen_at'] ?? null;
        $online = false;
        if ($lastSeen) {
            $online = (time() - strtotime($lastSeen)) < 90;
        }
        $dto = [
            'id' => (int) $row['id'],
            'uuid' => $row['uuid'] ?? null,
            'username' => $row['username'] ?? null,
            'display_name' => $row['display_name'] ?? null,
            'avatar_url' => $row['avatar_url'] ?? null,
            'bio' => $row['bio'] ?? null,
            'gender' => $row['gender'] ?? null,
            'online' => $online,
            'last_seen_at' => $lastSeen,
        ];
        if ($self) {
            $dto['phone'] = $row['phone'] ?? null;
            $dto['birthday'] = $row['birthday'] ?? null;
            $dto['discoverable'] = (bool) ($row['discoverable'] ?? true);
            $dto['created_at'] = $row['created_at'] ?? null;
        }
        return $dto;
    }
}

if (!function_exists('message_dto')) {
    function message_dto(array $row): array
    {
        $meta = null;
        if (!empty($row['attachment_meta'])) {
            $decoded = json_decode($row['attachment_meta'], true);
            if (is_array($decoded)) {
                $meta = $decoded;
            }
        }
        return [
            'id' => (int) $row['id'],
            'uuid' => $row['uuid'],
            'conversation_id' => (int) $row['conversation_id'],
            'sender' => [
                'id' => (int) $row['sender_id'],
                'display_name' => $row['sender_display_name'] ?? null,
                'username' => $row['sender_username'] ?? null,
                'avatar_url' => $row['sender_avatar_url'] ?? null,
            ],
            'type' => $row['type'],
            'body' => $row['revoked_at'] ? null : $row['body'],
            'attachment_url' => $row['revoked_at'] ? null : $row['attachment_url'],
            'attachment_meta' => $row['revoked_at'] ? null : $meta,
            'reply_to_id' => $row['reply_to_id'] !== null ? (int) $row['reply_to_id'] : null,
            'client_id' => $row['client_id'] ?? null,
            'status' => $row['status'],
            'revoked' => $row['revoked_at'] !== null,
            'created_at' => $row['created_at'],
        ];
    }
}

if (!function_exists('conversation_dto')) {
    function conversation_dto(array $row, array $members = []): array
    {
        return [
            'id' => (int) $row['id'],
            'uuid' => $row['uuid'],
            'type' => $row['type'],
            'title' => $row['title'],
            'avatar_url' => $row['avatar_url'],
            'created_by' => (int) $row['created_by'],
            'last_message' => isset($row['last_message']) ? $row['last_message'] : null,
            'last_message_at' => $row['last_message_at'],
            'unread_count' => (int) ($row['unread_count'] ?? 0),
            'role' => $row['role'] ?? 'member',
            'muted' => (bool) ($row['muted'] ?? false),
            'members' => $members,
            'created_at' => $row['created_at'],
        ];
    }
}

if (!function_exists('moment_dto')) {
    function moment_dto(array $row): array
    {
        $media = [];
        if (!empty($row['media_json'])) {
            $decoded = json_decode($row['media_json'], true);
            if (is_array($decoded)) {
                $media = $decoded;
            }
        }
        return [
            'id' => (int) $row['id'],
            'uuid' => $row['uuid'],
            'author' => [
                'id' => (int) $row['user_id'],
                'display_name' => $row['author_display_name'] ?? null,
                'username' => $row['author_username'] ?? null,
                'avatar_url' => $row['author_avatar_url'] ?? null,
            ],
            'body' => $row['body'],
            'media' => $media,
            'like_count' => (int) ($row['like_count'] ?? 0),
            'comment_count' => (int) ($row['comment_count'] ?? 0),
            'liked_by_me' => (bool) ($row['liked_by_me'] ?? false),
            'created_at' => $row['created_at'],
        ];
    }
}

if (!function_exists('push_notify')) {
    /**
     * Best-effort FCM fan-out. Never throws; push is auxiliary to the
     * realtime websocket. Skips users without registered tokens.
     */
    function push_notify(array $userIds, string $title, string $body, array $data = []): void
    {
        try {
            $ci = get_instance();
            $ids = array_values(array_unique(array_map('intval', $userIds)));
            if (!$ids) {
                return;
            }
            $placeholders = implode(',', array_fill(0, count($ids), '?'));
            $rows = $ci->store->all(
                "SELECT token FROM push_tokens WHERE user_id IN ({$placeholders})",
                $ids
            );
            $tokens = [];
            foreach ($rows as $row) {
                if (!empty($row['token'])) {
                    $tokens[] = (string) $row['token'];
                }
            }
            $tokens = array_values(array_unique($tokens));
            if (!$tokens) {
                return;
            }
            $ci->load->library('fcm');
            $ci->fcm->sendSilent($tokens, $title, $body, $data);
        } catch (Throwable) {
            // Never break the API response because of push.
        }
    }
}
