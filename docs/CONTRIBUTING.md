# Contributing to Cinder

Cinder is an engineering project. Contributions are welcome — but Cinder has a higher bar for what gets merged than most hobby projects. This document explains what that bar is and how to clear it.

Read this document before opening a PR. A PR that ignores these guidelines will be closed without review.

---

## Contents

1. [What Contributions Are Welcome](#1-what-contributions-are-welcome)
2. [Development Prerequisites](#2-development-prerequisites)
3. [Project Layout](#3-project-layout)
4. [Java Code Standards](#4-java-code-standards)
5. [Shell Script Standards](#5-shell-script-standards)
6. [Testing Requirements](#6-testing-requirements)
7. [Performance Profiling Requirements](#7-performance-profiling-requirements)
8. [Pull Request Process](#8-pull-request-process)
9. [Commit Message Format](#9-commit-message-format)
10. [What Will Not Be Merged](#10-what-will-not-be-merged)

---

## 1. What Contributions Are Welcome

**High priority (actively needed):**
- Phase 1 completions: `CinderEntity`, `CinderChunk`, `ChunkPosition`, `CinderWorld`, `CinderServer`, `CinderWatchdogNotifier`
- `ChunkStorage` implementations (flat binary format, Anvil reader)
- Unit tests for existing Phase 0 classes
- `prestart-check.sh`
- Cinder Bench load simulation tools

**Medium priority (welcome with good design):**
- Phase 2 network layer components
- Phase 3 world simulation components
- `cinder-backup.sh`
- Runtime metrics collection scripts
- SD card wear monitoring integration

**Lower priority (discuss first):**
- Plugin system design — opens an API that must be maintained; discuss in an issue before implementing
- New runtime presets — must come with benchmark data from real Pi 4 hardware
- OS image modifications — must not increase installed package count without documented justification

**Not welcome:**
- See [Section 10](#10-what-will-not-be-merged).

---

## 2. Development Prerequisites

### For Java (Cinder Core)

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 (ARM64 or x86_64) | `apt install openjdk-21-jdk` |
| Gradle | 8.x | Wrapper included: `./gradlew` |
| Git | 2.x | — |

Optional but recommended:
- IntelliJ IDEA (Community Edition) — best ARM64 Java support
- `perf` (Linux) — for profiling JVM hot paths

### For Shell scripts

| Tool | Version | Notes |
|---|---|---|
| bash | 5.x | Scripts use associative arrays (bash 4.3+) |
| shellcheck | 0.9+ | `apt install shellcheck` — mandatory before PR |
| shfmt | 3.x | `apt install shfmt` — for formatting check |

### For OS / distro work

| Tool | Version | Notes |
|---|---|---|
| debootstrap | Any | `apt install debootstrap` |
| qemu-user-static | Any | For cross-build on x86 host |
| parted, kpartx | Any | Loop device management |

### Target hardware (for performance testing)

All performance claims in PRs must be measured on actual Raspberry Pi 4 hardware. Virtualised or emulated ARM64 is not acceptable for performance PRs. If you do not have Pi 4 hardware, mark the PR as `needs-hardware-validation` and note what was tested on.

---

## 3. Project Layout

```
cinder/
├── cinder-core/            Java server engine (Gradle project)
│   └── src/main/java/
│       ├── dev/cinder/server/      Tick loop, scheduler, server entry point
│       ├── dev/cinder/world/       World state, time, weather
│       ├── dev/cinder/entity/      Entity pipeline and base types
│       ├── dev/cinder/chunk/       Chunk lifecycle, storage, types
│       ├── dev/cinder/network/     Connection management, packet codec
│       └── dev/cinder/profiling/   Tick profiler, snapshot, statistics
├── cinder-runtime/         Launch scripts and tuning presets
│   ├── launch/             launch.sh
│   └── presets/            *.conf tuning presets
├── cinder-control/         Monitoring and control scripts
│   ├── services/           health-check.sh and related
│   └── monitoring/         metrics-display.sh and related
├── cinder-bench/           Benchmarking tools
│   ├── load/               Load simulation scripts
│   └── metrics/            Metric collection and analysis
├── os/                     OS build and configuration
│   ├── build/              build-image.sh, packages.list
│   ├── services/           systemd unit files
│   └── usb-import/         usb-import.sh
├── docs/                   Project documentation
└── scripts/                Shared utility scripts
```

New files must go in the correct directory. If a file doesn't fit the existing structure, discuss it in the issue before creating it.

---

## 4. Java Code Standards

### Formatting

- 4-space indentation. No tabs.
- Lines ≤ 120 characters.
- One blank line between methods.
- No trailing whitespace.
- `import` statements: no wildcards. Group: java.*, javax.*, then third-party, then `dev.cinder.*`.

### Naming

| Element | Convention | Example |
|---|---|---|
| Classes | `UpperCamelCase` | `ChunkLifecycleManager` |
| Methods | `lowerCamelCase` | `requestChunkWithCallback` |
| Constants | `UPPER_SNAKE_CASE` | `BUDGET_STANDARD_NS` |
| Local variables | `lowerCamelCase` | `elapsedNs` |
| Packages | `lower.dot.separated` | `dev.cinder.chunk` |

### Documentation

Every public class and every public method must have a Javadoc comment that explains:
- **What it does** (not just what its name says)
- **Thread safety** — who can call this and from which thread
- **Performance notes** — if the method has allocation or latency characteristics the caller needs to know about
- **ARM64 / Pi 4 notes** — if there are hardware-specific considerations

Javadoc is not optional for public API. Reviewers will reject PRs with undocumented public methods.

### Performance rules

These rules apply to all code that runs in the tick path (anything called from `CinderTickLoop.runTick()`):

1. **No allocation in the steady state.** The tick loop must not allocate heap objects that did not exist before the server started. Use object pools, pre-allocated buffers, and reusable builder patterns. The `TickSnapshot.Builder` pattern is the reference implementation.

2. **No blocking IO on the tick thread.** If a method might touch disk, network, or any blocking resource, it must be dispatched through `CinderScheduler.submitAsync()` or the chunk IO executor.

3. **No locks held across tick phase boundaries.** If you need synchronisation between the tick thread and an external thread, use the scheduler's sync task queue, `volatile` reads, or `AtomicReference`. Do not introduce `synchronized` blocks or `ReentrantLock` in tick-path code.

4. **Explain every `System.nanoTime()` call.** Timing calls have a cost (~5–10ns on ARM64 vDSO). Profiling instrumentation is welcome in designated profiling hooks. Ad-hoc timing calls in hot paths require justification.

5. **Prefer `long[]` and `int[]` over object arrays in hot-path data structures.** ARM64 cache line width is 64 bytes; primitive arrays are cache-friendly; object arrays are not.

### Style notes

- Use `record` types for immutable data carriers (`TickSnapshot`, `ChunkManagerStats`, etc.).
- Use `sealed` interfaces where a closed set of subtypes is the design intent.
- Use `var` only when the type is obvious from the right-hand side. Never `var` for return types.
- Prefer explicit `for` loops over streams in tick-path code. Streams allocate lambda capture objects.
- `final` on local variables is encouraged where the value does not change.

---

## 5. Shell Script Standards

### Mandatory header

Every script must start with:

```bash
#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
```

`set -euo pipefail` is not optional. Reviewers will reject scripts without it.

### ShellCheck

Run `shellcheck` on every script before committing:

```bash
shellcheck scripts/my-script.sh
```

A PR with ShellCheck errors will not be merged. Disable individual rules only with an inline comment explaining why:

```bash
# shellcheck disable=SC2206  # Word splitting intentional: building array from preset string
```

### Logging

Every script must use a consistent logging pattern:

```bash
log()  { echo "[$(date -u +%H:%M:%S)] [script-name] $*"; }
warn() { echo "[$(date -u +%H:%M:%S)] [script-name:WARN] $*" >&2; }
die()  { echo "[$(date -u +%H:%M:%S)] [script-name:FATAL] $*" >&2; exit 1; }
```

Timestamps must be UTC. Log output must go to stdout (for capture); warnings and errors to stderr.

### Error handling

- Use `die()` for unrecoverable errors; never `exit 1` bare.
- Use `trap cleanup EXIT` for resource cleanup (mounts, temp files, loop devices).
- Every external command that might fail must be checked, either by `set -e` or explicit `|| die "..."`.
- Never use `2>/dev/null` to silently discard errors that indicate real problems.

### ARM64 / Debian compatibility

- Do not use bash 5.1+ features without checking that Debian bookworm's bash version supports them.
- `awk`, `sed`, `grep` must use POSIX syntax unless GNU extensions are explicitly required (and documented).
- `stat -c` is GNU stat; verify it is present before using (it is in Cinder OS but not on macOS).
- `readarray` / `mapfile` require bash 4; they are available on Debian bookworm.

---

## 6. Testing Requirements

### Java unit tests

All Phase 0 and Phase 1 Java classes must have unit tests. Use JUnit 5.

Coverage expectations by class type:

| Class type | Minimum test coverage |
|---|---|
| Core engine (tick loop, scheduler, pipeline) | All public methods; all documented edge cases |
| Data types (TickSnapshot, ChunkPosition) | All accessors; serialisation round-trips |
| Utility / stats (TickProfiler, RollingStats) | Correctness of rolling window logic; ring buffer wraparound |
| Stub / placeholder implementations | At least a smoke test confirming the stub compiles and runs |

Tests must run on both x86_64 (CI) and ARM64 (Pi 4). Do not use `Thread.sleep()` in tests; use `TickSnapshot.Builder` and synthetic timestamps where timing is needed.

### Shell script tests

Scripts with non-trivial logic must include a `--dry-run` or `--test` mode that exercises the validation and logic paths without touching real system resources. `usb-import.sh --dry-run` is the reference implementation.

For scripts that interact with the filesystem, write integration tests that operate on a temporary directory and verify the expected files are created, modified, or left untouched.

---

## 7. Performance Profiling Requirements

Any PR that modifies code in the tick path must include performance evidence. "It seems faster" is not evidence.

### Minimum evidence for tick-path changes

1. **Benchmark preset run:** start Cinder with the `benchmark` preset, run for 60 seconds of steady-state ticks, capture the MSPT histogram using `cinder-bench/metrics/mspt-histogram.sh`. Include results for the PR branch and the base branch.

2. **JVM GC log:** capture GC activity during the benchmark run: `-Xlog:gc*:file=gc.log`. Attach the GC log or a summary showing pause count and total pause time.

3. **Hardware specification:** include the Pi 4 model, RAM, storage type (SD/USB SSD), cooling solution, CPU governor, and measured temperature during the run.

### Format for performance results

Include a table like this in the PR description:

```
Hardware: Pi 4 8GB, USB3 NVMe SSD, ICE Tower cooler, performance governor, 55°C peak
Preset:   benchmark (Xms2g Xmx2g, G1GC)
Duration: 60s steady state, 0 players, 0 entities

               Base branch    PR branch    Delta
Mean MSPT:     4.21ms         3.87ms       -8.1%
P95 MSPT:      6.44ms         5.92ms       -8.1%
P99 MSPT:      9.11ms         8.34ms       -8.5%
Max MSPT:      18.2ms         14.7ms       -19.2%
GC pauses:     12 (total 87ms) 11 (total 79ms)
Spike count:   0              0
```

PRs that improve code clarity but have no measurable performance impact do not need this evidence. State that explicitly in the PR description.

### Profiling tools

For identifying hot paths in the tick loop:

```bash
# Attach async-profiler to the running JVM (ARM64 supported)
# Download: https://github.com/async-profiler/async-profiler
./profiler.sh -e cpu -d 30 -f profile.html $(cat /opt/cinder/logs/cinder.pid)
```

For system-level profiling (CPU cycles, cache misses) on Pi 4:

```bash
# ARM64 Cortex-A72 PMU events
perf stat -e cycles,instructions,cache-misses,cache-references \
    -p $(cat /opt/cinder/logs/cinder.pid) -- sleep 30
```

---

## 8. Pull Request Process

### Before opening a PR

1. Run `shellcheck` on all modified shell scripts.
2. Run `./gradlew test` on Cinder Core. All tests must pass.
3. If modifying tick-path Java code, include benchmark results (Section 7).
4. Rebase onto `main`. Cinder does not accept merge commits.
5. Write a PR description that explains what changed and why — not just what.

### PR description template

```
## What
Brief description of the change.

## Why
The problem this solves, or the improvement this makes.

## How
Technical summary of the approach. Reference relevant architecture sections if applicable.

## Testing
How this was tested. Include hardware spec if performance-relevant.

## Performance impact
[If applicable] Benchmark results table (see CONTRIBUTING.md Section 7).
[If not applicable] State: "No tick-path changes; no benchmark required."

## Breaking changes
[If any] List any interface changes, config format changes, or behaviour changes.
```

### Review process

- PRs need one approval from a maintainer before merge.
- Maintainers will review: correctness, code style, test coverage, documentation, and (for tick-path changes) performance evidence.
- Expect review within 7 days for open-scope items. Niche hardware-specific PRs may take longer.
- Address all review comments before requesting re-review. Partially-addressed PRs will not be merged.

### Merge policy

Cinder uses squash merge for small PRs (< 5 commits, single logical change) and merge commits for PRs that represent a distinct phase or feature. The committer decides which applies.

---

## 9. Commit Message Format

Use the conventional commits format:

```
<type>(<scope>): <short summary>

[optional body]

[optional footer: breaking changes, issue references]
```

Types:
- `feat` — new feature or capability
- `fix` — bug fix
- `perf` — performance improvement (must include benchmark evidence in PR)
- `refactor` — code change with no behaviour change
- `docs` — documentation only
- `test` — test additions or corrections
- `chore` — build, CI, tooling changes
- `os` — OS/distro layer changes

Scopes: `core`, `runtime`, `control`, `bench`, `os`, `docs`, `usb-import`, `build`

Examples:
```
feat(core): add CinderEntity base class with tier management
perf(core): reduce EntityUpdatePipeline allocation in standard tier hot path
fix(os): handle exFAT USB drives in usb-import.sh auto-detection
docs(arch): add Phase 2 network layer data flow diagram
```

---

## 10. What Will Not Be Merged

The following will be rejected without extended discussion:

- **Code without documentation.** Undocumented public methods in `cinder-core` will be rejected.
- **Tick-path changes without benchmark evidence.** If it touches `CinderTickLoop`, `EntityUpdatePipeline`, `ChunkLifecycleManager`, or `CinderScheduler`, it needs numbers.
- **New packages without justification.** Additions to `packages.list` require a documented reason for why the package is necessary. "Might be useful" is not a reason.
- **Platform expansions.** x86 support, Windows support, Docker images, ARM32 support. Cinder is ARM64 Pi 4.
- **Broad Bukkit API compatibility layers.** This fundamentally conflicts with Cinder's tick-path ownership model.
- **PRs that revert documented performance decisions without equivalent evidence.** If `SerialGC` in `low-power.conf` is replaced with G1GC, benchmark data from a passively-cooled Pi 4 must support the change.
- **Shell scripts without `set -euo pipefail`.** Non-negotiable.
- **Shell scripts that fail ShellCheck.** Non-negotiable.
- **Whitespace-only formatting PRs** that are not paired with substantive changes.
- **PRs opened against an issue that was not discussed first** for changes to plugin API, OS image composition, or preset defaults.
