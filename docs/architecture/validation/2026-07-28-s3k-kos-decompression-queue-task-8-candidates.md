# S3K direct Kosinski queue Task 8 publication candidates

Date: 2026-07-28

## Status

Task 8 pre-approval is complete. Three schema-2 candidates were captured with
the reviewed native recorder into harness scratch and were not installed:

- `s3k/aiz1_to_hcz_fullrun`;
- `s3k/aiz_completerun`; and
- `s3k/icz_completerun`.

This report is the only committed Task 8 deliverable. Nothing under
`src/test/resources/traces/` was modified. Publication, post-installation
guards, after-publication frontier measurement, integration, merge, and push
remain blocked on explicit approval of the exact bytes below.

There is no prior approval for these schema-2 candidate bytes. The requested
approval is a new, narrowly scoped authorization to replace the three named
schema-1 fixture directories. It does not supersede the prior recorder code
review or the historical approval of the currently committed schema-1
fixtures.

## Candidate selection

`TestCommittedHardwareTimingFixtures` and the Task 6 checked inventory identify
exactly three schema-1 routes which reach a gameplay consumer of
`Kos_decomp_queue_count`:

| Candidate | Consumer | Native identity |
|---|---|---|
| `aiz1_to_hcz_fullrun` | AIZ intro | STANDARD `aiz_end_to_end`, offset 511, 20,798 rows |
| `aiz_completerun` | AIZ intro | COMPLETE-RUN segment 0, offset 941, 26,228 rows |
| `icz_completerun` | ICZ1-to-ICZ2 transition | COMPLETE-RUN segment 4, offset 138,117, 25,393 rows |

The multi-bonus AIZ segment begins at camera X `$1300`, after the intro
consumer, and is excluded. The complete-run capture also produced HCZ, MGZ,
and CNZ scratch segments needed to preserve the run-wide timing ledgers through
ICZ; they are not publication candidates.

## Capture provenance

Candidates were captured at:

```text
branch: bugfix/ai-s3k-kos-decompression-queue
commit: fbfee310cd3f12596cf1f0abcd8bdb0032a5ff0c
worktree: /home/farrell/code/projects/OpenGGF/.worktrees/ai-s3k-kos-decompression-queue
BizHawk: 2.11.0.0
Mono: 6.12.0
Maven: 3.9.16
Java: 21.0.11
```

The worktree had no tracked changes before capture. Its only pre-existing
untracked entries were local disassembly directories. Scratch capacity was
1.1 TiB. The native harness built successfully with `xbuild`.

The discovered ROM identities were:

| Game | File | CRC32 | SHA-1 |
|---|---|---|---|
| S1 REV01 | `Sonic The Hedgehog (W) (REV01) [!].gen` | `AFE05EEE` | `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
| S2 REV01 | `Sonic The Hedgehog 2 (W) (REV01) [!].gen` | `7B905383` | `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| S3K locked-on | `Sonic and Knuckles & Sonic 3 (W) [!].gen` | `63522553` | `CFBF98C36C776677290A872547AC47C53D2761D6` |

Only the verified S3K ROM was consumed by these captures. Movie provenance is:

| Movie | Bytes | SHA-256 |
|---|---:|---|
| `aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2` | 9,133 | `6837de0f67db7eb68f20b6f6df6a2872713a613d8b4dbc804847209c16b56e97` |
| `_movies/s3k-complete-sonic-tails.bk2` | 192,715 | `82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf` |

All output-affecting unmodeled `OGGF_*` variables were unset. The capture
commands were:

```bash
BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  env \
  -u OGGF_TRACE_ENABLE_DIAGNOSTIC_HOOKS \
  -u OGGF_S3K_CNZ_EVENT_RAM_RANGE \
  -u OGGF_S3K_RNG_CALL_RANGE \
  -u OGGF_S3K_AIZ_FIRE_RANGE \
  -u OGGF_S3K_AIZ_WALL_SENSOR_RANGE \
  -u OGGF_S3K_CRL_RANGE \
  -u OGGF_S3K_CNZ_CYLINDER_RANGE \
  -u OGGF_S3K_AIZ_HANDOFF_TERRAIN_FRAME_START \
  -u OGGF_S3K_AIZ_HANDOFF_TERRAIN_FRAME_END \
  -u OGGF_TRACE_STOP_FRAME \
  -u OGGF_BK2_FRAME_COUNT \
  tools/bizhawk-headless/run.sh \
  --mode trace \
  --rom "/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  --movie "/home/farrell/code/projects/OpenGGF/.worktrees/ai-s3k-kos-decompression-queue/src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2" \
  --output "/home/farrell/code/projects/OpenGGF/.worktrees/ai-s3k-kos-decompression-queue/tools/bizhawk-headless/.scratch/task8-candidates/aiz-standard" \
  --trace-profile aiz_end_to_end

BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  env \
  -u OGGF_TRACE_ENABLE_DIAGNOSTIC_HOOKS \
  -u OGGF_S3K_CNZ_EVENT_RAM_RANGE \
  -u OGGF_S3K_RNG_CALL_RANGE \
  -u OGGF_S3K_AIZ_FIRE_RANGE \
  -u OGGF_S3K_AIZ_WALL_SENSOR_RANGE \
  -u OGGF_S3K_CRL_RANGE \
  -u OGGF_S3K_CNZ_CYLINDER_RANGE \
  -u OGGF_S3K_AIZ_HANDOFF_TERRAIN_FRAME_START \
  -u OGGF_S3K_AIZ_HANDOFF_TERRAIN_FRAME_END \
  -u OGGF_TRACE_STOP_FRAME \
  -u OGGF_BK2_FRAME_COUNT \
  tools/bizhawk-headless/run.sh \
  --mode trace \
  --rom "/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  --movie "/home/farrell/code/projects/OpenGGF/.worktrees/ai-s3k-kos-decompression-queue/src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2" \
  --output "/home/farrell/code/projects/OpenGGF/.worktrees/ai-s3k-kos-decompression-queue/tools/bizhawk-headless/.scratch/task8-candidates/complete-through-icz" \
  --trace-profile complete_run \
  --effective-movie-length 163511
```

