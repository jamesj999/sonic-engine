# Trace v5 Capture and Publication

Trace v5 is the only supported fixture contract. Every metadata file and run
manifest declares:

```json
{
  "recorder": "native-bizhawk-headless",
  "recorder_version": "3.0",
  "trace_schema": 5
}
```

`recorder` identifies the producer and `recorder_version` identifies the
native implementation. Both are opaque provenance; neither selects parsing or
replay. `lua_script_version` was removed, not renamed. V5 also removes
`csv_version`, `ss_csv_version`, `hardware_timing_schema`, and `run_schema`.

## Capture matrix

All production capture uses `tools/tracechaser/bizhawk-headless/run.sh --mode trace` with a
verified user-supplied ROM and a destination that does not exist.

| Family | Input and selector | Output shape |
|---|---|---|
| S1 standard | BK2 + `--trace-profile <profile>` | one level fixture |
| S1 complete/named run | BK2 + `--trace-profile complete_run` or `--run-id <id>` | segments; named runs include a manifest |
| S1 credits demos | no BK2; `--trace-profile credits_demo --credits-target all` | all eight ROM-owned credits fixtures atomically |
| S2 standard/level select | BK2 + profile; optional `--gameplay-segment <n>` | one selected gameplay segment |
| S2 complete/named run | BK2 + `--trace-profile complete_run` or `--run-id <id>` | segments and optional manifest |
| S3K standard | BK2 + `--trace-profile aiz_end_to_end`, `level_gated_reset_aware`, or `gameplay_unlock` | one fixture |
| S3K complete/named run | BK2 + `--trace-profile complete_run` or `--run-id <id>` | level, bonus, and special-stage segments; optional manifest |

Ordinary level rows have 42 columns. Special stages have fixed game/profile
widths: S1 14, S2 48, and S3K 20. Presence of
`hardware_timing.jsonl` enables the one module-plus-direct grammar. Every run
manifest has `dynamic_art_gap_transitions`, including an empty array.

## Candidate validation and comparison

Capture the complete fleet to scratch. Do not overlay or copy it into
`src/test/resources/traces`.

```bash
python3 tools/tracechaser/traces/validate_trace_v5.py /scratch/v5-candidate/traces

python3 tools/tracechaser/traces/compare_trace_v5_candidates.py \
  src/test/resources/traces /scratch/v5-candidate/traces \
  --mode credits-20-to-42 \
  --output /scratch/v5-candidate-report.json
```

The comparator is read-only. It inventories both roots, reports stored and
logical SHA-256 values, decompresses payloads for logical comparison, compares
physics columns by header name, classifies added/removed columns and aux event
types, and preserves literal metadata, manifest, timing, and payload deltas.
The predecessor scan describes old keys and widths but does not make them valid
v5. For two v5 captures use the default `v5-literal` mode and
`--fail-on-difference` as a determinism gate.

The one-time S1 credits migration deliberately uses the explicit
`credits-20-to-42` mode. It reports every common-field mismatch and every new
column. The legacy constant-zero `v_framecount` defect and all physical deltas
must remain visible.

## Credits raw-host evidence

The evidence artifact must live outside the candidate root and use format
`openggf-s1-credits-raw-host-evidence-v1`. Each route names its logical physics
payload and SHA-256. Each disclosed predecessor first-divergence supplies the
row, common field, raw and emitted values, plus either a RAM address and
endianness or a documented derivation.

```json
{
  "format": "openggf-s1-credits-raw-host-evidence-v1",
  "routes": [{
    "route": "credits_00_ghz1",
    "candidate_payload": "s1/00_ghz1_credits_demo_1/physics.csv",
    "candidate_logical_sha256": "<64 lowercase hex characters>",
    "observations": [{
      "row": 0,
      "common_field": "v_framecount",
      "ram_address": "0xFFFFFE04",
      "endianness": "big",
      "raw_value": "0001",
      "emitted_value": "0001"
    }]
  }]
}
```

Verify it against both the candidate and comparison report:

```bash
python3 tools/tracechaser/traces/verify_s1_credits_raw_host_evidence.py \
  src/test/resources/traces \
  /scratch/v5-candidate/traces \
  /scratch/v5-candidate-report.json \
  /scratch/s1-credits-raw-observations.jsonl \
  /scratch/s1-credits-raw-host-evidence.json
```

RAM addresses use canonical `0x` plus eight uppercase hexadecimal digits and
require `big`, `little`, or `byte` endianness. A derivation must be non-empty.
The verifier rejects missing/extra first-divergence evidence, malformed
provenance, raw/emitted/CSV value disagreement, report candidate-root,
inventory, or file-hash drift, candidate hash drift, and an evidence artifact
placed inside the candidate root.

## Candidate-root Java replay

Replay tests may read a complete scratch fleet without installing it:

```bash
mvn -Dmse=off \
  -Dopenggf.trace.fixtureRoot=/scratch/v5-candidate/traces \
  -Dtest='*TraceReplay' test
```

The property is confined to test fixture-path resolution. It is absent from
default configuration, never changes runtime/gameplay state, and only maps a
path below `src/test/resources/traces` to the same relative path below the
scratch root.

## Exact-byte publication

1. Establish recorder correctness with ROM/disassembly semantics, tests, and
   independent review.
2. Capture the complete fleet twice from the frozen source/artifact and prove
   deterministic logical payloads and inventories.
3. Freeze literal inventories, stored/logical hashes, row/event counts, and
   every classified delta. Verify the credits raw-host artifact.
4. Obtain explicit user approval for those exact bytes and disclosed deltas.
5. Install only the approved frozen candidate, byte-for-byte and atomically.
   Never delete an S1 credits fixture, replay class, or consumer; only approved
   obsolete `*_retro` sidecars may be removed.
6. Re-run native gates, validator/comparator, fixture guards, all eight credits
   replays, the full trace fleet, and the three-ROM Java suite.

Recorded timing remains subject to hard rule 4 throughout: it may delay the
readiness of matching production work and nothing else.
