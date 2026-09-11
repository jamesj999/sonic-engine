# Native Load-Time Measurement Stream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans and TDD.

**Goal:** Capture native FIFO service opportunities, generate deterministic measured
manifests, and publish a validated optional estimator without changing replay authority.

## Tasks

- [x] Retain and cite the locked-ROM instruction-byte golden test for the sole permitted
  S&K-half callback at `ModuleChildSubmissionPc = 0x001B46`; register no new callbacks.
- [x] Extend `HardwareTimingEventEngine` with one run-wide measurement ledger and optional
  tooling-only writer. Reuse the permitted child callback and audited frame-end FIFO
  reconciliation for submission, activation, eligible service, retirement, reset epoch,
  raw frame, and monotonic sequence. Emit strict retirement aggregates with immutable
  hashes, service model, descriptor/features, parent identity, and within-frame ordering.
  Exclude censored top-level jobs and exact children whose
  lifetime intersects an unclassified service mode from runtime rows and estimator data.
  Leave `hardware_timing.jsonl` byte-identical.
- [x] Add C# unit tests for direct and module lifecycles, waiting exclusion, callbacks,
  same-frame ordering, reset, hashes, and diagnostic writer absence.
- [x] Add a measurement-only S3K headless capture mode. Keep the ledger
  run-wide across segment writers and publish `load_time_measurements.jsonl` to
  `target/load-time-measurements/` under the design's input-set no-replace hash.
- [x] Add Java `HardwareWorkFeatures` to production submissions and rewind snapshots;
  extract matching standard-Kos features from ROM-backed bytes in S3K queue owners.
- [x] Implement a strict Java diagnostic parser and deterministic manifest generator with
  lower-median aggregation, fixture dictionary, validation report, and byte stability.
- [x] Implement the finite feature/intercept/divisor estimator family, exact candidate
  ordering, checked arithmetic, whole-fingerprint/family folds, and validation gates;
  publish coefficients only when all gates pass.
- [x] Add shared synthetic C#/Java scanner vectors for terminators, descriptor refill,
  short/long/overlap copies, padding/alignment, final modules, malformed input, and
  overflow.
- [x] Add architecture guards excluding `com.openggf.tools.timing` and diagnostic filenames
  from trace replay/gameplay authority; trace fixture loaders explicitly reject the file.
- [x] Replay the five enumerated S3K BK2 files with the verified ROM, publish the nonempty
  S3K manifest, validation Markdown, and publication TSV at the exact design paths, then
  regenerate into a fresh target directory and compare bytes plus unique coverage with
  the 125 S3K and 436 cross-game provisional lower bounds.
- [x] Run headless C# tests, focused Java timing/S3K tests, full Java tests, and the
  repository integration workflow.
