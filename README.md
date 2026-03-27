# Cinder

**High-performance Minecraft hosting platform and Debian-based Linux distribution for Raspberry Pi 4 and Pi 400.**

Cinder is a purpose-built server platform designed around one constraint: extract maximum, sustained Minecraft server performance from the Raspberry Pi 4's quad-core ARM64 Cortex-A72, 8GB LPDDR4, and gigabit Ethernet — without pretending the hardware is something it isn't.

It is two things simultaneously:

- **Cinder OS** — a minimal Debian ARM64 image that boots directly into a hardened, Pi 4-optimised hosting environment
- **Cinder Core** — a Minecraft server engine with a custom tick loop, batched entity pipeline, async chunk lifecycle management, and a full profiling and benchmarking stack

Neither half works without the other. The OS is tuned for the engine. The engine is written for the OS.

---

## Why Cinder Exists

Running a Minecraft server on a Pi 4 is not novel. Running one that holds 20 TPS under real player load, survives 20+ concurrent connections, and gives operators the diagnostic visibility to understand *why* it degrades — that is the problem Cinder is built to solve.

Existing approaches fall into three categories:

1. **Generic server JARs on stock Raspberry Pi OS** — workable but untuned. The OS carries desktop baggage. The JVM runs with default flags. Chunk IO blocks the tick thread. No profiling data is available without installing third-party tooling.

2. **Pre-configured images** — opinionated, not engineered. They pick reasonable defaults but offer no upgrade path and no visibility into what those defaults actually do.

3. **Nothing** — most Pi Minecraft guides end at "increase your swap."

Cinder is the fourth category: an engineered platform with documented performance decisions, measurable outcomes, and a clean path from first boot to production hosting.

---

## Hardware Target

| Device | Role |
|---|---|
| Raspberry Pi 4 (8GB) | Control / orchestration / monitoring node |
| Raspberry Pi 400 (8GB) | Compute / Minecraft server node |

Both roles can run on a single Pi 4. Cinder's two-node separation is the recommended architecture for sustained event hosting, not a requirement for getting started.

**Minimum hardware:** Raspberry Pi 4 with 4GB RAM, active cooling, gigabit Ethernet, Class 10 microSD or USB3 SSD.

**Recommended hardware:** Raspberry Pi 4 8GB with ICE Tower or Argon ONE M.2 cooling, USB3 NVMe SSD boot, gigabit wired Ethernet.

---

## What Cinder OS Does

Cinder OS is a minimal Debian bookworm ARM64 image built specifically for Pi 4 hosting:

- **Headless by design** — no desktop, no X11, no GPU stack. `gpu_mem=16`. WiFi and Bluetooth overlays disabled. Every removed package is a saved interrupt, a freed memory page, and a reduced attack surface.
- **Boots into `cinder.service`** — systemd starts Cinder Core automatically on boot with the configured preset, CPU affinity, and JVM flags.
- **First-boot provisioning** — root partition expansion, SSH key generation, and hostname assignment happen automatically on first power-on. No manual setup required beyond writing the image to SD.
- **USB mod import pipeline** — a validated, staged, versioned import workflow for deploying plugins, mods, datapacks, and config files from a USB drive without network access.
- **Hardened by default** — SSH with `PermitRootLogin no`, `fail2ban`, `nftables`, unprivileged `cinder` user, `ProtectSystem=strict` in the systemd unit.
- **SD card-aware** — ext4 without journaling to reduce write amplification, `noatime` mounts, bounded journald storage, and a 90-second graceful shutdown window to complete world saves before power-off.

---

## What Cinder Core Does

Cinder Core is the server engine. It is not a fork of an existing server JAR. It is an original implementation of the components that matter most for Pi 4 performance:

### Tick Loop
`CinderTickLoop` drives the server at 20 TPS using `LockSupport.parkNanos()` for sub-millisecond sleep precision on ARM64 Linux. Drift correction prevents lag spirals when individual ticks overrun their 50ms budget. Five ordered tick phases — `PRE → WORLD → ENTITY → CHUNK → POST` — ensure causal consistency across all systems.

### Entity Pipeline
`EntityUpdatePipeline` organises entities into three priority tiers:
- **CRITICAL** — player-attached entities, projectiles. Updated every tick without deferral.
- **STANDARD** — mobs, animals, items. Updated every tick with per-tick CPU budget enforcement; tail entities are deferred rather than forcing tick overruns.
- **DEFERRED** — distant, decorative, or sleeping entities. Updated every 4 ticks on a round-robin slice schedule to spread CPU cost evenly.

Async pre-computation (pathfinding, AI goal evaluation) runs on a bounded worker pool between ticks. Results are applied synchronously at the next PRE phase.

### Chunk Lifecycle
`ChunkLifecycleManager` never blocks the tick thread for disk IO. Chunk loads are submitted to a single-threaded IO executor; results are promoted to the cache via the sync task queue. Chunk saves are async with snapshot isolation. An LRU cache with configurable capacity and holder-based eviction protection keeps frequently-accessed chunks in memory.

