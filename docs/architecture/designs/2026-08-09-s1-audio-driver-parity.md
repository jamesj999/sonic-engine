# Sonic 1 Audio Driver Parity Harness

**Date:** 2026-08-09

## Goal

Build a differential harness that compares OpenGGF's Sonic 1 SMPS music
sequencing with the shipped Sonic 1 World REV01 driver. The first milestone is
GHZ music only. It compares logical driver state and the ordered YM2612/PSG
register transactions for a complete musical cycle plus one repeated cycle.

The harness must identify the first genuine divergence without treating final
PCM as the first diagnostic surface. PCM/sample parity and sound effects are
immediate follow-up work after the music MVP.

## Acceptance criteria

The harness is valid when all of the following hold:

1. BizHawk reaches the GHZ sound-test selection through recorded controller
   input against the verified REV01 ROM. Lua does not mutate emulated memory,
   registers, input, or savestates.
2. Capture begins at the exact invocation that accepts music ID `$81`, not at a
   gameplay or emulator frame chosen by observation.
3. Each reference record is keyed by a monotonic audio-driver invocation
   ordinal and contains normalized driver state plus ordered decoded chip
   writes for that invocation.
4. OpenGGF emits the same normalized contract from its real Sonic 1 ROM loader,
   SMPS configuration, sequencer, driver, and chip-core write boundary.
5. The comparator distinguishes capture failure, state divergence, and
   register divergence and reports the first mismatch with bounded context.
6. Two independent BizHawk captures match byte-for-byte after deterministic
   normalization; two independent OpenGGF captures do the same. Normalized
   identity excludes timestamps, absolute paths, process IDs, host-specific
   emulator fields, and other environment metadata.
7. The checked interval contains a proven complete musical cycle followed by a
   second identical cycle.
8. The GHZ music parity claim requires exact logical-state equality and exact
   ordered decoded-register equality throughout the checked interval.

Tool delivery and the parity result are distinct. A correct tool may initially
produce a red parity report. Any engine corrections discovered by that report
are separately reviewed changes, not part of validating the observer.

## Non-goals

- Final PCM, resampler, DAC byte-stream, or sample-accurate output parity.
- Sonic 1 sound-effect priority, channel stealing, or music/SFX contention.
- Sonic 2 or Sonic 3&K audio parity.
- Loading gameplay state from captured data.
- Automatically rewriting OpenGGF chip-write ordering after a mismatch.
- Committing a complete GHZ register stream, PCM capture, VGM, or other
  replayable representation of copyrighted music.

## Reference transport

The reference input is the user-authored movie currently located at:

```text
docs/BizHawk-2.11-linux-x64/Movies/s1-soundtest-ghz.bk2
```

Its verified properties are:

| Property | Value |
|---|---|
| SHA-256 | `622ff642d0b0835a4f77bee568f2413f288ead3306a8bc2a93e8d8f77f24ca9c` |
| Controller input rows | 989 |
| Emulator | BizHawk 2.11 |
| Core | Genesis Plus GX |
| Game | Sonic The Hedgehog World REV01 |

Implementation copies this controller-only BK2 to the tracked audio-test fixture:

```text
src/test/resources/audio/parity/s1/s1-soundtest-ghz.bk2
```

The ignored copy inside the local BizHawk installation remains untouched.

The movie is a launch transport, not the capture duration. It powers on the
ROM, passes the Sega and title sequences, enters Level Select through retail
controller input, selects `$81` in Sound Test, and triggers it. After the movie
ends, the capture runner continues advancing the emulator with neutral input
until the musical-cycle stop contract succeeds. This behavior is opt-in: the
shared probe runtime continues to exit at movie completion for existing
diagnostics.

Lua does not manufacture that neutral state. On every post-movie frame the
observer reads `joypad.get(1)` and `joypad.get(2)` and requires every digital
control to be false. Any host input after movie completion fails capture.

The runner validates the ROM as Sonic 1 World REV01 using the project's
documented CRC32/SHA-1 values. The BK2 hash and sync settings are also checked
before execution. A wrong ROM, movie, core configuration, or movie hash is a
capture failure rather than a parity mismatch.

