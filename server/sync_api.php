<?php

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With');

date_default_timezone_set('Asia/Kolkata');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

$storageRoot = __DIR__ . DIRECTORY_SEPARATOR . 'storage';
if (!is_dir($storageRoot)) {
    mkdir($storageRoot, 0775, true);
}

$action = $_REQUEST['action'] ?? '';

switch ($action) {
    case 'status':
        $folder = sanitize_folder($_GET['folder'] ?? '');
        if ($folder === '') {
            json_error('Folder is required.', 400);
        }
        json_ok([
            'folder' => $folder,
            'file_count' => count_page_files(folder_path($storageRoot, $folder)),
        ]);
        break;

    case 'folders':
        $folders = [];
        foreach (glob($storageRoot . DIRECTORY_SEPARATOR . '*', GLOB_ONLYDIR) ?: [] as $directory) {
            $folder = basename($directory);
            $folders[] = [
                'folder' => $folder,
                'file_count' => count_page_files($directory),
            ];
        }
        usort($folders, static fn(array $a, array $b): int => strcmp($a['folder'], $b['folder']));
        json_ok(['folders' => $folders]);
        break;

    case 'manifest':
        $folder = sanitize_folder($_GET['folder'] ?? '');
        if ($folder === '') {
            json_error('Folder is required.', 400);
        }
        $directory = folder_path($storageRoot, $folder);
        $metadata = read_metadata($directory);
        $files = [];
        $htmlFiles = glob($directory . DIRECTORY_SEPARATOR . '*.html') ?: [];
        $mhtFiles = glob($directory . DIRECTORY_SEPARATOR . '*.mht') ?: [];
        foreach (array_merge($htmlFiles, $mhtFiles) as $filePath) {
            $fileName = basename($filePath);
            $entry = $metadata[$fileName] ?? [];
            $files[] = [
                'name' => $fileName,
                'size' => filesize($filePath),
                'updated_at' => (int) ($entry['updated_at'] ?? filemtime($filePath)),
                'original_url' => (string) ($entry['original_url'] ?? ''),
                'title' => (string) ($entry['title'] ?? pathinfo($fileName, PATHINFO_FILENAME)),
                'row_index' => isset($entry['row_index']) ? (int) $entry['row_index'] : -1,
            ];
        }
        usort($files, static fn(array $a, array $b): int => strcmp($a['name'], $b['name']));
        json_ok([
            'folder' => $folder,
            'files' => $files,
        ]);
        break;

    case 'download':
        $folder = sanitize_folder($_GET['folder'] ?? '');
        $file = sanitize_file($_GET['file'] ?? '');
        if ($folder === '' || $file === '') {
            json_error('Folder and file are required.', 400);
        }
        $path = folder_path($storageRoot, $folder) . DIRECTORY_SEPARATOR . $file;
        if (!is_file($path)) {
            json_error('Requested file was not found.', 404);
        }
        if (str_ends_with(strtolower($file), '.mht')) {
            header('Content-Type: message/rfc822');
        } else {
            header('Content-Type: text/html; charset=UTF-8');
        }
        header('Content-Length: ' . (string) filesize($path));
        header('Content-Disposition: inline; filename="' . basename($file) . '"');
        log_sync_event($storageRoot, 'download', $folder, $file);
        readfile($path);
        exit;

    case 'delete':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
            json_error('Delete must use POST.', 405);
        }
        $folder = sanitize_folder($_POST['folder'] ?? '');
        $file = sanitize_file($_POST['file'] ?? '');
        $reason = trim((string) ($_POST['reason'] ?? ''));
        if ($folder === '' || $file === '') {
            json_error('Folder and file are required.', 400);
        }
        $directory = folder_path($storageRoot, $folder);
        $path = $directory . DIRECTORY_SEPARATOR . $file;
        $metadata = read_metadata($directory);
        $entry = $metadata[$file] ?? [];
        $title = (string) ($entry['title'] ?? pathinfo($file, PATHINFO_FILENAME));

        if (is_file($path) && !unlink($path)) {
            json_error('Failed to delete server file.', 500);
        }
        remove_metadata_entry($directory, $file);
        $actionTag = $reason === 'moderated' ? 'moderated' : 'delete';
        log_sync_event($storageRoot, $actionTag, $folder, $file, $title);
        json_ok([
            'folder' => $folder,
            'filename' => $file,
            'action' => $actionTag,
        ]);
        break;

    case 'upload':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
            json_error('Upload must use POST.', 405);
        }
        $folder = sanitize_folder($_POST['folder'] ?? '');
        $filename = sanitize_file($_POST['filename'] ?? '');
        if ($folder === '' || $filename === '') {
            json_error('Folder and filename are required.', 400);
        }
        if (!isset($_FILES['html_file']) || $_FILES['html_file']['error'] !== UPLOAD_ERR_OK) {
            json_error('No HTML file was uploaded.', 400);
        }
        $directory = folder_path($storageRoot, $folder);
        if (!is_dir($directory) && !mkdir($directory, 0775, true) && !is_dir($directory)) {
            json_error('Could not create server folder.', 500);
        }
        $target = $directory . DIRECTORY_SEPARATOR . $filename;
        if (!move_uploaded_file($_FILES['html_file']['tmp_name'], $target)) {
            json_error('Failed to save uploaded file.', 500);
        }
        $title = (string) ($_POST['title'] ?? '');
        update_metadata(
            $directory,
            $filename,
            (string) ($_POST['original_url'] ?? ''),
            $title,
            isset($_POST['row_index']) ? (int) $_POST['row_index'] : -1
        );
        log_sync_event($storageRoot, 'upload', $folder, $filename, $title);
        json_ok([
            'folder' => $folder,
            'filename' => $filename,
        ]);
        break;

    default:
        json_error('Unsupported action.', 400);
}

