<?php
declare(strict_types=1);
/**
 * Router for the PHP built-in server:
 *   php -S 0.0.0.0:8080 router.php
 *
 * Serves uploaded media from storage/uploads and sends every other request to
 * the CodeIgniter front controller.
 */
$uri = (string) parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);

if ($uri !== '/' && str_starts_with($uri, '/uploads/')) {
    $file = __DIR__ . '/storage' . $uri;
    if (is_file($file)) {
        $ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
        $mimes = [
            'jpg' => 'image/jpeg', 'jpeg' => 'image/jpeg', 'png' => 'image/png',
            'webp' => 'image/webp', 'gif' => 'image/gif', 'mp4' => 'video/mp4',
            'm4a' => 'audio/mp4', 'mp3' => 'audio/mpeg', 'ogg' => 'audio/ogg',
            'pdf' => 'application/pdf',
        ];
        header('Content-Type: ' . ($mimes[$ext] ?? 'application/octet-stream'));
        header('Content-Length: ' . filesize($file));
        header('Cache-Control: public, max-age=604800');
        readfile($file);
        return true;
    }
    http_response_code(404);
    header('Content-Type: application/json');
    echo json_encode(['ok' => false, 'error' => ['code' => 'not_found', 'message' => 'File not found']]);
    return true;
}

if ($uri === '/favicon.ico') {
    http_response_code(204);
    return true;
}

require __DIR__ . '/index.php';
