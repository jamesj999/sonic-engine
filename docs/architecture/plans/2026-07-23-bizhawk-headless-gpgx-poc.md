# Headless BizHawk GPGX Proof of Concept Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Linux-only Mono console harness that loads BizHawk 2.11 GPGX directly, replays the tracked Sonic 1 GHZ1 BK2, and emits a deterministic 1,000-row smoke CSV without EmuHawk, X11, rendering, or audio.

**Architecture:** A shell bootstrap supplies the pinned BizHawk binary installation to two non-SDK C# projects. Focused components validate/read BK2 data, host GPGX behind `IGpgxHost`, record S1 RAM fields, and publish output with Linux no-replace semantics. A dependency-free console test runner covers units and optional ROM-backed integration.

**Tech Stack:** C# 7.x compatible with Mono 6.12, non-SDK MSBuild projects built by `xbuild`, BizHawk 2.11 release assemblies, `System.IO.Compression`, Newtonsoft.Json from the release, Bash, and a dependency-free C# test executable.

## Global Constraints

- Linux only; do not add Windows launch or packaging support.
- Pin BizHawk 2.11/source commit `427556b5ef3ac437eba754d90c5e7e9096c9a8df`.
- Reference the verified local `docs/BizHawk-2.11-linux-x64/dll` assemblies; do not vendor BizHawk files.
- Require BizHawk managed assembly version `2.11.0.0`; do not apply that check
  to framework assemblies or bundled Newtonsoft.Json (`13.0.0.0`).
- Set absolute `BIZHAWK_HOME`, `MONO_PATH`, and `LD_LIBRARY_PATH` before loading any BizHawk type.
- Directly construct GPGX; do not reference EmuHawk, WinForms, graphics frontends, audio frontends, Lua, or `BizHawk.Client.Common`.
- In the pinned BizHawk 2.11 runtime, resolve Genesis work RAM as `68K RAM`.
  Permit `Main RAM` only as a future source-backed API compatibility fallback,
  after `68K RAM`; require the selected domain to be exactly 65,536 bytes.
- Accept only Sonic 1 World REV01 SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`.
- The BK2 header's legacy 32-hex `SHA1 09DADB5071EB35050067A32462E39C5F` is metadata, not the ROM SHA-1, and must not be compared with the canonical 40-hex ROM hash.
- Use the tracked GHZ1 BK2 SHA-256 `dced61b2d3a3346b2ecd62254140497ef2827374c1de8597780f91e39ca0dcea`, exact supported sync payload SHA-256 `8f4130ebee1f1593080371f1d257477fbb2cc68c1cb691620736639e768c97bc`, offset `840`, and canonical physics CSV SHA-256 `dd0a03bfddefa9570d4b49ee2d4ea5e35e2b8141147e17ab482a3654d311cb66`.
- Follow TDD: each behavior begins with a failing test, the failure is observed, then minimal production code is added.
- Write only `smoke.csv.tmp.<pid>.<nonce>` until successful same-directory hard-link publication to `smoke.csv`.
- Preserve unrelated user changes and do not add ROMs or downloaded BizHawk artifacts to Git.

---

## File Map

Create the following focused files:

```text
tools/bizhawk-headless/
  BizHawk.Headless.Gpgx.csproj       production non-SDK project
  BizHawk.Headless.Gpgx.Tests.csproj dependency-free console tests
  build.sh                           xbuild + release reference validation
  common-env.sh                      shared BizHawk/Mono environment bootstrap
  run.sh                             production launcher
  test.sh                            test launcher
  src/
    Program.cs                       CLI composition root
    Bootstrap/BizHawkInstallation.cs install/path/version validation
    Bootstrap/RomIdentity.cs         Sonic 1 ROM SHA-1 validation
    Bk2/Bk2Frame.cs                  typed system/P1/P2 input state
    Bk2/Bk2Movie.cs                  parsed metadata, sync settings, frames
    Bk2/Bk2Reader.cs                 ZIP/header/sync/input grammar
    Core/IGpgxHost.cs                recorder-facing host boundary
    Core/MutableController.cs        local BizHawk IController
    Core/NoFirmwareProvider.cs       local ICoreFileProvider
    Core/RomAsset.cs                 local IRomAsset
    Core/GpgxHost.cs                 direct core construction and RAM access
    Recording/S1SmokeRecorder.cs     S1 address decoding and row formatting
    Recording/SmokeCaptureRunner.cs  warm-up/advance/record orchestration
    Recording/NoReplacePublisher.cs  exclusive temp + link publication
  tests/
    TestMain.cs                      test registry/exit status
    AssertEx.cs                      dependency-free assertions
    BootstrapTests.cs
    Bk2ReaderTests.cs
    GpgxHostTests.cs
    S1SmokeRecorderTests.cs
    SmokeCaptureRunnerTests.cs
    NoReplacePublisherTests.cs
    EndToEndTests.cs
  fixtures/
    ghz1-header.txt
    ghz1-sync-settings.json
    ghz1-input-prefix.txt
