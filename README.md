# Tiny FTP File Browser

A self-contained **Spring Boot** application that lets you browse and manage
files on the **local filesystem** and on **remote FTP servers** through a
**two-panel** web UI built with **Angular + Bootstrap** — in the spirit of
classic orthodox file managers (Norton/Total/Midnight Commander).

Backend (REST API) and frontend (compiled Angular) are packaged into a single
runnable `jar`. No database, no external services — just the jar and a JRE.

## Features

- **Two side-by-side panels** with a draggable splitter; each panel can show a
  local directory or a remote FTP server independently.
- **FTP connection manager** with multiple saved profiles (passive/active mode,
  UTF-8 paths). Profiles are stored in the browser's `localStorage`.
- **File operations** between panels:
  - Rename (F2), View / preview (F3), Copy → (F5), Move → (F6),
    New folder (F7), Delete (F8).
  - Local↔local, local↔FTP, and FTP↔FTP transfers (the latter streams through
    the server).
- **Background jobs panel** for long-running transfers/deletes with:
  - live byte-level progress bar,
  - per-job protocol log that auto-scrolls,
  - **Stop** button to interrupt the current operation,
  - **Clear completed** to clean up the job list.
- **Text and image preview** for files in either panel (F3).
- **Sandboxed local root**: the local side cannot escape the configured root
  directory — path-traversal is blocked server-side.
- **Single jar** distribution; the Angular UI is served as static resources by
  Spring Boot.

## Quick start

Requirements: **Java 21+** (the build is configured for Java 25; lower the
`java.version` property in `pom.xml` if you need to build with an older JDK).

```bash
# Run
java -jar target/ftpclient-1.0.0-alpha.jar

# Optional flags:
#   --app.root-dir=/path/to/managed/folder   (default: current working dir)
#   --server.port=8899                       (default: 8080)
```

Then open <http://localhost:8080>.

The first panel opens on the configured local root. To browse an FTP server,
click **Connect FTP** in the bottom bar, add a connection profile, and hit
**Connect**.

## Configuration

| Property                                    | Default       | Description                                          |
|---------------------------------------------|---------------|------------------------------------------------------|
| `app.root-dir`                              | `${user.dir}` | Local directory all local-side ops are sandboxed to. |
| `server.port`                               | `8080`        | HTTP port.                                           |
| `spring.servlet.multipart.max-file-size`    | `512MB`       | Max upload size (HTTP form uploads).                 |
| `spring.servlet.multipart.max-request-size` | `512MB`       | Max upload request size.                             |

All standard Spring Boot configuration mechanisms are supported (CLI args,
`application.properties`, environment variables, etc.).

## REST API

### Local files (sandboxed under `app.root-dir`)

| Method | Path                  | Body / Params              | Purpose                        |
|--------|-----------------------|----------------------------|--------------------------------|
| GET    | `/api/files/root`     | –                          | Managed root path              |
| GET    | `/api/files/list`     | `?path=`                   | List a directory               |
| GET    | `/api/files/content`  | `?path=`                   | Read text content (preview)    |
| GET    | `/api/files/raw`      | `?path=`                   | Read raw bytes (image preview) |
| POST   | `/api/files/mkdir`    | `{ parent, name }`         | Create a directory             |
| POST   | `/api/files/rename`   | `{ path, newName }`        | Rename a file/dir              |
| POST   | `/api/files/copy`     | `{ paths[], destination }` | Copy into destination dir      |
| POST   | `/api/files/move`     | `{ paths[], destination }` | Move into destination dir      |
| POST   | `/api/files/delete`   | `{ paths[] }`              | Delete (recursive)             |

All `path` / `paths` values are **relative to the configured root**.

### FTP

Each call carries the connection settings in its body so the server stays
stateless (no session per user).

| Method | Path                  | Purpose                                                  |
|--------|-----------------------|----------------------------------------------------------|
| POST   | `/api/ftp/connect`    | Connect, list a directory, return the protocol log       |
| POST   | `/api/ftp/list`       | List a remote directory                                  |
| POST   | `/api/ftp/content`    | Read remote text file                                    |
| POST   | `/api/ftp/raw`        | Read remote bytes (image preview)                        |
| POST   | `/api/ftp/mkdir`      | Create remote directory                                  |
| POST   | `/api/ftp/rename`     | Rename remote entry                                      |
| POST   | `/api/ftp/upload`     | Upload local paths to a remote dir (returns `jobId`)     |
| POST   | `/api/ftp/download`   | Download remote paths to a local dir (returns `jobId`)   |
| POST   | `/api/ftp/copy`       | Copy between two FTP servers (returns `jobId`)           |
| POST   | `/api/ftp/delete`     | Delete remote paths (returns `jobId`)                    |

### Jobs

| Method | Path                                       | Purpose                              |
|--------|--------------------------------------------|--------------------------------------|
| GET    | `/api/jobs?max=20`                         | Recent jobs (newest first)           |
| GET    | `/api/jobs/{id}`                           | Job detail including full log        |
| POST   | `/api/jobs/{id}/cancel`                    | Request cancellation                 |
| DELETE | `/api/jobs/completed`                      | Remove DONE/FAILED/CANCELLED jobs    |
| DELETE | `/api/jobs/cleanup?olderThanSeconds=600`   | Time-based cleanup                   |

## Project layout

```
src/main/java/by/homesite/ftpclient/
  config/StorageProperties.java     # binds app.root-dir
  model/FileItem.java               # file/dir DTO
  service/StorageService.java       # sandboxed local FS operations
  service/FtpService.java           # streamed FTP operations
  service/JobService.java           # async job tracking + progress + cancel
  web/FileController.java           # local file REST API
  web/FtpController.java            # FTP REST API
  web/JobController.java            # jobs REST API
  web/ApiExceptionHandler.java      # JSON error responses
frontend/                           # Angular + Bootstrap UI
  src/app/app.component.*           # shell, two panels, bottom bar, modals
  src/app/components/panel/*        # one panel (toolbar + 3 view modes)
  src/app/services/*.ts             # REST clients
```

## Building from source

See [BUILD.md](BUILD.md) for a step-by-step guide to building the backend, the
Angular UI, and combined builds.

## Running as a service (Debian/systemd)

See [deploy/DEPLOY.md](deploy/DEPLOY.md) for a complete guide to running the
application as a managed `systemd` service (dedicated user, auto-start on boot,
restart on failure). A ready-to-edit unit file is provided at
[deploy/ftpclient.service](deploy/ftpclient.service).

## License

[MIT](LICENSE) — do whatever you like, no warranty.

Copyright © 2026 [Aliaksandr Piatkevich](https://homesite.by).

