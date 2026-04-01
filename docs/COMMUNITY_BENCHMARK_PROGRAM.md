# Community Benchmark Program

This program standardizes operator-submitted benchmark results so measurements can be compared across devices and software versions.

## Scope

- Supported hardware: Raspberry Pi 4 variants (4GB and 8GB)
- Required preset: survival (plus optional benchmark preset runs)
- Required metrics: mean MSPT, p95 MSPT, p99 MSPT, TPS

## Submission Checklist

1. Use performance governor during benchmark window.
2. Record temperature before and after run.
3. Run warmup/steady/cooldown benchmark phases.
4. Capture result JSON using scripts in [cinder-bench](../cinder-bench).
5. Submit via Performance Report issue template.

## Required Attachment Fields

- Hardware model and RAM
- Cooling solution
- Storage type (SD, SSD, NVMe over USB)
- Preset and any overridden settings
- Benchmark result JSON

## Reproducibility Guidance

- Keep firmware and Java versions in report notes.
- Avoid background workloads during benchmark windows.
- Run each scenario three times and include variance.
