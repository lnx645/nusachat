<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Message_model extends CI_Model
{
    /** TTL antrean relay untuk pesan yang belum terkirim penuh (ala WhatsApp: 30 hari). */
    private const TTL_UNDELIVERED = 30 * 86400;
    /** Masa tenggang setelah semua penerima ack (jendela revoke/edit 24 jam). */
    private const GRACE_FULLY_ACKED = 86400;

    private static function isUniqueViolation(PDOException $e): bool
    {
        // SQLSTATE 23000: MySQL (1062) + SQLite (19) — keduanya integritas.
        return $e->getCode() === '23000'
            || str_contains($e->getMessage(), 'UNIQUE')
            || str_contains($e->getMessage(), 'Duplicate entry');
    }

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
        try {
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
                'expires_at' => gmdate('Y-m-d\TH:i:s\Z', time() + self::TTL_UNDELIVERED),
            ]);
        } catch (PDOException $e) {
            // Race kirim-ganda: cek-duplikat lolos di dua request bersamaan,
            // yang kalah menabrak UNIQUE(sender_id, client_id) — perlakukan
            // sebagai duplikat (200 + duplicate:true), bukan 500.
            if (!empty($data['client_id']) && self::isUniqueViolation($e)) {
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
            throw $e;
        }

        // Snapshot penerima saat kirim; pengirim langsung dianggap ack.
        // Anggota yang masuk belakangan tidak melihat pesan lama (ala WhatsApp).
        // Satu INSERT multi-baris (bukan N round-trip) — SQLite + MySQL sama.
        $memberIds = array_values(array_unique(array_map(
            'intval',
            (array) ($data['member_ids'] ?? [])
        )));
        if ($memberIds) {
            $placeholders = implode(',', array_fill(0, count($memberIds), '(?, ?, ?)'));
            $params = [];
            foreach ($memberIds as $memberId) {
                $params[] = $id;
                $params[] = $memberId;
                $params[] = $memberId === (int) $data['sender_id'] ? $now : null;
            }
            $this->store->run(
                'INSERT INTO message_recipients (message_id, user_id, acked_at) VALUES ' . $placeholders,
                $params
            );
        }

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

    /**
     * Daftar antrean relay untuk satu viewer: hanya pesan yang menargetkannya
     * dan belum kedaluwarsa. Riwayat permanen tinggal di perangkat (Room).
     */
    public function list(int $conversationId, ?int $beforeId, ?int $afterId, int $limit, int $viewerId): array
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
                 WHERE ' . $where . ' AND m.expires_at > ?
                   AND EXISTS (SELECT 1 FROM message_recipients r WHERE r.message_id = m.id AND r.user_id = ?)
                 ORDER BY m.id ASC LIMIT ' . $limit,
                array_merge($params, [now_iso(), $viewerId])
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
             WHERE ' . $where . ' AND m.expires_at > ?
               AND EXISTS (SELECT 1 FROM message_recipients r WHERE r.message_id = m.id AND r.user_id = ?)
             ORDER BY m.id DESC LIMIT ' . $limit,
            array_merge($params, [now_iso(), $viewerId])
        );
        return array_reverse($rows);
    }

    /**
     * Ack-sampai (up-to): tandai semua pesan milikku ≤ messageId sebagai
     * tersimpan di perangkat. Pesan yang semua penerimanya ack masuk masa
     * tenggang 24 jam lalu disapu penyapu relay (Erlang).
     *
     * @return int[] id pesan yang baru saja full-ack
     */
    public function ackUpTo(int $conversationId, int $userId, int $messageId): array
    {
        $now = now_iso();
        $this->store->run(
            'UPDATE message_recipients SET acked_at = ? WHERE user_id = ? AND acked_at IS NULL
             AND message_id IN (SELECT id FROM messages WHERE conversation_id = ? AND id <= ?)',
            [$now, $userId, $conversationId, $messageId]
        );
        $rows = $this->store->all(
            'SELECT m.id FROM messages m WHERE m.conversation_id = ? AND m.id <= ?
             AND NOT EXISTS (SELECT 1 FROM message_recipients r WHERE r.message_id = m.id AND r.acked_at IS NULL)',
            [$conversationId, $messageId]
        );
        $ids = array_map(static fn (array $r): int => (int) $r['id'], $rows);
        if ($ids) {
            $grace = gmdate('Y-m-d\TH:i:s\Z', time() + self::GRACE_FULLY_ACKED);
            $placeholders = implode(',', array_fill(0, count($ids), '?'));
            // Hanya persingkat masa berlaku, jangan perpanjang.
            $this->store->run(
                'UPDATE messages SET expires_at = ? WHERE id IN (' . $placeholders . ')
                 AND (expires_at IS NULL OR expires_at > ?)',
                array_merge([$grace], $ids, [$grace])
            );
        }
        return $ids;
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
                SELECT MAX(id) FROM messages
                WHERE conversation_id IN (' . $placeholders . ') AND expires_at > ?
                GROUP BY conversation_id
             )',
            array_merge(
                array_map(static fn (int|string $id): int => (int) $id, $conversationIds),
                [now_iso()]
            )
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
             WHERE m.conversation_id = ? AND m.body LIKE ? AND m.expires_at > ?
             ORDER BY m.id DESC LIMIT ' . $limit,
            [$conversationId, '%' . $query . '%', now_iso()]
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

    /**
     * Biru hanya bila SEMUA penerima sudah membaca. "Penerima" = snapshot
     * message_recipients saat kirim (bukan anggota kini): anggota yang
     * masuk belakangan tak memblokir pesan lama. 1-1 = perilaku lama.
     */
    public function markReadUpTo(int $conversationId, int $readerId, int $messageId): void
    {
        $this->store->run(
            'UPDATE messages SET status = ? WHERE conversation_id = ? AND sender_id <> ? AND id <= ? AND status <> ? ' .
            'AND NOT EXISTS (SELECT 1 FROM message_recipients r ' .
            'JOIN conversation_members cm ON cm.conversation_id = messages.conversation_id AND cm.user_id = r.user_id ' .
            'WHERE r.message_id = messages.id AND r.user_id <> messages.sender_id AND cm.last_read_message_id < messages.id)',
            ['read', $conversationId, $readerId, $messageId, 'read']
        );
    }

    /**
     * @return int[] id pesan (bukan milik reader, ≤ messageId) yang kini
     *               berstatus read — dikirim sebagai `fully_read' agar
     *               klien grup tak membirukan pesan yang baru dibaca satu
     *               anggota.
     */
    public function readIdsUpTo(int $conversationId, int $readerId, int $messageId): array
    {
        $rows = $this->store->all(
            'SELECT id FROM messages WHERE conversation_id = ? AND sender_id <> ? AND id <= ? AND status = ?',
            [$conversationId, $readerId, $messageId, 'read']
        );
        return array_map(static fn (array $r): int => (int) $r['id'], $rows);
    }

    /**
     * @param int $maxId batasi sampai id ini (0 = semua); pesan yang belum
     *                  diambil perangkat tak boleh terlanjur delivered.
     */
    public function markDelivered(int $conversationId, int $readerId, int $maxId = 0): void
    {
        if ($maxId > 0) {
            $this->store->run(
                'UPDATE messages SET status = ? WHERE conversation_id = ? AND sender_id <> ? AND id <= ? AND status = ?',
                ['delivered', $conversationId, $readerId, $maxId, 'sent']
            );
            return;
        }
        $this->store->run(
            'UPDATE messages SET status = ? WHERE conversation_id = ? AND sender_id <> ? AND status = ?',
            ['delivered', $conversationId, $readerId, 'sent']
        );
    }

    /**
     * Hapus untuk saya: sembunyikan pesan dari daftar viewer saja.
     */
    public function hide(int $messageId, int $userId): void
    {
        try {
            $this->store->insert('hidden_messages', [
                'user_id' => $userId,
                'message_id' => $messageId,
                'created_at' => now_iso(),
            ]);
        } catch (Throwable $e) {
            // Sudah disembunyikan: unique constraint.
        }
    }

    /** @return int[] id pesan yang disembunyikan viewer dari daftar ini */
    public function hiddenIds(int $userId, array $messageIds): array
    {
        $messageIds = array_values(array_unique(array_map('intval', $messageIds)));
        if (!$messageIds) {
            return [];
        }
        $placeholders = implode(',', array_fill(0, count($messageIds), '?'));
        $rows = $this->store->all(
            'SELECT message_id FROM hidden_messages WHERE user_id = ? AND message_id IN (' . $placeholders . ')',
            array_merge([$userId], $messageIds)
        );
        return array_map(static fn (array $r): int => (int) $r['message_id'], $rows);
    }

    public function star(int $conversationId, int $messageId, int $userId): void
    {
        try {
            $this->store->insert('starred_messages', [
                'user_id' => $userId,
                'conversation_id' => $conversationId,
                'message_id' => $messageId,
                'created_at' => now_iso(),
            ]);
        } catch (Throwable $e) {
            // Sudah dibintangi: unique constraint.
        }
    }

    public function unstar(int $messageId, int $userId): void
    {
        $this->store->delete('starred_messages', 'user_id = ? AND message_id = ?', [$userId, $messageId]);
    }

    /** @return array<int, array> pesan berbintang + judul percakapan */
    public function starred(int $userId, int $limit = 50): array
    {
        $limit = max(1, min(100, $limit));
        return $this->store->all(
            'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                    u.avatar_url AS sender_avatar_url, s.created_at AS starred_at,
                    c.type AS conversation_type, c.title AS conversation_title
             FROM starred_messages s
             JOIN messages m ON m.id = s.message_id
             JOIN users u ON u.id = m.sender_id
             JOIN conversations c ON c.id = s.conversation_id
             WHERE s.user_id = ?
             ORDER BY s.id DESC LIMIT ' . $limit,
            [$userId]
        );
    }

    /**
     * Cari pesan di semua percakapan milik viewer (terbaru dulu).
     *
     * @return array<int, array>
     */
    public function searchAll(int $userId, string $query, int $limit = 30): array
    {
        $limit = max(1, min(50, $limit));
        return $this->store->all(
            'SELECT m.*, u.display_name AS sender_display_name, u.username AS sender_username,
                    u.avatar_url AS sender_avatar_url,
                    c.type AS conversation_type, c.title AS conversation_title
             FROM messages m
             JOIN users u ON u.id = m.sender_id
             JOIN conversations c ON c.id = m.conversation_id
             JOIN conversation_members cm ON cm.conversation_id = m.conversation_id AND cm.user_id = ?
             WHERE m.body LIKE ? AND m.revoked_at IS NULL AND m.expires_at > ?
               AND NOT EXISTS (SELECT 1 FROM hidden_messages h WHERE h.user_id = ? AND h.message_id = m.id)
             ORDER BY m.id DESC LIMIT ' . $limit,
            [$userId, '%' . $query . '%', now_iso(), $userId]
        );
    }
}
