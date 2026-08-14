<?php

declare(strict_types=1);

date_default_timezone_set('Asia/Kolkata');

$storageRoot = __DIR__ . DIRECTORY_SEPARATOR . 'storage';
if (!is_dir($storageRoot)) {
    mkdir($storageRoot, 0775, true);
}

// 1. Calculate Server Stats & Load Folder Manifests
$folders = [];
$totalFiles = 0;
$totalSize = 0;
$latestSyncTimestamp = 0;
$folderChartLabels = [];
$folderChartData = [];
$folderFileCounts = [];

$dirEntries = glob($storageRoot . DIRECTORY_SEPARATOR . '*', GLOB_ONLYDIR) ?: [];
foreach ($dirEntries as $directory) {
    $folderName = basename($directory);
    $metadataPath = $directory . DIRECTORY_SEPARATOR . 'metadata.json';
    $metadata = [];
    if (is_file($metadataPath)) {
        $decoded = json_decode((string) file_get_contents($metadataPath), true);
        if (is_array($decoded)) {
            $metadata = $decoded;
        }
    }

    $folderFiles = [];
    $folderSize = 0;
    $folderLatestSync = 0;

    $htmlFiles = glob($directory . DIRECTORY_SEPARATOR . '*.html') ?: [];
    $mhtFiles = glob($directory . DIRECTORY_SEPARATOR . '*.mht') ?: [];
    $allFiles = array_merge($htmlFiles, $mhtFiles);

    foreach ($allFiles as $filePath) {
        $fileName = basename($filePath);
        $fileSize = filesize($filePath);
        $fileMtime = filemtime($filePath);
        $metaEntry = $metadata[$fileName] ?? [];
        $updatedAt = (int) ($metaEntry['updated_at'] ?? $fileMtime);

        $folderSize += $fileSize;
        if ($updatedAt > $folderLatestSync) {
            $folderLatestSync = $updatedAt;
        }

        $folderFiles[] = [
            'name' => $fileName,
            'size' => $fileSize,
            'updated_at' => $updatedAt,
            'title' => (string) ($metaEntry['title'] ?? pathinfo($fileName, PATHINFO_FILENAME)),
            'original_url' => (string) ($metaEntry['original_url'] ?? ''),
            'row_index' => isset($metaEntry['row_index']) ? (int) $metaEntry['row_index'] : -1,
        ];
    }

    usort($folderFiles, static fn(array $a, array $b): int => strcmp($a['name'], $b['name']));

    $totalFiles += count($folderFiles);
    $totalSize += $folderSize;
    if ($folderLatestSync > $latestSyncTimestamp) {
        $latestSyncTimestamp = $folderLatestSync;
    }

    $folders[] = [
        'name' => $folderName,
        'file_count' => count($folderFiles),
        'total_size' => $folderSize,
        'latest_sync' => $folderLatestSync,
        'files' => $folderFiles,
    ];

    $folderChartLabels[] = str_replace('_', ' ', $folderName);
    $folderChartData[] = round($folderSize / (1024 * 1024), 2); // MB
    $folderFileCounts[] = count($folderFiles);
}

usort($folders, static fn(array $a, array $b): int => strcmp($a['name'], $b['name']));

// 2. Load & Group Sync History Log
$historyPath = $storageRoot . DIRECTORY_SEPARATOR . 'history.json';
$rawHistory = [];
if (is_file($historyPath)) {
    $decodedHistory = json_decode((string) file_get_contents($historyPath), true);
    if (is_array($decodedHistory)) {
        $rawHistory = $decodedHistory;
    }
}

// Action counts for Chart
$actionCounts = [
    'upload' => 0,
    'download' => 0,
    'delete' => 0,
    'moderated' => 0,
];

$moderatedEntries = [];

// Group consecutive / duplicate hits by action + folder + filename within 10-min window
$groupedHistory = [];
$currentGroup = null;

