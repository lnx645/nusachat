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

    public function create(int $callerId, int $calleeId): array
    {
        $now = now_iso();
        $id = $this->store->insert('calls', [
            'uuid' => uuid4(),
            'caller_id' => $callerId,
            'callee_id' => $calleeId,
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
}
