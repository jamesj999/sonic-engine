# Build-along: a no-ROM platformer with a double-jumping robot

This guide walks through the seventh gallery sample, `sample-platformer`, start to
finish: a complete, original, no-ROM game — one Tiled-authored act called Bolt
Plains, a round robot character ("Bolt") with a distinct physics feel and a double
jump, a patrolling badnik ("ZapBug"), a spring gimmick ("SpringPad"), looping OGG
music, and WAV sound effects. It exists to demonstrate the standalone-game path end
to end, including two seams the [native-Tails Flappy guide](native-tails-flappy.md)
doesn't need: authoring a level
directly in Tiled instead of the in-engine editor, and shipping a genuinely new
playable character with custom physics and an original ability.

The finished source lives at
[`src/test/resources/mods/sample-platformer-src`](../../../src/test/resources/mods/sample-platformer-src/README.md).
Every code excerpt below is copied verbatim from that checked-in project; the sample
is the executable contract and this guide is a tour of it, not an independent
implementation. If a snippet here and the file on disk ever disagree, the file on
disk is right — `TestSamplePlatformerIntegration` and `TestSampleModsPackage` build
and run the real source on every CI run, not this document.

This guide assumes you've already skimmed [Standalone games](../standalone-games.md)
and [Playable character mods](../characters.md); it narrates the same contracts
against this sample's actual files rather than repeating their reference material.

## 1. What you'll build

Bolt Plains is one act: a 128×16-tile (2048×256px) level with solid ground running
most of its width, a pit near the middle that swallows a careless walk or a badly
timed jump, a few raised single-tile platforms, and about twenty rings arced across
the level's length. A `ZapBug` badnik patrols back and forth near the far end, and a
`SpringPad` just past it launches you into the air. There is no boss, no second act,
and no zone transition — reaching the end of the act (or dying enough times, or just
quitting to title) is the whole game; the acceptance test drives it through to
credits and back.

**No ROM is required at all**, to build, package, play, or test this mod. It's a
`type: standalone` manifest with no `baseGame`: the module reports
`GameId.STANDALONE`, every asset (level, art, audio) is baked into the jar, and it
appears as its own entry on the master title beside — not instead of — the three
stock games.

## 2. Project setup

Scaffold a project the same way any other mod starts:

```text
ggfmod.ps1 OpenGGF-0.7.prerelease-jar-with-dependencies.jar OpenGGF-0.7.prerelease-openggf-mod-sdk.jar init sample-platformer --id sample-platformer --package example.platformer
```

(POSIX shells use `docs/modding/ggfmod` instead of `ggfmod.ps1`; every `ggfmod`
command below drops the two leading jar arguments for readability, same as the
flappy guide.) The checked-in sample's `project/` directory is that scaffold
hand-adapted for a standalone game instead of a Sonic 2 patch: no `baseGame`, four
object/character art sources instead of one badnik, and a TMX-authored level instead
of an editor export.

The manifest
([`openggf-mod.yaml`](../../../src/test/resources/mods/sample-platformer-src/project/src/main/resources/META-INF/openggf-mod.yaml))
declares:

```yaml
formatVersion: 1
id: sample-platformer
name: Sample Platformer
version: 1.0.0
authors:
  - OpenGGF Sample Authors
description: No-ROM standalone platformer sample with a TMX-authored act, an original character, a badnik, and a spring gimmick.
engineApiRange: ">=0.7.0 <0.8.0"
type: standalone
entrypoint: example.platformer.PlatformerMod
dependencies: []
audioOverrides: {}
artOverrides: {}
```

Use the maintained Mod API 0.7 range. Bolt overrides the playable-subclass rewind
hooks (`captureSubclassRewindState`/`restoreSubclassRewindState`; see
[Characters](../characters.md)). It never calls `ModContext.registerRomObjectArt`
because there is no ROM to borrow art from. `type:
standalone` and the absent `baseGame` are what route this manifest through
[Standalone games](../standalone-games.md)'s registration contract instead of the
additive-patch one.

The entrypoint
([`PlatformerMod.java`](../../../src/test/resources/mods/sample-platformer-src/project/src/main/java/example/platformer/PlatformerMod.java))
registers everything in one pass:

```java
public void register(ModContext context) {
    ModAssetRoot assets = context.modAssets();
    var materialized = PlayableSheetMaterializer.read(
            assets.readBounded("art/bolt.ggfp", assets.limits().maxAssetBytes()));
    Level level = StandaloneLevelLoader.load(assets,
            new BakedLevelRef("levels/act1/level.json"), context.ownerModId(),
            buildRingSheet(assets));
    context.registerObject("zapbug", (spawn, registry) -> new ZapBug(spawn));
    context.registerObjectArt("zapbug", new BakedSheetRef("art/zapbug.ggfs"));
    context.registerObject("springpad", (spawn, registry) -> new SpringPad(spawn));
    context.registerObjectArt("springpad", new BakedSheetRef("art/springpad.ggfs"));
    context.registerCharacter("bolt", BoltCharacter.definition(
            context.ownerModId(), materialized));
    context.registerGameModule(new PlatformerModule(context.ownerModId(), level));
}
```

Read bounded assets and register characters/objects first, then register exactly one
module last — `registerGameModule` closes the transaction. The `registerObjectArt`
calls here are the *only* object-art source this sample needs — as Chapter 8 covers
in detail, the engine decorates `PlatformerModule`'s own (in this case absent)
`getObjectArtProvider()` result with these registered sheets, so `ZapBug`/`SpringPad`
render in gameplay without `PlatformerModule` serving any art itself.

## 3. Authoring the level in Tiled

