# Cinder Documentation

Cinder is a Raspberry Pi focused Minecraft hosting platform with two active tracks:

- A production OS/runtime track that ships a Debian ARM64 image and runs PaperMC.
- An experimental Java engine track in this repository (tick loop, chunk/entity systems, plugin API, benchmarking tools).

This documentation set is updated for the current stable baseline (`1.0.0`).

---

## Current Project State

- Runtime in shipped images: `PaperMC` (`/opt/cinder/server/paper-server.jar`)
- Service entrypoint: `cinder.service` -> `prestart-check.sh` -> `launch.sh`
- Build profiles: `desktop` (default) and `server`
- Image artifact: `cinder-os-<version>-arm64.img.zst`
- Bundle artifact: `cinder-os-<version>-arm64-bundle.iso`
- CI bundle floor: `2 GiB` (`--min-size-gib 2`)
- Release upload guard: files at or above GitHub's per-asset 2 GiB limit are skipped to avoid release-job failure

---

## Documentation Map

| Document | Purpose |
|---|---|
| `ARCHITECTURE.md` | End-to-end system architecture: first boot, service flow, data topology, release pipeline |
| `DISTRO.md` | Operator guide: build, flash, first boot, operations, updates, troubleshooting |
| `CONTRIBUTING.md` | Contributor workflow, coding standards, test expectations, PR checklist |
| `ROADMAP.md` | Post-`1.0.0` roadmap and delivery priorities |
| `PLUGIN_AUTHORING.md` | Authoring plugins for the `dev.cinder.plugin` API in this repository |
| `COMMUNITY_BENCHMARK_PROGRAM.md` | Standardized benchmark process for community submissions |

---

## Repository Tracks

### Production Track (Shipping Runtime)

- `os/` image build, firstboot, services, operational scripts
- `cinder-runtime/` launch logic and presets
- `cinder-control/` health checks and monitoring scripts

The production image boots into PaperMC by default.

### Experimental Track (Java Engine + Tooling)

- `server/`, `world/`, `entity/`, `chunk/`, `network/`, `profiling/`
- `plugin/` Cinder plugin API and loader
- `cinder-bench/` load and metrics harness

These modules are valuable for research, profiling, and future direction, but they are not the default runtime used by the shipping OS service.

---

## Quick Start References

Build image:

```bash
sudo ./os/build/build-image.sh --version 1.0.0 --profile desktop
```

Create release bundle from output artifacts:

```bash
./os/build/create-release-iso.sh --output-dir ./os/build/output --version 1.0.0 --min-size-gib 2
```

On-device service control:

```bash
sudo systemctl status cinder
sudo systemctl restart cinder
```

---

## Versioning and Release Notes

- Active CI workflow lives at `.github/workflows/ci.yml`.
- Stable stream is currently pinned to `1.0.0` in CI release flow.
- Release creation/upload is push-to-main only.
- Very large artifacts are preserved in CI artifacts; oversized files are filtered during GitHub Release upload.

---

For operational details, start with `DISTRO.md`.
