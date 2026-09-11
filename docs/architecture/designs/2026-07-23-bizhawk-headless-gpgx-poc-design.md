# Headless BizHawk GPGX Proof of Concept

## Requirements

### Goal

Prove that OpenGGF can generate deterministic Sonic reference data through a
native C# console harness around BizHawk's GPGX core, without starting EmuHawk,
WinForms, X11, video rendering, or audio output.

The first milestone is deliberately small. It loads the Sonic 1 REV01 ROM,
replays a power-on Genesis BK2 movie through a declared warm-up offset, records
at most the following 1,000 frames, and writes a diagnostic CSV containing the
trace-row number, P1 input mask, player centre coordinates, and player
velocities.

### Non-goals

- Reproducing the complete Sonic 1 trace schema.
- Running the existing Lua recorders.
- Supporting Sonic 2 or Sonic 3&K.
- Supporting Windows.
- Supporting savestate-start BK2 movies.
- Supporting memory execute/write callbacks or CPU-register capture.
- Shipping or vendoring BizHawk binaries, ROMs, movies, or copyrighted assets.
- Replacing the existing BizHawk or stable-retro trace pipelines yet.

### Constraints

- Pin BizHawk 2.11, source commit
  `427556b5ef3ac437eba754d90c5e7e9096c9a8df`, which matches the version used by
  the current OpenGGF BizHawk tooling.
- Use the verified local BizHawk 2.11 Linux binary distribution and reference
  its managed assemblies directly.
- Use Mono on Linux for the initial build and runtime.
- Treat compilation and one direct ten-frame GPGX advance under Mono 6.12 as a
  feasibility gate. No parser or recorder implementation proceeds until that
  gate passes with the release dependency set.
- Load the GPGX core directly. Do not instantiate EmuHawk or any frontend
  display, audio, or movie-session component.
- Reject ROMs whose SHA-1 is not the Sonic 1 World REV01 hash
  `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`.
- Keep BizHawk release-location assumptions outside recorder logic so the tool
  can later migrate to project references against a source checkout.
- Treat ROM `x_pos` and `y_pos` as centre coordinates.

### Acceptance criteria

1. The tool compiles on the current Linux development environment using Mono
   and the locally installed BizHawk 2.11 distribution.
2. It runs with `DISPLAY` unset and does not instantiate EmuHawk, WinForms,
   rendering, or audio output.
3. It validates the ROM hash and BK2 platform/core/controller/sync assumptions
   before emulation, and applies the accepted sync profile to the constructed
   GPGX core.
4. It replays a supported power-on Genesis BK2 through the declared warm-up
   offset and records the following 1,000 frames using its declared `LogKey`.
5. It advances GPGX with `render: false` and `renderSound: false`.
6. It emits `smoke.csv` with deterministic frame, input, centre-position, and
   velocity values.
7. Two identical invocations produce byte-identical CSV files.
8. Selected frames agree with an existing canonical BizHawk Lua trace recorded
   from the same ROM and movie.
9. An interrupted or failed run does not leave a finalized `smoke.csv`.

### Assumptions

- The existing `tools/bizhawk/fetch_bizhawk_2_11_linux.sh` installation remains
  the authoritative way to obtain the binary distribution.
- The distribution contains a mutually compatible managed dependency set,
  `gpgx.wbx.zst`, and `libwaterboxhost.so`.
- The first selected BK2 is the tracked
  `src/test/resources/traces/s1/ghz1_fullrun/ghz1_fullrun.bk2`, SHA-256
  `dced61b2d3a3346b2ecd62254140497ef2827374c1de8597780f91e39ca0dcea`.
- Its canonical comparison trace is
  `src/test/resources/traces/s1/ghz1_fullrun/physics.csv`, SHA-256
  `dd0a03bfddefa9570d4b49ee2d4ea5e35e2b8141147e17ab482a3654d311cb66`.

### Risks

- BizHawk resolves its DLL directory as `$BIZHAWK_HOME/dll` on Unix. The
  launcher must export an absolute `BIZHAWK_HOME`, set `MONO_PATH` and
  `LD_LIBRARY_PATH` before Mono starts, and verify the required files before
  launching.
- Direct references to a release DLL set can expose transitive assembly-loading
  failures. The launcher must report the missing assembly or native library.
- BK2 input rows are grouped according to `LogKey`; fixed character offsets
  would silently replay the wrong controls. The parser must derive groups and
  button order from the header.
- Movie sync settings affect deterministic emulation. The POC must instantiate
  the exact supported profile from the movie rather than merely inspect it.