The BK2 `Header.txt` field named `SHA1` is opaque BizHawk movie metadata (it is
32 hexadecimal characters here), not ROM identity. The runner hashes the ROM
file independently and never substitutes the header value for the documented
40-character REV01 SHA-1.

## Capture epoch and clock

Sonic 1's SMPS driver is 68000-side. The unit of comparison is an invocation of
`UpdateMusic` at ROM address `$71B4C`, not a gameplay frame, emulator frame, or
OpenGGF presentation packet.

The probe remains dormant through Sega and title playback. It arms only when
execution reaches `Sound_PlayBGM` at `$71FD2` with `D7 == $81`. That invocation
is audio tick zero. Events earlier in the invocation are discarded so title
music cannot contaminate the GHZ initialization transaction. Events from
`Sound_PlayBGM` onward, followed by the post-invocation state, form tick zero.
Subsequent `UpdateMusic` invocations increment the ordinal by one.

BizHawk frame count, movie frame, game mode, and interrupt context are retained
as diagnostics. They never select or realign a comparison record.

An entry hook alone is insufficient because the DAC-busy path branches back to
`$71B4C`. The observer therefore keeps an `invocation_active` guard and the 68K
stack pointer from the external entry. The first entry while the guard is clear
opens an invocation. Re-entry with the same stack pointer while it is set is a
DAC-busy retry and does not increment the ordinal; a different stack pointer is
an invalid nested entry. Execution of the final `rts` at `$71C4C` snapshots
state, closes the invocation, and asserts exactly one close. Re-entry after a
close opens the next ordinal. A close without an open or a different-stack
entry before close is a capture failure.

An invocation may legitimately cross an emulator-frame boundary while waiting
for the Z80 DAC side. The supplied movie proves this at tick zero: the `$81`
epoch opens on BizHawk frame 823, retries at `$71B4C` with the same stack pointer,
and closes at `$71C4C` on frame 824. Frame changes are diagnostic only and never
split or reject a driver invocation.

Launch-only Sega PCM has a separate shipped escape path. `PlaySegaSound`
executes `addq.w #4,sp` and returns at `$71FD0`, deliberately bypassing
`UpdateMusic`'s normal `$71C4C` close. The observer hooks `$71FD0`; before the
GHZ epoch it closes/resets the dormant launch invocation without emitting a
tick. This prevents a later external call that reuses the same stack pointer
from being mistaken for an endless retry. After `$81` arms capture, `$71FD0` is
invalid contamination, same-stack `$71B4C` entries are retries across any
number of emulator frames, different-stack entry-before-close is invalid, and
`$71C4C` remains the sole normal close.

The ROM capture records the title-to-GHZ `UpdateMusic` count only as launch
diagnostics. It is not replayed on the engine side. OpenGGF's ordinary music
replacement currently discards its `SmpsDriver` and `VirtualSynthesizer`, so a
title pre-roll cannot establish a shared chip precondition and must not be
claimed to do so.

The MVP deliberately targets the production S1 sequencer/driver output rather
than backend stream-replacement plumbing. The OpenGGF test host constructs a
fresh production `SmpsDriver` with observation disarmed during the
`VirtualSynthesizer` power-on silence, arms observation, and opens tick zero
immediately before constructing the GHZ `SmpsSequencer`. Tick zero includes all
constructor writes and exactly the sequencer's first S1 tempo service (the
existing priming path), then takes its state snapshot. Each later record spans
exactly one further NTSC tempo-service boundary. On the ROM side tick zero
likewise discards writes before `$71FD2`, includes `Sound_PlayBGM` initialization
and the rest of that `UpdateMusic` invocation, and snapshots at `$71C4C`.

This grouping intentionally excludes both hosts' unrelated power-on/title chip
history while retaining all GHZ driver initialization writes. It neither
asserts that OpenGGF's backend replacement matches the ROM nor uses a custom
sequencer. A future backend-transition comparison is a separate layer.

## Components

### BizHawk observer

A Sonic 1-specific read-only Lua observer owns:

