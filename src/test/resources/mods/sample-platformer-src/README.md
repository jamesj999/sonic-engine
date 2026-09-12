# sample-platformer acceptance sample

This is a complete source fixture for a no-ROM standalone platformer. It contains only
original generated assets: one TMX-authored act converted through the hardened TMX level
pipeline, playable art for a round robot character ("Bolt"), a patrolling badnik ("ZapBug"),
a spring gimmick ("SpringPad"), looping OGG music, and WAV sound effects.

## Build

The scripts take three positional arguments: an OpenGGF engine jar, the matching
`openggf-mod-sdk` classifier jar, and a new output directory. They copy `project/`, then
run Maven.

PowerShell:

```powershell
./build.ps1 C:\path\OpenGGF-0.8.prerelease-jar-with-dependencies.jar C:\path\OpenGGF-0.8.prerelease-openggf-mod-sdk.jar C:\temp\sample-platformer
```

POSIX shell:

```sh
./build.sh /path/OpenGGF-0.8.prerelease-jar-with-dependencies.jar /path/OpenGGF-0.8.prerelease-openggf-mod-sdk.jar /tmp/sample-platformer
```

The packed output is:

```text
<output>/target/sample-platformer-mod.jar
```

The Maven lifecycle invokes the real object-art, playable-art, and TMX level converters,
writing `target/classes/art/{zapbug,springpad,ring}.ggfs`, `target/classes/art/bolt.ggfp`,
and `target/classes/levels/act1/`, then packages `target/classes` with `ggfmod package`.

## What acceptance proves

`TestSampleModsPackage` builds and validates this jar alongside the rest of the gallery
repository. `TestSamplePlatformerIntegration` loads it through its owner class loader and
verifies:

- a `GameId.STANDALONE` module whose identifier/save code is `sample-platformer`;
- one game-agnostic level with solid ground, floating platforms, a pit, ~20 rings, a
  patrolling badnik, and a spring gimmick, all authored in Tiled and converted with
  `ggfmod convert level --from-tmx --palette --music`;
- an owner-tagged `bolt` character with a distinct `PhysicsProfile`, a double-jump
  secondary ability, its landing-reset latch, and the latch's rewind restore path;
- `ZapBug` patrol-and-reverse movement, its 2-frame walk-animation cadence, and namespaced
  `hit` SFX on destruction, all resolved through the engine's `registerObjectArt`-decorated
  `ObjectArtProvider`;
- `SpringPad` proximity-launch physics, its namespaced `spring` SFX, and its 8-frame
  extended pose;
- `recreateForRewind` for both `ZapBug` and `SpringPad`;
- namespaced `zone-theme` streamed music plus `jump2`/`hit`/`spring` one-shot SFX, each
  decoding nonzero PCM through the bounded pool;
- real master-title New Game boot, slot-1 save, terminal credits/title return, and
  Continue restoration; and
- corrupt, fractional, or overflowing save payloads hiding Continue.

For the build-along guide, see
[`docs/modding/guides/standalone-platformer.md`](../../../../../docs/modding/guides/standalone-platformer.md),
and for generating replacement sprite art, see
[`docs/modding/guides/ai-art.md`](../../../../../docs/modding/guides/ai-art.md). For the
creator contract and standalone-game boundaries, see
[`docs/modding/standalone-games.md`](../../../../../docs/modding/standalone-games.md).