function folder_path(string $storageRoot, string $folder): string
{
    return $storageRoot . DIRECTORY_SEPARATOR . $folder;
}

function sanitize_folder(string $value): string
{
    $sanitized = preg_replace('/[^A-Za-z0-9_-]+/', '_', trim($value)) ?? '';
    $sanitized = trim($sanitized, '_');
    return substr($sanitized, 0, 80);
}

function sanitize_file(string $value): string
{
    $name = basename(trim($value));
    if (!preg_match('/^[A-Za-z0-9._-]+\.(html|mht)$/i', $name)) {
        return '';
    }
    return $name;
}

function count_page_files(string $directory): int
{
    if (!is_dir($directory)) {
        return 0;
    }
    $htmlCount = count(glob($directory . DIRECTORY_SEPARATOR . '*.html') ?: []);
    $mhtCount = count(glob($directory . DIRECTORY_SEPARATOR . '*.mht') ?: []);
    return $htmlCount + $mhtCount;
}

function read_metadata(string $directory): array
{
    $metadataPath = $directory . DIRECTORY_SEPARATOR . 'metadata.json';
    if (!is_file($metadataPath)) {
        return [];
    }
    $decoded = json_decode((string) file_get_contents($metadataPath), true);
    return is_array($decoded) ? $decoded : [];
}

function update_metadata(string $directory, string $filename, string $originalUrl, string $title, int $rowIndex): void
{
    $metadata = read_metadata($directory);
    $previous = is_array($metadata[$filename] ?? null) ? $metadata[$filename] : [];
    $metadata[$filename] = [
        'original_url' => $originalUrl !== '' ? $originalUrl : (string) ($previous['original_url'] ?? ''),
        'title' => $title !== '' ? $title : (string) ($previous['title'] ?? pathinfo($filename, PATHINFO_FILENAME)),
        'row_index' => $rowIndex >= 0 ? $rowIndex : (int) ($previous['row_index'] ?? -1),
        'updated_at' => time(),
    ];
    write_metadata($directory, $metadata);
}

function remove_metadata_entry(string $directory, string $filename): void
{
    $metadata = read_metadata($directory);
    unset($metadata[$filename]);
    write_metadata($directory, $metadata);
}

function write_metadata(string $directory, array $metadata): void
{
    $metadataPath = $directory . DIRECTORY_SEPARATOR . 'metadata.json';
    if (empty($metadata)) {
        if (is_file($metadataPath)) {
            unlink($metadataPath);
        }
        return;
    }
    file_put_contents($metadataPath, json_encode($metadata, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
}

function json_ok(array $payload): void
{
    header('Content-Type: application/json; charset=UTF-8');
    echo json_encode(array_merge(['ok' => true], $payload), JSON_UNESCAPED_SLASHES);
    exit;
}

function json_error(string $message, int $statusCode): void
{
    http_response_code($statusCode);
    header('Content-Type: application/json; charset=UTF-8');
    echo json_encode([
        'ok' => false,
        'message' => $message,
    ], JSON_UNESCAPED_SLASHES);
    exit;
}

function log_sync_event(string $storageRoot, string $action, string $folder, string $filename, string $title = ''): void
{
    $historyPath = $storageRoot . DIRECTORY_SEPARATOR . 'history.json';
    $history = [];
    if (is_file($historyPath)) {
        $decoded = json_decode((string) file_get_contents($historyPath), true);
        if (is_array($decoded)) {
            $history = $decoded;
        }
    }
    
    array_unshift($history, [
        'timestamp' => time(),
        'action' => $action,
        'folder' => $folder,
        'filename' => $filename,
        'title' => $title !== '' ? $title : pathinfo($filename, PATHINFO_FILENAME),
        'ip' => $_SERVER['REMOTE_ADDR'] ?? '127.0.0.1',
    ]);

    if (count($history) > 300) {
        $history = array_slice($history, 0, 300);
    }

    @file_put_contents($historyPath, json_encode($history, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
}