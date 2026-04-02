# Cinder Roadmap

This roadmap reflects the project after the `1.0.0` stable milestone.

The goal is to keep the shipping Raspberry Pi distro reliable while continuing targeted improvements to operations, observability, and ecosystem tooling.

---

## Baseline (Completed)

Status: Done

- Stable PaperMC runtime wiring in Cinder OS
- Image build pipeline with desktop and server profiles
- First-boot provisioning and system hardening
- Backup, update, USB import, and monitoring script set
- CI build + release flow for OS artifacts

---

## Stream 1: Release Hardening (`1.0.x`)

Status: In progress

Focus:

- Keep release pipeline resilient to platform constraints
- Improve release notes and artifact discoverability
- Keep checksum and upload paths deterministic

Planned work:

- [ ] Publish explicit large-artifact guidance in release notes
- [ ] Add release summary artifact that lists skipped oversized files
- [ ] Add automated validation for release-asset naming consistency

---

## Stream 2: Operator Experience (`1.1`)

Status: Planned

Focus:

- Make setup and maintenance less error-prone
- Improve desktop profile usability without regressing server profile behavior

Planned work:

- [ ] First-boot UX pass for key/credential setup flow
- [ ] Better guardrails for SSH lockout scenarios
- [ ] Improve online import and desktop launcher prompts
- [ ] Add compact runbook output command for on-device support triage

---

## Stream 3: Observability and Reporting (`1.1`/`1.2`)

Status: Planned

Focus:

- Better diagnostics for long-running nodes
- More consistent machine-readable reporting

Planned work:

- [ ] Standardize health/report JSON schemas
- [ ] Add stronger correlation between launch metadata and health snapshots
- [ ] Improve aggregate metrics pipeline for multi-node setups

---

## Stream 4: Update and Backup Safety (`1.2`)

Status: Planned

Focus:

- Minimize operator risk during updates and restores

Planned work:

- [ ] Add checksum verification mode to update script path
- [ ] Add backup integrity verification command
- [ ] Add guided rollback helper for failed runtime updates

---

## Stream 5: Java Track and Plugin API

Status: Active but non-default runtime track

Focus:

- Keep the experimental Java engine and plugin API maintainable
- Preserve clear boundaries from shipping PaperMC runtime behavior

Planned work:

- [ ] Expand plugin API docs and examples
- [ ] Add more targeted tests in plugin loader/event bus areas
- [ ] Publish compatibility matrix for API changes by tag

---

## Stream 6: Community Benchmarking

Status: Active

Focus:

- Build a comparable performance dataset across Pi hardware/storage/cooling combinations

Planned work:

- [ ] Increase number of accepted community benchmark submissions
- [ ] Publish periodic benchmark digest from submitted result bundles
- [ ] Track variance and reproducibility metrics over time

---

## Out of Scope (for current roadmap horizon)

- x86/x64 distro support
- Windows-native deployment track
- Mandatory Bukkit compatibility layer for core runtime
- Any change that breaks stable Pi-focused operations without a migration plan
