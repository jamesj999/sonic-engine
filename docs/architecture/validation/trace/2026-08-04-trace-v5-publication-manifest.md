# Trace v5 publication manifest

This is the deterministic record of the v5 fixture-tree transaction. It is
paired with the installed inventory aggregate
`04851c0a146eeb101a0ce0d76c78ba9c861a4eb3d6c9ff50612c84112d868790`.

## Protected predecessor archive

The eight S1 credits directories were moved, not deleted. Each directory's
three archived files (`aux_state.jsonl`, `metadata.json`, `physics.csv`) is
preserved under `2026-08-04-s1-credits-predecessor/`. The archive contains 24
files; their SHA-256 values are verified by the following deterministic
command from the repository root:

```text
find docs/architecture/validation/trace/2026-08-04-s1-credits-predecessor \
  -type f -print0 | sort -z | xargs -0 sha256sum
```

The protected directories are `credits_00_ghz1`, `credits_01_mz2`,
`credits_02_syz3`, `credits_03_lz3`, `credits_04_slz3`, `credits_05_sbz1`,
`credits_06_sbz2`, and `credits_07_ghz1b`.

The stored and logical hashes are identical because the archive preserves the
raw predecessor files without an additional compression layer. The publication
guard checks both the table entries and the bytes on disk.

| archive path | stored_sha256 | logical_sha256 |
| --- | --- | --- |
| `credits_00_ghz1/aux_state.jsonl` | `419c3875ffcff5157a8c1df7703faf07d6fd7a3330193f24bffa64bfb2f73809` | `419c3875ffcff5157a8c1df7703faf07d6fd7a3330193f24bffa64bfb2f73809` |
| `credits_00_ghz1/metadata.json` | `fc29c74edaeabb2944e31fc2375a1461d29dde7dd8af4f359c4651da0e882441` | `fc29c74edaeabb2944e31fc2375a1461d29dde7dd8af4f359c4651da0e882441` |
| `credits_00_ghz1/physics.csv` | `6cb9346fd919c697b295b8b6ba3d7c827ef1fd49f0ea8a44d2bd907942322117` | `6cb9346fd919c697b295b8b6ba3d7c827ef1fd49f0ea8a44d2bd907942322117` |
| `credits_01_mz2/aux_state.jsonl` | `2367a5459edc46d2677bade2c9e692f1dd65ad2dbb0222b82f7b27be4cb6d6f7` | `2367a5459edc46d2677bade2c9e692f1dd65ad2dbb0222b82f7b27be4cb6d6f7` |
| `credits_01_mz2/metadata.json` | `ef1b94f7b432b84af5affecfce6180249f14ed95b03d87418550f71e9a48ff76` | `ef1b94f7b432b84af5affecfce6180249f14ed95b03d87418550f71e9a48ff76` |
| `credits_01_mz2/physics.csv` | `0e4c2c7bf098ec8996300c4ee324c60b56eb30962e974d8e5a793dc420dc2ea3` | `0e4c2c7bf098ec8996300c4ee324c60b56eb30962e974d8e5a793dc420dc2ea3` |
| `credits_02_syz3/aux_state.jsonl` | `52408b3e7f8bb6812e6e190c7b1bc8dda5959a817478c77f013f33d148f3d737` | `52408b3e7f8bb6812e6e190c7b1bc8dda5959a817478c77f013f33d148f3d737` |
| `credits_02_syz3/metadata.json` | `a8ea841704d977a377c1526edb8b0be70c9f0f631abbb2552689663b71242350` | `a8ea841704d977a377c1526edb8b0be70c9f0f631abbb2552689663b71242350` |
| `credits_02_syz3/physics.csv` | `fe17d4fdc1bc0bf98d4e5de7847b77aad832157717099024587365cb2478312a` | `fe17d4fdc1bc0bf98d4e5de7847b77aad832157717099024587365cb2478312a` |
| `credits_03_lz3/aux_state.jsonl` | `318a1ca0aae7d8ee699926b492351d904f892b9b4b9b477211e02be5dc26d81c` | `318a1ca0aae7d8ee699926b492351d904f892b9b4b9b477211e02be5dc26d81c` |
| `credits_03_lz3/metadata.json` | `bd63115e1f63be11e3065e6c331d3b387300afdf3c9ae94d85fccd5f81663c8e` | `bd63115e1f63be11e3065e6c331d3b387300afdf3c9ae94d85fccd5f81663c8e` |
| `credits_03_lz3/physics.csv` | `b0b2d01d7ab5676d25159e90cb20071294cbbe83306e1a3112f6cbff4fa4be23` | `b0b2d01d7ab5676d25159e90cb20071294cbbe83306e1a3112f6cbff4fa4be23` |
| `credits_04_slz3/aux_state.jsonl` | `f103f8065a60f4795bfa64a8622689533135d84f7720669f51c85a5910c339d8` | `f103f8065a60f4795bfa64a8622689533135d84f7720669f51c85a5910c339d8` |
| `credits_04_slz3/metadata.json` | `d6d005cb9a973c77876f45f77d56872ed67af190a1712f2f1a195568b8b0de5c` | `d6d005cb9a973c77876f45f77d56872ed67af190a1712f2f1a195568b8b0de5c` |
| `credits_04_slz3/physics.csv` | `6c9112aa09769404405b47c7ca30e3884916d0524a31f2fbbf3bd6a3be0c2642` | `6c9112aa09769404405b47c7ca30e3884916d0524a31f2fbbf3bd6a3be0c2642` |
| `credits_05_sbz1/aux_state.jsonl` | `81ed2f1ec3e18bc6ff0788cd490e985a164c4e15dcfec48d9a0ebf8448565130` | `81ed2f1ec3e18bc6ff0788cd490e985a164c4e15dcfec48d9a0ebf8448565130` |
| `credits_05_sbz1/metadata.json` | `c66b3d9f5491c37902139e2c1c980bb86e78f1a5d190e5bc7c1c932b0994093a` | `c66b3d9f5491c37902139e2c1c980bb86e78f1a5d190e5bc7c1c932b0994093a` |
| `credits_05_sbz1/physics.csv` | `c30f6a9746a80cbfe0a95013229bca3f620ead5f24f1fa37ff9fefd7845fa929` | `c30f6a9746a80cbfe0a95013229bca3f620ead5f24f1fa37ff9fefd7845fa929` |
| `credits_06_sbz2/aux_state.jsonl` | `a61490bbd35ec2f8debdde06bad633e9e31cd13e54becb6af7c65cc76d1bd31b` | `a61490bbd35ec2f8debdde06bad633e9e31cd13e54becb6af7c65cc76d1bd31b` |
| `credits_06_sbz2/metadata.json` | `12f58ef34b480df3c32f334e04ddf84f6a1200a00ff7ce17e3db03ed81097fab` | `12f58ef34b480df3c32f334e04ddf84f6a1200a00ff7ce17e3db03ed81097fab` |
| `credits_06_sbz2/physics.csv` | `873b2ae1019189e36c1817fafdcdd9e04ea7b7f3cb5f0a2c18cf1a7a5059e3b6` | `873b2ae1019189e36c1817fafdcdd9e04ea7b7f3cb5f0a2c18cf1a7a5059e3b6` |
| `credits_07_ghz1b/aux_state.jsonl` | `f2197dd3fce29ae0b21e6536e81c5fce29b2950ae32b5a6ddaff264279379fdb` | `f2197dd3fce29ae0b21e6536e81c5fce29b2950ae32b5a6ddaff264279379fdb` |
| `credits_07_ghz1b/metadata.json` | `8b034618a9fc78d515e8fe10e94f39d342902fb6ad1752dcb1740bba4fccf81f` | `8b034618a9fc78d515e8fe10e94f39d342902fb6ad1752dcb1740bba4fccf81f` |
| `credits_07_ghz1b/physics.csv` | `5b1e1d47a351f19fdfe57a1b80928bce30bfaec7435882b76e637d9baf6fb7ec` | `5b1e1d47a351f19fdfe57a1b80928bce30bfaec7435882b76e637d9baf6fb7ec` |

