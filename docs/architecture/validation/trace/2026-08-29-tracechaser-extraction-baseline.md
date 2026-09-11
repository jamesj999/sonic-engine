# TraceChaser extraction baseline

Date: 2026-08-29

Status: **Task 1 gate passed.** This record freezes the OpenGGF source boundary,
the complete path disposition, and the immutable external six-capture corpus
that Task 9 must reproduce before TraceChaser v0.1.0 can be published.

## Extraction trigger and repository boundary

The updated main workspace was on `develop` at
`8290558c48e26c896c80617b5783c9b6d9aa6f1b`; fetched
`origin/develop` resolved to the same object and `git pull --ff-only`
reported `Already up to date.`

The exact S2 native-recorder workflow branch tip is
`e3996d30237b14f77606eb6372374ea518d87e66`. Its direct merge is:

```text
e4619c4e870a84f05fbe2ec0fd6e71bfe53ddb97
Merge branch 'feature/ai-bizhawk-native-s2-recorder' into develop
parents: efee41... e3996d...
```

`git merge-base --is-ancestor
e4619c4e870a84f05fbe2ec0fd6e71bfe53ddb97 develop` returned exit 0.
`develop..feature/ai-bizhawk-native-s2-recorder` contained no commits.
`gh pr list --head feature/ai-bizhawk-native-s2-recorder --state all`
returned an empty list, so this was a direct merge and has no PR number.

The main workspace retained exactly these pre-existing user changes:

```text
 M docs/s1disasm
 M docs/s2disasm
 M docs/skdisasm
```

The inspected gitlink worktree values were `f6ece657...`,
`107cdf851...-dirty`, and `1a454a0e...-dirty`, respectively. Fetch and
the empty incoming diff proved the fast-forward could not touch them. No Task 1
command staged, reset, or modified a disassembly gitlink.

## Worktree inspection

Every entry from `git worktree list --porcelain` was inspected with its
branch/detached state, HEAD, exact `git status --short`, and
`git diff --name-status develop...HEAD --` over the candidate roots, named
Java/Lua tests, audio callers, and active docs/skills.

The worktrees and inspected HEADs were:

