<?php

declare(strict_types=1);

/**
 * Read-only access to the GitHub storage repository used by the WebRecorder app.
 *
 * Layout written by the app:
 *   pages/<folder>/<md5>.mht|.html
 *   pages/<folder>/metadata.json
 *   history.json
 */
final class GitHubRepo
{
    private static function api(): string
    {
        // GITHUB_API_URL is only used for local testing.
        return rtrim((string) (getenv('GITHUB_API_URL') ?: 'https://api.github.com'), '/');
    }

    private function __construct(
        private readonly string $repo,
        private readonly string $branch,
        private readonly string $token,
    ) {
    }

    public static function fromEnv(): ?self
    {
        $token = trim((string) getenv('GITHUB_TOKEN'));
        $repo = trim((string) getenv('GITHUB_REPO'));
        $branch = trim((string) getenv('GITHUB_BRANCH')) ?: 'main';
        if ($token === '' || !preg_match('#^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$#', $repo)) {
            return null;
        }
        return new self($repo, $branch, $token);
    }

    public function label(): string
    {
        return $this->repo . ' @ ' . $this->branch;
    }

    /**
     * @return array{folders: array<string, array{files: array<string,int>, metadata: array}>, history: array}
     */
    public function snapshot(): array
    {
        $ref = $this->json('/repos/' . $this->repo . '/git/ref/heads/' . rawurlencode($this->branch));
        $commitSha = (string) ($ref['object']['sha'] ?? '');
        $commit = $this->json('/repos/' . $this->repo . '/git/commits/' . $commitSha);
        $root = $this->json('/repos/' . $this->repo . '/git/trees/' . ($commit['tree']['sha'] ?? ''));

        $pagesSha = null;
        $hasHistory = false;
        foreach ($root['tree'] ?? [] as $entry) {
            if (($entry['path'] ?? '') === 'pages' && ($entry['type'] ?? '') === 'tree') {
                $pagesSha = $entry['sha'];
            }
            if (($entry['path'] ?? '') === 'history.json') {
                $hasHistory = true;
            }
        }

        $folders = [];
        if ($pagesSha !== null) {
            $tree = $this->json('/repos/' . $this->repo . '/git/trees/' . $pagesSha . '?recursive=1');
            foreach ($tree['tree'] ?? [] as $entry) {
                $path = (string) ($entry['path'] ?? '');
                $parts = explode('/', $path);
                if (($entry['type'] ?? '') === 'tree' && count($parts) === 1) {
                    $folders[$path] ??= ['files' => [], 'metadata' => [], 'has_meta' => false];
                    continue;
                }
                if (($entry['type'] ?? '') !== 'blob' || count($parts) !== 2) {
                    continue;
                }
                [$folder, $name] = $parts;
                $folders[$folder] ??= ['files' => [], 'metadata' => [], 'has_meta' => false];
                if ($name === 'metadata.json') {
                    $folders[$folder]['has_meta'] = true;
                } elseif (preg_match('/\.(html|mht)$/i', $name)) {
                    $folders[$folder]['files'][$name] = (int) ($entry['size'] ?? 0);
                }
            }
        }

        // Fetch every folder's metadata.json + history.json in parallel.
        $paths = [];
        foreach ($folders as $folder => $info) {
            if ($info['has_meta']) {
                $paths[$folder] = 'pages/' . $folder . '/metadata.json';
            }
        }
        if ($hasHistory) {
            $paths['__history__'] = 'history.json';
        }
        $texts = $this->rawMany($paths, $commitSha);

        foreach ($folders as $folder => &$info) {
            $decoded = isset($texts[$folder]) ? json_decode($texts[$folder], true) : null;
            $info['metadata'] = is_array($decoded) ? $decoded : [];
            unset($info['has_meta']);
        }
        unset($info);

        $history = isset($texts['__history__']) ? json_decode($texts['__history__'], true) : [];
        return ['folders' => $folders, 'history' => is_array($history) ? $history : []];
    }

    /** Streams one stored page to the browser. */
    public function streamFile(string $path): void
    {
        $url = self::api() . '/repos/' . $this->repo . '/contents/' . implode('/', array_map('rawurlencode', explode('/', $path)))
            . '?ref=' . rawurlencode($this->branch);
        $isMht = str_ends_with(strtolower($path), '.mht');
        $ch = curl_init($url);
        $headersSent = false;
        curl_setopt_array($ch, [
            CURLOPT_HTTPHEADER => $this->headers('application/vnd.github.raw+json'),
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => 25,
            CURLOPT_WRITEFUNCTION => function ($ch, string $chunk) use (&$headersSent, $isMht, $path): int {
                if (!$headersSent) {
                    $code = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
                    if ($code !== 200) {
                        http_response_code($code === 404 ? 404 : 502);
                        echo $code === 404 ? 'File not found' : 'GitHub error ' . $code;
                        $headersSent = true;
                        return -1;
                    }
                    header($isMht ? 'Content-Type: message/rfc822' : 'Content-Type: text/html; charset=UTF-8');
                    header('Content-Disposition: ' . ($isMht ? 'attachment' : 'inline') . '; filename="' . basename($path) . '"');
                    $headersSent = true;
                }
                echo $chunk;
                return strlen($chunk);
            },
        ]);
        curl_exec($ch);
        curl_close($ch);
    }

    private function json(string $apiPath): array
    {
        $ch = curl_init(self::api() . $apiPath);
        curl_setopt_array($ch, [
            CURLOPT_HTTPHEADER => $this->headers('application/vnd.github+json'),
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 20,
        ]);
        $body = (string) curl_exec($ch);
        $code = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        $data = json_decode($body, true);
        if ($code < 200 || $code >= 300 || !is_array($data)) {
            $message = is_array($data) ? (string) ($data['message'] ?? '') : '';
            throw new RuntimeException('GitHub API returned HTTP ' . $code . ($message !== '' ? ' – ' . $message : '') . ' for ' . $apiPath);
        }
        return $data;
    }

    /** @param array<string,string> $paths key => repo path. Returns key => file text. */
    private function rawMany(array $paths, string $ref): array
    {
        $multi = curl_multi_init();
        $handles = [];
        foreach ($paths as $key => $path) {
            $url = self::api() . '/repos/' . $this->repo . '/contents/'
                . implode('/', array_map('rawurlencode', explode('/', $path))) . '?ref=' . rawurlencode($ref);
            $ch = curl_init($url);
            curl_setopt_array($ch, [
                CURLOPT_HTTPHEADER => $this->headers('application/vnd.github.raw+json'),
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_TIMEOUT => 20,
            ]);
            curl_multi_add_handle($multi, $ch);
            $handles[$key] = $ch;
        }
        do {
            $status = curl_multi_exec($multi, $running);
            if ($running) {
                curl_multi_select($multi, 1.0);
            }
        } while ($running && $status === CURLM_OK);

        $results = [];
        foreach ($handles as $key => $ch) {
            if ((int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE) === 200) {
                $results[$key] = (string) curl_multi_getcontent($ch);
            }
            curl_multi_remove_handle($multi, $ch);
            curl_close($ch);
        }
        curl_multi_close($multi);
        return $results;
    }

    private function headers(string $accept): array
    {
        return [
            'Authorization: Bearer ' . $this->token,
            'Accept: ' . $accept,
            'X-GitHub-Api-Version: 2022-11-28',
            'User-Agent: WebRecorder-Dashboard',
        ];
    }
}
