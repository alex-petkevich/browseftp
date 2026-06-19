# Building from source

This project is a Spring Boot jar with an embedded Angular UI. There are two
parts:

- the **Spring Boot backend** built with Maven, and
- the **Angular frontend** built with npm.

The frontend's compiled output is committed into
`src/main/resources/static`, so a typical build only needs Maven. You only have
to rebuild the UI when you change something under `frontend/src`.

The repository ships a Maven Wrapper (`mvnw` / `mvnw.cmd`) so you don't need a
local Maven install.

## Prerequisites

- **JDK 21+** for runtime; **JDK 25** to build with the default `pom.xml`
  settings (lower the `<java.version>` property if needed).
- **Node.js 20+ and npm 10+** — only required when rebuilding the UI.

Verify:

```bash
java -version
node -v
npm -v
```

## 1. Backend-only build (UI already embedded)

Use this when you changed only Java code (or nothing in `frontend/`).

```bash
# Linux / macOS
./mvnw clean package

# Windows
mvnw.cmd clean package
```

Result: `target/ftpclient-0.0.1-SNAPSHOT.jar`

Skip tests:

```bash
./mvnw clean package -DskipTests
```

## 2. Rebuild the Angular UI

When anything under `frontend/src` changes you need to rebuild the UI and copy
its output into the backend's static resources.

### Manually

```bash
cd frontend
npm install
npm run build           # produces frontend/dist/
cd ..

# Replace embedded UI with the fresh build
rm -rf src/main/resources/static
mkdir -p src/main/resources/static
cp -r frontend/dist/* src/main/resources/static/
```

Then run a backend build (step 1) to package the new UI into the jar.

### Or have Maven do it

The `build-frontend` profile runs `npm install && npm run build` and copies
the result into the static resources during the Maven build (Node and npm must
be on `PATH`):

```bash
./mvnw -Pbuild-frontend clean package
```

The correct npm launcher is selected automatically per OS (`npm` on
Linux/macOS, `npm.cmd` on Windows). If your npm lives elsewhere or has a
different name, override it explicitly:

```bash
./mvnw -Pbuild-frontend -Dnpm.executable=/usr/bin/npm clean package
```

## 3. Run the application

```bash
java -jar target/ftpclient-1.0.0-beta.jar
```

Useful flags:

| Flag                          | Purpose                                                        |
|-------------------------------|----------------------------------------------------------------|
| `--app.root-dir=...`          | Local directory to manage (sandboxed). Default: working dir.   |
| `--server.port=8899`          | HTTP port. Default `8080`.                                     |
| `-Dspring.profiles.active=...`| Activate a Spring profile.                                     |

Then open <http://localhost:8080>.

## 4. Run from sources during development

Backend with auto-reload (Spring Boot DevTools is on the classpath):

```bash
./mvnw spring-boot:run
```

Angular with live reload (separate terminal):

```bash
cd frontend
npm install      # first time
npm start        # serves on http://localhost:4200
```

`frontend/proxy.conf.json` proxies `/api/*` from `:4200` to `:8080`, so the dev
server talks to the running backend.

## 5. Useful commands

| Command                                  | Purpose                                                    |
|------------------------------------------|------------------------------------------------------------|
| `./mvnw clean package -DskipTests`       | Build without running tests                                |
| `./mvnw test`                            | Run tests only                                             |
| `./mvnw spring-boot:run`                 | Run straight from sources (dev)                            |
| `./mvnw -Pbuild-frontend clean package`  | Rebuild UI as part of the Maven build                      |
| `cd frontend && npm start`               | Angular dev server on `:4200`                              |
| `cd frontend && npm run build`           | Build the production Angular bundle into `frontend/dist/`  |

## 6. Troubleshooting

- **`release version 25 not supported`** — your `JAVA_HOME` points at an older
  JDK. Either install JDK 25 or lower `<java.version>` in `pom.xml` to your
  installed version (21 or 24 work too).
- **`Failed to delete ...jar: being used by another process`** — a previous
  run of the app still has the jar open. Stop it before rebuilding.
- **Build fails behind a TLS-inspecting proxy** with `PKIX path building
  failed` — point Maven at your OS trust store, e.g. on Windows:
  `set MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`.
- **`npm install` complains about esbuild** — make sure you're online and that
  no firewall blocks `registry.npmjs.org`. The `package.json` pins esbuild via
  `overrides` so reproducible builds are possible.
- **`npm ERR! ENOTEMPTY: directory not empty, rename ...node_modules/...`** — a
  stale or partially-installed `node_modules` (often copied from another
  machine/OS, which also has the wrong platform binaries). Delete it and rebuild:
  ```bash
  rm -rf frontend/node_modules frontend/.angular
  ./mvnw -Pbuild-frontend clean package
  ```
  The `build-frontend` profile now also wipes these folders during `clean`, so a
  `clean package` starts from a fresh tree.