```text
OpenGGF                                  develop                                      8290558c48e26c896c80617b5783c9b6d9aa6f1b
code-cleanup                             bugfix/ai-code-cleanup                       e4663e62135ac9b4e1abfe81d9ee8ca707584ed1
fbz-blaster-controller-followup          bugfix/ai-fbz-blaster-controller-followup    2659e703c498fa1fb9c2acb8f5df78afbb331dd7
game-over                                feature/ai-game-over-flow                    1da9c63295a68608c33303cabc0d28119dd2dcf7
guide-docs                               bugfix/ai-guide-docs                         2b564c4937a5f9113918a9f88af8a597b5735f29
hygiene                                  bugfix/ai-hygiene                            b65880ea3fa084744954d16c9cb73c8abaca5b63
ledgers                                  bugfix/ai-ledgers                            ae4b9b7a5bc549e059a9926d18bca01b126397fb
next-widescreen-capture                  feature/ai-next-widescreen-capture           c80155eb3da3dadf75352039bee5376a851ea2aa
next-widescreen-scenes                   bugfix/ai-next-widescreen-scenes             2d6e8502302951de1a56496b12b48ecc902cec1a
next-widescreen-special-stages           feature/ai-next-widescreen-special-stages    ee406c74027ac8a9240cefeddd9cdc0dea09c4d6
next-widescreen-ui                       feature/ai-next-widescreen-ui                c2e3997802bd1cef4e5cdf5d3250eaa3aa10082a
psg-rewrite                              feature/ai-psg-clean-room                    a96ff897f6827b02748b6d91f405a71ce9045a9d
release-06-remediation                   bugfix/ai-release-06-remediation             0b364985db24b245c9b8115c19cc8dfc7052855a
release-docs                             bugfix/ai-release-docs                       671b345dcb767eb1fc1786f4062bdd5593f1bd27
s2-cpz2-release                          bugfix/ai-s2-cpz2-release                    3ca7e70e282ead4e9a2d545a2e7c9f84ea23d6b2
smps-transaction-parity                  feature/ai-smps-transaction-parity           83723907d5705aa4cab9bda850982f5c434b3ff7
suite-green                              bugfix/ai-suite-green                        9ce7094f7f92d8dd8a025a888cd1eb05c9d4dcea
tracechaser-extraction-design            feature/ai-tracechaser-extraction-design     081167cb9363f989b74d56e7551b3cce37a8017a
widescreen-capture-runtime               bugfix/ai-widescreen-capture-runtime         65ed0ea1b13adf39ac14984fd21812e1ce64b391
widescreen-capture-tool                  feature/ai-widescreen-capture-tool           c3dfca766cf58d81278866de704e6d9291cbabd7
widescreen-completion                    feature/ai-widescreen-completion             270911f7bbe15af2cda7a7f5aa7faeef7200cd00
widescreen-gl-ownership                  feature/ai-widescreen-gl-ownership           adc8a8e734d6d11ff20250560a193f23a32bd95c
widescreen-s1-special-stage              feature/ai-widescreen-s1-special-stage       d92fb8e5edefdf20df7f0b96456fa60aede64d80
widescreen-s2-bounded                    feature/ai-widescreen-s2-bounded             2cf282b6a79d7e06293854075d7f128db1f2037b
widescreen-s2-special-stage              feature/ai-widescreen-s2-special-stage       5ccad0fc94af9e280ba38f7937a6e917bce5bb65
widescreen-s3k-special-stage             feature/ai-widescreen-s3k-special-stage      f93736807c5fcdcb50bce74fbddfb766fbb7dcdb
widescreen-scene-aiz-tree                feature/ai-widescreen-scene-aiz-tree         cffa65df8e6a6a0d982d65334c4517db988373f1
widescreen-scene-aiz-vine                feature/ai-widescreen-scene-aiz-vine         4ec4c0de22f6fd41302c67d8dabba5e76b94e17f
widescreen-scene-icz-freezer             feature/ai-widescreen-scene-icz-freezer      9d50b48c4c6a8776ad113e3f1f10626a2f550701
widescreen-scene-s1-ghz                  feature/ai-widescreen-scene-s1-ghz           220e45188d520fe2be1e8420fef6d6bd602dace1
widescreen-scene-s2-window               feature/ai-widescreen-scene-s2-window        f63004d9348b2567a688fa53f0d6c8c8d3e4c8c9
ai-s2-seg19                              feature/ai-s2-arz2-bubble-inhale-edge        0d890561145452d89806e0c94e04d67d5b897f78
bounce-dispatch                          bugfix/ai-boss-defeat-slot                   bf148a668269c30af98ddf9eb28b59c6f2d1a80a
occ-arc                                  bugfix/ai-convert-in-place-design            ef794d39db3e7ba1b1d290823d5bc424f7a29fb1
octus-hover                              bugfix/ai-octus-hover-60                     be9e7cdfe7b229640a923e0e8ac325d1d67e5953
publishers                               bugfix/ai-publishers-census                  14b2e3df7707e8bc2aa6eaa067ecd33639e7c993
s2-bossexplosion                         bugfix/ai-s2-boss-explosion-occupancy         72bb5f4bc5aee8fc2acd0a35e05828b874a92b0b
s3k-aiz2-sidekick-r1                     bugfix/ai-s3k-aiz2-sidekick-f6000             ffa3c20e7ad52bd68b55bd0f74873e92b86c1da1
s3k-aiz5-sidekick                        bugfix/ai-s3k-aiz5-sidekick-x                 139fb4aedcecd9e3420cf64685090d4fcf554139
s3k-card-attrib                          detached                                     60a8f02f1ed5b84f8d192b04838f0d8c0a836485
touch-oracle-r2                          bugfix/ai-touch-oracle-r2                    9f5a503e427c4297c2ad2e61fc877cd3bb338dee
```

