# `ggfmod` creator CLI

The creator CLI is published separately from the engine APIs. Put both artifacts on
the classpath: the `OpenGGF-0.7.prerelease-jar-with-dependencies.jar` supplies the public mod API
and runtime dependencies; `OpenGGF-0.7.prerelease-openggf-mod-sdk.jar` supplies only
the CLI, converters, packager, and project templates. The SDK classifier is not a
standalone jar.

Run `docs/modding/ggfmod.ps1` on Windows or `docs/modding/ggfmod` on macOS/Linux,
passing the two jar paths followed by a command. For example:

```text
ggfmod.ps1 OpenGGF-0.7.prerelease-jar-with-dependencies.jar OpenGGF-0.7.prerelease-openggf-mod-sdk.jar init my-mod --id my-mod --package example.mymod
```

The generated project targets Mod API 0.7 and contains a canonical manifest, a
compilable namespaced sample badnik, a Phase 3 character stub, a Genesis-exact sample
sheet, and a minimal level source in the editor's exact JSON/binary export format.
The character stub demonstrates owner-scoped registration but deliberately has no
playable art or terrain sensors; complete it from the
[character guide](characters.md) before selecting it in gameplay.

`convert art` has two distinct outputs:

```text
ggfmod convert art --image object.png --sheet object-sheet.yaml --out object.ggfs
ggfmod convert art --playable --image runner.png --sheet runner-sheet.yaml --out runner.ggfp
```

`.ggfs` is the ordinary baked object-sheet container. `.ggfp` is playable container
v2: it adds playable animation, palette, bank metadata, and per-frame DPLC sections.
`--playable` must appear exactly once, immediately after `convert art`. The current
converter emits trivial full-frame DPLC runs and prints their pattern-bank cost, so
the result is correct but may use more VRAM than a hand-authored DPLC layout.

`convert level` accepts either the exact full-level export format or the finite Tiled
domain documented by the level reference:

```text
ggfmod convert level --from-export <dir> --out <dir>
ggfmod convert level --from-tmx <map.tmx> --palette <GPAL> [--solid-tiles <profile-dir>] [--music <owner:localName>] --out <dir>
```

`--music <owner:localName>` declares a namespaced streamed track (the `TrackMusic` shape) instead
of the default `StockMusic(0)` placeholder. Standalone levels must carry a namespaced track owned
by the declaring mod (see `ModZoneLoader#loadStandalone`), so any `--from-tmx` level feeding a
standalone module needs this flag.

`convert audio` validates and copies WAV/OGG bytes without transcoding. `package`
creates a deterministic jar and validates its staging jar before atomic publication.
Use `ggfmod validate <mod.jar>` to print the sorted findings for an existing jar.

`run <build-output>` is the only development-directory entry point. It launches the
engine with `-Dggfmod.dev.modDir=<absolute-build-output>`. The engine snapshots that
directory once into engine-owned immutable storage and never rereads the creator tree
during the session. Merely enabling test mode does not enable directory loading.

For complete Mod API 0.7 examples, see [Content mods](content-mods.md),
[Playable characters](characters.md), and [Standalone games](standalone-games.md).