- Recorder timing can be off by one frame. Tests must prove that each row
  describes state after advancing the corresponding BK2 input row.
- The BizHawk binary bundle contains components with varied licenses. The POC
  downloads/uses the official distribution locally and does not redistribute
  it.

## Exploration Synthesis

OpenGGF currently launches EmuHawk with Lua recorders under
`tools/bizhawk/`. Although these launchers disable visible rendering and audio,
EmuHawk remains a WinForms application on Linux and requires a display server.
The existing tooling pins BizHawk 2.11 because later frontend API changes affect
the recorders.

BizHawk's `BizHawk.Tests.Testroms/DummyFrontend.cs` demonstrates the core
headless loop:

- construct `CoreComm`;
- construct an emulator core;
- create `SimpleController` from its `ControllerDefinition`;
- call `FrameAdvance(controller, render: false, renderSound: false)`;
- obtain `IMemoryDomains` and `IDebuggable` from the core service provider.

The concrete GPGX constructor accepts
`CoreLoadParameters<GPGXSettings, GPGXSyncSettings>`. The pinned BizHawk 2.11
GPGX runtime exposes Genesis work RAM as `68K RAM`, alongside the M68K bus,
registers, lag state, and memory callbacks. Only that work-RAM domain is needed
for this milestone.

BK2 files are ZIP archives. The required first-milestone entries are
`Header.txt`, `SyncSettings.json`, and `Input Log.txt`. `Input Log.txt` declares
button order through `LogKey`, followed by grouped input rows. A small native
parser avoids importing the EmuHawk movie-session lifecycle that this POC is
intended to bypass.

The repository already contains a verified Linux installer and a local
BizHawk 2.11 distribution. The current environment provides Mono 6.12, `csc`,
`mcs`, and `xbuild`; it does not currently provide the .NET SDK. Direct release
assembly references are therefore the smallest viable first step.

This remains a hypothesis until the first implementation task compiles a
minimal executable against the release assemblies and advances GPGX ten frames
under Mono 6.12. Failure of that spike stops this design and triggers a choice
between a pinned source build and a different supported compiler/runtime.

## Architecture Decision

### Decision

Create a Linux-only C# console tool under `tools/bizhawk-headless/`. Use
non-SDK, .NET Framework 4.8-compatible MSBuild projects accepted by Mono
`xbuild`. Compile them against the verified BizHawk 2.11 release assemblies and
run with managed and native search paths pointing at that installation.

The application owns no EmuHawk, WinForms, graphics, sound, Lua, or Client.Common
movie-session objects.

### Components

#### `Program`

Parses CLI arguments, performs validation, creates the host and recorder, runs
the bounded frame loop, and returns a non-zero exit code with a concise error on
failure.

#### `BizHawkBootstrap`

Validates the configured BizHawk installation. On Linux the launcher exports:

```text
BIZHAWK_HOME=<absolute path to docs/BizHawk-2.11-linux-x64>
MONO_PATH=$BIZHAWK_HOME/dll
LD_LIBRARY_PATH=$BIZHAWK_HOME/dll:<existing value>
```

BizHawk's Unix `PathUtils` consequently resolves
`DllDirectoryPath` to `$BIZHAWK_HOME/dll`. Before starting Mono, the launcher
requires `BizHawk.Common.dll`, `BizHawk.Emulation.Common.dll`,
`BizHawk.Emulation.Cores.dll`, `BizHawk.BizInvoke.dll`, `gpgx.wbx.zst`, and
`libwaterboxhost.so`. The caller's working directory is not used for asset
resolution. Release-layout knowledge is confined here and in build/launcher
scripts.

Before constructing any BizHawk type, the process has `BIZHAWK_HOME` set. The
feasibility gate asserts at runtime that `PathUtils.DllDirectoryPath` equals the
absolute `$BIZHAWK_HOME/dll` path.

#### `IGpgxHost`

Defines the recorder-facing emulator boundary:

- expose the current completed frame number;
- apply named controller button states;
- advance one frame without video or sound;
- read bytes and signed/unsigned words from Genesis work RAM.

It deliberately omits callbacks, registers, savestates, and rendering until a
later milestone requires them.

#### `BizHawkGpgxHost`

Constructs `CoreComm`, `GameInfo`, a local immutable `IRomAsset`
implementation, GPGX settings, and a local mutable `IController`
implementation. `CoreComm` receives a local `ICoreFileProvider` whose firmware
methods fail clearly if invoked, and `CorePreferencesFlags.None`; Sonic 1
Genesis cartridge emulation requires no firmware.