### Scheduler
`CinderScheduler` is the bridge between the tick thread and all external systems. External threads post sync tasks to a lock-free queue; the tick thread drains them during PRE. Delayed and repeating tasks are managed via a priority queue ordered by target tick.

### Profiler
`TickProfiler` maintains a 1200-slot ring buffer (60 seconds at 20 TPS) of `TickSnapshot` records. Each snapshot captures total MSPT and per-phase durations. Rolling statistics (mean, p50, p95, p99, spike count) are computed on demand for Cinder Control's monitoring dashboard.

---

## Runtime Presets

Cinder ships five tuning presets, each a self-documenting `.conf` file that controls JVM flags, GC tuning, entity pipeline parameters, chunk cache size, and world save intervals:

| Preset | Players | Use Case |
|---|---|---|
| `survival` | 10–20 | Default 24/7 hosting. Stable TPS, 2GB heap, conservative GC. |
| `event` | 20–35 | Scheduled sessions. Larger heap, tighter entity radii, per-tick logging. |
| `low-power` | 2–8 | Passively cooled or shared-power Pi 4. SerialGC, 768MB heap. |
| `benchmark` | 0 | Deterministic measurement. Fixed heap, JIT warmup bias, save disabled. |
| `extreme` | 35–50 | Actively cooled, characterised hardware. Max heap, aggressive GC, raised view distance. |

Switch preset: `systemctl set-environment CINDER_PRESET=event && systemctl restart cinder`

---

## Setup Overview

### 1. Build or download the Cinder OS image

```bash
# Build from source (requires Debian/Ubuntu x86_64 host with build tools)
sudo ./os/build/build-image.sh --version 0.1.0

# Output: os/build/output/cinder-os-0.1.0-<date>.img.xz
```

### 2. Write to microSD or USB SSD

```bash
# Decompress and write
xz -d cinder-os-0.1.0-<date>.img.xz
sudo dd if=cinder-os-0.1.0-<date>.img of=/dev/sdX bs=4M status=progress
sudo sync
```

### 3. First boot

Insert SD into Pi 4 and power on. First-boot provisioning runs automatically:
- Root partition expands to fill the card
- SSH host keys are generated
- Hostname is set to `cinder-<last4-of-mac>`

Find the IP: `nmap -sn 192.168.1.0/24 | grep cinder` or check your router's DHCP table.

### 4. Connect and configure

```bash
ssh cinder@cinder-<id>.local
# Default password: cinderpi  (change immediately)
passwd

# Check server status
systemctl status cinder

# View live metrics
/opt/cinder/scripts/metrics-display.sh

# Run health check
/opt/cinder/scripts/health-check.sh
```

### 5. Import mods / plugins via USB

```bash
# Prepare your USB drive with the expected layout:
# /cinder-import/
#   plugins/   (JAR files)
#   mods/      (JAR files)
#   datapacks/ (ZIP files)
#   config/    (config files)
#   checksums.sha256

# On the Pi, with the server stopped:
systemctl stop cinder
/opt/cinder/scripts/usb-import.sh
systemctl start cinder
```

---

## Project Status

Cinder is in active early development. The following components are implemented:

| Component | Status |
|---|---|
| `CinderTickLoop` | ✅ Complete |
| `EntityUpdatePipeline` | ✅ Complete |
| `ChunkLifecycleManager` | ✅ Complete |
| `CinderScheduler` | ✅ Complete |
| `TickProfiler` / `TickSnapshot` | ✅ Complete |
| Runtime presets (all 5) | ✅ Complete |
| `launch.sh` | ✅ Complete |
| `cinder.service` (systemd) | ✅ Complete |
| `usb-import.sh` | ✅ Complete |
| `build-image.sh` | ✅ Complete |
| `packages.list` | ✅ Complete |
| `health-check.sh` | ✅ Complete |
| `metrics-display.sh` | ✅ Complete |
| `CinderWorld` (world state) | 🔧 In progress |
| `CinderNetworkManager` | 🔧 In progress |
| `CinderEntity` / `CinderChunk` types | 🔧 In progress |
| Cinder Bench load simulator | 📋 Planned |
| Backup automation scripts | 📋 Planned |
| `ARCHITECTURE.md` | ✅ Complete |
| `ROADMAP.md` | 📋 Planned |
| `CONTRIBUTING.md` | 📋 Planned |

---

## Contributing

Cinder is an open engineering project. Contributions are welcome in any of these areas:

- **Core engine** — `CinderWorld`, `CinderNetworkManager`, entity/chunk type implementations
- **OS layer** — package list refinements, security hardening, first-boot tooling
- **Benchmarking** — Cinder Bench load simulation, metric collection, result analysis
- **Documentation** — architecture notes, operational guides, hardware test reports

See `docs/CONTRIBUTING.md` for code style, PR expectations, and performance profiling requirements.

---

## Licence

Cinder is released under the MIT Licence. See `LICENSE` for full terms.

---

*Cinder — built for the Pi 4, engineered for the task.*
