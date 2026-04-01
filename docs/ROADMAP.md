# Cinder Roadmap

This document describes Cinder's development trajectory in phases. Each phase has a stated goal, a list of concrete deliverables, and a definition of done. Phases are sequential within a track but the OS track and Core track develop in parallel where dependencies allow.

This is an engineering roadmap, not a marketing roadmap. Dates are not listed. Phases are listed. A phase is complete when its deliverables are done and its success criteria are met — not before.

---

## Phase Overview

```
Phase 0 — Foundation         ████████████████████  complete
Phase 1 — Core Engine        ████████████████████  complete
Phase 2 — Network Layer      ████████████████████  complete
Phase 3 — World Engine       ████████████████████  complete
Phase 4 — Distro Image       ████████████████████  complete
Phase 5 — Runtime Tuning     ████████████████████  complete
Phase 6 — Monitoring         ████████████████████  complete
Phase 7 — Benchmarking       ████████████████████  complete
Phase 8 — Plugin System      ████████████████████  complete
Phase 9 — Public Testing     ████████████████░░░░  in progress
```

---

## Phase 0 — Foundation

**Status:** Complete

**Goal:** Establish the project structure, engineering principles, and architectural skeleton. Every subsequent phase should be able to develop without revisiting foundational decisions.

### Deliverables

- [x] Repository structure (`cinder-core`, `cinder-runtime`, `cinder-control`, `cinder-bench`, `os`, `docs`, `scripts`, `branding`)
- [x] `CinderTickLoop` — precision tick loop with drift correction, phase ordering, profiling hooks
- [x] `CinderScheduler` — sync/async task bridge with delayed and repeating task support
- [x] `EntityUpdatePipeline` — three-tier entity update system with async pre-computation
- [x] `ChunkLifecycleManager` — async IO, LRU cache, holder system, sync promote
- [x] `TickProfiler` + `TickSnapshot` — ring buffer, rolling statistics, structured log output
- [x] `launch.sh` — production launch script with preset loading, JVM assembly, watchdog
- [x] All five runtime presets (`survival`, `event`, `low-power`, `benchmark`, `extreme`)
- [x] `cinder.service` — systemd unit with watchdog integration and security hardening
- [x] `usb-import.sh` — validated USB import pipeline with staging, backup, and rollback
- [x] `packages.list` — minimal Debian ARM64 package manifest
- [x] `build-image.sh` — full Debian ARM64 image assembly pipeline
- [x] `health-check.sh` — multi-layer health monitoring with JSON/brief/human output modes
- [x] `metrics-display.sh` — live TPS/MSPT terminal dashboard with sparkline graphs
- [x] `README.md`, `ARCHITECTURE.md` — primary project documentation

### Success Criteria

- The tick loop, scheduler, entity pipeline, chunk manager, and profiler compile and pass unit tests.
- The launch script starts the JVM with the correct JVM flags for each preset.
- The OS image builds without errors on an x86_64 host.
- The USB import pipeline validates, stages, deploys, and rolls back correctly.
- `health-check.sh` correctly reports OK/WARN/FAIL for simulated conditions.

---

## Phase 1 — Core Engine Completion

**Status:** Complete

**Goal:** Complete the stub implementations in Cinder Core so the server can start, accept a connection, load a world, and run ticks with real world state.

### Deliverables

- [x] `CinderEntity` — base entity class with tick contract, tier management, holder count, snapshot interface
- [x] `CinderChunk` — chunk data model with block storage, snapshot serialisation, `onLoad`/`onUnload` lifecycle
- [x] `ChunkPosition` — immutable value type for chunk coordinates (used throughout Core)
- [x] `CinderWorld` — world state container: time, weather, loaded chunk map, entity registry
- [x] `CinderServer` — main class: wires together tick loop, scheduler, entity pipeline, chunk manager, profiler; handles startup and shutdown
- [x] `CinderWatchdogNotifier` — calls `sd_notify(WATCHDOG=1)` every 30 seconds from a background thread; calls `sd_notify(READY=1)` when the tick loop enters steady state
- [x] `ChunkStorage` implementation — flat binary format optimised for Pi 4 sequential SD read; initial Anvil/MCa reader for world import compatibility
- [x] `prestart-check.sh` — run by `ExecStartPre` in `cinder.service`: verifies disk space, Java version, sets CPU governor for benchmark/extreme presets
- [x] Unit tests for all Phase 0 and Phase 1 core classes

### Success Criteria

- `CinderServer` starts, loads a test world, runs for 60 seconds at 20 TPS with zero entities and zero players.
- `TickProfiler` reports mean MSPT < 5ms for an empty world on Pi 4.
- `CinderWatchdogNotifier` satisfies systemd's `WatchdogSec=90s` requirement without false kills.
- `health-check.sh` reports `PROCESS/server_pid OK` and `RUNTIME/tps OK` for a running empty server.

---

## Phase 2 — Network Layer

**Status:** Complete

**Goal:** Implement the player connection pipeline so real clients can connect, authenticate, and receive basic world state.

### Deliverables

