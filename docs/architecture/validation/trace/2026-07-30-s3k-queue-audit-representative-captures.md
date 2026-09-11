# S3K Queue-Audit Representative Capture Validation

Date: 2026-07-30
Branch: `feature/ai-trace-fleet-regeneration`
Recorder commits: `aa5400814`, `1f27edc8e` plus the review fixes
recorded with this report
ROM SHA-1: `CFBF98C36C776677290A872547AC47C53D2761D6`

These captures validate the native schema-2 S3K Kosinski module and direct
queue audit before full-fleet regeneration. They are scratch candidates only;
no canonical fixture was installed.

## Capture commands

All commands used the repository-local BizHawk 2.11 distribution and added
`--load-queue-state`.

| Candidate | Movie | Profile |
|---|---|---|
| AIZ | `s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2` | `aiz_end_to_end` |
| CNZ | `s3k/cnz/s3k-cnz-sonic-tails.bk2` | `level_gated_reset_aware` |
| MGZ | `s3k/mgz/s3k-mgz-sonic-tails.bk2` | `level_gated_reset_aware` |

Output root:
`.scratch/s3k-queue-audit-reviewfix-20260730`.

## Structural results

Every stored row contains exactly one `s3k_kos_direct` and one
`s3k_kos_module` `load_queue_state` event. All queue fingerprints are
lowercase 64-character SHA-256 values, service observations are empty, idle
states are canonical, and every busy direct destination is within signed 68K
work RAM (`-65536..-2`). Direct completions occur only at `pre_main_loop`;
module completions occur only at `post_objects`.

| Candidate | Rows | Queue states | Direct PRE completions | Module POST completions |
|---|---:|---:|---:|---:|
| AIZ | 20,798 | 41,596 | 57 | 38 |
| CNZ | 42,253 | 84,506 | 27 | 27 |
| MGZ | 35,912 | 71,824 | 56 | 34 |

Java `TraceData.load` strictly parsed all three candidates and enforced the
advertised two-heartbeat-per-row contract. `TestLoadQueueTraceComparison`
also verified literal JSON parsing and zero-tolerance comparison of signed
`$FFFFD000`.

## Frozen files

| File | Bytes | SHA-256 |
|---|---:|---|
| `aiz/aux_state.jsonl.gz` | 6,079,699 | `3150b0d585729c90476b8e863f717cf5221b4c59f9de569d275933726f232b1d` |
| `aiz/hardware_timing.jsonl` | 21,083 | `e458a2fafd800d53cff4fbbc4e15e2df493312c67b46b6b138641372bf3fe284` |
| `aiz/metadata.json` | 1,303 | `1b2fdc408aeabd3a87c2840baa205a7cadc60f7d9b48368425fd34208d0341f2` |
| `aiz/physics.csv.gz` | 605,006 | `b5591fe7f274b78f72dfbae83eb8d975c172cd393fe5707582f11efa4ea20c77` |
| `cnz/aux_state.jsonl.gz` | 11,116,516 | `003573b758577e12655a7668b70e72f5044dfec7df251c4cc259daa8ac34d137` |
| `cnz/hardware_timing.jsonl` | 11,952 | `27a8446c19849fa650899b0135338e495a6122b8732ecc56fcf31b1035d2bb9c` |
| `cnz/metadata.json` | 1,375 | `9499656a23351c82ed1404f1e21db3b91ac8eeb2d97fbdbb5ceb2e2ab4946cfd` |
| `cnz/physics.csv.gz` | 1,351,548 | `7d0c482b873cf24f48641d9e0f93751024fc94ddfcb361f3a6f3b4c88e57dc9a` |
| `mgz/aux_state.jsonl.gz` | 9,642,088 | `0d937a9af3318e5ee06de4accf8132b7e285d42cd427d64b96179de1aa2d19e4` |
| `mgz/hardware_timing.jsonl` | 20,038 | `7a07fc7e05abe74d3075a7137067c7874152af40e01c6e9ab4954d1a63c46510` |
| `mgz/metadata.json` | 1,295 | `fb8fb624e27ae7f48e7b980cf01c4659f18c711ceaab6c8d25f88fb21456ac36` |
| `mgz/physics.csv.gz` | 1,203,806 | `be03e6551f28da9679db63365896a4b31dee2a65d4cc2245bf4d0f20a7d23640` |

The hashes match the pre-review representative capture byte-for-byte. The
review fixes affect Java validation and differential fail-closed policy, not
recorder output.

## Focused verification

- `TestLoadQueueTraceComparison`: 8 passed, 0 failed.
- Native repeated-callback hardware-timing matrix: 24 passed, 0 failed.
- Complete-run metadata compatibility: 2 passed, 0 failed.
- Complete-segments and run-mode migration fail-closed tests: 2 passed,
  0 failed.
- AIZ canonical native differential: passed.
- CNZ and MGZ canonical native differentials: recorder completed, then each
  stopped at the intentional schema-1 load-only compatibility refusal.