The host resolves the pinned runtime's `68K RAM` Genesis work-RAM domain and
implements `IGpgxHost`. It tries `68K RAM` first; `Main RAM` is accepted only
as a compatibility fallback for a future source-backed API. The selected domain
must be exactly 65,536 bytes. The host owns and disposes the core. It does not reference
`BizHawk.Client.Common.SimpleController`, keeping the compile-time dependency
boundary at BizHawk Common, Emulation.Common, Emulation.Cores, BizInvoke, and
their runtime dependency closure.

#### `Bk2Reader`

Reads the BK2 ZIP, validates `Header.txt` and the one supported
`SyncSettings.json` profile, derives controller groups and button order from
`LogKey`, and streams typed input frames. Unsupported platforms, cores,
controller layouts, malformed rows, or savestate-start requirements are
errors.

For milestone one, the accepted sync profile is the tracked GHZ1 movie's GPGX
profile: `UseSixButton=false`, `ControlTypeLeft=1`, `ControlTypeRight=1`,
`Region=0`, `ForceVDP=0`, `LoadBIOS=false`, and `Overscan=3`. All remaining
serialized fields are parsed and required to equal that fixture. These values
are assigned to the corresponding GPGX settings object before core
construction. An unknown or omitted field is rejected rather than defaulted.
The raw tracked `SyncSettings.json` payload has SHA-256
`8f4130ebee1f1593080371f1d257477fbb2cc68c1cb691620736639e768c97bc`;
the committed real-movie parser fixture preserves that payload verbatim.

#### `S1SmokeRecorder`

Reads a fixed, documented set of Sonic 1 Genesis work-RAM fields after each completed
frame and writes:

```text
frame,input,x,y,x_velocity,y_velocity
```

This is a diagnostic milestone format, not a new canonical OpenGGF trace schema.

#### Linux scripts

A build script compiles against the configured BizHawk installation. A run
script locates the verified repository-local installation, supplies Mono and
native-library paths, unsets frontend-only assumptions, and invokes the console
application.

### Project and test layout

```text
tools/bizhawk-headless/
  BizHawk.Headless.Gpgx.csproj
  BizHawk.Headless.Gpgx.Tests.csproj
  src/
  tests/
  fixtures/
  build.sh
  test.sh
  run.sh
```

Both projects are non-SDK projects targeting .NET Framework 4.8-compatible Mono
assemblies. The test project is a dependency-free console runner with focused
test methods and assertion helpers; any failure prints the test name and returns
non-zero. This avoids adding a NuGet test framework for the POC.

The exact gates are:

```bash
tools/bizhawk-headless/build.sh
tools/bizhawk-headless/test.sh
env -u DISPLAY tools/bizhawk-headless/run.sh ...
```

`build.sh` invokes `xbuild` for both projects. `test.sh` runs the test executable
through Mono with the same BizHawk environment as `run.sh`. A future
source-build migration converts these project files to SDK style while
retaining the source directories, interfaces, fixtures, and tests, then
replaces assembly references with pinned project references. It does not ask
`xbuild` to consume BizHawk's SDK-style source projects.

### Data flow

1. Validate CLI paths, BizHawk installation, ROM SHA-1, and BK2 metadata.
2. Construct GPGX and resolve `68K RAM` (with `Main RAM` only as the future
   source-backed compatibility fallback), requiring exactly 65,536 bytes.
3. Apply and advance every BK2 row before `--bk2-frame-offset` without emitting
   output.
4. Exclusively create a uniquely named temporary CSV beside the requested
   output. Concurrent runs never share a temporary path.
5. For each of the next `--max-frames` BK2 rows:
   1. clear the prior controller state;
   2. apply the row's Power/Reset/P1 button states;
   3. advance GPGX once without rendering or sound;
   4. read the just-completed frame's S1 RAM values;
   5. append one CSV row.
6. Flush and close the temporary file.
7. Atomically publish it to `smoke.csv` with no-replace semantics. On Linux this
   uses a hard-link publication in the same directory (`link(temp, final)`)
   followed by deletion of the temporary name; an existing final path makes
   `link` fail without replacing it.

### Migration to a source-built BizHawk

