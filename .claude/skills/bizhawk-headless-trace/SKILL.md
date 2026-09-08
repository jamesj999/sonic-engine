---
name: bizhawk-headless-trace
description: Record and publish Sonic 1, 2, or 3&K trace fixtures from a BizHawk BK2 movie using the native headless harness.
---

# BizHawk headless trace

Use TraceChaser's native GPGX harness for canonical BK2 capture. Initialize the
optional `tools/tracechaser` submodule if needed, then read its
`bizhawk/README.md` and the applicable game behavior document under
`bizhawk-headless/docs/` for current capture options.

## Capture identity and invocation

Discover the actual ROM filename and verify its game/hash using root `AGENTS.md`.
Use official BizHawk **2.11**, not 2.11.1 or a later version. The default location
is `tools/tracechaser/.dependencies/BizHawk-2.11-linux-x64`; `BIZHAWK_HOME` may name
another absolute installation directory. The Linux harness runs through Mono
without a display and publishes an all-or-nothing output directory.

Preserve the BK2 filename: `source_bk2` must name that file exactly. Set absolute
ROM/movie/output paths; the output must not already exist. Durable capture work
belongs in an explicit task directory outside the repository.

```bash
tools/tracechaser/bizhawk-headless/run.sh \
  --mode trace --rom "$ROM_PATH" --movie "$MOVIE" --output "$OUTPUT"
```

Use the game's documented profile. For multi-stage movies, `--trace-profile
complete_run` records level segments, adding a manifest when a stage detour
requires it; `--run-id <truthful-id>` names a run and preserves its manifest,
stage/bonus segments, and transitions. Treat different movies, including ordinary
and emerald routes, as distinct fixtures. Do not inject gameplay state or a fake
movie length to force completion.

For queue/DPLC investigations, enable documented `--load-queue-state` capture
and verify `load_queue_state_per_frame` and, when applicable,
`dynamic_art_transfer_state_per_frame` in metadata. A metadata flag cannot
substitute for the corresponding observations. Diagnostic probes are opt-in
when the current investigation needs evidence absent from normal capture.

## Validate and publish

`trace_schema: 5` owns metadata, rows, hardware timing, and manifests. Recorder
provenance is opaque; it does not select replay behavior. When changing a
recorder, establish correctness from ROM semantics and meaningful native tests
before using new output as evidence. Generated expectations from the same capture
do not independently validate the recorder.

Inspect the complete output inventory, ROM identity, source BK2 names, manifest
segments/offsets, row/event counts, capabilities, and timing streams. Compare
against the previous fixture where present and explain material deltas. Record
hashes and sizes so the reviewed output can be identified. Follow the user's
publication scope and existing authorization; do not invent an extra approval
step for already-authorized fixture replacement.

Publish the BK2, manifest, metadata, sidecars, and every segment together under
`src/test/resources/traces/<game>/runs/<run-id>/` for named runs. Install capture
output without hand-tuning observations. Payload compression is the deliberate
exception: never commit plain `physics*.csv` or `aux_state*.jsonl`. Use the
native compression output or deterministic gzip:

```bash
gzip -9 -n -c physics.csv > physics.csv.gz
gzip -9 -n -c aux_state.jsonl > aux_state.jsonl.gz
```

Run relevant native capture tests, Java fixture-load/schema/compression/reference
guards, and applicable replay tests with absolute ROM paths. Report skips and
first-error frame/field, including pre-existing failures. Use
`trace-replay-bug-fixing` for parity interpretation and append frontier evidence
when required by root guidance. Keep original capture evidence until publication
and verification succeed.
