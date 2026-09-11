# Trace v5 Consolidation Baseline

## Capture identity

- Captured on 2026-08-03 in repository worktree
  `.worktrees/trace-v5-consolidation`.
- Baseline commit: `2c7fb8fbbb65790e596a78f938fe38a7b0a346d8`
  (`bugfix/ai-trace-v5-consolidation`).
- Pre-task dirty state: only the reviewed, untracked consolidation design and
  plan. Their untracked-diff SHA-256 values were respectively
  `f397b51bdf48d3961689de8a4fe191a1e76f70929f435bf14f0dd80419f9e1aa`
  and `96a8cb0f085ac66d12a22dc94ce18b602b638dcbbc9f8b05b15d207719e73018`.
- Maven: 3.9.16 on OpenJDK 21.0.11 (Arch Linux).
- Mono: 6.12.0.182.
- BizHawk: the repository-local `docs/BizHawk-2.11-linux-x64/EmuHawk.exe`
  reports as a PE Mono/.NET assembly; maintained harness documentation pins
  BizHawk 2.11.
- Verified ROMs used for Java replay baseline:
  - S1 World REV01: CRC32 `AFE05EEE`, SHA-1
    `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`.
  - S2 World REV01: CRC32 `7B905383`, SHA-1
    `8bca5dcef1af3e00098666fd892dc1c2a76333f9`.
  - S3&K locked-on: CRC32 `63522553`, SHA-1
    `cfbf98c36c776677290a872547ac47c53d2761d6`.

## Fixture inventory

The original Task 1 inventory scanned all 217 `metadata.json` files and all seven
`run_manifest.json` files under `src/test/resources/traces`. Width is the
current `physics.csv` or `physics.csv.gz` header width. No fixture bytes were
changed while producing this inventory.

Task 8 merged `origin/develop` at `8211d923f`, which legitimately added the
104-file legacy production run
`s1/runs/s1-sonic-complete-withemeralds/`. Every added byte matches that
upstream tree, and the inventory verifier reported no change to any of the
original 809 files. The complete machine-readable
[per-path fixture inventory](2026-08-03-trace-v5-baseline-inventory.json) was
therefore re-frozen at 913 files: 251 metadata documents, eight run manifests,
and an aggregate SHA-256 of
`52ea19afea7250121c35a94927e3a4b950c6b00b8fac9570284401db3f0615bd`.
The original Task 1 aggregate was
`6885fbd0b0c5f02adf95ca33841c9755d2c81b46220f918d810394108b25da00`.
The upstream run is retained predecessor evidence and is part of Task 9's
complete native-v5 capture matrix; it is not normalized, deleted, or treated
as current-schema input before that capture.

The inventory freezes each file's kind, exact stored SHA-256, and logical
SHA-256 for every gzip file.
`tools/traces/trace_fixture_inventory.py` generates and verifies this exact
format for both the worktree and staged Git index; Task 8 must use it before
capture and immediately before fixture installation.

The following table records the original Task 1 migration baseline; the
upstream run is accounted for separately above so its provenance remains
explicit.

| Game | Profile | Width | Count |
| --- | --- | ---: | ---: |
| s1 | missing | 11 | 1 |
| s1 | missing | 20 | 8 |
| s1 | missing | 22 | 1 |
| s1 | missing | 42 | 23 |
| s1 | s1_special_stage | 14 | 2 |
| s2 | missing | 22 | 1 |
| s2 | gameplay_unlock | 42 | 34 |
| s2 | level_gated_reset_aware | 42 | 18 |
| s2 | s2_special_stage | 48 | 11 |
| s3k | missing | 22 | 1 |
| s3k | aiz_end_to_end | 42 | 1 |
| s3k | complete_run | 42 | 74 |
| s3k | level_gated_reset_aware | 42 | 2 |
| s3k | s3k_bonus_stage | 42 | 22 |
| s3k | s3k_special_stage | 20 | 18 |

The eight S1 20-column rows are exactly `credits_00_ghz1` through
`credits_07_ghz1b`; they are retained migration inputs, not deletion targets.

| Version/provenance field | Values and counts |
| --- | --- |
| `trace_schema` | absent: 22; 3: 3; 5: 23; 6: 3; 7: 114; 9: 2; 10: 50 |
| `csv_version` | absent: 33; 4: 10; 7: 174 |
| `ss_csv_version` | absent: 186; 1: 31 |
| `hardware_timing_schema` | absent: 103; 2: 114 |
| `recorder` | absent: 217 |
| `recorder_version` | absent: 217 |
| `run_schema` manifests | 1: 4; 2: 3 |

## Java baseline

The known shared `target/test-tmp` cold extraction race makes parallel forks
unsuitable, so both Java commands use `-Dsurefire.forkCount=1`.

```text
mvn -Dmse=off -Dsurefire.forkCount=1 test
```

The pre-change worktree report inventory recorded 13,935 tests, 25 failures,
225 errors, and 35 skipped. The dominant error is an environment-wide GLFW
initialisation cascade rooted at
`GlfwKeyNameResolver$Holder` after unavailable LWJGL/GLFW extraction; it
affects unrelated tests after its first occurrence and is baseline-only. The
remaining assertion failures include hardware-boundary ordering,
display-aspect defaults, zone-event runtime access, rewind PLC state,
GHZ boss child pruning, S2 special-stage cadence, and CNZ boss PLC timing.
None is attributable to this read-only tooling task.

```text
mvn -Dmse=off -Dsurefire.forkCount=1 -Dtest=*TraceReplay \
  -Dsonic1.rom.path="$REPO_ROOT/Sonic The Hedgehog (W) (REV01) [!].gen" \
  -Dsonic2.rom.path="$REPO_ROOT/Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
  -Ds3k.rom.path="$REPO_ROOT/Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

This replay sweep recorded 35 tests, 0 failures, 11 errors, and 0 skipped.
All eleven errors are pre-existing S3K timing/replay failures, primarily
`unsupported-row-POST` where a recorded `KOS_MODULE_QUEUE` completion lands
on a VBLANK-only row. The affected classes are AIZ, CNZ, Gumball, HCZ (two),
ICZ, LBZ, MGZ complete, MGZ, MHZ, and Pachinko. The validator neither loads
Java fixtures nor changes these results.

## Expected-red v5 validator checklist

The command below intentionally exits 1 against the current legacy fleet:

```text
python3 tools/traces/validate_trace_v5.py src/test/resources/traces
```

It enumerated 233 exact failing paths and 1,257 independent diagnostics. The
full, exact-path diagnostics are reproducible from that command; the frozen
reason inventory is the migration checklist:

| Diagnostics | Count |
| --- | ---: |
| forbidden `lua_script_version` | 224 |
| missing native recorder | 224 |
| missing native recorder version | 224 |
| non-v5 trace schema | 201 |
| forbidden `csv_version` | 184 |
| forbidden `hardware_timing_schema` | 114 |
| missing trace profile | 35 |
| forbidden `ss_csv_version` | 31 |
| alternate `*_retro` sidecars | 8 |
| forbidden `run_schema` | 7 |
| missing required manifest gap array | 4 |
| noncanonical timing ordering | 1 |

The eight sidecars are confined to the two legacy S1 full-run directories:
`s1/ghz1_fullrun` and `s1/mz1_fullrun`. The required eight credits directories
are present and individually diagnosed for migration; this validator does not
rewrite, delete, or otherwise alter any fixture.
