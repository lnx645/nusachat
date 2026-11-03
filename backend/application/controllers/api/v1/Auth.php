<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Auth extends MY_Controller
{
    public function __construct()
    {
        parent::__construct();
        $this->load->model(['user_model', 'token_model']);
    }

    public function register(): void
    {
        $this->require_method(['POST']);
        if (!$this->throttle('register:' . client_ip(), (int) $this->app['register_rate_limit'], 3600)) {
            $this->fail('Too many registrations from this network. Try again later.', 429, 'rate_limited');
        }

        $this->require_fields(['username', 'password', 'display_name']);

        $username = strtolower($this->str_param('username'));
        $password = (string) $this->param('password');
        $displayName = $this->str_param('display_name');
        $phoneRaw = $this->str_param('phone');
        $phone = $phoneRaw !== '' ? normalize_phone($phoneRaw) : null;

        if (!preg_match('/^[a-z0-9_.]{4,20}$/', $username)) {
            $this->fail('Username must be 4-20 characters: letters, numbers, dot or underscore.', 422, 'invalid_username');
        }
        if (in_array($username, ['admin', 'administrator', 'support', 'system', 'nusachat', 'official'], true)) {
            $this->fail('This username is reserved.', 422, 'invalid_username');
        }
        $length = mb_strlen($password);
        if ($length < 6 || $length > 72) {
            $this->fail('Password must be 6-72 characters.', 422, 'invalid_password');
        }
        if (mb_strlen($displayName) < 2 || mb_strlen($displayName) > 40) {
            $this->fail('Display name must be 2-40 characters.', 422, 'invalid_display_name');
        }
        if ($phoneRaw !== '' && $phone === null) {
            $this->fail('Phone number is not valid.', 422, 'invalid_phone');
        }
        if ($this->user_model->usernameExists($username)) {
            $this->fail('Username is already taken.', 409, 'username_taken');
        }
        if ($phone !== null && $this->user_model->phoneExists($phone)) {
            $this->fail('Phone number is already registered.', 409, 'phone_taken');
        }

        $userId = $this->user_model->create([
            'username' => $username,
            'phone' => $phone,
            'password_hash' => password_hash($password, PASSWORD_DEFAULT),
            'display_name' => $displayName,
        ]);
        $user = $this->user_model->find($userId);
        $this->user_model->touch($userId);

        $this->ok([
            'user' => user_dto($user, true),
            'auth' => $this->token_model->issue($user, $this->device_name(), $this->app),
        ], 201);
    }

    public function login(): void
    {
        $this->require_method(['POST']);
        $this->require_fields(['password']);

        $identifier = strtolower($this->str_param('login', $this->str_param('username')));
        if ($identifier === '') {
            $this->fail('Username or phone number is required.', 422, 'validation_error');
        }
        if (!$this->throttle('login-ip:' . client_ip(), 30, 60)) {
            $this->fail('Too many login attempts. Please wait a minute.', 429, 'rate_limited');
        }
        if (!$this->throttle('login-id:' . $identifier, 10, 60)) {
            $this->fail('Too many login attempts for this account. Please wait a minute.', 429, 'rate_limited');
        }

        $user = $this->user_model->findByLogin($identifier);
        if (!$user || !password_verify((string) $this->param('password'), $user['password_hash'])) {
            $this->fail('Incorrect username or password.', 401, 'invalid_credentials');
        }
        if ((int) $user['is_active'] !== 1) {
            $this->fail('This account is disabled.', 403, 'account_disabled');
        }

        $this->user_model->touch((int) $user['id']);
        $this->ok([
            'user' => user_dto($user, true),
            'auth' => $this->token_model->issue($user, $this->device_name(), $this->app),
        ]);
    }

    public function refresh(): void
    {
        $this->require_method(['POST']);
        $refresh = $this->str_param('refresh_token');
        if ($refresh === '') {
            $this->fail('refresh_token is required.', 422, 'validation_error');
        }
        if (!$this->throttle('refresh:' . client_ip(), 60, 60)) {
            $this->fail('Too many refresh attempts.', 429, 'rate_limited');
        }

        $row = $this->token_model->consumeRefresh($refresh);
        if (!$row) {
            $this->fail('Refresh token is invalid or expired.', 401, 'invalid_refresh_token');
        }
        $user = $this->user_model->find((int) $row['user_id']);
        if (!$user || (int) $user['is_active'] !== 1) {
            $this->fail('Account not found.', 401, 'unauthorized');
        }

        $this->ok([
            'user' => user_dto($user, true),
            'auth' => $this->token_model->issue($user, $this->device_name(), $this->app),
        ]);
    }

    public function logout(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();

        $refresh = $this->str_param('refresh_token');
        if ($refresh !== '') {
            $this->token_model->revokeRefresh($refresh);
        }
        $this->token_model->revokeAll((int) $user['id']);
        $this->user_model->bumpTokenVersion((int) $user['id']);

        $this->ok(['message' => 'Logged out.']);
    }

    public function check_username(): void
    {
        $this->require_method(['GET']);
        $username = strtolower($this->str_param('username'));
        if (!preg_match('/^[a-z0-9_.]{4,20}$/', $username)) {
            $this->ok(['username' => $username, 'available' => false, 'reason' => 'invalid']);
        }
        $reserved = in_array($username, ['admin', 'administrator', 'support', 'system', 'nusachat', 'official'], true);
        $this->ok([
            'username' => $username,
            'available' => !$reserved && !$this->user_model->usernameExists($username),
            'reason' => $reserved ? 'reserved' : null,
        ]);
    }

    private function device_name(): string
    {
        $device = $this->str_param('device');
        if ($device === '') {
            $device = $_SERVER['HTTP_USER_AGENT'] ?? 'unknown';
        }
        return $device;
    }
}
