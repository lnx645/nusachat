<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Users extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['user_model', 'friend_model', 'token_model', 'key_model', 'conversation_model', 'realtime_model']);
    }

    public function me(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $fresh = $this->user_model->find((int) $user['id']);
        $this->ok(['user' => user_dto($fresh, true)]);
    }

    public function update(): void
    {
        $this->require_method(['POST', 'PATCH']);
        $user = $this->require_auth();
        $fields = [];

        if ($this->param('display_name') !== null) {
            $name = $this->str_param('display_name');
            if (mb_strlen($name) < 2 || mb_strlen($name) > 40) {
                $this->fail('Display name must be 2-40 characters.', 422, 'invalid_display_name');
            }
            $fields['display_name'] = $name;
        }
        if ($this->param('bio') !== null) {
            $bio = $this->str_param('bio');
            if (mb_strlen($bio) > 160) {
                $this->fail('Bio is limited to 160 characters.', 422, 'invalid_bio');
            }
            $fields['bio'] = $bio;
        }
        if ($this->param('gender') !== null) {
            $gender = $this->str_param('gender');
            if (!in_array($gender, ['male', 'female', 'other', ''], true)) {
                $this->fail('Gender must be male, female, other or empty.', 422, 'invalid_gender');
            }
            $fields['gender'] = $gender === '' ? null : $gender;
        }
        if ($this->param('birthday') !== null) {
            $birthday = $this->str_param('birthday');
            if ($birthday !== '' && !preg_match('/^\d{4}-\d{2}-\d{2}$/', $birthday)) {
                $this->fail('Birthday must use YYYY-MM-DD.', 422, 'invalid_birthday');
            }
            $fields['birthday'] = $birthday === '' ? null : $birthday;
        }
        if ($this->param('avatar_url') !== null) {
            $avatar = $this->str_param('avatar_url');
            if ($avatar !== '' && mb_strlen($avatar) > 500) {
                $this->fail('Avatar URL is too long.', 422, 'invalid_avatar');
            }
            $fields['avatar_url'] = $avatar === '' ? null : $avatar;
        }
        if ($this->param('discoverable') !== null) {
            $fields['discoverable'] = $this->bool_param('discoverable') ? 1 : 0;
        }
        if ($this->param('phone') !== null) {
            $raw = $this->str_param('phone');
            if ($raw !== '') {
                $phone = normalize_phone($raw);
                if ($phone === null) {
                    $this->fail('Phone number is not valid.', 422, 'invalid_phone');
                }
                $existing = $this->user_model->findByPhone($phone);
                if ($existing && (int) $existing['id'] !== (int) $user['id']) {
                    $this->fail('Phone number is already registered.', 409, 'phone_taken');
                }
                $fields['phone'] = $phone;
                $fields['phone_hash'] = phone_hash($phone);
            } else {
                $fields['phone'] = null;
                $fields['phone_hash'] = null;
            }
        }

        if ($fields) {
            $this->user_model->update((int) $user['id'], $fields);
        }
        $this->ok(['user' => user_dto($this->user_model->find((int) $user['id']), true)]);
    }

    public function search(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $query = $this->str_param('q');
        if (mb_strlen($query) < 2) {
            $this->ok(['users' => []]);
        }
        if (!$this->throttle('search:' . (int) $user['id'], 60, 60)) {
            $this->fail('Too many searches. Please slow down.', 429, 'rate_limited');
        }

        $rows = $this->user_model->search($query, (int) $user['id'], 20);
        $hidden = array_merge(
            $this->friend_model->blockedIds((int) $user['id']),
            $this->friend_model->blockedByIds((int) $user['id'])
        );
        $users = [];
        foreach ($rows as $row) {
            if (in_array((int) $row['id'], $hidden, true)) {
                continue;
            }
            $dto = user_dto($row);
            $friendship = $this->friend_model->findBetween((int) $user['id'], (int) $row['id']);
            $dto['friend_status'] = $friendship ? $friendship['status'] : 'none';
            if ($friendship) {
                $dto['request_id'] = (int) $friendship['id'];
                $dto['request_direction'] = (int) $friendship['requester_id'] === (int) $user['id'] ? 'outgoing' : 'incoming';
            }
            $users[] = $dto;
        }
        $this->ok(['users' => $users]);
    }

    public function show(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $id = $this->int_param('id');
        if ($id <= 0) {
            $this->fail('User id is required.', 422, 'validation_error');
        }
        $row = $this->user_model->find($id);
        if (!$row || (int) $row['is_active'] !== 1) {
            $this->fail('User not found.', 404, 'not_found');
        }
        if ($this->friend_model->isBlocked((int) $user['id'], $id)
            || $this->friend_model->isBlocked($id, (int) $user['id'])) {
            $this->fail('User not found.', 404, 'not_found');
        }
        $dto = user_dto($row);
        $friendship = $this->friend_model->findBetween((int) $user['id'], $id);
        $dto['friend_status'] = $friendship ? $friendship['status'] : 'none';
        if ($friendship) {
            $dto['request_id'] = (int) $friendship['id'];
            $dto['request_direction'] = (int) $friendship['requester_id'] === (int) $user['id'] ? 'outgoing' : 'incoming';
        }
        $this->ok(['user' => $dto]);
    }

    public function password(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['old_password', 'new_password']);

        if (!password_verify((string) $this->param('old_password'), $user['password_hash'])) {
            $this->fail('Current password is incorrect.', 401, 'invalid_credentials');
        }
        $new = (string) $this->param('new_password');
        if (mb_strlen($new) < 6 || mb_strlen($new) > 72) {
            $this->fail('New password must be 6-72 characters.', 422, 'invalid_password');
        }

        $this->user_model->setPassword((int) $user['id'], password_hash($new, PASSWORD_DEFAULT));
        $this->token_model->revokeAll((int) $user['id']);
        $this->ok(['message' => 'Password changed. Please log in again.']);
    }

    public function delete(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $this->require_fields(['password']);

        if (!password_verify((string) $this->param('password'), $user['password_hash'])) {
            $this->fail('Password is incorrect.', 401, 'invalid_credentials');
        }
        $userId = (int) $user['id'];
        $this->token_model->revokeAll($userId);
        $this->user_model->deleteAccount($userId);
        $this->ok(['deleted' => true]);
    }

    public function device_key(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $this->require_fields(['device_id', 'public_key']);

        $deviceId = trim($this->str_param('device_id'));
        if ($deviceId === '' || mb_strlen($deviceId) > 128) {
            $this->fail('Invalid device id.', 422, 'invalid_device');
        }
        $publicKey = trim($this->str_param('public_key'));
        if ($publicKey === '' || mb_strlen($publicKey) > 2048
            || base64_decode($publicKey, true) === false) {
            $this->fail('Invalid public key.', 422, 'invalid_key');
        }
        $this->key_model->publishDeviceKey((int) $user['id'], $deviceId, $publicKey);
        // Beri tahu semua lawan bicara: pemegang kunci membungkus ulang
        // untuk perangkat baru ini (multi-device E2EE) via Erlang.
        $neighbors = $this->conversation_model->peerUserIds((int) $user['id']);
        if ($neighbors) {
            $this->realtime_model->push($neighbors, 'device_added', [
                'user_id' => (int) $user['id'],
                'device_id' => $deviceId,
            ]);
        }
        $this->ok(['published' => true], 201);
    }

    public function device_keys(): void
    {
        $this->require_method(['GET']);
        $this->require_auth();
        $userId = $this->int_param('user_id');
        $target = $this->user_model->find($userId);
        if (!$target || (int) $target['is_active'] !== 1) {
            $this->fail('User not found.', 404, 'not_found');
        }
        $this->ok([
            'user_id' => $userId,
            'keys' => $this->key_model->deviceKeys($userId),
        ]);
    }

    public function presence(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();
        $me = (int) $user['id'];

        // Transisi offline -> online disiarkan via Erlang agar subtitle
        // "x anggota, y online" terasa realtime (tanpa polling).
        $prevSeen = $this->user_model->find($me)['last_seen_at'] ?? null;
        $wasOnline = is_string($prevSeen) && (time() - (int) strtotime($prevSeen)) < 90;

        $lat = $this->param('lat');
        $lng = $this->param('lng');
        $hasLocation = $lat !== null && $lng !== null;
        if ($hasLocation) {
            $lat = (float) $lat;
            $lng = (float) $lng;
            if ($lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) {
                $this->fail('Invalid coordinates.', 422, 'invalid_location');
            }
            $this->user_model->touch((int) $user['id'], $lat, $lng);
        } else {
            $this->user_model->touch((int) $user['id']);
        }
        if (!$wasOnline) {
            $neighbors = $this->conversation_model->peerUserIds($me);
            if ($neighbors) {
                $this->realtime_model->push($neighbors, 'presence', [
                    'user_id' => $me,
                    'online' => true,
                    'display_name' => $user['display_name'] ?? null,
                ]);
            }
        }
        $this->ok(['server_time' => now_iso()]);
    }
}