foreach ($rawHistory as $event) {
    $action = strtolower((string) ($event['action'] ?? 'sync'));
    if (isset($actionCounts[$action])) {
        $actionCounts[$action]++;
    } else {
        $actionCounts[$action] = 1;
    }

    if ($action === 'moderated') {
        $moderatedEntries[] = $event;
    }

    $folder = (string) ($event['folder'] ?? '');
    $filename = (string) ($event['filename'] ?? '');
    $title = (string) ($event['title'] ?? '');
    $timestamp = (int) ($event['timestamp'] ?? time());
    $ip = (string) ($event['ip'] ?? '');

    if ($currentGroup !== null &&
        $currentGroup['action'] === $action &&
        $currentGroup['folder'] === $folder &&
        $currentGroup['filename'] === $filename &&
        abs($currentGroup['latest_timestamp'] - $timestamp) < 600
    ) {
        $currentGroup['hits']++;
        $currentGroup['earliest_timestamp'] = min($currentGroup['earliest_timestamp'], $timestamp);
        $currentGroup['items'][] = $event;
    } else {
        if ($currentGroup !== null) {
            $groupedHistory[] = $currentGroup;
        }
        $currentGroup = [
            'id' => uniqid('grp_'),
            'action' => $action,
            'folder' => $folder,
            'filename' => $filename,
            'title' => $title,
            'latest_timestamp' => $timestamp,
            'earliest_timestamp' => $timestamp,
            'ip' => $ip,
            'hits' => 1,
            'items' => [$event],
        ];
    }
}
if ($currentGroup !== null) {
    $groupedHistory[] = $currentGroup;
}

if (isset($_GET['export']) && $_GET['export'] === 'csv') {
    header('Content-Type: text/csv; charset=UTF-8');
    header('Content-Disposition: attachment; filename="sync_audit_history_' . date('Y-m-d_H-i-s') . '.csv"');
    $output = fopen('php://output', 'w');
    fputcsv($output, ['Timestamp (IST)', 'Action', 'Folder', 'Title', 'File Name', 'Hits', 'Client IP']);
    foreach ($groupedHistory as $group) {
        fputcsv($output, [
            format_date($group['latest_timestamp']),
            strtoupper($group['action']),
            $group['folder'],
            $group['title'],
            $group['filename'],
            $group['hits'],
            $group['ip']
        ]);
    }
    fclose($output);
    exit;
}

$hourCounts = array_fill(0, 24, 0);
foreach ($rawHistory as $event) {
    if (isset($event['timestamp'])) {
        $dt = new DateTime("@" . $event['timestamp']);
        $dt->setTimezone(new DateTimeZone('Asia/Kolkata'));
        $hour = (int)$dt->format('G');
        $hourCounts[$hour]++;
    }
}
$peakHour = array_search(max($hourCounts), $hourCounts);
$peakHourFormatted = sprintf('%02d:00 - %02d:00 IST', $peakHour, ($peakHour + 1) % 24);

function format_bytes(int $bytes): string
{
    if ($bytes >= 1073741824) {
        return number_format($bytes / 1073741824, 2) . ' GB';
    }
    if ($bytes >= 1048576) {
        return number_format($bytes / 1048576, 2) . ' MB';
    }
    if ($bytes >= 1024) {
        return number_format($bytes / 1024, 2) . ' KB';
    }
    return $bytes . ' B';
}

function format_date(int $timestamp): string
{
    if ($timestamp <= 0) {
        return 'Never';
    }
    try {
        $dt = new DateTime("@$timestamp");
        $dt->setTimezone(new DateTimeZone('Asia/Kolkata'));
        return $dt->format('Y-m-d H:i:s') . ' IST';
    } catch (Exception $e) {
        return date('Y-m-d H:i:s', $timestamp) . ' IST';
    }
}