The first command reported offset 511 and 20,798 rows. The second reported
five segments and zero transitions:

| Scratch token | Offset | Rows |
|---|---:|---:|
| `aiz` | 941 | 26,228 |
| `hcz` | 27,170 | 31,482 |
| `mgz` | 58,653 | 39,398 |
| `cnz` | 98,052 | 40,064 |
| `icz` | 138,117 | 25,393 |

The STANDARD output contains exactly the four fixture files. The COMPLETE-RUN
root contains no files and exactly the five directories above; each segment
contains exactly the four fixture files. Neither capture contains a
`run_manifest.json`, so all three candidates are standalone fixture
directories, not run-manifest-owned segments.

## Frozen candidate bytes

For compressed payloads, the first length and digest identify the exact
prospective installed gzip container. The plaintext length and digest identify
its verified decompression.

| Candidate | File | Candidate bytes | Candidate SHA-256 | Plaintext bytes | Plaintext SHA-256 |
|---|---|---:|---|---:|---|
| `aiz1_to_hcz_fullrun` | `physics.csv.gz` | 605,006 | `b5591fe7f274b78f72dfbae83eb8d975c172cd393fe5707582f11efa4ea20c77` | 3,369,910 | `3c219725d85d64762b514f973263edced337a37cd16fb8bf50f2b0ac3b5a2a39` |
|  | `aux_state.jsonl.gz` | 5,846,635 | `0c7ac4514b9e6c1750a340aeb034660b0a954ba666977aab9dea9da18e19e137` | 127,656,342 | `9d90d669de5b9fc0c00666ad2023a164d1d110d441b9bcc8403280d1a5d74b47` |
|  | `hardware_timing.jsonl` | 17,478 | `d9dfb065ba364b7e8e1843e4debdcfd50e37205017478b12391408d0f72421ba` | — | — |
|  | `metadata.json` | 1,273 | `64fd828e0fe16debdb22766d65b7230463fe84e49a1e483c3f93690b55be2571` | — | — |
| `aiz_completerun` | `physics.csv.gz` | 818,157 | `1b60c8120b60aaf815e42123a58307e1b088201da6d6767c9703e40d16a51ce8` | 4,249,570 | `2f8d3d0c2f5a4b3f30b7784ed28fa37071951f6d8d538f08573b4631fa33f872` |
|  | `aux_state.jsonl.gz` | 7,986,812 | `b98e40eb2847ca24a83ee4be6e19a39e304a38e207f206069577ed7502aa4549` | 172,380,688 | `d55efb44c7fadc022591c56054964e002c8ade868867a8965a0efbe820f2d210` |
|  | `hardware_timing.jsonl` | 19,729 | `22ca5e34396a20f4c8b45b0c2afbf72f8f282f26da4542f3d35a75d8289e996d` | — | — |
|  | `metadata.json` | 1,608 | `26889c0529b31975200d0d8f9e33152fe047906238dcf2eca3dc7af560c02570` | — | — |
| `icz_completerun` | `physics.csv.gz` | 848,470 | `49a9d4c90dfa5e392d25fc694ebf489e372b64b8f0b30bd8c63e5cf2d1cbdbf4` | 4,114,300 | `386cf6e8e62b61c8cd03c252668db47d3511fc1fd6c43399830e6655086d0c99` |
|  | `aux_state.jsonl.gz` | 8,232,022 | `aef33fbefc2164807cb8ea66fcafa416105a154a084dd2d52dd4e9887910ec7b` | 173,735,703 | `0e21af4b895ab47ceca79ea74301208bd8ab1a44899cab921c566d75608efaa9` |
|  | `hardware_timing.jsonl` | 10,226 | `a1db1f2825b4672a2f5789e981cce17f2575548847f5b82aa47f7ecc6faf6405` | — | — |
|  | `metadata.json` | 1,611 | `81a7e5ca76d6284b8ac356772bcffd1a83caccf96b8a439acdadb396122df4a1` | — | — |

A second STANDARD capture to
`.scratch/task8-candidates/aiz-standard-repro` reproduced all four candidate
files byte-for-byte, including both gzip containers.

## Metadata, row, and event inventory

