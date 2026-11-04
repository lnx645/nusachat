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
            $debit = $this->store->run(
                'UPDATE wallets SET balance = balance - ?, updated_at = ? WHERE user_id = ? AND balance >= ?',
                [$amount, $now, $fromUserId, $amount]
            );
            if ($debit->rowCount() !== 1) {
                $this->store->rollback();
                return null;
            }
            $this->store->run(
                'UPDATE wallets SET balance = balance + ?, updated_at = ? WHERE user_id = ?',
                [$amount, $now, $toUserId]
            );
            $this->store->insert('wallet_txns', [
                'from_user_id' => $fromUserId,
                'to_user_id' => $toUserId,
                'amount' => $amount,
                'kind' => 'transfer',
                'note' => $note,
                'created_at' => $now,
            ]);
            $this->store->commit();
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
        return $this->balance($fromUserId);
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
