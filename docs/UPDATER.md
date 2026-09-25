# panelsDCC-connect updater

The updater checks [GitHub Releases](https://github.com/PanelsDCC/connect/releases) for a newer `panelsdcc-connect` `.deb`, compares it to the installed package version, and can download and install the update with `dpkg` / `apt-get install -f`.

For local development you can also **upload a built `.deb`** from the web UI (or install from a fixed path via CLI) without publishing a GitHub release.

## Requirements

- **Installed package**: `panelsdcc-connect` (provides the fat JAR under `/usr/lib/panelsdcc-connect/`).
- **Network**: outbound HTTPS to `api.github.com` and GitHub release assets (GitHub check/install only).
- **Privileges**: `check` only needs read access to dpkg state and write to the status file. **`install` / `install-local` must run as root** (e.g. `sudo`) so `dpkg` / `apt-get` can install the package.

## Status file

Progress and results are merged into:

`/var/lib/panelsdcc-connect/update-status.json`

Fields mirror the panels-control updater pattern, for example:

- `state`: `checking` | `update-available` | `up-to-date` | `downloading` | `installing` | `success` | `error`
- `currentVersion`, `latestVersion`, `targetVersion`, `hasUpdate`
- `message`, `lastError` (on failure)
- `updatedAt` (ISO-8601)

Uploaded packages are stored at a fixed path (shared with the install unit; not under the service `PrivateTmp`):

`/var/lib/panelsdcc-connect/uploads/pending.deb`

## Commands

```bash
# Query GitHub for latest release vs installed version (no install)
sudo panelsdcc-connect-updater check

# Download latest .deb and install (requires root); waits until dpkg finishes
sudo panelsdcc-connect-updater install

# Same install, but only queue the transient systemd unit (returns immediately)
sudo panelsdcc-connect-updater install --background

# Install a previously uploaded local .deb (see pending.deb path above)
sudo panelsdcc-connect-updater install-local
sudo panelsdcc-connect-updater install-local --background

# Dry-run: exercise state transitions only (no network, no dpkg)
sudo panelsdcc-connect-updater test
```

From a development tree (without dpkg), the current version is read from the Maven `pom.properties` inside the built JAR on the classpath when you run:

```bash
java -cp target/panelsDCC-connect-*-jar-with-dependencies.jar \
  cc.panelsd.connect.updater.ConnectUpdater check
```

## Local development (upload `.deb`)

Typical loop without GitHub:

1. Build a package: `./build-deb.sh` (or your usual packaging command).
2. Open the Connect UI → **Software update**.
3. Choose the `.deb` under **Install from local package** and click **Install from file**.
4. The UI posts the file to `POST /api/update/upload`, the daemon saves `pending.deb`, queues `install-local --background`, and you poll status the same way as a GitHub install (the service may restart).

CLI equivalent after copying a `.deb` to the pending path:

```bash
sudo cp ./panelsdcc-connect_*.deb /var/lib/panelsdcc-connect/uploads/pending.deb
sudo panelsdcc-connect-updater install-local
```

## GitHub release assets

Release `.deb` downloads use URLs that often **HTTP 302** to another host (e.g. `objects.githubusercontent.com`). The updater’s HTTP client follows **cross-origin** redirects (`Redirect.ALWAYS`); the default would not, and you would see `Download failed with status 302`.

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
| `POST` | `/api/update/install` | Queue `sudo -n panelsdcc-connect-updater install --background` (needs sudoers below). Returns **202** with `{ "accepted": true, "message": "..." }`; poll `GET /api/update` for progress. |
| `POST` | `/api/update/upload` | Accept a raw `.deb` body (`application/octet-stream`), optional `?filename=…`. Validates size (max 100MB) and Debian `!<arch>` magic, writes `uploads/pending.deb`, then queues `install-local --background`. Returns **202**; poll `GET /api/update`. |

The home page includes a **Software update** panel that calls these routes. **Install** / **upload** require the packaged file `/etc/sudoers.d/panelsdcc-connect-updater`, which allows user `dcc-io` to run `/usr/local/bin/panelsdcc-connect-updater install`, `… install --background`, `… install-local`, and `… install-local --background` with `NOPASSWD`.

**Why not `install` without `--background` from the web UI?** The CLI uses `systemd-run --wait` so you see success/failure in the shell. The daemon used the same path, but when `dpkg` upgrades the package, maintainer scripts typically **restart** `panelsdcc-connect`. That stops the **service cgroup** and **SIGTERM**s the parent `sudo` / `systemd-run --wait` chain (often **exit 143**), while the **transient install unit** can still finish and write `success` to the status file — so the update succeeds but the HTTP handler reported failure. **Background** install only **queues** the unit and returns before the restart, so the UI polls `GET /api/update` instead.

### Systemd: `NoNewPrivileges` and `sudo`

The service runs as **`dcc-io`**, not root. Install uses **`sudo -n`** so that user can run the updater as root. That only works if the main process is **not** started with the kernel **no new privileges** bit set.

If you see:

`sudo: The "no new privileges" flag is set, which prevents sudo from running as root`

then the unit had **`NoNewPrivileges=true`**, which blocks `sudo` (even with correct sudoers). The packaged unit sets **`NoNewPrivileges=false`** so web-triggered installs work. If you override the unit, keep **`NoNewPrivileges=false`** or use **CLI install** from an SSH session as root: `sudo panelsdcc-connect-updater install`.

### Systemd: `ProtectSystem` / read-only root — `dpkg: Read-only file system`

The service unit uses **`ProtectSystem=strict`** and limited **`ReadWritePaths`**. Even when **`sudo`** runs **`dpkg`** as root, it stays in the **same mount namespace** as the daemon, so **`/var/lib/dpkg`**, **`/usr`**, and **`apt`** caches can be read-only and **`dpkg` fails**.

The **`panelsdcc-connect-updater`** script runs **`install`** / **`install-local`** inside **`systemd-run --wait`** (or **`--no-block`** for **`--background`**) with **`ProtectSystem=no`** (and related relaxations) so **`dpkg`/`apt`** run in a **transient** unit with a normal writable filesystem. **`check`** / **`test`** still run in-process and do not need this.

If **`systemd-run`** is unavailable, install from SSH: `sudo dpkg -i /path/to/panelsdcc-connect_*.deb` (or `sudo apt install ./file.deb`).

## Frontend integration (custom UI)

Typical flow (same idea as panels-control):

1. `GET /api/update` or read `/var/lib/panelsdcc-connect/update-status.json`.
2. `POST /api/update/check` when the user asks to check.
3. If `hasUpdate` is true, `POST /api/update/install` when the user confirms (or run the CLI with `sudo`).
4. For a local package, `POST /api/update/upload` with the raw `.deb` body (optional `?filename=`).
5. Optionally restart or reload `panelsdcc-connect` after a successful install if your UI requires it.