All three candidates retain trace schema 7 and CSV version 7. STANDARD is
`6.38-s3k`; COMPLETE-RUN is `6.38-s3k-completerun`. All three declare hardware
timing schema 2.

| Candidate | Physics lines | Declared rows | Aux events | Aux frame range | Hardware events |
|---|---:|---:|---:|---|---:|
| `aiz1_to_hcz_fullrun` | 20,799 | 20,798 | 598,934 | `-1..20797` | 79 |
| `aiz_completerun` | 26,229 | 26,228 | 835,313 | `-1..26227` | 89 |
| `icz_completerun` | 25,394 | 25,393 | 851,401 | `-1..25392` | 46 |

The complete aux event inventories are:

| Event | AIZ STANDARD | AIZ complete | ICZ complete |
|---|---:|---:|---:|
| `air_countdown_state` | 41,596 | 52,456 | 50,786 |
| `aiz_fire_transition` | 401 | 0 | 0 |
| `aiz_handoff_terrain_state` | 9 | 9 | 0 |
| `checkpoint` | 6 | 0 | 0 |
| `control_lock_state` | 2,160 | 3,273 | 3,196 |
| `cpu_state` | 20,798 | 26,228 | 25,393 |
| `cpu_state_snapshot` | 1 | 1 | 1 |
| `game_paused_state` | 0 | 26,228 | 25,393 |
| `interact_state` | 41,496 | 52,336 | 50,670 |
| `mode_change` | 502 | 747 | 876 |
| `object_appeared` | 3,638 | 4,551 | 7,329 |
| `object_near` | 199,830 | 277,783 | 289,285 |
| `object_removed` | 2,390 | 3,051 | 4,506 |
| `object_state` | 242,402 | 333,312 | 338,502 |
| `oscillation_state` | 20,798 | 26,228 | 25,393 |
| `player_mode_set` | 1 | 1 | 1 |
| `routine_change` | 6 | 14 | 16 |
| `sidekick_interact_object` | 20,698 | 26,108 | 25,277 |
| `slot_dump` | 1,593 | 2,174 | 3,954 |
| `state_snapshot` | 591 | 797 | 819 |
| `terrain_wall_sensor` | 12 | 12 | 0 |
| `zone_act_state` | 6 | 4 | 4 |

The plaintext payload digests are identical to the committed fixtures, so
this inventory is unchanged in full rather than sampled.

## Hardware timing ledger

The candidate event streams pass a mechanical global ordering check using
`VINT_SERVICE < PRE_MAIN_LOOP < POST_OBJECTS` within equal raw frames. Every
direct event is at `PRE_MAIN_LOOP`, and ordinals are contiguous independently
within each kind. No selected segment happens to contain a direct and module
event on the same raw frame.

| Candidate | Kind/boundary | Count | Ordinals | Raw frames | Unique fingerprints |
|---|---|---:|---|---|---:|
| `aiz1_to_hcz_fullrun` | direct/PRE | 41 | `8..48` | `358..20794` | 32 |
|  | module/POST | 38 | `2..39` | `361..20797` | 24 |
| `aiz_completerun` | direct/PRE | 48 | `8..55` | `68..26207` | 39 |
|  | module/POST | 38 | subset of `2..42` | `71..26116` | 25 |
|  | module/VINT | 3 | subset of `2..42` | `6351..26208` | 3 |
| `icz_completerun` | direct/PRE | 20 | `161..180` | `12304..25367` | 18 |
|  | module/POST | 24 | `158..181` | `35..25287` | 18 |
|  | module/VINT | 2 | `182..183` | `25352..25370` | 2 |

The first and last ordinal fingerprints are:

| Candidate/kind | First | Last |
|---|---|---|
| AIZ STANDARD direct | `8:c381a8f75b41d3e2d1e52fb90ae8a5c269b1daeb88dd198bd8fb3d07d3703a7b` | `48:fbfc78d499717cfec6df27fdd04fa4b5293a7147ec7ff7a7a18004e9db801e78` |
| AIZ STANDARD module | `2:4423f6be47e039925c8575c68ed5eb22e9cba75f2aadd05f1d288d6c9579e723` | `39:05324378670c6afa8c6d99f6e5313d625d2d926e6bc16f25cd9d8d1a5a195bf8` |
| AIZ complete direct | `8:c381a8f75b41d3e2d1e52fb90ae8a5c269b1daeb88dd198bd8fb3d07d3703a7b` | `55:e518e12a98e5cf2e81aaa09873e0c6c15e20f2813825dd2c88ce7ce3cc62d1cd` |
| AIZ complete module | `2:4423f6be47e039925c8575c68ed5eb22e9cba75f2aadd05f1d288d6c9579e723` | `42:6f27fe001c4a21687a98eb0dc8178340e7434967941c7a2fb7df442285b4c6f0` |
| ICZ complete direct | `161:29c7cf89ce102c704c78944dacf02b2a08fd370784d778dd08586c9b5842c9f6` | `180:ed4cd374a892ed4874e233fdc171cf685049e7ad8738d1874f4b76479ccf8360` |
| ICZ complete module | `158:9d76abb5369bb27fca1574b28bf37d429af1904dbd45edabef04fd0ab1e0f594` | `183:0cb0ec2d16ae44f272700f6e4825526242417afa145a7ff8405f92db8f341be2` |