- [x] `CinderNetworkManager` — connection acceptor, per-connection packet pipeline, connection lifecycle management
- [x] `CinderConnection` — per-player connection state: read buffer, write queue, protocol state machine
- [x] Packet codec framework — binary encode/decode for the subset of Minecraft protocol packets required for basic play
- [x] `PlayerEntity` — extends `CinderEntity`, CRITICAL tier; holds connection reference, position, view distance tracking
- [x] Chunk view distance management — load/unload chunks as player moves; uses `ChunkLifecycleManager.requestChunkWithCallback`
- [x] POST-phase network flush — batch all outbound state deltas into minimum packet windows per connection
- [x] Proxy-friendly architecture — `HAProxy` PROXY protocol v2 support for control-node proxying
- [x] Connection rate limiting — protect against connection floods on Pi 4's single network interface

### Success Criteria

- A vanilla Minecraft client connects to Cinder and receives a valid world.
- Player movement updates propagate to other connected players within one tick.
- `health-check.sh` `NETWORK/player_port OK` reports correctly for an active server.
- Network phase MSPT contribution < 2ms with 10 connected players on Pi 4.

---

## Phase 3 — World Engine

**Status:** Complete

**Goal:** Implement world simulation: block ticks, random ticks, fluid updates, time/weather, and basic entity AI.

### Deliverables

- [x] Block tick scheduler — per-chunk tick budget, configurable random tick rate
- [x] Fluid update system — water and lava propagation with async pre-computation
- [x] Time and weather state machine — day/night cycle, rain/thunder transitions
- [x] Basic mob AI — goal evaluator framework, pathfinding integration with async entity pre-computation
- [x] `MobEntity` — extends `CinderEntity`, STANDARD tier; integrates with AI goal evaluator
- [x] Passive entity — `AnimalEntity`, `VillagerEntity` stubs with basic wander/interact behaviour
- [x] Item entity — dropped item lifecycle with pickup detection
- [x] World generation stub — flat world generator for testing; Anvil world reader for production use
- [x] Spawn control — configurable mob caps per chunk region; respects entity tier system

### Success Criteria

- A world with 200 active mobs sustains > 18 TPS on Pi 4 under the survival preset.
- Block tick rate matches vanilla Minecraft behaviour for a representative test world.
- Entity MSPT phase does not exceed 15ms with 200 mobs at view distance 8 on Pi 4.
- Pathfinding requests process within 1 tick latency (async pre-compute + sync apply).

---

## Phase 4 — Distro Image

**Status:** Complete

See Phase 0. The Cinder OS image build pipeline (`build-image.sh`, `packages.list`, `cinder.service`, first-boot provisioning) is fully implemented. Phase 4 in subsequent development refers to image hardening, update channel integration, and SD card wear monitoring.

### Remaining work

- [x] `cinder-update.sh` — pull and apply Cinder Core updates from a signed release channel
- [x] SD card wear estimation — use `smartmontools` SMART data and erase count estimation for eMMC/SD
- [x] OTA image update path — in-place OS update without full re-flash for minor releases
- [x] `cinder-backup.sh` — automated world backup with configurable retention, compression, and optional remote push (rsync over SSH to control node or NAS)

---

## Phase 5 — Runtime Tuning

**Status:** Complete

All five presets are implemented and documented. Phase 5 in subsequent development refers to adaptive tuning and runtime reconfiguration.

### Remaining work

- [x] Runtime preset hot-swap — change preset settings without server restart for non-JVM parameters (entity radii, chunk cache size, save interval)
- [x] Adaptive TPS governor — detect sustained MSPT elevation and automatically shed load (reduce simulation distance, increase deferred interval) with operator notification
- [x] Per-session performance report — written to `logs/session-<date>.json` on clean shutdown: session duration, mean/p99 MSPT, peak player count, total ticks, GC event count

---

## Phase 6 — Monitoring

**Status:** Complete

`health-check.sh` and `metrics-display.sh` are fully implemented. Phase 6 remaining work covers persistent metrics storage and multi-node aggregation.

### Remaining work

- [x] `cinder-metrics-collector.sh` — cron-driven metrics capture to a time-series log file (one JSON line per minute)
- [x] `cinder-report.sh` — weekly performance report: TPS histogram, MSPT trend, spike frequency, storage growth
- [x] Control-node metrics aggregation — pull metrics from compute node over SSH; display combined view in `metrics-display.sh`
- [x] `cinder-alert.sh` — configurable alert thresholds with notification via local log, email, or webhook (for operators who want external paging)

---

## Phase 7 — Benchmarking

**Status:** Complete

**Goal:** Implement Cinder Bench — a controlled load simulation and measurement pipeline that can characterise Pi 4 performance objectively and reproduce results across hardware variants.

### Deliverables

