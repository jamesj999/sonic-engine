# Phase 3 standalone acceptance sample

This is a complete source fixture for a no-ROM standalone game. It contains only
original generated assets: one baked level, playable art, a short WAV used for
streamed music and SFX, literal physics, a walking badnik, and owner-tagged main and
sidekick definitions.

## Build

The scripts take three positional arguments: an OpenGGF engine jar, the matching
`openggf-mod-sdk` classifier jar, and a new output directory. They copy `project/`,
decode the checked-in base64 level binaries, PNG, and WAV, then run Maven.

PowerShell:

```powershell
./build.ps1 C:\path\OpenGGF-0.7.prerelease-jar-with-dependencies.jar C:\path\OpenGGF-0.7.prerelease-openggf-mod-sdk.jar C:\temp\phase3-standalone
```

POSIX shell:

```sh
./build.sh /path/OpenGGF-0.7.prerelease-jar-with-dependencies.jar /path/OpenGGF-0.7.prerelease-openggf-mod-sdk.jar /tmp/phase3-standalone
```

The packed output is:

```text
<output>/target/phase3-standalone-mod.jar
```

The Maven lifecycle invokes the real playable-art and level converters, writing
`target/classes/art/runner.ggfp` and `target/classes/levels/sample/`, then packages
`target/classes` with `ggfmod package`.

## What acceptance proves

`TestPhase3StandaloneSampleIntegration` builds and validates this jar, loads it
through its owner class loader, and verifies:

- a `GameId.STANDALONE` module whose identifier/save code is
  `phase3-standalone`;
- one game-agnostic `ModLevel`, its namespaced badnik, and terminal credits
  topology with no ROM capability;
- owner-tagged `runner` and `friend` characters, playable art/palette, and literal
  non-stock physics;
- normal object-service update and namespaced `hit` SFX dispatch;
- real WAV decode and nonzero PCM mixing through the bounded one-shot pool;
- real master-title New Game boot with an empty ROM root and namespaced streamed
  `zone-theme` music;
- slot-1 save, terminal credits/title return, and Continue restoration of saved
  location and team; and
- corrupt, fractional, overflowing, negative, or topology-invalid save payloads
  hiding Continue.

For the creator contract and Phase 3 non-goals, see
[`docs/modding/standalone-games.md`](../../../../../docs/modding/standalone-games.md).