?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>WebRecorder - Cloud Storage &amp; Analytics Dashboard</title>
    <!-- Chart.js for Visual Data Analysis -->
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root {
            --bg-color: #0f172a;
            --surface-color: #1e293b;
            --card-color: #334155;
            --text-primary: #f8fafc;
            --text-secondary: #94a3b8;
            --primary-accent: #6366f1;
            --primary-hover: #4f46e5;
            --border-color: #475569;
            --success-color: #10b981;
            --warning-color: #f59e0b;
            --danger-color: #ef4444;
            --moderated-color: #ec4899;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        }

        body {
            background-color: var(--bg-color);
            color: var(--text-primary);
            padding: 24px;
            min-height: 100vh;
        }

        .container {
            max-width: 1280px;
            margin: 0 auto;
        }

        /* Header */
        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding-bottom: 24px;
            border-bottom: 1px solid var(--border-color);
            margin-bottom: 24px;
            flex-wrap: wrap;
            gap: 16px;
        }

        .header-title h1 {
            font-size: 1.75rem;
            font-weight: 700;
            color: var(--text-primary);
            display: flex;
            align-items: center;
            gap: 10px;
        }

        .header-title p {
            color: var(--text-secondary);
            font-size: 0.875rem;
            margin-top: 4px;
        }

        .server-status-pill {
            background-color: rgba(16, 185, 129, 0.15);
            color: var(--success-color);
            border: 1px solid rgba(16, 185, 129, 0.3);
            padding: 6px 14px;
            border-radius: 20px;
            font-size: 0.85rem;
            font-weight: 600;
            display: inline-flex;
            align-items: center;
            gap: 6px;
        }

        .server-status-pill::before {
            content: "";
            width: 8px;
            height: 8px;
            background-color: var(--success-color);
            border-radius: 50%;
            display: inline-block;
        }

        /* Stats Cards */
        .stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
            gap: 16px;
            margin-bottom: 24px;
        }

        .stat-card {
            background-color: var(--surface-color);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            padding: 20px;
            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
        }

        .stat-card .label {
            color: var(--text-secondary);
            font-size: 0.85rem;
            font-weight: 500;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }

        .stat-card .value {
            font-size: 1.75rem;
            font-weight: 700;
            color: var(--text-primary);
            margin-top: 8px;
        }

        .stat-card .subtext {
            color: var(--text-secondary);
            font-size: 0.8rem;
            margin-top: 6px;
        }

        /* Charts Grid Section */
        .charts-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
            gap: 20px;
            margin-bottom: 32px;
        }

        .chart-card {
            background-color: var(--surface-color);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            padding: 20px;
        }

        .chart-card h3 {
            font-size: 1rem;
            font-weight: 600;
            margin-bottom: 16px;
            color: var(--text-primary);
            display: flex;
            align-items: center;
            gap: 8px;
        }

        .chart-container {
            position: relative;
            height: 250px;
            width: 100%;
        }

        /* Filter Toolbar */
        .toolbar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 20px;
            gap: 16px;
            flex-wrap: wrap;
        }

        .search-box {
            background-color: var(--surface-color);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 10px 16px;
            color: var(--text-primary);
            font-size: 0.9rem;
            width: 320px;
            max-width: 100%;
        }

        .search-box:focus {
            outline: none;
            border-color: var(--primary-accent);
        }

        .section-title {
            font-size: 1.25rem;
            font-weight: 600;
            margin-bottom: 16px;
            display: flex;
            align-items: center;
            gap: 8px;
        }

        /* Folder Accordion Cards */
        .folder-card {
            background-color: var(--surface-color);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            margin-bottom: 16px;
            overflow: hidden;
        }

        .folder-header {
            padding: 16px 20px;
            background-color: rgba(255, 255, 255, 0.02);
            cursor: pointer;
            display: flex;
            justify-content: space-between;
            align-items: center;
            user-select: none;
        }

        .folder-header:hover {
            background-color: rgba(255, 255, 255, 0.05);
        }

        .folder-name {
            font-weight: 600;
            font-size: 1.1rem;
            color: var(--text-primary);
            display: flex;
            align-items: center;
            gap: 8px;
        }

        .folder-meta {
            display: flex;
            align-items: center;
            gap: 16px;
            color: var(--text-secondary);
            font-size: 0.85rem;
        }

        .badge {
            background-color: var(--card-color);
            color: var(--text-primary);
            padding: 4px 10px;
            border-radius: 12px;
            font-size: 0.75rem;
            font-weight: 600;
        }

        .hit-badge {
            background-color: rgba(99, 102, 241, 0.2);
            color: var(--primary-accent);
            border: 1px solid rgba(99, 102, 241, 0.3);
            padding: 2px 8px;
            border-radius: 10px;
            font-size: 0.75rem;
            font-weight: 700;
            margin-left: 6px;
        }

        /* Tables */
        .file-table-container {
            overflow-x: auto;
            border-top: 1px solid var(--border-color);
        }

        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 0.875rem;
            text-align: left;
        }

        th {
            background-color: rgba(0, 0, 0, 0.2);
            color: var(--text-secondary);
            padding: 12px 20px;
            font-weight: 600;
            text-transform: uppercase;
            font-size: 0.75rem;
            letter-spacing: 0.5px;
        }

        td {
            padding: 12px 20px;
            border-bottom: 1px solid rgba(255, 255, 255, 0.05);
            color: var(--text-primary);
        }

        tr:last-child td {
            border-bottom: none;
        }

        tr:hover td {
            background-color: rgba(255, 255, 255, 0.02);
        }

        a {
            color: var(--primary-accent);
            text-decoration: none;
        }

        a:hover {
            text-decoration: underline;
        }

        .btn-download {
            background-color: var(--primary-accent);
            color: white;
            padding: 6px 12px;
            border-radius: 6px;
            font-size: 0.8rem;
            font-weight: 600;
            display: inline-block;
        }

        .btn-download:hover {
            background-color: var(--primary-hover);
            text-decoration: none;
        }

        /* Audit Tabs & History Card */
        .history-card {
            background-color: var(--surface-color);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            padding: 24px;
            margin-top: 32px;
        }

        .tabs {
            display: flex;
            gap: 8px;
            margin-bottom: 20px;
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 12px;
            flex-wrap: wrap;
        }

        .tab-btn {
            background-color: transparent;
            border: 1px solid var(--border-color);
            color: var(--text-secondary);
            padding: 8px 16px;
            border-radius: 8px;
            font-size: 0.85rem;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s ease;
        }

        .tab-btn:hover {
            background-color: rgba(255, 255, 255, 0.05);
            color: var(--text-primary);
        }

        .tab-btn.active {
            background-color: var(--primary-accent);
            border-color: var(--primary-accent);
            color: white;
        }

        .action-tag {
            padding: 3px 8px;
            border-radius: 6px;
            font-size: 0.75rem;
            font-weight: 700;
            text-transform: uppercase;
        }

        .action-upload { background-color: rgba(16, 185, 129, 0.2); color: var(--success-color); }
        .action-delete { background-color: rgba(239, 68, 68, 0.2); color: var(--danger-color); }
        .action-download { background-color: rgba(99, 102, 241, 0.2); color: var(--primary-accent); }
        .action-moderated { background-color: rgba(236, 72, 153, 0.2); color: var(--moderated-color); border: 1px solid rgba(236, 72, 153, 0.3); }

        .sub-details {
            background-color: rgba(0, 0, 0, 0.25);
            padding: 12px 20px;
            display: none;
        }

        .sub-details table {
            font-size: 0.8rem;
        }
    </style>
