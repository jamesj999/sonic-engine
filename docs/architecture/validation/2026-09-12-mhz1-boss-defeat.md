# MHZ1 miniboss destruction sequence

Direct disassembly-backed follow-up on local `develop`, base `c4483c06b2`.
No traces or new worktrees were used. Scope is the Sonic/Tails MHZ1 encounter.

## Findings and change

Fresh MHZ1 did not register `ObjectArtKeys.BOSS_EXPLOSION`. Explosion children
therefore reached their draw path without a renderer and displayed nothing.
`loc_75220` loads `PLC_MHZMiniboss_Explosion`, whose only entry is
`ArtNem_BossExplosion` at `ArtTile_BossExplosion2`. Boss initialization now
uses the existing ROM-backed `ensureBossExplosionArtLoaded` path, as MHZ2
already does. The shared sheet uses the native mapping and animation.

The user's manual retest still showed disappearing defeat graphics. Inspection
then found a second rendering defect: `registerSheet` leaves `patternBase=-1`
and the late-load helper never uploaded the new patterns. The explosion draw
path explicitly rejects an unready renderer. `ensureBossExplosionArtLoaded`
now uploads the ordered object atlas and returns actual renderer readiness.
The fresh-level test has been strengthened to assert readiness, but has not
been rerun: the user requested manual verification before further engine tests.

The old body-owned burst counter also stopped when the body retired after
64 updates. `loc_75DCC` instead allocates a separate subtype-$10 controller.
`CreateBossExp10` selects `Obj_Wait` / `Obj_BossExpControl2`, with count `$20`
and X/Y ranges `$20`. The zero initial wait produces an immediate burst;
subsequent bursts are three updates apart. The predecrement gives 31 attempts,
then deferred controller deletion. The new independent object survives the
body's music/signpost handoff and participates in rewind recreation.

`CreateChild6_Simple` searches after the controller slot. Failed allocation
consumes an attempt without consuming RNG. `loc_83E90` uses the low random
word for X and the high word after `SWAP` for Y. Each successfully allocated
`Obj_BossExplosion2` owns its sound on first dispatch (`loc_83F52`). The
existing shared explosion child supplies that animation/sound lifecycle.

These are the art-loading and separate-owner obligations already covered by
the S3K boss skill and its encounter-graph reference; no new skill rule is
needed.

## Verification

The fresh-level renderer regression failed on the unmodified runtime:
`TestMhzMinibossDefeat#freshMhz1BossRegistersItsExplosionRenderer` expected a
renderer but got null (one failure, no errors/skips;
`target/mhz1-defeat-before.log`). It loads the existing locked-on ROM through
`SharedLevel` and initializes the boss through the actual object manager.

The initial focused run completed at 2026-09-12 12:45:08 BST: **267 tests,
zero failures/errors/skips**. Log: `target/mhz1-defeat-focused.log`;
reports: `target/mhz1-defeat-focused-reports`. Command:

```bash
mvn -Dmse=off \
  '-Dtest=TestMhzMinibossDefeat,TestMhzBossObjects,TestMhzMinibossLifetime,TestS3kMhzMinibossFlameGraphRewind,TestMhzMinibossEscapeShardGraphRewind,TestSonic3kPlcArtRegistry,TestPatternSpriteRendererCorruptionGuard,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils' \
  "-Ds3k.rom.path=$S3K_ROM_PATH" \
  -Dopenggf.surefire.reports=target/mhz1-defeat-focused-reports test -B
```

The real-manager defeat regression observes 22 bursts by body retirement,
31 overall, controller deletion, and successful rewind recreation after
the body is already gone. The old mock-body test now checks independent
controller allocation and retains its music, signpost, timer and score checks.
This is headless behavior/art validation, not a claim of visual ROM parity
from a recorded playthrough.

These passing checks did not verify pattern upload and do not validate the
later rendering correction. The broad run was interrupted at the user's
request (exit 130; `target/mhz1-defeat-full.log`), so it supplies no completed
suite verdict. Separate guards and final focused checks are deferred until
the user manually verifies the updated implementation.

Before interruption, the full suite identified the expected rewind inventory increase from
1,009/789 total/passing objects to 1,010/790. The new controller passes the
isolated sweep; the inventory expectation has been updated. The pending focused
rerun should include `TestRemainingRewindTailInventory` and the additional
`TestMhzMinibossExplosionAllocation` check for failed allocation, unchanged
RNG on failure, high-word Y placement and unchanged controller lifetime.


## Manual verification and resumed checks

The user confirmed the corrected visuals and authorized tests and delivery.
The final focused selection above plus `TestMhzMinibossExplosionAllocation`
and `TestRemainingRewindTailInventory` passed **269 tests, zero failures,
errors or skips** at 2026-09-12 12:58:32 BST on `c4483c06b2` plus this fix.
It includes the strengthened renderer-readiness assertion. Log:
`target/mhz1-defeat-final-focused.log`; isolated reports:
`target/mhz1-defeat-final-focused-reports`.
