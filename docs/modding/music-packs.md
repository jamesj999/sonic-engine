# Music packs

Phase 1 music packs are restart-loaded patch mods that replace stock music with
bounded WAV or Ogg/Vorbis assets. They do not contain executable code, and they
never replace ROM data on disk.

The music-pack path accepts packed, data-only patch JARs only. The current
Mod API 0.7 also supports code-backed objects, art reskins, Sonic 2 zones,
playable characters, no-ROM standalone games, and explicit development-directory
runs through `ggfmod`; those use separate eligibility and trust rules documented in
[Content mods](content-mods.md). Standalone games may play declared streamed SFX
through their own namespaced one-shot route. Base-game streamed SFX overrides, MP3,
and hot reload remain unsupported.

## Source and packaged layout

Package the source tree as a JAR and place the JAR in the process `mods/`
directory. Paths in both manifests are case-sensitive, forward-slash-separated,
normalized JAR paths.

```text
META-INF/openggf-mod.yaml
audio/audio-manifest.yaml
audio/emerald-hill.wav
```

Open the Mod Manager through the master title's `MODS` action. Newly discovered
mods start disabled. Enable, disable, or reorder entries; dependency cascades
require confirmation. Apply saves the pending state to `mods/modstate.json` without
leaving the manager; Back asks before discarding unsaved changes.
Restart OpenGGF to apply it: the running catalog and prepared audio session are
immutable. A failed owner is disabled for the next start while independent
owners and stock fallbacks continue.

## `META-INF/openggf-mod.yaml`

Every field below is required unless marked optional. Unknown fields, duplicate
mapping keys, YAML aliases, anchors, tags, merge keys, and trailing documents are
rejected.

```yaml
formatVersion: 1
id: example-music
name: Example Music Pack
version: 1.0.0
authors:
  - Example Author
description: Replaces Emerald Hill music with an original recording.
engineApiRange: ">=0.7.0 <0.8.0"
type: patch
baseGame: s2
dependencies: []
audioOverrides:
  129: emerald-hill
artOverrides: {}
# Optional for code-bearing/future content:
# entrypoint: org.example.ExampleEntrypoint
# insertAfter: a-stock-key
# patternWindows: 1
```

- `formatVersion` is exactly `1`.
- `id` matches `[a-z0-9][a-z0-9-]{0,63}`. `name`, `description`, and each of
  1–32 `authors` must be nonblank.
- `version` is a strict `MAJOR.MINOR.PATCH` triple of nonnegative decimal
  integers: no `v` prefix, missing component, leading zero, prerelease, or build
  suffix.
- `engineApiRange` and dependency `versionRange` accept `*`, one exact version,
  or at most four whitespace-separated `<`, `<=`, `=`, `>=`, or `>`
  comparators. Ranges must not be empty or contradictory. The current Mod API is
  `0.7.0`; maintained manifests use `>=0.7.0 <0.8.0`.
- The schema accepts `type: patch` or `standalone`. A music pack uses `patch` and
  requires `baseGame: s1`, `s2`, or `s3k`; a standalone manifest omits `baseGame`,
  requires a trusted entrypoint, and follows the separate
  [standalone-game contract](standalone-games.md).
- Each dependency is `{id, versionRange}`. Dependency ids must be unique.
- `audioOverrides` is an explicit mapping from a canonical nonnegative decimal
  stock music id to a track's mod-local `id`. Use decimal keys (`129`), not YAML
  hexadecimal (`0x81`). The id must exist in the selected game's stock music
  domain and the local track must be declared in the audio manifest.
- `artOverrides` is required even for music-only packs; use `{}`.
- Optional `entrypoint`, `insertAfter`, and `patternWindows` are reserved future
  fields. They are parsed strictly, but any explicit value makes a Phase 1 pack
  ineligible.

Stock ids are game-specific. The authoritative lists are `Sonic1Music`,
`Sonic2Music`, and `Sonic3kMusic`; for example, Sonic 2 Emerald Hill is decimal
`129` (`0x81`). When multiple enabled packs replace the same stock id, the later
pack in effective load order wins and the manager reports the conflict.

## `audio/audio-manifest.yaml`

```yaml
formatVersion: 1
tracks:
  - id: emerald-hill
    assetPath: audio/emerald-hill.wav
    loop: true
    loopStartFrame: 22050
    loopEndFrame: 110250
    gain: 0.8
    tempoEffects: true
sfx: []
```

