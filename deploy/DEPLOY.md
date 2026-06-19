# Running on Debian as a systemd service

This guide installs the Tiny FTP File Browser jar as a managed
**systemd** service that starts on boot, restarts on failure, and runs under a
dedicated unprivileged user.

A ready-to-edit unit file is provided at [`deploy/ftpclient.service`](ftpclient.service).

## 1. Prerequisites

- A JRE/JDK 21+ on the server (`java -version`). To install Temurin 25 or the
  distro OpenJDK:
  ```bash
  sudo apt update
  sudo apt install -y openjdk-21-jre-headless   # or a newer JDK if available
  ```
- The built application jar: `target/ftpclient-1.0.0-alpha.jar`
  (see [BUILD.md](../BUILD.md)).

## 2. Create a service user and directories

```bash
# Dedicated, locked-down system account (no login shell, no home login)
sudo useradd --system --no-create-home --shell /usr/sbin/nologin ftpclient

# Application directory and the directory the app is allowed to manage
sudo mkdir -p /opt/ftpclient
sudo mkdir -p /srv/ftpfiles
```

## 3. Install the jar

```bash
# Copy and give it a stable name the unit file expects
sudo cp target/ftpclient-1.0.0-alpha.jar /opt/ftpclient/ftpclient.jar

# Ownership
sudo chown -R ftpclient:ftpclient /opt/ftpclient
sudo chown -R ftpclient:ftpclient /srv/ftpfiles
```

> Using the stable name `ftpclient.jar` means you don't have to edit the unit
> file every time the version changes — just replace the jar and restart.

## 4. Install the service unit

```bash
sudo cp deploy/ftpclient.service /etc/systemd/system/ftpclient.service

# Edit paths/port/root-dir and the java path if needed
sudo nano /etc/systemd/system/ftpclient.service

sudo systemctl daemon-reload
```

Key things to check in the unit:

| Setting             | Purpose                                                        |
|---------------------|----------------------------------------------------------------|
| `ExecStart` java    | Full path to `java` if not on the service user's PATH.         |
| `--server.port=`    | HTTP port (default in the unit: `8899`).                       |
| `--app.root-dir=`   | Directory the local side is sandboxed to (`/srv/ftpfiles`).    |
| `--app.settings-file=` | Where user settings are stored. Under the hardened unit the service user has no home and `ProtectHome=true`, so it points at `/opt/ftpclient/settings.json`. |
| `ReadWritePaths=`   | Must include the managed directory **and** the settings dir.   |

## 5. Enable and start

```bash
sudo systemctl enable --now ftpclient

# Status and logs
systemctl status ftpclient
journalctl -u ftpclient -f          # live logs
```

Then open `http://SERVER_IP:8899`.

## 6. Update to a new version

```bash
sudo systemctl stop ftpclient
sudo cp target/ftpclient-1.0.0-alpha.jar /opt/ftpclient/ftpclient.jar
sudo chown ftpclient:ftpclient /opt/ftpclient/ftpclient.jar
sudo systemctl start ftpclient
```

## 7. Common commands

| Command                                  | Purpose                       |
|------------------------------------------|-------------------------------|
| `sudo systemctl start ftpclient`         | Start now                     |
| `sudo systemctl stop ftpclient`          | Stop                          |
| `sudo systemctl restart ftpclient`       | Restart                       |
| `sudo systemctl enable ftpclient`        | Start on boot                 |
| `sudo systemctl disable ftpclient`       | Don't start on boot           |
| `systemctl status ftpclient`             | Show current status           |
| `journalctl -u ftpclient -e`             | Show recent logs              |
| `journalctl -u ftpclient -f`             | Follow logs live              |

## 8. Notes

- **Port < 1024**: if you want to serve on port 80, either keep a higher port
  and put nginx/Caddy in front, or grant the capability:
  `AmbientCapabilities=CAP_NET_BIND_SERVICE` in the `[Service]` section.
- **Reverse proxy / TLS**: terminate HTTPS at nginx/Caddy and proxy to the app's
  HTTP port. The app itself serves plain HTTP.
- **Memory limits**: add JVM flags to `ExecStart`, e.g.
  `/usr/bin/java -Xmx256m -jar /opt/ftpclient/ftpclient.jar ...`.
- **Config via file**: instead of CLI flags you can drop an
  `application.properties` next to the jar (same `WorkingDirectory`) and Spring
  Boot will pick it up automatically.

