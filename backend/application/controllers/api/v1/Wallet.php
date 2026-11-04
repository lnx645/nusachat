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
        $this->load->model(['wallet_model', 'user_model', 'realtime_model', 'message_model', 'conversation_model']);
    }

    public function index(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $this->ok([
            'balance' => $this->wallet_model->balance((int) $user['id']),
            'has_pin' => $this->wallet_model->hasPin((int) $user['id']),
        ]);
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
        $this->assertPin((int) $user['id']);
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

    /**
     * Atur/ganti PIN dompet (4-6 digit). Bila sudah ada PIN, wajib old_pin.
     */
    public function set_pin(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['pin']);

        $pin = $this->str_param('pin');
        if (!preg_match('/^[0-9]{4,6}$/', $pin)) {
            $this->fail('PIN must be 4-6 digits.', 422, 'invalid_pin');
        }
        $me = (int) $user['id'];
        $this->checkPinThrottle($me);
        if ($this->wallet_model->hasPin($me)) {
            $old = $this->str_param('old_pin');
            if ($old === '' || !$this->wallet_model->verifyPin($me, $old)) {
                $this->fail('Current PIN is incorrect.', 401, 'wrong_pin');
            }
        }
        $this->wallet_model->setPin($me, $pin);
        $this->ok(['has_pin' => true]);
    }

    public function check_pin(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['pin']);
        $me = (int) $user['id'];
        $this->checkPinThrottle($me);
        if (!$this->wallet_model->hasPin($me)) {
            $this->fail('No PIN is set.', 422, 'pin_not_set');
        }
        if (!$this->wallet_model->verifyPin($me, $this->str_param('pin'))) {
            $this->fail('PIN is incorrect.', 401, 'wrong_pin');
        }
        $this->ok(['valid' => true]);
    }

    // ------------------------------------------------------------ tagih koin

    public function request_money(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['to_user_id', 'amount']);

        $toId = $this->int_param('to_user_id');
        $amount = $this->int_param('amount');
        if ($toId === (int) $user['id']) {
            $this->fail('You cannot request from yourself.', 422, 'invalid_target');
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
        $id = $this->wallet_model->createRequest((int) $user['id'], $toId, $amount, $note !== '' ? $note : null);
        $request = $this->presentRequest($this->wallet_model->findRequest($id), $user, $target);
        $this->realtime_model->push([$toId], 'wallet_requested', ['request' => $request]);
        push_notify([$toId], 'Tagihan koin', ($user['display_name'] ?? 'Seseorang') . ' menagih ' . number_format($amount) . ' koin', [
            'event' => 'wallet_requested',
            'request_id' => (string) $id,
        ]);
        $this->ok(['request' => $request], 201);
    }

    public function requests(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $lists = $this->wallet_model->requestsFor((int) $user['id']);
        $map = function (array $rows, bool $incoming): array {
            $out = [];
            foreach ($rows as $row) {
                $other = [
                    'id' => (int) ($incoming ? $row['requester_id'] : $row['target_id']),
                    'username' => $row['username'] ?? null,
                    'display_name' => $row['display_name'] ?? null,
                    'avatar_url' => $row['avatar_url'] ?? null,
                ];
                $out[] = [
                    'id' => (int) $row['id'],
                    'amount' => (int) $row['amount'],
                    'note' => $row['note'] ?? null,
                    'status' => $row['status'],
                    'created_at' => $row['created_at'],
                    'requester' => $incoming ? $other : user_dto($this->user_model->find((int) $row['requester_id'])),
                    'target' => $incoming ? user_dto($this->user_model->find((int) $row['target_id'])) : $other,
                ];
            }
            return $out;
        };
        $this->ok([
            'incoming' => $map($lists['incoming'], true),
            'outgoing' => $map($lists['outgoing'], false),
        ]);
    }

    public function request_pay(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['request_id']);

        $request = $this->wallet_model->findRequest($this->int_param('request_id'));
        if (!$request || (int) $request['target_id'] !== (int) $user['id']) {
            $this->fail('Request not found.', 404, 'not_found');
        }
        if ($request['status'] !== 'pending') {
            $this->fail('This request is no longer pending.', 422, 'already_resolved');
        }
        $this->assertPin((int) $user['id']);
        $result = $this->wallet_model->payRequest((int) $request['id'], (int) $user['id']);
        if ($result === 'insufficient') {
            $this->fail('Insufficient balance.', 422, 'insufficient_balance');
        }
        if ($result !== 'paid') {
            $this->fail('This request is no longer pending.', 422, 'already_resolved');
        }
        $requester = $this->user_model->find((int) $request['requester_id']);
        $this->realtime_model->push([(int) $request['requester_id']], 'wallet_request_paid', [
            'request_id' => (int) $request['id'],
            'amount' => (int) $request['amount'],
            'from' => user_dto($user),
        ]);
        $this->realtime_model->push([(int) $request['requester_id']], 'wallet_received', [
            'amount' => (int) $request['amount'],
            'from' => user_dto($user),
        ]);
        $this->ok(['paid' => true, 'balance' => $this->wallet_model->balance((int) $user['id'])]);
    }

    public function request_decline(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['request_id']);

        $request = $this->wallet_model->findRequest($this->int_param('request_id'));
        if (!$request || (int) $request['target_id'] !== (int) $user['id']) {
            $this->fail('Request not found.', 404, 'not_found');
        }
        if ($request['status'] !== 'pending') {
            $this->fail('This request is no longer pending.', 422, 'already_resolved');
        }
        $this->wallet_model->setRequestStatus((int) $request['id'], 'declined');
        $this->realtime_model->push([(int) $request['requester_id']], 'wallet_request_declined', [
            'request_id' => (int) $request['id'],
        ]);
        $this->ok(['declined' => true]);
    }

    public function request_cancel(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['request_id']);

        $request = $this->wallet_model->findRequest($this->int_param('request_id'));
        if (!$request || (int) $request['requester_id'] !== (int) $user['id']) {
            $this->fail('Request not found.', 404, 'not_found');
        }
        if ($request['status'] !== 'pending') {
            $this->fail('This request is no longer pending.', 422, 'already_resolved');
        }
        $this->wallet_model->setRequestStatus((int) $request['id'], 'cancelled');
        $this->ok(['cancelled' => true]);
    }

    // ----------------------------------------------------------- amplop merah

    public function redpacket_create(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['conversation_id', 'total', 'count']);

        if (empty($this->app['features']['redpackets'])) {
            $this->fail('Red packets are disabled.', 403, 'redpackets_disabled');
        }
        $convId = $this->int_param('conversation_id');
        $row = $this->conversation_model->forUser($convId, (int) $user['id']);
        if (!$row) {
            $this->fail('Conversation not found.', 404, 'not_found');
        }
        $total = $this->int_param('total');
        $count = $this->int_param('count');
        $memberCount = $this->conversation_model->memberCount($convId);
        if ($total < 1 || $total > self::MAX_AMOUNT) {
            $this->fail('Total must be 1-' . number_format(self::MAX_AMOUNT) . '.', 422, 'invalid_amount');
        }
        if ($count < 1 || $count > min(50, $memberCount)) {
            $this->fail('Count must be 1-' . min(50, $memberCount) . ' for this chat.', 422, 'invalid_count');
        }
        if ($total < $count) {
            $this->fail('Total must be at least 1 coin per claim.', 422, 'invalid_amount');
        }
        $note = trim($this->str_param('note'));
        if (mb_strlen($note) > 200) {
            $this->fail('Note is limited to 200 characters.', 422, 'note_too_long');
        }
        $this->assertPin((int) $user['id']);

        $packet = $this->wallet_model->createPacket($convId, (int) $user['id'], $total, $count, $note !== '' ? $note : null);
        if (!$packet) {
            $this->fail('Insufficient balance.', 422, 'insufficient_balance');
        }
        $clientId = $this->str_param('client_id');
        $result = $this->message_model->create([
            'conversation_id' => $convId,
            'sender_id' => (int) $user['id'],
            'type' => 'redpacket',
            'body' => $note !== '' ? $note : 'Amplop merah untukmu',
            'attachment_url' => null,
            'attachment_meta' => ['packet_id' => (int) $packet['id']],
            'reply_to_id' => null,
            'mentions' => [],
            'client_id' => $clientId !== '' ? $clientId : null,
            'member_ids' => $this->conversation_model->memberIds($convId),
        ]);
        $this->wallet_model->attachPacketMessage((int) $packet['id'], (int) $result['row']['id']);
        $rows = $this->message_model->withExtras([$result['row']], (int) $user['id']);
        $message = message_dto($rows[0]);
        $this->conversation_model->setLastMessage($convId, (int) $result['row']['id'], $result['row']['created_at']);
        $memberIds = $this->conversation_model->memberIds($convId);
        $this->realtime_model->push($memberIds, 'message', [
            'conversation_id' => $convId,
            'message' => $message,
        ]);
        $unmuted = array_values(array_diff($memberIds, [(int) $user['id']]));
        if ($unmuted) {
            push_notify($unmuted, (string) ($user['display_name'] ?? 'Amplop merah'), 'Mengirim amplop merah', [
                'event' => 'message',
                'conversation_id' => (string) $convId,
                'message_id' => (string) $result['row']['id'],
            ]);
        }
        $this->ok([
            'packet' => $this->presentPacket($this->wallet_model->findPacket((int) $packet['id']), (int) $user['id']),
            'message' => $message,
            'balance' => $this->wallet_model->balance((int) $user['id']),
        ], 201);
    }

    public function redpacket(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $packet = $this->wallet_model->findPacket($this->int_param('id'));
        if (!$packet) {
            $this->fail('Red packet not found.', 404, 'not_found');
        }
        if (!$this->conversation_model->isMember((int) $packet['conversation_id'], (int) $user['id'])) {
            $this->fail('Red packet not found.', 404, 'not_found');
        }
        $this->store->begin();
        try {
            $packet = $this->wallet_model->expirePacketIfNeeded($packet);
            $this->store->commit();
        } catch (Throwable $e) {
            $this->store->rollback();
            throw $e;
        }
        $this->ok(['packet' => $this->presentPacket($packet, (int) $user['id'])]);
    }

    public function redpacket_claim(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['packet_id']);

        $packet = $this->wallet_model->findPacket($this->int_param('packet_id'));
        if (!$packet) {
            $this->fail('Red packet not found.', 404, 'not_found');
        }
        if (!$this->conversation_model->isMember((int) $packet['conversation_id'], (int) $user['id'])) {
            $this->fail('Red packet not found.', 404, 'not_found');
        }
        $result = $this->wallet_model->claimPacket((int) $packet['id'], (int) $user['id']);
        if ($result['status'] === 'invalid') {
            $this->fail('You cannot claim this red packet.', 422, 'cannot_claim');
        }
        if ($result['status'] === 'expired') {
            $this->fail('This red packet has expired.', 422, 'expired');
        }
        if ($result['status'] === 'finished') {
            $this->fail('This red packet is empty.', 422, 'empty');
        }
        $fresh = $this->wallet_model->findPacket((int) $packet['id']);
        if ($result['status'] === 'claimed') {
            $this->realtime_model->push($this->conversation_model->memberIds((int) $packet['conversation_id']), 'redpacket_claimed', [
                'packet_id' => (int) $packet['id'],
                'conversation_id' => (int) $packet['conversation_id'],
                'message_id' => $fresh['message_id'] !== null ? (int) $fresh['message_id'] : null,
                'user' => user_dto($user),
                'amount' => $result['amount'],
                'claimed_count' => (int) $fresh['claimed_count'],
                'total_count' => (int) $fresh['total_count'],
                'status' => $fresh['status'],
            ]);
        }
        $this->ok([
            'status' => $result['status'],
            'amount' => $result['amount'],
            'balance' => $this->wallet_model->balance((int) $user['id']),
            'packet' => $this->presentPacket($fresh, (int) $user['id']),
        ]);
    }

    private function presentPacket(array $packet, int $viewerId): array
    {
        $claims = array_map(
            static fn (array $c): array => [
                'user' => [
                    'id' => (int) $c['user_id'],
                    'username' => $c['username'] ?? null,
                    'display_name' => $c['display_name'] ?? null,
                    'avatar_url' => $c['avatar_url'] ?? null,
                ],
                'amount' => (int) $c['amount'],
                'created_at' => $c['created_at'],
            ],
            $this->wallet_model->packetClaims((int) $packet['id'])
        );
        $myClaim = null;
        foreach ($claims as $claim) {
            if ($claim['user']['id'] === $viewerId) {
                $myClaim = $claim['amount'];
            }
        }
        $sender = $this->user_model->find((int) $packet['sender_id']);
        return [
            'id' => (int) $packet['id'],
            'conversation_id' => (int) $packet['conversation_id'],
            'message_id' => $packet['message_id'] !== null ? (int) $packet['message_id'] : null,
            'sender' => $sender ? user_dto($sender) : ['id' => (int) $packet['sender_id']],
            'total_amount' => (int) $packet['total_amount'],
            'total_count' => (int) $packet['total_count'],
            'claimed_count' => (int) $packet['claimed_count'],
            'claimed_amount' => (int) $packet['claimed_amount'],
            'note' => $packet['note'],
            'status' => $packet['status'],
            'my_claim' => $myClaim,
            'claims' => $claims,
            'created_at' => $packet['created_at'],
            'expires_at' => $packet['expires_at'],
        ];
    }

    private function presentRequest(array $request, array $requester, array $target): array
    {
        return [
            'id' => (int) $request['id'],
            'amount' => (int) $request['amount'],
            'note' => $request['note'] ?? null,
            'status' => $request['status'],
            'created_at' => $request['created_at'],
            'requester' => user_dto($requester),
            'target' => user_dto($target),
        ];
    }

    /**
     * Bila pengirim memasang PIN, semua pengeluaran wajib PIN yang benar.
     * Throttle 10x/5 mnt per user agar PIN 4-6 digit tidak bisa brute-force.
     */
    private function assertPin(int $userId): void
    {
        if (!$this->wallet_model->hasPin($userId)) {
            return;
        }
        $this->checkPinThrottle($userId);
        if (!$this->wallet_model->verifyPin($userId, $this->str_param('pin'))) {
            $this->fail('Wallet PIN is required.', 403, 'pin_required');
        }
    }

    private function checkPinThrottle(int $userId): void
    {
        if (!$this->throttle('pin:' . $userId, 10, 300)) {
            $this->fail('Too many wrong PIN attempts. Try again in a few minutes.', 429, 'rate_limited');
        }
    }
}
