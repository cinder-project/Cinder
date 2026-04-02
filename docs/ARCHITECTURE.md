# Cinder Architecture

This document describes the current Cinder architecture for the stable `1.0.0` line.

It focuses on what ships and runs in production images first, then summarizes the experimental Java track that still exists in this repository.

---

## 1. Layered System Overview

```text
Hardware (Raspberry Pi 4 / Pi 400)
  -> Cinder OS (Debian ARM64 image)
    -> systemd units (cinder.service, firstboot)
      -> Cinder Runtime scripts (launch + prestart + update + backup + import)
        -> PaperMC server process
          -> Cinder Control scripts (health + metrics + reporting)
```

Primary production runtime target is PaperMC.

---

## 2. Boot and Provisioning Flow

First boot is handled by `firstboot.sh` via `cinder-firstboot.service`.

Core responsibilities:

1. Expand `/data` partition and filesystem.
2. Build data topology and symlinks under `/opt/cinder`.
3. Create service user (`cinder`) and admin user (`cinder-admin`).
4. Apply optional boot-partition customization files.
5. Configure SSH hardening, firewall, fail2ban, and service overrides.
6. Run prestart validation and start `cinder.service`.

Boot customization files read from `/boot/firmware`:

- `cinder-hostname.txt`
- `cinder-pubkey.txt`
- `cinder-preset.txt`
- `cinder-eula.txt`
- `cinder-password.txt`

---

## 3. Runtime Service Flow

`cinder.service` startup chain:

```text
ExecStartPre: /opt/cinder/scripts/prestart-check.sh
  -> validates Java, jar, memory, disk, directories, governor policy

ExecStart: /opt/cinder/cinder-runtime/launch/launch.sh --preset <preset> --no-watchdog
  -> loads preset
  -> applies JVM flags
  -> rotates logs
  -> handles EULA file policy
  -> launches PaperMC jar with taskset/nice/ionice
```

Runtime jar path:

- `/opt/cinder/server/paper-server.jar`

Launch metadata written to:

- `/opt/cinder/logs/last-launch.json`

---

## 4. Storage Topology

The image uses a 3-partition layout:

- Boot (`/boot/firmware`, FAT32)
- Root (`/`, ext4)
- Data (`/data`, ext4)

Operational directories are rooted under `/data`, then linked into `/opt/cinder`:

- `/opt/cinder/world` -> `/data/world`
- `/opt/cinder/logs` -> `/data/logs`
- `/opt/cinder/backups` -> `/data/backups`
- `/opt/cinder/staging` -> `/data/staging`
- `/opt/cinder/plugins` -> `/data/plugins`
- `/opt/cinder/mods` -> `/data/mods`
- `/opt/cinder/resourcepacks` -> `/data/resourcepacks`
- `/opt/cinder/downloads` -> `/data/downloads`
- `/opt/cinder/metrics` -> `/data/metrics`

This keeps mutable runtime data off the root partition and simplifies backup/restore.

---

## 5. Build Profiles

`build-image.sh` supports two profiles:

- `desktop` (default): GUI and desktop import helpers included.
- `server`: headless profile for dedicated deployments.

Profile is propagated to chroot provisioning and package manifest selection (`packages.list` + optional `packages-desktop.list`).

---

## 6. Operational Pipelines

### 6.1 USB Import Pipeline

`usb-import.sh` stages and validates files from `cinder-import/` on removable media.

Validation includes:

- Extension allowlist
- File-size bounds
- Optional checksum verification (`checksums.sha256`)
- Optional hash allowlists

Deployment is backup-first and rollback-capable.

### 6.2 Online Import

`cinder-online-import.sh` fetches plugin/mod/datapack/resourcepack artifacts from URL into target directories with extension guards.

### 6.3 Backup and Update

- `backup.sh` (`cinder-backup.sh` wrapper) creates timestamped world archives.
- `update.sh` (`cinder-update.sh` wrapper) deploys new PaperMC JARs with rollback slot support.

---

## 7. CI and Release Architecture

Current release flow (main branch push):

1. Build/test Java sources.
2. Build ARM64 OS image.
3. Create ISO bundle (CI floor: `2 GiB`).
4. Create/update GitHub release and upload assets.

Important constraint:

- GitHub release uploads require each asset to be strictly smaller than `2147483648` bytes.
- Workflow now filters oversized files from release upload and emits warnings instead of failing the whole release job.

This preserves release continuity while still allowing large artifacts in workflow artifacts.

---

## 8. Experimental Java Track

The repository still contains an extensive Java implementation track:

- Tick loop and scheduler
- Entity/chunk/world/network modules
- Plugin API (`dev.cinder.plugin`)
- Benchmark harness (`cinder-bench`)

These modules are actively testable and useful for R and D, but they are not the default runtime started by the stable OS service today.

---

## 9. Design Priorities

Current priorities for the stable line:

1. Predictable on-device operations on Pi hardware.
2. Reliable release automation (no fragile artifact upload failures).
3. Clear operator workflows for build, first boot, updates, and rollback.
4. Maintain a clean path for future engine/runtime experimentation without breaking shipping behavior.