The only committed broad candidate-root branch was
`feature/ai-smps-transaction-parity`; develop commit `b4c8fbd8...`
explicitly defers that SMPS playback-authenticity programme to 0.7 while
retaining its measurement tooling, so it is not an extraction prerequisite.
The FBZ and widescreen worktrees add visual-capture support for the separate
0.7 widescreen programme. They do not modify the six reviewed native recorder
paths used here. All other diffs were engine, object, release-documentation, or
trace-consumer work. No relevant committed extraction dependency was found
outside develop.

Dirty worktrees were inspected rather than ignored. Besides the main gitlinks,
`s2-cpz2-release` contained five release/status documents,
`octus-hover` contained one Sonic 2 badnik source edit, and detached
`s3k-card-attrib` contained five engine/title-card/trace-consumer edits.
None is a moved producer or active extraction dependency.

## Versioned path inventory

The adjacent TSV contains 532 unique data rows with the required nine columns.
It classifies all 346 tracked files below the four candidate roots, every named
Python/Java/Lua/audio contract, `LICENSE`, the normalization contract, and
every tracked file containing an old-root reference.

History-filter actions are deliberately distinct from final OpenGGF cutover
actions. Task 2 reads only `history_disposition` and `history_new_path`; it
cannot consume a conceptual test replacement or a later forwarder decision.
Counts are:

| Phase / disposition | Count |
| --- | ---: |
| history `move` | 352 |
| history `exclude` | 180 |
| cutover `delete` | 340 |
| cutover `forwarder` | 12 |
| cutover `retain` | 59 |
| cutover `historical-reference` | 110 |
| cutover `delete-after-task-5` | 9 |
| cutover `split-after-task-5` | 2 |

Automated checks found zero duplicate paths, zero duplicate history
destinations, zero missing candidate-root paths, and zero unclassified tracked
reference files. All Java contract tests are history-excluded OpenGGF inputs;
the eleven replacement/split actions are gated on Task 5 proof. The twelve
public run/test/Lua/validation/comparison/compression/matrix paths are
individually named forwarders. The two generated diagnostics are history
excluded and cutover-deleted. The four
`tools/testing/test_*trace_v5*.py` files, both hook installers, and both
Surefire tools have explicit individual dispositions.

## Immutable capture freeze

The historical 2026-08-04 publication freeze remains an exact-artifact policy.
Its verifier was corrected to use immutable base
`36be0aa44e4e1db9d2d586fff984e52ffd4fe053`, without changing its source
commit or expected hash.

The extraction-specific matrix is
`2026-08-29-tracechaser-extraction-capture-matrix.json`:

- policy: `extraction-build-test-v1`;
- source commit: `41828f10998f531e614d855c858ba1b26429d757`;
- immutable diff base: `081167cb9363f989b74d56e7551b3cce37a8017a`;
- diff SHA-256:
  `4238071a54cb4e23b2b19b63a05bf6ed57c535f61a0cd18ae9b34cc44be75b90`;
- current fixture inventory: 1,519 files, aggregate
  `ecf9506d175a6b336e87e9baffb0fb0262a8821c9dc38d696dc985b518259ed9`;
- Mono: `6.12.0`; xbuild: `14.0`; Roslyn:
  `3.9.0-6.21124.20 (db94f4cc)`;
- Roslyn compiler SHA-256:
  `81e98ade50f3e4127237128211778bd6ebe0c3998c9cc2f5eb44f3196a0297f8`.

The policy checks the immutable source diff, exact toolchain, official BizHawk
2.11 archive/source locks, eleven exact runtime inputs, both required
`client.invisibleemulation` capability witnesses, a clean archive build, 150
selected capture-relevant native contracts, all ROM/movie hashes, and the
current fixture inventory. The deterministic compiler maps both ordinary and
space-containing checkouts to the canonical source path, so all four EXE/PDB
identities are release authority:

