# Release Notes

## v1.0.0-beta — 2026-06-19

A large feature update focused on the image viewer, downloads, sorting,
transfer feedback, and persistent server-side settings.

### New features

- **Download to your computer**: each panel now has a **Download** button that
  saves the selected item(s) — a single file as-is, or multiple items / a
  folder as a streamed **ZIP**. Works for both local and FTP panels.
- **Image viewer (F3) overhaul**:
  - **Prev / Next** navigation through all images in the current directory,
    with a position indicator.
  - **Click the image** to open the original at full size in a new tab.
  - Keyboard shortcuts: **Esc** closes, **←/→** step between images.
  - **HEIC/HEIF support** — converted to JPEG in the browser for display.
  - Larger preview size limit (up to 25 MB) so typical photos open fine.
- **Sort the file list** by **Name / Date / Size**, ascending/descending —
  via toolbar buttons or by clicking the column headers. Folders stay grouped
  first; each panel remembers its own sort.
- **Transfer speed feedback** in the Operations window:
  - live **speed + ETA** while a transfer runs,
  - **aggregate speed** badge on the bottom bar,
  - final **average speed** shown when a job completes.
- **Persistent settings**: theme, saved FTP connections, and last-opened
  directories are now stored **server-side** in the user's home directory
  (`~/.browseftp/settings.json`) instead of the browser, so they follow you
  across browsers and devices. Existing `localStorage` settings are migrated
  automatically.
- **Last-opened directory** for each panel is restored on startup.
- **Version info** is shown in the Settings dialog (served from `/api/info`).
- **Copyright / attribution** added across the UI and the startup log.

### Deployment

- New **systemd** guide and ready-to-edit unit file for running on Debian
  (`deploy/DEPLOY.md`, `deploy/ftpclient.service`).
- New `app.settings-file` property to relocate the settings file.

### REST API additions

- `GET /api/files/download`, `GET /api/files/download-zip`
- `POST /api/ftp/download-file`, `POST /api/ftp/download-zip`
- `GET/PUT /api/settings`, `GET /api/info`

### Build / fixes

- Cross-platform frontend build: the correct `npm` launcher is selected per OS,
  `npm ci` is used for reproducible installs, and stale Angular caches are
  cleaned automatically.
- The committed compiled UI is now refreshed reliably so freshly built changes
  always end up in the jar.
- Image preview no longer fails with HTTP 400 on larger photos.

### Upgrade notes

- On first run after upgrading, settings move from the browser to
  `~/.browseftp/settings.json` (auto-migrated). **FTP passwords are stored in
  plaintext** in that file — restrict its permissions (e.g. `chmod 600`) if the
  host is shared.

## v0.1.0 — First public release

The first release of **Tiny FTP File Browser**: a self-contained Spring Boot
application with an embedded Angular + Bootstrap UI that lets you browse and
manage files on the **local filesystem** and on **remote FTP servers** through
a classic **two-panel** (orthodox file manager) interface.

Backend and frontend ship as a single runnable `jar` — no database, no external
services. Just a JRE and the jar.

### Highlights

- **Two side-by-side panels** with a draggable splitter. Each panel can
  independently show a local directory or a remote FTP server.
- **FTP connection manager** with multiple saved profiles (passive/active mode,
  UTF-8 paths). Profiles are persisted in the browser's `localStorage`.
- **File operations** between panels, driven by classic function keys:
  - Rename (F2), View / preview (F3), Copy → (F5), Move → (F6),
    New folder (F7), Delete (F8).
  - Supports local↔local, local↔FTP, and FTP↔FTP transfers (server-to-server
    transfers stream through the application).
- **Background jobs panel** for long-running transfers and deletes, featuring:
  - a live byte-level progress bar,
  - a per-job protocol log that auto-scrolls,
  - a **Stop** button to interrupt the current operation,
  - **Clear completed** to tidy up the job list.
- **Text and image preview** (F3) for files in either panel.
- **Sandboxed local root**: the local side cannot escape the configured root
  directory — path traversal is blocked server-side.
- **Single-jar distribution**: the Angular UI is served as static resources by
  Spring Boot.

### REST API

A stateless REST API backs the UI:

- **Local files** (`/api/files/*`) — list, read, preview, mkdir, rename, copy,
  move, and delete, all sandboxed under `app.root-dir`.
- **FTP** (`/api/ftp/*`) — connect, list, preview, mkdir, rename, upload,
  download, server-to-server copy, and delete. Connection settings are passed
  per request so the server holds no per-user session.
- **Jobs** (`/api/jobs/*`) — track recent jobs, fetch job detail and logs,
  cancel running jobs, and clean up completed/old jobs.

See the [README](README.md) for the full endpoint reference.

### Requirements

- **Java 21+** to run (the build is configured for Java 25; lower the
  `java.version` property in `pom.xml` to build with an older JDK).
- **Node.js 20+ / npm 10+** only when rebuilding the Angular UI (the compiled
  UI is committed under `src/main/resources/static`).

### Getting started

```bash
# Build
./mvnw clean package

# Run
java -jar target/ftpclient-1.0.0-alpha.jar

# Optional flags:
#   --app.root-dir=/path/to/managed/folder   (default: current working dir)
#   --server.port=8899                       (default: 8080)
```

Then open <http://localhost:8080>. See [BUILD.md](BUILD.md) for full build
instructions.

### Tech stack

- Spring Boot 4.1 (Spring Web MVC, Mustache)
- Apache Commons Net 3.11 for FTP
- Angular 18 + Bootstrap 5.3 + Bootstrap Icons

### Notes

- This is an initial release; expect rough edges. Please file issues and
  feedback on the GitHub tracker.

### License

[MIT](LICENSE) — do whatever you like, no warranty.