The future source-build migration replaces release DLL references with
`ProjectReference` entries into a pinned BizHawk checkout. The CLI, BK2 reader,
recorder, `IGpgxHost`, tests, and data flow remain unchanged. Necessary
BizHawk-version API adjustments stay inside `BizHawkGpgxHost` and bootstrap
wiring. The first source-backed configuration pins the checkout to the same
BizHawk 2.11 commit and continues using `gpgx.wbx.zst` and
`libwaterboxhost.so` from the verified 2.11 release, with their checksums
verified by bootstrap. A later, separate milestone may build Waterbox artifacts
from source and must establish a new golden differential gate before replacing
the release artifacts. Project references alone are never treated as a
complete runnable GPGX distribution.

### Rollback

The POC is additive and does not change existing trace generation. Removing
`tools/bizhawk-headless/` restores the prior state. Existing Lua and stable-retro
recorders remain authoritative until a later milestone explicitly replaces
them.

## Feature Design

### CLI

```bash
tools/bizhawk-headless/run.sh \
  --rom "/path/to/s1.gen" \
  --movie "/path/to/ghz1.bk2" \
  --output target/bizhawk-headless-smoke \
  --bk2-frame-offset 840 \
  --max-frames 1000
```

Required arguments are `--rom`, `--movie`, and `--output`.
`--bk2-frame-offset` defaults to `0` and must be non-negative.
`--max-frames` defaults to `1000` and must be in the inclusive range
`1..1000`.

The output directory may be created by the tool. An existing finalized
`smoke.csv` is not overwritten. Finalization uses same-directory hard-link
publication so a file created between the initial check and publication is
also preserved.

### Supported movie subset

- `Header.txt` must contain exactly one `Core Genplus-gx` entry and one
  `Platform GEN` entry. Duplicate keys are rejected. `StartsFromSavestate` and
  `StartsFromSaveRam` must both be absent or parse as `false`; true values are
  rejected.
- Start: power-on, without an embedded savestate or SaveRAM dependency.
- `Input Log.txt` contains a single `[Input]` ... `[/Input]` section. Its first
  nonblank line is:

  ```text
  LogKey:#Power|Reset|#P1 Up|P1 Down|P1 Left|P1 Right|P1 A|P1 B|P1 C|P1 Start|#P2 Up|P2 Down|P2 Left|P2 Right|P2 A|P2 B|P2 C|P2 Start|
  ```

- Each subsequent input row is exactly
  `|SS|PPPPPPPP|QQQQQQQQ|`, where each character is `.` for released or a
  non-dot button marker for pressed. `SS`, `PPPPPPPP`, and `QQQQQQQQ` map by
  their declared group entries, not by a second hardcoded button table.
- Controllers: system Power/Reset plus the declared P1 three-button layout.
- P2 inputs may be parsed but are rejected if active in milestone one.
- Button order is derived from `LogKey`; it is never hardcoded to character
  offsets.

The smoke CSV `input` field uses the existing OpenGGF mask:
`Up=0x0001`, `Down=0x0002`, `Left=0x0004`, `Right=0x0008`,
`A=0x0010`, `B=0x0020`, `C=0x0040`, and `Start=0x0080`.

### Frame semantics

Every BK2 row before `--bk2-frame-offset` is applied and advanced normally but
does not emit CSV. At offset `O`, BK2 input row `O` is applied before the next
`FrameAdvance`; trace row `0000` contains RAM state after that call completes.
Trace row `N` therefore describes the completed emulation of BK2 row `O + N`.
The CSV `frame` field is this zero-based trace-row number. The host's absolute
GPGX completed-frame counter remains available for assertions and diagnostics
but is not written in the milestone CSV. GPGX begins with completed-frame
counter `0`; after warm-up it is `O`, and immediately after emitting trace row
`N` it must equal `O + N + 1`. For the canonical offset `840`, trace row `0000`
is sampled at GPGX completed-frame counter `841`.

### RAM semantics

The pinned BizHawk 2.11 GPGX `68K RAM` domain exposes the 64 KiB Genesis
work-RAM window
`$FF0000`–`$FFFFFF` as offsets `$0000`–`$FFFF`. Sonic's object base `$FFD000`
therefore maps to domain offset `$D000`. Multi-byte values are read explicitly
as big-endian bytes from that domain:

- `x_pos`: `$D008`, unsigned 16-bit centre X;
- `y_pos`: `$D00C`, unsigned 16-bit centre Y;
- `x_vel`: `$D010`, signed two's-complement 16-bit velocity;
- `y_vel`: `$D012`, signed two's-complement 16-bit velocity.

The implementation composes words from two byte reads rather than relying on an
ambiguous domain-endianness convenience method.