```

No Java production or test source changes are required.

---

## Execution Worktree Preflight

Before Task 1, use the worktree workflow requested by the user. From the primary
checkout, discover rather than assume the ROM filename:

```bash
export BIZHAWK_HOME="$(realpath docs/BizHawk-2.11-linux-x64)"
mapfile -t S1_ROM_CANDIDATES < <(
  rg --files -g '*.gen' -g '!**/.worktrees/**' |
  while read -r candidate; do
    [ "$(sha1sum "$candidate" | cut -d' ' -f1)" = \
      "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b" ] &&
      realpath "$candidate"
  done |
  sort
)
[ "${#S1_ROM_CANDIDATES[@]}" -gt 0 ]
export S1_ROM_PATH="${S1_ROM_CANDIDATES[0]}"
```

Verify the ROM hash before continuing. The linked worktree receives neither
path because both assets are ignored; do not copy, rename, or symlink them. Pass
these exported absolute values to every delegated implementer and reviewer.
`common-env.sh` honors an explicit `BIZHAWK_HOME`; only its fallback is relative
to the current checkout. Preserve all unrelated dirty/untracked files, then run
`git switch bugfix/ai-s3k-structural-replay-phases` in the primary checkout
(this was the branch active before this feature branch). If Git reports an
overlapping tracked change, stop rather than stashing or overwriting it. Then
attach the existing feature branch at
`.worktrees/bizhawk-headless-poc` with:

```bash
git worktree add .worktrees/bizhawk-headless-poc \
  feature/ai-bizhawk-headless-poc
```

Do not create a second feature branch.

---

### Task 1: Mono Build, Bootstrap, and Direct GPGX Feasibility Gate

**Files:**
- Create: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Create: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Create: `tools/bizhawk-headless/common-env.sh`
- Create: `tools/bizhawk-headless/build.sh`
- Create: `tools/bizhawk-headless/test.sh`
- Create: `tools/bizhawk-headless/src/Bootstrap/BizHawkInstallation.cs`
- Create: `tools/bizhawk-headless/src/Bootstrap/RomIdentity.cs`
- Create: `tools/bizhawk-headless/src/Core/MutableController.cs`
- Create: `tools/bizhawk-headless/src/Core/NoFirmwareProvider.cs`
- Create: `tools/bizhawk-headless/src/Core/RomAsset.cs`
- Create: `tools/bizhawk-headless/src/Core/IGpgxHost.cs`
- Create: `tools/bizhawk-headless/src/Core/GpgxHost.cs`
- Create: `tools/bizhawk-headless/tests/AssertEx.cs`
- Create: `tools/bizhawk-headless/tests/TestMain.cs`
- Create: `tools/bizhawk-headless/tests/BootstrapTests.cs`
- Create: `tools/bizhawk-headless/tests/GpgxHostTests.cs`

**Interfaces:**
- Produces: `BizHawkInstallation.Validate(string root)`, `IGpgxHost`, and `GpgxHost.Open(string romPath, GPGX.GPGXSyncSettings syncSettings)`.
- `IGpgxHost` exposes `int CompletedFrame { get; }`, `void ClearButtons()`, `void SetButton(string name, bool pressed)`, `void Advance()`, `byte ReadMainRamByte(int offset)`, and `Dispose()`.

- [ ] **Step 1: Add and verify the build/test scaffold**

Create the non-SDK production project initially as a `Library`, the executable
test project, `AssertEx`, and `TestMain`. `TestMain` accepts optional
`--filter <case-insensitive-substring>`, runs only matching test names, and
returns `2` with `No tests matched filter: <value>` when none match.

The production project references BizHawk/Newtonsoft with bundle `HintPath`s.
`System.IO.Compression` and `System.IO.Compression.FileSystem` are framework
references without `HintPath`.

Create `common-env.sh`, `build.sh`, and `test.sh` as specified below, then add a
single `Harness scaffold runs` test.

Run:

```bash
tools/bizhawk-headless/test.sh --filter scaffold
tools/bizhawk-headless/test.sh --filter absent-test-name
```

Expected: scaffold exits `0`; no-match exits `2`.

- [ ] **Step 2: Add compilable failing bootstrap, ROM, and host tests**

Create a console test registry that runs named `Action` tests, reports `PASS`/`FAIL`, and exits `1` on any failure. Add tests which:

```csharp
BizHawkInstallation install = BizHawkInstallation.Validate(root);
AssertEx.Equal(new Version(2, 11, 0, 0), install.ManagedVersion);
AssertEx.Equal(Path.Combine(root, "dll"), install.DllDirectory);
AssertEx.Throws<InvalidOperationException>(
    () => BizHawkInstallation.Validate(missingRoot),
    "gpgx.wbx.zst");