- [x] `cinder-bench/load/entity-stress.sh` — spawn N entities of a given tier and measure steady-state MSPT
- [x] `cinder-bench/load/chunk-stress.sh` — simulate player movement patterns to drive chunk load/unload cycles; measure IO-induced MSPT spikes
- [x] `cinder-bench/load/connection-stress.sh` — simulate M players connecting, moving, and disconnecting; measure network phase overhead
- [x] `cinder-bench/metrics/mspt-histogram.sh` — parse a profiler log file and produce a MSPT frequency distribution (min, mean, p50, p95, p99, max, spike rate)
- [x] `cinder-bench/metrics/phase-analysis.sh` — extract per-phase MSPT data; identify which phase is the binding constraint at a given load level
- [x] `cinder-bench/metrics/startup-time.sh` — measure time from JVM start to first tick (useful for SD vs SSD comparison)
- [x] `CinderBenchRunner.java` — Java-side harness that drives warmup → steady-state → cooldown phases and signals the shell metrics tools at phase boundaries
- [x] Benchmark result schema — standardised JSON output format for cross-run comparison
- [x] Reference benchmark results — Pi 4 8GB baseline numbers for survival preset at 0, 10, 20 players under controlled conditions

### Success Criteria

- Running `cinder-bench` produces a reproducible MSPT result with < 5% variance across three runs under identical conditions (performance governor, < 65°C, no background services).
- Results are stored in a structured format that can be diffed between code versions.
- The entity stress benchmark confirms the 200-entity / 18 TPS claim from Phase 3 success criteria.

---

## Phase 8 — Plugin System

**Status:** Complete

**Goal:** Define and implement Cinder's plugin API — the sanctioned extension point for server operators who need behaviour beyond what Cinder Core provides natively.

### Scope constraints

Cinder's plugin system is intentionally narrower than Bukkit/Paper. It does not aim for broad API compatibility. It aims for:

1. A stable, versioned event model that lets plugins react to game events without accessing world state directly.
2. A command registration API that routes commands through `CinderScheduler.submitSync`.
3. A configuration API for plugin-specific settings.
4. Plugin lifecycle management: load, enable, disable, reload without server restart.

### Deliverables

- [x] `CinderPlugin` interface — lifecycle hooks (`onEnable`, `onDisable`, `onReload`)
- [x] `CinderEventBus` — async-safe event dispatch; listeners are always called on the tick thread
- [x] Core event types: `PlayerJoinEvent`, `PlayerLeaveEvent`, `EntitySpawnEvent`, `ChunkLoadEvent`, `TickEvent`
- [x] Command API — register commands that execute as sync tasks during PRE phase
- [x] Plugin loader — scan `plugins/` on startup; load JARs that implement `CinderPlugin`
- [x] Plugin isolation — each plugin runs in its own classloader; a plugin crash does not bring down the server
- [x] USB import allowlist integration — plugin JARs must be in the import allowlist before `usb-import.sh` will deploy them
- [x] Developer documentation — plugin authoring guide with examples

### Success Criteria

- A minimal "hello world" plugin can be written, built, deployed via USB import, and executed without restarting the server.
- A plugin that throws an unhandled exception during an event handler is caught, logged, and the plugin is disabled — the server continues ticking.
- Plugin event overhead < 0.5ms per tick at 10 registered event listeners.

---

## Phase 9 — Public Testing

**Status:** In progress

**Goal:** Release Cinder OS images and Cinder Core builds for community testing. Establish a feedback and issue reporting process.

### Deliverables

- [ ] Public GitHub repository with full source
- [x] GitHub Actions CI pipeline: build Cinder Core (ARM64), run unit tests, produce release artefacts
- [ ] Signed release images hosted with SHA-256 checksums
- [x] Issue templates: bug report (with required hardware/preset/log sections), performance report (with required benchmark results), feature request
- [x] `CONTRIBUTING.md` — full contributor guide (see `docs/CONTRIBUTING.md`)
- [x] `DISTRO.md` — operator guide for the Cinder OS image (see `docs/DISTRO.md`)
- [x] Community benchmark program — standardised test procedure for operators to submit Pi 4 performance results
- [ ] Discord or Matrix server for community support

### Success Criteria

- At least three independent operators run Cinder on Pi 4 hardware and report results.
- The benchmark program produces comparable results across operator submissions.
- The public issue tracker contains no critical unresolved bugs in core engine or OS layer.
- Cinder Core holds 18+ TPS with 20 players on a Pi 4 8GB under the survival preset, confirmed by at least two independent operator reports.

---

## Not On The Roadmap

The following are explicitly out of scope for Cinder and will not be added:

- **x86 / x86_64 support** — Cinder's OS and JVM tuning is ARM64-specific. Supporting x86 would require a parallel tuning track that dilutes focus.
- **Broad Bukkit/Spigot API compatibility** — Cinder's plugin API is intentionally narrower. Operators who need Bukkit compatibility should use Paper.
- **Multi-world support** — Cinder targets single-world deployments on Pi 4 hardware. Multi-world multiplies chunk cache requirements in ways that are not viable within the Pi 4's memory budget.
- **Vanilla parity** — Cinder is not a re-implementation of vanilla Minecraft. It is a hosting platform. Some vanilla behaviours that are incompatible with Pi 4 performance targets will not be implemented.
- **Web dashboard** — a browser-based admin UI is not planned. `metrics-display.sh` and `health-check.sh` are the monitoring interfaces. A web dashboard adds runtime overhead and attack surface.