All CSV numeric columns use uppercase four-digit hexadecimal without `0x`. The
file is UTF-8 without BOM, uses LF line endings and invariant formatting, and
ends with one newline.

### Error handling

- Validation completes before the finalized output path is created.
- Errors identify the rejected ROM hash, movie field, controller setting,
  missing BizHawk file, or malformed BK2 row.
- Partial output uses an exclusively created unique temporary filename within
  the output directory and is removed on handled failure.
- Successful finalization uses same-directory hard-link publication with
  no-replace semantics.
- The process returns zero only after finalization succeeds.
- Reaching movie end before the requested frame count is an error and does not
  finalize output.
- An uncatchable termination such as `SIGKILL` may leave the uniquely named
  temporary file, but can never create a false finalized `smoke.csv`.

### Observability

Normal output reports the pinned BizHawk version, ROM identity, movie frame
count, requested frame limit, completed frame count, and finalized CSV path.
Per-frame logging is omitted.

## Acceptance Tests

### Unit tests

1. Parse a synthetic BK2 ZIP whose `LogKey` uses the accepted Genesis grammar,
   plus a small committed fixture containing the real GHZ1 movie's header,
   sync-settings document, log key, and first input rows.
2. Map Power, Reset, directions, A, B, C, and Start by declared name and group.
3. Reject malformed rows, unsupported core/platform, active P2 input, unsupported
   sync settings, and savestate-start movies.
4. Validate the accepted and rejected Sonic 1 ROM hashes.
5. Decode signed velocities and unsigned centre coordinates from a synthetic
   Genesis work-RAM reader.
6. Prove the recorder observes state after frame advancement rather than before
   it.
7. Prove a failed run does not finalize `smoke.csv`.
8. Prove both a pre-existing final file and a file created immediately before
   publication remain byte-identical and are not replaced.
9. Prove `--max-frames` rejects zero and values above `1000`, and that early
   movie exhaustion fails without finalization.
10. Assert exact UTF-8-without-BOM, LF, uppercase-hex, and final-newline output.
11. Prove offset `2` advances two warm-up rows without output and emits trace
    row `0000` only after advancing the third BK2 row.

### Integration tests

1. As the mandatory feasibility gate, run `build.sh`, assert the release
   assemblies report version `2.11.0.0`, assert the runtime
   `PathUtils.DllDirectoryPath`, then with `DISPLAY` unset load GPGX and advance
   ten frames using the pinned local BizHawk distribution. Assert that the
   selected domain is `68K RAM` and its size is exactly `65536`; `Main RAM` is
   only the documented future source-backed compatibility fallback.
2. With `--bk2-frame-offset 840`, capture 1,000 rows into two distinct temporary
   output directories and compare SHA-256 hashes of their `smoke.csv` files.
3. Compare the native smoke rows corresponding to canonical trace frames `0000`,
   `0001`, and `03E7` with
   `src/test/resources/traces/s1/ghz1_fullrun/physics.csv`. The expected
   `(input,x,y,x_velocity,y_velocity)` values are respectively:

   ```text
   0000: (0000,0050,03B0,0000,0000)
   0001: (0000,0050,03B0,0000,0000)
   03E7: (0008,09A5,02AA,0272,FF80)
   ```

   The tracked trace metadata declares `bk2_frame_offset: 840`, so trace row
   `03E7` maps to BK2 input row `840 + 999 = 1839`. The test uses that declared
   offset and the explicit completed-frame semantics; it does not search the
   trace for matching values.

ROM-backed integration tests skip with an explicit reason when the user-supplied
ROM or downloaded BizHawk distribution is absent. They must fail, rather than
skip, when dependencies are present but incompatible.

GPGX may initialize its internal video-emulation state and buffers during core
construction. “Headless” here means no EmuHawk/frontend/display context, no
video presentation or rendered frames, and no audio synthesis/output; it does
not mean removing the emulated VDP from the core.

## Future Milestones

1. Expand the S1 recorder field-by-field until it matches the canonical S1 CSV
   schema.
2. Add deterministic metadata and auxiliary-event writers.
3. Add M68K execute callbacks and lag/input support for Sonic 2.
4. Add write callbacks and register reads for Sonic 3&K.
5. Provide a repeatable Claude workflow that converts one bounded Lua recorder
   field group or event handler at a time, with golden differential tests.
6. Replace release assembly references with source project references if deeper
   integration or upstream changes require it.

Each milestone retains the comparison-only invariant: emulator data generates
reference traces and never hydrates OpenGGF engine state during trace replay.