```

Add `RomIdentity.ValidateSonic1Rev01(byte[])` tests for the accepted SHA-1 and a
one-byte mutation. The rejection message must contain the actual uppercase
40-hex SHA-1. Add minimal production stubs whose methods throw
`NotImplementedException`, so the suite compiles and fails behaviorally rather
than because a script or type is absent.

Add a ROM-backed test enabled only when `S1_ROM_PATH` exists:

```csharp
using (IGpgxHost host = GpgxHost.Open(
    Environment.GetEnvironmentVariable("S1_ROM_PATH"),
    GpgxHost.CreateGhz1SyncSettings()))
{
    for (var i = 0; i < 10; i++) host.Advance();
    AssertEx.Equal(10, host.CompletedFrame);
}
```

When the ROM variable is absent, print `SKIP GpgxHost advances ten frames: S1_ROM_PATH not set`; do not report pass.

- [ ] **Step 3: Run the tests and observe RED**

Run:

```bash
tools/bizhawk-headless/test.sh
```

Expected: exit `1`; the scaffold runs, then bootstrap/ROM/host tests fail at the
intentional `NotImplementedException`.

- [ ] **Step 4: Complete the Linux environment/build scripts**

The production project targets `v4.8`, includes `src/**/*.cs`, and references exact `HintPath`s under `$(BizHawkDllDir)` for:

```text
BizHawk.Common.dll
BizHawk.Emulation.Common.dll
BizHawk.Emulation.Cores.dll
BizHawk.Emulation.DiscSystem.dll
BizHawk.BizInvoke.dll
Newtonsoft.Json.dll
```

`System.IO.Compression` and `System.IO.Compression.FileSystem` are framework
references without `HintPath`. The test project is an executable, references
the production project, and includes `tests/**/*.cs`.

`common-env.sh` resolves the repository root, defaults `BIZHAWK_HOME` to the
absolute `docs/BizHawk-2.11-linux-x64`, normalizes an explicit value with
`realpath`, rejects a nonexistent or relative result, and exports:

```bash
export BIZHAWK_HOME
export MONO_PATH="$BIZHAWK_HOME/dll${MONO_PATH:+:$MONO_PATH}"
export LD_LIBRARY_PATH="$BIZHAWK_HOME/dll${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
```

It validates `mono`, `xbuild`, the six BizHawk/Newtonsoft bundle DLLs above,
`gpgx.wbx.zst`, and `libwaterboxhost.so`. `build.sh` invokes:

```bash
xbuild /nologo /verbosity:minimal \
  /property:Configuration=Release \
  /property:BizHawkDllDir="$BIZHAWK_HOME/dll" \
  BizHawk.Headless.Gpgx.csproj
xbuild /nologo /verbosity:minimal \
  /property:Configuration=Release \
  /property:BizHawkDllDir="$BIZHAWK_HOME/dll" \
  BizHawk.Headless.Gpgx.Tests.csproj