| Artifact | Size | Required SHA-256 |
| --- | ---: | --- |
| `BizHawk.Headless.Gpgx.exe` | 537,600 | `5811e3103ac64a26c56e9ae08775ec0beab3ddf02be6b20ef31113abbb5cd316` |
| `BizHawk.Headless.Gpgx.pdb` | 157,796 | `ac213d7c55316b63001b34a0c72e727f95767151ff6f2c75f5a2260aa526a0f7` |
| `BizHawk.Headless.Gpgx.Tests.exe` | 1,024,000 | `0e65176bb80e3fbee699a8bc4174a9adc62c6359e9773e8495fe68515a738947` |
| `BizHawk.Headless.Gpgx.Tests.pdb` | 241,468 | `2ef3337c4b54734b16eddf1a2a0149839842d5aedeac55bb8907ad0610fa2f63` |

The official Linux archive SHA-256 is
`cdaf9650d880bae660d63a388430f630b8d8a96b1ba59ebf0e0195a645c3bab8`.
The installer-lock SHA-256 is
`8dadc9cad103b30155a72a948e81b360753f867cd35978e3e16cd115de25d85d`;
the native source-lock SHA-256 is
`348ffd29471bcd9a0f452b6112a7bed941f0dd43f364a315c5cbd68f918205f9`.
An arbitrary `BIZHAWK_HOME` is rejected before build if any locked runtime
input or capability witness differs.

The preflight ran in the fresh, previously absent root:

```text
OPENGGF_MAIN_ROOT=<absolute OpenGGF main workspace>
OPENGGF_REPOSITORY_ROOT=<absolute extraction-source checkout>
TRACECHASER_WORK_ROOT=<fresh absolute external task root>
TRACECHASER_EVIDENCE_ROOT=$TRACECHASER_WORK_ROOT/evidence
batch=$TRACECHASER_EVIDENCE_ROOT/pre-extraction
candidate=$TRACECHASER_WORK_ROOT/task9-candidate
```

The candidate and all six output roots were absent. Available capacity was
471,703,277,568 bytes against 5,125,000,000 required bytes. Matrix preflight
passed, and matrix expansion wrote exactly six commands. The ledger SHA-256 is
`dca236caf2a46e626b09f0f0c4935e2d32221368a2305740dab4887396afc84b`;
the complete capture log SHA-256 is
`40a5ef17a99b43bcab37e0a36f943d2cdeea31d5ef57d47d7dee21959802433c`.

Review preflight re-proved the locked build in a second fresh root. All 150
selected tests and all four deterministic artifact gates passed; all six output
roots and the Task 9 candidate were absent, with 469,109,690,368 bytes
available against 5,125,000,000 required.

## ROM and movie identities

| Game | Absolute ROM | SHA-1 | SHA-256 |
| --- | --- | --- | --- |
| S1 | `$S1_ROM_PATH` (absolute, discovered below `$OPENGGF_MAIN_ROOT`) | `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` | `1b7f6635bd713f37f3c2f44f302b872c2e3c5f56e63637918dad4637146900fd` |
| S2 | `$S2_ROM_PATH` (absolute, discovered below `$OPENGGF_MAIN_ROOT`) | `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` | `193bc4064ce0daf27ea9e908ed246d87ec576cc294833badebb590b6ad8e8f6b` |
| S3K | `$S3K_ROM_PATH` (absolute, discovered below `$OPENGGF_MAIN_ROOT`) | `CFBF98C36C776677290A872547AC47C53D2761D6` | `fba0677fde9f76df93f3e98d6310d8af68b9847bde16e253d73cd4dd8134ed23` |

