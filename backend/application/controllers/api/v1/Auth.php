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

        $result = $this->token_model->consumeRefreshStrict($refresh);
        if ($result['status'] === 'reused') {
            // Token yang sudah dirotasi dipakai ulang → kemungkinan curian.
            // Keluarga token sudah dicabut di model; naikkan token_version
            // agar access token curian pun langsung mati.
            $row = $result['row'];
            $this->user_model->bumpTokenVersion((int) $row['user_id']);
            log_message('error', 'Refresh token reuse detected for user ' . (int) $row['user_id']);
            $this->fail('Session has been revoked, please log in again.', 401, 'token_revoked');
        }
        if ($result['status'] !== 'ok') {
            $this->fail('Refresh token is invalid or expired.', 401, 'invalid_refresh_token');
        }
        $row = $result['row'];
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

    /**
     * Login / daftar dengan Google ID token.
     *
     * POST {id_token}: bila google_sub sudah tertaut -> login langsung.
     * Bila belum: 409 google_register_required agar client lanjut ke
     * layar nama/avatar/sandi, lalu POST lagi lengkap untuk membuat akun.
     * POST {id_token, username, display_name, password, avatar_url?}:
     * buat akun + tautkan google_sub.
     */
    public function google(): void
    {
        $this->require_method(['POST']);
        $this->require_fields(['id_token']);
        if (!$this->throttle('google:' . client_ip(), 30, 60)) {
            $this->fail('Too many attempts. Please wait a minute.', 429, 'rate_limited');
        }

        $audiences = array_values(array_filter(array_map(
            'trim',
            explode(',', (string) ($this->app['google_client_ids'] ?? ''))
        )));
        if ($audiences === []) {
            $this->fail('Google login is not configured on this server.', 503, 'google_not_configured');
        }
        try {
            $claims = GoogleAuth::verifyIdToken((string) $this->param('id_token'), $audiences);
        } catch (RuntimeException $e) {
            $this->fail($e->getMessage(), 401, 'invalid_google_token');
        }

        $user = $this->user_model->findByGoogleSub($claims['sub']);
        if ($user) {
            if ((int) $user['is_active'] !== 1) {
                $this->fail('This account is disabled.', 403, 'account_disabled');
            }
            $this->user_model->touch((int) $user['id']);
            $this->ok([
                'user' => user_dto($user, true),
                'auth' => $this->token_model->issue($user, $this->device_name(), $this->app),
            ]);
        }

        // Google baru: client harus melengkapi profil dulu.
        $username = strtolower($this->str_param('username'));
        $displayName = $this->str_param('display_name');
        $password = (string) $this->param('password');
        if ($username === '' || $displayName === '' || $password === '') {
            $this->fail('Lengkapi profil untuk membuat akun.', 409, 'google_register_required', [
                'google' => [
                    'email' => $claims['email'],
                    'name' => $claims['name'],
                    'picture' => $claims['picture'],
                ],
            ]);
        }

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
        if ($this->user_model->usernameExists($username)) {
            $this->fail('Username is already taken.', 409, 'username_taken');
        }

        $userId = $this->user_model->create([
            'username' => $username,
            'phone' => null,
            'password_hash' => password_hash($password, PASSWORD_DEFAULT),
            'display_name' => $displayName,
            'avatar_url' => $this->str_param('avatar_url') !== '' ? $this->str_param('avatar_url') : ($claims['picture'] !== '' ? $claims['picture'] : null),
            'google_sub' => $claims['sub'],
            'google_email' => $claims['email'],
        ]);
        $user = $this->user_model->find($userId);
        $this->user_model->touch($userId);

        $this->ok([
            'user' => user_dto($user, true),
            'auth' => $this->token_model->issue($user, $this->device_name(), $this->app),
        ], 201);
    }

    /**
     * Minta token reset kata sandi. Selalu 200 agar username/HP tidak bisa
     * dienumerasi. Demo: di development token dikembalikan langsung karena
     * belum ada gateway SMS/email; di produksi token hanya dicatat di log.
     */
    public function forgot(): void
    {
        $this->require_method(['POST']);
        $this->require_fields(['login']);
        if (!$this->throttle('forgot:' . client_ip(), 10, 3600)) {
            $this->fail('Too many requests. Try again later.', 429, 'rate_limited');
        }

        $identifier = strtolower($this->str_param('login'));
        $user = $identifier !== '' ? $this->user_model->findByLogin($identifier) : null;
        if ($user && (int) $user['is_active'] === 1) {
            $token = bin2hex(random_bytes(24));
            $this->store->insert('password_resets', [
                'user_id' => (int) $user['id'],
                'token_hash' => hash('sha256', $token),
                'expires_at' => iso_plus(900),
                'created_at' => now_iso(),
            ]);
            if (($this->app['env'] ?? 'production') !== 'production') {
                $this->ok(['sent' => true, 'reset_token' => $token, 'expires_in' => 900]);
            }
            log_message('info', 'Password reset requested for user ' . (int) $user['id']);
        }
        $this->ok(['sent' => true]);
    }

    public function reset(): void
    {
        $this->require_method(['POST']);
        $this->require_fields(['token', 'new_password']);
        if (!$this->throttle('reset:' . client_ip(), 20, 3600)) {
            $this->fail('Too many requests. Try again later.', 429, 'rate_limited');
        }

        $row = $this->store->one(
            'SELECT * FROM password_resets WHERE token_hash = ? AND used_at IS NULL',
            [hash('sha256', $this->str_param('token'))]
        );
        if (!$row || strtotime($row['expires_at']) < time()) {
            $this->fail('Reset token is invalid or expired.', 401, 'invalid_reset_token');
        }
        $new = (string) $this->param('new_password');
        if (mb_strlen($new) < 6 || mb_strlen($new) > 72) {
            $this->fail('New password must be 6-72 characters.', 422, 'invalid_password');
        }
        $user = $this->user_model->find((int) $row['user_id']);
        if (!$user || (int) $user['is_active'] !== 1) {
            $this->fail('Account not found.', 404, 'not_found');
        }

        $this->store->update('password_resets', ['used_at' => now_iso()], 'id = ?', [(int) $row['id']]);
        $this->user_model->setPassword((int) $user['id'], password_hash($new, PASSWORD_DEFAULT));
        $this->token_model->revokeAll((int) $user['id']);
        $this->ok(['message' => 'Password has been reset. Please log in.']);
    }

    /**
     * Daftar sesi aktif milik sendiri (untuk layar "Perangkat saya").
     */
    public function sessions(): void
    {
        $this->require_method(['GET']);
        $user = $this->require_auth();
        $rows = $this->token_model->listActive((int) $user['id']);
        $this->ok(['sessions' => array_map(
            static fn (array $r): array => [
                'jti' => $r['jti'],
                'device' => $r['device'],
                'created_at' => $r['created_at'],
                'last_used_at' => $r['last_used_at'],
                'expires_at' => $r['expires_at'],
            ],
            $rows
        )]);
    }

    public function revoke_session(): void
    {
        $this->require_method(['POST', 'DELETE']);
        $user = $this->require_auth();
        $this->require_fields(['jti']);
        $jti = $this->str_param('jti');
        if ($jti === '' || !$this->token_model->revokeByJti((int) $user['id'], $jti)) {
            $this->fail('Session not found.', 404, 'not_found');
        }
        $this->ok(['revoked' => true]);
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