```

`build.sh` sources `common-env.sh` itself so it is a valid standalone gate.
`test.sh` implements one dependency-aware exception before sourcing
`common-env.sh`: when invoked with `--filter EndToEnd` or
`--filter GpgxHost`, no explicit `BIZHAWK_HOME` was supplied, and the default
distribution directory is absent, it prints
`SKIP <filter>: BizHawk distribution not installed` and exits `0`. An explicitly
supplied but invalid `BIZHAWK_HOME` always fails. Other invocations require the
distribution because the production/test assemblies cannot compile without
their references. After that check, `test.sh` sources `common-env.sh`, invokes
`build.sh`, unsets `DISPLAY`, and forwards all arguments to the test executable
through Mono.

- [ ] **Step 5: Implement bootstrap, ROM validation, and exact direct-core adapters**

`BizHawkInstallation.Validate` verifies required files, checks each direct BizHawk assembly with `AssemblyName.GetAssemblyName(path).Version == new Version(2, 11, 0, 0)`, and verifies:

```csharp
Path.GetFullPath(PathUtils.DllDirectoryPath)
    == Path.GetFullPath(Path.Combine(root, "dll"))
```

Implement a local immutable `IRomAsset`, local no-firmware `ICoreFileProvider`,
and local dictionary-backed `IController`. `MutableController` is constructed
with `core.ControllerDefinition`, returns that exact instance from
`Definition`, returns `0` from `AxisValue`, returns an empty
`IReadOnlyCollection<(string Name, int Strength)>` from `GetHapticsSnapshot`,
and no-ops `SetHapticChannelStrength`. `Clear` releases every button before the
next row; `Set` and `IsPressed` use the core's exact names `P1 Up`, `P1 Down`,
`P1 Left`, `P1 Right`, `P1 A`, `P1 B`, `P1 C`, `P1 Start`, `Power`, and
`Reset`. Construct:

The exact pinned interfaces implemented are:

```csharp
// IRomAsset
byte[] RomData { get; }
byte[] FileData { get; }
string Extension { get; }
string RomPath { get; }
GameInfo Game { get; }

// IController
ControllerDefinition Definition { get; }
bool IsPressed(string button);
int AxisValue(string name);
IReadOnlyCollection<(string Name, int Strength)> GetHapticsSnapshot();
void SetHapticChannelStrength(string name, int strength);

// ICoreFileProvider
byte[] GetFirmware(FirmwareID id, string msg = null);
byte[] GetFirmwareOrThrow(FirmwareID id, string msg = null);
(byte[] FW, GameInfo Game) GetFirmwareWithGameInfoOrThrow(
    FirmwareID id, string msg = null);
string GetRetroSaveRAMDirectory(IGameInfo game);
string GetRetroSystemPath(IGameInfo game);
string GetUserPath(string sysID, bool temp);
```

```csharp
var game = new GameInfo {
    Name = "Sonic The Hedgehog",
    System = VSystemID.Raw.GEN,
    Hash = romSha1
};
var comm = new CoreComm(
    _ => { },
    (_, __) => { },
    new NoFirmwareProvider(),
    CoreComm.CorePreferencesFlags.None,
    null);
var core = new GPGX(
    new CoreLoadParameters<GPGX.GPGXSettings, GPGX.GPGXSyncSettings> {
        Comm = comm,
        Game = game,
        Settings = new GPGX.GPGXSettings(),
        SyncSettings = syncSettings,
        Roms = { new RomAsset(romBytes, romPath, game) },
        DeterministicEmulationRequested = true
    });
```

Resolve `core.ServiceProvider.GetService<IMemoryDomains>()["68K RAM"]` first.
Only if that is absent, accept `"Main RAM"` as a compatibility fallback for a
future source-backed API. Reject a missing domain or any selected domain whose
size is not exactly `65536` bytes. Expose the selected domain name and size to
the ROM-backed test, which must assert the pinned 2.11 result is `68K RAM` and
`65536`. `Advance()` calls `core.FrameAdvance(controller, false, false)`.
Dispose the GPGX core.

`GpgxHost.Open` calls `RomIdentity.ValidateSonic1Rev01` before constructing
`GameInfo`; the BK2 Header's legacy 32-hex value is never passed to this
validator.

`CreateGhz1SyncSettings()` assigns every field from the tracked JSON, including
normal three-button controls, autodetect region, disabled forced VDP/BIOS, all
overscan, MAME YM2413/YM2612, no filter, and the exact numeric EQ/backdrop values
from the authoritative `ghz1-sync-settings.json` fixture.

- [ ] **Step 6: Run the gate and observe GREEN**

Run:

```bash
S1_ROM_PATH="$S1_ROM_PATH" BIZHAWK_HOME="$BIZHAWK_HOME" \
  tools/bizhawk-headless/test.sh