| Capture | BK2 path below `src/test/resources/traces` | BK2 SHA-256 |
| --- | --- | --- |
| `s1-ghz1` | `s1/ghz1_fullrun/ghz1_fullrun.bk2` | `dced61b2d3a3346b2ecd62254140497ef2827374c1de8597780f91e39ca0dcea` |
| `s1-emeralds-run` | `s1/runs/s1-sonic-complete-withemeralds/sonic1-complete-withemeralds.bk2` | `f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b` |
| `s2-ehz1` | `s2/ehz1_fullrun/s2-ehz1.bk2` | `db310fa5e70a3cbaca4bafb06d98509894df920e4ab267d3e22db3f530104eed` |
| `s2-emeralds-run` | `s2/runs/s2-sonic-tails-complete-emeralds/sonic-2-sonic-tails-complete-emeralds.bk2` | `e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5` |
| `s3k-aiz` | `s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2` | `6837de0f67db7eb68f20b6f6df6a2872713a613d8b4dbc804847209c16b56e97` |
| `s3k-complete` | `s3k/_movies/s3k-complete-sonic-tails.bk2` | `82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf` |

No BK2 or ROM was copied.

## Literal argument ledger

The adjacent tracked
`2026-08-29-tracechaser-extraction-argv-ledger.json` is the exact ordered argv
authority. Its canonical command-array SHA-256 is
`46d639e8b91ea59c3c236d99876163e3b48b833949b69390aab3c529eae4070b`.
Each `${NAME}` token is prefix-substituted with the required absolute root as
raw UTF-8 and the resulting ordered strings are passed directly to process
creation without joining or shell reparsing. This reconstructs every argument
byte from the tracked base while keeping machine-local prefixes out of Git.
The shell rendering below is informative; the JSON argv arrays are authority.

```bash
"$OPENGGF_REPOSITORY_ROOT/tools/bizhawk-headless/run.sh" --mode trace --rom "$S1_ROM_PATH" --movie "$OPENGGF_REPOSITORY_ROOT/src/test/resources/traces/s1/ghz1_fullrun/ghz1_fullrun.bk2" --output "$TRACECHASER_EVIDENCE_ROOT/pre-extraction/captures/s1-ghz1"
"$OPENGGF_REPOSITORY_ROOT/tools/bizhawk-headless/run.sh" --mode trace --rom "$S1_ROM_PATH" --movie "$OPENGGF_REPOSITORY_ROOT/src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/sonic1-complete-withemeralds.bk2" --output "$TRACECHASER_EVIDENCE_ROOT/pre-extraction/captures/s1-emeralds-run" --run-id s1-sonic-complete-withemeralds
"$OPENGGF_REPOSITORY_ROOT/tools/bizhawk-headless/run.sh" --mode trace --rom "$S2_ROM_PATH" --movie "$OPENGGF_REPOSITORY_ROOT/src/test/resources/traces/s2/ehz1_fullrun/s2-ehz1.bk2" --output "$TRACECHASER_EVIDENCE_ROOT/pre-extraction/captures/s2-ehz1" --trace-profile gameplay_unlock
"$OPENGGF_REPOSITORY_ROOT/tools/bizhawk-headless/run.sh" --mode trace --rom "$S2_ROM_PATH" --movie "$OPENGGF_REPOSITORY_ROOT/src/test/resources/traces/s2/runs/s2-sonic-tails-complete-emeralds/sonic-2-sonic-tails-complete-emeralds.bk2" --output "$TRACECHASER_EVIDENCE_ROOT/pre-extraction/captures/s2-emeralds-run" --run-id s2-sonic-tails-complete-emeralds
"$OPENGGF_REPOSITORY_ROOT/tools/bizhawk-headless/run.sh" --mode trace --rom "$S3K_ROM_PATH" --movie "$OPENGGF_REPOSITORY_ROOT/src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2" --output "$TRACECHASER_EVIDENCE_ROOT/pre-extraction/captures/s3k-aiz" --trace-profile aiz_end_to_end --load-queue-state
"$OPENGGF_REPOSITORY_ROOT/tools/bizhawk-headless/run.sh" --mode trace --rom "$S3K_ROM_PATH" --movie "$OPENGGF_REPOSITORY_ROOT/src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2" --output "$TRACECHASER_EVIDENCE_ROOT/pre-extraction/captures/s3k-complete" --trace-profile complete_run --load-queue-state
```

