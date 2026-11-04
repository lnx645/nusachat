<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Minimal voice-call records. SDP/ICE themselves travel through the
 * realtime outbox (low extra storage), this table only tracks call state.
 */
final class Call_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function create(int $callerId, int $calleeId, string $kind = 'audio', ?int $conversationId = null): array
    {
        $now = now_iso();
        $id = $this->store->insert('calls', [
            'uuid' => uuid4(),
            'caller_id' => $callerId,
            'callee_id' => $calleeId,
            'conversation_id' => $conversationId,
            'kind' => $kind,
            'status' => 'ringing',
            'created_at' => $now,
            'updated_at' => $now,
            'ended_at' => null,
        ]);
        return $this->find($id);
    }

    public function find(int $id): ?array
    {
        return $this->store->one('SELECT * FROM calls WHERE id = ?', [$id]);
    }

    public function setStatus(int $id, string $status): ?array
    {
        $now = now_iso();
        $fields = ['status' => $status, 'updated_at' => $now];
        if (in_array($status, ['ended', 'rejected', 'missed'], true)) {
            $fields['ended_at'] = $now;
        }
        $this->store->update('calls', $fields, 'id = ?', [$id]);
        return $this->find($id);
    }

    /** @return list<array> */
    public function ringingFor(int $userId, int $limit = 20): array
    {
        return $this->store->all(
            "SELECT * FROM calls WHERE callee_id = ? AND status = 'ringing' ORDER BY id DESC LIMIT " . max(1, min(50, $limit)),
            [$userId]
        );
    }

    /**
     * Room panggilan grup: satu baris calls (callee_id = 0) + partisipan.
     */
    public function createRoom(int $creatorId, int $conversationId, string $kind = 'audio'): array
    {
        $now = now_iso();
        $id = $this->store->insert('calls', [
            'uuid' => uuid4(),
            'caller_id' => $creatorId,
            'callee_id' => 0,
            'conversation_id' => $conversationId,
            'kind' => $kind,
            'status' => 'ringing',
            'created_at' => $now,
            'updated_at' => $now,
            'ended_at' => null,
        ]);
        $this->setParticipant($id, $creatorId, 'joined');
        return $this->find($id);
    }

    public function activeRoomFor(int $conversationId): ?array
    {
        return $this->store->one(
            "SELECT * FROM calls WHERE conversation_id = ? AND status IN ('ringing', 'active') ORDER BY id DESC LIMIT 1",
            [$conversationId]
        );
    }

    /** @return list<array> room yang masih bisa di-join (ringing/active) */
    public function openRoomsFor(int $userId): array
    {
        return $this->store->all(
            "SELECT c.* FROM calls c
             JOIN conversation_members m ON m.conversation_id = c.conversation_id
             WHERE m.user_id = ? AND c.conversation_id IS NOT NULL AND c.status IN ('ringing', 'active')
             ORDER BY c.id DESC LIMIT 20",
            [$userId]
        );
    }

    public function setParticipant(int $callId, int $userId, string $status): void
    {
        $now = now_iso();
        $existing = $this->store->one(
            'SELECT id FROM call_participants WHERE call_id = ? AND user_id = ?',
            [$callId, $userId]
        );
        if ($existing) {
            $this->store->update(
                'call_participants',
                ['status' => $status, 'updated_at' => $now],
                'id = ?',
                [(int) $existing['id']]
            );
            return;
        }
        $this->store->insert('call_participants', [
            'call_id' => $callId,
            'user_id' => $userId,
            'status' => $status,
            'created_at' => $now,
            'updated_at' => $now,
        ]);
    }

    public function participantStatus(int $callId, int $userId): ?string
    {
        $status = $this->store->value(
            'SELECT status FROM call_participants WHERE call_id = ? AND user_id = ?',
            [$callId, $userId]
        );
        return $status !== null ? (string) $status : null;
    }

    /** @return list<array> partisipan + profil ringkas */
    public function participants(int $callId): array
    {
        return $this->store->all(
            'SELECT p.*, u.username, u.display_name, u.avatar_url
             FROM call_participants p JOIN users u ON u.id = p.user_id
             WHERE p.call_id = ?
             ORDER BY p.id ASC',
            [$callId]
        );
    }

    /** @return int[] id user yang masih joined */
    public function joinedIds(int $callId): array
    {
        $rows = $this->store->all(
            "SELECT user_id FROM call_participants WHERE call_id = ? AND status = 'joined'",
            [$callId]
        );
        return array_map(static fn (array $r): int => (int) $r['user_id'], $rows);
    }

    /**
     * Riwayat: panggilan 1-1 milikku + room grup yang pernah kuikuti.
     *
     * @return list<array>
     */
    public function historyFor(int $userId, int $limit = 30, int $beforeId = 0): array
    {
        $limit = max(1, min(100, $limit));
        $params = [$userId, $userId, $userId];
        $where = '(c.caller_id = ? OR c.callee_id = ?
             OR EXISTS (SELECT 1 FROM call_participants p WHERE p.call_id = c.id AND p.user_id = ?))';
        if ($beforeId > 0) {
            $where .= ' AND c.id < ?';
            $params[] = $beforeId;
        }
        return $this->store->all(
            'SELECT c.*, g.title AS group_title FROM calls c
             LEFT JOIN conversations g ON g.id = c.conversation_id
             WHERE ' . $where . '
             ORDER BY c.id DESC LIMIT ' . $limit,
            $params
        );
    }
}
