# MHZ1 post-signpost rewind identity crash

The user reported `Cannot register object id to different live instances`
at `LiveRewindManager.handleSeamlessLevelTransitionBoundary`, after the
signpost and ring monitor appeared. Work remains directly on local `develop`.
The explosion fix is `0bac57fe56`; `52d1269a6f` merges `origin/develop` at
`3379a933c5` before this follow-up. No traces or new worktrees were used.

## Cause and correction

`rebuildManagersForActTransition` resets the replacement manager, installing
the target act's fixed SST defaults. Exact-slot carry then imported the
original persistent pollen controller without removing the fresh slot-4
controller. Both remained live with rewind dynamic ordinal zero. The same
defect affected HCZ's fixed slot-5 water-splash owner.

Exact-slot carry now removes freshly initialized occupants of carried slots
before importing identities and registering the original objects. This keeps
the original state, slot and identity, while retaining initialization defaults
for slots that are not carried. The rewind identity table still rejects
duplicates; the fix removes the duplicate gameplay owner itself.

The shared implementation uses the transition's existing survival policy and
captured slot ownership, without a game/zone special case or Mod API change.
The pitfall is recorded in both S3K object skill mirrors.

## Verification

`TestS3kFixedSstTransitionRewind` executes a real ROM-backed Act 1 → Act 2
resource reload for MHZ and HCZ. It captures rewind immediately after the
transition, checks one fixed owner with its original slot/identity, restores,
and captures again. Before the fix both cases failed with the user's exact
exception (2 failures, no errors/skips), in
`target/mhz1-transition-before.log` and its isolated reports directory.

Focused validation passed **348 tests, zero failures/errors/skips** at
2026-09-12 13:08:00 BST, on `52d1269a6f` plus this fix. Log:
`target/mhz1-transition-focused.log`; reports:
`target/mhz1-transition-focused-reports`. The command used `mvn -Dmse=off`,
the discovered absolute locked-on `-Ds3k.rom.path`, `test -B`, and this selector:

```text
TestS3kFixedSstTransitionRewind,TestMhzMinibossDefeat,TestMhzMinibossExplosionAllocation,TestRemainingRewindTailInventory,TestMhzBossObjects,TestMhzMinibossLifetime,TestS3kMhzMinibossFlameGraphRewind,TestMhzMinibossEscapeShardGraphRewind,TestSonic3kPlcArtRegistry,TestPatternSpriteRendererCorruptionGuard,TestSonic3kMHZEvents,TestLevelSeamlessTransitionExecutor,TestObjectManagerLifecycle,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils
```

The earlier integrated full run (`target/mhz1-defeat-integrated-full.log`)
was interrupted on receipt of the user's crash report and has no completed
verdict. Final full-suite and separate guard evidence belongs below.

The full suite completed at 2026-09-12 13:24:29 BST on `52d1269a6f` plus
this fix: **20,297 tests, 27 failures, 8 errors, 97 skips** (exit 1).
Log: `target/mhz1-transition-full.log`; isolated reports:
`target/mhz1-transition-full-reports`. Command: `mvn -Dmse=off
-Dsurefire.forkCount=2` with all three discovered absolute ROM properties,
`-Dopenggf.surefire.reports=target/mhz1-transition-full-reports test -B`.
The PowerShell tool directory was on `PATH`, `TMPDIR=/private/tmp`, and the
run had native desktop access. No other Maven process ran concurrently.

All 35 failed/error testcase names and failure/error categories exactly match
the completed earlier lifetime suite (`target/mhz1-lifetime-full-reports`).
They cover FBZ route/compatibility, sample-mod packaging, donor-ROM/HUD,
graphics contexts, Mod API hook policy, audio/reference/shell tooling and
mod scaffolding. This is a comparison of reported cases, not a controlled
causal baseline experiment; the full suite remains red.

All skip reasons were inspected: opt-in measurements/captures, missing local
reference artifacts, unavailable graphics backends, tests still expecting
`s1.gen`/`s2.gen` despite the supplied properties, and a CNZ spin-tube setup
assumption. No ROM was renamed or linked to conceal those gaps. The focused
MHZ/transition selection has zero skips.


Separate structural guards passed **656 tests, zero failures/errors/skips**
at 2026-09-12 13:33:37 BST (exit 0). Log:
`target/mhz1-transition-guards.log`; reports:
`target/mhz1-transition-guard-reports`. Command:

```bash
PATH="/private/tmp/openggf-release-pwsh:$PATH" \
LUA_BIN=/private/tmp/openggf-slicer-lua/lua-5.4.8/src/lua TMPDIR=/private/tmp \
mvn -Dmse=off -Pguards -Dsurefire.forkCount=2 \
  -Dopenggf.surefire.reports=target/mhz1-transition-guard-reports test -B
```
