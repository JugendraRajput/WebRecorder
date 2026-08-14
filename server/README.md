# WebRecorder Sync Server

Upload `sync_api.php` and `.htaccess` to your PHP web server and ensure the `storage/` directory is writable (`chmod 775 storage`).

The default Android app base URL is configured to:

`https://webrecorder.jdworks.in`

The app communicates with the following endpoints:

- `sync_api.php?action=status&folder=<folder_name>`
- `sync_api.php?action=folders`
- `sync_api.php?action=manifest&folder=<folder_name>`
- `sync_api.php?action=download&folder=<folder_name>&file=<filename>`
- `sync_api.php?action=upload` (via `POST` multipart)
- `sync_api.php?action=delete` (via `POST`)

Each Excel list is stored as its own folder inside `storage/`.