- ROM, BK2, core, and sync-setting validation;
- `$81` epoch detection;
- `UpdateMusic` invocation bracketing;
- raw chip-port event capture;
- post-invocation reads of the `$FFF000` SMPS state block;
- contamination and stop-condition checks; and
- deterministic JSONL output under `target/audio-parity/s1-ghz/`.

The observer may reuse the shared probe runtime after adding an opt-in
continue-after-movie contract. Existing probe behavior must remain unchanged.
The observer must not call `mainmemory.write*`, `memory.write*`, `joypad.set`,
savestate mutation APIs, or `emu.setregister`.

### OpenGGF observer

A test/tool-only Java observer uses the existing `SmpsDriverSnapshot`,
`SmpsSequencerSnapshot`, and `SmpsTrackSnapshot` surfaces where they express ROM
semantics. A disabled-by-default observation port sits at
`Ym2612Chip.write(...)` and `PsgChip.write(...)`, after driver arbitration and
after high-level operations have expanded into actual chip writes. This is the
only current boundary that observes direct writes plus the writes hidden inside
`setInstrument(...)` and `silenceAll()` without reimplementing their ordering.
The observer receives immutable byte values synchronously and may only append
them; it cannot suppress, reorder, or alter a chip write. It does not add
gameplay-visible logging, introduce a second sequencer, or make production
behavior depend on comparison state.

The Java observer emits the same normalized JSONL schema as Lua. Normalization
belongs to a dedicated audio-parity package rather than `AudioManager`,
`SmpsDriver`, or `SmpsSequencer`.

### Comparator

A local CLI reads one ROM reference stream and one OpenGGF stream. It validates
metadata and ordinal continuity before comparing state or events. It writes a
human-readable report and a compact machine-readable summary beneath
`target/audio-parity/s1-ghz/`.

## Raw chip-port capture

For every audio tick the ROM observer retains raw writes, in execution order,
to:

- YM2612 port 0 address/data at `$A04000`/`$A04001`;
- YM2612 port 1 address/data at `$A04002`/`$A04003`; and
- PSG at `$C00011`.

Before the main capture relies on memory-write callbacks, a short validation
probe must demonstrate that Genesis Plus GX supplies the written byte correctly
for these write-only ports. The validation cross-checks the FM result against
the real data-write instructions at `$72752` and `$72788`, where `D0` holds the
register and `D1` holds the value.

If the callback does not expose reliable values, capture falls back to a
reviewed PC-site manifest. FM uses address/data sites `$7273A/$72752` (port 0,
`D0`/`D1`) and `$72770/$72788` (port 1, `D0`/`D1`). PSG uses these shipped-ROM
write sites and operand sources: `$7225E:D0`, `$72268:D0`, `$723B6:D4`,
`$723C0:D4`, `$7246A:$1F(A0)`, `$724DC:$1F(A5)`, `$72912:D0`, `$72918:D6`,
`$72984:D6`, `$729AE:D0`, `$729BC:#$9F`, `$729C0:#$BF`, `$729C4:#$DF`,
`$729C8:#$FF`, `$72DFA:$1F(A0)`, and `$72E16:-1(A4)`.

At startup the fallback verifies the complete opcode bytes at every site
against the REV01 ROM and refuses partial coverage. It emits synthetic raw bus
events explicitly marked `source: "pc_manifest"`; the ordinary callback path
uses `source: "memory_callback"`. Both paths feed the same stateful YM decoder.
The manifest cites `docs/s1disasm/sonic.lst` and is derived from all shipped
driver write instructions, not observed GHZ coverage.

The decoder folds valid YM address/data pairs into:

```text
{ chip: "ym2612", port: 0|1, register: 0x00..0xFF, value: 0x00..0xFF }
```

PSG writes remain:

```text
{ chip: "psg", value: 0x00..0xFF }
```

An orphaned YM address or data operation, malformed pair, or unsupported port
operation fails capture. Raw events remain in local output for diagnosis;
decoded transactions form the parity contract.

## Logical state schema

Each tick contains global state, a fixed set of music-track roles, and ordered
decoded writes. The initial schema is versioned independently of trace schema 5
because this is not a trace-replay fixture or gameplay authority.

### Global state