## Frozen output corpus

Every root passed `tools/traces/validate_trace_v5.py`. Physics rows exclude
the CSV header. Auxiliary and timing counts are JSONL event counts across all
segments. The deterministic inventory's `files` array is the authoritative
sorted member ordering and records every stored SHA-256 plus every applicable
logical decompressed SHA-256.

| Capture | Segments | Physics rows | Aux rows | Timing rows | Members | Stored bytes | Inventory aggregate SHA-256 | Inventory artifact SHA-256 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `s1-ghz1` | 1 | 3,905 | 34,548 | 7 | 4 | 899,223 | `6d06e8f531e5f5a773154c522eb8e54cff33f32e78e3ff07798b8ae323feb01f` | `75c9fa3852f28bf12c0431c49d5966824527056c972ce22179893f57ec6c5ee1` |
| `s1-emeralds-run` | 34 | 208,586 | 2,755,825 | 242 | 131 | 54,632,903 | `617a54ee1e5a25ecb12a7d68cc93fca8d9a33eba15319c7a0cb562a56f7b8845` | `05bb3266c8b48782667b1a4caab9d293768bf9f4c30bde22f845ef8311a5d042` |
| `s2-ehz1` | 1 | 5,852 | 53,536 | 0 | 3 | 1,647,512 | `f622e91679b2d258f5278cdf157f99ed1982983db695ad7252ec0df5daebeaba` | `eb5be205e99296bc2338a1ffb4d28dd3059fd7c680b9980681343b8bfaff9c24` |
| `s2-emeralds-run` | 35 | 239,476 | 2,302,051 | 0 | 106 | 48,805,190 | `2ed5810d23bd0c704f8177a4f78a6e8290df88a984c977f653b0d4f9446de429` | `fb5dc7efb0507a70547dd130e881bad982bccb2c89b6d3fd193dd5147a176bdd` |
| `s3k-aiz` | 1 | 20,798 | 640,530 | 95 | 4 | 6,753,859 | `e8f96a768d157694509d38bdf18f8b488301967e665c7d43bd21469ed3df3767` | `1303bd7c800ebd73541d65419f0e27295f10392cc03e61993e1871c9bf72b0f0` |
| `s3k-complete` | 15 | 465,378 | 14,000,426 | 1,098 | 61 | 152,659,597 | `9643a9f69a62b67fc6f9f7defb2575955a420eb2c73ee5e3ef77cc5e5adb4a91` | `28ec45fd11b36dcd592c8f38155e8ac53c1258706d809ef3692bf78cd465db5b` |

The inventories are immutable external artifacts at
`TRACECHASER_EVIDENCE_ROOT/pre-extraction/inventories/<capture-id>.json`.
They are outside both repositories and outside every capture root.

The strict validator now opens the field-disjoint run-root
`hardware_timing_interstitial.jsonl` companion. For `s3k-complete` it validated
1,087 per-segment timing events plus all 11 interstitial events, preserving the
recorded total of 1,098. It enforces exact grammar, boundary ordering/name
stability, unique/increasing per-kind ordinals, and contiguous within-boundary
spans; interstitial fields cannot be accepted as per-segment timing fields.

Task 9 must compare a fresh standalone reproduction with:

```bash
python3 tools/traces/compare_trace_v5_candidates.py \
  --mode v5-literal \
  --fail-on-difference \
  --output "$TRACECHASER_EVIDENCE_ROOT/task9-six-capture-comparison.json" \
  "$TRACECHASER_EVIDENCE_ROOT/pre-extraction/captures" \
  "$TRACECHASER_WORK_ROOT/task9-candidate/captures"
```

Task 9 must also regenerate each inventory and compare the artifact SHA-256,
aggregate SHA-256, row counts, sidecar counts, and exact sorted `files` array.
Any unexplained byte or semantic difference blocks release.

## Test baseline

`mvn -v` reported Maven 3.9.16 on Java 21.0.11.

