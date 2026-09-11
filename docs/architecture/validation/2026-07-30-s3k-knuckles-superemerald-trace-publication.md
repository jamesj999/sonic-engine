# S3K Knuckles Super-Emerald Trace Publication Validation

## Result

Approved for immutable publication after an independent inventory review. The
native S3K complete-run recorder consumed all 434,417 BK2 controller rows and
published 67 segments, 48 typed transitions, and one run manifest under run id
`s3k-knuckles-complete-superemeralds`.

The capture contains 14 special-stage visits: the first seven raise the emerald
count from 0 to 7, and the next seven are the super-emerald route. It also
contains five Pachinko, three Gumball, and two Slots bonus-stage visits.

## Provenance

| Item | Value |
|---|---|
| Source BK2 | `docs/BizHawk-2.11-linux-x64/Movies/s3k-knuckles-complete-superemeralds.bk2` |
| Curated BK2 | `src/test/resources/traces/s3k/_movies/s3k-knuckles-complete-superemeralds.bk2` |
| BK2 SHA-256 | `aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc` |
| Controller rows | 434,417 |
| BizHawk / core | 2.11 / Genplus-gx |
| ROM MD5 / BK2 token | `C5B1C655C19F462ADE0AC4E17A844D10` |
| ROM SHA-1 | `CFBF98C36C776677290A872547AC47C53D2761D6` |
| ROM CRC32 | `63522553` |
| Recorder | `6.38-s3k-completerun` |
| Trace / timing schema | 7 / 2 |
| Manifest SHA-256 | `01246e7e74f019382b2e210896c12668266fd3d3e11914da3a84428904fdc6a3` |
| Manifest bytes | 20,740 |

Capture command:

```bash
BIZHAWK_HOME="$MAIN_ROOT/docs/BizHawk-2.11-linux-x64" \
tools/bizhawk-headless/run.sh \
  --rom "$S3K_ROM_PATH" \
  --movie "$PWD/src/test/resources/traces/s3k/_movies/s3k-knuckles-complete-superemeralds.bk2" \
  --output "$PWD/tools/bizhawk-headless/.scratch/s3k-knuckles-complete-superemeralds" \
  --mode trace \
  --run-id s3k-knuckles-complete-superemeralds
```

## Ordered Segment Inventory

