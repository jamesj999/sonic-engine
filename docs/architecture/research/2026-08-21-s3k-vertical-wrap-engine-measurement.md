# S3K vertical wrap: measured engine state vs the ROM constant

Measurement round, 2026-08-21, at commit 785fde5e8. Closes the open question from
[2026-08-21-s3k-screen-y-wrap-value-rule.md](2026-08-21-s3k-screen-y-wrap-value-rule.md):
is the engine's derived wrap mask (divergence **A**) live or latent on the release slice?

**No runtime code was changed.** The probe was a throwaway measurement test, run once and
then removed rather than committed; it is preserved with its surefire XML at
`$AGENT_SCRATCH_ROOT/tasks/s3k-wrap-measure-20260821T175840Z-3733333-e1f12cce/`.

## Method

`TestS3kVerticalWrapProbe`, a `@ParameterizedTest` over all 13 zones × 2 acts. For each it
loads the act headless, calls `GroundSensor.setLevelManager` and `Camera.updatePosition(true)`
after the load, then prints the level's FG layout height, `minY`,
`Camera.isVerticalWrapEnabled()` and `Camera.getVerticalWrapRange()`.

```
rm -rf target/surefire-reports
mvn -q -Dmse=off -Dtest=TestS3kVerticalWrapProbe \
    -Ds3k.rom.path=<the .gen in the project root> -DfailIfNoTests=true test
```

`-Dmse=off` because MSE `relaxed` swallows CLI `-D` properties. ROM SHA-1
`cfbf98c36c776677290a872547ac47c53d2761d6`, matching CLAUDE.md's locked-on entry. JDK 21
(`mvn -v` reports 21.0.11).

**Run confirmed before reading output**: `target/surefire-reports` was cleared first, and the
resulting XML reports `tests="26" errors="0" skipped="0" failures="0"` — 26 of 26 executed,
none skipped. A `@RequiresRom` class that silently skipped would have reported `skipped="26"`.

## Measured results

`engineMask` is `getVerticalWrapRange() - 1`. `romWrap` is the value established in the
previous round (`LevelSetup` writes `$FFF`; ICZ1 and SOZ2 lower it to `$7FF`).

| Act | minY | FG blocks | FG height | wrapEnabled | engineMask | romWrap | agrees |
|---|---|---|---|---|---|---|---|
| AIZ1 | 0 | 13 | `$0680` | **false** | `$07FF` | `$FFF` | no |
| AIZ2 | 0 | 21 | `$0A80` | **false** | `$07FF` | `$FFF` | no |
| HCZ1 | 0 | 24 | `$0C00` | **false** | `$07FF` | `$FFF` | no |
| HCZ2 | 0 | 24 | `$0C00` | **false** | `$07FF` | `$FFF` | no |
| MGZ1 | -256 | 32 | `$1000` | true | `$0FFF` | `$FFF` | **yes** |
| MGZ2 | 0 | 25 | `$0C80` | false | `$07FF` | `$FFF` | no |
| CNZ1 | 0 | 25 | `$0C80` | false | `$07FF` | `$FFF` | no |
| CNZ2 | 1408 | 25 | `$0C80` | false | `$07FF` | `$FFF` | no |
| FBZ1 | 0 | 24 | `$0C00` | false | `$07FF` | `$FFF` | no |
| FBZ2 | 0 | 27 | `$0D80` | false | `$07FF` | `$FFF` | no |
| ICZ1 | -256 | 16 | `$0800` | true | `$07FF` | `$7FF` | **yes** |
| ICZ2 | 0 | 24 | `$0C00` | false | `$07FF` | `$FFF` | no |
| LBZ1 | 0 | 24 | `$0C00` | false | `$07FF` | `$FFF` | no |
| LBZ2 | 0 | 24 | `$0C00` | false | `$07FF` | `$FFF` | no |
| MHZ1 | 0 | 23 | `$0B80` | false | `$07FF` | `$FFF` | no |
| MHZ2 | 1568 | 21 | `$0A80` | false | `$07FF` | `$FFF` | no |
| SOZ1 | 0 | 25 | `$0C80` | false | `$07FF` | `$FFF` | no |
| SOZ2 | -256 | 16 | `$0800` | true | `$07FF` | `$7FF` | **yes** |
| LRZ1 | 0 | 24 | `$0C00` | false | `$07FF` | `$FFF` | no |
| LRZ2 | 0 | 24 | `$0C00` | false | `$07FF` | `$FFF` | no |
| SSZ1 | -256 | 32 | `$1000` | true | `$0FFF` | `$FFF` | **yes** |
| SSZ2 | 0 | 19 | `$0980` | false | `$07FF` | `$FFF` | no |
| DEZ1 | 0 | 32 | `$1000` | **false** | `$07FF` | `$FFF` | no |
| DEZ2 | 0 | 32 | `$1000` | **false** | `$07FF` | `$FFF` | no |
| DDZ1 | 0 | 6 | `$0300` | false | `$07FF` | `$FFF` | no |
| DDZ2 | 0 | 6 | `$0300` | false | `$07FF` | `$FFF` | no |

## Divergence A is LATENT — but not for the reason it looks like

**Every act where the engine enables wrapping agrees with the ROM's constant.** Four acts —
MGZ1, ICZ1, SOZ2, SSZ1 — are the complete set with `minY < 0`, and in all four the derived
mask matches: MGZ1 and SSZ1 are 32-row (`$1000`) layouts and get `$FFF`, ICZ1 and SOZ2 are
16-row (`$800`) layouts and get `$7FF`.

