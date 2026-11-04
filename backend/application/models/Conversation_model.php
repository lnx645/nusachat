<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Conversation_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function find(int $id): ?array
    {
        return $this->store->one('SELECT * FROM conversations WHERE id = ?', [$id]);
    }

    public function findDirect(string $directKey): ?array
    {
        return $this->store->one('SELECT * FROM conversations WHERE direct_key = ?', [$directKey]);
    }

    public function createDirect(int $a, int $b): int
    {
        $now = now_iso();
        $this->store->begin();
        try {
            $id = $this->store->insert('conversations', [
                'uuid' => uuid4(),
                'type' => 'direct',
                'title' => null,
                'created_by' => $a,
                'direct_key' => min($a, $b) . ':' . max($a, $b),
                'created_at' => $now,
                'updated_at' => $now,
            ]);
            $this->store->insert('conversation_members', [
                'conversation_id' => $id, 'user_id' => $a, 'role' => 'member',
                'joined_at' => $now, 'last_read_message_id' => 0, 'muted' => 0,
            ]);
            $this->store->insert('conversation_members', [
                'conversation_id' => $id, 'user_id' => $b, 'role' => 'member',
                'joined_at' => $now, 'last_read_message_id' => 0, 'muted' => 0,
            ]);
            $this->store->commit();
            return $id;
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
    }

    public function createGroup(int $creatorId, string $title, array $memberIds): int
    {
        $now = now_iso();
        $memberIds = array_values(array_unique(array_map('intval', $memberIds)));
        $memberIds = array_filter(
            $memberIds,
            static fn (int $id): bool => $id > 0 && $id !== $creatorId
        );
        $memberIds[] = $creatorId;

        $this->store->begin();
        try {
            $id = $this->store->insert('conversations', [
                'uuid' => uuid4(),
                'type' => 'group',
                'title' => $title,
                'created_by' => $creatorId,
                'direct_key' => null,
                'created_at' => $now,
                'updated_at' => $now,
            ]);
            foreach ($memberIds as $memberId) {
                $this->store->insert('conversation_members', [
                    'conversation_id' => $id,
                    'user_id' => $memberId,
                    'role' => $memberId === $creatorId ? 'owner' : 'member',
                    'joined_at' => $now,
                    'last_read_message_id' => 0,
                    'muted' => 0,
                ]);
            }
            $this->store->commit();
            return $id;
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
    }

    public function forUser(int $id, int $userId): ?array
    {
        return $this->store->one(
            'SELECT c.*, m.last_read_message_id, m.role, m.muted, m.archived, m.pinned,
                    (SELECT COUNT(*) FROM messages msg
                     WHERE msg.conversation_id = c.id AND msg.id > m.last_read_message_id
                       AND msg.sender_id <> ? AND msg.revoked_at IS NULL AND msg.expires_at > ?
                       AND NOT EXISTS (SELECT 1 FROM hidden_messages h WHERE h.user_id = ? AND h.message_id = msg.id)) AS unread_count
             FROM conversations c
             JOIN conversation_members m ON m.conversation_id = c.id
             WHERE c.id = ? AND m.user_id = ?',
            [$userId, now_iso(), $userId, $id, $userId]
        );
    }

    public function listFor(int $userId, int $limit = 100, ?string $before = null): array
    {
        $params = [$userId, now_iso(), $userId, $userId];
        $where = 'm.user_id = ?';
        if ($before !== null && $before !== '') {
            $where .= ' AND COALESCE(c.last_message_at, c.created_at) < ?';
            $params[] = $before;
        }
        return $this->store->all(
            'SELECT c.*, m.last_read_message_id, m.role, m.muted, m.archived, m.pinned,
                    (SELECT COUNT(*) FROM messages msg
                     WHERE msg.conversation_id = c.id AND msg.id > m.last_read_message_id
                       AND msg.sender_id <> ? AND msg.revoked_at IS NULL AND msg.expires_at > ?
                       AND NOT EXISTS (SELECT 1 FROM hidden_messages h WHERE h.user_id = ? AND h.message_id = msg.id)) AS unread_count
             FROM conversations c
             JOIN conversation_members m ON m.conversation_id = c.id
             WHERE ' . $where . '
             ORDER BY m.pinned DESC, COALESCE(c.last_message_at, c.created_at) DESC
             LIMIT ' . max(1, min(200, $limit)),
            $params
        );
    }

    public function members(int $conversationId): array
    {
        return $this->store->all(
            'SELECT u.*, m.role, m.joined_at, m.last_read_message_id, m.muted
             FROM conversation_members m JOIN users u ON u.id = m.user_id
             WHERE m.conversation_id = ? ORDER BY m.role = ? DESC, u.display_name ASC',
            [$conversationId, 'owner']
        );
    }

    public function memberIds(int $conversationId): array
    {
        $rows = $this->store->all('SELECT user_id FROM conversation_members WHERE conversation_id = ?', [$conversationId]);
        return array_map('intval', array_column($rows, 'user_id'));
    }

    /** Semua user yang berbagi percakapan dengan user ini (untuk event presence). */
    public function peerUserIds(int $userId): array
    {
        $rows = $this->store->all(
            'SELECT DISTINCT m2.user_id FROM conversation_members m1 ' .
            'JOIN conversation_members m2 ON m2.conversation_id = m1.conversation_id ' .
            'WHERE m1.user_id = ? AND m2.user_id != ?',
            [$userId, $userId]
        );
        return array_map('intval', array_column($rows, 'user_id'));
    }

    public function isMember(int $conversationId, int $userId): bool
    {
        return (bool) $this->store->value(
            'SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?',
            [$conversationId, $userId]
        );
    }

    public function addMembers(int $conversationId, array $userIds): void
    {
        $now = now_iso();
        foreach (array_unique(array_map('intval', $userIds)) as $userId) {
            if ($userId <= 0 || $this->isMember($conversationId, $userId)) {
                continue;
            }
            $this->store->insert('conversation_members', [
                'conversation_id' => $conversationId,
                'user_id' => $userId,
                'role' => 'member',
                'joined_at' => $now,
                'last_read_message_id' => 0,
                'muted' => 0,
            ]);
        }
        $this->store->update('conversations', ['updated_at' => $now], 'id = ?', [$conversationId]);
    }

    public function removeMember(int $conversationId, int $userId): void
    {
        $this->store->delete(
            'conversation_members',
            'conversation_id = ? AND user_id = ?',
            [$conversationId, $userId]
        );
        $this->store->update('conversations', ['updated_at' => now_iso()], 'id = ?', [$conversationId]);
    }

    public function roleOf(int $conversationId, int $userId): ?string
    {
        $role = $this->store->value(
            'SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?',
            [$conversationId, $userId]
        );
        return $role !== null ? (string) $role : null;
    }

    public function isAdmin(int $conversationId, int $userId): bool
    {
        $row = $this->find($conversationId);
        if ($row && (int) $row['created_by'] === $userId) {
            return true;
        }
        $role = $this->roleOf($conversationId, $userId);
        return $role === 'owner' || $role === 'admin';
    }

    public function isOwner(int $conversationId, int $userId): bool
    {
        $row = $this->find($conversationId);
        if ($row && (int) $row['created_by'] === $userId) {
            return true;
        }
        return $this->roleOf($conversationId, $userId) === 'owner';
    }

    public function setRole(int $conversationId, int $userId, string $role): void
    {
        $this->store->update(
            'conversation_members',
            ['role' => $role],
            'conversation_id = ? AND user_id = ?',
            [$conversationId, $userId]
        );
    }

    public function memberCount(int $conversationId): int
    {
        return (int) $this->store->value(
            'SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?',
            [$conversationId]
        );
    }

    public function ensureInviteToken(int $conversationId): string
    {
        $existing = $this->store->value(
            'SELECT invite_token FROM conversations WHERE id = ?',
            [$conversationId]
        );
        if (is_string($existing) && $existing !== '') {
            return $existing;
        }
        $token = bin2hex(random_bytes(12));
        $this->store->update('conversations', ['invite_token' => $token, 'updated_at' => now_iso()], 'id = ?', [$conversationId]);
        return $token;
    }

    public function rotateInviteToken(int $conversationId): string
    {
        $token = bin2hex(random_bytes(12));
        $this->store->update('conversations', ['invite_token' => $token, 'updated_at' => now_iso()], 'id = ?', [$conversationId]);
        return $token;
    }

    public function findByInviteToken(string $token): ?array
    {
        if ($token === '') {
            return null;
        }
        return $this->store->one('SELECT * FROM conversations WHERE invite_token = ?', [$token]);
    }

    /**
     * @return array{status: string, request: array} status: joined|pending|member
     */
    public function requestJoin(int $conversationId, int $userId, bool $needsApproval): array
    {
        if ($this->isMember($conversationId, $userId)) {
            return ['status' => 'member', 'request' => []];
        }
        $existing = $this->store->one(
            'SELECT * FROM group_join_requests WHERE conversation_id = ? AND user_id = ?',
            [$conversationId, $userId]
        );
        $now = now_iso();
        if (!$needsApproval) {
            $this->addMembers($conversationId, [$userId]);
            if ($existing) {
                $this->store->update(
                    'group_join_requests',
                    ['status' => 'approved', 'updated_at' => $now],
                    'id = ?',
                    [(int) $existing['id']]
                );
            }
            return ['status' => 'joined', 'request' => []];
        }
        if ($existing && $existing['status'] === 'pending') {
            return ['status' => 'pending', 'request' => $existing];
        }
        if ($existing) {
            $this->store->update(
                'group_join_requests',
                ['status' => 'pending', 'updated_at' => $now],
                'id = ?',
                [(int) $existing['id']]
            );
            $request = $this->store->one('SELECT * FROM group_join_requests WHERE id = ?', [(int) $existing['id']]);
            return ['status' => 'pending', 'request' => $request ?? []];
        }
        $id = $this->store->insert('group_join_requests', [
            'conversation_id' => $conversationId,
            'user_id' => $userId,
            'status' => 'pending',
            'created_at' => $now,
            'updated_at' => $now,
        ]);
        $request = $this->store->one('SELECT * FROM group_join_requests WHERE id = ?', [$id]);
        return ['status' => 'pending', 'request' => $request ?? []];
    }

    /**
     * @return array<int, array> pending requests + user rows
     */
    public function pendingJoinRequests(int $conversationId): array
    {
        return $this->store->all(
            'SELECT r.*, u.username, u.display_name, u.avatar_url
             FROM group_join_requests r JOIN users u ON u.id = r.user_id
             WHERE r.conversation_id = ? AND r.status = ?
             ORDER BY r.id ASC',
            [$conversationId, 'pending']
        );
    }

    public function reviewJoinRequest(int $requestId, bool $approve): ?array
    {
        $request = $this->store->one('SELECT * FROM group_join_requests WHERE id = ?', [$requestId]);
        if (!$request || $request['status'] !== 'pending') {
            return null;
        }
        $now = now_iso();
        $this->store->update(
            'group_join_requests',
            ['status' => $approve ? 'approved' : 'rejected', 'updated_at' => $now],
            'id = ?',
            [$requestId]
        );
        if ($approve) {
            $this->addMembers((int) $request['conversation_id'], [(int) $request['user_id']]);
        }
        return $this->store->one('SELECT * FROM group_join_requests WHERE id = ?', [$requestId]);
    }

    public function markRead(int $conversationId, int $userId, int $messageId): void
    {
        $current = (int) $this->store->value(
            'SELECT last_read_message_id FROM conversation_members WHERE conversation_id = ? AND user_id = ?',
            [$conversationId, $userId]
        );
        if ($messageId > $current) {
            $this->store->update(
                'conversation_members',
                ['last_read_message_id' => $messageId],
                'conversation_id = ? AND user_id = ?',
                [$conversationId, $userId]
            );
        }
    }

    public function setLastMessage(int $conversationId, int $messageId, string $createdAt): void
    {
        $this->store->update(
            'conversations',
            ['last_message_id' => $messageId, 'last_message_at' => $createdAt, 'updated_at' => now_iso()],
            'id = ?',
            [$conversationId]
        );
    }

    /**
     * Tandai belum dibaca: mundurkan penanda baca ke sebelum message_id
     * (atau sebelum pesan terakhir bila kosong) agar unread_count > 0.
     */
    public function markUnread(int $conversationId, int $userId, int $messageId = 0): int
    {
        if ($messageId > 0) {
            $target = $messageId - 1;
        } else {
            $lastId = (int) $this->store->value(
                'SELECT COALESCE(MAX(id), 0) FROM messages WHERE conversation_id = ?',
                [$conversationId]
            );
            $target = max(0, $lastId - 1);
        }
        $this->store->update(
            'conversation_members',
            ['last_read_message_id' => $target],
            'conversation_id = ? AND user_id = ?',
            [$conversationId, $userId]
        );
        return $target;
    }

    public function setPinned(int $conversationId, int $userId, bool $pinned): void
    {
        $this->store->update(
            'conversation_members',
            ['pinned' => $pinned ? 1 : 0],
            'conversation_id = ? AND user_id = ?',
            [$conversationId, $userId]
        );
    }

    /**
     * Alihkan kepemilikan grup: target menjadi owner, owner lama jadi admin.
     */
    public function transferOwnership(int $conversationId, int $newOwnerId): void
    {
        $row = $this->find($conversationId);
        if (!$row) {
            return;
        }
        $oldOwner = (int) $row['created_by'];
        $this->store->begin();
        try {
            $this->store->update(
                'conversations',
                ['created_by' => $newOwnerId, 'updated_at' => now_iso()],
                'id = ?',
                [$conversationId]
            );
            $this->setRole($conversationId, $newOwnerId, 'owner');
            if ($oldOwner !== $newOwnerId && $this->isMember($conversationId, $oldOwner)) {
                $this->setRole($conversationId, $oldOwner, 'admin');
            }
            $this->store->commit();
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
    }
}
