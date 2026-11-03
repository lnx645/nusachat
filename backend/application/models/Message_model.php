<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Message_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    /**
     * Insert a message. When client_id is supplied the operation is idempotent.
     *
     * @return array{row: array, duplicate: bool}
     */
    public function create(array $data): array
    {
        if (!empty($data['client_id'])) {
            $existing = $this->store->one(
                'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                        u.avatar_url AS sender_avatar_url
                 FROM messages m JOIN users u ON u.id = m.sender_id
                 WHERE m.sender_id = ? AND m.client_id = ?',
                [(int) $data['sender_id'], $data['client_id']]
            );
            if ($existing) {
                return ['row' => $existing, 'duplicate' => true];
            }
        }

        $now = now_iso();
        $id = $this->store->insert('messages', [
            'uuid' => uuid4(),
            'conversation_id' => (int) $data['conversation_id'],
            'sender_id' => (int) $data['sender_id'],
            'type' => $data['type'],
            'body' => $data['body'] ?? null,
            'attachment_url' => $data['attachment_url'] ?? null,
            'attachment_meta' => !empty($data['attachment_meta']) ? json_encode($data['attachment_meta'], JSON_UNESCAPED_SLASHES) : null,
            'reply_to_id' => !empty($data['reply_to_id']) ? (int) $data['reply_to_id'] : null,
            'status' => 'sent',
            'client_id' => $data['client_id'] ?? null,
            'created_at' => $now,
        ]);

        return ['row' => $this->find($id), 'duplicate' => false];
    }

    public function find(int $id): ?array
    {
        return $this->store->one(
            'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                    u.avatar_url AS sender_avatar_url
             FROM messages m JOIN users u ON u.id = m.sender_id
             WHERE m.id = ?',
            [$id]
        );
    }

    public function list(int $conversationId, ?int $beforeId, ?int $afterId, int $limit = 30): array
    {
        $limit = max(1, min(100, $limit));
        $params = [$conversationId];
        $where = 'm.conversation_id = ?';
        if ($afterId) {
            $where .= ' AND m.id > ?';
            $params[] = $afterId;
            $rows = $this->store->all(
                'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                        u.avatar_url AS sender_avatar_url
                 FROM messages m JOIN users u ON u.id = m.sender_id
                 WHERE ' . $where . ' ORDER BY m.id ASC LIMIT ' . $limit,
                $params
            );
            return $rows;
        }
        if ($beforeId) {
            $where .= ' AND m.id < ?';
            $params[] = $beforeId;
        }
        $rows = $this->store->all(
            'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                    u.avatar_url AS sender_avatar_url
             FROM messages m JOIN users u ON u.id = m.sender_id
             WHERE ' . $where . ' ORDER BY m.id DESC LIMIT ' . $limit,
            $params
        );
        return array_reverse($rows);
    }

    public function latestForConversations(array $conversationIds): array
    {
        if (!$conversationIds) {
            return [];
        }
        $placeholders = implode(',', array_fill(0, count($conversationIds), '?'));
        $rows = $this->store->all(
            'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                    u.avatar_url AS sender_avatar_url
             FROM messages m JOIN users u ON u.id = m.sender_id
             WHERE m.id IN (
                SELECT MAX(id) FROM messages WHERE conversation_id IN (' . $placeholders . ') GROUP BY conversation_id
             )',
            array_map(static fn (int|string $id): int => (int) $id, $conversationIds)
        );
        $byConversation = [];
        foreach ($rows as $row) {
            $byConversation[(int) $row['conversation_id']] = $row;
        }
        return $byConversation;
    }

    public function revoke(int $id, int $senderId): bool
    {
        $row = $this->store->one('SELECT * FROM messages WHERE id = ? AND sender_id = ?', [$id, $senderId]);
        if (!$row || $row['revoked_at'] !== null) {
            return false;
        }
        if (time() - strtotime($row['created_at']) > 86400) {
            return false;
        }
        $this->store->update('messages', ['revoked_at' => now_iso(), 'body' => null, 'attachment_url' => null, 'attachment_meta' => null], 'id = ?', [$id]);
        return true;
    }

    public function markReadUpTo(int $conversationId, int $readerId, int $messageId): void
    {
        $this->store->run(
            'UPDATE messages SET status = ? WHERE conversation_id = ? AND sender_id <> ? AND id <= ? AND status <> ?',
            ['read', $conversationId, $readerId, $messageId, 'read']
        );
    }

    public function markDelivered(int $conversationId, int $readerId): void
    {
        $this->store->run(
            'UPDATE messages SET status = ? WHERE conversation_id = ? AND sender_id <> ? AND status = ?',
            ['delivered', $conversationId, $readerId, 'sent']
        );
    }
}