</head>
<body>

<div class="container">

    <!-- Header -->
    <header>
        <div class="header-title">
            <h1>🌐 WebRecorder Cloud &amp; Analytics</h1>
            <p>Live storage metrics, visual graphs, and sync audit logs for <strong>webrecorder.jdworks.in</strong></p>
        </div>
        <div class="server-status-pill">
            Server Active
        </div>
    </header>

    <!-- Top Stats Cards -->
    <div class="stats-grid">
        <div class="stat-card">
            <div class="label">Total Folders</div>
            <div class="value"><?= count($folders) ?></div>
            <div class="subtext">Active offline directories</div>
        </div>
        <div class="stat-card">
            <div class="label">Archived Pages</div>
            <div class="value"><?= number_format($totalFiles) ?></div>
            <div class="subtext">HTML &amp; MHT single-file packages</div>
        </div>
        <div class="stat-card">
            <div class="label">Storage Used</div>
            <div class="value"><?= format_bytes($totalSize) ?></div>
            <div class="subtext">Total server disk space</div>
        </div>
        <div class="stat-card">
            <div class="label">Moderated / Deleted</div>
            <div class="value" style="color: var(--moderated-color);"><?= count($moderatedEntries) ?></div>
            <div class="subtext">Flagged entries removed</div>
        </div>
        <div class="stat-card">
            <div class="label">Last Sync Activity (IST)</div>
            <div class="value"><?= $latestSyncTimestamp > 0 ? (new DateTime("@$latestSyncTimestamp"))->setTimezone(new DateTimeZone('Asia/Kolkata'))->format('H:i:s') . ' IST' : 'N/A' ?></div>
            <div class="subtext"><?= format_date($latestSyncTimestamp) ?></div>
        </div>
        <div class="stat-card">
            <div class="label">Peak Sync Hour (IST)</div>
            <div class="value" style="font-size: 1.4rem; color: #818CF8;"><?= $peakHourFormatted ?></div>
            <div class="subtext">Highest traffic window</div>
        </div>
    </div>

    <!-- Visual Charts & Graphs -->
    <div class="charts-grid">
        <div class="chart-card">
            <h3>📊 Sync Action Breakdown</h3>
            <div class="chart-container">
                <canvas id="actionChart"></canvas>
            </div>
        </div>
        <div class="chart-card">
            <h3>💾 Storage Usage by Folder (MB)</h3>
            <div class="chart-container">
                <canvas id="storageChart"></canvas>
            </div>
        </div>
    </div>

    <!-- Storage Folder Explorer -->
    <div class="toolbar">
        <div class="section-title">
            📁 Storage Folders &amp; Archive Explorer (<?= count($folders) ?>)
        </div>
        <input type="text" id="searchInput" class="search-box" placeholder="Filter by title, folder, or file name..." onkeyup="filterItems()">
    </div>

    <?php if (empty($folders)): ?>
        <div class="stat-card" style="text-align: center; padding: 40px;">
            <h3>No offline folders found on server</h3>
            <p style="margin-top: 8px; color: var(--text-secondary);">Sync from the WebRecorder Android app to start uploading archived web pages.</p>
        </div>
    <?php else: ?>
        <div id="folderContainer">
            <?php foreach ($folders as $index => $folder): ?>
                <div class="folder-card" data-folder-name="<?= htmlspecialchars(strtolower($folder['name'])) ?>">
                    <div class="folder-header" onclick="toggleFolder('folder-<?= $index ?>')">
                        <div class="folder-name">
                            📂 <?= htmlspecialchars(str_replace('_', ' ', $folder['name'])) ?>
                            <span class="badge"><?= $folder['name'] ?></span>
                        </div>
                        <div class="folder-meta">
                            <span><strong><?= $folder['file_count'] ?></strong> items</span>
                            <span><strong><?= format_bytes($folder['total_size']) ?></strong></span>
                            <span>Last sync: <strong><?= format_date($folder['latest_sync']) ?></strong></span>
                            <span id="arrow-folder-<?= $index ?>">▼</span>
                        </div>
                    </div>

                    <div id="folder-<?= $index ?>" class="file-table-container">
                        <table>
                            <thead>
                                <tr>
                                    <th>#</th>
                                    <th>Title / Item</th>
                                    <th>File Name</th>
                                    <th>Original URL</th>
                                    <th>Size</th>
                                    <th>Last Sync Date</th>
                                    <th>Action</th>
                                </tr>
                            </thead>
                            <tbody>
                                <?php foreach ($folder['files'] as $fIndex => $file): ?>
                                    <tr class="file-row" data-file-text="<?= htmlspecialchars(strtolower($file['title'] . ' ' . $file['name'] . ' ' . $file['original_url'])) ?>">
                                        <td><?= $file['row_index'] >= 0 ? $file['row_index'] : ($fIndex + 1) ?></td>
                                        <td><strong><?= htmlspecialchars($file['title']) ?></strong></td>
                                        <td><code><?= htmlspecialchars($file['name']) ?></code></td>
                                        <td>
                                            <?php if ($file['original_url'] !== ''): ?>
                                                <a href="<?= htmlspecialchars($file['original_url']) ?>" target="_blank" rel="noopener">
                                                    <?= htmlspecialchars(substr($file['original_url'], 0, 45)) ?><?= strlen($file['original_url']) > 45 ? '...' : '' ?>
                                                </a>
                                            <?php else: ?>
                                                <span style="color: var(--text-secondary);">-</span>
                                            <?php endif; ?>
                                        </td>
                                        <td><?= format_bytes($file['size']) ?></td>
                                        <td><?= format_date($file['updated_at']) ?></td>
                                        <td>
                                            <a href="sync_api.php?action=download&folder=<?= urlencode($folder['name']) ?>&file=<?= urlencode($file['name']) ?>" class="btn-download" target="_blank">
                                                Open / Download
                                            </a>
                                        </td>
                                    </tr>
                                <?php endforeach; ?>
                            </tbody>
                        </table>
                    </div>
                </div>
            <?php endforeach; ?>
        </div>
    <?php endif; ?>

    <!-- Categorized & Collapsible Sync Audit Log -->
    <div class="history-card">
        <div class="section-title">
            ⚡ Categorized &amp; Collapsible Audit History
        </div>

        <div class="tabs" style="justify-content: space-between; align-items: center;">
            <div style="display: flex; gap: 8px; flex-wrap: wrap;">
                <button class="tab-btn active" onclick="filterTab('all', this)">All Activity (<?= count($groupedHistory) ?>)</button>
                <button class="tab-btn" onclick="filterTab('upload', this)">Uploads (<?= $actionCounts['upload'] ?>)</button>
                <button class="tab-btn" onclick="filterTab('download', this)">Downloads (<?= $actionCounts['download'] ?>)</button>
                <button class="tab-btn" onclick="filterTab('moderated', this)">Moderated / Removed (<?= count($moderatedEntries) ?>)</button>
                <button class="tab-btn" onclick="filterTab('delete', this)">Deletions (<?= $actionCounts['delete'] ?>)</button>
            </div>
            <div style="display: flex; gap: 10px; align-items: center;">
                <input type="text" id="auditSearchInput" class="search-box" style="margin: 0; width: 220px;" placeholder="Search history..." onkeyup="filterAuditHistory()">
                <a href="?export=csv" class="tab-btn" style="background-color: var(--primary-accent); color: white; border: none; text-decoration: none;">📥 Export CSV</a>
            </div>
        </div>

        <?php if (empty($groupedHistory)): ?>
            <p style="color: var(--text-secondary); font-size: 0.9rem;">No sync events recorded yet. Sync operations will log here automatically.</p>
        <?php else: ?>
            <div class="file-table-container" style="border-top: none;">
                <table>
                    <thead>
                        <tr>
                            <th>Time</th>
                            <th>Action</th>
                            <th>Folder</th>
                            <th>Title / File</th>
                            <th>Hits / Hits Detail</th>
                            <th>Client IP</th>
                        </tr>
                    </thead>
                    <tbody>
                        <?php foreach ($groupedHistory as $group): ?>
                            <tr class="history-row action-type-<?= $group['action'] ?>">
                                <td><?= format_date($group['latest_timestamp']) ?></td>
                                <td>
                                    <span class="action-tag action-<?= htmlspecialchars($group['action']) ?>">
                                        <?= htmlspecialchars($group['action']) ?>
                                    </span>
                                </td>
                                <td><strong><?= htmlspecialchars($group['folder']) ?></strong></td>
                                <td>
                                    <div><strong><?= htmlspecialchars($group['title']) ?></strong></div>
                                    <div style="font-size: 0.75rem; color: var(--text-secondary);"><?= htmlspecialchars($group['filename']) ?></div>
                                </td>
                                <td>
                                    <?php if ($group['hits'] > 1): ?>
                                        <span class="hit-badge" onclick="toggleSubDetails('<?= $group['id'] ?>')" style="cursor: pointer;">
                                            <?= $group['hits'] ?> hits (Collapse / Expand)
                                        </span>
                                    <?php else: ?>
                                        <span style="color: var(--text-secondary);">1 hit</span>
                                    <?php endif; ?>
                                </td>
                                <td><code><?= htmlspecialchars($group['ip']) ?></code></td>
                            </tr>
                            <?php if ($group['hits'] > 1): ?>
                                <tr id="sub-<?= $group['id'] ?>" class="sub-details action-type-<?= $group['action'] ?>">
                                    <td colspan="6">
                                        <div style="padding: 6px 0; color: var(--text-secondary); font-weight: 600;">Individual Hit Timestamps:</div>
                                        <ul>
                                            <?php foreach ($group['items'] as $item): ?>
                                                <li style="margin-left: 16px; margin-bottom: 4px;">
                                                    <?= format_date((int) ($item['timestamp'] ?? 0)) ?> — IP: <code><?= htmlspecialchars((string) ($item['ip'] ?? '')) ?></code>
                                                </li>
                                            <?php endforeach; ?>
                                        </ul>
                                    </td>
                                </tr>
                            <?php endif; ?>
                        <?php endforeach; ?>
                    </tbody>
                </table>
            </div>
        <?php endif; ?>
    </div>