The full hardware file digests above freeze every intermediate ordinal and
fingerprint.

## Mechanical byte-delta categorization

Every whole-file delta from the committed fixture has a named cause:

1. **Physics and aux plaintext: no delta.** All six decompressed byte streams
   have identical lengths and SHA-256 digests.
2. **Complete-run gzip containers: no delta.** Both AIZ and ICZ physics and
   aux gzip files are byte-identical to the committed containers.
3. **STANDARD gzip containers: native publisher recompression only.** The
   committed physics container is 570,548 bytes,
   SHA-256 `ad226dd6db810b6e12980e0211fe735782bb2888f5df2bdc865698ae8ac60571`;
   the committed aux container is 5,736,502 bytes,
   SHA-256 `212ad7191fd5f00bf333886a179a1ba471d7d7c242599da0e9fd8e230c4f5617`.
   The candidate containers differ, but complete streaming decompression is
   byte-identical and a second current-native capture reproduced the candidate
   containers exactly. The difference is therefore confined to deterministic
   current-publisher deflate layout, with no payload delta.
4. **Metadata: exactly three fields per candidate.** Mechanical JSON
   comparison finds only `recording_date` (`2026-07-27` to `2026-07-28`),
   `lua_script_version` (`6.37` to `6.38` for the relevant recorder), and
   `hardware_timing_schema` (`1` to `2`). All remaining keys, order, and
   formatting are exact.
5. **Hardware timing: direct-ledger insertion only.** Filtering each candidate
   to `kos_module_queue` produces a byte-exact copy of the complete committed
   hardware file. The candidate-minus-fixture byte increase equals the
   serialized direct lines exactly:

   | Candidate | File increase | Direct-line bytes |
   |---|---:|---:|
   | `aiz1_to_hcz_fullrun` | 9,232 | 9,232 |
   | `aiz_completerun` | 10,820 | 10,820 |
   | `icz_completerun` | 4,540 | 4,540 |

This projection and size identity accounts mechanically for every hardware
stream byte; no module event moved or changed.

## Independent recorder review

Independent review found no blocker at the capture commit.

Recorder production source is unchanged after the reviewed Task 5 sequence
`282018672`, `39584f77b`, and `cddbff68d`;
`git diff cddbff68d..fbfee310c -- tools/bizhawk-headless/src` is empty. The
review verified:

- `$FF0E` big-endian busy/count semantics and the four-entry `$FF40` FIFO;
- busy-to-idle retirement, longest suffix/prefix overlap, identical
  replacement, stable identical head, stale slot-zero, and mutation failure
  paths;
- exact 32-bit source/destination identity, standard-Kosinski scan lengths,
  and the shared language-neutral Java/C# vectors;
- direct PRE emission, canonical VINT/PRE/POST ordering, segment-gap
  continuity, and atomic reset of both ledgers; and
- schema-2 metadata defaults plus refusal of every unmodeled output-affecting
  S3K environment variable.

The ROM lifecycle remains anchored to `Queue_Kos`, `Process_Kos_Queue`, and
`Process_Kos_Module_Queue` in `docs/skdisasm/sonic3k.asm:2668-2967`, with the
ordinary level-loop boundary order at `sonic3k.asm:7884-7922`.

## Pre-installation trace frontiers

Each class ran in a separate JDK-21 Maven invocation with the verified
`-Ds3k.rom.path`. `target/trace-reports` was cleared between invocations and
each report, context, Surefire text report, and XML report was archived under
`.scratch/task8-candidates/frontiers/`.

| Class | Maven outcome | Timing publication stop | Comparator report |
|---|---|---|---|
| `TestS3kAizTraceReplay` | 16 tests: 4 failures, 1 error | module `#27`, fingerprint `65c8c371e1ca1f70acf3a74cc1fa689867dcffbe93617a8c968e3de9242f89b3`, engine pending none | 36 errors; first `x` at frame 5496, `0x00CD` vs `0x2FCD` |
| `TestS3kAizCompleteRunTraceReplay` | 1 test: 1 error | module `#26`, same fingerprint, engine pending none | 46 errors; first `x` at frame 6300, `0x00D2` vs `0x2FD2` |
| `TestS3kIczCompleteRunTraceReplay` | 1 test: 1 error | module `#160`, fingerprint `0fbbcb25822bda53fc0b212780f2218200ef117c753fa623fa1a05c66379f152`, engine pending none | 81 errors; first `x` at frame 1232, `0x386E` vs `0x386F` |

These are the frozen pre-installation frontiers. The schema-1 module-only
fixtures cannot close the new direct-count authority contract.

## Verification

Fresh focused recorder lifecycle tests:

```text
BIZHAWK_HOME=<bizhawk> ./test.sh --filter HardwareTimingEventEngine --jobs 1
15 passed; unrelated GpgxHost case skipped because S1_ROM_PATH was unset
```

Fresh schema-selection tests:

```text
BIZHAWK_HOME=<bizhawk> ./test.sh --filter "schema two" --jobs 1
3 passed; unrelated GpgxHost case skipped because S1_ROM_PATH was unset
```

Fresh Java scanner, queue, stream-loader, and replay-port tests:

```text
mvn -Dmse=off \
  "-Dtest=TestHardwareSubmissionFingerprint,TestS3kKosDecompressionQueue,TestHardwareTimingStreamLoader,TestHardwareTimingReplayPort" test
Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
```

Task 7's current-recorder all-ROM no-gates result remains:

```text
410 total: 410 passed, 0 failed, 0 skipped
```

The expected native differentials remain non-successful at the explicit
schema-1 publication boundary; they were not weakened into passes.

## Exact approval payload

Approval is requested for byte-for-byte installation, with no hand edits, of
these exact mappings:

| Scratch candidate | Approved destination |
|---|---|
| `tools/bizhawk-headless/.scratch/task8-candidates/aiz-standard/` | `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/` |
| `tools/bizhawk-headless/.scratch/task8-candidates/complete-through-icz/aiz/` | `src/test/resources/traces/s3k/aiz_completerun/` |
| `tools/bizhawk-headless/.scratch/task8-candidates/complete-through-icz/icz/` | `src/test/resources/traces/s3k/icz_completerun/` |

Approval covers only the twelve exact files identified by the candidate
SHA-256 and byte-length table. It explicitly acknowledges:

- unchanged physics and aux plaintext;
- deterministic STANDARD gzip container recompression;
- the three exact metadata field changes; and
- byte-exact preservation of every module event plus the reported direct
  schema-2 events.

Without that explicit approval, none of these bytes will be installed.

## Corrected observability candidates (2026-07-29)

The approval payload above is superseded and must not be used. Native review
after the first capture found that polling `$FF0E`/`$FF40` alone cannot
observe an ordinary `Queue_Kos` entry which is submitted and retired between
recorder samples. The recorder now observes the exact S3K `Queue_Kos`
execution callback at PC `$001B46` on the M68K bus and uses the RAM ledger
only to reconcile physical FIFO lifetime. Fresh candidates remain isolated at:

```text
tools/bizhawk-headless/.scratch/task8-candidates-observability-20260729-v5/
```

No corrected candidate has been copied into a fixture directory or added to
the publication manifest. Publishing any of these bytes requires renewed
explicit approval.

The corrected STANDARD run preserves physics and aux bytes from the first
candidate and adds exactly 16 previously unobservable direct completions:

| File | Bytes | SHA-256 |
|---|---:|---|
| `aiz-standard/metadata.json` | 1,273 | `11d288234235ae2cdeb06c00ef59fa3aabc1ad2e69990541fa4e01d57676f4f7` |
| `aiz-standard/physics.csv.gz` | 605,006 | `b5591fe7f274b78f72dfbae83eb8d975c172cd393fe5707582f11efa4ea20c77` |
| `aiz-standard/aux_state.jsonl.gz` | 5,846,635 | `0c7ac4514b9e6c1750a340aeb034660b0a954ba666977aab9dea9da18e19e137` |
| `aiz-standard/hardware_timing.jsonl` | 21,083 | `e458a2fafd800d53cff4fbbc4e15e2df493312c67b46b6b138641372bf3fe284` |

It contains 95 hardware events: 57 direct events at ordinals `9..65`,
raw frames `358..20796`, and the unchanged 38-event module projection at
ordinals `2..39`, raw frames `361..20797`.

The corrected complete run again produced all five segments with the same
offsets, row counts, physics bytes, aux bytes, and byte-exact module
projections. Every segment gained only direct events, so the corrected
candidate scope is all five segments rather than the original AIZ/ICZ subset:

| Segment | Direct additions | Hardware bytes | Hardware SHA-256 | Metadata SHA-256 |
|---|---:|---:|---|---|
| AIZ | 18 | 23,788 | `c80a9c2f0383cfb3ad153ea5448684657543676f1c5920a0e472095a09f8d9e4` | `b171cd243fccb42beb91f086a9b933b03a917961d68c201edce8bb4e0bd5727b` |
| HCZ | 21 | 22,429 | `a19d98bd7cf341ffcf1c19871e22044abc00bbde99ad352a0e1d41e8f3a34aeb` | `2629d104936f9e9cb133c3061d1d307af28e7beea52e69ba4dcc3db5146fd5e0` |
| MGZ | 15 | 23,274 | `82cc794ff12d811cc4be3c99af2e28d1cb8ea4b8ddb04f0ea53142275d387562` | `e11d5973a88f6549cdad3b3600256b48e63740e82b605fd4d2a7c8e592473186` |
| CNZ | 18 | 15,377 | `821810c7cd400064f2f204eed333b16880f86a3b81dc333e7c7b74d16d086c2f` | `cf6d98e9d4db90ba35adb4eb2fc3c5e07b26154ace667c704c3fccba09e01b6a` |
| ICZ | 19 | 14,531 | `bc006d24b4065ac13fd9d464a14d12618f4811e9a9679ce2f45f62b554677b92` | `c529ab4aa1a1917eea81521615c7749585cb561166951fcffe2de729c291716a` |

The complete-run payload bytes are frozen as:

