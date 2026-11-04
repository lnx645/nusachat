<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * JSON API base controller: CORS, JSON body parsing, bearer auth, throttling.
 */
class MY_Controller extends CI_Controller
{
    protected array $app;

    protected array $payload = [];

    protected ?array $user = null;

    public function __construct()
    {
        parent::__construct();

        $this->config->load('app');
        $this->app = $this->config->item('app');
        $this->assert_secret();
        $this->load->library('store');
        $this->load->helper('api');

        $this->send_cors();

        if (($this->input->method() === 'options')) {
            http_response_code(204);
            exit;
        }

        $contentType = $_SERVER['CONTENT_TYPE'] ?? '';
        if (str_contains(strtolower($contentType), 'application/json')) {
            $raw = file_get_contents('php://input');
            $decoded = json_decode($raw, true);
            if (is_array($decoded)) {
                $this->payload = $decoded;
            }
        }
    }

    protected function send_cors(): void
    {
        $allowed = $this->app['cors_origins'] ?? ['*'];
        $requestOrigin = trim((string) ($_SERVER['HTTP_ORIGIN'] ?? ''));
        if (in_array('*', $allowed, true)) {
            // Dev default. Jangan pakai '*' di produksi (lihat .env.example).
            header('Access-Control-Allow-Origin: *');
        } elseif ($requestOrigin !== '' && in_array($requestOrigin, $allowed, true)) {
            header('Access-Control-Allow-Origin: ' . $requestOrigin);
            header('Vary: Origin');
        } else {
            // Origin tidak dikenal: tanpa header ACAO, browser memblokir.
            // Request non-browser (curl, Android) tetap jalan.
            header('Vary: Origin');
        }
        header('Access-Control-Allow-Methods: GET, POST, PATCH, PUT, DELETE, OPTIONS');
        header('Access-Control-Allow-Headers: Authorization, Content-Type, X-Requested-With');
        header('Access-Control-Max-Age: 86400');
        header('X-Content-Type-Options: nosniff');
    }

    /**
     * Fail-closed bila secret dev dipakai di production: menolak boot
     * daripada menandatangani JWT dengan kunci publik.
     */
    protected function assert_secret(): void
    {
        $env = (string) ($this->app['env'] ?? 'production');
        $secret = (string) ($this->app['secret'] ?? '');
        $weak = ['dev-secret-change-me', 'dev-secret-change-me-please-32-bytes-min', 'changeme', ''];
        if ($env === 'production' && (strlen($secret) < 32 || in_array($secret, $weak, true))) {
            http_response_code(500);
            header('Content-Type: application/json; charset=utf-8');
            echo json_encode(['ok' => false, 'error' => ['code' => 'misconfigured', 'message' => 'Server misconfigured.']]);
            exit;
        }
    }

    protected function json(array $body, int $status = 200): never
    {
        http_response_code($status);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        exit;
    }

    protected function ok(mixed $data = [], int $status = 200): never
    {
        $this->json(['ok' => true, 'data' => $data], $status);
    }

    protected function fail(string $message, int $status = 400, string $code = 'bad_request', array $extra = []): never
    {
        $error = ['code' => $code, 'message' => $message];
        if ($extra) {
            $error['details'] = $extra;
        }
        $this->json(['ok' => false, 'error' => $error], $status);
    }

    protected function require_method(array $methods): void
    {
        $method = strtoupper($this->input->method());
        $methods = array_map('strtoupper', $methods);
        if (!in_array($method, $methods, true)) {
            header('Allow: ' . implode(', ', $methods));
            $this->fail('Method not allowed', 405, 'method_not_allowed');
        }
    }

    protected function param(string $key, mixed $default = null): mixed
    {
        if (array_key_exists($key, $this->payload)) {
            return $this->payload[$key];
        }
        $value = $this->input->get($key, true);
        return $value !== null ? $value : $default;
    }

    protected function str_param(string $key, string $default = ''): string
    {
        $value = $this->param($key, $default);
        if (is_string($value)) {
            return trim($value);
        }
        return $default;
    }

    protected function int_param(string $key, int $default = 0): int
    {
        $value = $this->param($key, $default);
        return is_numeric($value) ? (int) $value : $default;
    }

    protected function bool_param(string $key, bool $default = false): bool
    {
        $value = $this->param($key, $default ? 1 : 0);
        return in_array($value, [true, 1, '1', 'true', 'yes', 'on'], true);
    }

    protected function require_fields(array $fields): void
    {
        $missing = [];
        foreach ($fields as $field) {
            $value = $this->param($field);
            if ($value === null || (is_string($value) && trim($value) === '')) {
                $missing[] = $field;
            }
        }
        if ($missing) {
            $this->fail('Missing required field(s): ' . implode(', ', $missing), 422, 'validation_error', ['fields' => $missing]);
        }
    }

    protected function require_auth(): array
    {
        if ($this->user !== null) {
            return $this->user;
        }

        $header = $this->authorization_header();
        if (!preg_match('/Bearer\s+(.+)/i', $header, $matches)) {
            $this->fail('Authorization token required', 401, 'unauthorized');
        }

        $claims = Jwt::decode(trim($matches[1]), $this->app['secret']);
        if (!$claims || ($claims['typ'] ?? '') !== 'access' || empty($claims['sub'])) {
            $this->fail('Invalid or expired token', 401, 'token_expired');
        }

        $user = $this->store->one('SELECT * FROM users WHERE id = ? AND is_active = 1', [(int) $claims['sub']]);
        if (!$user) {
            $this->fail('Account not found', 401, 'unauthorized');
        }
        if ((int) $user['token_version'] !== (int) ($claims['tv'] ?? 0)) {
            $this->fail('Session has been revoked, please log in again', 401, 'token_revoked');
        }

        $this->user = $user;
        $this->store->update('users', ['last_seen_at' => now_iso()], 'id = ?', [(int) $user['id']]);
        return $user;
    }

    protected function authorization_header(): string
    {
        if (function_exists('getallheaders')) {
            $headers = getallheaders();
            if (is_array($headers)) {
                foreach ($headers as $name => $value) {
                    if (strcasecmp($name, 'Authorization') === 0) {
                        return (string) $value;
                    }
                }
            }
        }
        foreach (['HTTP_AUTHORIZATION', 'REDIRECT_HTTP_AUTHORIZATION'] as $key) {
            if (!empty($_SERVER[$key])) {
                return (string) $_SERVER[$key];
            }
        }
        return '';
    }

    /**
     * Fixed-window rate limiter. Returns true when the request is allowed.
     */
    protected function throttle(string $key, int $max, int $windowSeconds = 60): bool
    {
        if ($max <= 0) {
            return true;
        }
        $window = intdiv(time(), $windowSeconds) * $windowSeconds;
        $hash = hash('sha256', $key . ':' . $window);
        try {
            $this->store->run(
                'INSERT INTO rate_limits (key_hash, window_start, count) VALUES (?, ?, 1)',
                [$hash, $window]
            );
            return true;
        } catch (Throwable $e) {
            $this->store->run('UPDATE rate_limits SET count = count + 1 WHERE key_hash = ?', [$hash]);
            $row = $this->store->one('SELECT count FROM rate_limits WHERE key_hash = ?', [$hash]);
            return $row && (int) $row['count'] <= $max;
        }
    }

    protected function direct_conversation_key(int $a, int $b): string
    {
        return min($a, $b) . ':' . max($a, $b);
    }
}
