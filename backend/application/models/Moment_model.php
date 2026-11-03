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

    public function create(int $userId, string $body, array $media): int
    {
        return $this->store->insert('moments', [
            'uuid' => uuid4(),
            'user_id' => $userId,
            'body' => $body,
            'media_json' => $media ? json_encode(array_values($media), JSON_UNESCAPED_SLASHES) : null,
            'created_at' => now_iso(),
        ]);
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
            'SELECT c.*, u.display_name, u.username, u.avatar_url
             FROM moment_comments c JOIN users u ON u.id = c.user_id
             WHERE c.moment_id = ? ORDER BY c.id ASC LIMIT 200',
            [$momentId]
        );
    }

    public function addComment(int $momentId, int $userId, string $body): int
    {
        return $this->store->insert('moment_comments', [
            'moment_id' => $momentId,
            'user_id' => $userId,
            'body' => $body,
            'created_at' => now_iso(),
        ]);
    }
}
