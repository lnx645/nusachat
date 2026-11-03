<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

/**
 * FCM sender, no Composer dependency.
 *
 * Primary: HTTP v1 API using a service-account JSON
 *   (FCM_SERVICE_JSON + FCM_PROJECT_ID in .env).
 * Fallback: legacy HTTP API using FCM_SERVER_KEY.
 * When neither is configured, send() returns false and the API keeps
 * working over the realtime websocket.
 */
final class Fcm
{
    public function __construct(
        private readonly string $serverKey = '',
        private readonly string $serviceJson = '',
        private readonly string $projectId = '',
    ) {
    }

    public static function fromEnv(): self
    {
        return new self(
            (string) (getenv('FCM_SERVER_KEY') ?: ''),
            (string) (getenv('FCM_SERVICE_JSON') ?: ''),
            (string) (getenv('FCM_PROJECT_ID') ?: ''),
        );
    }

    public function enabled(): bool
    {
        return $this->resolveServiceJson() !== '' || $this->resolveServerKey() !== '';
    }

    public function v1Enabled(): bool
    {
        return $this->resolveServiceJson() !== '';
    }

    /**
     * @param list<string> $tokens
     */
    public function send(array $tokens, string $title, string $body, array $data = []): bool
    {
        $tokens = array_values(array_unique(array_filter($tokens)));
        if (!$tokens) {
            return false;
        }
        if ($this->v1Enabled()) {
            return $this->sendV1($tokens, $title, $body, $data);
        }
        if ($this->resolveServerKey() !== '') {
            return $this->sendLegacy($tokens, $title, $body, $data);
        }
        return false;
    }

    /**
     * Best-effort helper used by controllers: never throws.
     *
     * @param list<string> $tokens
     */
    public function sendSilent(array $tokens, string $title, string $body, array $data = []): void
    {
        try {
            $this->send($tokens, $title, $body, $data);
        } catch (Throwable) {
            // Push must never break the API response.
        }
    }

    // ------------------------------------------------------------- HTTP v1

    /**
     * @param list<string> $tokens
     */
    private function sendV1(array $tokens, string $title, string $body, array $data): bool
    {
        $serviceFile = $this->resolveServiceJson();
        $service = json_decode(@file_get_contents($serviceFile), true);
        if (!is_array($service) || empty($service['private_key']) || empty($service['client_email'])) {
            return false;
        }
        $projectId = $this->projectId !== '' ? $this->projectId : (string) ($service['project_id'] ?? '');
        if ($projectId === '') {
            return false;
        }
        $accessToken = $this->v1AccessToken($service);
        if ($accessToken === null) {
            return false;
        }
        $flat = ['title' => $title, 'body' => $body];
        foreach ($data as $k => $v) {
            if (is_scalar($v) || $v === null) {
                $flat[(string) $k] = $v === null ? '' : (string) $v;
            }
        }
        $url = 'https://fcm.googleapis.com/v1/projects/' . $projectId . '/messages:send';
        $sent = 0;
        foreach (array_slice($tokens, 0, 500) as $token) {
            $payload = [
                'message' => [
                    'token' => $token,
                    'data' => $flat,
                    'android' => ['priority' => 'high'],
                ],
            ];
            [$code] = $this->curlJson($url, $payload, ['Authorization: Bearer ' . $accessToken]);
            if ($code >= 200 && $code < 300) {
                $sent++;
                continue;
            }
            // One retry with a fresh access token on auth errors.
            if ($code === 401) {
                $fresh = $this->v1AccessToken($service, true);
                if ($fresh !== null) {
                    [$retryCode] = $this->curlJson($url, $payload, ['Authorization: Bearer ' . $fresh]);
                    if ($retryCode >= 200 && $retryCode < 300) {
                        $sent++;
                    }
                }
            }
        }
        return $sent > 0;
    }