```

Expected: build succeeds; bootstrap tests pass; with `DISPLAY` unset GPGX advances ten frames and reports completed frame `10`.

- [ ] **Step 7: Commit**

```bash
git add tools/bizhawk-headless
git commit -m "feat(trace): bootstrap headless GPGX host"
```

Use the repository trailer policy; set `Changelog: n/a: proof-of-concept tooling`, `Guide: n/a`, and the remaining unaffected mappings to justified `n/a`.

---

### Task 2: Strict GHZ1 BK2 Reader

**Files:**
- Create: `tools/bizhawk-headless/src/Bk2/Bk2Frame.cs`
- Create: `tools/bizhawk-headless/src/Bk2/Bk2Movie.cs`
- Create: `tools/bizhawk-headless/src/Bk2/Bk2Reader.cs`
- Create: `tools/bizhawk-headless/tests/Bk2ReaderTests.cs`
- Create: `tools/bizhawk-headless/fixtures/ghz1-header.txt`
- Create: `tools/bizhawk-headless/fixtures/ghz1-sync-settings.json`
- Create: `tools/bizhawk-headless/fixtures/ghz1-input-prefix.txt`
- Modify: both C# project compile/content item lists
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Produces: `Bk2Movie Bk2Reader.Read(string path)`.
- `Bk2Movie` exposes `int FrameCount`,
  `IEnumerable<Bk2Frame> OpenFrameStream()`,
  `GPGX.GPGXSyncSettings SyncSettings`, and header properties. Each enumeration
  opens and streams the archive input entry; it does not retain every movie row.
- `Bk2Frame` exposes system `Power`/`Reset`, P1 named button states, P2 activity, and `ushort OpenGgfInputMask`.

- [ ] **Step 1: Add the real prefix fixtures, parser stubs, and failing tests**

Extract the tracked BK2's `Header.txt`, complete `SyncSettings.json`, `LogKey`, and first three input rows into small text fixtures. Do not copy the full movie.

Tests must build synthetic ZIPs in a temporary directory and assert:

```csharp
var movie = Bk2Reader.Read(validZip);
var frames = movie.OpenFrameStream().ToList();
AssertEx.Equal("Genplus-gx", movie.Core);
AssertEx.Equal("GEN", movie.Platform);
AssertEx.Equal(3, movie.FrameCount);
AssertEx.Equal((ushort) 0, frames[0].OpenGgfInputMask);
```

Add one-frame variants for each accepted P1 bit:

```text
Up=0001 Down=0002 Left=0004 Right=0008
A=0010 B=0020 C=0040 Start=0080
```

Add synthetic system-group rows `|P.|........|........|` and
`|.R|........|........|`; assert the parsed frames expose `Power=true,
Reset=false` and `Power=false, Reset=true` respectively, while both P1 masks
remain zero.

Reject duplicate/missing Core or Platform, `StartsFromSavestate True`, `StartsFromSaveRam True`, wrong/missing/extra sync fields, six-button/controller changes, multiple input sections, malformed group lengths, active P2, and unknown group names.

Assert the fixture sync payload SHA-256 is exactly
`8f4130ebee1f1593080371f1d257477fbb2cc68c1cb691620736639e768c97bc`.
Assert the legacy 32-hex Header `SHA1` is retained as metadata and is not used as a ROM identity.

Add compilable `Bk2Frame`, `Bk2Movie`, and `Bk2Reader` stubs with
`Bk2Reader.Read` throwing `NotImplementedException`.

- [ ] **Step 2: Run the parser tests and observe RED**

Run:

```bash
tools/bizhawk-headless/test.sh --filter Bk2Reader
```

Expected: exit `1` at the intentional `NotImplementedException`.

- [ ] **Step 3: Implement the strict ZIP grammar**

Use `ZipArchive` and reject missing/duplicate archive entries. Parse header lines at the first space with duplicate-key detection. Deserialize sync JSON into a local DTO with all expected fields, reject missing/unknown fields, compare exact supported values, then construct the exact `GPGXSyncSettings`.

Parse one `[Input]` section during validation to obtain `FrameCount`. Require the
exact declared group names from the design. `OpenFrameStream` reopens the ZIP
and streams rows through an iterator. Split each row as
`|SS|PPPPPPPP|QQQQQQQQ|`, map characters through the declared group order, and
treat `.` as released and any other character as pressed. Reject active P2.

The reader verifies the full BK2 SHA-256 only when the input path is the canonical integration fixture; synthetic unit movies validate content rather than the canonical archive hash.

- [ ] **Step 4: Run tests and observe GREEN**

Run:

```bash
tools/bizhawk-headless/test.sh --filter Bk2Reader
```

Expected: all BK2 reader tests pass.

- [ ] **Step 5: Commit**

```bash
git add tools/bizhawk-headless
git commit -m "feat(trace): parse supported Genesis BK2 input"
```

Use the required documentation trailers with justified `n/a`.

---

### Task 3: S1 Smoke Recorder and Frame-Orchestrated Capture

**Files:**
- Create: `tools/bizhawk-headless/src/Recording/S1SmokeRecorder.cs`
- Create: `tools/bizhawk-headless/src/Recording/SmokeCaptureRunner.cs`
- Create: `tools/bizhawk-headless/tests/S1SmokeRecorderTests.cs`
- Create: `tools/bizhawk-headless/tests/SmokeCaptureRunnerTests.cs`
- Modify: both project files
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Produces: `string S1SmokeRecorder.Header`, `string S1SmokeRecorder.Record(int traceFrame, ushort input, IGpgxHost host)`.
- Produces: `void SmokeCaptureRunner.Capture(Bk2Movie movie, IGpgxHost host, int bk2FrameOffset, int maxFrames, TextWriter output)`.

- [ ] **Step 1: Add recorder/runner stubs and failing RAM decode/frame-order tests**

Use a fake `IGpgxHost` backed by a 64 KiB byte array. Set:

```text
D008 = 0x09, D009 = 0xA5
D00C = 0x02, D00D = 0xAA
D010 = 0x02, D011 = 0x72
D012 = 0xFF, D013 = 0x80
```

Assert:

```text
03E7,0008,09A5,02AA,0272,FF80
```

and exact header:

```text
frame,input,x,y,x_velocity,y_velocity
```

Assert UTF-8 content uses uppercase four-digit hex, LF only, no BOM, and one final newline.

For orchestration, use three warm-up/input frames and a fake host that mutates RAM in `Advance()`. With offset `2` and count `1`, assert:

- `Advance()` is called three times;
- the emitted row is trace frame `0000`;
- it uses input and post-advance RAM from BK2 row `2`;
- `CompletedFrame == offset + traceFrame + 1`.

Add separate fake-host frames with system Power and Reset active. Record every
host call and assert the exact per-row sequence:

```text
ClearButtons
SetButton("Power", true)   # or Reset for the second case
SetButton(<active P1 names>, true)
Advance
ReadMainRamByte...
```

Also assert the next row begins with `ClearButtons`, so Power/Reset cannot leak
between frames.

Reject negative offset, count outside `1..1000`, and movie exhaustion before `offset + count`.

Add compilable recorder and runner types whose public methods throw
`NotImplementedException`.

- [ ] **Step 2: Run focused tests and observe RED**

Run:

```bash
tools/bizhawk-headless/test.sh --filter Smoke
```

Expected: exit `1` at the intentional `NotImplementedException`.

- [ ] **Step 3: Implement recorder and runner**

Read each word through two `ReadMainRamByte` calls in M68K big-endian order. Cast velocity words with `unchecked((short) word)` only for semantic validation; format the underlying 16-bit value as `X4`.

The runner first advances exactly `bk2FrameOffset` rows without recording. For each output row it clears/applies the selected frame, advances, asserts `host.CompletedFrame == offset + traceFrame + 1`, then records. It writes the header once and appends one LF-terminated row per trace frame.

- [ ] **Step 4: Run focused and full tests and observe GREEN**

Run:

```bash
tools/bizhawk-headless/test.sh --filter Smoke
tools/bizhawk-headless/test.sh
```

Expected: focused tests and all prior tests pass.

- [ ] **Step 5: Commit**

```bash
git add tools/bizhawk-headless
git commit -m "feat(trace): record deterministic S1 smoke rows"
```

Use the required documentation trailers with justified `n/a`.

---

### Task 4: Safe Publication and Production CLI

**Files:**
- Create: `tools/bizhawk-headless/src/Recording/NoReplacePublisher.cs`
- Create: `tools/bizhawk-headless/src/Program.cs`
- Create: `tools/bizhawk-headless/run.sh`
- Create: `tools/bizhawk-headless/tests/NoReplacePublisherTests.cs`
- Create: `tools/bizhawk-headless/tests/EndToEndTests.cs`
- Modify: project files
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Produces: `NoReplacePublisher.Publish(string outputDirectory, Action<TextWriter> write)`.
- An internal constructor accepts `ILinkOperation.Create(string temporary,
  string finalPath)` so the race test can deterministically act immediately
  before the real `link(2)` call.
- Produces CLI arguments `--rom`, `--movie`, `--output`, `--bk2-frame-offset`, and `--max-frames`.

- [ ] **Step 1: Add publisher/CLI stubs and failing no-replace and CLI tests**

Publication tests assert:

- a missing nested output directory is created and publishes successfully;
- handled writer failure removes the unique temporary file and creates no final;
- pre-existing `smoke.csv` remains byte-identical;
- a test hook that creates `smoke.csv` immediately before publication wins the race and remains byte-identical;
- successful publication leaves only `smoke.csv`;
- output bytes are UTF-8 without BOM with LF endings.

CLI parsing tests assert required arguments, offset `>= 0`, count `1..1000`, unknown/duplicate arguments rejected, and no existing final output accepted.

The race test injects an `ILinkOperation` decorator which writes the competing
final file and then delegates to the real libc link implementation. Add
compilable publisher and CLI stubs throwing `NotImplementedException`.

Add the ROM-backed end-to-end test before production CLI wiring. It verifies the
canonical BK2 and physics CSV hashes, launches two captures into distinct
temporary directories with offset `840` and count `1000`, and asserts identical
SHA-256 output. It parses canonical `physics.csv` by header name and compares
the native `input`, `x`, `y`, `x_velocity`, and `y_velocity` columns against
canonical `input`, `player_x`, `player_y`, `player_x_speed`, and
`player_y_speed` for rows `0000`, `0001`, and `03E7`.

Before launching, parse
`src/test/resources/traces/s1/ghz1_fullrun/metadata.json`, require its
`bk2_frame_offset` to be integer `840`, and pass that parsed value—not a second
hardcoded offset—to both captures and the expected BK2-row mapping assertion.

If either `S1_ROM_PATH` or the ignored BizHawk distribution is absent, print an
explicit `SKIP EndToEnd: <missing dependency>` and do not count it as pass.
When either dependency is supplied/present but has the wrong hash, version, or
runtime layout, fail rather than skip.

Add observability assertions for exact labeled values: BizHawk version, uppercase
ROM SHA-1, BK2 frame count, requested frame count, completed GPGX frame count,
and finalized absolute CSV path.

Add an assembly-reference test using `Assembly.GetReferencedAssemblies()` on
the production assembly: names must not include `BizHawk.Client.Common`,
`System.Windows.Forms`, or any BizHawk graphics/audio frontend assembly. The
child-process environment asserts `DISPLAY` is absent, and `run.sh` is inspected
to invoke only the harness executable through Mono.

- [ ] **Step 2: Run tests and observe RED**

Run:

```bash
tools/bizhawk-headless/test.sh --filter Publisher
tools/bizhawk-headless/test.sh --filter Cli
tools/bizhawk-headless/test.sh --filter EndToEnd
```

Expected: exit `1` at the intentional publisher/CLI
`NotImplementedException`; the end-to-end test fails at the stub executable.

- [ ] **Step 3: Implement Linux publication and composition root**

Call `Directory.CreateDirectory(outputDirectory)` before opening any file.
Create the temporary file with `FileMode.CreateNew` and a name
`smoke.csv.tmp.<pid>.<cryptographic nonce>`. Flush writer and stream, then call
`link(2)` through a small `DllImport("libc")`. Treat `EEXIST` as a clear
no-overwrite error. Delete the temporary link after successful publication and
on handled failures.

`Program` validates installation, ROM SHA-1, movie, offset/count, and output
before constructing GPGX. It passes `movie.SyncSettings` into `GpgxHost.Open`,
runs capture inside `NoReplacePublisher`, prints the pinned version/identities
and final path, and returns non-zero on a concise exception message.

`run.sh` sources `common-env.sh`, builds if the executable is absent, unsets
`DISPLAY`, and executes it via Mono. Do not set or access an X server.

Successful stdout is exactly these six LF-terminated lines:

```text
BizHawk: 2.11.0.0
ROM SHA-1: 69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B
Movie frames: <validated integer>
Requested trace frames: <max-frames>
Completed GPGX frames: <offset + max-frames>
Output: <absolute path to smoke.csv>
```

Change the production project from `Library` to `Exe`, set startup object
`BizHawk.Headless.Gpgx.Program`, and place the release executable at
`bin/Release/BizHawk.Headless.Gpgx.exe`. Update the test project reference to
that executable assembly.

- [ ] **Step 4: Run tests and observe GREEN**

Run:

```bash
tools/bizhawk-headless/test.sh --filter Publisher
tools/bizhawk-headless/test.sh --filter Cli
S1_ROM_PATH="$S1_ROM_PATH" BIZHAWK_HOME="$BIZHAWK_HOME" \
  tools/bizhawk-headless/test.sh --filter EndToEnd
