# Cinder Architecture

This document describes the technical architecture of Cinder: its server engine, OS layer, runtime model, node separation, and key operational flows. It is the primary reference for contributors and operators who need to understand how the pieces fit together.

---

## Contents

1. [System Overview](#1-system-overview)
2. [Node Separation](#2-node-separation)
3. [Cinder Core — Server Engine](#3-cinder-core--server-engine)
4. [Runtime Layer](#4-runtime-layer)
5. [Cinder OS — Distribution Layer](#5-cinder-os--distribution-layer)
6. [USB Import Flow](#6-usb-import-flow)
7. [Monitoring and Diagnostics Architecture](#7-monitoring-and-diagnostics-architecture)
8. [Performance Engineering Decisions](#8-performance-engineering-decisions)
9. [Threading Model](#9-threading-model)
10. [Data Flow Summary](#10-data-flow-summary)

---

## 1. System Overview

Cinder is a layered platform. Each layer has a single well-defined responsibility:

```
┌─────────────────────────────────────────────────────────────────┐
│  Cinder Control                                                  │
│  Health checks · Metrics dashboard · Backup orchestration       │
├─────────────────────────────────────────────────────────────────┤
│  Cinder Runtime                                                  │
│  Launch script · JVM flags · Preset loading · Watchdog          │
├─────────────────────────────────────────────────────────────────┤
│  Cinder Core                                                     │
│  Tick loop · Entity pipeline · Chunk lifecycle · Scheduler      │
│  Profiler · Network manager · World state                        │
├─────────────────────────────────────────────────────────────────┤
│  Cinder OS                                                       │
│  Debian ARM64 base · systemd services · USB import pipeline      │
│  Pi 4 hardware config · Security hardening                       │
├─────────────────────────────────────────────────────────────────┤
│  Hardware: Raspberry Pi 4 / Pi 400 (ARM64, LPDDR4, GbE)         │
└─────────────────────────────────────────────────────────────────┘
```

The layers communicate only through well-defined interfaces:
- Cinder Core ↔ Cinder Runtime: system properties, `CinderScheduler` task queue
- Cinder Runtime ↔ Cinder Control: `last-launch.json`, server log, PID file
- Cinder Core ↔ Cinder OS: systemd unit supervision, `sd_notify` watchdog heartbeat
- Cinder Control ↔ Cinder OS: `health-check.sh`, `metrics-display.sh`, `usb-import.sh`

---

## 2. Node Separation

Cinder separates concerns across two Pi 4 nodes in its recommended two-node deployment. The separation is not a technical requirement — both roles run on a single Pi 4 — but it isolates the server tick loop from all non-game work.

```
┌───────────────────────────┐         ┌───────────────────────────┐
│  CONTROL NODE             │         │  COMPUTE NODE             │
│  Raspberry Pi 4 (8GB)     │◄──GbE──►│  Raspberry Pi 400 (8GB)   │
│                           │         │                           │
│  Cinder Control           │         │  Cinder Core              │
│  - health-check.sh        │         │  - CinderTickLoop         │
│  - metrics-display.sh     │         │  - EntityUpdatePipeline   │
│  - backup orchestration   │         │  - ChunkLifecycleManager  │
│  - log aggregation        │         │  - CinderScheduler        │
│  - USB import interface   │         │  - TickProfiler           │
│                           │         │                           │
│  Monitoring services      │         │  Player connections        │
│  - sysstat / iostat       │         │  World data (NVMe/SSD)    │
│  - journald aggregation   │         │  cinder.service           │
└───────────────────────────┘         └───────────────────────────┘
```

**Why separate?**

On a quad-core Pi 4, every background process is a competitor for the tick thread. Log aggregation, backup compression, health check forks, and monitoring polls all generate memory pressure, disk IO, and CPU interrupts. Moving them to a dedicated control node eliminates this contention from the compute node entirely.

The separation also provides operational resilience: the control node can continue monitoring, logging, and serving the admin dashboard even when the compute node is restarting after a crash.

---

## 3. Cinder Core — Server Engine

### 3.1 Tick Loop

`CinderTickLoop` is the server's primary thread. It drives the world at 20 TPS using a precision sleep loop:

```
┌─────────────────────────────────────────────────────────────────┐
│  CinderTickLoop (dedicated thread, CPU cores 1–3)               │
│                                                                  │
│  loop:                                                           │
│    tickStart = nanoTime()                                        │
│    runTick(N)   → PRE → WORLD → ENTITY → CHUNK → POST           │
│    elapsed = nanoTime() - tickStart                              │
│    profiler.recordTick(N, elapsed)                               │
│    sleepNs = nsPerTick - elapsed - driftAccumulator              │
│    if sleepNs > 0: LockSupport.parkNanos(sleepNs)                │
│    else: driftAccumulator += |sleepNs|   (carry overrun forward) │
└─────────────────────────────────────────────────────────────────┘
```

**Drift correction** is the key mechanism that prevents lag spirals. If tick N takes 52ms (2ms over the 50ms budget), the next tick's sleep is shortened by 2ms. This maintains long-run temporal accuracy without requiring wall-clock correction.

**Phase ordering** is fixed and intentional:

| Phase | Purpose | Key constraint |
|---|---|---|
| `PRE` | Drain sync task queue, fire delayed tasks | Must precede all world mutations |
| `WORLD` | Time, weather, environment, block ticks | Must precede entity updates |
| `ENTITY` | Entity update pipeline | Sees consistent world state |
| `CHUNK` | Chunk load/unload/save scheduling | Runs after entities may have triggered loads |
| `POST` | Network flush, metrics finalisation | Batches all state changes into minimum packets |

### 3.2 Entity Pipeline

`EntityUpdatePipeline` replaces flat entity iteration with a three-tier priority model:

```
ALL ENTITIES
     │
     ├── CRITICAL tier  ─────────────────────────── Update every tick, no budget cap
     │   (players, projectiles, vehicles)            Budget: 4ms advisory warning only
     │
     ├── STANDARD tier  ─────────────────────────── Update every tick, budget-enforced
     │   (mobs, animals, dropped items)              Budget: 10ms — tail entities deferred
     │   Uses ArrayDeque: O(1) re-queue of tail      to front of queue for next tick
     │
     └── DEFERRED tier  ─────────────────────────── Update every 4 ticks
         (distant, decorative, sleeping)             Budget: 3ms — slice-spread across ticks
         Uses ArrayList + offset pointer             No burst; smooth cost profile
```

**Async pre-computation** runs between ticks on a bounded `ForkJoinPool`:

```
Tick N completes
     │
     └── scheduleAsyncWork(N)
           │ submitAsync: pathfinding requests
           │ submitAsync: AI goal evaluation
           │ submitAsync: collision pre-checks
           ▼
        pendingAsyncResults queue (ConcurrentLinkedQueue)
                    │
         drainAsyncResults() called at start of ENTITY phase, Tick N+1
                    │
              Apply results synchronously on tick thread
```

### 3.3 Chunk Lifecycle

`ChunkLifecycleManager` maintains a strict async/sync boundary for all disk IO:

```
requestLoad(pos)
     │
     └── loadQueue (ArrayDeque, tick thread)
               │  processLoadQueue() — up to 2 loads/tick
               ▼
         ioExecutor.submit(asyncLoad)
               │
               │  [IO thread: disk read or world generation]
               │
               └── scheduler.submitSync("chunk-promote")
                         │  [PRE phase, tick thread]
                         ▼
                   chunkCache.put(pos, chunk)   ← LRU LinkedHashMap
                   chunk.onLoad()
```

**LRU eviction** is handled by `LinkedHashMap.removeEldestEntry()`. Evicted chunks with `holderCount > 0` are never removed. Dirty evictions trigger an async save before removal. The holder system uses a simple reference count — any system holding a chunk reference calls `addHolder(pos)` and `removeHolder(pos)` around its usage window.

### 3.4 Scheduler

`CinderScheduler` is the thread-safety boundary of Cinder Core:

```
Any external thread                    Tick thread
       │                                    │
       │ submitSync("label", task)           │
       ▼                                    │
ConcurrentLinkedQueue (lock-free)           │
       │                                    │
       │                          drainSyncQueue(tick)
       │                                    │
       └──────────────────────────► ArrayDeque (local buffer)
                                            │
                                     execute up to 4096 tasks
                                            │
                                    priority queue check
                                    (delayed/repeating tasks)
```

The `ConcurrentLinkedQueue` → local `ArrayDeque` transfer pattern is intentional. It avoids repeatedly polling the CAS-based lock-free queue in the drain hot path, instead doing one drain pass into the faster local deque.

---

## 4. Runtime Layer

The runtime layer sits between Cinder Core and the OS. It is entirely implemented in shell:

```
launch.sh
  │
  ├── Load preset (source presets/<name>.conf)
  ├── Validate environment (Java version, jar, RAM, disk device)
  ├── Assemble JVM flags (memory + GC + runtime + preset overrides)
  ├── Write last-launch.json (for Cinder Control)
  ├── Rotate server log
  ├── taskset --cpu-list 1-3 (pin JVM to non-OS cores)
  ├── nice -n -5 (elevate process priority)
  ├── ionice -c 1 -n 2 (best-effort IO class)
  └── Launch JVM
        │
        └── Watchdog restart loop (unless --no-watchdog)
              - max 5 restarts in 300s window
              - counter resets after stable period
              - exit 0 = clean stop, no restart
              - exit 3 = operator signal, no restart
```

**Preset system** — each `.conf` file is sourced by `launch.sh`. It sets `PRESET_HEAP_MIN`, `PRESET_HEAP_MAX`, `PRESET_GC_FLAGS`, `PRESET_EXTRA_FLAGS`, and a family of `CINDER_*` variables that are passed to the JVM as system properties. The JVM flags and runtime settings are completely decoupled: changing a preset requires only `systemctl set-environment CINDER_PRESET=event && systemctl restart cinder`.

---

## 5. Cinder OS — Distribution Layer

### 5.1 Image Layout

```
/
├── boot/firmware/          ← FAT32 partition (256MB)
│   ├── config.txt          ← Pi 4 boot config (gpu_mem=16, overlays disabled)
│   └── cmdline.txt         ← Kernel command line (UUID root, cgroup support)
│
├── etc/
│   ├── systemd/system/
│   │   ├── cinder.service             ← Primary server service
│   │   └── cinder-firstboot.service   ← One-shot first-boot provisioning
│   ├── ssh/sshd_config.d/
│   │   └── cinder-hardening.conf      ← SSH hardening (no root login)
│   └── fstab                          ← UUID-based, noatime, tmpfs for /tmp
│
└── opt/cinder/             ← All Cinder files (owned by cinder:cinder)
    ├── world/              ← World data (ReadWritePaths in systemd unit)
    ├── logs/               ← Server logs, launch metadata, import manifests
    ├── backups/            ← World backups and USB import backups
    ├── staging/            ← USB import staging area
    ├── plugins/            ← Plugin JARs
    ├── mods/               ← Mod JARs
    ├── config/             ← Server config files, import allowlist
    ├── cinder-runtime/
    │   ├── launch/launch.sh
    │   └── presets/*.conf
    └── scripts/
        ├── health-check.sh
        ├── metrics-display.sh
        ├── usb-import.sh
        ├── firstboot.sh
        └── prestart-check.sh
```

### 5.2 Storage Design

**ext4 without journaling** — the root filesystem is formatted with `^has_journal`. On a microSD card, ext4 journal writes are a significant source of write amplification. The trade-off is accepted: Cinder's systemd shutdown sequence is designed to complete a world save before power-off, and the watchdog ensures clean exits where possible.

**`noatime,nodiratime`** — access time updates are disabled on all Cinder data mounts. This eliminates a write-per-read on every chunk file access.

**`tmpfs` for `/tmp` and `/run`** — temporary files never reach the SD card.

### 5.3 systemd Service Design

`cinder.service` delegates all complexity to `launch.sh`:

```
systemd
  │  [Restart=on-failure, StartLimitBurst=5/600s]
  ▼
launch.sh --no-watchdog --preset ${CINDER_PRESET}
  │  [--no-watchdog: systemd is the outer restart loop]
  ▼
taskset nice ionice java [JVM_FLAGS] CinderServer
  │  [sd_notify READY=1 when tick loop starts]
  │  [sd_notify WATCHDOG=1 every 30s]
  ▼
systemd WatchdogSec=90s
  │  [kill + restart if no WATCHDOG ping within 90s]
  ▼
TimeoutStopSec=90s
  │  [SIGTERM → CinderServer shutdown hook → world save]
  │  [SIGKILL after 90s if not clean]
```

---

## 6. USB Import Flow

The USB import pipeline is the only external modification path that does not require SSH access. It is designed for operators in environments where network access to the Pi is restricted or unavailable.

```
Operator inserts USB drive
         │
         ▼
usb-import.sh
  │
  ├── DETECT: lsblk -o NAME,TRAN,RM,TYPE → find usb + part + removable
  │
  ├── MOUNT: mount -o ro,noexec,nosuid /dev/sdX /mnt/cinder-usb
  │           (read-only: USB cannot write to Pi filesystem during import)
  │
  ├── SCAN: find /mnt/cinder-usb/cinder-import/ -type f
  │
  ├── VALIDATE (per file):
  │     ├── Extension whitelist: .jar .zip .json .toml .yaml .yml .txt .png
  │     ├── Size limit: 100MB max
  │     ├── SHA-256 checksum vs checksums.sha256 on USB (if present)
  │     └── Hash allowlist check (if /opt/cinder/config/import-allowlist.sha256 exists)
  │         [ANY failure → abort entire import, zero server files touched]
  │
  ├── BACKUP: copy existing files to /opt/cinder/backups/usb-import/<import-id>/
  │           with manifest.txt recording pre-import state
  │
  ├── STAGE: copy validated files to /opt/cinder/staging/usb-import/<import-id>/
  │
  ├── DEPLOY: move from staging to final destinations:
  │     ├── plugins/   → /opt/cinder/plugins/
  │     ├── mods/      → /opt/cinder/mods/
  │     ├── datapacks/ → /opt/cinder/world/datapacks/
  │     └── config/    → /opt/cinder/config/
  │     [deploy failure → automatic rollback from BACKUP]
  │
  └── CLEANUP: unmount USB, remove staging, write final manifest
```

**Rollback** is available at any time after a completed import:

```bash
/opt/cinder/scripts/usb-import.sh --list-backups
/opt/cinder/scripts/usb-import.sh --rollback 20240315T143022Z
```

---

## 7. Monitoring and Diagnostics Architecture

Cinder's monitoring stack is built around one principle: the tick thread writes data, everything else reads it. There is no monitoring IPC, no metrics push, no external agent. The server log is the single source of truth.

```
CinderTickLoop
  │  [ENTITY phase completes]
  ▼
TickProfiler.endTick(snapshot)
  │
  ├── ring[writeHead % 1200] = snapshot    (ring buffer write, O(1))
  └── lastSnapshot.set(snapshot)           (volatile write for live readout)

Server log (cinder-server.log)
  │  [per-tick lines when CINDER_LOG_TICK_LINES=true]
  │  Format: [HH:MM:SS] tick=N total=X.XXms [PRE=X WORLD=X ENTITY=X CHUNK=X POST=X] [OK|OVERRUN|SPIKE]
  │
  ├── metrics-display.sh ─── tail + grep → sparkline, TPS estimate, phase breakdown
  ├── health-check.sh ────── grep last 100 lines → TPS, MSPT, spike count
  └── Cinder Bench ──────── full log parse → MSPT histogram, percentiles, phase analysis

last-launch.json
  │  [written by launch.sh on each start]
  └── metrics-display.sh, health-check.sh → preset, heap config display

/proc/<pid>/
  └── health-check.sh → RSS, uptime, stat
```

`TickProfiler.computeStats()` is callable from any thread at any time and returns `RollingStats` with TPS, MSPT percentiles (p50/p95/p99), per-phase means, and spike counts derived from the last 100 ring buffer entries. The computation is O(n log n) for the sort — acceptable at 1 Hz polling from Cinder Control.

---

## 8. Performance Engineering Decisions

These are the decisions that differ from a naive Minecraft server implementation and the reasons for each.

| Decision | Rationale |
|---|---|
| `LockSupport.parkNanos()` instead of `Thread.sleep()` | `Thread.sleep(1)` has a ~1–2ms floor on Linux. `parkNanos` uses the ARM64 CNTPCT_EL0 counter via vDSO for nanosecond-resolution sleep. |
| Entity tier deferral | Pi 4's entity budget at 20 TPS is roughly 10ms. Flat iteration of 300+ entities exceeds this. Deferral lets the server host more total entities than the per-tick budget would otherwise allow. |
| Async chunk IO with sync promote | microSD sequential read is ~20–60 MB/s. A synchronous chunk load can block the tick thread for 10–40ms. Async load eliminates this entirely. |
| `^has_journal` on ext4 | ext4 journaling on microSD causes write amplification of ~2–3x for small random writes. World saves are already crash-consistent via the save-then-rename pattern used by the chunk storage layer. |
| `taskset 1-3` CPU affinity | Reserves CPU 0 for the kernel, IRQ handlers, and Cinder Control services. Reduces tick thread preemption during network interrupt storms (Pi 4's GENET driver uses CPU 0 by default). |
| Fixed heap (`Xms==Xmx`) in benchmark preset | Heap growth events show up as MSPT spikes that are GC artefacts, not server behaviour. Fixing heap eliminates this noise from benchmark results. |
| SerialGC in low-power preset | G1GC's concurrent background threads consume 15–20% sustained CPU even at idle. SerialGC's stop-the-world model is quieter at low load; the longer individual pauses are acceptable at < 8 players. |
| `G1HeapRegionSize=4m` in survival preset | Pi 4's LPDDR4 has limited bandwidth. Large G1 regions increase the amount of memory scanned per GC cycle. 4MB regions keep individual GC operations small and predictable. |

---

## 9. Threading Model

```
Thread                        CPU affinity    Priority    Owned state
─────────────────────────────────────────────────────────────────────
cinder-tick                   cores 1–3       MAX-1       World, all entities, all chunks
cinder-entity-async-0         cores 1–3       NORM-1      (reads entity refs, writes pendingAsyncResults)
cinder-entity-async-1         cores 1–3       NORM-1      (same)
cinder-entity-async-2         cores 1–3       NORM-1      (same, extreme/event only)
cinder-chunk-io               cores 1–3       NORM-2      (reads/writes disk only)
cinder-scheduler-async-*      cores 1–3       NORM        (no world state access)
cinder-network-*              core 0 (OS)     NORM        (packet encode/decode only)
```

**The tick thread is the sole writer of world state.** All other threads are readers of immutable snapshots or writers to thread-safe queues that the tick thread drains. This eliminates the need for world-state locking at the cost of requiring all external mutations to go through `CinderScheduler.submitSync()`.

---

## 10. Data Flow Summary

```
Player connection
  │  [cinder-network thread]
  ▼
Packet decode
  │
  └── scheduler.submitSync("packet:PlayerAction", handler)
            │  [ConcurrentLinkedQueue]
            ▼
      PRE phase, tick thread
      drainSyncQueue() → handler.run()
            │
            ▼
      World state mutation (tick thread, safe)
            │
            ├── ENTITY phase: entity updates, async pre-compute scheduled
            ├── CHUNK phase:  chunk loads/saves triggered as needed
            └── POST phase:   outbound state delta → network flush → packets sent
```

Every player action enters the system through the scheduler, is processed on the tick thread during PRE, and its effects are flushed to the network during POST. The maximum latency between a player action and its world-state application is one tick (50ms at 20 TPS) plus network round-trip time. This is identical to standard Minecraft server behaviour and is the correct trade-off for a single-threaded world-state model.