| # | Directory | Kind | Profile | Zone | Act | BK2 offset | Rows |
|---:|---|---|---|---:|---:|---:|---:|
| 0 | `aiz` | level | complete_run | 0 | 1 | 810 | 1653 |
| 1 | `ss` | special_stage | s3k_special_stage | 0 | 0 | 2464 | 4479 |
| 2 | `aiz_2` | level | complete_run | 0 | 1 | 8423 | 5174 |
| 3 | `ss_2` | special_stage | s3k_special_stage | 0 | 0 | 13598 | 5550 |
| 4 | `aiz_3` | level | complete_run | 0 | 2 | 20647 | 9276 |
| 5 | `pachinko` | bonus_stage | s3k_bonus_stage | 20 | 1 | 29924 | 2775 |
| 6 | `aiz_4` | level | complete_run | 0 | 2 | 32700 | 6399 |
| 7 | `pachinko_2` | bonus_stage | s3k_bonus_stage | 20 | 1 | 39100 | 300 |
| 8 | `aiz_5` | level | complete_run | 0 | 2 | 39401 | 4011 |
| 9 | `hcz` | level | complete_run | 1 | 1 | 43413 | 4265 |
| 10 | `pachinko_3` | bonus_stage | s3k_bonus_stage | 20 | 1 | 47679 | 3135 |
| 11 | `hcz_2` | level | complete_run | 1 | 1 | 50815 | 5143 |
| 12 | `ss_3` | special_stage | s3k_special_stage | 0 | 0 | 55959 | 5701 |
| 13 | `hcz_3` | level | complete_run | 1 | 1 | 63131 | 678 |
| 14 | `gumball` | bonus_stage | s3k_bonus_stage | 19 | 1 | 63810 | 652 |
| 15 | `hcz_4` | level | complete_run | 1 | 1 | 64463 | 11056 |
| 16 | `ss_4` | special_stage | s3k_special_stage | 0 | 0 | 75520 | 5116 |
| 17 | `hcz_5` | level | complete_run | 1 | 2 | 82106 | 5596 |
| 18 | `slots` | bonus_stage | s3k_bonus_stage | 21 | 1 | 87703 | 2071 |
| 19 | `hcz_6` | level | complete_run | 1 | 2 | 89775 | 1975 |
| 20 | `ss_5` | special_stage | s3k_special_stage | 0 | 0 | 91751 | 4629 |
| 21 | `hcz_7` | level | complete_run | 1 | 2 | 97850 | 9390 |
| 22 | `mgz` | level | complete_run | 2 | 1 | 107241 | 8833 |
| 23 | `ss_6` | special_stage | s3k_special_stage | 0 | 0 | 116075 | 6022 |
| 24 | `mgz_2` | level | complete_run | 2 | 1 | 123572 | 31113 |
| 25 | `cnz` | level | complete_run | 3 | 1 | 154686 | 4362 |
| 26 | `ss_7` | special_stage | s3k_special_stage | 0 | 0 | 159049 | 4924 |
| 27 | `cnz_2` | level | complete_run | 3 | 1 | 165705 | 11256 |
| 28 | `icz` | level | complete_run | 5 | 1 | 176962 | 20128 |
| 29 | `lbz` | level | complete_run | 6 | 1 | 197091 | 24937 |
| 30 | `gumball_2` | bonus_stage | s3k_bonus_stage | 19 | 1 | 222029 | 749 |
| 31 | `lbz_2` | level | complete_run | 6 | 2 | 222779 | 6444 |
| 32 | `mhz` | level | complete_run | 7 | 1 | 229224 | 605 |
| 33 | `dez23` | level | complete_run | 23 | 2 | 229830 | 988 |
| 34 | `ss_8` | special_stage | s3k_special_stage | 0 | 0 | 230819 | 5083 |
| 35 | `mhz_2` | level | complete_run | 7 | 1 | 237252 | 6418 |
| 36 | `dez23_2` | level | complete_run | 23 | 2 | 243671 | 147 |
| 37 | `ss_9` | special_stage | s3k_special_stage | 0 | 0 | 243819 | 3969 |
| 38 | `mhz_3` | level | complete_run | 7 | 1 | 249586 | 10765 |
| 39 | `dez23_3` | level | complete_run | 23 | 2 | 260352 | 171 |
| 40 | `ss_10` | special_stage | s3k_special_stage | 0 | 0 | 260524 | 4759 |
| 41 | `mhz_4` | level | complete_run | 7 | 2 | 267081 | 2758 |
| 42 | `dez23_4` | level | complete_run | 23 | 2 | 269840 | 194 |
| 43 | `ss_11` | special_stage | s3k_special_stage | 0 | 0 | 270035 | 5335 |
| 44 | `mhz_5` | level | complete_run | 7 | 2 | 277168 | 1610 |
| 45 | `dez23_5` | level | complete_run | 23 | 2 | 278779 | 186 |
| 46 | `ss_12` | special_stage | s3k_special_stage | 0 | 0 | 278966 | 5155 |
| 47 | `mhz_6` | level | complete_run | 7 | 2 | 285919 | 5328 |
| 48 | `fbz` | level | complete_run | 4 | 1 | 291248 | 11571 |
| 49 | `dez23_6` | level | complete_run | 23 | 2 | 302820 | 209 |
| 50 | `ss_13` | special_stage | s3k_special_stage | 0 | 0 | 303030 | 8538 |
| 51 | `fbz_2` | level | complete_run | 4 | 1 | 313376 | 2118 |
| 52 | `gumball_3` | bonus_stage | s3k_bonus_stage | 19 | 1 | 315495 | 927 |
| 53 | `fbz_3` | level | complete_run | 4 | 1 | 316423 | 14067 |
| 54 | `dez23_7` | level | complete_run | 23 | 2 | 330491 | 418 |
| 55 | `ss_14` | special_stage | s3k_special_stage | 0 | 0 | 330910 | 4598 |
| 56 | `fbz_4` | level | complete_run | 4 | 2 | 338011 | 8302 |
| 57 | `soz` | level | complete_run | 8 | 1 | 346314 | 3344 |
| 58 | `pachinko_4` | bonus_stage | s3k_bonus_stage | 20 | 1 | 349659 | 3326 |
| 59 | `soz_2` | level | complete_run | 8 | 1 | 352986 | 34134 |
| 60 | `lrz` | level | complete_run | 9 | 1 | 387121 | 3337 |
| 61 | `pachinko_5` | bonus_stage | s3k_bonus_stage | 20 | 1 | 390459 | 2880 |
| 62 | `lrz_2` | level | complete_run | 9 | 1 | 393340 | 8945 |
| 63 | `slots_2` | bonus_stage | s3k_bonus_stage | 21 | 1 | 402286 | 2208 |
| 64 | `lrz_3` | level | complete_run | 9 | 2 | 404495 | 7000 |
| 65 | `hpz22` | level | complete_run | 22 | 2 | 411496 | 1004 |
| 66 | `hpz` | level | complete_run | 10 | 2 | 412501 | 21441 |