</div>

<script>
    // Folder accordion toggle
    function toggleFolder(id) {
        var element = document.getElementById(id);
        var arrow = document.getElementById('arrow-' + id);
        if (element.style.display === 'none') {
            element.style.display = 'block';
            if (arrow) arrow.innerText = '▼';
        } else {
            element.style.display = 'none';
            if (arrow) arrow.innerText = '▶';
        }
    }

    // Toggle sub-details for collapsed hits
    function toggleSubDetails(id) {
        var subRow = document.getElementById('sub-' + id);
        if (subRow) {
            subRow.style.display = subRow.style.display === 'table-row' ? 'none' : 'table-row';
        }
    }

    // Filter tabs for Audit Logs
    function filterTab(category, btn) {
        document.querySelectorAll('.tab-btn').forEach(function(b) { b.classList.remove('active'); });
        btn.classList.add('active');

        var rows = document.querySelectorAll('.history-row');
        rows.forEach(function(row) {
            if (category === 'all') {
                row.style.display = '';
            } else if (row.classList.contains('action-type-' + category)) {
                row.style.display = '';
            } else {
                row.style.display = 'none';
            }
        });
    }

    // Search filter for folders and files
    function filterItems() {
        var query = document.getElementById('searchInput').value.toLowerCase().trim();
        var folders = document.querySelectorAll('.folder-card');

        folders.forEach(function(folder) {
            var folderName = folder.getAttribute('data-folder-name') || '';
            var fileRows = folder.querySelectorAll('.file-row');
            var hasMatchingFile = false;

            fileRows.forEach(function(row) {
                var fileText = row.getAttribute('data-file-text') || '';
                if (query === '' || fileText.includes(query) || folderName.includes(query)) {
                    row.style.display = '';
                    hasMatchingFile = true;
                } else {
                    row.style.display = 'none';
                }
            });

            if (query === '' || folderName.includes(query) || hasMatchingFile) {
                folder.style.display = '';
            } else {
                folder.style.display = 'none';
            }
        });
    }

    function filterAuditHistory() {
        const query = document.getElementById('auditSearchInput').value.toLowerCase();
        const rows = document.querySelectorAll('.history-row');
        rows.forEach(row => {
            const text = row.innerText.toLowerCase();
            const hitBadge = row.querySelector('.hit-badge');
            let subId = null;
            if (hitBadge) {
                const match = hitBadge.getAttribute('onclick')?.match(/'([^']+)'/);
                if (match) subId = 'sub-' + match[1];
            }
            const subRow = subId ? document.getElementById(subId) : null;
            if (text.includes(query)) {
                row.style.display = '';
            } else {
                row.style.display = 'none';
                if (subRow) subRow.style.display = 'none';
            }
        });
    }

    // Render Chart.js Analytics Graphs
    document.addEventListener("DOMContentLoaded", function() {
        // Chart 1: Action Breakdown Doughnut Chart
        const actionCtx = document.getElementById('actionChart').getContext('2d');
        new Chart(actionCtx, {
            type: 'doughnut',
            data: {
                labels: ['Uploads', 'Downloads', 'Deletions', 'Moderated Items'],
                datasets: [{
                    data: [
                        <?= $actionCounts['upload'] ?>,
                        <?= $actionCounts['download'] ?>,
                        <?= $actionCounts['delete'] ?>,
                        <?= count($moderatedEntries) ?>
                    ],
                    backgroundColor: ['#10b981', '#6366f1', '#ef4444', '#ec4899'],
                    borderWidth: 0
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { position: 'bottom', labels: { color: '#94a3b8' } }
                }
            }
        });

        // Chart 2: Storage by Folder Bar Chart
        const storageCtx = document.getElementById('storageChart').getContext('2d');
        new Chart(storageCtx, {
            type: 'bar',
            data: {
                labels: <?= json_encode($folderChartLabels) ?>,
                datasets: [{
                    label: 'Storage (MB)',
                    data: <?= json_encode($folderChartData) ?>,
                    backgroundColor: '#6366f1',
                    borderRadius: 6
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    x: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } },
                    y: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } }
                },
                plugins: {
                    legend: { display: false }
                }
            }
        });
    });
</script>

</body>
</html>
