# Build-along: native Tails Flappy in Sonic 3 & Knuckles

This guide tours the maintained `sample-flappy` source: a Mod API 0.7 Sonic 3 &
Knuckles patch that starts a fresh game in a short custom sky level, launches native
Tails, and turns his normal flight into a fixed-position obstacle game. The mod does
not hide or replace the player, borrow ROM art into an object, force-scroll a camera,
or maintain a wrapping world. Tails stays visible while six pipes move toward him.

The executable source is
[`src/test/resources/mods/sample-flappy-src`](../../../src/test/resources/mods/sample-flappy-src/README.md).
The tests build that project and launch it through a real S3K module; when this tour
and the source differ, the source is authoritative.

You need a legally obtained combined Sonic 3 & Knuckles ROM (`s3k.gen`) to play the
patch and run its ROM-gated integration test. The mod jar contains only its Java
classes and generated sky/pipe assets.

## 1. Project and manifest

Creator builds use both release artifacts: the thin engine jar provides the public
API and the `openggf-mod-sdk` classifier provides `ggfmod`, converters, and project
templates. Build the engine first, then run the sample wrapper:

```powershell
mvn package
$out = Join-Path $env:TEMP ("sample-flappy-" + [guid]::NewGuid())
& src/test/resources/mods/sample-flappy-src/build.ps1 `
  -EngineJar (Resolve-Path target/OpenGGF-0.7.prerelease.jar) `
  -SdkJar (Resolve-Path target/OpenGGF-0.7.prerelease-openggf-mod-sdk.jar) `
  -OutputDirectory $out