The root fields are exactly `formatVersion`, `tracks`, and optional `sfx`.
`formatVersion` is exactly `1`. Every track requires exactly `id`, `assetPath`,
`loop`, `loopStartFrame`, `gain`, and `tempoEffects`; `loopEndFrame` is optional.
Every SFX entry requires exactly `id`, `assetPath`, and `gain`. Patch music packs
still use `sfx: []`: base-game SFX override routing is not implemented. A standalone
game may declare SFX; each becomes a namespaced `SfxKey` and is decoded into bounded
PCM for the standalone one-shot pool.

- A local `id` matches `[a-z0-9][a-z0-9._/-]{0,127}` with no empty, `.` or
  `..` path segment. At runtime it becomes the namespaced `TrackKey`
  `manifest-id:local-id`. Author manifests use the local id; engine registries,
  saved logical playback, and rewind use the full `TrackKey`. A namespaced key
  does not allocate or hijack a stock numeric id.
- `assetPath` is a lower-case normalized path under `audio/` ending in `.wav`
  or `.ogg`. The file must be inside the same JAR.
- `gain` is finite and in `0.0..4.0`.
- For a non-looping track, set `loop: false`, `loopStartFrame: 0`, and omit
  `loopEndFrame`.
- Loop positions are zero-based PCM frame offsets in the decoded source (one
  frame contains all channels), before conversion to the audio device rate.
  For a looping track, the start must be before the end; omit `loopEndFrame` to
  loop through decoded EOF. Both bounds must fit the decoded source.
- `tempoEffects: true` opts into the current streamed speed-shoes approximation:
  any requested multiplier above 1 plays at `1.25x`. `false` keeps `1.0x`.
  This is rate/pitch coupling, not independent time stretching.

## Production limits

Limits are applied before or during decoding and cannot be raised by a mod:

| Resource | Limit |
|---|---:|
| Manifest/audio metadata | 1 MiB each |
| JAR size / entries | 1 GiB / 16,384 |
| Entry-name UTF-8 bytes / aggregate entry-name bytes | 512 / 4 MiB |
| Single JAR entry after inflation | 64 MiB |
| Validation bytes per mod | 512 MiB |
| Music duration / decoded PCM | 600 s / 256 MiB |
| SFX duration / decoded PCM | 30 s / 32 MiB |
| SFX voices / prepared audio cache | 16 / 512 MiB |
| Source sample rate | 8,000–192,000 Hz |
| Source channels / gain | 1–2 / 0.0–4.0 |
| Collection entries / YAML depth / aliases | 10,000 / 32 / 0 |
| YAML document code points | 1,048,576 |
| Metadata string characters / numeric digits | 65,536 / 20 |

The repository is also capped at 1,024 mod JARs and 8 GiB of aggregate
validation input. Audio is decoded and resampled during launch, never on the
presentation thread.

## Runtime, rewind, and deterministic modes

The backend owns either stock SMPS music or one streamed foreground source.
Stock jingle/override stack behavior, pauses, fades, drowning replacement, and
restoration rules are preserved. Presentation mixing performs no filesystem I/O.

Rewind keyframes store logical streamed state: `TrackKey`, logical stock id,
source-frame position, pause mask, fade cadence/gain, playback rate, and the
aligned override stack. PCM buffers are presentation-only and are not copied
into keyframes. Restore resolves the saved key against the still-open prepared
session; a missing key fails closed to stock SMPS or silence. Re-simulation
bypasses live mod resolution so replay cannot overwrite the restored stream.

Tests, headless tools, and trace processes choose a startup-deterministic policy
before resolving or scanning `mods/`. Attempt replay, recording playback, trace
capture, and all Time Attack modes entered after a normal boot atomically install
an empty external-content view, release prepared PCM, invoke no mod callback,
and perform no new scan before deterministic stepping. Returning to the title
keeps the process catalog visible but does not hot-restore content into the old
session; the next normal launch prepares a fresh audio session at the negotiated
device rate.

Standalone one-shots are presentation-only. They use a bounded 16-voice pool, are
not recorded in the SMPS rewind command timeline, and are suppressed during rewind.
They do not allocate numeric stock ids and cannot replace a stock game's sound
effect. See [Standalone games](standalone-games.md) for the launch and ownership
rules.

## Checked-in sample

[`samples/phase4-gallery-music-pack`](samples/phase4-gallery-music-pack/README.md)
is a self-contained source sample for future gallery CI. Its audio is generated
from an original mathematical waveform, so it carries no game or third-party
assets.
