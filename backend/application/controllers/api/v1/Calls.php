<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * Voice-call signaling over the existing realtime outbox + FCM wake-up.
 *
 * SDP/ICE are relayed verbatim; the server never inspects media.
 * Audio itself is peer-to-peer via WebRTC (STUN configured client-side).
 */
class Calls extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['call_model', 'user_model', 'friend_model', 'realtime_model', 'push_model']);
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
            'status' => $row['status'],
            'created_at' => $row['created_at'],
            'updated_at' => $row['updated_at'],
            'ended_at' => $row['ended_at'] ?? null,
        ];
    }

    private function notify(int $userId, string $event, array $data, string $title, string $body): void
    {
        $this->realtime_model->push([$userId], $event, $data);
        $tokens = $this->push_model->tokensFor($userId);
        $this->fcm()->sendSilent($tokens, $title, $body, array_merge(['event' => $event], array_map('strval', array_filter(
            $data,
            static fn ($v): bool => is_scalar($v) || $v === null
        ))));
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

        if (!$this->throttle('call:' . (int) $user['id'], 20, 60)) {
            $this->fail('Too many call attempts. Try again later.', 429, 'rate_limited');
        }

        $call = $this->call_model->create((int) $user['id'], $calleeId);
        $payload = [
            'call' => $this->call_dto($call),
            'from' => user_dto($user),
            'sdp' => $sdp,
        ];
        $this->notify(
            $calleeId,
            'call_offer',
            $payload,
            'Panggilan suara masuk',
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
        if ($me !== (int) $call['caller_id'] && $me !== (int) $call['callee_id']) {
            $this->fail('You are not part of this call.', 403, 'forbidden');
        }
        $candidate = $this->param('candidate');
        if (!is_array($candidate)) {
            $this->fail('candidate must be an object.', 422, 'validation_error');
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
        $call = $this->call_model->setStatus((int) $call['id'], 'ended');
        $peerId = $me === (int) $call['caller_id'] ? (int) $call['callee_id'] : (int) $call['caller_id'];
        $this->realtime_model->push([$peerId], 'call_end', [
            'call' => $this->call_dto($call),
            'reason' => 'ended',
        ]);
        $this->ok(['call' => $this->call_dto($call)]);
    }

    public function incoming(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $rows = $this->call_model->ringingFor((int) $user['id']);
        $this->ok(['calls' => array_map([$this, 'call_dto'], $rows)]);
    }
}
