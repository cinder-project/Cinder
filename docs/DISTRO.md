# Cinder OS — Operator Guide

This document covers everything an operator needs to build, deploy, administer, and maintain a Cinder OS installation. It assumes comfort with the Linux command line and SSH. It does not assume familiarity with Cinder's internals — those are covered in `ARCHITECTURE.md`.

---

## Contents

1. [What Cinder OS Is](#1-what-cinder-os-is)
2. [Build Process](#2-build-process)
3. [Writing to SD Card or USB SSD](#3-writing-to-sd-card-or-usb-ssd)
4. [First Boot](#4-first-boot)
5. [Service Layout](#5-service-layout)
6. [Changing Presets and Configuration](#6-changing-presets-and-configuration)
7. [USB Mod Import Workflow](#7-usb-mod-import-workflow)
8. [Backup and Restore](#8-backup-and-restore)
9. [Updates](#9-updates)
10. [Administration Reference](#10-administration-reference)
11. [Troubleshooting](#11-troubleshooting)
12. [Hardware Notes](#12-hardware-notes)

---

## 1. What Cinder OS Is

Cinder OS is a Debian bookworm ARM64 image for Raspberry Pi 4/Pi 400 Minecraft hosting with two build profiles:

- `desktop` (default): full GUI host OS with desktop network/device management and mod/pack import launchers.
- `server`: headless profile tuned for dedicated unattended deployments.

What it does include:

- A hardened Debian bookworm ARM64 base (approximately 900MB installed)
- `cinder.service` — the systemd unit that starts and supervises Cinder Core
- `cinder-firstboot.service` — one-shot provisioning on first power-on
- The full Cinder Runtime (`launch.sh`, all five presets)
- The full Cinder Control scripts (`health-check.sh`, `metrics-display.sh`)
- The USB import pipeline (`usb-import.sh`)
- Pi 4-specific boot configuration (`config.txt`, `cmdline.txt`)
- OpenJDK 21 headless (ARM64)
- SSH with hardened defaults
- `nftables` firewall
- `fail2ban` for SSH brute-force protection
- Optional desktop profile packages (`xfce4`, `lightdm`, `firefox-esr`, removable-media UX stack)
- Online mod/pack import helper (`cinder-online-import.sh`) and USB import helper launcher

The base package set is defined in `os/build/packages.list`.
Desktop profile additions are defined in `os/build/packages-desktop.list`.

---

## 2. Build Process

Building Cinder OS requires a Debian or Ubuntu x86_64 host (or an ARM64 Pi 4 for native builds). The build script handles everything: debootstrap, package installation, Cinder file deployment, systemd configuration, Pi boot config, and image compression.

### Build host requirements

```bash
# Install build dependencies on the host
sudo apt install \
    debootstrap qemu-user-static binfmt-support \
    parted kpartx dosfstools e2fsprogs \
    rsync xz-utils git
```

### Running the build

```bash
# Clone the Cinder repository
git clone https://github.com/cinder-project/cinder.git
cd cinder

# Build with default settings (survival preset, 8GB image)
sudo ./os/build/build-image.sh

# Build with custom options
sudo ./os/build/build-image.sh \
    --version 0.1.0 \
    --output-dir /tmp/cinder-images \
    --image-size 16 \
    --profile server \
    --cinder-branch main
```

Build options:

| Option | Default | Description |
|---|---|---|
| `--version <ver>` | `1.0.0` | Version string embedded in the image name |
| `--output-dir <dir>` | `os/build/output/` | Where the finished image is written |
| `--image-size <gb>` | `8` | Image size in GB (desktop: recommended 8+, server: recommended 6+) |
| `--profile <desktop\|server>` | `desktop` | Select full GUI profile or headless server profile |
| `--skip-compress` | false | Skip xz compression (faster build, larger output) |
| `--cinder-branch <branch>` | `main` | Git branch to pull Cinder files from |
| `--password <pw>` | `cinderpi` | Default password for the `cinder` user (change on first boot) |

### Build output

```
os/build/output/
├── cinder-os-0.1.0-20240315.img        # Raw disk image (8GB)
├── cinder-os-0.1.0-20240315.img.xz     # Compressed image (~400MB)
├── cinder-os-0.1.0-20240315.img.sha256
├── cinder-os-0.1.0-20240315.img.xz.sha256
└── build-20240315.log                  # Full build log
```

Build time: approximately 15–25 minutes on a modern x86_64 host. The xz compression step takes the majority of this time.

### Verifying the build

```bash
# Verify the compressed image checksum
sha256sum -c cinder-os-0.1.0-20240315.img.xz.sha256
```

---

## 3. Writing to SD Card or USB SSD

### microSD card

```bash
# Identify your SD card device (check dmesg after inserting)
lsblk

# Decompress and write (replace sdX with your device — be careful)
xz -d cinder-os-0.1.0-20240315.img.xz
sudo dd if=cinder-os-0.1.0-20240315.img of=/dev/sdX bs=4M status=progress
sudo sync
```

Alternatively, use Raspberry Pi Imager: select **Use custom image** and point it at the `.img.xz` file.

### USB SSD / NVMe (recommended for production)

The image can be written to a USB SSD the same way as an SD card. To boot from USB:

1. Write the image to the USB SSD using `dd` or Pi Imager
2. Update the Pi 4's EEPROM boot order to prefer USB:

```bash
# On the Pi (booted from SD first), then:
sudo rpi-eeprom-config --edit
# Set: BOOT_ORDER=0xf41
# 0xf41 = try USB first, then SD card, then network
sudo reboot
```

USB3 SSD dramatically improves chunk IO performance. Expected improvement on chunk-heavy loads: 30–50% MSPT reduction in the CHUNK phase.

---

## 4. First Boot

Insert the SD card or USB SSD into the Pi 4 and power on. No display, keyboard, or monitor is required.

**`cinder-firstboot.service` runs automatically** and performs:

1. **Root partition expansion** — expands the ext4 root partition and filesystem to fill the full storage device. This takes 15–30 seconds.
2. **SSH host key generation** — generates fresh, unique SSH host keys. This ensures every Cinder installation has distinct keys.
3. **Hostname assignment** — sets the hostname to `cinder-<last4-of-eth0-mac>` (e.g., `cinder-a3f2`). This makes each Pi identifiable on the local network.

The first-boot service runs exactly once. After it completes, it removes its trigger file and will not run again on subsequent boots.

### Finding the Pi's IP address

```bash
# From another machine on the same network
nmap -sn 192.168.1.0/24 | grep -A1 cinder

# Or check your router's DHCP client list
# Or use mDNS: ping cinder-<id>.local
```

### First SSH connection

```bash
ssh cinder@cinder-<id>.local
# Default password: cinderpi
# Change it immediately:
passwd
```

### Post-first-boot checklist

```bash
# 1. Change the default password
passwd

# 2. Check that the server started
systemctl status cinder

# 3. Run a health check
/opt/cinder/scripts/health-check.sh

# 4. Confirm the world directory exists
ls /opt/cinder/world/

# 5. Set up SSH key authentication (recommended)
mkdir -p ~/.ssh
echo "ssh-ed25519 AAAA... your-key" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys

# 6. Optionally disable password authentication after adding your key
sudo sed -i 's/PasswordAuthentication yes/PasswordAuthentication no/' \
    /etc/ssh/sshd_config.d/cinder-hardening.conf
sudo systemctl reload sshd
```

---

## 5. Service Layout

Cinder OS runs three systemd services relevant to server operation:

### `cinder.service` — Primary server service

```
systemctl status cinder      # Check status
systemctl start cinder       # Start the server
systemctl stop cinder        # Stop the server (clean shutdown, up to 90s)
systemctl restart cinder     # Restart (stop + start)
journalctl -u cinder -f      # Follow live logs
journalctl -u cinder -n 100  # Last 100 log lines
```

`cinder.service` is enabled by default and starts automatically on boot after networking is ready.

### `cinder-firstboot.service` — First-boot provisioning

Runs once on first power-on and then disables itself. You should never need to interact with this service directly. If first-boot did not run (e.g., the trigger file was removed prematurely), it can be re-triggered:

```bash
sudo touch /etc/cinder-firstboot-pending
sudo systemctl start cinder-firstboot.service
```

### `cinder-health.timer` (if configured)

An optional systemd timer that runs `health-check.sh --quiet` every 5 minutes and writes results to the journal. Enable it:

```bash
sudo systemctl enable --now cinder-health.timer
journalctl -u cinder-health -n 20
```

---

## 6. Changing Presets and Configuration

### Switching presets

The active preset is set via a systemd environment variable:

```bash
# Switch to the event preset
sudo systemctl set-environment CINDER_PRESET=event
sudo systemctl restart cinder

# Verify
systemctl show-environment | grep CINDER_PRESET
```

Available presets: `survival` (default), `event`, `low-power`, `benchmark`, `extreme`.

### Override heap without editing the preset file

```bash
# Use a systemd drop-in override
sudo mkdir -p /etc/systemd/system/cinder.service.d/
sudo tee /etc/systemd/system/cinder.service.d/override.conf <<EOF
[Service]
Environment=CINDER_PRESET=event
Environment=CINDER_HEAP_MAX=3g
Environment=CINDER_HEAP_MIN=1g
EOF
sudo systemctl daemon-reload
sudo systemctl restart cinder
```

Drop-in overrides survive OS updates and are the preferred way to customise the service without editing the unit file directly.

### Editing a preset file

Preset files live at `/opt/cinder/cinder-runtime/presets/`. Each setting is documented inline. Edit with:

```bash
sudo nano /opt/cinder/cinder-runtime/presets/survival.conf
sudo systemctl restart cinder
```

### Viewing the current launch configuration

The last-launch metadata is written to a JSON file on every start:

```bash
cat /opt/cinder/logs/last-launch.json
```

To see the fully resolved JVM flags without starting the server:

```bash
sudo -u cinder /opt/cinder/cinder-runtime/launch/launch.sh \
    --preset survival --dry-run
```

---

## 7. USB Mod Import Workflow

The USB import pipeline allows deploying plugins, mods, datapacks, and config files from a USB drive. No network access to the Pi is required.

### Prepare the USB drive

On your development machine, create the following directory layout on the USB drive:

```
/cinder-import/
├── plugins/           (Bukkit/Cinder-compatible JAR files)
├── mods/              (Optional mod server payloads; PaperMC deployments typically use plugins/)
├── datapacks/         (ZIP files)
├── config/            (TOML, YAML, JSON config files)
└── checksums.sha256   (strongly recommended)
```

Generate the checksum file:

```bash
cd /path/to/usb-drive/cinder-import
find . -type f ! -name checksums.sha256 | sort | xargs sha256sum > checksums.sha256
```

The checksum file prevents corrupted files from being deployed. If it is absent, `usb-import.sh` will warn but proceed.

### Validation rules

Before any server file is touched, `usb-import.sh` validates every file in the import directory:

| Check | Behaviour on failure |
|---|---|
| Extension not in whitelist (`.jar .zip .json .toml .yaml .yml .txt .png`) | File rejected; import aborted |
| File size > 100MB | File rejected; import aborted |
| SHA-256 doesn't match `checksums.sha256` | File rejected; import aborted |
| Hash not in `/opt/cinder/config/import-allowlist.sha256` (if configured) | File rejected; import aborted |

If **any** file fails validation, the import aborts before touching any server files. This is all-or-nothing by design.

### Running the import

```bash
# 1. Stop the server (recommended — server-running check will warn if skipped)
sudo systemctl stop cinder

# 2. Insert the USB drive and run the import
sudo /opt/cinder/scripts/usb-import.sh

# 3. Review the output — the script reports each file's validation status
# 4. Start the server
sudo systemctl start cinder
```

The script auto-detects the USB device. To specify manually:

```bash
sudo /opt/cinder/scripts/usb-import.sh --device /dev/sda1
```

To validate without deploying:

```bash
sudo /opt/cinder/scripts/usb-import.sh --dry-run
```

### What the import does to your server

Each category deploys to a fixed destination:

| USB directory | Server destination |
|---|---|
| `plugins/` | `/opt/cinder/plugins/` |
| `mods/` | `/opt/cinder/mods/` |
| `datapacks/` | `/opt/cinder/world/datapacks/` |
| `config/` | `/opt/cinder/config/` |

**Existing files with the same name are overwritten.** The previous version is backed up before overwriting (see rollback below).

### Allowlist (optional, recommended for multi-operator deployments)

To restrict imports to a pre-approved set of files, create an allowlist:

```bash
# On your development machine, hash every approved plugin/mod:
sha256sum my-plugin-1.0.jar approved-mod-2.3.jar >> /opt/cinder/config/import-allowlist.sha256

# Or on the Pi directly:
sudo -u cinder sha256sum /opt/cinder/plugins/known-good-plugin.jar \
    >> /opt/cinder/config/import-allowlist.sha256
```

When the allowlist file exists, only files whose SHA-256 hash appears in it will be accepted.

### Rollback

Every import creates a versioned backup before overwriting any server files. The backup ID is a UTC timestamp (e.g., `20240315T143022Z`).

```bash
# List available rollback points
sudo /opt/cinder/scripts/usb-import.sh --list-backups

# Restore to a specific backup
sudo systemctl stop cinder
sudo /opt/cinder/scripts/usb-import.sh --rollback 20240315T143022Z
sudo systemctl start cinder
```

Rollback restores exactly the files that were overwritten or added by that import. Files that did not exist before the import are removed on rollback.

### Import log

Every import writes a detailed log and manifest to:

```
/opt/cinder/logs/import-manifests/import-<timestamp>.log
/opt/cinder/backups/usb-import/<timestamp>/manifest.txt
```

The manifest records every file's pre-import state, making rollback decisions auditable.

---

## 8. Backup and Restore

### Manual world backup

```bash
# Stop the server to ensure a consistent backup
sudo systemctl stop cinder

# Create a compressed backup
BACKUP_NAME="world-backup-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
sudo tar -czf "/opt/cinder/backups/${BACKUP_NAME}" \
    -C /opt/cinder world/

sudo systemctl start cinder
echo "Backup created: /opt/cinder/backups/${BACKUP_NAME}"
```

### Restoring a world backup

```bash
sudo systemctl stop cinder

# List available backups
ls -lh /opt/cinder/backups/world-backup-*.tar.gz

# Restore (this overwrites the current world directory)
BACKUP="/opt/cinder/backups/world-backup-20240315T143022Z.tar.gz"
sudo mv /opt/cinder/world /opt/cinder/world.bak
sudo mkdir -p /opt/cinder/world
sudo tar -xzf "${BACKUP}" -C /opt/cinder

sudo chown -R cinder:cinder /opt/cinder/world
sudo systemctl start cinder
```

### Automated backups

Automated backup support via `cinder-backup.sh` is planned for Phase 4. In the interim, a cron job provides basic automation:

```bash
# Edit crontab as the cinder user
sudo -u cinder crontab -e

# Add: backup world every 6 hours, keep 7 days
0 */6 * * * /opt/cinder/scripts/backup-world.sh >> /opt/cinder/logs/backup.log 2>&1
```

### Remote backup (rsync to control node)

```bash
# From the control node (Pi 4), pull world data from the compute node
rsync -avz --delete \
    cinder@cinder-compute.local:/opt/cinder/world/ \
    /opt/cinder/remote-backups/cinder-compute/world/
```

---

## 9. Updates

### Updating Cinder Core (JAR only)

When a new Cinder Core JAR is released:

```bash
sudo systemctl stop cinder

# Download and verify the new JAR
wget https://releases.cinder-project.dev/cinder-core-0.2.0.jar
sha256sum -c cinder-core-0.2.0.jar.sha256

# Replace the current JAR
sudo cp cinder-core-0.2.0.jar /opt/cinder/cinder-core/build/libs/cinder-core.jar
sudo chown cinder:cinder /opt/cinder/cinder-core/build/libs/cinder-core.jar

sudo systemctl start cinder
```

### Updating Cinder scripts (runtime, control, OS scripts)

```bash
# Pull updated scripts from the repository
cd /opt/cinder
sudo git pull origin main

# Make scripts executable
sudo find /opt/cinder -name '*.sh' -exec chmod 750 {} \;
sudo chown -R cinder:cinder /opt/cinder

# Reload the systemd unit if it changed
sudo systemctl daemon-reload
sudo systemctl restart cinder
```

### Updating the OS (security patches only)

Cinder OS uses `unattended-upgrades` configured for security-only updates. These apply automatically without restarting the server.

For manual security updates:

```bash
sudo apt update
sudo apt upgrade --with-new-pkgs
```

**Do not run `apt full-upgrade` or `apt dist-upgrade` on a production Cinder node.** This can pull in new kernel versions and dependency changes that require validation. Test on a secondary Pi before applying to production.

### Full image re-flash

For major OS updates (new Debian release, major Cinder OS version), the recommended path is a fresh image write:

1. Back up world data: copy `/opt/cinder/world/` and `/opt/cinder/backups/` off the Pi
2. Write the new image to a new SD card
3. First boot on the new image
4. Restore world data
5. Re-apply any drop-in overrides from `/etc/systemd/system/cinder.service.d/`

---

## 10. Administration Reference

### Server control

```bash
# Start / stop / restart
sudo systemctl start cinder
sudo systemctl stop cinder
sudo systemctl restart cinder

# Enable / disable autostart
sudo systemctl enable cinder
sudo systemctl disable cinder

# Check status
systemctl status cinder

# Follow live logs
journalctl -u cinder -f

# Last 200 log lines
journalctl -u cinder -n 200
```

### Monitoring

```bash
# Live TPS/MSPT dashboard
/opt/cinder/scripts/metrics-display.sh

# Quick health check
/opt/cinder/scripts/health-check.sh

# Health check in JSON (for scripting)
/opt/cinder/scripts/health-check.sh --json

# Single-line health summary
/opt/cinder/scripts/health-check.sh --brief
```

### System diagnostics

```bash
# CPU temperature (Pi 4)
vcgencmd measure_temp

# Throttle status (0x0 = healthy)
vcgencmd get_throttled

# Memory usage
free -h

# Disk usage
df -h /opt/cinder

# IO wait (check for SD card saturation)
iostat -x 2 5

# JVM process info
ps aux | grep cinder-core
cat /opt/cinder/logs/cinder.pid

# Open file handles (JVM + chunk files)
lsof -p $(cat /opt/cinder/logs/cinder.pid) | wc -l
```

### USB import

```bash
# List available rollbacks
sudo /opt/cinder/scripts/usb-import.sh --list-backups

# Dry run (validate without deploying)
sudo /opt/cinder/scripts/usb-import.sh --dry-run

# Import from a specific device
sudo /opt/cinder/scripts/usb-import.sh --device /dev/sda1

# Rollback
sudo /opt/cinder/scripts/usb-import.sh --rollback <backup-id>
```

### Log locations

| File | Contents |
|---|---|
| `/opt/cinder/logs/cinder-server.log` | Current server log (tick lines, warnings, errors) |
| `/opt/cinder/logs/cinder-server-<timestamp>.log.gz` | Rotated server logs |
| `/opt/cinder/logs/last-launch.json` | Launch metadata (preset, heap, Java version) |
| `/opt/cinder/logs/launch-<timestamp>.log` | launch.sh execution log |
| `/opt/cinder/logs/cinder.pid` | Current server PID (while running) |
| `/opt/cinder/logs/import-manifests/` | USB import logs and manifests |
| `/var/log/cinder-firstboot.log` | First-boot provisioning log |
| `journalctl -u cinder` | Structured journal output from cinder.service |

---

## 11. Troubleshooting

### Server fails to start

```bash
# Check service status for the error
systemctl status cinder

# Check launch log for the most recent attempt
ls -lt /opt/cinder/logs/launch-*.log | head -1
cat /opt/cinder/logs/launch-<latest>.log

# Verify Java is installed and correct version
java -version

# Verify the JAR exists
ls -lh /opt/cinder/cinder-core/build/libs/cinder-core.jar

# Dry run to see resolved config
sudo -u cinder /opt/cinder/cinder-runtime/launch/launch.sh \
    --preset survival --dry-run
```

### TPS is consistently below 18

```bash
# Check MSPT breakdown to identify the bottleneck phase
/opt/cinder/scripts/metrics-display.sh

# Run health check for system-level issues
/opt/cinder/scripts/health-check.sh

# Check for thermal throttling
vcgencmd get_throttled  # Should be 0x0

# Check CPU governor (should be 'performance' for benchmark/extreme)
cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor

# Check disk IO wait (high IO = chunk load bottleneck)
iostat -x 1 10
```

### World not saving / saves taking too long

```bash
# Check available disk space
df -h /opt/cinder/world

# Check the number of dirty chunks in the save queue
# (visible in STORAGE section of health-check --json)
/opt/cinder/scripts/health-check.sh --json | jq '.checks[] | select(.name=="world_writable")'

# Check if running from SD (slow) vs SSD (fast)
df /opt/cinder/world | tail -1
# Device starting with /dev/mmcblk = SD card; /dev/sda = USB SSD
```

### USB import rejected files

```bash
# Run with verbose output (default mode shows per-file status)
sudo /opt/cinder/scripts/usb-import.sh

# Common rejections:
# - "disallowed extension" → file type not in whitelist
# - "checksum mismatch" → file was corrupted during copy or modified after checksum generation
# - "hash not in allowlist" → file not pre-approved; add its hash to import-allowlist.sha256
# - "no checksum entry" → checksums.sha256 on USB does not include this file; regenerate it
```

### OOM kills / server crashing with no log output

```bash
# Check for OOM kill events
dmesg | grep -i "killed process"
journalctl -k | grep -i oom

# If OOM killing the JVM, reduce heap max in the preset
# Or switch to a smaller preset (e.g., survival → low-power)
sudo systemctl set-environment CINDER_HEAP_MAX=1536m
sudo systemctl restart cinder
```

### SSH connection refused after first boot

```bash
# The Pi may still be running first-boot provisioning (takes up to 2 minutes)
# Wait 2 minutes and retry

# If still failing, check if SSH is running (requires HDMI + keyboard access or serial console):
systemctl status sshd
# Restart SSH if it failed:
sudo systemctl start sshd
```

---

## 12. Hardware Notes

### Cooling

Pi 4 will throttle at 80°C. Sustained Minecraft server load at 20 TPS typically runs the Pi 4 at 65–75°C with passive cooling. The following cooling options are tested with Cinder:

| Solution | Max stable temp | Recommended preset |
|---|---|---|
| Bare Pi 4 (no case) | ~72°C | `low-power` or `survival` |
| Official Pi case with fan | ~65°C | `survival` |
| ICE Tower cooler | ~52°C | `event` or `extreme` |
| Argon ONE M.2 (with NVMe) | ~58°C | `event` or `extreme` |

Monitor temperature during normal operation:

```bash
watch -n 5 vcgencmd measure_temp
```

If temperature exceeds 75°C during a session, switch to the `low-power` preset or improve cooling before enabling `event` or `extreme`.

### Storage recommendations

| Storage | Read (seq) | Write (seq) | Recommendation |
|---|---|---|---|
| Class 10 microSD | ~40 MB/s | ~15 MB/s | Acceptable for `low-power`/`survival` |
| V30 / A2 microSD | ~90 MB/s | ~40 MB/s | Good for `survival`/`event` |
| USB3 SSD (SATA) | ~400 MB/s | ~350 MB/s | Excellent for all presets |
| USB3 NVMe (via adapter) | ~900 MB/s | ~700 MB/s | Maximum performance for `extreme` |

For microSD: use a Samsung Endurance Pro or Lexar Endurance card. Standard consumer cards are not designed for the sustained small-random-write pattern of a Minecraft server.

### Power supply

Use the official Raspberry Pi 15W USB-C power supply or equivalent (5.1V / 3A). Undervoltage causes CPU throttling that will degrade TPS unpredictably. Check for undervoltage:

```bash
vcgencmd get_throttled
# Bit 0 = under-voltage now
# Bit 16 = under-voltage has occurred since last reboot
```

If bit 0 or bit 16 is set, replace the power supply before debugging any performance issues.

### Pi 400 notes

The Pi 400 uses the same SoC as the Pi 4 but runs at 1.8GHz (vs 1.5GHz standard on Pi 4). This gives a modest performance advantage for the tick thread. The built-in keyboard is irrelevant for server use — Cinder OS is headless. The Pi 400's integrated case provides better passive cooling than a bare Pi 4 board but less than a dedicated heatsink solution. Suitable for `survival` preset without additional cooling; `event` preset benefits from an external fan.