    /**
     * OAuth2 access token for https://www.googleapis.com/auth/firebase.messaging,
     * cached on disk until shortly before expiry.
     *
     * @param array<string,mixed> $service
     */
    private function v1AccessToken(array $service, bool $forceRefresh = false): ?string
    {
        $cacheFile = $this->baseDir() . 'storage/fcm-oauth-cache.json';
        if (!$forceRefresh && is_file($cacheFile)) {
            $cached = json_decode(@file_get_contents($cacheFile), true);
            if (is_array($cached) && !empty($cached['access_token']) && ($cached['expires_at'] ?? 0) > time() + 120) {
                return (string) $cached['access_token'];
            }
        }
        $tokenUri = (string) ($service['token_uri'] ?? 'https://oauth2.googleapis.com/token');
        $now = time();
        $jwt = $this->jwtRs256(
            ['alg' => 'RS256', 'typ' => 'JWT'],
            [
                'iss' => $service['client_email'],
                'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
                'aud' => $tokenUri,
                'iat' => $now,
                'exp' => $now + 3600,
            ],
            (string) $service['private_key']
        );
        if ($jwt === null) {
            return null;
        }
        [, $decoded] = $this->httpPost($tokenUri, http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion' => $jwt,
        ]), 'application/x-www-form-urlencoded');
        if (!is_array($decoded) || empty($decoded['access_token'])) {
            return null;
        }
        $expiresIn = (int) ($decoded['expires_in'] ?? 3600);
        @file_put_contents(
            $cacheFile,
            json_encode(['access_token' => $decoded['access_token'], 'expires_at' => $now + $expiresIn]),
            LOCK_EX
        );
        @chmod($cacheFile, 0600);
        return (string) $decoded['access_token'];
    }

    /** @param array<string,mixed> $header @param array<string,mixed> $claims */
    private function jwtRs256(array $header, array $claims, string $privateKey): ?string
    {
        $b64 = static fn (string $raw): string => rtrim(strtr(base64_encode($raw), '+/', '-_'), '=');
        $signingInput = $b64(json_encode($header, JSON_UNESCAPED_SLASHES) ?: '') . '.' .
            $b64(json_encode($claims, JSON_UNESCAPED_SLASHES) ?: '');
        $signature = '';
        if (!openssl_sign($signingInput, $signature, $privateKey, OPENSSL_ALGO_SHA256)) {
            return null;
        }
        return $signingInput . '.' . $b64($signature);
    }

    // -------------------------------------------------------------- legacy

    /**
     * @param list<string> $tokens
     */
    private function sendLegacy(array $tokens, string $title, string $body, array $data): bool
    {
        $payload = [
            'registration_ids' => array_slice($tokens, 0, 500),
            'priority' => 'high',
            'data' => array_merge([
                'title' => $title,
                'body' => $body,
                'click_action' => 'FLUTTER_NOTIFICATION_CLICK',
            ], $data),
        ];
        [$code, $response] = $this->curlJson(
            'https://fcm.googleapis.com/fcm/send',
            $payload,
            ['Authorization: key=' . $this->resolveServerKey()]
        );
        return $response !== null && $code >= 200 && $code < 300;
    }

    // ---------------------------------------------------------------- helpers

    private function resolveServerKey(): string
    {
        if ($this->serverKey !== '') {
            return $this->serverKey;
        }
        return (string) (getenv('FCM_SERVER_KEY') ?: '');
    }

    private function resolveServiceJson(): string
    {
        $configured = $this->serviceJson !== '' ? $this->serviceJson : (string) (getenv('FCM_SERVICE_JSON') ?: '');
        if ($configured === '') {
            return '';
        }
        if (str_starts_with($configured, '/')) {
            return is_file($configured) ? $configured : '';
        }
        $full = $this->baseDir() . ltrim($configured, '/');
        return is_file($full) ? $full : '';
    }

    private function baseDir(): string
    {
        if (defined('FCPATH')) {
            return rtrim(FCPATH, '/') . '/';
        }
        return dirname(__DIR__, 2) . '/';
    }

    /**
     * POST helper based on PHP streams (no curl extension required).
     *
     * @param array<string,mixed>|string $body
     * @return array{0:int,1:mixed} HTTP status code + decoded JSON (or null)
     */
    private function httpPost(string $url, array|string $body, string $contentType): array
    {
        $raw = is_string($body)
            ? $body
            : json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        $context = stream_context_create([
            'http' => [
                'method' => 'POST',
                'header' => "Content-Type: {$contentType}\r\nAccept: application/json\r\n",
                'content' => $raw,
                'timeout' => $this->timeoutSeconds(),
                'ignore_errors' => true,
            ],
            'ssl' => ['verify_peer' => true, 'verify_peer_name' => true],
        ]);
        $response = @file_get_contents($url, false, $context);
        $code = 0;
        if (isset($http_response_header) && is_array($http_response_header)) {
            foreach ($http_response_header as $header) {
                if (preg_match('#^HTTP/\S+\s+(\d{3})#i', $header, $m)) {
                    $code = (int) $m[1];
                }
            }
        }
        $decoded = is_string($response) ? json_decode($response, true) : null;
        return [$code, $decoded];
    }

    /**
     * @param array<string,mixed> $payload
     * @param list<string> $extraHeaders use "Name: value" format
     * @return array{0:int,1:mixed}
     */
    private function curlJson(string $url, array $payload, array $extraHeaders): array
    {
        $raw = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        $headers = "Content-Type: application/json\r\nAccept: application/json\r\n";
        foreach ($extraHeaders as $header) {
            $headers .= $header . "\r\n";
        }
        $context = stream_context_create([
            'http' => [
                'method' => 'POST',
                'header' => $headers,
                'content' => $raw,
                'timeout' => $this->timeoutSeconds(),
                'ignore_errors' => true,
            ],
            'ssl' => ['verify_peer' => true, 'verify_peer_name' => true],
        ]);
        $response = @file_get_contents($url, false, $context);
        $code = 0;
        if (isset($http_response_header) && is_array($http_response_header)) {
            foreach ($http_response_header as $header) {
                if (preg_match('#^HTTP/\S+\s+(\d{3})#i', $header, $m)) {
                    $code = (int) $m[1];
                }
            }
        }
        $decoded = is_string($response) ? json_decode($response, true) : null;
        return [$code, $decoded];
    }

    private function timeoutSeconds(): int
    {
        return 10;
    }
}
