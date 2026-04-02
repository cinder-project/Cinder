# Contributing to Cinder

Cinder accepts contributions across OS/runtime tooling, Java modules, benchmarking, and documentation.

This guide defines the baseline expected before review.

---

## 1. Prerequisites

Required:

- Java 21
- Gradle 8.11+ available on PATH
- Git
- Bash + ShellCheck for script work

Notes:

- The repository currently does not include a committed Gradle wrapper.
- Use your local `gradle` installation for build/test commands.

---

## 2. Project Layout (Current)

```text
chunk/                  Java chunk lifecycle code
entity/                 Java entity pipeline code
network/                Java network layer code
plugin/                 Plugin API and loader
profiling/              Tick profiling types
server/                 Java server wiring
world/                  Java world state code

cinder-runtime/         launch scripts + runtime presets
cinder-control/         monitoring/health/report tooling
cinder-bench/           benchmark wrappers and metrics scripts
os/                     image build, firstboot, services, operational scripts
docs/                   project documentation
test/                   Java tests
```

---

## 3. What to Prioritize

High-value contribution areas:

- CI/release reliability for OS artifacts
- First-boot, update, import, and backup safety
- Operator observability and diagnostics
- Benchmark reproducibility and schema quality
- Documentation accuracy and drift reduction

Before major runtime or architecture changes, open an issue describing:

1. Problem statement
2. Proposed approach
3. Risk and rollback plan

---

## 4. Coding Standards

### 4.1 Java

- Keep Java 21 compatible code.
- Prefer explicit, readable code in hot paths.
- Avoid adding blocking operations in performance-sensitive paths.
- Add/adjust tests under `test/` for behavior changes.

### 4.2 Shell

Every script should keep:

```bash
#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
```

Run ShellCheck for modified scripts and fix findings unless a suppression is justified inline.

---

## 5. Local Validation

Minimum expected checks for most PRs:

```bash
gradle test --no-daemon
```

For workflow/script-only changes, include:

- static validation (lint/diagnostics)
- reasoned dry-run notes where execution is environment-limited

For docs-only changes:

- verify commands/paths/options match current scripts
- ensure cross-document consistency (do not update one doc and leave others contradictory)

---

## 6. Documentation Change Policy

If your PR changes behavior, update docs in the same PR.

Examples:

- Runtime path changes -> update `DISTRO.md` and `ARCHITECTURE.md`
- Release behavior changes -> update `README.md` and relevant operator notes
- API changes -> update `PLUGIN_AUTHORING.md`

PRs that alter behavior but leave docs stale may be rejected.

---

## 7. Commit and PR Format

Use conventional-style commit messages, for example:

- `fix(ci): skip oversized release assets at GitHub limit`
- `docs(distro): align first-boot and runtime commands`
- `feat(os): add desktop profile package manifest wiring`

PR description should include:

1. What changed
2. Why it changed
3. How it was validated
4. Operational impact or migration notes (if any)

---

## 8. Review Expectations

Review is primarily about:

- correctness and safety
- operational impact on Raspberry Pi targets
- regression risk
- clarity of docs and rollout guidance

For release/CI changes, include enough detail that maintainers can reason about failure modes.

---

## 9. Out-of-Scope Changes (Without Prior Discussion)

- Broad platform expansion beyond Pi-focused ARM64 deployment
- Breaking operational defaults without migration documentation
- Large dependency additions without clear need
- Behavioral changes without tests or validation evidence

---

## 10. Security and Safety Notes

- Never commit secrets, tokens, private keys, or credentials.
- Treat first-boot and update scripts as high-impact code paths.
- For any potentially destructive operation, document rollback steps in PR notes.
