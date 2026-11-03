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
            'SELECT c.*, m.last_read_message_id, m.role, m.muted,
                    (SELECT COUNT(*) FROM messages msg
                     WHERE msg.conversation_id = c.id AND msg.id > m.last_read_message_id
                       AND msg.sender_id <> ? AND msg.revoked_at IS NULL) AS unread_count
             FROM conversations c
             JOIN conversation_members m ON m.conversation_id = c.id
             WHERE c.id = ? AND m.user_id = ?',
            [$userId, $id, $userId]
        );
    }

    public function listFor(int $userId, int $limit = 100): array
    {
        return $this->store->all(
            'SELECT c.*, m.last_read_message_id, m.role, m.muted,
                    (SELECT COUNT(*) FROM messages msg
                     WHERE msg.conversation_id = c.id AND msg.id > m.last_read_message_id
                       AND msg.sender_id <> ? AND msg.revoked_at IS NULL) AS unread_count
             FROM conversations c
             JOIN conversation_members m ON m.conversation_id = c.id
             WHERE m.user_id = ?
             ORDER BY COALESCE(c.last_message_at, c.created_at) DESC
             LIMIT ' . max(1, min(200, $limit)),
            [$userId, $userId]
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
}
