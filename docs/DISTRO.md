# Cinder OS Operator Guide

This guide covers build, deployment, first boot, operations, update, and troubleshooting for the Cinder OS stable line.

Scope:

- Raspberry Pi 4 / Pi 400 targets
- Debian ARM64 image produced by `os/build/build-image.sh`
- PaperMC runtime managed by `cinder.service`

---

## 1. Build the Image

### 1.1 Host Requirements

Recommended host: Debian/Ubuntu x86_64.

Install dependencies:

```bash
sudo apt-get update
sudo apt-get install -y \
  debootstrap qemu-user-static binfmt-support \
  parted kpartx dosfstools e2fsprogs \
  rsync xz-utils xorriso zstd curl
```

### 1.2 Build Command

Desktop profile (default):

```bash
sudo ./os/build/build-image.sh \
  --version 1.0.0 \
  --image-size-gib 8 \
  --profile desktop
```

Headless server profile:

```bash
sudo ./os/build/build-image.sh \
  --version 1.0.0 \
  --image-size-gib 8 \
  --profile server
```

Common options:

- `--output-dir <path>`
- `--work-dir <path>`
- `--paper-mc-version <version>`
- `--paper-build <build|latest>`

### 1.3 Build Outputs

Default output directory: `os/build/output`

Expected artifacts:

- `cinder-os-<version>-arm64.img.zst`
- `cinder-os-<version>-arm64.img.zst.sha256`
- `build-image-<version>.log`

Optional release bundle:

```bash
./os/build/create-release-iso.sh \
  --output-dir ./os/build/output \
  --version 1.0.0 \
  --min-size-gib 2
```

---

## 2. Flash and Boot

Write image:

```bash
zstd -d cinder-os-1.0.0-arm64.img.zst -o cinder-os-1.0.0-arm64.img
sudo dd if=cinder-os-1.0.0-arm64.img of=/dev/sdX bs=4M status=progress conv=fsync
```

Replace `/dev/sdX` with your target device.

---

## 3. First-Boot Customization (Recommended)

Before first boot, mount the boot partition and add these optional files:

- `cinder-hostname.txt` -> hostname
- `cinder-pubkey.txt` -> SSH public key for `cinder-admin`
- `cinder-preset.txt` -> `survival|event|low-power|benchmark|extreme`
- `cinder-eula.txt` -> `true|false`
- `cinder-password.txt` -> initial `cinder-admin` password (deleted after use)

Important:

- SSH hardening disables password auth by default.
- Provide `cinder-pubkey.txt` if you need remote SSH access immediately.

First boot tasks include partition expansion, data topology setup, user creation, and service startup.

---

## 4. Runtime Layout

Key paths:

- Runtime base: `/opt/cinder`
- Paper jar: `/opt/cinder/server/paper-server.jar`
- Launch script: `/opt/cinder/cinder-runtime/launch/launch.sh`
- Service: `/etc/systemd/system/cinder.service`
- Logs: `/opt/cinder/logs`
- World data: `/opt/cinder/world` (linked to `/data/world`)

Users:

- `cinder` (service account)
- `cinder-admin` (operator account)

---

## 5. Daily Operations

Service control:

```bash
sudo systemctl status cinder
sudo systemctl restart cinder
sudo systemctl stop cinder
```

Logs:

```bash
journalctl -u cinder -f
tail -f /opt/cinder/logs/paper-server.log
```

Health and metrics:

```bash
/opt/cinder/cinder-control/services/health-check.sh
/opt/cinder/cinder-control/monitoring/metrics-display.sh
```

---

## 6. Content Import Workflows

### 6.1 USB Import

Use staged, validated import:

```bash
sudo systemctl stop cinder
sudo /opt/cinder/scripts/usb-import.sh
sudo systemctl start cinder
```

USB layout:

```text
cinder-import/
  plugins/
  mods/
  datapacks/
  config/
  checksums.sha256
```

Useful commands:

- Dry run: `sudo /opt/cinder/scripts/usb-import.sh --dry-run`
- List backups: `sudo /opt/cinder/scripts/usb-import.sh --list-backups`
- Rollback: `sudo /opt/cinder/scripts/usb-import.sh --rollback <id>`

### 6.2 Online Import

```bash
sudo /opt/cinder/scripts/cinder-online-import.sh --interactive
```

or non-interactive:

```bash
sudo /opt/cinder/scripts/cinder-online-import.sh \
  --url "https://example.com/plugin.jar" \
  --target plugins
```

---

## 7. Backups

Wrapper command:

```bash
sudo /opt/cinder/scripts/cinder-backup.sh --retention 14
```

Direct command:

```bash
sudo /opt/cinder/scripts/backup.sh --retention 14
```

Backups are written to `/opt/cinder/backups` as `world-<timestamp>.tar.zst`.

---

## 8. Runtime Updates

Update PaperMC jar safely:

```bash
sudo /opt/cinder/scripts/cinder-update.sh --jar /path/to/new-paper.jar --restart
```

This process maintains a rollback slot at:

- `/opt/cinder/server/paper-server.jar.rollback`

---

## 9. Release Artifact Notes

- CI builds a large ISO bundle with a 2 GiB minimum size floor.
- GitHub release assets must be `< 2147483648` bytes each.
- Oversized assets are skipped during release upload to avoid failing the release job.
- Full-size artifacts remain available through workflow artifacts.

---

## 10. Quick Troubleshooting

Prestart validation failures:

```bash
sudo /opt/cinder/scripts/prestart-check.sh
```

Missing or broken runtime jar:

```bash
ls -lh /opt/cinder/server/paper-server.jar
java -version
```

EULA startup failures:

```bash
cat /opt/cinder/eula.txt
# must contain: eula=true
```

Thermal and power checks:

```bash
vcgencmd measure_temp
vcgencmd get_throttled
```

---

For contributor standards and PR workflow, see `CONTRIBUTING.md`.
