<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Voice-call signaling over the existing realtime outbox + FCM wake-up.
 *
 * SDP/ICE are relayed verbatim; the server never inspects media.
 * Audio itself is peer-to-peer via WebRTC. Short-lived Cloudflare TURN
 * credentials are issued per client via ice_servers (docs/TURN.md).
 */
class Calls extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['call_model', 'user_model', 'friend_model', 'realtime_model', 'push_model', 'conversation_model']);
        $this->load->library('fcm');
    }

    private function fcm(): Fcm
    {
        return new Fcm(
            $this->app['fcm_server_key'] ?? '',
            $this->app['fcm_service_json'] ?? '',
            $this->app['fcm_project_id'] ?? ''
        );
    }

    private function call_dto(array $row): array
    {
        return [
            'id' => (int) $row['id'],
            'uuid' => $row['uuid'],
            'caller_id' => (int) $row['caller_id'],
            'callee_id' => (int) $row['callee_id'],
            'conversation_id' => isset($row['conversation_id']) && $row['conversation_id'] !== null ? (int) $row['conversation_id'] : null,
            'kind' => $row['kind'] ?? 'audio',
            'status' => $row['status'],
            'created_at' => $row['created_at'],
            'updated_at' => $row['updated_at'],
            'answered_at' => $row['answered_at'] ?? null,
            'ended_at' => $row['ended_at'] ?? null,
        ];
    }

    private function participants_dto(int $callId): array
    {
        return array_map(
            static fn (array $p): array => [
                'user' => [
                    'id' => (int) $p['user_id'],
                    'username' => $p['username'] ?? null,
                    'display_name' => $p['display_name'] ?? null,
                    'avatar_url' => $p['avatar_url'] ?? null,
                ],
                'status' => $p['status'],
            ],
            $this->call_model->participants($callId)
        );
    }

    private function notify(int $userId, string $event, array $data, string $title, string $body): void
    {
        $this->realtime_model->push([$userId], $event, $data);
        $tokens = $this->push_model->tokensFor($userId);
        // FCM hanya membawa skalar: ratakan id/kind/peer agar klien mati pun
        // bisa menampilkan notif panggilan + tombol Jawab/Tolak.
        $flat = ['event' => $event];
        foreach ($data as $k => $v) {
            if (is_scalar($v) || $v === null) {
                $flat[$k] = $v === null ? '' : (string) $v;
            }
        }
        if (isset($data['call']) && is_array($data['call'])) {
            if (isset($data['call']['id'])) {
                $flat['call_id'] = (string) $data['call']['id'];
            }
            if (isset($data['call']['kind'])) {
                $flat['kind'] = (string) $data['call']['kind'];
            }
        }
        if (isset($data['from']) && is_array($data['from']) && isset($data['from']['display_name'])) {
            $flat['peer'] = (string) $data['from']['display_name'];
        }
        $this->fcm()->sendSilent($tokens, $title, $body, $flat);
    }

    public function offer(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['to_user_id', 'sdp']);

        $calleeId = $this->int_param('to_user_id');
        $sdp = $this->param('sdp');
        if (!is_string($sdp) || strlen($sdp) < 10 || strlen($sdp) > 20000) {
            $this->fail('Invalid SDP offer.', 422, 'invalid_sdp');
        }
        $callee = $this->user_model->find($calleeId);
        if (!$callee || (int) $callee['is_active'] !== 1) {
            $this->fail('User not found.', 404, 'not_found');
        }
        if ($calleeId === (int) $user['id']) {
            $this->fail('You cannot call yourself.', 422, 'invalid_target');
        }
        if ($this->friend_model->isBlocked((int) $user['id'], $calleeId)) {
            $this->fail('You have blocked this user. Unblock to continue.', 403, 'blocked');
        }
        if ($this->friend_model->isBlocked($calleeId, (int) $user['id'])) {
            $this->fail('User not found.', 404, 'not_found');
        }

        $kind = strtolower(trim($this->str_param('kind', 'audio')));
        if (!in_array($kind, ['audio', 'video'], true)) {
            $this->fail('Kind must be audio or video.', 422, 'invalid_kind');
        }
        if ($kind === 'video' && empty($this->app['features']['video_calls'])) {
            $this->fail('Video calls are disabled.', 403, 'video_disabled');
        }

        if (!$this->throttle('call:' . (int) $user['id'], 20, 60)) {
            $this->fail('Too many call attempts. Try again later.', 429, 'rate_limited');
        }

        $call = $this->call_model->create((int) $user['id'], $calleeId, $kind);
        $payload = [
            'call' => $this->call_dto($call),
            'from' => user_dto($user),
            'sdp' => $sdp,
        ];
        $this->notify(
            $calleeId,
            'call_offer',
            $payload,
            $kind === 'video' ? 'Panggilan video masuk' : 'Panggilan suara masuk',
            ($user['display_name'] ?? 'Seseorang') . ' menghubungimu'
        );
        $this->ok(['call' => $this->call_dto($call)], 201);
    }

    public function answer(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['call_id', 'sdp']);

        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call) {
            $this->fail('Call not found.', 404, 'not_found');
        }
        if ((int) $call['callee_id'] !== (int) $user['id']) {
            $this->fail('Only the callee can answer.', 403, 'forbidden');
        }
        $sdp = $this->param('sdp');
        if (!is_string($sdp) || strlen($sdp) < 10 || strlen($sdp) > 20000) {
            $this->fail('Invalid SDP answer.', 422, 'invalid_sdp');
        }
        $call = $this->call_model->setStatus((int) $call['id'], 'active');
        $this->store->update('calls', ['answered_at' => now_iso()], 'id = ? AND answered_at IS NULL', [(int) $call['id']]);
        $call = $this->call_model->find((int) $call['id']);
        $payload = ['call' => $this->call_dto($call), 'sdp' => $sdp, 'from' => user_dto($user)];
        $this->notify((int) $call['caller_id'], 'call_answer', $payload, 'Panggilan dijawab', 'Panggilanmu dijawab');
        $this->ok(['call' => $this->call_dto($call)]);
    }

    public function ice(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['call_id', 'candidate']);

        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call) {
            $this->fail('Call not found.', 404, 'not_found');
        }
        $me = (int) $user['id'];
        $candidate = $this->param('candidate');
        if (!is_array($candidate)) {
            $this->fail('candidate must be an object.', 422, 'validation_error');
        }
        if (!empty($call['conversation_id'])) {
            $convId = (int) $call['conversation_id'];
            if (!$this->conversation_model->isMember($convId, $me)) {
                $this->fail('You are not part of this call.', 403, 'forbidden');
            }
            if ($this->call_model->participantStatus((int) $call['id'], $me) !== 'joined') {
                $this->fail('Join the room before sending ICE.', 403, 'not_joined');
            }
            $this->require_fields(['to_user_id']);
            $targetId = $this->int_param('to_user_id');
            if ($targetId === $me || $this->call_model->participantStatus((int) $call['id'], $targetId) !== 'joined') {
                $this->fail('Target peer is not in this room.', 422, 'invalid_target');
            }
            $this->realtime_model->push([$targetId], 'call_ice', [
                'call' => $this->call_dto($call),
                'candidate' => $candidate,
                'from_user_id' => $me,
            ]);
            $this->ok(['sent' => true]);
        }
        if ($me !== (int) $call['caller_id'] && $me !== (int) $call['callee_id']) {
            $this->fail('You are not part of this call.', 403, 'forbidden');
        }
        $peerId = $me === (int) $call['caller_id'] ? (int) $call['callee_id'] : (int) $call['caller_id'];
        $this->realtime_model->push([$peerId], 'call_ice', [
            'call' => $this->call_dto($call),
            'candidate' => $candidate,
            'from_user_id' => $me,
        ]);
        $this->ok(['sent' => true]);
    }

    public function reject(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['call_id']);

        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call) {
            $this->fail('Call not found.', 404, 'not_found');
        }
        $me = (int) $user['id'];
        if ($me !== (int) $call['caller_id'] && $me !== (int) $call['callee_id']) {
            $this->fail('You are not part of this call.', 403, 'forbidden');
        }
        $call = $this->call_model->setStatus((int) $call['id'], 'rejected');
        $peerId = $me === (int) $call['caller_id'] ? (int) $call['callee_id'] : (int) $call['caller_id'];
        $this->notify($peerId, 'call_end', ['call' => $this->call_dto($call), 'reason' => 'rejected'], 'Panggilan ditolak', 'Panggilan ditolak');
        $this->ok(['call' => $this->call_dto($call)]);
    }

    public function end(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['call_id']);

        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call) {
            $this->fail('Call not found.', 404, 'not_found');
        }
        $me = (int) $user['id'];
        if ($me !== (int) $call['caller_id'] && $me !== (int) $call['callee_id']) {
            $this->fail('You are not part of this call.', 403, 'forbidden');
        }
        $wasRinging = $call['status'] === 'ringing' && empty($call['answered_at']);
        $callerId = (int) $call['caller_id'];
        $call = $this->call_model->setStatus((int) $call['id'], 'ended');
        $peerId = $me === $callerId ? (int) $call['callee_id'] : $callerId;
        $this->realtime_model->push([$peerId], 'call_end', [
            'call' => $this->call_dto($call),
            'reason' => 'ended',
        ]);
        // Penelepon membatalkan sebelum diangkat: beri tahu yang ditelepon.
        if ($wasRinging && $me === $callerId && ($this->app['features']['push'] ?? true)) {
            $calleeId = (int) $call['callee_id'];
            push_notify([$calleeId], 'Panggilan tak terjawab', ($user['display_name'] ?? 'Seseorang') . ' meneleponmu', [
                'event' => 'call_missed',
                'call_id' => (string) $call['id'],
                'caller_id' => (string) $callerId,
            ]);
        }
        $this->ok(['call' => $this->call_dto($call)]);
    }

    public function incoming(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $rows = $this->call_model->ringingFor((int) $user['id']);
        $rooms = $this->call_model->openRoomsFor((int) $user['id']);
        $this->ok([
            'calls' => array_map([$this, 'call_dto'], $rows),
            'rooms' => array_map([$this, 'call_dto'], $rooms),
        ]);
    }

    public function room_create(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['conversation_id']);

        if (empty($this->app['features']['group_calls'])) {
            $this->fail('Group calls are disabled.', 403, 'group_calls_disabled');
        }
        $convId = $this->int_param('conversation_id');
        $group = $this->conversation_model->find($convId);
        if (!$group || $group['type'] !== 'group') {
            $this->fail('Group not found.', 404, 'not_found');
        }
        if (!$this->conversation_model->isMember($convId, (int) $user['id'])) {
            $this->fail('You are not a member of this group.', 403, 'forbidden');
        }
        $kind = strtolower(trim($this->str_param('kind', 'audio')));
        if (!in_array($kind, ['audio', 'video'], true)) {
            $this->fail('Kind must be audio or video.', 422, 'invalid_kind');
        }
        if ($kind === 'video' && empty($this->app['features']['video_calls'])) {
            $this->fail('Video calls are disabled.', 403, 'video_disabled');
        }
        if (!$this->throttle('room:' . (int) $user['id'], 10, 60)) {
            $this->fail('Too many call attempts. Try again later.', 429, 'rate_limited');
        }

        $existing = $this->call_model->activeRoomFor($convId);
        if ($existing) {
            $this->call_model->setParticipant((int) $existing['id'], (int) $user['id'], 'joined');
            $this->ok([
                'call' => $this->call_dto($existing),
                'participants' => $this->participants_dto((int) $existing['id']),
                'reused' => true,
            ]);
        }

        $room = $this->call_model->createRoom((int) $user['id'], $convId, $kind);
        $others = array_values(array_diff($this->conversation_model->memberIds($convId), [(int) $user['id']]));
        if ($others) {
            $payload = [
                'call' => $this->call_dto($room),
                'from' => user_dto($user),
                'conversation_id' => $convId,
            ];
            $this->realtime_model->push($others, 'call_room_offer', $payload);
            $tokens = [];
            foreach ($others as $uid) {
                foreach ($this->push_model->tokensFor($uid) as $token) {
                    $tokens[] = $token;
                }
            }
            $this->fcm()->sendSilent(
                $tokens,
                $kind === 'video' ? 'Panggilan video grup' : 'Panggilan grup',
                ($user['display_name'] ?? 'Seseorang') . ' memulai panggilan grup',
                ['event' => 'call_room_offer', 'call_id' => (string) $room['id'], 'conversation_id' => (string) $convId]
            );
        }
        $this->ok([
            'call' => $this->call_dto($room),
            'participants' => $this->participants_dto((int) $room['id']),
            'reused' => false,
        ], 201);
    }

    public function room_join(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['call_id']);

        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call || empty($call['conversation_id'])) {
            $this->fail('Call room not found.', 404, 'not_found');
        }
        if (!in_array($call['status'], ['ringing', 'active'], true)) {
            $this->fail('This room is no longer active.', 422, 'room_ended');
        }
        $convId = (int) $call['conversation_id'];
        if (!$this->conversation_model->isMember($convId, (int) $user['id'])) {
            $this->fail('You are not a member of this group.', 403, 'forbidden');
        }
        $this->call_model->setParticipant((int) $call['id'], (int) $user['id'], 'joined');
        if ($call['status'] === 'ringing') {
            $call = $this->call_model->setStatus((int) $call['id'], 'active');
        }
        $others = array_values(array_diff($this->call_model->joinedIds((int) $call['id']), [(int) $user['id']]));
        if ($others) {
            $this->realtime_model->push($others, 'call_room_joined', [
                'call' => $this->call_dto($call),
                'user' => user_dto($user),
            ]);
        }
        $this->ok([
            'call' => $this->call_dto($call),
            'participants' => $this->participants_dto((int) $call['id']),
        ]);
    }

    public function room_leave(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['call_id']);

        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call || empty($call['conversation_id'])) {
            $this->fail('Call room not found.', 404, 'not_found');
        }
        $this->call_model->setParticipant((int) $call['id'], (int) $user['id'], 'left');
        $remaining = $this->call_model->joinedIds((int) $call['id']);
        if ($remaining) {
            $this->realtime_model->push($remaining, 'call_room_left', [
                'call' => $this->call_dto($call),
                'user_id' => (int) $user['id'],
            ]);
            $this->ok(['left' => true, 'ended' => false]);
        }
        $call = $this->call_model->setStatus((int) $call['id'], 'ended');
        $convId = (int) $call['conversation_id'];
        $this->realtime_model->push($this->conversation_model->memberIds($convId), 'call_room_ended', [
            'call' => $this->call_dto($call),
        ]);
        $this->ok(['left' => true, 'ended' => true]);
    }

    /**
     * Relay SDP offer/answer antar dua partisipan room (untuk mesh WebRTC).
     */
    public function room_signal(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['call_id', 'to_user_id', 'sdp', 'signal']);

        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call || empty($call['conversation_id'])) {
            $this->fail('Call room not found.', 404, 'not_found');
        }
        if (!in_array($call['status'], ['ringing', 'active'], true)) {
            $this->fail('This room is no longer active.', 422, 'room_ended');
        }
        $me = (int) $user['id'];
        if ($this->call_model->participantStatus((int) $call['id'], $me) !== 'joined') {
            $this->fail('Join the room before signaling.', 403, 'not_joined');
        }
        $signal = strtolower(trim($this->str_param('signal')));
        if (!in_array($signal, ['offer', 'answer'], true)) {
            $this->fail('Signal must be offer or answer.', 422, 'invalid_signal');
        }
        $sdp = $this->param('sdp');
        if (!is_string($sdp) || strlen($sdp) < 10 || strlen($sdp) > 20000) {
            $this->fail('Invalid SDP.', 422, 'invalid_sdp');
        }
        $targetId = $this->int_param('to_user_id');
        if ($targetId === $me || $this->call_model->participantStatus((int) $call['id'], $targetId) !== 'joined') {
            $this->fail('Target peer is not in this room.', 422, 'invalid_target');
        }
        $this->realtime_model->push([$targetId], 'call_room_signal', [
            'call' => $this->call_dto($call),
            'signal' => $signal,
            'sdp' => $sdp,
            'from_user_id' => $me,
        ]);
        $this->ok(['sent' => true]);
    }

    /**
     * Riwayat panggilan: 1-1 milikku + room grup yang kuikuti.
     */
    public function history(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $me = (int) $user['id'];
        $limit = $this->int_param('limit', 30);
        $beforeId = $this->param('before_id') !== null ? $this->int_param('before_id') : 0;
        $rows = $this->call_model->historyFor($me, $limit, $beforeId);
        $this->ok(['calls' => array_map(
            function (array $row) use ($me): array {
                $dto = $this->call_dto($row);
                $isRoom = !empty($row['conversation_id']);
                if ($isRoom) {
                    $dto['direction'] = (int) $row['caller_id'] === $me ? 'outgoing' : 'incoming';
                    $dto['peer'] = null;
                    $dto['group_title'] = $row['group_title'] ?? null;
                    $dto['missed'] = false;
                } else {
                    $outgoing = (int) $row['caller_id'] === $me;
                    $dto['direction'] = $outgoing ? 'outgoing' : 'incoming';
                    $peerId = $outgoing ? (int) $row['callee_id'] : (int) $row['caller_id'];
                    $peer = $this->user_model->find($peerId);
                    $dto['peer'] = $peer ? user_dto($peer) : ['id' => $peerId];
                    $staleRinging = $row['status'] === 'ringing'
                        && strtotime($row['created_at']) < time() - 300;
                    $dto['missed'] = !$outgoing && empty($row['answered_at'])
                        && in_array($row['status'], ['ended', 'rejected', 'missed'], true);
                    if (!$outgoing && $staleRinging) {
                        $dto['missed'] = true;
                    }
                    $dto['group_title'] = null;
                }
                $dto['duration'] = $this->callDuration($row);
                return $dto;
            },
            $rows
        )]);
    }

    private function callDuration(array $row): int
    {
        if (empty($row['answered_at']) || empty($row['ended_at'])) {
            return 0;
        }
        return max(0, strtotime($row['ended_at']) - strtotime($row['answered_at']));
    }

    public function room_state(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call || empty($call['conversation_id'])) {
            $this->fail('Call room not found.', 404, 'not_found');
        }
        if (!$this->conversation_model->isMember((int) $call['conversation_id'], (int) $user['id'])) {
            $this->fail('You are not a member of this group.', 403, 'forbidden');
        }
        $this->ok([
            'call' => $this->call_dto($call),
            'participants' => $this->participants_dto((int) $call['id']),
        ]);
    }

    /**
     * Short-lived Cloudflare TURN credentials (docs/TURN.md).
     * The API token stays server-side (ENV TURN_API_TOKEN); clients only
     * ever see the generated iceServers. Falls back to plain Cloudflare
     * STUN when no token is configured or Cloudflare is unreachable.
     */
    public function ice_servers(): void
    {
        $this->require_method(['GET']);
        $this->require_auth();
        $this->ok(['iceServers' => $this->turn_ice_servers()]);
    }

    /**
     * Kredensial media NVP/1 (docs/VOICE-PROTOCOL.md §3-4): slot + token
     * deterministik + port relay. Cermin `call.prepared` untuk jalur
     * fallback REST (jawab/ruang saat WS mati). Token dihitung IDENTIK
     * dengan Erlang (`nusa_voice_relay:call_token/2`); uji silang di smoke.
     */
    public function nvp_join(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['call_id']);

        $me = (int) $user['id'];
        $call = $this->call_model->find($this->int_param('call_id'));
        if (!$call) {
            $this->fail('Call not found.', 404, 'not_found');
        }
        if (!in_array($call['status'], ['ringing', 'active'], true)) {
            $this->fail('This call is no longer active.', 422, 'gone');
        }
        $convId = $call['conversation_id'] ?? null;
        if ($convId === null) {
            if ((int) $call['caller_id'] !== $me && (int) $call['callee_id'] !== $me) {
                $this->fail('You are not a participant of this call.', 403, 'forbidden');
            }
            $slot = ((int) $call['caller_id'] === $me) ? 0 : 1;
        } else {
            if (!$this->conversation_model->isMember((int) $convId, $me)) {
                $this->fail('You are not a member of this group.', 403, 'forbidden');
            }
            $ids = array_map('intval', array_column($this->call_model->participants((int) $call['id']), 'user_id'));
            $slot = array_search($me, $ids, true);
            if ($slot === false) {
                if (count($ids) >= 8) {
                    $this->fail('Room is full.', 422, 'room_full');
                }
                $this->call_model->setParticipant((int) $call['id'], $me, 'joined');
                $slot = count($ids);
            }
        }
        $resp = [
            'call' => $this->call_dto($call),
            'slot' => $slot,
            'token' => self::nvpToken((int) $call['id'], $me),
            'relay_port' => (int) (getenv('VOICE_UDP_PORT') ?: 8092),
        ];
        if ($convId !== null) {
            // Kunci media room (cermin Erlang `nusa_call:room_key/salt`).
            $resp['key_ct'] = self::nvpB64Url(hash_hmac('sha256', 'nvp-room-key' . pack('J', (int) $call['id']), self::nvpSecret(), true));
            $resp['salt'] = self::nvpB64Url(substr(hash_hmac('sha256', 'nvp-room-salt' . pack('J', (int) $call['id']), self::nvpSecret(), true), 0, 4));
        }
        $this->ok($resp);
    }

    private static function nvpSecret(): string
    {
        return (string) (getenv('APP_SECRET') ?: 'dev-secret-change-me-please-32-bytes-min');
    }

    private static function nvpB64Url(string $bin): string
    {
        return rtrim(strtr(base64_encode($bin), '+/', '-_'), '=');
    }

    /** Token sesi NVP: 4 byte pertama HMAC-SHA256(APP_SECRET, "nvp-v1"|call:64|user:64). */
    public static function nvpToken(int $callId, int $userId): int
    {
        $mac = hash_hmac('sha256', "nvp-v1" . pack('J', $callId) . pack('J', $userId), self::nvpSecret(), true);
        $parts = unpack('N', substr($mac, 0, 4));
        return $parts[1];
    }

    private function turn_ice_servers(): array
    {
        $fallback = [['urls' => ['stun:stun.cloudflare.com:3478']]];
        $turn = $this->app['turn'] ?? [];
        $token = trim((string) ($turn['api_token'] ?? ''));
        $appId = trim((string) ($turn['app_id'] ?? ''));
        if ($token === '' || $appId === '') {
            return $fallback;
        }
        $ttl = max(600, (int) ($turn['ttl'] ?? 86400));
        $cacheFile = sys_get_temp_dir() . '/nusachat_turn_ice.json';
        $cached = json_decode((string) @file_get_contents($cacheFile), true);
        if (is_array($cached) && isset($cached['expires_at'], $cached['iceServers'])
            && is_array($cached['iceServers']) && $cached['iceServers'] !== []
            && (int) $cached['expires_at'] - time() > 1800) {
            return $cached['iceServers'];
        }
        $url = 'https://rtc.live.cloudflare.com/v1/turn/keys/' . $appId . '/credentials/generate-ice-servers';
        $resp = $this->post_json($url, json_encode(['ttl' => $ttl]), $token);
        $servers = $resp['iceServers'] ?? null;
        if (!is_array($servers) || $servers === []) {
            return $fallback;
        }
        $clean = [];
        foreach ($servers as $entry) {
            if (!is_array($entry) || empty($entry['urls'])) {
                continue;
            }
            $item = ['urls' => array_values((array) $entry['urls'])];
            if (!empty($entry['username'])) {
                $item['username'] = (string) $entry['username'];
            }
            if (!empty($entry['credential'])) {
                $item['credential'] = (string) $entry['credential'];
            }
            $clean[] = $item;
        }
        if ($clean === []) {
            return $fallback;
        }
        @file_put_contents($cacheFile, json_encode(['expires_at' => time() + $ttl, 'iceServers' => $clean]));
        return $clean;
    }

    private function post_json(string $url, string $body, string $token): array
    {
        if (function_exists('curl_init')) {
            $ch = curl_init($url);
            curl_setopt_array($ch, [
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_POST => true,
                CURLOPT_POSTFIELDS => $body,
                CURLOPT_HTTPHEADER => ['Authorization: Bearer ' . $token, 'Content-Type: application/json'],
                CURLOPT_TIMEOUT => 8,
            ]);
            $out = curl_exec($ch);
            curl_close($ch);
            $decoded = json_decode((string) $out, true);
            return is_array($decoded) ? $decoded : [];
        }
        $ctx = stream_context_create(['http' => [
            'method' => 'POST',
            'header' => "Authorization: Bearer {$token}\r\nContent-Type: application/json\r\n",
            'content' => $body,
            'timeout' => 8,
            'ignore_errors' => true,
        ]]);
        $out = @file_get_contents($url, false, $ctx);
        $decoded = json_decode((string) $out, true);
        return is_array($decoded) ? $decoded : [];
    }
}
