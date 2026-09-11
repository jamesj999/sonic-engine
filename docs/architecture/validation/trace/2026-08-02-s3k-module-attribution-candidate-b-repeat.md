# S3K module-attribution Candidate B / Repeat validation

**Status:** explicitly approved and installed byte-for-byte
**Approval manifest commit:** `f7827cb1f`
**Conductor commit:** `77965d4f2`
**Recorder:** `6.42-s3k-completerun`
**ROM SHA-1:** `CFBF98C36C776677290A872547AC47C53D2761D6`
**Movie:** `s3k-complete-sonic-tails.bk2`, 466,334 frames

## Capture result

Two independent uncompressed native captures completed with BizHawk 2.11.0.0.
Each produced the same 15 canonical segments, zero transitions, and 60 files.
`diff -qr` found Candidate B and Repeat byte-identical across all 60 files.

Against the committed fixture tree:

- all 15 `physics.csv` streams are decompressed-byte-identical;
- all 15 `aux_state.jsonl` streams are decompressed-byte-identical;
- all 15 metadata files differ only in `recording_date` (`2026-08-01` to
  `2026-08-02`) and `lua_script_version` (`6.40-s3k-completerun` to
  `6.42-s3k-completerun`);
- 14 timing files contain 27 total in-place substitutions whose sole semantic
  change is `"boundary":"vint_service"` to
  `"boundary":"post_objects"`;
- the ending timing file is byte-identical.

The first exhaustive comparator deliberately pinned the stale aggregate of 25
and failed after all per-segment checks passed. The per-segment counts sum to
27. Commit `77965d4f2` adds the independently reviewed aggregate contract; its
negative reports actual 27 / expected 25 and its positive pins 27.

## Frozen install manifest

`metadata` and `timing` columns are `byte length / SHA-256` from Candidate B;
Repeat has the exact same bytes.

