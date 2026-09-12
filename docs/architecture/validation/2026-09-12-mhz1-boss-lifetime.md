# MHZ1 miniboss offscreen lifetime

Work is directly on local `develop`, following the user's new workflow
instruction. Before edits, merged `origin/develop` by fast-forward to
`a71e1fe304`, including the sanctuary recovery changes. No new worktree or
trace was used.

## Finding and change

A user reported the MHZ1 boss disappearing after a short while. Its first dash
is intentionally offscreen: `loc_75356` widens the camera limit and
`loc_75392` waits for the previous draw visibility bit before resuming.
That behavior remains unchanged. The user's exact disappearance conditions
have not yet been confirmed.

A separate, reproducible deletion defect exists in the real object manager:
`MhzMinibossInstance` and its two flame children inherited nonpersistent
lifetime, so ordinary distance culling removed them despite their native
routines lacking an out-of-range deletion tail. `Obj_MHZMiniboss` /
`loc_751E2` ends in `Draw_And_Touch_Sprite`; `loc_757D6` and
`Child_DrawTouch_Sprite` keep the flames until parent deletion. Both classes
now declare persistence while retaining their explicit cleanup paths.

`TestMhzMinibossLifetime` runs actual `ObjectManager` updates with S3K slots.
It checks camera separation on either side, the same boss slot and two flame
instances surviving, native wait timer retention, movement resuming when
visible, and explicit parent/child removal. Initial harness attempts needed
explicit empty player-query and mocked audio services for the installed
pollen/music objects. Once those dependencies were supplied, both parameter
cases failed on the unmodified runtime because the boss disappeared from the
manager. Log: `target/mhz1-lifetime-before.log` (2 assertion failures, no errors).

This is the already-documented manager-culling pitfall in
`.agents/skills/s3k-implement-object/rom-pitfalls.md`; no new skill rule is needed.

## Verification

Focused validation passed **251 tests, zero failures/errors/skips** at
2026-09-12 11:33:05 BST, on `a71e1fe304` plus this fix, in `target/mhz1-lifetime-focused.log` with
isolated reports in `target/mhz1-lifetime-focused-reports`. The command uses
`mvn -Dmse=off -Dtest=TestMhzMinibossLifetime,TestMhzBossObjects,TestS3kMhzMinibossFlameGraphRewind,TestMhzMinibossEscapeShardGraphRewind,TestSonic3kMHZEvents,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils`
plus the absolute existing locked-on ROM path and `test -B`.


The full suite completed with `-Dsurefire.forkCount=2`, separate LWJGL
extraction per fork, all three absolute root ROM properties, and isolated
reports at `target/mhz1-lifetime-full-reports`. Command log:
`target/mhz1-lifetime-full.log`. No second Maven invocation runs concurrently
in this checkout. The full run finished at 2026-09-12 11:49:23 BST (exit 1): **20,300 tests,
27 failures, 8 errors, 97 skips**. Failing classes are outside MHZ: FBZ
route/compatibility, sample-mod packaging, donor-ROM/HUD, graphics contexts,
Mod API hook policy, audio/reference/shell tooling and mod scaffolding.
The full run is not green; using two forks and the newer sanctuary base also
means it is not a strictly controlled comparison to the prior one-fork run.

All skipped cases were inspected. They include opt-in measurements/captures,
missing reference artifacts, EGL availability, consumers still expecting
`s1.gen`/`s2.gen` despite absolute test properties, and a CNZ spin-tube setup
assumption. No ROM names or links were changed to conceal these gaps.
The focused MHZ/stability selection has zero skips.

Separate guard validation passed **656 tests, zero failures/errors/skips**
at 2026-09-12 11:56:23 BST, in `target/mhz1-lifetime-guards.log`,
with fresh reports at `target/mhz1-lifetime-guard-reports`. Command:
`PATH="/private/tmp/openggf-release-pwsh:$PATH"
LUA_BIN=/private/tmp/openggf-slicer-lua/lua-5.4.8/src/lua TMPDIR=/private/tmp
mvn -Dmse=off -Pguards -Dsurefire.forkCount=2
-Dopenggf.surefire.reports=target/mhz1-lifetime-guard-reports test -B`.