Bolt Plains is authored directly in [Tiled](https://www.mapeditor.org/), not
exported from the in-engine editor — the finite orthogonal 16×16 TMX subset that
`ggfmod convert level --from-tmx` accepts. The source map is
[`level.tmx`](../../../src/test/resources/mods/sample-platformer-src/project/src/main/mod/level.tmx):
a 128×16 tile map with an embedded `tileset.png`, exactly two named tile layers
(`FG` and `COLLISION`), and one `OBJECTS` object layer.

**Layer names carry meaning.** The importer only recognizes four case-insensitive
tile-layer names: `FG` (required — the visible foreground), `BG` (optional visible
background), `COLLISION` (optional primary solidity), and `COLLISION_ALT` (optional
secondary solidity, mirrors S2/S3K's dual collision-path model — see
`CLAUDE.md`'s "Collision Model" section). This level uses only `FG` and `COLLISION`;
without `COLLISION_ALT` the importer copies the primary collision layer onto the
secondary path automatically.

**Per-tile collision GIDs are restricted to 0 or 1 unless you supply custom
profiles.** Without a `--solid-tiles` profile directory, the default profile set
only understands GID `0` (`NO_COLLISION`) and `1` (`ALL_SOLID`) — asymmetric
`TOP_SOLID`/`LEFT_RIGHT_BOTTOM_SOLID` authoring needs a custom profile binary set
(tracked, not yet needed by any maintained TMX sample; see
[the deferred backlog](../BACKLOG.md)). Bolt Plains only needs plain solid
ground/platforms, so its `COLLISION` layer is pure 0/1 and it passes no
`--solid-tiles` flag at all.

**Object, ring, and start markers** are Tiled point objects in the one allowed
`OBJECTS` group, distinguished by `class` (or the legacy `type` attribute):

```xml
<object id="1" class="start" x="64" y="160"><point/></object>
<object id="2" class="object" x="900" y="192">
  <properties><property name="objectKey" value="sample-platformer:zapbug"/></properties>
  <point/>
</object>
<object id="3" class="object" x="944" y="192">
  <properties><property name="objectKey" value="sample-platformer:springpad"/></properties>
  <point/>
</object>
<object id="4" class="ring" x="80" y="100"><point/></object>
```

An `object` marker needs exactly one of a typed-`int` `stockObjectId` or a
string `objectKey` property (this sample, being a standalone game with no stock
object table, uses namespaced `objectKey` values exclusively); `ring` and `start`
markers take no properties at all. Exactly one `start` marker is required — Bolt
Plains' is at `(64, 160)`, matching `PlatformerZones.getStartPosition`.

**Converting the level** is one command (this project wires it into
`generate-resources`; see Chapter 8):

```text
ggfmod convert level --from-tmx src/main/mod/level.tmx --palette src/main/mod/palette.gpal --music sample-platformer:zone-theme --out target/classes/levels/act1
```

`--palette` is **mandatory** for `--from-tmx` — there is no default palette. It
points at a small binary GPAL v1 container (`palette.gpal` — generated once
alongside the level by this sample's offline asset generator, not hand-edited; see
Chapter 7's note on non-hermetic generator steps).

**`--music <owner:localName>`** is the flag this plan added to the TMX pipeline: it
declares a namespaced streamed track (a `TrackMusic` reference) instead of the
default `StockMusic(0)` placeholder a patch-type TMX level would use. **A standalone
level must carry one** — `ModZoneLoader#loadStandalone` requires every standalone
level to have a `TrackMusic` owned by the declaring mod, so any `--from-tmx` level
feeding a standalone module needs this flag. `sample-platformer:zone-theme` here
matches the track id declared in `audio/audio-manifest.yaml` (Chapter 7). Passing a
malformed value (anything without exactly one colon) fails fast with a
`COMMAND_FAILED "Mod key must contain exactly one colon"` error before any file is
touched.

**The additive palette-line arithmetic, and why line 2 is populated.** Every one of
this sample's baked-art sheet YAMLs (`zapbug-sheet.yaml`, `springpad-sheet.yaml`,
`ring-sheet.yaml`) opens with the same warning-comment pattern:

```yaml
# paletteLine is a BASE, not the final CRAM line: SpritePieceRenderer.preparePiece
# computes the rendered line as (paletteLine + piece.paletteIndex) & 0x3 (Genesis
# art_tile-addition semantics). Every piece below declares paletteIndex: 0, so this
# sheet renders on CRAM line (2 + 0) & 3 = 2 -- which this mod's palette.gpal
# DELIBERATELY populates with the shared 16-color object palette (ring golds,
# zapbug reds, springpad grays/yellow), mirroring the sample-flappy pipe-sheet
# approach. The declared palette list below is byte-for-byte that GPAL line, so
# convert-time quantization and runtime colors agree. Keep the three in sync:
# this list, the PNG's pixels, and the generator's LINE2 GPAL data. Do not bump
# paletteIndex: (2 + 1) & 3 = 3 is zero-filled in the GPAL (solid black).
formatVersion: 1
paletteLine: 2
```

The rendered CRAM line is `(sheet.paletteLine + piece.paletteIndex) & 3`, exactly
the arithmetic [ROM-art remix Chapter 3](rom-art-remix.md#3-request-the-bounded-rom-window)
introduced for ROM-materialized art — it applies uniformly to baked sheets too.
Level tile art occupies lines 0–1 in this level's GPAL; the three object sheets share
line 2 deliberately, so their `palette:` lists must stay byte-for-byte identical to
the GPAL's line 2 and to each other. `bolt-sheet.yaml` is different: it targets line
0 with the character's *own* 16-color palette (loaded from the `.ggfp`'s BASE
palette by `CharacterDefinition`'s palette supplier), not the level GPAL's line 0 —
see Chapter 4.

**The importer is deterministic and refuses to clobber.** Running the same TMX,
palette, and (absent) solid-tiles inputs through `convert level --from-tmx` twice
produces byte-identical output directories — the engine test suite pins this exactly
(`TestTmxLevelImporter#repeatedImportIsByteIdenticalPinnedAndAcceptedByPhase2Parser`
asserts a fixed aggregate SHA-256 across two independent conversions of the same
fixture). If the requested output directory already exists, the importer fails
before writing anything rather than overwriting or merging into it.

**Block 0 is handled for you.** Unlike a hand-authored full-level export (where, as
[`native-tails-flappy.md` Chapter 4](native-tails-flappy.md#4-level-and-camera) warns, you must
personally avoid putting real content at block index 0 because `ModLevel` blanks it
on load), the TMX compiler reserves an all-zero pattern, chunk, and block at index 0
*before* compiling a single real tile, and every subsequent chunk/block is
deduplicated by exact content against that reserved entry. An actually-empty 128×128
region of your TMX map always resolves back to reserved block 0 automatically; any
block containing real tiles gets a new index ≥1 the first time it's seen. You don't
need to arrange your layout around this — just author the map naturally in Tiled and
the reservation falls out of the compiler's own hierarchy-building pass.

## 4. The character

Bolt's playable art is a two-frame sheet,
[`bolt-sheet.yaml`](../../../src/test/resources/mods/sample-platformer-src/project/src/main/mod/bolt-sheet.yaml):

```yaml
# Character sheet: paletteLine 0 + piece paletteIndex 0 => rendered CRAM line
# (0 + 0) & 3 = 0. Bolt's runtime colors come from this sheet's own 16-color
# palette below, carried into the .ggfp BASE palette and loaded over CRAM
# line 0 by CharacterDefinition's palette supplier -- NOT from the level
# GPAL's line 0 (which is placeholder data).
formatVersion: 1
paletteLine: 0
palette: ["#000000", "#242424", "#494949", "#929292", "#DBDBDB", "#006DDB", "#49B6FF", "#FF0000", "#6D0000", "#FFFF00", "#00FF00", "#FF9200", "#9200FF", "#00FFFF", "#B66D24", "#FFFFFF"]
frames:
  - delay: 30
    pieces:
      - { sourceX: 0, sourceY: 0, widthPixels: 24, heightPixels: 32, xOffset: -12, yOffset: -16, hFlip: false, vFlip: false, paletteIndex: 0, priority: false }
  - delay: 30
    pieces:
      - { sourceX: 0, sourceY: 32, widthPixels: 24, heightPixels: 32, xOffset: -12, yOffset: -16, hFlip: false, vFlip: false, paletteIndex: 0, priority: false }
```

Convert it with `--playable`, placed immediately after `convert art`:

```text
ggfmod convert art --playable --image src/main/mod/bolt.png --sheet src/main/mod/bolt-sheet.yaml --out target/classes/art/bolt.ggfp
```

**The single-`idle`-animation limitation, stated plainly.** The current
`PlayableArtConverter` always emits **exactly one** animation, hardcoded as
`"idle"`, containing every frame from the sheet in declared order — there is no way
to author separate run/roll/jump/skid cycles through `convert art --playable` today.
Bolt's two frames simply alternate forever at a 30-tick delay each, in every ground
or air state; nothing in this sample's art changes when Bolt jumps, lands, or moves.
This is a real, current converter limitation (not a design choice) — hand-authoring
a `.ggfp` container directly, bypassing the converter, is the only way around it if
your character needs distinct per-state animation today.

**`CharacterDefinition`**, from
[`BoltCharacter.definition(...)`](../../../src/test/resources/mods/sample-platformer-src/project/src/main/java/example/platformer/BoltCharacter.java):

```java
public static CharacterDefinition definition(String owner, MaterializedArt materialized) {
    CharacterKey key = CharacterKey.mod(owner, "bolt");
    return new CharacterDefinition(key, "Bolt", BoltCharacter::new, null,
            PlayerCharacter.SONIC_ALONE, SecondaryAbility.NONE, false,
            ignored -> materialized.art(), ignored -> materialized.palette());
}
```

In order: the owner-tagged `CharacterKey`; the display name; the sprite factory
(`BoltCharacter::new`); `null` for `respawnStrategyFactory` (falls back to
`SonicRespawnStrategy`); `PlayerCharacter.SONIC_ALONE` for `behavesLike` (Bolt routes
through Sonic's solo event/level bucket — there's no team here, since
`supportsSidekick()` is `false`); `SecondaryAbility.NONE`; `false` for
`supportsSuperForm` (mandatory for mod characters); and the art/palette suppliers
sourced from the materialized `.ggfp`. `SecondaryAbility.NONE` is not a contradiction
with the double jump in Chapter 5 — that enum selects a *built-in* ability dispatch
value (`FLY`, `GLIDE`, `INSTA_SHIELD`); a wholly custom ability still declares `NONE`
and implements its own logic in `onAbilityActivate` directly, per
[`characters.md`'s ability rules](../characters.md#ability-and-super-form-rules).

**`PhysicsProfile` knobs, and what each did to Bolt's feel.** `PlatformerModule`'s
`PhysicsProvider` and `BoltCharacter.defineSpeeds()` duplicate the same literal
profile (mod author classes may not hold non-primitive static state, so the values
are simply written twice, mirroring `sample-standalone-src`):

```java
runAccel = 0x20; runDecel = 0x80; friction = 0x20; max = 0x480; jump = 0x780;
slopeRunning = 0x20; slopeRollingUp = 0x14; slopeRollingDown = 0x50;
rollDecel = 0x20; minStartRollSpeed = 0x80; minRollSpeed = 0x80; maxRoll = 0x1000;
rollHeight = 28; runHeight = 38; standXRadius = 9; standYRadius = 19;
rollXRadius = 7; rollYRadius = 14;
```

Compared against `PhysicsProfile.SONIC_2_SONIC` (`runAccel` `0x0C`, `max` `0x600`,
`jump` `0x680`):

| Knob | Bolt | Stock Sonic (S2) | Effect |
|---|---:|---:|---|
| `runAccel` | `0x20` (32) | `0x0C` (12) | ~2.7× snappier off a standstill |
| `max` | `0x480` (1152) | `0x600` (1536) | noticeably lower top speed |
| `jump` | `0x780` (1920) | `0x680` (1664) | higher jump arc |

The net feel is a robot that gets moving fast but never runs as fast as Sonic, and
jumps higher — a genuinely distinct character purely from data, no custom movement
code required. Change one literal, rebuild, and feel the difference immediately;
this is the whole point of the profile being data instead of behavior.

## 5. The double jump

`onAbilityActivate` "fires only for a valid airborne ability-button activation" (see
`AbstractPlayableSprite`'s javadoc, quoted in
[`characters.md`](../characters.md#ability-and-super-form-rules)) — no additional
`getAir()` gate is needed inside the hook itself. Bolt's implementation is a
one-shot latch:

```java
private boolean doubleJumpUsed;

@Override protected boolean onAbilityActivate(boolean up, boolean down, boolean left, boolean right) {
    if (doubleJumpUsed) {
        return false;
    }
    doubleJumpUsed = true;
    setYSpeed((short) -0x600);
    setJumping(false);
    key.ownerModId().ifPresent(owner ->
            currentAudioManager().playNamespacedSfx(new StreamedMusicPort.SfxRef(owner, "jump2")));
    return true;
}
```

Returning `true` on the first mid-air press consumes the button (applying the ROM
-style `-0x600` upward impulse and playing the namespaced `jump2` SFX); returning
`false` on a second press before landing leaves the player's existing velocity
untouched — no infinite-jump exploit.

**The landing reset.** `AbstractPlayableSprite` has no dedicated landing callback, so
Bolt reuses the per-frame `draw()` override — already required for rendering — as
the reset seam:

```java
@Override public void draw() {
    if (!getAir()) {
        doubleJumpUsed = false;
    }
    if (!isHidden() && getSpriteRenderer() != null) {
        getSpriteRenderer().drawFrame(getMappingFrame(), getRenderCentreX(), getRenderCentreY(),
                getRenderHFlip(), getRenderVFlip());
    }
}
```

Every frame Bolt is grounded, the latch clears, re-arming the ability for the next
airborne stretch.

**The rewind checklist step, and why it matters here specifically.** `doubleJumpUsed`
is a plain non-final `boolean` instance field — the shape a mod *object*'s rewind
coverage validator would want (`FINAL_SCALAR_REWIND_GAP` rejects uncaptured *final*
scalar state; a mutable field like this one is exactly what it expects). But
`BoltCharacter` is a **player-character sprite**, not a `RewindRecreatable` mod
object, so it is governed by a different, closed pipeline —
`AbstractPlayableSprite.captureRewindState()` / `PlayerRewindExtra` — and that
Mod API 0.7 pipeline publishes an overridable subclass extension point exactly
for fields like this one. `BoltCharacter` implements both hook halves, copied
verbatim from
[`BoltCharacter.java`](../../../src/test/resources/mods/sample-platformer-src/project/src/main/java/example/platformer/BoltCharacter.java):

```java
/** Immutable payload carrying the double-jump latch through a rewind keyframe. */
private record BoltRewindExtra(boolean doubleJumpUsed)
        implements PerObjectRewindSnapshot.PlayableSubclassRewindExtra {
}

@Override protected PerObjectRewindSnapshot.PlayableSubclassRewindExtra captureSubclassRewindState() {
    return new BoltRewindExtra(doubleJumpUsed);
}

/**
 * Tolerates {@code null} (no subclass payload in the snapshot) by resetting the
 * latch to its fresh default of {@code false} rather
 * than assuming a payload is always present, per the hook's null contract.
 */
@Override
protected void restoreSubclassRewindState(PerObjectRewindSnapshot.PlayableSubclassRewindExtra extra) {
    doubleJumpUsed = extra instanceof BoltRewindExtra bolt && bolt.doubleJumpUsed();
}
```

`captureSubclassRewindState()` runs on every keyframe capture and
`restoreSubclassRewindState(...)` runs on every restore — both including
keyframe-exact seeks and cached-segment scrubs, not just re-simulated seeks — so
`doubleJumpUsed` now round-trips byte-for-byte across any rewind seek, mid-air or
not. The landing reset in `draw()` (Chapter 5, above) is no longer load-bearing for
this correctness — it still clears the latch every grounded frame, which is simply
the right ability-reset behavior independent of rewind. See
[`characters.md`'s rewind section](../characters.md#failure-rewind-and-acceptance-checklist)
for the hooks' full contract (call cadence, ordering guarantee, immutability and
null contracts). Engine-side rewind coverage tests exercise this exact restore path
— see `TestSamplePlatformerIntegration#exerciseDoubleJumpAndRewindLatch`. This
closes the engine gap tracked in [`BACKLOG.md`](../BACKLOG.md)'s "Rewind capture for
mod-character subclass fields" row.

## 6. Badnik + gimmick

**`ZapBug`** extends `AbstractBadnikInstance` and reuses the published
`PatrolMovementHelper` instead of hand-rolling a subpixel counter:

```java
public final class ZapBug extends AbstractBadnikInstance implements RewindRecreatable {
    @Override protected void updateMovement(int frameCounter, PlayableEntity player) {
        int leftBound = spawn.x() - PATROL_RANGE;
        int rightBound = spawn.x() + PATROL_RANGE;

        PatrolMovementHelper.PatrolResult result =
                PatrolMovementHelper.applyVelocity(currentX, xSub, direction * VELOCITY);
        currentX = result.newX();
        xSub = result.newXSub();

        if (currentX <= leftBound) { currentX = leftBound; direction = 1; facingLeft = false; }
        else if (currentX >= rightBound) { currentX = rightBound; direction = -1; facingLeft = true; }
        // ... 2-frame walk animation tick ...
    }
}
```

`PatrolMovementHelper.applyVelocity(x, xSub, velocity)` is the **timer-style,
floor-check-free** three-argument overload — pure 16:8 fixed-point subpixel
accumulation with no terrain probing, appropriate for a badnik that just walks a
fixed range and turns around at explicit bounds rather than at a floor edge.
`leftBound`/`rightBound` are recomputed from `spawn.x()` on every call instead of
being cached in a field: `ggfmod validate`'s `FINAL_SCALAR_REWIND_GAP` check rejects
a *final* scalar instance field it can't restore generically, and `spawn` is already
the single deterministic source `recreateForRewind` rebuilds from, so deriving the
bounds each call sidesteps the whole problem rather than working around it.

`ZapBug` implements the other three required badnik hooks minimally —
`getCollisionSizeIndex()` returns a fixed index, `getDestructionConfig()` returns a
config with no animal/points/explosion factories (a bare hit-and-vanish), and
`onPlayerAttack` fires a namespaced `hit` SFX exactly once (only if not already
destroyed) before delegating to the base class's standard explosion/score/slot
sequence — and `recreateForRewind` simply rebuilds itself from the spawn, the same
minimal pattern every rewind-safe object in this gallery uses.

**`SpringPad`** cannot use the stock spring pipeline at all. Mod API 0.7 does not
publish a solid-object marker interface (`SolidObjectProvider` and friends are
engine-internal), so `SpringPad` does simple proximity + velocity detection every
frame instead of riding `SolidObject`/checkpoint contact:

```java
@Override public void update(int frameCounter, PlayableEntity player) {
    PlayableEntity contact = services().playerQuery().mainPlayerOrNull();
    if (contact != null && contact.getYSpeed() > 0 && isWithinPad(contact)) {
        // STRENGTH_YELLOW is already negative (= upward); do not negate it.
        contact.setYSpeed((short) SpringBounceHelper.STRENGTH_YELLOW);
        contact.setAir(true);
        contact.setOnObject(false);
        services().playSfx(new SfxRef(OWNER, "spring"));
        extendedFramesRemaining = EXTENDED_FRAMES;
    } else if (extendedFramesRemaining > 0) {
        extendedFramesRemaining--;
    }
}
```

`SpringBounceHelper.STRENGTH_YELLOW` is `-0x0A00` — already negative (upward), so it
must not be negated again. `SpringBounceHelper.CONTROL_LOCK_FRAMES` (the stock
15-frame post-launch input lock) **cannot be mirrored**: the setter that would apply
it (`AbstractPlayableSprite#setSpringing`) is not part of the published
`PlayableEntity` surface, so this gimmick launches the player without locking their
input afterward — a known, documented gap versus a ROM-accurate spring, not an
oversight. `extendedFramesRemaining` is a plain non-final counter, rewind-safe the
same way `ZapBug`'s fields are.

## 7. Music and SFX

Standalone audio is declared in
[`audio/audio-manifest.yaml`](../../../src/test/resources/mods/sample-platformer-src/project/src/main/resources/audio/audio-manifest.yaml):

```yaml
formatVersion: 1
tracks:
  - id: zone-theme
    assetPath: audio/zone-theme.ogg
    loop: true
    loopStartFrame: 0
    gain: 1.0
    tempoEffects: false
sfx:
  - id: jump2
    assetPath: audio/jump2.wav
    gain: 1.0
  - id: hit
    assetPath: audio/hit.wav
    gain: 1.0
  - id: spring
    assetPath: audio/spring.wav
    gain: 1.0
```

`zone-theme` loops from source-decoded PCM frame `0` with no `loopEndFrame`, so it
loops through decoded EOF — the simplest legal loop declaration (see
[Music packs](../music-packs.md#audioaudio-manifestyaml) for the full loop-position
rule). Encoding the OGG is the one non-hermetic step in this sample's asset
pipeline: the offline generator
([`SamplePlatformerAssetGenerator`](../../../src/test/java/com/openggf/tools/modsdk/SamplePlatformerAssetGenerator.java))
writes a deterministic WAV chiptune arpeggio, and the checked-in
`audio/zone-theme.ogg` was produced exactly once, by hand, with:

```text
ffmpeg -i zone-theme.wav -c:a libvorbis -q:a 3 zone-theme.ogg
```

...after which the intermediate WAV was deleted. If you ever regenerate this
sample's assets, rerun that exact command yourself and delete the WAV again — the
build does not do this for you, and the WAV must never ship as the actual music
track.

`jump2`, `hit`, and `spring` are ordinary one-shot WAV SFX, played through the
injected service path — `services().playSfx(new SfxRef(OWNER, "hit"))` in `ZapBug`,
the equivalent in `SpringPad`, and `currentAudioManager().playNamespacedSfx(...)` in
`BoltCharacter`'s double-jump hook. Standalone one-shots use a bounded **16-voice
pool**, are presentation-only, never enter the SMPS command timeline, and are
suppressed during rewind (see
[Music packs — "Runtime, rewind, and deterministic modes"](../music-packs.md#runtime-rewind-and-deterministic-modes)).

## 8. Package, trust, play

The checked-in project wires every conversion step into Maven's `generate-resources`
phase via `exec-maven-plugin`, so a plain `mvn package` runs them all in order,
including the TMX/`--music` step from Chapter 3:

```xml
<execution><id>convert-level</id><phase>generate-resources</phase><goals><goal>exec</goal></goals><configuration>
  <executable>java</executable><classpathScope>compile</classpathScope><arguments><argument>-cp</argument><classpath/><argument>com.openggf.tools.modsdk.GgfModCli</argument>
    <argument>convert</argument><argument>level</argument><argument>--from-tmx</argument><argument>${project.basedir}/src/main/mod/level.tmx</argument>
    <argument>--palette</argument><argument>${project.basedir}/src/main/mod/palette.gpal</argument>
    <argument>--music</argument><argument>sample-platformer:zone-theme</argument>
    <argument>--out</argument><argument>${project.build.outputDirectory}/levels/act1</argument>
  </arguments></configuration></execution>
```

...followed by a `package-validated-mod` execution that runs `ggfmod package
--input target/classes --out target/sample-platformer-mod.jar` in `prepare-package`.
The explicit equivalent, spelled out by hand:

```text
mvn compile
ggfmod convert art --playable --image src/main/mod/bolt.png --sheet src/main/mod/bolt-sheet.yaml --out target/classes/art/bolt.ggfp
ggfmod convert art --image src/main/mod/zapbug.png --sheet src/main/mod/zapbug-sheet.yaml --out target/classes/art/zapbug.ggfs
ggfmod convert art --image src/main/mod/springpad.png --sheet src/main/mod/springpad-sheet.yaml --out target/classes/art/springpad.ggfs
ggfmod convert art --image src/main/mod/ring.png --sheet src/main/mod/ring-sheet.yaml --out target/classes/art/ring.ggfs
ggfmod convert level --from-tmx src/main/mod/level.tmx --palette src/main/mod/palette.gpal --music sample-platformer:zone-theme --out target/classes/levels/act1
ggfmod package --input target/classes --out target/sample-platformer-mod.jar
ggfmod validate target/sample-platformer-mod.jar
```

**`registerObjectArt` just works for standalone modules.** `PlatformerModule`
declares no `getObjectArtProvider()` override at all — `PlatformerMod.register()`'s
`context.registerObjectArt(...)` calls from Chapter 2 are the entire object-art
story for `ZapBug` and `SpringPad`. Every standalone module is returned to the
engine wrapped in `OwnerAwareStandaloneModule`'s proxy, and that proxy's
`getObjectArtProvider()` handler decorates whatever the module's own delegate
method returns with the prepared `registerObjectArt` sheets (falling back to an
empty base provider when the delegate itself returns `null`, which is what
`AbstractStandaloneGameModule`'s default does and what `PlatformerModule` inherits
here). The decorated provider is cached after its first build, so later calls don't
re-invoke the module's delegate through the fault boundary. `TestSamplePlatformerIntegration`
asserts this by reflecting on the module's unwrapped delegate (the real
`PlatformerModule` instance behind the proxy) and confirming
`getDeclaredMethod("getObjectArtProvider")` throws `NoSuchMethodException` — the
fixture can't silently regress back to hand-rolling.

A module is still free to override `getObjectArtProvider()` itself — for HUD art,
zone-scoped art, or any provider logic beyond baked sheets — and any
`registerObjectArt` sheets simply layer on top of it, same as they do for
`ModBackedGamePatch` on the patch side.

*Historical note:* earlier revisions of this sample carried a hand-rolled
`SheetBackedObjectArtProvider` because `OwnerAwareStandaloneModule.wrap` used to pass
`getObjectArtProvider()` straight through with no decoration, silently dropping
`registerObjectArt` sheets for standalone owners. That engine gap is fixed — see
[`BACKLOG.md`](../BACKLOG.md)'s "Standalone `registerObjectArt` engine wiring" row —
and this sample was migrated to the engine path as its regression fixture. There is
no workaround left to imitate.

**Trust.** Because this mod carries an entrypoint and therefore executes creator
code, the Mod Manager shows a code-trust prompt naming the jar's exact SHA-256 hash
before it will run (see [Executable-mod trust](../concepts/trust.md)). That prompt
reappears on **every rebuild** — `package` produces a deterministic jar from its
input, but any change to source, art, or level bytes changes the resulting hash,
which is a new grant as far as the trust store is concerned. Don't be surprised that
re-enabling a mod you've already trusted once asks again after a rebuild.

**Playing it.** Drop the packaged jar into your engine's `mods/` directory, start the
engine, open the Mod Manager, enable `Sample Platformer`, and restart. From the
master title, `sample-platformer` appears as its own entry after the three stock
games — no ROM required to see or select it. **New Game** starts Bolt Plains fresh
in namespaced slot 1 (`saves/sample-platformer/slot1.json`); **Continue** appears
only once that slot holds a structurally valid payload whose team and zone/act
topology still resolve. Reaching Bolt Plains' terminal act result (there is only the
one act, so completing it *is* the terminal result) saves and returns to title;
selecting Continue restores Bolt as the saved main character with an empty sidekick
list (`PlatformerModule#supportsSidekick()` is `false`). See
[Standalone games — "Master title and saves"](../standalone-games.md#master-title-and-saves)
for the full contract this sample exercises.

## What proves this actually works

`TestSamplePlatformerIntegration`
(`src/test/java/com/openggf/mods/integration/TestSamplePlatformerIntegration.java`)
is a headless, real-build-and-load test requiring no ROM at all. Its first test
packages this exact project from source, loads it through a real owner class
loader, and exercises the module identity, terminal-progression sentinel, level
loading (confirming both gimmick spawns are present), character registry (physics
profile, art, palette), the double-jump ability hook and its rewind-restore path,
all four packaged audio assets decoding non-zero PCM through the bounded pool, and
the full master-title New Game → save → complete → credits → title →
Continue-restoration flow (plus three corrupt-slot Continue-hiding cases). Its
second test drives `ZapBug` and `SpringPad` directly against a hand-built
`ObjectManager`, proving patrol reversal, the 2-frame walk-animation cadence, spring
launch physics and SFX, the extended-pose frame count, and `recreateForRewind` for
both objects. `TestSampleModsPackage` builds this project alongside the other six
gallery sources as one repository and confirms it validates with zero findings. Run
all three locally with:

```text
mvn "-Dtest=com.openggf.tools.modsdk.TestSampleModsPackage" test
mvn "-Dtest=com.openggf.mods.integration.TestSamplePlatformerIntegration" test
```

Neither test needs a ROM; both run in the default `mvn test` suite unconditionally.

For AI-generated sprite art (including how to swap Bolt's or a gimmick's PNG for
your own), see [AI-generated art](ai-art.md).