tools/bizhawk-headless/test.sh
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add tools/bizhawk-headless
git commit -m "feat(trace): add headless GPGX smoke CLI"
```

Use the required documentation trailers with justified `n/a`.

---

### Task 5: Documentation and Final Determinism Gate

**Files:**
- Modify: `tools/bizhawk/README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes the CLI and components from Tasks 1–4.
- Produces a documented, repeatable Linux POC command and golden verification.

- [ ] **Step 1: Re-run the existing end-to-end golden gate**

```bash
S1_ROM_PATH="$S1_ROM_PATH" BIZHAWK_HOME="$BIZHAWK_HOME" \
  tools/bizhawk-headless/test.sh --filter EndToEnd
```

Expected: pass before documentation changes. This is a verification gate, not a
new TDD cycle; any failure returns to Task 4's reviewed implementation rather
than being patched by loosening fixtures.

- [ ] **Step 2: Document the verified command**

Add a concise `Native headless GPGX proof of concept` section to
`tools/bizhawk/README.md` with:

```bash
tools/bizhawk-headless/run.sh \
  --rom "$S1_ROM_PATH" \
  --movie "$PWD/src/test/resources/traces/s1/ghz1_fullrun/ghz1_fullrun.bk2" \
  --output "$PWD/target/bizhawk-headless-smoke" \
  --bk2-frame-offset 840 \
  --max-frames 1000
```

