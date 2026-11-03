<?php
declare(strict_types=1);
defined('BASEPATH') OR exit('No direct script access allowed');

class Uploads extends MY_Controller
{
    private const ALLOWED = [
        'image/jpeg' => 'jpg',
        'image/png' => 'png',
        'image/webp' => 'webp',
        'image/gif' => 'gif',
        'audio/mp4' => 'm4a',
        'audio/mpeg' => 'mp3',
        'audio/ogg' => 'ogg',
        'video/mp4' => 'mp4',
        'application/pdf' => 'pdf',
    ];

    public function create(): void
    {
        $this->require_method(['POST']);
        $user = $this->require_auth();

        if (!$this->throttle('upload:' . (int) $user['id'], 60, 3600)) {
            $this->fail('Upload limit reached. Try again later.', 429, 'rate_limited');
        }
        if (empty($_FILES['file']) || !is_array($_FILES['file'])) {
            $this->fail('Attach a file using the "file" field.', 422, 'no_file');
        }

        $file = $_FILES['file'];
        if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
            $this->fail('Upload failed with error code ' . (int) $file['error'] . '.', 422, 'upload_error');
        }

        $maxBytes = (int) $this->app['upload_max_mb'] * 1024 * 1024;
        if ((int) $file['size'] <= 0 || (int) $file['size'] > $maxBytes) {
            $this->fail('File must be smaller than ' . $this->app['upload_max_mb'] . ' MB.', 413, 'file_too_large');
        }

        $finfo = new finfo(FILEINFO_MIME_TYPE);
        $mime = $finfo->file($file['tmp_name']) ?: '';
        if (!isset(self::ALLOWED[$mime])) {
            $this->fail('Unsupported file type: ' . $mime, 422, 'unsupported_type');
        }

        $extension = self::ALLOWED[$mime];
        $relativeDir = 'uploads/' . gmdate('Y/m');
        $absoluteDir = FCPATH . 'storage/' . $relativeDir;
        if (!is_dir($absoluteDir) && !mkdir($absoluteDir, 0775, true) && !is_dir($absoluteDir)) {
            $this->fail('Could not create the upload directory.', 500, 'storage_error');
        }

        $name = bin2hex(random_bytes(16)) . '.' . $extension;
        $absolutePath = $absoluteDir . '/' . $name;
        if (!move_uploaded_file($file['tmp_name'], $absolutePath)) {
            $this->fail('Could not store the uploaded file.', 500, 'storage_error');
        }
        @chmod($absolutePath, 0644);

        $meta = [
            'name' => mb_substr((string) ($file['name'] ?? $name), 0, 200),
            'size' => (int) $file['size'],
            'mime' => $mime,
        ];
        if (str_starts_with($mime, 'image/')) {
            $size = @getimagesize($absolutePath);
            if ($size) {
                $meta['width'] = (int) $size[0];
                $meta['height'] = (int) $size[1];
            }
        }

        $this->ok([
            'url' => absolute_upload_url($relativeDir . '/' . $name),
            'path' => $relativeDir . '/' . $name,
            'meta' => $meta,
        ], 201);
    }
}