The ROM state base is system-bus `$FFF000`; BizHawk `mainmemory` exposes the
64 KiB 68K work-RAM domain with `$FF0000` stripped, so it is read at `$F000`.
Before capture, the observer validates that `$F040` is the DAC track start and
that the ten `$30`-byte music slots at `$F040..$F21F` have the expected channel
initialization after `$81`. Execution hooks remain 24-bit ROM PCs. Mixing these
address domains is a capture failure.

Only fields with genuine equivalents gate parity:

| Semantic field | ROM source from `$FFF000` | OpenGGF source | Normalization |
|---|---:|---|---|
| tempo countdown | `+$01 v_main_tempo_timeout` | `SmpsSequencerSnapshot.tempoAccumulator` | unsigned byte; S1 timeout phase only |
| tempo reload | `+$02 v_main_tempo` | `tempoWeight` | unsigned byte after the S1 config transform is proved by a focused test |
| speed-up enabled | `+$2A f_speedup` | `speedShoes` | ROM bit 7 to boolean |
| speed-up reload | `+$29 v_speeduptempo` | derived configured speed-shoes tempo | unsigned byte; diagnostic until the derivation test proves identity |
| fade active/direction | `+$04`, `+$24` | `fade.active`, `fade.fadeOut` | only gate when either side is active; GHZ fixture expects both inactive |
| fade delay/steps | `+$06`, `+$25`, `+$26` | `fade.delayCounter`, `fade.steps` | compare only for the active fade direction |

Music ID `$81` is epoch metadata, not logical state: the ROM resets
`v_sound_id` to `$80`. Pause and queue bytes have no OpenGGF snapshot equivalent
and are capture-integrity diagnostics only. Voice selector, updating-DAC,
priority, communication, 1-up, and push/ring flags are retained locally for
diagnosis but do not gate MVP parity unless a later field mapping proves a
shared semantic.

### Music tracks

Records use fixed semantic roles:

```text
DAC, FM1, FM2, FM3, FM4, FM5, FM6, PSG1, PSG2, PSG3
```

The ten ROM slots begin at `$FFF040`, are `$30` bytes each, and map in order to
the roles above. An unused ROM slot and an absent OpenGGF track both normalize
to an explicit inactive role. For an inactive role only `active=false`, role,
and expected hardware channel gate parity; stale slot bytes are ignored.

Active roles use this field map (`T` is the slot base):

| Semantic field | ROM field | OpenGGF snapshot field | Rule |
|---|---:|---|---|
| active | `T+$00` bit 7 | `active` | boolean |
| resting | `T+$00` bit 1 | `note == $80` | gate only after a focused derivation test confirms every GHZ rest path |
| overridden | `T+$00` bit 2 | `overridden` | boolean |
| modulation enabled | `T+$00` bit 3 | `modEnabled` | boolean |
| do-not-attack | `T+$00` bit 4 | `tieNext` | boolean |
| hardware channel/type | fixed slot plus `T+$01 VoiceControl` | `type`, `channelId` | role comes from slot ordinal; validate active FM/DAC bytes `06,00,01,02,04,05,06`; active PSG bytes `80,A0,C0/E0`, where C0 and E0 are the PSG3 tone/noise alias |
| sequence position | `T+$04 DataPointer` | `pos` | ROM pointer minus GHZ asset ROM base |
| transpose | `T+$08` | `keyOffset` | signed byte |
| attenuation | `T+$09` | `volumeOffset` | signed/unsigned interpretation tested per S1 operation |
| pan/AMS/FMS | `T+$0A` | `pan`, `ams`, `fms` | split packed ROM byte |
| instrument/envelope id | `T+$0B` | FM `voiceId`; PSG `instrumentId` | unsigned byte |
| PSG envelope cursor | `T+$0C` | `envPos` | PSG only; unsigned byte |
| duration countdown | `T+$0E` | `duration` | unsigned byte |
| duration reload | `T+$0F` | `scaledDuration` | unsigned byte; `rawDuration` is diagnostic |
| base frequency | FM/PSG `T+$10` | `(baseBlock << 11) | baseFnum` for FM; `baseFnum` for PSG | unsigned word; gate after derivation tests, not `note`; omit for DAC because its `T+$10` aliases `SavedDAC` rather than `Freq` |
| note-fill countdown/reload | `T+$12/$13` | derived from `fill`, `duration`, `scaledDuration` | gate only after focused transition tests prove the derivation |
| modulation delay/speed/delta/steps/value | `T+$18..$1D` | `modDelay`, `modRateCounter`, `modCurrentDelta`, `modStepCounter`, `modAccumulator` | signed delta/value; individual fields become gates only after transition tests |
| detune | `T+$1E` | `detune` | signed byte |
| loop counters | `T+$24..$2F` | `loopCounters` | compare only indices referenced by parsed GHZ `$F7` commands |
| return stack | top grows down from `T+$30`, cursor `T+$0D` | `returnSp`, `returnStack` | normalize only live entries in call order |

