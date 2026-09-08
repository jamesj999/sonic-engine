# Next CI repair — 2026-09-08

The failing `next` push [34257297716](https://github.com/OpenGGF/OpenGGF/actions/runs/34257297716)
ran the smoke lane at `004b978d75ae899969269e95ffced09bd7e88f6d`. Its failure was
not a guards failure. `develop` at `8392494a0` passed both its observed push and
the local complete ordinary suite. The repair source is `7716b39f1`, comprising
the integration repairs in `110d7597b` and the subsequent asset-budget transaction fix.

## Configuration and cause

Both branches run smoke on pushes and full/guards on pull requests or manual
runs. Next also supplies its destination branch to the unpublished Mod API
policy. Its existing single reused 3 GiB fork is retained: the capacity evidence
in `pom.xml` explains why four 1 GiB forks are insufficient for its larger suite.
No lane, guard, assertion failure policy, or trace warning policy was disabled.

The manual develop trace job checked out develop and then invoked a validator
only present on next. It now checks out that single helper from the dispatched
workflow SHA separately, while all execution and report roots still target
develop. A YAML-parsed structural guard covers the checkout revision, sparse
path, ordering and invocation.

The push failures came from lost integration fixes and stale fixture contracts:

- Restore strict stock game resolution, four GameModule forwards, S2 initial
  X-only placement and the native CPZ live-position capture pass. The candidate
  Mod API pin includes the forwards and FBZ identity-transfer method.
- Resolve additive-zone music through its registry instead of treating an
  opaque mod level index as a stock array offset. Preserve injected CNZ terrain
  ownership, initialized FBZ cloud address slots and exact-transition rewind IDs.
- Make each bounded asset read a per-root reservation/rollback transaction.
  Partial reservations previously caused valid concurrent reads to reject one
  another. The controlled regression fails before the fix; the original
  exact-two-success test and all byte limits remain intact.
- Decode sample assets without POSIX `read` stripping base64 padding under dash.
  Keep sample packaging/registration/art coverage ROM-free; its separate
  gameplay/rewind/save test declares the S2 ROM dependency and uses the configured
  absolute ROM path. Other genuine ROM consumers declare their requirements.
- Update fixtures for production audio/input scheduling, apparent-act music,
  deferred startup dispatch, extra sidekicks and the rewind adapter inventory.
  FBZ route inventory remains a subset check, with exact all-factory coverage in
  the dedicated registry guard.
- Replace assertions about never-committed PowerShell files with checks of the
  tracked FBZ evidence amendment, cadence and publisher owners. The historical
  FBZ visual report remains failed; this does not certify its old capture groups.

## Baselines and execution

The baseline and final suite runs used Java 21 and fresh report directories.
ROM-backed runs passed these existing files explicitly. `${OPENGGF_ROOT}`
denotes the checkout root and `${CI_REPAIR_EVIDENCE}` the external task evidence
directory; raw logs retain the executed absolute paths:

```text
S1: ${OPENGGF_ROOT}/Sonic The Hedgehog (W) (REV01) [!].gen
S2: ${OPENGGF_ROOT}/Sonic The Hedgehog 2 (W) (REV01) [!].gen
S3K: ${OPENGGF_ROOT}/Sonic and Knuckles & Sonic 3 (W) [!].gen
```

Their verified CRC32 values are `AFE05EEE`, `7B905383`, and `63522553` respectively.
The ordinary command was `LUA_BIN=/usr/bin/lua5.4 mvn -Dmse=off test -B` with
all three absolute `sonic1.rom.path`, `sonic2.rom.path`, and `s3k.rom.path`
properties and a separate `openggf.surefire.reports` directory per run.

| Completed baseline | XML testcase occurrences | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| develop `8392494a0`, ordinary full | 17,040 | 0 | 0 | 24 |
| next `004b978d7`, ordinary full | 20,121 | 38 | 21 | 24 |
| next `004b978d7`, separate guards | 651 | 0 | 0 | 0 |

The next baseline has 56 failing identities and 59 failing occurrences because
one dynamic factory contributes four failures. Develop's suite headers report
16,947 while its XML contains 17,040 testcase occurrences; the table counts actual
case elements. The 24 skips are the same named optional/disabled/reference
capture cases in both branches; none is a missing stock ROM annotation skip.

Final verification uses a clean tracked-files-only local clone without ROMs or
worktree convenience links, `CI=true`, and dash as `sh` for Ubuntu sample-build
behavior. Commands are `mvn -Dmse=off -Psmoke test -B
-DmodApi.destinationBranch=next`, `mvn -Dmse=off test -B`, and a separate fresh-JVM
`LUA_BIN=/usr/bin/lua5.4 mvn -Dmse=off -Pguards test -B`, each with its own report
root. ROM-backed full runs use the same source and the explicit paths above.

A preliminary smoke run at `0a4f4fafb` found the missing candidate pin and the
undeclared Phase2 gameplay ROM dependency. Two other failures came from this
task's `JAVA_TOOL_OPTIONS` override, which the audio shell deliberately rejects
and which prefixes JVM stderr. Removing that override satisfies both original
contracts. The corresponding incomplete diagnostic ROM-backed run was stopped;
it is not full-suite evidence. Final focused verification at the assembled
repair tree passed 35 tests with no skips, including both environment-sensitive
CLI contracts, the API pin, and the Phase2 sample under dash.

## Trace scope

The CPZ capture correction follows ROM `loc_225FC` live coordinate reads and
`loc_22688`'s capture tail, which does not call `Obj1E_MoveCharacter`. Four matched
seg9 runs isolate the capture correction from initial placement. All retain the
same first error: frame 6, `dynamic_art.outstanding_transfer_ids`, expected `[2]`,
actual `[]`; the first gameplay x divergence remains frame 4859. Error spans
increase from 18,686 to 19,347, while gameplay mismatched row-fields decrease
from 13,023 to 8,009 and dynamic-art row-fields increase from 44,270 to 47,870.
This is mixed downstream evidence on an already-divergent replay, not a green
trace or a claim that every field improved. See the trace-frontier log for the
four-arm command and source attribution.

## Evidence

Logs, XML summaries, original failure output and CPZ reports are preserved under
`${CI_REPAIR_EVIDENCE}/`. Source changes are checked
against baseline failures by identity and assertion, not totals alone. Completed
final candidate and post-integration results are recorded below.

## Completed final candidate checks

| Source / lane | XML testcase occurrences | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| `110d7597b`, ROM-free push smoke | 18,793 | 0 | 0 | 2,722 |
| `7716b39f1`, ROM-free full CI | 19,917 | 0 | 0 | 2,727 |
| `7716b39f1`, fresh ROM-free guards | 652 | 0 | 0 | 0 |
| `7716b39f1`, ROM-backed ordinary full | 20,129 | 14 | 0 | 24 |

The first completed full CI run at `110d7597b` found one intermittent
`TestModAssetRoot` failure after smoke had passed. The final full CI run includes
the transaction repair and its additional controlled regression. Both the old
exact-two-success test and the new rollback test pass. The final push-smoke
result belongs to GitHub’s run for the integrated branch; it is checked after push.

The 14 remaining ROM-backed failures are all the existing FBZ Act 2 route and
compatibility cases. Their exception types and complete assertion messages
match `004b978d7` exactly, including controller timeouts and first failure frames.
Thirty-six existing failing identities now pass. Six obsolete identities were
replaced with current rewind inventory, both apparent-act music cases, scoped
FBZ inventory plus the separate exact registry guard, extra-sidekick capture,
and tracked FBZ exporter/amendment contracts. All replacement cases pass.
The [identity evidence](2026-09-08-next-ci-repair.json) records every remaining
assertion, resolved identity and replacement inventory; no failing test became
a new skip in the ROM-backed run.

ROM-free skips comprise declared ROM dependencies, runtime assumptions and
existing optional diagnostics. The final ROM-backed run has exactly the same
24 skipped identities as both completed baselines. No final full CI dump or
fork-crash file was produced. This is passing CI and baseline-preserving
ordinary-suite evidence, not completed FBZ campaigns or a green CPZ replay.

Completed logs and XML use `target/ci-complete-full/` and
`target/ci-complete-guards/` in the ROM-free clone, and
`target/ci-complete-backed/` in the repair worktree. The tested dash wrapper is
preserved outside the worktrees at
`${CI_REPAIR_EVIDENCE}/ubuntu-shell/`.


## Post-integration verification

The repair fast-forwarded into `next` at `918d5e9f6` without conflicts. The full
ordinary ROM-backed command above was rerun from `.worktrees/next-merge`, with
reports and the complete log under `target/ci-repair-post-integration/`.
It completed 20,129 testcase occurrences: 20,091 passed, 14 failed, zero errors,
and the same 24 skips. Every testcase identity, status and failure assertion
matches the completed candidate at `7716b39f1`; the only failing assertions are
the same FBZ baseline cases documented above.

A fresh `-Pguards -Dtest=TestBuildToolingGuard,TestTraceWorkflowToolingGuard`
run on the integrated snapshot also passed all 110 selected cases without skips,
covering the added evidence's resource policy and the manual workflow wiring.
The complete 652-case guards run on the same repair source is recorded above.
Subsequent changes in this evidence commit are documentation-only. The actual
GitHub push-smoke result is reported with delivery rather than inferred from
these local runs.