State Linux/Mono/BizHawk 2.11 limitations and that this smoke CSV is not yet a
canonical trace schema. Add a changelog entry for the new developer tool.

- [ ] **Step 3: Run full verification**

Run:

```bash
S1_ROM_PATH="$S1_ROM_PATH" BIZHAWK_HOME="$BIZHAWK_HOME" \
  tools/bizhawk-headless/test.sh
env -u DISPLAY tools/bizhawk-headless/run.sh \
  --rom "$S1_ROM_PATH" \
  --movie "$PWD/src/test/resources/traces/s1/ghz1_fullrun/ghz1_fullrun.bk2" \
  --output "$(mktemp -d)/capture" \
  --bk2-frame-offset 840 \
  --max-frames 1000
```

Expected: every test passes, the CLI exits zero, and the finalized CSV has
1,001 lines.

- [ ] **Step 4: Commit**

```bash
git add tools/bizhawk-headless tools/bizhawk/README.md CHANGELOG.md
git commit -m "feat(trace): prove deterministic native GPGX capture"
```

Trailers must set `Changelog: updated` and `Guide: n/a`; all other mappings use
accurate `n/a` attestations. `tools/bizhawk/README.md` is not mapped to the
`Guide` trailer.

---

## Final Verification

After all task reviews are green:

```bash
S1_ROM_PATH="$S1_ROM_PATH" BIZHAWK_HOME="$BIZHAWK_HOME" \
  tools/bizhawk-headless/test.sh
git diff --check "$(git merge-base develop HEAD)" HEAD
git status --short
```

Use the absolute `S1_ROM_PATH` and `BIZHAWK_HOME` preserved by the worktree
preflight, not worktree-relative ignored paths.

Then run an independent whole-branch review against:

- `docs/architecture/designs/2026-07-23-bizhawk-headless-gpgx-poc-design.md`
- this implementation plan;
- the complete branch diff from its merge base.

The branch is ready for human review only when that reviewer reports no
critical or important findings.

If the human later authorizes merging this branch into `develop`, update root
`README.md` in the merge commit as required by repository policy. That
merge-only documentation change is intentionally outside this feature
implementation plan.
