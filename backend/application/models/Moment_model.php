<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Moment_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function find(int $id): ?array
    {
        return $this->store->one('SELECT * FROM moments WHERE id = ?', [$id]);
    }

    public function feed(array $authorIds, int $viewerId, ?int $beforeId, int $limit = 20): array
    {
        $limit = max(1, min(50, $limit));
        $placeholders = implode(',', array_fill(0, count($authorIds), '?'));
        // Placeholder order: liked_by_me viewer, author ids, optional before id.
        $params = array_merge(
            [$viewerId],
            array_map(static fn (int|string $id): int => (int) $id, $authorIds)
        );
        $where = "mo.deleted_at IS NULL AND mo.user_id IN ($placeholders)";
        if ($beforeId) {
            $where .= ' AND mo.id < ?';
            $params[] = $beforeId;
        }

        return $this->store->all(
            'SELECT mo.*, u.display_name AS author_display_name, u.username AS author_username,
                    u.avatar_url AS author_avatar_url,
                    (SELECT COUNT(*) FROM moment_likes l WHERE l.moment_id = mo.id) AS like_count,
                    (SELECT COUNT(*) FROM moment_comments c WHERE c.moment_id = mo.id) AS comment_count,
                    (SELECT COUNT(*) FROM moment_likes l2 WHERE l2.moment_id = mo.id AND l2.user_id = ?) AS liked_by_me
             FROM moments mo JOIN users u ON u.id = mo.user_id
             WHERE ' . $where . '
             ORDER BY mo.id DESC LIMIT ' . $limit,
            $params
        );
    }

    public function create(int $userId, string $body, array $media, ?int $resharedFromId = null): int
    {
        return $this->store->insert('moments', [
            'uuid' => uuid4(),
            'user_id' => $userId,
            'body' => $body,
            'media_json' => $media ? json_encode(array_values($media), JSON_UNESCAPED_SLASHES) : null,
            'reshared_from_id' => $resharedFromId,
            'created_at' => now_iso(),
        ]);
    }

    /**
     * Posting ulang momen lain (menyalin isi + atribusi penulis asli).
     */
    public function reshare(int $userId, array $source): int
    {
        $media = [];
        if (!empty($source['media_json'])) {
            $decoded = json_decode($source['media_json'], true);
            if (is_array($decoded)) {
                $media = $decoded;
            }
        }
        // Atribusi mengikuti rantai ke momen asli bila sumbernya sendiri hasil reshare.
        $originId = !empty($source['reshared_from_id']) ? (int) $source['reshared_from_id'] : (int) $source['id'];
        return $this->create($userId, (string) ($source['body'] ?? ''), $media, $originId);
    }

    /**
     * Tambahkan atribusi reshare: $row['reshared'] = null|{moment_id, author}.
     */
    public function withReshare(array $rows): array
    {
        $ids = [];
        foreach ($rows as $row) {
            if (!empty($row['reshared_from_id'])) {
                $ids[] = (int) $row['reshared_from_id'];
            }
        }
        $origins = [];
        if ($ids) {
            $placeholders = implode(',', array_fill(0, count(array_unique($ids)), '?'));
            foreach ($this->store->all(
                'SELECT m.*, u.display_name AS author_display_name, u.username AS author_username, u.avatar_url AS author_avatar_url
                 FROM moments m JOIN users u ON u.id = m.user_id
                 WHERE m.id IN (' . $placeholders . ')',
                array_values(array_unique($ids))
            ) as $origin) {
                $origins[(int) $origin['id']] = [
                    'moment_id' => (int) $origin['id'],
                    'author' => [
                        'id' => (int) $origin['user_id'],
                        'display_name' => $origin['author_display_name'] ?? null,
                        'username' => $origin['author_username'] ?? null,
                        'avatar_url' => $origin['author_avatar_url'] ?? null,
                    ],
                ];
            }
        }
        foreach ($rows as &$row) {
            $row['reshared'] = !empty($row['reshared_from_id'])
                ? ($origins[(int) $row['reshared_from_id']] ?? ['moment_id' => (int) $row['reshared_from_id'], 'author' => null])
                : null;
        }
        unset($row);
        return $rows;
    }

    // ------------------------------------------------------------- stories

    public function createStory(int $userId, string $body, array $media, int $ttlHours = 24): array
    {
        $now = time();
        $id = $this->store->insert('stories', [
            'uuid' => uuid4(),
            'user_id' => $userId,
            'body' => $body,
            'media_json' => $media ? json_encode(array_values($media), JSON_UNESCAPED_SLASHES) : null,
            'created_at' => gmdate('Y-m-d\TH:i:s\Z', $now),
            'expires_at' => gmdate('Y-m-d\TH:i:s\Z', $now + $ttlHours * 3600),
        ]);
        return $this->findStory($id);
    }

    public function findStory(int $id): ?array
    {
        return $this->store->one('SELECT * FROM stories WHERE id = ?', [$id]);
    }

    /**
     * @return array<int, array> story aktif milik user + teman, terbaru dulu
     */
    public function activeStories(array $authorIds): array
    {
        if (!$authorIds) {
            return [];
        }
        $now = gmdate('Y-m-d\TH:i:s\Z');
        $placeholders = implode(',', array_fill(0, count($authorIds), '?'));
        return $this->store->all(
            'SELECT s.*, u.display_name AS author_display_name, u.username AS author_username,
                    u.avatar_url AS author_avatar_url
             FROM stories s JOIN users u ON u.id = s.user_id
             WHERE s.deleted_at IS NULL AND s.expires_at > ? AND s.user_id IN (' . $placeholders . ')
             ORDER BY s.id DESC',
            array_merge([$now], array_values(array_map('intval', $authorIds)))
        );
    }

    public function deleteStory(int $id, int $userId): bool
    {
        $row = $this->store->one('SELECT * FROM stories WHERE id = ? AND user_id = ?', [$id, $userId]);
        if (!$row || $row['deleted_at'] !== null) {
            return false;
        }
        $this->store->update('stories', ['deleted_at' => now_iso()], 'id = ?', [$id]);
        return true;
    }

    public function softDelete(int $id, int $userId): bool
    {
        $row = $this->store->one('SELECT * FROM moments WHERE id = ? AND user_id = ?', [$id, $userId]);
        if (!$row || $row['deleted_at'] !== null) {
            return false;
        }
        $this->store->update('moments', ['deleted_at' => now_iso()], 'id = ?', [$id]);
        return true;
    }

    public function like(int $momentId, int $userId): void
    {
        try {
            $this->store->insert('moment_likes', [
                'moment_id' => $momentId,
                'user_id' => $userId,
                'created_at' => now_iso(),
            ]);
        } catch (Throwable $e) {
            // Already liked: unique constraint.
        }
    }

    public function unlike(int $momentId, int $userId): void
    {
        $this->store->delete('moment_likes', 'moment_id = ? AND user_id = ?', [$momentId, $userId]);
    }

    public function comments(int $momentId): array
    {
        return $this->store->all(
            'SELECT c.*, u.display_name, u.username, u.avatar_url, u.gender,
                    ru.display_name AS reply_to_display_name, ru.username AS reply_to_username
             FROM moment_comments c
             JOIN users u ON u.id = c.user_id
             LEFT JOIN users ru ON ru.id = c.reply_to_user_id
             WHERE c.moment_id = ? ORDER BY c.id ASC LIMIT 200',
            [$momentId]
        );
    }

    public function addComment(int $momentId, int $userId, string $body, ?int $parentId = null, ?int $replyToUserId = null): int
    {
        return $this->store->insert('moment_comments', [
            'moment_id' => $momentId,
            'user_id' => $userId,
            'parent_id' => $parentId,
            'reply_to_user_id' => $replyToUserId,
            'body' => $body,
            'created_at' => now_iso(),
        ]);
    }
}