That is a real coincidence, not a design: `minY < 0` happens to select exactly the acts whose
FG layout height equals the ROM's written period for that act. Nothing enforces it. A future
act, or a corrected `minY`, breaks the agreement silently, because nothing compares the two.

So **A is latent, worth fixing, not urgent** — which is the "not urgent" branch of what was
asked. But the reason it is latent is worth more than the verdict: it is latent because
divergence **B** suppresses it everywhere it would otherwise bite.

**Note on the `engineMask` column for disabled acts.** `Camera.setVerticalWrapEnabled(false)`
leaves `verticalWrapRange`/`verticalWrapMask` at their previous values
(`Camera.java:1023-1029` only assigns them when `enabled && range > 0`), so the `$07FF`
shown for every disabled act is the stale S1 default `VERTICAL_WRAP_RANGE`, not a value
chosen for that act. It is reported for completeness and must not be read as "the engine
picked `$7FF` here".

## Divergence B is ACTIVE in 22 of 26 acts, including the whole release slice

The engine enables vertical wrapping only when `minY < 0` (`LevelManager.java:2852`). The
ROM has no such gate: `Render_Sprites` masks unconditionally at sonic3k.asm:36360 and 36487,
and `$FFFF` is how the ROM *expresses* "no wrap". All four AIZ and HCZ acts have `minY = 0`,
so **on the primary release slice the engine does not mask at all while the ROM masks with
`$FFF`.**

**How far that actually reaches, stated carefully.** The two formulations agree wherever
`|relY|` stays below the wrap period, which for an object and a camera both inside a
`$C00`-tall layout it does. They diverge only when `relY + height_pixels` is far enough from
zero to alias — around `±$1000` for a `$FFF` mask. So B is *structurally* active in 22 acts
but *observably* active only where an object can sit roughly a full `$1000` from the camera.
**DEZ1 and DEZ2 are the sharpest case**: both are exactly `$1000`-tall layouts with
`minY = 0`, so aliasing distance is reachable inside the layout while the engine's wrap stays
off. For AIZ and HCZ it requires an object well outside the layout bounds — possible, not
routine.

**I did not measure whether any object in any committed trace actually reaches such a
`relY`.** That needs a trace, not a level load, and it is the right next question if B is
ever prioritised. Without it, "22 of 26 acts diverge structurally" is the honest claim and
"22 of 26 acts are visibly wrong" is not.

## The period is modelled three times, independently

Asked because it decides whether fixing A is one change or three. It is **three**, and none
of them reads the ROM constant:

1. **`Camera.verticalWrapRange` / `verticalWrapMask`** (`Camera.java:83-84`), set by
   `LevelManager.java:2853-2859` and `:3808-3812` from `cachedFgHeightPx`, mask = `range - 1`.
2. **`LevelTilemapManager`'s layout row lookup**, which derives the period *again and
   separately*: `Math.floorMod(worldY, levelHeight)` with
   `levelHeight = getLayerLevelHeightPx((byte) 0)` at `LevelTilemapManager.java:1473-1481`
   and again at `:1552-1560`. It does not consult the camera's mask; it takes
   `verticalWrapEnabled` as a passed-in boolean and recomputes the modulus itself.
   This is the engine's counterpart to the ROM's `Layout_row_index_mask`.
3. ~~**`Camera.VERTICAL_WRAP_BG_MASK`**, a hardcoded static `0x3FF`.~~ **WITHDRAWN** by
   [2026-08-21-s3k-wrap-trigger-and-bg-mask-provenance.md](2026-08-21-s3k-wrap-trigger-and-bg-mask-provenance.md):
   it is Sonic 1's BG wrap (`docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:241,266`),
   genuine and correctly valued, reached only from `SwScrlLz`. It does not participate in the
   S3K period. **The S3K period is modelled twice, not three times** — items 1 and 2 above.
   The ownership point is unchanged; the fix is smaller than reported.

The ROM writes its three — `Screen_Y_wrap_value`, `Camera_Y_pos_mask`,
`Layout_row_index_mask` — as one triple from one place (`LevelSetup`, sonic3k.asm:102205-102207),
so they cannot disagree. The engine derives its three from two different sources plus one
literal, so they can. Fixing A properly means giving the period a single owner first;
patching only the camera's mask would leave `LevelTilemapManager` computing a different
period from the same layout, and the `0x3FF` BG literal untouched.

## What I could not establish

1. **Whether B is observable in any committed trace** — see above. Needs a trace run.
2. ~~**Whether `minY < 0` is itself ROM-accurate** as the engine's wrap trigger.~~
   **CLOSED** by the follow-up doc: it is ROM-derived, but S3K's player/camera paths test
   `Camera_min_Y_pos == -$100` exactly (sonic3k.asm:21989, "is vertical wrapping enabled?")
   while its object-load manager uses a `< 0` sign test (sonic3k.asm:37560) — and
   `Render_Sprites` uses **no trigger at all**. So B is a slightly wrong threshold for
   position wrapping and an *invented gate* for the render path.
3. ~~**Whether `VERTICAL_WRAP_BG_MASK = 0x3FF` corresponds to any ROM constant.**~~
   **CLOSED — the suspicion was wrong.** It is Sonic 1's, not S3K's: `andi.w #$3FF,
   (v_bgscreenposy).w` at `docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:241` and
   `:266`. Only the citation was missing. See the follow-up doc.
4. **DEZ1/DEZ2's actual behaviour.** They are the case where B should be reachable inside
   the layout, and I identified them by arithmetic rather than by observing a wrong render.