## Rename

| action | old path | new path |
| --- | --- | --- |
| rename (metadata identity only) | `src/test/resources/traces/s3k/ending_completerun/metadata.json` | `src/test/resources/traces/s3k/hpz22_completerun/metadata.json` |

## True deletions

These paths are obsolete and are not represented by a compatibility reader:

```text
src/test/resources/traces/s1/ghz1_fullrun/aux_state_retro.jsonl
src/test/resources/traces/s1/ghz1_fullrun/aux_state_retro.jsonl.gz
src/test/resources/traces/s1/ghz1_fullrun/metadata_retro.json
src/test/resources/traces/s1/ghz1_fullrun/physics_retro.csv
src/test/resources/traces/s1/mz1_fullrun/aux_state_retro.jsonl
src/test/resources/traces/s1/mz1_fullrun/aux_state_retro.jsonl.gz
src/test/resources/traces/s1/mz1_fullrun/metadata_retro.json
src/test/resources/traces/s1/mz1_fullrun/physics_retro.csv
src/test/resources/traces/s3k/ending_completerun/aux_state.jsonl.gz
src/test/resources/traces/s3k/ending_completerun/hardware_timing.jsonl
src/test/resources/traces/s3k/ending_completerun/physics.csv.gz
src/test/resources/traces/synthetic/basic_3frames/aux_state.jsonl
src/test/resources/traces/synthetic/basic_3frames/metadata.json
src/test/resources/traces/synthetic/basic_3frames/physics.csv
src/test/resources/traces/synthetic/execution_v3_2frames/metadata.json
src/test/resources/traces/synthetic/execution_v3_2frames/physics.csv
src/test/resources/traces/synthetic/run_aiz_gumball_3seg/run_manifest.json
src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg00_aiz/metadata.json
src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg00_aiz/physics.csv
src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg01_gumball/metadata.json
src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg01_gumball/physics.csv
src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg02_aiz/metadata.json
src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg02_aiz/physics.csv
src/test/resources/traces/synthetic/run_ehz_ss_3seg/run_manifest.json
src/test/resources/traces/synthetic/run_ehz_ss_3seg/seg1_ehz1/metadata.json
src/test/resources/traces/synthetic/run_ehz_ss_3seg/seg1_ehz1/physics.csv
src/test/resources/traces/synthetic/run_ehz_ss_3seg/seg2_ehz1/metadata.json
src/test/resources/traces/synthetic/run_ehz_ss_3seg/seg2_ehz1/physics.csv
src/test/resources/traces/synthetic/run_ehz_ss_3seg/ss/metadata.json
src/test/resources/traces/synthetic/run_ehz_ss_3seg/ss/physics.csv
src/test/resources/traces/synthetic/s2_execution_v3_2frames/aux_state.jsonl
src/test/resources/traces/synthetic/s2_execution_v3_2frames/metadata.json
src/test/resources/traces/synthetic/s2_execution_v3_2frames/physics.csv
src/test/resources/traces/synthetic/s3k_execution_v3_2frames/aux_state.jsonl
src/test/resources/traces/synthetic/s3k_execution_v3_2frames/metadata.json
src/test/resources/traces/synthetic/s3k_execution_v3_2frames/physics.csv
```

The publication check compares this exact path set to the predecessor diff. No
file under the protected S1 archive is included in this deletion set.
