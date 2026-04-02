# Community Benchmark Program

This program standardizes operator-submitted benchmark runs so results can be compared across Raspberry Pi deployments.

It is designed for the current Cinder baseline:

- Cinder OS v1.0.0 image + runtime tooling
- PaperMC production runtime
- Cinder benchmark scripts under `cinder-bench/`

---

## 1. Hardware Scope

Supported target devices:

- Raspberry Pi 4 (4GB or 8GB)
- Raspberry Pi 400

Storage classes to report explicitly:

- microSD
- USB SATA SSD
- USB NVMe

---

## 2. Required Run Conditions

For a submission to be accepted:

1. Use a fixed runtime preset for each run (`survival` required; `benchmark` optional).
2. Keep cooling and power conditions stable for all repeated runs.
3. Record thermal and throttling state before and after each run.
4. Execute each scenario at least 3 times and report variance.
5. Keep background workload low and note any non-default services.

---

## 3. Required Scenarios

Run these wrappers from `cinder-bench/load/`:

- `entity-stress.sh`
- `chunk-stress.sh`
- `connection-stress.sh`

Each wrapper delegates to `run-load-benchmark.sh` and outputs a JSON result.

Example:

```bash
mkdir -p cinder-bench/results/community

./cinder-bench/load/entity-stress.sh \
  --entities 200 \
  --tier STANDARD \
  --log /opt/cinder/logs/cinder-server.log \
  --steady-sec 120 \
  --output cinder-bench/results/community/entity-stress-run1.json

./cinder-bench/load/chunk-stress.sh \
  --players 10 \
  --pattern spiral \
  --log /opt/cinder/logs/cinder-server.log \
  --steady-sec 120 \
  --output cinder-bench/results/community/chunk-stress-run1.json

./cinder-bench/load/connection-stress.sh \
  --connections 20 \
  --move-profile default \
  --log /opt/cinder/logs/cinder-server.log \
  --steady-sec 120 \
  --output cinder-bench/results/community/connection-stress-run1.json
```

---

## 4. Required Submission Fields

Include these fields with every benchmark package:

- Device model and RAM
- Cooling solution
- Storage type
- PSU rating and cable details
- Preset name and overrides
- Ambient temperature (if known)
- Firmware version
- Java version
- Cinder image/runtime version
- Raw JSON result files

Recommended extra attachments:

- `vcgencmd get_throttled` before/after run
- `vcgencmd measure_temp` sample series
- `journalctl -u cinder -n 200` snippet for run window

---

## 5. Quality Gate

A submission is considered comparable when:

- All required scenarios are present
- Each scenario has 3 repeated runs
- Reported variance is less than 10% for mean MSPT
- No thermal throttling occurred during steady-state windows

If variance is higher, submit anyway but mark it as exploratory.

---

## 6. Result Interpretation

Primary metrics to compare:

- `meanMspt`
- `p95Mspt`
- `p99Mspt`
- `maxMspt`
- `overrunCount` (>= 50ms)
- `spikeCount` (>= 100ms)

Lower is better for MSPT and counts.

---

## 7. Submission Path

Submit benchmark packages through the project issue tracker (performance report template) or an equivalent maintainer-requested channel.

When posting results, include:

1. A short summary table
2. Links to raw JSON files
3. Notes on anything unusual during the run

---

## 8. Maintainer Baseline Reference

Reference schema and baseline examples are stored in:

- `cinder-bench/schema/benchmark-result.schema.json`
- `cinder-bench/results/`

Use them to validate format consistency before submitting.