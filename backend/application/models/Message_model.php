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
            'forwarded_from_id' => !empty($data['forwarded_from_id']) ? (int) $data['forwarded_from_id'] : null,
            'mentions' => !empty($data['mentions']) ? json_encode(array_values($data['mentions']), JSON_UNESCAPED_SLASHES) : null,
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

    /**
     * Ubah isi pesan teks/keterangan milik sendiri.
     */
    public function edit(int $id, int $senderId, string $body): bool
    {
        $row = $this->store->one('SELECT * FROM messages WHERE id = ? AND sender_id = ?', [$id, $senderId]);
        if (!$row || $row['revoked_at'] !== null) {
            return false;
        }
        if (time() - strtotime($row['created_at']) > 86400) {
            return false;
        }
        $this->store->update('messages', ['body' => $body, 'edited_at' => now_iso()], 'id = ?', [$id]);
        return true;
    }

    /**
     * Ringkasan reaksi per pesan: [{emoji, count, reacted_by_me}].
     *
     * @param int[] $messageIds
     * @return array<int, array<int, array{emoji: string, count: int, reacted_by_me: bool}>>
     */
    public function reactionsFor(array $messageIds, int $viewerId): array
    {
        $messageIds = array_values(array_unique(array_map('intval', $messageIds)));
        if (!$messageIds) {
            return [];
        }
        $placeholders = implode(',', array_fill(0, count($messageIds), '?'));
        $rows = $this->store->all(
            'SELECT message_id, emoji, user_id FROM message_reactions WHERE message_id IN (' . $placeholders . ')',
            $messageIds
        );
        $grouped = [];
        foreach ($rows as $row) {
            $mid = (int) $row['message_id'];
            $emoji = (string) $row['emoji'];
            if (!isset($grouped[$mid])) {
                $grouped[$mid] = [];
            }
            if (!isset($grouped[$mid][$emoji])) {
                $grouped[$mid][$emoji] = ['emoji' => $emoji, 'count' => 0, 'reacted_by_me' => false];
            }
            $grouped[$mid][$emoji]['count']++;
            if ((int) $row['user_id'] === $viewerId) {
                $grouped[$mid][$emoji]['reacted_by_me'] = true;
            }
        }
        $out = [];
        foreach ($grouped as $mid => $emojis) {
            $out[$mid] = array_values($emojis);
        }
        return $out;
    }

    /**
     * Toggle reaksi: emoji sama dicabut, emoji beda diganti.
     *
     * @return string 'added'|'removed'|'replaced'
     */
    public function setReaction(int $messageId, int $userId, string $emoji): string
    {
        $existing = $this->store->one(
            'SELECT * FROM message_reactions WHERE message_id = ? AND user_id = ?',
            [$messageId, $userId]
        );
        if ($existing) {
            if ($existing['emoji'] === $emoji) {
                $this->store->delete('message_reactions', 'message_id = ? AND user_id = ?', [$messageId, $userId]);
                return 'removed';
            }
            $this->store->update(
                'message_reactions',
                ['emoji' => $emoji, 'created_at' => now_iso()],
                'message_id = ? AND user_id = ?',
                [$messageId, $userId]
            );
            return 'replaced';
        }
        $this->store->insert('message_reactions', [
            'message_id' => $messageId,
            'user_id' => $userId,
            'emoji' => $emoji,
            'created_at' => now_iso(),
        ]);
        return 'added';
    }

    /**
     * Tambahkan reactions + mentioned_me ke baris pesan untuk satu viewer.
     */
    public function withExtras(array $rows, int $viewerId): array
    {
        if (!$rows) {
            return $rows;
        }
        $ids = array_map(static fn (array $r): int => (int) $r['id'], $rows);
        $reactions = $this->reactionsFor($ids, $viewerId);
        foreach ($rows as &$row) {
            $row['reactions'] = $reactions[(int) $row['id']] ?? [];
            $row['mentioned_me'] = false;
            if (!empty($row['mentions'])) {
                $decoded = json_decode($row['mentions'], true);
                if (is_array($decoded)) {
                    $row['mentioned_me'] = in_array($viewerId, array_map('intval', $decoded), true);
                }
            }
        }
        unset($row);
        return $rows;
    }

    /**
     * Cari pesan dalam satu percakapan (cocokkan isi, terbaru dulu).
     */
    public function search(int $conversationId, string $query, int $limit = 20): array
    {
        $limit = max(1, min(50, $limit));
        return $this->store->all(
            'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                    u.avatar_url AS sender_avatar_url
             FROM messages m JOIN users u ON u.id = m.sender_id
             WHERE m.conversation_id = ? AND m.body LIKE ?
             ORDER BY m.id DESC LIMIT ' . $limit,
            [$conversationId, '%' . $query . '%']
        );
    }

    /**
     * @return array<int, array> pesan yang disematkan + info penyemat
     */
    public function pins(int $conversationId): array
    {
        return $this->store->all(
            'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                    u.avatar_url AS sender_avatar_url, p.pinned_by, p.created_at AS pinned_at
             FROM conversation_pins p
             JOIN messages m ON m.id = p.message_id
             JOIN users u ON u.id = m.sender_id
             WHERE p.conversation_id = ?
             ORDER BY p.id DESC',
            [$conversationId]
        );
    }

    public function pin(int $conversationId, int $messageId, int $pinnedBy): void
    {
        $existing = $this->store->one(
            'SELECT id FROM conversation_pins WHERE conversation_id = ? AND message_id = ?',
            [$conversationId, $messageId]
        );
        if (!$existing) {
            $this->store->insert('conversation_pins', [
                'conversation_id' => $conversationId,
                'message_id' => $messageId,
                'pinned_by' => $pinnedBy,
                'created_at' => now_iso(),
            ]);
        }
    }

    public function unpin(int $conversationId, int $messageId): void
    {
        $this->store->delete(
            'conversation_pins',
            'conversation_id = ? AND message_id = ?',
            [$conversationId, $messageId]
        );
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