| Segment | Physics bytes / SHA-256 | Aux bytes / SHA-256 |
|---|---|---|
| AIZ | 818,157 / `1b60c8120b60aaf815e42123a58307e1b088201da6d6767c9703e40d16a51ce8` | 7,986,812 / `b98e40eb2847ca24a83ee4be6e19a39e304a38e207f206069577ed7502aa4549` |
| HCZ | 918,349 / `f5b2f7b97951a3b8897d2e3f96c1a8155cd2bcf5d82410a441f8b376c2020337` | 9,749,637 / `d909247f538836fd2a02f462fb022e276c259418f6d10b4c2d45c1780b1f6146` |
| MGZ | 1,312,707 / `71019baab98c1ef4b9580507e710098cd95e20aa176dc93a0e37613c967d3a48` | 10,340,461 / `00f42e42dc1178884d132df11d7a66ff2f157b083bebb8a73d57a65b910c790c` |
| CNZ | 1,271,308 / `df5dccc34312ab600c308d8d6b57b9930c24dd00472c8607be4ba1918f7bd1f2` | 10,345,467 / `c0ceeee1837cde85aff516da4a0c2a474d2c5ecbbdbb38ae1f03ca915a948d09` |
| ICZ | 848,470 / `49a9d4c90dfa5e392d25fc694ebf489e372b64b8f0b30bd8c63e5cf2d1cbdbf4` | 8,232,022 / `aef33fbefc2164807cb8ea66fcafa416105a154a084dd2d52dd4e9887910ec7b` |

Mechanical validation found zero global ordering violations and zero module
projection differences. Metadata differs from the first capture only by the
recording date (`2026-07-28` to `2026-07-29`).

The complete corrected ledger inventory is:

| Candidate | Direct count / ordinals / raw frames | Module count / ordinals / raw frames |
|---|---|---|
| AIZ STANDARD | 57 / `9..65` / `358..20796` | 38 / `2..39` / `361..20797` |
| AIZ complete | 66 / `9..74` / `68..26207` | 41 / `2..42` / `71..26208` |
| HCZ complete | 56 / `75..130` / `35..31460` | 45 / `43..87` / `36..31461` |
| MGZ complete | 66 / `131..196` / `35..39374` | 38 / `88..125` / `36..39375` |
| CNZ complete | 37 / `197..233` / `36..40043` | 32 / `126..157` / `37..40044` |
| ICZ complete | 39 / `234..272` / `34..25369` | 26 / `158..183` / `35..25370` |

The first/last event identities freeze each kind independently:

| Candidate | Direct first / last fingerprint | Module first / last fingerprint |
|---|---|---|
| AIZ STANDARD | `9:c381a8f75b41d3e2d1e52fb90ae8a5c269b1daeb88dd198bd8fb3d07d3703a7b` / `65:513a9a9016d04c5eb13ce66f295beee59f58d0f7b521633e61b4a2d331e0f696` | `2:4423f6be47e039925c8575c68ed5eb22e9cba75f2aadd05f1d288d6c9579e723` / `39:05324378670c6afa8c6d99f6e5313d625d2d926e6bc16f25cd9d8d1a5a195bf8` |
| AIZ complete | `9:c381a8f75b41d3e2d1e52fb90ae8a5c269b1daeb88dd198bd8fb3d07d3703a7b` / `74:e518e12a98e5cf2e81aaa09873e0c6c15e20f2813825dd2c88ce7ce3cc62d1cd` | `2:4423f6be47e039925c8575c68ed5eb22e9cba75f2aadd05f1d288d6c9579e723` / `42:6f27fe001c4a21687a98eb0dc8178340e7434967941c7a2fb7df442285b4c6f0` |
| HCZ complete | `75:82c973d77d76a07873ede88ecf76104306b834aa6410710f0cfc10f95240331f` / `130:a94c4e7ec2be218f2439a58af082042c8058df0a537cb6c37c7b716d3af0e472` | `43:f7d726c95e019598b69ed655a53fca44b42967d9361230092ba02e539abfa45f` / `87:f7494191ee9cfd23061afe24051ea2a3ebf06ed397e40004ba9e56be2db9a4ad` |
| MGZ complete | `131:e045a53989c11fc9a7d8e36f7f7418f40bd13144de810424122515cd609b9cca` / `196:555b3799e0ac6348265737a808bc435817aa033430c53a9118608be66b0e0089` | `88:5927b86c66ad50078c2330d88709746bff3cbe47bdbfb15dd9e62508d9d32e08` / `125:b5567e4f10bffc7360e18d2ca0706b93d6dd0f7a579323cd844299d9bf7dfc24` |
| CNZ complete | `197:c2b0befca6c881f069f36f7bf5955eda3974e620af2f01172124ba808eeb4650` / `233:1c6b51a04273b40fc21644558d2bd3f610781f66cb1135698dd849eecf5f45b0` | `126:e71f44c98c5ca2ba810ff178e16690d6833a829768e840eea2867a1e866837dd` / `157:26b13360dd345d34b890725a34c96e1224725d95acd9583b75b6237052350c66` |
| ICZ complete | `234:403b1d33b7d7af9a32e45aca194d548b6c96a8fc718daca7e19aed05d14a14c8` / `272:7bcd78401814329cc46de116d846e4c037cb2f8a559e69902a4e699bd84effd6` | `158:9d76abb5369bb27fca1574b28bf37d429af1904dbd45edabef04fd0ab1e0f594` / `183:0cb0ec2d16ae44f272700f6e4825526242417afa145a7ff8405f92db8f341be2` |

