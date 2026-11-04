<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Dompet koin demo. Bukan uang sungguhan / bukan payment gateway.
 */
class Wallet extends MY_Controller
{
    private const MAX_AMOUNT = 10000000;

    public function __construct()
    {
        parent::__construct();
        $this->load->model(['wallet_model', 'user_model', 'realtime_model']);
    }

    public function index(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $this->ok(['balance' => $this->wallet_model->balance((int) $user['id'])]);
    }

    public function topup(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();

        $amount = $this->int_param('amount');
        if ($amount < 1 || $amount > self::MAX_AMOUNT) {
            $this->fail('Amount must be 1-' . number_format(self::MAX_AMOUNT) . '.', 422, 'invalid_amount');
        }
        $balance = $this->wallet_model->topup((int) $user['id'], $amount);
        $this->ok(['balance' => $balance], 201);
    }

    public function transfer(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['to_user_id', 'amount']);

        $toId = $this->int_param('to_user_id');
        $amount = $this->int_param('amount');
        if ($toId === (int) $user['id']) {
            $this->fail('You cannot transfer to yourself.', 422, 'invalid_target');
        }
        if ($amount < 1 || $amount > self::MAX_AMOUNT) {
            $this->fail('Amount must be 1-' . number_format(self::MAX_AMOUNT) . '.', 422, 'invalid_amount');
        }
        $target = $this->user_model->find($toId);
        if (!$target || (int) $target['is_active'] !== 1) {
            $this->fail('User not found.', 404, 'not_found');
        }
        $note = trim($this->str_param('note'));
        if (mb_strlen($note) > 200) {
            $this->fail('Note is limited to 200 characters.', 422, 'note_too_long');
        }
        $balance = $this->wallet_model->transfer((int) $user['id'], $toId, $amount, $note !== '' ? $note : null);
        if ($balance === null) {
            $this->fail('Insufficient balance.', 422, 'insufficient_balance');
        }
        $this->realtime_model->push([$toId], 'wallet_received', [
            'amount' => $amount,
            'from' => user_dto($user),
        ]);
        $this->ok(['balance' => $balance], 201);
    }

    public function history(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $limit = $this->int_param('limit', 20);
        $txns = array_map(
            static function (array $t) use ($user): array {
                $direction = (int) ($t['from_user_id'] ?? 0) === (int) $user['id'] ? 'out' : 'in';
                return [
                    'id' => (int) $t['id'],
                    'kind' => $t['kind'],
                    'amount' => (int) $t['amount'],
                    'direction' => $direction,
                    'note' => $t['note'] ?? null,
                    'from' => $t['from_user_id'] !== null ? [
                        'id' => (int) $t['from_user_id'],
                        'username' => $t['from_username'] ?? null,
                        'display_name' => $t['from_display_name'] ?? null,
                    ] : null,
                    'to' => $t['to_user_id'] !== null ? [
                        'id' => (int) $t['to_user_id'],
                        'username' => $t['to_username'] ?? null,
                        'display_name' => $t['to_display_name'] ?? null,
                    ] : null,
                    'created_at' => $t['created_at'],
                ];
            },
            $this->wallet_model->history((int) $user['id'], $limit)
        );
        $this->ok([
            'balance' => $this->wallet_model->balance((int) $user['id']),
            'transactions' => $txns,
        ]);
    }
}
