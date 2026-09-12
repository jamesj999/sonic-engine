# Sample Flappy mod source

`project/` is the checked-in output of `ggfmod init --id sample-flappy --package
example.flappysample`, adapted into a Mod API 0.7 Sonic 3 & Knuckles patch. It
inserts an anchorless game-start level, launches native Tails alone, pins the
camera, suppresses horizontal input, and keeps Tails' ROM-faithful flight active
without replacing or hiding the playable sprite. The level contains only a short
generated sky strip and one controller placement; no ROM-derived art is packaged.

For the maintained chapter-by-chapter tour, see
[`docs/modding/guides/native-tails-flappy.md`](../../../../../docs/modding/guides/native-tails-flappy.md)
in the OpenGGF source tree.

## Build

The scripts take three positional arguments: an OpenGGF engine jar, the matching
`openggf-mod-sdk` classifier jar, and a new output directory. They copy `project/`,
decode the checked-in base64 level binaries, then run Maven.

PowerShell:

```powershell
./build.ps1 C:\path\OpenGGF-0.8.prerelease.jar C:\path\OpenGGF-0.8.prerelease-openggf-mod-sdk.jar C:\temp\sample-flappy
```

POSIX shell:

```sh
./build.sh /path/OpenGGF-0.8.prerelease.jar /path/OpenGGF-0.8.prerelease-openggf-mod-sdk.jar /tmp/sample-flappy
```

The packed output is:

```text
<output>/target/sample-flappy-mod.jar
```

The Maven lifecycle runs the real `ggfmod convert art` and `ggfmod convert level`
commands during `generate-resources`, then packages `target/classes` with
`ggfmod package` during `prepare-package`.

## What acceptance proves

The ROM-gated integration test launches the contribution through a real S3K module
and owner class loader. It verifies that native Tails starts visibly at the fixed
screen position with flight active, horizontal velocity suppressed, flight energy
refilled to `0xF0`, and both camera axes pinned. The registration, source-format,
and package tests separately enforce the S3K/Mod API 0.7 manifest, anchorless game-start
contribution, Tails-only launch team, input/HUD policies, strict v2 level shape, and
zero validator findings. No built jar is checked in.