Comparison against the first-capture candidates produced this exact result:

```text
aiz-standard              module=exact physics=exact aux=exact direct=41->57
complete-through-icz/aiz  module=exact physics=exact aux=exact direct=48->66
complete-through-icz/hcz  module=exact physics=exact aux=exact direct=35->56
complete-through-icz/mgz  module=exact physics=exact aux=exact direct=51->66
complete-through-icz/cnz  module=exact physics=exact aux=exact direct=19->37
complete-through-icz/icz  module=exact physics=exact aux=exact direct=20->39
```

This is a semantic insertion comparison, not a claim that retained direct
JSON lines remain byte-identical. Every first-capture direct completion is
preserved at the same raw frame, boundary, and fingerprint, but its ordinal
can increase when an exact callback reveals an earlier short-lived
submission. The corrected hardware files therefore contain inserted direct
events plus mechanically re-ordinaled retained direct events; module lines
remain byte-exact.

Relative to the currently installed first-capture AIZ STANDARD, AIZ complete,
and ICZ complete candidates, metadata changes only `recording_date`. Relative
to the committed schema-1 HCZ, MGZ, and CNZ baselines, metadata changes
`recording_date`, recorder version `6.37` to `6.38`, and
`hardware_timing_schema` `1` to `2`. Physics and aux payload bytes do not
change in either comparison.

### Corrected-overlay implementation frontier

The corrected bytes were mapped into an isolated source-shaped overlay and
replayed with the Surefire fork rooted there:

```bash
mvn -q \
  -Dsurefire.argLine=-Duser.dir=/tmp/openggf-task8-replay-jV83Dn \
  -Dtest=com.openggf.tests.trace.s3k.TestS3kAizCompleteRunTraceReplay,\
com.openggf.tests.trace.s3k.TestS3kMgzCompleteRunTraceReplay,\
com.openggf.tests.trace.s3k.TestS3kCnzCompleteRunTraceReplay \
  -Ds3k.rom.path=<verified-s3k-rom> test
```

The ROM-backed fixes advance all three routes beyond their targeted missing
owners:

- AIZ admits direct ordinals `26..34` and module parents `13..14`, then stops
  at direct `#35`, fingerprint `c3e8ddd34bf587540ca7d131fc68d371538d1a746da64c4eee3ec01f524948b7`,
  raw frame 6346. It is the sole child of module `#15`
  (`65c8c371e1ca1f70acf3a74cc1fa689867dcffbe93617a8c968e3de9242f89b3`),
  the AIZ Monkey Dude request owned by
  `Sonic3kObjectArtProvider.scheduleEnemyKosArt`. The initial AIZ enemy group
  already replays; this repetition belongs to the post-AIZ1 reload/title
  lifecycle rather than the hardware queue.
- MGZ admits the complete MGZ1 LoadEnemyArt group, direct ordinals `131..133`
  and module parents `88..90`, then stops at direct `#134`, fingerprint
  `c2db2fda975f758607b601f686bc782c7ebe55e2413f540f23b193ba2b6f1741`,
  raw frame 14631. It is the child of module `#91`
  (`1523a70a6b4a9eea42f03f72e73e83ab222fb6e09187cbc49b028451f14e3033`),
  the StarPost Stars1/blue bonus-art request owned by
  `Sonic3kStarPostObjectInstance.spawnBonusStars()`. The identical pair recurs
  in HCZ, CNZ, and ICZ native ledgers.
- CNZ admits the complete LoadEnemyArt group, direct ordinals `197..200` and
  module parents `126..129`, then stops at StarPost direct `#201`, fingerprint
  `66961069e564ef707173bbad733f75e3ab034e29e3f4833a02e2e26af452d8fd`,
  raw frame 5337. It is the child of Stars3 module `#130`
  (`28a69b8f385d0f7355d90a7aa996d75d45e26eb4b2672d7ce3e0eec11a513b3f`).

These new failures are deliberately not converted into timing exceptions.
The other three exact isolated-overlay stops are:

- AIZ STANDARD: StarPost Stars3 direct `#25`,
  `66961069e564ef707173bbad733f75e3ab034e29e3f4833a02e2e26af452d8fd`,
  raw frame 3879;
- HCZ complete: repeated Blastoid direct `#80`,
  `82c973d77d76a07873ede88ecf76104306b834aa6410710f0cfc10f95240331f`,
  raw frame 1335; and
- ICZ complete: StarPost Stars2 direct `#236`,
  `107442b529cf8edafb0750d0198606ee2c4a667f4f99bb3c68065188177cf1e6`,
  raw frame 1629.

All six stops are upstream object/reload lifecycle gaps. None indicates a
recorder or timing-coordinator fault.

### Renewed approval payload

Renewed approval would authorize byte-for-byte replacement of exactly the
four named fixture files (`metadata.json`, `physics.csv.gz`,
`aux_state.jsonl.gz`, and `hardware_timing.jsonl`) in each of these six
directories: 24 files total.

