# Troubleshooting

## Black screen or crash on startup

**Wrong ROM revision.** The engine is verified against specific ROM revisions. Using a
different revision (e.g., Sonic 2 REV00 instead of REV01) will cause incorrect ROM address
lookups, which can result in crashes, garbled graphics, or a black screen.

Check that your ROM files match these revisions:

| Game | Required Revision |
|------|-------------------|
| Sonic 1 | World, REV01 |
| Sonic 2 | World, REV01 |
| Sonic 3&K | World, lock-on combined |

**Missing ROM file.** If the ROM file is not found in the working directory, the engine
will show an error. Ensure the files are in the same directory as the JAR (or the project
root if running from source). ROM filenames can be configured in `config.yaml` -- see
[Configuration](configuration.md).

**Java version too old.** The engine requires Java 21 or later. Run `java -version` to
check. If you have multiple Java installations, ensure the correct one is on your PATH.

**OpenGL version too low.** The engine requires OpenGL 4.1 core profile. This is supported
by most GPUs from the last decade (Intel HD 4000+, NVIDIA GeForce 400+, AMD Radeon HD 5000+).
Check your GPU driver is up to date. On Linux, ensure you have the correct Mesa or
proprietary driver installed.

## No sound

**Audio disabled in config.** Check that `audio.enabled` is `true` (or absent, since
`true` is the default) in `config.yaml`.

**System audio driver issues.** The engine uses LWJGL's OpenAL backend. If your system
audio is routed through an unusual driver or virtual device, playback may fail silently.
Try updating your audio driver or switching to a different output device.

**Audio internal rate mismatch.** If `audio.internalRateOutput` is set to `true`, the
engine outputs at the YM2612 internal sample rate (~53 kHz), which some audio drivers
cannot handle. Set it to `false` (the default).

## Game runs too fast or too slow

**FPS setting.** The engine targets 60 FPS for NTSC (the default). If you have set `display.fps`
to a different value, the game will run faster or slower. Reset to `60`.

**Region setting.** PAL mode (`audio.region: "PAL"`) runs at 50 Hz, which makes the game
approximately 17% slower than NTSC. This is accurate to the original hardware behavior,
not a bug.

**System performance.** If the engine cannot maintain 60 FPS, the game will appear to run
slowly. Check that your GPU driver supports OpenGL 4.1 and that no other application is
consuming GPU resources.

## Garbled graphics or wrong colors

**GPU driver issues.** Update your graphics driver to the latest version. The engine uses
OpenGL 4.1 core profile features that may not work correctly with outdated drivers.

**Window sizing.** If `display.windowAutosize` is disabled and `debug.window.width` /
`debug.window.height` are set to unusual values, the output may look incorrect. Re-enable
autosize or reset the debug window dimensions.

**Wrong ROM revision.** Some graphics data is loaded from specific ROM offsets. A different
ROM revision will cause art to be loaded from wrong addresses, producing garbled tiles.

## "Object registry missing id 0xNN" warnings

These log messages appear when the engine encounters an object in a level's placement data
that it does not have an implementation for. This is normal for zones or games that are
still being developed. The unimplemented object will simply not appear in the level.

Check the object checklists to see if the object is planned:
- [Sonic 2 Object Checklist](../../../OBJECT_CHECKLIST.md)
- [Sonic 1 Object Checklist](../../../S1_OBJECT_CHECKLIST.md)
- [Sonic 3&K Object Checklist](../../../S3K_OBJECT_CHECKLIST.md)

## Special stage issues

**Entering special stages:** In Sonic 2, special stages are entered through checkpoints
with 50+ rings. In Sonic 1, they are entered through giant rings at the end of an act.

**Visual differences:** Special stage rendering is functional but may not be pixel-perfect
compared to the original. This is a known area for improvement.

## S3K zones beyond AIZ don't work properly

S3K support is currently focused on playable route slices from Angel Island through
Hydrocity, with CNZ/MGZ/ICZ work in progress. Other zones may load (tiles and layout
visible) but will likely be missing objects, events, scroll handlers, and other
zone-specific features. This is the expected state of the project, not a bug. See
[Game Status](game-status.md) for details.