The exact mapping registry is executable and versioned with the schema. A field
marked as awaiting a derivation test is diagnostic until that test exists; the
tool cannot silently promote it. ROM `Freq` is not compared with OpenGGF's note
number, effective PSG envelope values are not inferred from asset bytes, the
ROM modulation pointer has no current snapshot equivalent, and unused loop or
stack capacity is never compared.

Voice bytes, sequence bytes, envelopes, and other runtime asset payloads are
never copied into output. OpenGGF-only caches and ROM-only unused bytes remain
diagnostic or ignored.

## Musical-cycle stop contract

The capture does not use an audibly estimated duration. It computes a recurrence
signature over tempo phase and all active music-track playback state, excluding
frame numbers, ordinals, and non-musical monotonic metadata.

The signature includes only live return-stack entries and loop-counter indices
referenced by parsed GHZ loop commands. Stale bytes and unused OpenGGF array
capacity cannot create or prevent a recurrence.

A repeated signature is only a candidate boundary. The candidate period is
accepted after the immediately following period has:

- the same normalized state-signature sequence; and
- the same per-tick decoded-register event hashes.

This rejects a repeated note or phrase that happens to share one state. Capture
ends at the third occurrence of the accepted boundary, yielding one proven
cycle plus one identical repeated cycle. Failure to establish the cycle within
36,000 audio-driver invocations is a capture failure with the candidate history
included in the report.

The reference metadata publishes the accepted boundary start ordinal, period,
and exact terminal record count. OpenGGF always runs for that exact record count,
even if its state never recurs or recurs elsewhere. Engine-side cycle detection
is diagnostic only and cannot shorten or extend the comparison interval.

## Comparison order and diagnostics

The comparator performs these gates in order:

1. Metadata, identity, schema, and capture-integrity validation.
2. Tick count and ordinal continuity.
3. Exact normalized logical-state comparison.
4. Exact decoded-register transaction comparison, including order.
5. Local raw-bus context attachment for any register mismatch.

State divergence reports name the tick, track role, field, ROM value, and
OpenGGF value. Register divergence is classified as missing, extra, reordered,
or value-different and includes the eight preceding and eight following
transactions. The report also includes the current normalized track context and
ROM routine where the capture can identify it without guessing.

No mismatch automatically authorizes a production change. A proposed chip
ordering correction requires:

- proof that the capture point and tick alignment are correct;
- the exact shipped-ROM driver routine responsible for the order;
- an explanation of why the current OpenGGF order is incorrect rather than
  merely redundant or represented at a different abstraction boundary; and
- a focused regression test that preserves adjacent known-good transactions.

This safety policy is especially important because OpenGGF's chip-write ordering
is already largely correct and has been regressed by broad audio changes in the
past.

## Capture contamination rules

After tick zero, capture fails rather than silently filtering if any of these
occurs:

- another music or SFX request enters a sound queue;
- `$81` is accepted a second time;
- pause, fade, reset, Sega PCM, or speed-up commands are requested externally;
- the emulator leaves the stable Level Select/Sound Test mode unexpectedly;
- the sound driver is reinitialized; or
- movie completion produces non-neutral controller state.

Driver-generated DAC note activity is part of GHZ music and is not
contamination. DAC sample bytes and sample timing remain outside the MVP
comparison.

## Gameplay timeline runner trust boundary

