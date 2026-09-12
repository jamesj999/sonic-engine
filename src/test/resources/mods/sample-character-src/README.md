# Phase 3 character acceptance sample

This is a complete source fixture for a Sonic 2 character mod, not a distributable
game asset. It contains original generated art, a trusted entrypoint, an owner-tagged
`CharacterDefinition`, a distinct physics-provider patch, and a Maven build that
converts playable art and packages a validated mod jar.

## Build

The scripts take three positional arguments: an OpenGGF engine jar, the matching
`openggf-mod-sdk` classifier jar, and a new output directory. The scripts copy
`project/` into the output directory and decode the checked-in base64 PNG before
running Maven.

PowerShell:

```powershell
./build.ps1 C:\path\OpenGGF-0.7.prerelease-jar-with-dependencies.jar C:\path\OpenGGF-0.7.prerelease-openggf-mod-sdk.jar C:\temp\phase3-character
```

POSIX shell:

```sh
./build.sh /path/OpenGGF-0.7.prerelease-jar-with-dependencies.jar /path/OpenGGF-0.7.prerelease-openggf-mod-sdk.jar /tmp/phase3-character
```

The packed output is:

```text
<output>/target/phase3-character-mod.jar
```

The Maven lifecycle runs the real
`ggfmod convert art --playable ... --out target/classes/art/runner.ggfp`
command before compiling and runs `ggfmod package` over `target/classes` during
`prepare-package`.

## What acceptance proves

`TestPhase3SampleCharacterIntegration` builds this project from source, discovers
and validates the packed jar, grants trust, and verifies:

- the canonical `phase3-character:runner` identity and `Phase Runner` launch label;
- a nonempty playable-v2 art set and palette loaded from `runner.ggfp`;
- the mod's distinct `PhysicsProfile`, `SONIC_ALONE` route, `NONE` ability, and
  disabled super form;
- owner-class-loader construction with no sidekick;
- rewind recreation retaining the character identity and position;
- save payload round-trip of the canonical main-character key; and
- deterministic launch policy excluding the external character and leaving the
  stock Sonic 2 module undecorated.

For the creator contract, see
[`docs/modding/characters.md`](../../../../../docs/modding/characters.md).
