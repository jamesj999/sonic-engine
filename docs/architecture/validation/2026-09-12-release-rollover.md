# 2026-09-12 release rollover validation

## Starting points and branch intent

- `master` / `v0.6.20260911`: `37aeb6b84`; new published branch
  `release/0.6.20260911` points to this exact commit.
- `develop`: `0173232e1`; synchronize the released tree via `aed278910`.
- `next`: `218b8fff1`; integrate synchronized develop, then merge the combined
  history back to develop before advancing next to 0.8.
- Final version intent: master 0.6.20260911, develop 0.7.prerelease,
  next 0.8.prerelease. The Mod API remains unpublished at 0.7.0; its engine branch topology is updated.

## Conflict decisions

- Preserve released hosted-build/snapshot-policy checks and exact artifact
  launcher selection alongside next CI, mod, and trace guards.
- Combine mod-aware preparation, immutable inputs, and loader/mutation job
  matching; preserve transition request scope and custom descriptors.
- Retain CNZ prize sound and Kosinski queue rewind state, and both MGZ tests.
- Keep next camera workers plus release LBZ rendering fixes.
- Restore the released saved-return gate in the shared zone bootstrap, including
  checkpoint ICZ and title-card LBZ handling. Restore the July carry-init fix
  (`629c56417`) over the older June pre-arm (`b0a97febd`): ROM routine `$0C`
  dispatch performs pickup on the following call. Keep next's later CNZ
  fixed-slot reconciliation (`4284010ff`) and LRZ load-state gate.
- Preserve both trace-frontier log sections verbatim and all configuration
  additions; published 0.6 changelog remains identical to the release tag.

## Verification method

Full ordinary baselines use `mvn -Dmse=off test -B` with all three verified
absolute ROM paths. No ROM files or aliases are created to satisfy tests.
Each worktree owns its own target and Surefire reports. Separate `-Pguards`
and focused integration tests follow compilation; package validation checks
the effective artifact and filtered runtime version.

The ordinary command is:

```bash
mvn -Dmse=off test -B \
  "-Dsonic1.rom.path=$S1_ROM" "-Dsonic2.rom.path=$S2_ROM" "-Ds3k.rom.path=$S3K_ROM"
```

The variables name the existing, hash-verified absolute ROM paths. Candidate
and promoted-branch runs additionally set `-DmodApi.destinationBranch=develop`
or `next`, matching that tree's descriptor. Structural runs use
`LUA_BIN=lua5.4 mvn -Dmse=off -Pguards test -B` with a separate report directory.
Package validation uses `mvn -Dmse=off -DskipTests package -B` after the ordinary
suite; skipped package tests are not counted as another passing suite.

Completed outcomes and baseline comparisons are recorded below.

## Environment interference

The first main-workspace post-sync run (`target/rollover-sync-integrated.log`)
was invalidated by a concurrent website-notify Maven build in the same workspace.
It reported missing compiled classes while those classes were being recompiled.
Do not treat its totals, report snapshot, or changed skips as code evidence.
The isolated synchronization run remains valid; final main integration needs
an uncontended full run.

## Completed baselines

- Original next structural guards: 652 tests, zero failures/errors/skips
  (`LUA_BIN=lua5.4 mvn -Dmse=off -Pguards test -B`, destination next).

- Synchronized release tree `aed278910`, isolated full suite: 17,135 tests,
  zero failures/errors, 46 skips. All 16,006 distinct outcomes exactly match
  the preceding develop baseline (`0173232e1`).
- Original next `218b8fff1`, full suite: 20,129 tests, 15 failures, 2 errors,
  94 skips. Completed in 19m45s. The following baseline failures are retained
  as comparison evidence, not accepted new regressions:

- `com.openggf.game.TestCrossGameFeatureProviderRefactor$S3kTailsDonationIntegration.s3kTailsScriptsSurviveTranslationIntoS1Host` (error): Failed to open secondary ROM: s3k.gen
- `com.openggf.game.TestCrossGameFeatureProviderRefactor$S3kTailsDonationIntegration.s3kTailsScriptsSurviveTranslationIntoS2Host` (error): Failed to open secondary ROM: s3k.gen
- `com.openggf.game.sonic2.TestSonic2LivesHudDonation.loadArtForZone_rebuildsAndExposesDonorLivesFrameThroughHudStaticArt` (failure): expected: <0> but was: <1>
- `com.openggf.tests.TestFbzAct2RouteHeadless.nativeStartWaveCompletesFbz2AndRequestsSandopolisAct0` (failure): obj28-terrain-recovery-timeout frame=30613 target=$1d7d player=($1d6e,$76c) speed=($0,$0) air=false onObject=false hurt=false dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.configuredTeamSurvivesSharedPlaneAndBossState(TeamCase)[1]` (failure): obj28-terrain-recovery-timeout frame=30613 target=$1d7d player=($1d6e,$76c) speed=($0,$0) air=false onObject=false hurt=false dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.configuredTeamSurvivesSharedPlaneAndBossState(TeamCase)[2]` (failure): obj28-terrain-recovery-timeout frame=30613 target=$1d7d player=($1d6e,$76c) speed=($0,$0) air=false onObject=false hurt=false dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.configuredTeamSurvivesSharedPlaneAndBossState(TeamCase)[3]` (failure): obj28-terrain-recovery-timeout frame=30613 target=$1d7d player=($1d6e,$76c) speed=($0,$0) air=false onObject=false hurt=false dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.configuredTeamSurvivesSharedPlaneAndBossState(TeamCase)[4]` (failure): obj28-terrain-recovery-timeout frame=30608 target=$1d7d player=($1d6e,$76c) speed=($c,$0) air=false onObject=false hurt=false dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.configuredTeamSurvivesSharedPlaneAndBossState(TeamCase)[5]` (failure): obj28-terrain-recovery-timeout frame=30608 target=$1d7d player=($1d6e,$76c) speed=($c,$0) air=false onObject=false hurt=false dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.donatedMovementProfileCanReachTheMandatoryBossEntryWithoutSpindash(String, Path)[1]` (failure): obj28-terrain-recovery-timeout frame=30613 target=$1d7d player=($1d6e,$76c) speed=($0,$0) air=false onObject=false hurt=false dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.donatedMovementProfileCanReachTheMandatoryBossEntryWithoutSpindash(String, Path)[2]` (failure): frame=7343 camera=($b56,$934) player=($be5,$994) speed=($0,$f900) rings=0 hurt=false dead=true
- `com.openggf.tests.TestFbzCompatibilityMatrix.donatedMovementProfileCanReachTheMandatoryBossEntryWithoutSpindash(String, Path)[3]` (failure): frame=29706 camera=($1810,$579) player=($18b0,$5d0) speed=($fe00,$fc00) rings=0 hurt=true dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.viewportKeepsWorldThresholdsCullingAndBossContainment(WidescreenAspect, int)[1]` (failure): obj28-terrain-recovery-timeout frame=30613 target=$1d7d player=($1d6e,$76c) speed=($0,$0) air=false onObject=false hurt=false dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.viewportKeepsWorldThresholdsCullingAndBossContainment(WidescreenAspect, int)[2]` (failure): frame=27356 camera=($625,$7ea) player=($6ef,$84a) speed=($0,$f900) rings=30 hurt=false dead=true
- `com.openggf.tests.TestFbzCompatibilityMatrix.viewportKeepsWorldThresholdsCullingAndBossContainment(WidescreenAspect, int)[3]` (failure): frame=29138 camera=($168f,$589) player=($178f,$5ec) speed=($fe00,$fc00) rings=0 hurt=true dead=false
- `com.openggf.tests.TestFbzCompatibilityMatrix.viewportKeepsWorldThresholdsCullingAndBossContainment(WidescreenAspect, int)[4]` (failure): frame=1455 camera=($730,$20c) player=($870,$26c) speed=($0,$f900) rings=0 hurt=false dead=true
- `com.openggf.tests.TestFbzCompatibilityMatrix.viewportKeepsWorldThresholdsCullingAndBossContainment(WidescreenAspect, int)[5]` (failure): frame=1291 camera=($688,$1e9) player=($818,$26c) speed=($0,$f900) rings=0 hurt=false dead=true

Full diagnostic messages and outcome identities are preserved in the external
rollover evidence directory; comparisons use those complete messages.

## Initial combined run and corrections

The first combined run completed 20,217 tests: 18 failures, no errors, and
25 skips. Four failures were new: the Sound Test source assertion, two
phase-2 sample scaffold checks, and the released ICZ checkpoint regression.
Their fixes are described above and in the artifact/tooling changes. All
14 FBZ failure identities and complete messages matched the fixed next baseline.

The three donor failures/errors and 70 old ROM-availability skips were absent
in the internal worktree. Existing short-ROM/config links in the external
next checkout resolve outside the repository; the internal checkout resolves
them to the main workspace. No links were created or changed to repair that
baseline. This difference is environmental, not a claimed gameplay improvement.
The newly added foreground-window rendering test skips because surfaceless
EGL is unavailable.

The clean focused correction run completed 228 tests with zero failures,
errors, or skips. All 23 requested classes reported, including Sound Test,
phase-2 SDK samples, saved-return/carry intros, prepared/mod/deferred loading,
rewind registration, MGZ, and required S3K loading/bootstrap/decoding checks.

## Concurrent upstream change

While validation ran, another session integrated `20cd3956f` on develop,
adding the website notification job and its guide/test. Its engine source tree
is unchanged from `aed278910`. An uncontended full baseline at `20cd3956f`
with the same absolute ROM properties completed 17,135 tests, zero failures
or errors, and 25 skips. Against the isolated sync baseline, 21 graphics
tests now pass rather than skip; no new failures or skips appeared.

The updated develop structural guard run completed 617 tests with zero
failures, errors, or skips. Both uncontended baseline logs and outcome
snapshots are preserved with the rollover evidence.

## Corrected combined tree

The full corrected worktree run completed 20,217 tests: 14 failures, no errors,
and 25 skips (16m54s). Every remaining failure identity and complete diagnostic
matches the original next baseline. No new or worsened failure remains. The
new foreground-window EGL skip is the same environment limitation described
above. All four integration failures from the initial run now pass.

Three former successful test names were replaced intentionally: the rewind
registry now expects thirteen atomic keys, and two Mod API policy tests now
express the retained-candidate rollover contract. Their replacement tests
passed; no unexplained successful test disappearance was found.

The corrected structural run completed 656 tests, zero failures/errors/skips.
Relative to next's 652 guards, the release-trigger guard is replaced by its
master-push equivalent and four released build/policy guards are added.

The corrected package command succeeded. The ordinary JAR, dependency JAR,
and Mod SDK JAR are present with `0.7.prerelease` filenames; both runtime JARs
contain `app.version` and `app.baseVersion` equal to `0.7.prerelease`. The
macOS bundle fields are `0.7.0`. The original SDK inventory failure is resolved.
