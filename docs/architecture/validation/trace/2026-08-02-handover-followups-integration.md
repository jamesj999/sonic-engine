# Handover Follow-ups Integration

## Baseline and inputs

- Main workspace branch: `develop`
- Updated baseline: `2c64e09d4925cd6d9628ea59ac9874b2e26e6829`
- Development branch: `bugfix/ai-handover-followups`
- Candidate at first comparison: `1ebb5d929`
- JVM: OpenJDK 21.0.11
- ROM inputs: verified S1 REV01, S2 REV01, and locked-on S3K images supplied outside
  the repository
- Remote synchronization: `git fetch origin` followed by
  `git pull --ff-only origin develop`; `develop` and `origin/develop` were already aligned

The same command was used in both workspaces:

```text
mvn -Dmse=off \
  -Dsonic1.rom.path=<verified-s1> \
  -Dsonic2.rom.path=<verified-s2> \
  -Ds3k.rom.path=<verified-s3k> \
  test
```

## Pre-merge regression comparison

| Revision | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| `develop` `2c64e09d4` | 14,054 | 27 | 8 | 31 | expected red baseline |
| candidate `1ebb5d929` | 14,072 | 27 | 7 | 31 | expected red baseline plus one isolation finding |
| candidate after isolation correction | 14,072 | 26 | 7 | 31 | no new or worsened baseline failure |

The candidate added 18 focused contract cases. Relative to reports written by these exact
suite invocations, two baseline failures became green:

- `TestHardwareTimingReplayPort#schemaTwoAdmitsDirectPreEdgeBeforeIndependentModulePostEdge`
  now follows the production loop-tail order fixed on 2026-08-01.
- `TestMhzMushroomParachuteObjectInstance#fallingPlayerInGrabWindowIsCarriedAtRomOffsetAndParachuteStartsFalling`
  no longer inherits the persistent swing-vine owner from an earlier test.

One otherwise-passing method failed only in the first development suite:
`Sonic1SpecialStageResultsScreenTest#testRingBonusTalliesIntoScoreAndCompletes`. The
unchanged baseline and candidate both pass the complete four-method class alone. The test
polls session-owned S1 PLC readiness but lacked the repository's standard singleton reset,
so a reused fork could inherit unrelated outstanding PLC work. The amended design and plan
scope the correction to class-level `SingletonResetExtension` plus `@FullReset`, because the
lighter per-test profile does not clear the PLC service. Production result timing and PLC
behavior remain unchanged. The results class and lifecycle guard pass together, 12/12, and
the repeated full suite passes the method.

The final candidate failure set is a strict subset of the baseline set. It removes
`TestHardwareTimingReplayPort#schemaTwoAdmitsDirectPreEdgeBeforeIndependentModulePostEdge`
and `TestMhzMushroomParachuteObjectInstance#fallingPlayerInGrabWindowIsCarriedAtRomOffsetAndParachuteStartsFalling`;
every remaining failure and error has the same method identity as the baseline.

The suite also rewrites `docs/status/rewind-round-trip-gaps.md` as generated probe output.
That rewrite is not an intended deliverable and must be discarded before integration.

## Integration completion

The initial independent end-to-end review was green and merge `2d76b8951` applied without
conflicts. Post-merge verification, renewed review of the isolation correction, push, and
worktree cleanup remain pending. This report will be completed with exact repeated-suite,
review, push, and cleanup state.

### First post-merge run

Merge commit `2d76b8951` applied without conflicts. The first post-merge suite ran 14,072
tests with 28 failures, 7 errors, and 31 skipped. It retained the baseline MHZ mushroom
failure and added one baseline-passing failure in
`TestS3kSignpostInstance#fallingDispatchSkipsExpiringCooldownThenAppliesBumpBeforeGravity`.
The complete signpost class passes alone, 17/17. The test's local services omit terrain,
but `ObjectTerrainUtils` consults the ambient `GameServices` level; a reused fork can
therefore land the signpost and zero its falling velocity. The reviewed correction is
class-scoped `SingletonResetExtension` plus `@FullReset`, with no production change. A
repeated post-merge result and renewed independent whole-delivery review are required before
commit and push.

### Final post-merge verification

The signpost class and singleton lifecycle guard pass together, 25/25. The exact full-suite
command was then repeated on merged `develop`:

| Revision | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| merged `develop` after isolation correction | 14,072 | 26 | 7 | 31 | strict subset of baseline |

The repeated failure/error identities exactly match the final development-worktree set and
are a strict subset of the updated baseline. The baseline-only failures removed are
`TestHardwareTimingReplayPort#schemaTwoAdmitsDirectPreEdgeBeforeIndependentModulePostEdge`
and `TestMhzMushroomParachuteObjectInstance#fallingPlayerInGrabWindowIsCarriedAtRomOffsetAndParachuteStartsFalling`.
No baseline-passing method fails. The generated `rewind-round-trip-gaps.md` rewrite was
discarded under explicit user authorization.

Remote refresh found `origin/develop` already current before merge. Merge `2d76b8951`
applied without conflicts, and all pre-existing untracked main-workspace files remained
untouched. Renewed independent whole-delivery review reported `NO BLOCKERS`. The follow-up
isolation/review commit is `1f9213433`. `develop` pushed successfully from `2c64e09d4`
through `1f9213433`; no implementation branch was pushed.

The clean `.worktrees/handover-followups` worktree was removed after verifying its tip was
merged. Fully merged local branches `bugfix/ai-handover-followups` and
`bugfix/ai-handover-aiz` were deleted, and stale worktree metadata was pruned. Pre-existing
untracked main-workspace files remain untouched. This final integration-report update is the
only subsequent documentation delta.
