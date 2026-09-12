# Build a ROM-art remix

This source-first guide follows the maintained
[`sample-rom-art-remix`](../../../src/test/resources/mods/sample-rom-art-remix-src/README.md)
project. It builds a small additive Sonic 2 patch whose object displays Tails' two
flight frames without putting those graphics, mappings, or DPLCs in the mod jar.
The engine reads the bounded inputs from the player's own ROM only when gameplay
launches.

Build OpenGGF before the sample so both artifacts expected by the scripts exist:

```powershell
mvn package
src/test/resources/mods/sample-rom-art-remix-src/build.ps1 `
  target/OpenGGF-0.8.prerelease.jar `
  target/OpenGGF-0.8.prerelease-openggf-mod-sdk.jar `
  target/sample-rom-art-remix-build
```

The project targets the current Mod API candidate range `>=0.7.0 <0.8.0`, whose
surface includes ROM-art intake. Start from the checked-in source rather than copying a built jar;
its registration, object, level source, build scripts, and package checks are all
part of the maintained example.

## 1. Register the object and zone

`RomArtRemixMod` makes three transactional contributions: the display
object, its ROM-art request, and a zone inserted after EHZ2. The relevant shape is:

```java
context.registerObject("tails-flight-art",
        (spawn, registry) -> new TailsFlightArtObject(spawn));
context.registerRomObjectArt("tails-flight", new RomArtRequest(
        0x64320, RomArtCompression.UNCOMPRESSED, 0xB8C0,
        0x739E2, 0x7446C, 0, 1));
context.registerZone(new ModZoneContribution("rom-art-gallery",
        new BakedLevelRef("levels/rom-art-gallery/level.json"), "ehz2", null));
```

Registration remains inside `ModContext`; the engine assigns the owner and
namespaces both local keys. The object obtains the prepared renderer by its owned
`tails-flight` key rather than reading the ROM or opening files itself.

## 2. Keep the request Sonic 2-only

`registerRomObjectArt` is accepted only for an additive patch declaring
`baseGame: s2`. Standalone games and patches for another base game fail
registration. Registration happens before a player's ROM is open, so the engine
can validate the request only against its static Sonic 2 address bounds at this
stage. Actual byte reads and format parsing remain launch-time work.

This separation is deliberate: the source project can compile, package, and pass
catalog validation without possessing a ROM, while a gameplay launch still fails
with an owner-attributed diagnostic if the supplied Sonic 2 image cannot satisfy
the request.

## 3. Request the bounded ROM window

Every field in `RomArtRequest` contributes to the allocation and parsing boundary:

| Field | Sample value | Contract |
| --- | ---: | --- |
| `artAddress` | `0x64320` | Start of the uncompressed Tails pattern bytes. |
| `compression` | `UNCOMPRESSED` | Decoder selected for the art window. |
| `uncompressedByteSize` | `0xB8C0` | Positive multiple of 32 for uncompressed Genesis patterns. |
| `mappingAddress` | `0x739E2` | Start of the bounded Sonic 2 mapping table. |
| `dplcAddress` | `0x7446C` | Start of the player-format DPLC table; `0` means no DPLC flattening. |
| `paletteLine` | `0` | Genesis palette line index, restricted to `0` through `3`. |
| `bankSize` | `1` | Positive static-sheet bank count. |

The address literals correspond to the Sonic 2 Tails art, mappings, and DPLC
constants. For a different stock object, find the correct labels and compression
with `RomOffsetFinder --game s2`; do not widen the request speculatively. Sheet
patterns, frames, and pieces remain subject to `ModInputLimits` after decoding.

## 4. Materialize only at launch

When the patched game launches, `ModBackedGamePatch` asks the ROM-art materializer
to read the player's active Sonic 2 data source. It decodes the requested art,
parses the mapping frames, parses the optional player DPLC table, and applies the
static DPLC flattener. The result is an in-memory `ObjectSpriteSheet` served through
the ordinary object-art provider.

No ROM-derived sheet is written to disk, added to a cache, or persisted in a save.
The mod package contains only its original Java bytecode, metadata, and baked level
data. This launch-memory boundary is what makes the example redistributable.

## 5. Preserve mapping and DPLC frame order

Mapping frame `N` pairs with DPLC frame `N`. The materializer preserves the ROM
table order while flattening the dynamic tile loads into a static sheet; never sort,
deduplicate, or renumber one table independently of the other. In this sample,
frames 94 and 95 are the two Sonic 2 Tails flight poses, so the materialized sheet
must contain more than 95 frames.

Sonic 2 palette line 0 is Pal_SonicTails, shared by Sonic and Tails, so the
sample works with the default Sonic team. A Knuckles-main lock-on changes line
0 indices 2-5 and may recolour borrowed Tails art.

For Sonic 2 patch zones, the host supplies active ROM palette line 0 after the
creator level is decoded; creator-owned lines 1 through 3 remain unchanged. This
keeps the shared Sonic/Tails colors accurate without making palette bytes part of
the mod.

## 6. Probe decoded patterns, not a framebuffer

Headless tests do not capture OpenGL output. The integration test therefore checks
the decoded representation that drives rendering: frames 94 and 95 each have at
least one `SpriteMappingPiece`, each first piece selects palette index 0, and each
referenced pattern span contains a non-zero decoded pixel. The integration then
steps five real frames and verifies that the live object visibly advances from
frame 94 to frame 95. This catches wrong addresses, mapping/DPLC misalignment,
blank materialization, and disconnected animation without inventing a framebuffer
test seam.

## 7. Keep object animation rewind-safe

`TailsFlightArtObject` stores `animTick` as a non-final instance scalar. Each update
increments it modulo eight and chooses frame `94 + animTick / 4`, yielding four
ticks per flight pose. The object implements `RewindRecreatable`, so recreation
uses the same mod classloader and object identity path as the live instance.

The generic rewind machinery captures the non-final scalar. The real-ROM
integration steps five frames to displayed frame 95, seeks back to frame zero, and
confirms the stable object reference identity, frame 94, and initial tick before
replay. Do not make gameplay scalars `static` or `final`: either form would fall
outside this ordinary per-instance capture contract.

## 8. Inspect the redistributable package

After building, inspect the jar itself rather than trusting the source tree:

```powershell
jar tf target/sample-rom-art-remix-build/target/sample-rom-art-remix-mod.jar
```

The listing may contain the mod manifest, classes, and baked level binaries. It
must not contain a `.gen` ROM or an `art/tails-flight.ggfs` baked copy of the
borrowed sheet. The maintained package test enforces the same rule, and the
ROM-gated integration can be run explicitly with:

```powershell
mvn "-Dsonic2.rom.path=s2.gen" `
  "-Dtest=com.openggf.mods.code.TestSampleRomArtRemixIntegration" test
```

That integration is allowed to skip when no valid ROM is supplied during ordinary
development. Release verification supplies the explicit property and requires its
Surefire report to contain zero skipped tests.