```

The result is `<output>/target/sample-flappy-mod.jar`. Copy that jar—not the SDK—
to the engine's `mods/` directory. The manifest declares `type: patch`,
`baseGame: s3k`, and `engineApiRange: ">=0.7.0 <0.8.0"`; the 0.7 contract includes
the fresh-game destination, launch-team, input-filter, and HUD-policy contributions.

## 2. Anchorless game-start and scoped policies

`FlappySampleMod` registers `flappy-garden` with the last
`ModZoneContribution` flag set to `true`. That makes the tagged zone the owner's
exclusive fresh-game destination. Its `insertAfter` and replacement anchor are both
`null`: starting New Slot or No Save resolves directly to this zone, while stock S3K
progression remains untouched. Disabling the mod restores the stock AIZ1 start.

Three policies use the same `ZoneKey.mod("sample-flappy", "flappy-garden")`:

- `ModLaunchTeamContribution` launches Tails as the sole main character for this
  destination. It is launch-only and does not overwrite a durable data-select team.
- `ModInputFilterContribution` removes left and right from the held and pressed
  masks. The raw input snapshot is recorded first and the filter is reapplied after
  replay/rewind restoration, so deterministic traces retain the original left/right
  input while effective gameplay suppresses it. `PlayerInputState.of(...)`
  re-derives jump from the preserved action masks; a filter must preserve those
  masks rather than inventing an independent jump bit.
- `ModHudProfileContribution` hides the original SCORE row, keeps TIME and LIVES,
  and labels the existing RINGS counter as SCORE. This changes presentation only;
  scoring still writes the engine-owned rings value.

All three contributions are destination-scoped. Leaving this level restores the
stock input and HUD behavior.

## 3. Native Tails flight

The controller never renders a substitute bird. On its first active update it
relocates native Tails to screen position `(96, 112)`, stores the resulting world X,
zeros horizontal and ground speed, and activates `getTailsFlightController()`.
Subsequent frames restore that X and zero any drift while normal S3K player physics
continues to own vertical movement.

Flight endurance follows the ROM-faithful scripted-flight mechanism used by MGZ2:
the controller calls `setDoubleJumpProperty((byte) 0xF0)` every frame. There is no
custom fatigue rule and no direct vertical-velocity implementation. Jump remains the
ordinary Tails flight input.

## 4. Level and camera

The exact format-v2 source is deliberately small: a seven-by-two generated sky
strip, no rings, and one layout placement—the controller. Its nested bounds are:

```json
"bounds": {"minX": 0, "maxX": 0, "minY": 0, "maxY": 0}
```

Equal horizontal and vertical bounds pin both camera axes. The controller performs
the initial screen-anchor relocation after the player and camera exist; it does not
encode a misleading world-space start position in the level. Because the camera
never moves, there is no maximum-X exhaustion, rebase/wrap runtime, forced-scroll
policy, lead behavior, or despawn-window hazard. Persistent pipes remain owned by
the controller's fixed pool.

The level uses `hostMetadata.s3k.objectZoneSet: S3KL`, which selects the host's
compatible S3K object-set profile without borrowing any stock level event,
animation, PLC, or parallax behavior.

For hand-authored baked levels, block index 0 is reserved and blanked by the loader;
real visual content must use indices at or above 1. The deterministic TMX importer
handles that reservation automatically, but this sample's direct export does not.

## 5. Palette ownership and generated art

S3K still owns character palette line 0 and the live HUD cells. The level therefore
declares only the sparse colors its generated assets require:

- sky colors at line 1, cells 2 and 4; and
- pipe colors at line 2, cells 2 through 5.

Unclaimed cells remain host-owned or zero-filled according to the adapter contract.
This is why the sample no longer exhibits the palette corruption from its original
Sonic 2 prototype.

The adapter resolves those host and creator claims once during level initialization,
before the pre-level title card can draw. That initial publication matters because
the S3K red banner uses character line 0, color 6; waiting for the first gameplay
frame would let the card sample a stale palette texture from the preceding screen.
Normal frames begin a fresh ownership pass and resubmit the same claims.

`pipe-sheet.yaml` documents the other easy-to-miss rule. A piece's final CRAM line
is `(sheet.paletteLine + piece.paletteIndex) & 3`. The sheet uses base line 1 and
piece index 1, so the pipe renders from claimed line 2. `ggfmod convert art` writes
multi-tile pieces in Genesis column-major order; do not transpose the source image
or reinterpret its tiles row-first.

## 6. Pipes, score, death, and rewind

The layout contains no pipe placements. On first update the controller calls
`spawnFreeChild` six times, creating independent dynamic entries beyond the right
edge of the widest supported viewport. Each `FlappyPipe` is persistent and implements
`RewindRecreatable`; `recreateForRewind` uses `context.dynamicEntry().spawn()`.
The controller reconstructs only itself and does not respawn pipes during rewind
recreation, so the object manager restores each mod-owned dynamic entry with its
stable identity.

Every frame the controller sorts the live pipes by rewind identity, advances their
X position by a fixed `0x200` subpixels, and recycles any pipe whose right edge has
left the viewport. Recycling repositions the existing instance after the current
rightmost pipe rather than destroying and respawning it. Identity therefore remains
stable across both ordinary play and rewind.

Gap selection is deterministic and private to the minigame. A plain generation
counter repeats variants `2, 0, 4, 1, 3`; it deliberately does not consume the
shared gameplay RNG. A pipe's centre X, subpixel remainder, gap variant, and
`gateConsumed` flag are non-final scalar fields captured by the generic compact
rewind schema. The controller's routine, anchor X, pool flag, generation counter,
and the rings-backed score are likewise rewind-managed.

Scoring occurs once when a pipe's centre X passes Tails' centre. The controller adds
one ring, plays the ring SFX, and marks that pipe's gate consumed. `recycleAfter`
must reset `gateConsumed` to `false`; without that reset each pool entry could score
only on its first trip.

Death uses the normal player path. The controller calls `applyCrushDeath()` when
Tails overlaps either pipe body, reaches the top threshold
`cameraMinY + 0x10`, or reaches the bottom threshold `cameraY + 224`. It does not
fake damage, hide Tails, or write vertical physics directly. The host death
transition clears active flight before installing the stock `-0x700` death hop;
subsequent dead frames use normal player gravity, countdown, life loss, and restart
handling rather than continuing to run Tails' flight controller.

## 7. Presentation trade-off

The fixed camera is intentional for v1. The background does not scroll; all visible
motion comes from the approaching pipes. That is a real simplification: it removes
camera, forward-progression, wrapping, rebasing, and despawn-windowing engine surface
from the feature.

If the scene later feels too still, a recycling cloud or ground-strip object can
move independently behind the pipes. That presentation layer can use the same stable
pool idea without introducing a scroll framework or changing the level bounds.

## 8. Verification

The maintained tests cover the strict level source, S3K/API registration, raw versus
effective input, native Tails launch and flight refill, HUD rows, sparse palette
composition, six-pipe fresh and rewind sessions, deterministic recycling/scoring,
fatal bounds/contact, mod-classloader dynamic recreation, and packaging of all eight
gallery sources. The two ROM-gated integrations use assumptions when a ROM is absent;
the release gate supplies both ROM paths and requires zero skips.