| capture segment | committed fixture | timing changes | metadata | timing |
|---|---|---:|---|---|
| `aiz` | `aiz_completerun` | 3 | `1638 / 8d718989c06adecd92df11076116cbc8134d6cdbe36aaddce5697a8e05a02749` | `23788 / b8ebb4662c7361984e21541824166fbd597970171eed5025b6fdadbee6b4df24` |
| `hcz` | `hcz_completerun` | 2 | `1640 / 319629e38ed021439f35689cfc402d9a5a96bfe76b56d581d9def1dfe9ec228a` | `22429 / f055e4863d0048dd5143d353ad5946544a09da9d14325fe0fdf113a3d002a811` |
| `mgz` | `mgz_completerun` | 1 | `1640 / 590dcbe59d7fa1e1e82a9920457364a2388dd4884c90be571daa731f67257147` | `23274 / e87d25f5778461ada46fc52ef84da722f864cfb3440ac282f561a08b84ad1f8c` |
| `cnz` | `cnz_completerun` | 2 | `1640 / 1451e563141cdcd6b414db7b6129abfcf0f105c610db466da36140d9f61b2f3a` | `15377 / cb0d1ddc860f6984654ee0a9ed794a100733ffe11002facde19592724a9a91af` |
| `icz` | `icz_completerun` | 2 | `1641 / 28fe19fce47c2a01ceedc579011bb44961d3b2a99870ae23b52a3826765f4476` | `14531 / 4d6fd592d37b07ecce4462483b18ca5446a8f444ab98f7a110929af35410ee6e` |
| `lbz` | `lbz_completerun` | 2 | `1641 / 549a65d1ed74d856f06ebb7c5305c56a82f77ab7ec1666e2c478f52f25429f83` | `21016 / a1cce9f42ec0e7b164b2cf002a2f225ee55bf00b367e7f209537be2862d9fe6b` |
| `mhz` | `mhz_completerun` | 1 | `1641 / 01e41e7c2f56138b2e45a01c65fe03bd4ff0734f954e56be6987a7d4b262f425` | `20309 / a015ea48eb6c3068e9f1c2bdc706aeeadf5e38e02f5386b8d326b6b94deef415` |
| `fbz` | `fbz_completerun` | 2 | `1641 / dee5d6318adc86065586587a9e62212750e4d7e9b346fab07d6beadf7ad49573` | `16060 / c7513a1399cbd5b99b0136a03641e11e7fccffff93316fba9074ebdc2001d7c2` |
| `soz` | `soz_completerun` | 2 | `1641 / 970c00210e278c0affd1b5bc5d12ecf6fc676bafc95025cc73e9ec135a9254c8` | `22334 / c4b80609ead1d6d9e5d7365de8357679fd0df30401e99f0ee00e92da74a038a8` |
| `lrz` | `lrz_completerun` | 2 | `1641 / 190c01ceaae1b17bf83e9a82e7ed3a197f16d67c9a3bfa5629a637b90bee026f` | `17422 / 6f9bf1ce3bd9cfd99c90ae613bda78c1148ed1849e87b39f356e0c6e8e6f5c6c` |
| `hpz22` | `hpz_completerun` | 4 | `1645 / f7ac7b4946026e78d2e95607fb3e33f44766768f6da1d9470b2cf88be376f347` | `14268 / fb894bc710be8b8e28ef9ed7078cc5be3c95d205be39d1d906a5a68cfa7fc264` |
| `hpz` | `ssz_completerun` | 2 | `1643 / 948b11128c597295b73cd486102733aabdac6cf07fa5bf27c84ed8147e8393f5` | `14503 / 84565e6b08321137e5b175511a4a88f8ba8374839391ff4db0f75c96f07e0179` |
| `ssz` | `dez_completerun` | 1 | `1643 / 2e744eb9331f862f6c3ff9a502bf216ce39310086bdc75d25c7d01e8773bf18e` | `11174 / 391d1ad57d44156f9f3e6a84a71d241260183ef9bf85fc4eb4e575db696a4528` |
| `dez23` | `ddz_completerun` | 1 | `1644 / 9239a2fbad626056523e3a02c786dfc1e18965c1e59e95ce9947d62de36c1fc2` | `3785 / 56c6b5d3bc2ff996154d21f41520d5a86e7f4b58453714ece5b06d58c06989f8` |
| `ddz` | `ending_completerun` | 0 | `1641 / 24eb5cb16f5443105e5cdd40653632d19dbf0eb07833627d8009364bbc20a30a` | `2207 / f414d1a774ff97c012f626c08b1dd6a896c71719c386f6635abd20d7bb8dddec` |

## Approved publication transaction

The user explicitly approved the exact frozen bytes in this manifest. On
2026-08-03 the conductor installed exactly the 15 metadata files and 14 changed
timing files from Candidate B, for 29 installed files total. Repeat remained
validation-only and supplied no installed byte. Every compressed physics/aux
payload, all physics/aux decoded content, and the byte-identical ending timing
file remained untouched.

The post-install hash check re-read all 15 metadata files and all 15 timing
files: every length and SHA-256 matches the frozen table, and the modified
fixture-file inventory is exactly the approved 29 paths. The canonical native
gate now compares a fresh 6.42 capture directly with the installed 6.42 bytes;
the old 6.40 identities remain frozen as non-capture predecessor evidence.

## Post-install verification

The full 466,334-row native capture gate passed all fifteen canonical segments
against the installed fixture bytes. The complete native non-gate suite passed
516 tests with zero failures or skips; the focused bonus-gumball, AIZ, and HCZ
differential captures also passed. The Java publication, schema/loader,
compression, catalog/reference/provenance, schedule-compiler, authority,
replay-port, trace-data, and recording-driver selection passed 130 tests with
zero failures, errors, or skips.

Fresh complete-run replay installation remains intentionally fail-closed on
installed POST authority whenever its recorded row has no executable
object/POST phase: AIZ raw 6351/module `#16`, HCZ raw 31361/module `#82`, CNZ
raw 39940/module `#152`, and MHZ raw 28017/module `#256` all stop with
`unsupported-held-row-POST` before fixture or gameplay-session mutation. These
measured compiler frontiers are recorded in
`docs/status/trace-frontier-log.md`; publication does not synthesize the
missing CPU prefix.