The manifest is the authoritative full transition inventory, including sampled
entry fields. Review confirmed all 48 records are ordered and connect the
expected giant-ring, bonus-stage, and stage-exit pairs.

## Terminal and File Audit

The final HPZ segment covers controller indices 412,501 through 433,941:
`412501 + 21441 = 433942`. The native recorder continued to movie exhaustion,
leaving exactly `434417 - 433942 = 475` controller frames outside any active
trace segment. These are a non-segment terminal tail, not missing physics rows.

Independent review also confirmed:

- every directory contains exactly `metadata.json`, `physics.csv.gz`,
  `aux_state.jsonl.gz`, and `hardware_timing.jsonl`;
- all 67 metadata identities, offsets, counts, profiles, and run ids match the
  manifest;
- every physics gzip decodes to its declared number of rows;
- every aux/timing JSON record parses;
- all timing streams are nonempty, ordered, and frame-bounded;
- the run contains 1,537 hardware completion edges;
- all four file lengths and SHA-256 values, plus first/last edges and boundary
  counts, match the 67 reviewed `FIXTURE` rows in
  `src/test/resources/traces/s3k/hardware-timing-publication.tsv`; and
- adjacency has no overlap. The 52 ordinary boundaries have their expected
  one-frame consumed gap; larger gaps are non-segment interludes.

Only these new schema-2 destinations cross a reviewed direct-consumer boundary:

- `runs/s3k-knuckles-complete-superemeralds/aiz` — initial AIZ intro;
- `runs/s3k-knuckles-complete-superemeralds/icz` — ICZ act/zone transition.

Later AIZ segments and the HCZ, MGZ, and CNZ segments preserve the continuous
run-wide direct ledger but do not independently prove consumer reach, so they
are deliberately excluded from the semantic direct-consumer inventory.

## Focused Verification

```text
mvn -Dmse=off \
  "-Dtest=TestCommittedHardwareTimingFixtures,TestTraceFixtureCompressionGuard,TestTraceCatalogRunDiscovery,TraceCatalogTest,TraceCatalogSpecialStageTest" test
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

The ROM-backed full capture above is the exact-new-movie verification.

The maintained native regression suite also exposed four existing legacy
fixture-compatibility failures unrelated to this new run:

```text
tools/bizhawk-headless/test.sh --filter S3KCompleteRun
38 passed, 2 failed, 0 skipped
- bonus_gumball legacy metadata shape rejected
- lbz_completerun expects recorder 6.37 while the writer emits 6.38

tools/bizhawk-headless/test.sh --filter S3KRunMode
0 passed, 2 failed, 0 skipped
- identity (C) legacy metadata normalization count: expected 1, observed 0
- identity (B) legacy run-manifest normalization count: expected 1, observed 0
```

The failures reproduce entirely against pre-existing 6.37/schema-1 fixture
identities; this branch does not modify those fixtures or the native
differential tests. The same selected runs pass their recorder unit/publication
cases and the AIZ/HCZ schema-2 timing gates. These baseline compatibility gates
require a separate legacy-fixture policy correction or regeneration and do not
invalidate the independently hashed 6.38/schema-2 publication.