| Corrected isolated directory | Prospective fixture directory |
|---|---|
| `aiz-standard/` | `s3k/aiz1_to_hcz_fullrun/` |
| `complete-through-icz/aiz/` | `s3k/aiz_completerun/` |
| `complete-through-icz/hcz/` | `s3k/hcz_completerun/` |
| `complete-through-icz/mgz/` | `s3k/mgz_completerun/` |
| `complete-through-icz/cnz/` | `s3k/cnz_completerun/` |
| `complete-through-icz/icz/` | `s3k/icz_completerun/` |

This expands the obsolete three-directory request to six because exact
submission observation found short-lived direct jobs in every complete-run
segment. Approval would acknowledge:

- the exact file sizes and SHA-256 digests frozen above;
- byte-exact physics, aux, and module projections relative to the first
  candidates;
- direct-event additions only, with zero deletions or ordering violations;
- metadata recording-date changes only relative to the first candidates; and
- the measured next upstream gameplay/reload frontiers, which remain expected
  replay failures and are not timing-authority exceptions.

It would not authorize recorder or engine changes beyond the reviewed diff,
hand edits to candidate files, further native recapture, trace-derived
gameplay submissions, resolution of the upstream StarPost/reload gaps, merge,
or push.

## Publication completed (2026-07-29)

The user authorized the corrected v5 payload without another approval pause.
The four reviewed files were copied byte-for-byte from each of the six
corrected candidate directories into the mapped fixture directory. No
validation capture was installed. Although unchanged complete-run physics and
aux gzip members do not appear in the Git diff, all 24 publication files were
copied and verified.

`hardware-timing-publication.tsv` now freezes all six schema-2 identities:

| Fixture | Events | Direct PRE | Module POST/VINT | Timing SHA-256 |
|---|---:|---:|---:|---|
| `aiz1_to_hcz_fullrun` | 95 | 57 | 38/0 | `e458a2fafd800d53cff4fbbc4e15e2df493312c67b46b6b138641372bf3fe284` |
| `aiz_completerun` | 107 | 66 | 38/3 | `c80a9c2f0383cfb3ad153ea5448684657543676f1c5920a0e472095a09f8d9e4` |
| `hcz_completerun` | 101 | 56 | 43/2 | `a19d98bd7cf341ffcf1c19871e22044abc00bbde99ad352a0e1d41e8f3a34aeb` |
| `mgz_completerun` | 104 | 66 | 37/1 | `82cc794ff12d811cc4be3c99af2e28d1cb8ea4b8ddb04f0ea53142275d387562` |
| `cnz_completerun` | 69 | 37 | 30/2 | `821810c7cd400064f2f204eed333b16880f86a3b81dc333e7c7b74d16d086c2f` |
| `icz_completerun` | 65 | 39 | 24/2 | `bc006d24b4065ac13fd9d464a14d12618f4811e9a9679ce2f45f62b554677b92` |

The Java committed-fixture guard passes 2 tests. The expanded submission
fingerprint, queue, stream-loader, replay-port, and production replay command
passes 58 tests. The native no-gates suite passes 387 tests with zero failures
and 30 ROM-environment skips. A temporary ROM-backed STANDARD differential
also passes: the current recorder reproduces the installed AIZ timing stream
byte-for-byte.

An intermediate full 466,334-row complete-run segment differential was
attempted with temporary output only. Independent review found that this
intermediate gate compared metadata and timing only through ICZ, suppressing
the later schema-1 publication boundary. The run had not produced a verdict
after approximately 9 minutes 20 seconds and was stopped at the requested
bounded cutoff (`SIGINT`, exit 130); it is rejected as evidence. The gate now
again compares metadata and timing for every fixture-bearing segment, so the
unapproved later identities remain an explicit failure. That corrected full
gate was not run to a verdict. The installed fixture tree was untouched: a
post-attempt source/destination `cmp` and size/SHA-256 sweep passed for all 24
files.

The six installed-fixture replay methods reproduce the reviewed next
frontiers exactly. Each method reports one hardware-authority error and no
assertion failure:

| Replay | First error |
|---|---|
| AIZ STANDARD | direct `#25`, raw 3879, `66961069e564ef707173bbad733f75e3ab034e29e3f4833a02e2e26af452d8fd` |
| AIZ complete | direct `#35`, raw 6346, `c3e8ddd34bf587540ca7d131fc68d371538d1a746da64c4eee3ec01f524948b7` |
| HCZ complete | direct `#80`, raw 1335, `82c973d77d76a07873ede88ecf76104306b834aa6410710f0cfc10f95240331f` |
| MGZ complete | direct `#134`, raw 14631, `c2db2fda975f758607b601f686bc782c7ebe55e2413f540f23b193ba2b6f1741` |
| CNZ complete | direct `#201`, raw 5337, `66961069e564ef707173bbad733f75e3ab034e29e3f4833a02e2e26af452d8fd` |
| ICZ complete | direct `#236`, raw 1629, `107442b529cf8edafb0750d0198606ee2c4a667f4f99bb3c68065188177cf1e6` |

These are upstream object/reload lifecycle frontiers. Publication did not add
a trace exception, hydrate gameplay from comparison data, or change an engine
owner to make a fixture pass.