The gameplay timeline runner's trust boundary begins only when control reaches
`run_s1_ghz1_gameplay_audio_timeline.sh`. The kernel, the system dynamic loader,
and the parent launch environment through creation of the runner process are
trusted. Callers must launch the runner without dynamic-loader injection,
including any `LD_*` environment variable.

The runner is a dynamically linked Bash process. `LD_PRELOAD`, `LD_AUDIT`, or
another loader control can therefore load code before Bash executes the first
script instruction. Even a nonexistent preload or audit library can make the
system loader print its own diagnostic before the runner can respond. That
pre-start execution and diagnostic are outside the runner's protection; a
hostile parent environment requires an external clean launcher or static
bootstrap, neither of which this tool claims to provide.

Once control reaches the script, its first guard rejects every inherited
`LD_*` variable with exit status `4`, before argument parsing, repository path
resolution, or project/tool work. The runner then rejects the separately named
Java, Maven, Mono, Bash, and producer replacement variables, fixes `PATH` to
`/usr/bin:/bin`, selects bootstrap executables by absolute system path, and
starts every Maven, Java, Mono/BizHawk, and comparator child through `env -i`
with only the explicit display/session values needed by those tools. These
post-start controls do not weaken or imply protection before process creation.

## Verification

### Lua and capture tests

- Compile Lua with `lupa.LuaRuntime().compile(...)`, matching the repository's
  established diagnostic validation command.
- Enforce the read-only probe policy lexically and through focused contract
  tests.
- Verify the ROM and BK2 identities before capture.
- Prove that the `$71FD2`/`D7 == $81` epoch is reached exactly once.
- Cross-check raw port callbacks against PC hooks over a short initialization
  window.
- Test malformed YM pairing, missing PSG coverage, contamination, movie-end
  continuation, safety-limit exhaustion, and cleanup.
- Run the reference capture twice and require byte-identical normalized output.

### Java tests

- Unit-test every signed-field and pointer normalization.
- Test fixed role mapping, inactive tracks, modulation, envelopes, loop
  counters, and return stacks with synthetic snapshots.
- Test the low-level YM/PSG observation seam directly, including direct writes,
  the complete `setInstrument(...)` expansion, and `silenceAll()`, proving that
  enabling observation does not change chip state or ordering.
- Mutation-test the comparator with changed state, changed value, swapped
  events, missing events, and extra events.
- Run the OpenGGF capture twice and require byte-identical normalized output.

### End-to-end validation

Run the BizHawk capture, OpenGGF capture, and comparator against the discovered
REV01 ROM. The report must show a valid two-cycle interval. Zero state and
register mismatches are required before documenting GHZ music parity. A red
report remains a valid diagnostic result but does not establish parity.

Focused and full Maven verification run on JDK 21. The full suite uses
`mvn clean test -Pci`: the repository's single-fork CI profile avoids LWJGL
native-extraction races, while `clean` prevents Maven Silent Extension from
summarizing stale Surefire XML. `libglfw.so` is supplied by the activated LWJGL
Linux natives classifier; a default four-fork extraction race must not be
reported as a missing system library. Full-suite delivery compares against the
integration baseline using the repository's documented failure-manifest
method; pre-existing failures do not block delivery, but no new or worsened
result is permitted.

## Repository and copyright boundaries

Tracked deliverables may include source code, tests, the controller-only BK2,
schema documentation, and compact non-reconstructive summaries. Detailed ROM
and engine capture streams live only under ignored `target/audio-parity/`
paths.

Never commit:

- complete YM2612/PSG transaction streams;
- PCM, WAV, or VGM output;
- sequence or voice bytes copied from the ROM; or
- any other payload capable of replaying the GHZ composition without the
  user's ROM.

The Lua and Java streams are comparison-only. Runtime gameplay and audio state
must never read them, and they grant no timing or behavior authority.

## Follow-up sequence

After GHZ music reaches parity:

1. Add music-only cases that cover distinct S1 driver features without changing
   the schema opportunistically.
2. Add isolated SFX captures.
3. Add music/SFX contention and channel-override cases.
4. Add DAC timing and final PCM comparison as separately designed layers.
5. Consider Sonic 2 and Sonic 3&K only after the contract proves portable
   without game-name carve-outs in shared runtime code.