| Location / tier | Outcome |
| --- | --- |
| updated main `develop`, ordinary suite with all three absolute ROMs | 14,896 run; 0 failures; 0 errors; 36 skipped; BUILD SUCCESS (03:19) |
| updated main `develop`, `mvn -Dmse=off -Pguards test -B` | 550 run; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS (01:59) |
| feature before Task 1 corrections, same ordinary suite | 14,896 run; 0 failures; 0 errors; 36 skipped; BUILD SUCCESS (03:25) |
| feature before Task 1 corrections, same guards | 550 run; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS (01:55) |
| extraction matrix clean-build selected native contracts | 150 passed; 0 failed; 0 skipped |
| two-path deterministic EXE/PDB guard after review | PASS; both paths byte-identical; 24 smoke assertions passed |
| four trace-v5 Python modules after review | 64 tests; OK (72.616 s) |
| Java/Lua producer, audio, and PLC focused selector | 79 run; 0 failures; 0 errors; 3 skipped; BUILD SUCCESS (13.543 s) |
| full current native `--no-gates`, all ROMs, writable task temp | 653 tests: 636 passed; 14 failed; 3 skipped |

The focused Maven selector was:

```text
TestBizhawkProbeContractGuard,TestTraceAnimationRecorderContract,
TestTraceRecorderCounterAddresses,S2SpecialStageRecorderContractTest,
TestPlcTimingEvidenceTool,TestCompleteRunAudioCutoffFrontier,
TestS1CompleteRunLuaContract,TestS1CompleteRunProbeContract,
TestS2CompleteRunRealRow769DecodeGate,TestS3kCompleteRunRealRow810DecodeGate,
TestS1AudioParityLuaContract,TestS1AudioParityProbeContract,
TestS1GameplayAudioTimelineLuaContract,TestS1Ghz1GameplayAudioProbeContract,
TestSonic1PlcArmTiming,TestS1S2PlcComparisonOnlyGuard
```

The three focused skips were the two explicitly opt-in real-row decode gates
and one fixture-availability animation assertion. The ordinary-suite skip count
of 36 is the Task 11 comparison authority.

The full native tier is a recorded pre-existing red baseline, not the extraction
release authority. Its 14 failures are: six path-dependent S2 audio capability
identity consumers, missing/ambient GPGX audio source-install inputs, the
run-script clean-output assumption, one stale standalone S2 special-stage row
expectation, one stale S2 run aux input-frame expectation, and three Lua-vector
scratch writes that target a read-only source/archive location. No native
recorder source differs between the reviewed source commit and the final Task 1
worktree. The extraction policy's 150 capture-relevant tests and all six real
captures passed.

## Corrections made before freezing evidence

These were committed separately from this evidence:

- `41828f10998f531e614d855c858ba1b26429d757`: immutable base for the
  historical verifier;
- `0a0025042521030204737e1def8e8df90f8002b6`: extraction-specific
  build/test freeze and current fixture inventory;
- `e2cfdbae57eb9a073bafd72c4ed1d11b639befbb`: matrix expansion uses the
  verified absolute ROM environment paths;
- `717c6ff6eb0d77198ac866d489b27ee2539583e7`: the v5 validator recognizes
  the already-landed S1 `nemesis_plc_queue` kind and enforces its existing
  `pre_main_loop` boundary;
- `a8bbcbbebcfe7bad39294066e38a39eba6983940`: exact deterministic EXE/PDB
  and BizHawk 2.11 input gates, strict run-root interstitial timing validation,
  and the tracked normalized argv authority requested by Task 1 review.

The review correction changed the verifier, validator, deterministic-build
smoke selector, and evidence documents only. It did not change recorder
behaviour, trace schema, canonical fixtures, or any expected capture hash, so
the six outputs were revalidated rather than rerecorded.

The `TRACECHASER_EXTRACTION_BASE` is the 40-character commit containing this
baseline and the adjacent inventory. It is exported immediately after that
commit and recorded in the SDD progress ledger and implementer report.
