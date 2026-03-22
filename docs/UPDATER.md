# panelsDCC-connect updater

The updater checks [GitHub Releases](https://github.com/PanelsDCC/connect/releases) for a newer `panelsdcc-connect` `.deb`, compares it to the installed package version, and can download and install the update with `dpkg` / `apt-get install -f`.

## Requirements

- **Installed package**: `panelsdcc-connect` (provides the fat JAR under `/usr/lib/panelsdcc-connect/`).
- **Network**: outbound HTTPS to `api.github.com` and GitHub release assets.
- **Privileges**: `check` only needs read access to dpkg state and write to the status file. **`install` must run as root** (e.g. `sudo`) so `dpkg` / `apt-get` can install the package.

## Status file

Progress and results are merged into:

`/var/lib/panelsdcc-connect/update-status.json`

Fields mirror the panels-control updater pattern, for example:

- `state`: `checking` | `update-available` | `up-to-date` | `downloading` | `installing` | `success` | `error`
- `currentVersion`, `latestVersion`, `targetVersion`, `hasUpdate`
- `message`, `lastError` (on failure)
- `updatedAt` (ISO-8601)

## Commands

```bash
# Query GitHub for latest release vs installed version (no install)
sudo panelsdcc-connect-updater check

# Download latest .deb and install (requires root)
sudo panelsdcc-connect-updater install

# Dry-run: exercise state transitions only (no network, no dpkg)
sudo panelsdcc-connect-updater test
```

From a development tree (without dpkg), the current version is read from the Maven `pom.properties` inside the built JAR on the classpath when you run:

```bash
java -cp target/panelsDCC-connect-*-jar-with-dependencies.jar \
  cc.panelsd.connect.updater.ConnectUpdater check
```

## GitHub release assets

The updater uses the [latest release](https://api.github.com/repos/PanelsDCC/connect/releases/latest) API and picks:

1. A `.deb` whose name contains `panelsdcc` (case-insensitive), or  
2. Otherwise the first `.deb` asset in the release.

Ensure each release you publish includes a suitable `panelsdcc-connect_*.deb` (or similarly named) asset.

## HTTP API (built into the daemon)

The web server exposes the same workflow without shell access:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/update` | JSON status (mirrors `update-status.json`, or a minimal object if never checked). |
| `POST` | `/api/update/check` | Run a GitHub check (same as `panelsdcc-connect-updater check`). |
| `POST` | `/api/update/install` | Run `sudo -n panelsdcc-connect-updater install` (needs sudoers below). |

The home page includes a **Software update** panel that calls these routes. **Install** requires the packaged file `/etc/sudoers.d/panelsdcc-connect-updater`, which allows user `dcc-io` to run only `/usr/local/bin/panelsdcc-connect-updater install` with `NOPASSWD`.

## Frontend integration (custom UI)

Typical flow (same idea as panels-control):

1. `GET /api/update` or read `/var/lib/panelsdcc-connect/update-status.json`.
2. `POST /api/update/check` when the user asks to check.
3. If `hasUpdate` is true, `POST /api/update/install` when the user confirms (or run the CLI with `sudo`).
4. Optionally restart or reload `panelsdcc-connect` after a successful install if your UI requires it.
