<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Dompet koin demo (bilangan bulat, tanpa desimal). Transfer antar user,
 * topup dari sistem. Bukan uang sungguhan / bukan payment gateway.
 */
class Wallet_model extends CI_Model
{
    private Store $store;

    public function __construct()
    {
        parent::__construct();
        $this->store = get_instance()->store;
    }

    public function balance(int $userId): int
    {
        $balance = $this->store->value('SELECT balance FROM wallets WHERE user_id = ?', [$userId]);
        if ($balance === null) {
            $this->store->insert('wallets', [
                'user_id' => $userId,
                'balance' => 0,
                'updated_at' => now_iso(),
            ]);
            return 0;
        }
        return (int) $balance;
    }

    public function topup(int $userId, int $amount): int
    {
        $this->balance($userId);
        $now = now_iso();
        $this->store->run(
            'UPDATE wallets SET balance = balance + ?, updated_at = ? WHERE user_id = ?',
            [$amount, $now, $userId]
        );
        $this->store->insert('wallet_txns', [
            'from_user_id' => null,
            'to_user_id' => $userId,
            'amount' => $amount,
            'kind' => 'topup',
            'note' => null,
            'created_at' => $now,
        ]);
        return $this->balance($userId);
    }

    /**
     * @return int|null saldo pengirim baru, atau null bila saldo kurang
     */
    public function transfer(int $fromUserId, int $toUserId, int $amount, ?string $note): ?int
    {
        $this->balance($fromUserId);
        $this->balance($toUserId);
        $now = now_iso();
        $this->store->begin();
        try {
            if (!$this->moveFunds($fromUserId, $toUserId, $amount, 'transfer', $note, $now)) {
                $this->store->rollback();
                return null;
            }
            $this->store->commit();
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
        return $this->balance($fromUserId);
    }

    /**
     * Pindahkan dana antar dompet (harus di dalam transaksi aktif).
     *
     * @return bool false bila saldo kurang
     */
    private function moveFunds(int $fromUserId, int $toUserId, int $amount, string $kind, ?string $note, string $now): bool
    {
        $debit = $this->store->run(
            'UPDATE wallets SET balance = balance - ?, updated_at = ? WHERE user_id = ? AND balance >= ?',
            [$amount, $now, $fromUserId, $amount]
        );
        if ($debit->rowCount() !== 1) {
            return false;
        }
        $this->store->run(
            'UPDATE wallets SET balance = balance + ?, updated_at = ? WHERE user_id = ?',
            [$amount, $now, $toUserId]
        );
        $this->store->insert('wallet_txns', [
            'from_user_id' => $fromUserId,
            'to_user_id' => $toUserId,
            'amount' => $amount,
            'kind' => $kind,
            'note' => $note,
            'created_at' => $now,
        ]);
        return true;
    }

    // ------------------------------------------------------------------ PIN

    public function hasPin(int $userId): bool
    {
        $hash = $this->store->value('SELECT wallet_pin_hash FROM users WHERE id = ?', [$userId]);
        return is_string($hash) && $hash !== '';
    }

    public function setPin(int $userId, string $pin): void
    {
        $this->store->update(
            'users',
            ['wallet_pin_hash' => password_hash($pin, PASSWORD_DEFAULT), 'updated_at' => now_iso()],
            'id = ?',
            [$userId]
        );
    }

    public function verifyPin(int $userId, string $pin): bool
    {
        $hash = $this->store->value('SELECT wallet_pin_hash FROM users WHERE id = ?', [$userId]);
        if (!is_string($hash) || $hash === '') {
            return false;
        }
        return password_verify($pin, $hash);
    }

    // --------------------------------------------------------------- tagihan

    public function createRequest(int $requesterId, int $targetId, int $amount, ?string $note): int
    {
        $now = now_iso();
        return $this->store->insert('wallet_requests', [
            'requester_id' => $requesterId,
            'target_id' => $targetId,
            'amount' => $amount,
            'note' => $note,
            'status' => 'pending',
            'created_at' => $now,
            'updated_at' => $now,
        ]);
    }

    public function findRequest(int $id): ?array
    {
        return $this->store->one('SELECT * FROM wallet_requests WHERE id = ?', [$id]);
    }

    /** @return array{incoming: array, outgoing: array} */
    public function requestsFor(int $userId): array
    {
        $incoming = $this->store->all(
            'SELECT r.*, u.username, u.display_name, u.avatar_url
             FROM wallet_requests r JOIN users u ON u.id = r.requester_id
             WHERE r.target_id = ? AND r.status = ? ORDER BY r.id DESC LIMIT 50',
            [$userId, 'pending']
        );
        $outgoing = $this->store->all(
            'SELECT r.*, u.username, u.display_name, u.avatar_url
             FROM wallet_requests r JOIN users u ON u.id = r.target_id
             WHERE r.requester_id = ? AND r.status = ? ORDER BY r.id DESC LIMIT 50',
            [$userId, 'pending']
        );
        return ['incoming' => $incoming, 'outgoing' => $outgoing];
    }

    /**
     * Bayar tagihan (payer = target). Atomik + anti bayar ganda.
     *
     * @return string 'paid'|'insufficient'|'invalid'
     */
    public function payRequest(int $requestId, int $payerId): string
    {
        $request = $this->findRequest($requestId);
        if (!$request || (int) $request['target_id'] !== $payerId || $request['status'] !== 'pending') {
            return 'invalid';
        }
        $this->balance($payerId);
        $this->balance((int) $request['requester_id']);
        $now = now_iso();
        $this->store->begin();
        try {
            $fresh = $this->findRequest($requestId);
            if (!$fresh || $fresh['status'] !== 'pending') {
                $this->store->rollback();
                return 'invalid';
            }
            if (!$this->moveFunds($payerId, (int) $request['requester_id'], (int) $request['amount'], 'request', $request['note'], $now)) {
                $this->store->rollback();
                return 'insufficient';
            }
            $this->store->update(
                'wallet_requests',
                ['status' => 'paid', 'updated_at' => $now],
                'id = ? AND status = ?',
                [$requestId, 'pending']
            );
            $this->store->commit();
            return 'paid';
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
    }

    public function setRequestStatus(int $requestId, string $status): void
    {
        $this->store->update(
            'wallet_requests',
            ['status' => $status, 'updated_at' => now_iso()],
            'id = ?',
            [$requestId]
        );
    }

    // ---------------------------------------------------------- amplop merah

    /**
     * Buat amplop merah: debit pengirim + catat paket.
     *
     * @return array|null paket baru, atau null bila saldo kurang
     */
    public function createPacket(int $conversationId, int $senderId, int $total, int $count, ?string $note): ?array
    {
        $this->balance($senderId);
        $now = now_iso();
        $this->store->begin();
        try {
            $debit = $this->store->run(
                'UPDATE wallets SET balance = balance - ?, updated_at = ? WHERE user_id = ? AND balance >= ?',
                [$total, $now, $senderId, $total]
            );
            if ($debit->rowCount() !== 1) {
                $this->store->rollback();
                return null;
            }
            $this->store->insert('wallet_txns', [
                'from_user_id' => $senderId,
                'to_user_id' => null,
                'amount' => $total,
                'kind' => 'redpacket_send',
                'note' => $note,
                'created_at' => $now,
            ]);
            $id = $this->store->insert('red_packets', [
                'uuid' => uuid4(),
                'conversation_id' => $conversationId,
                'message_id' => null,
                'sender_id' => $senderId,
                'total_amount' => $total,
                'total_count' => $count,
                'claimed_count' => 0,
                'claimed_amount' => 0,
                'note' => $note,
                'status' => 'active',
                'created_at' => $now,
                'expires_at' => gmdate('Y-m-d\TH:i:s\Z', time() + 86400),
            ]);
            $this->store->commit();
            return $this->findPacket($id);
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
    }

    public function findPacket(int $id): ?array
    {
        return $this->store->one('SELECT * FROM red_packets WHERE id = ?', [$id]);
    }

    public function attachPacketMessage(int $packetId, int $messageId): void
    {
        $this->store->update('red_packets', ['message_id' => $messageId], 'id = ?', [$packetId]);
    }

    /** @return array<int, array> klaim + profil ringkas */
    public function packetClaims(int $packetId): array
    {
        return $this->store->all(
            'SELECT c.*, u.username, u.display_name, u.avatar_url
             FROM red_packet_claims c JOIN users u ON u.id = c.user_id
             WHERE c.packet_id = ? ORDER BY c.id ASC',
            [$packetId]
        );
    }

    /**
     * Klaim acak ala "luck draw": tiap klaim 1..batas wajar, total pas.
     *
     * @return array{status: string, amount: int} status: claimed|empty|finished|expired|already|invalid
     */
    public function claimPacket(int $packetId, int $userId): array
    {
        $this->balance($userId);
        $this->store->begin();
        try {
            $packet = $this->findPacket($packetId);
            if (!$packet) {
                $this->store->rollback();
                return ['status' => 'invalid', 'amount' => 0];
            }
            $packet = $this->expirePacketIfNeeded($packet);
            if ($packet['status'] === 'expired') {
                $this->store->commit();
                return ['status' => 'expired', 'amount' => 0];
            }
            if ($packet['status'] !== 'active') {
                $this->store->rollback();
                return ['status' => 'finished', 'amount' => 0];
            }
            if ((int) $packet['sender_id'] === $userId) {
                $this->store->rollback();
                return ['status' => 'invalid', 'amount' => 0];
            }
            $existing = $this->store->one(
                'SELECT * FROM red_packet_claims WHERE packet_id = ? AND user_id = ?',
                [$packetId, $userId]
            );
            if ($existing) {
                $this->store->rollback();
                return ['status' => 'already', 'amount' => (int) $existing['amount']];
            }
            $remaining = (int) $packet['total_amount'] - (int) $packet['claimed_amount'];
            $slots = (int) $packet['total_count'] - (int) $packet['claimed_count'];
            if ($slots <= 0 || $remaining <= 0) {
                $this->store->update('red_packets', ['status' => 'finished'], 'id = ?', [$packetId]);
                $this->store->commit();
                return ['status' => 'finished', 'amount' => 0];
            }
            $amount = $slots === 1
                ? $remaining
                : random_int(1, max(1, min($remaining - $slots + 1, (int) floor(2 * $remaining / $slots))));
            $now = now_iso();
            $this->store->insert('red_packet_claims', [
                'packet_id' => $packetId,
                'user_id' => $userId,
                'amount' => $amount,
                'created_at' => $now,
            ]);
            $claimedCount = (int) $packet['claimed_count'] + 1;
            $claimedAmount = (int) $packet['claimed_amount'] + $amount;
            $this->store->update(
                'red_packets',
                [
                    'claimed_count' => $claimedCount,
                    'claimed_amount' => $claimedAmount,
                    'status' => $claimedCount >= (int) $packet['total_count'] ? 'finished' : 'active',
                ],
                'id = ?',
                [$packetId]
            );
            $this->store->run(
                'UPDATE wallets SET balance = balance + ?, updated_at = ? WHERE user_id = ?',
                [$amount, $now, $userId]
            );
            $this->store->insert('wallet_txns', [
                'from_user_id' => (int) $packet['sender_id'],
                'to_user_id' => $userId,
                'amount' => $amount,
                'kind' => 'redpacket',
                'note' => $packet['note'],
                'created_at' => $now,
            ]);
            $this->store->commit();
            return ['status' => 'claimed', 'amount' => $amount];
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
    }

    /**
     * Kedaluwarsa malas: kembalikan sisa ke pengirim saat dibaca/diklaim
     * setelah 24 jam. Harus di dalam transaksi aktif.
     */
    public function expirePacketIfNeeded(array $packet): array
    {
        if ($packet['status'] !== 'active' || strtotime($packet['expires_at']) >= time()) {
            return $packet;
        }
        $remainder = (int) $packet['total_amount'] - (int) $packet['claimed_amount'];
        $now = now_iso();
        if ($remainder > 0) {
            $this->store->run(
                'UPDATE wallets SET balance = balance + ?, updated_at = ? WHERE user_id = ?',
                [$remainder, $now, (int) $packet['sender_id']]
            );
            $this->store->insert('wallet_txns', [
                'from_user_id' => null,
                'to_user_id' => (int) $packet['sender_id'],
                'amount' => $remainder,
                'kind' => 'redpacket_refund',
                'note' => $packet['note'],
                'created_at' => $now,
            ]);
        }
        $this->store->update('red_packets', ['status' => 'expired'], 'id = ?', [(int) $packet['id']]);
        $packet['status'] = 'expired';
        return $packet;
    }

    /** @return array<int, array> */
    public function history(int $userId, int $limit = 20): array
    {
        $limit = max(1, min(50, $limit));
        return $this->store->all(
            'SELECT t.*, fu.username AS from_username, fu.display_name AS from_display_name,
                    tu.username AS to_username, tu.display_name AS to_display_name
             FROM wallet_txns t
             LEFT JOIN users fu ON fu.id = t.from_user_id
             LEFT JOIN users tu ON tu.id = t.to_user_id
             WHERE t.from_user_id = ? OR t.to_user_id = ?
             ORDER BY t.id DESC LIMIT ' . $limit,
            [$userId, $userId]
        );
    }
}
