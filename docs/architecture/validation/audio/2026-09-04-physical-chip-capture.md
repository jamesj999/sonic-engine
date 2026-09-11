# Physical chip capture validation

## Scope

September 5 follow-on: [bounded raw-YM replay and slice evidence](2026-09-05-bounded-ym-replay-and-slice.md)
adds explicit endpoint proof and distinguishes `OUTPUT_GATE_CHANGE` from
actual chip mutation. The contract below records the original milestone;
the linked additive contract governs the new raw-YM-only consumer.

This change adds an opt-in diagnostic capture of the dispatched YM2612 and PSG
bus streams. It does not select a production FM backend, change driver timing,
alter trace schema 5, or make captured timing gameplay authority.

`ChipWriteObserver` keeps its existing logical-write methods. A consumer that
also returns `true` from `observesPhysicalWrites()` receives the additive raw
methods. The ordinary path therefore does not create a diagnostic `Runnable`
or a physical event for each DAC strobe.

## Contract and provenance

YM callbacks occur immediately after `NukedOpn2.write(port, value)` and before
the following core clock. `busPort` is the native 0--3 port, so address and data
strobes remain distinct. The timestamp is a diagnostic-only, monotonic YM
internal-cycle counter. Its rate is `Ym2612Chip.getInternalRate() * 24`.

PSG callbacks contain the raw byte at a diagnostic-only, monotonic generator
tick count, whose rate is `PsgChip.TICK_RATE_HZ`. These domains are deliberately
not a shared master clock: consumers must preserve callback order and must not
sort YM cycles against PSG ticks numerically.

YM origin is explicit:

* `EXTERNAL_BUS` is a normal resolved engine write.
* `DAC_STREAM` is a byte presented by the modeled DAC playback loop.
* `DAC_INTERPOLATION` is the engine's synthetic presentation option and is not
  hardware-reference evidence.
* `RESTORED_UNKNOWN` is a pending DAC data strobe resumed from a snapshot; the
  production snapshot intentionally does not retain its earlier provenance.

The capture emits no invented bus write for reset, restore, policy mutation,
output gating, mute, force-silence, or rollback. Instead it emits a native-domain
boundary: `RESET`, `SNAPSHOT_RESTORE`, `MODEL_MUTATION`, or
`TRANSACTION_ROLLBACK`. Force-silence reports its boundary while it is queued,
which is conservative: the segment is invalidated before the deferred physical
mutation occurs.

An aborted session transaction drops its staged bus callbacks. Its subsequent
restore callbacks are also private, then a surviving `TRANSACTION_ROLLBACK`
boundary is published for each chip before later committed events. This avoids
exposing aborted writes while making the monotonic-clock gap explicit.

Raw bus replay is valid only from the tool's explicit, trusted
`constructor_reset` initial-state header (including the declared core mode), or
from an externally established equivalent configuration, and within a segment
that has no non-bus boundary. A `RESET` event by itself does not establish that
configuration: YM reset retains chip type, mutes and output rate, while PSG reset
retains type, preamp and panning. In particular, a raw file is not an exact PCM replay
across mute, output-gate, policy, snapshot, or synthetic interpolation
boundaries. It is an engine/Nuked comparison capture, never a hardware recording.

## Tool artifact

`FmSfxRenderTool --physical-writes` attaches its physical-only observer before
the owned stream installs, so session boot writes and channel-mask mutations are
captured without changing the historical logical-write log. It writes the
bounded, versioned JSONL sidecar
`<game>-<kind>-<id>-ym-bus.jsonl` in addition to the unchanged legacy
frame-stamped `-ym-writes.txt` file. Its header identifies OpenGGF, the
`nuked-opn2` YM core, its known YM2612/read-mode flag set
(`MODE_YM2612 | MODE_READMODE`, numeric `3`), constructor-reset initial state,
output rate, absolute ROM
path and SHA-1, engine version/commit/dirty state, per-chip rates/domains,
capacity, event count, and overflow state. Events have a process-local callback
ordinal, but that ordinal is the only cross-chip order; it is not a conversion
between chip clocks.

The default capacity is one million events and `--physical-capacity` changes it.
Overflow drops subsequent events, records `overflow` and `dropped` in the JSONL
header, and makes the CLI fail after rendering. A partial file is explicitly not
replayable.

## Tests and evidence

The first red test introduced raw observer references before the additive API,
which failed compilation as expected. The PSG timing test was then red because
physical PSG callbacks were absent. The green coverage now verifies:

* paced YM 0/1 and 2/3 strobes at cycles 0, 1, 35, and 36;
* reset-origin raw YM replay into a fresh `NukedOpn2` core;
* replay-segment rejection for unknown initial configuration, non-bus
  boundaries (including reset after unknown configuration), unknown restored
  provenance, non-monotonic clocks, and invalid raw ports;
* real DAC and synthetic DAC provenance without synthetic legacy callbacks;
* PSG generator-tick timestamps;
* snapshot/mutation boundaries, non-bus configuration/admission restores, and
  observer-on output/snapshot equivalence;
* session forwarding, rollback discard plus surviving discontinuity, and later
  committed capture;
* JSONL domains/provenance/overflow reporting; and
* ROM-backed CLI emission of the physical JSONL sidecar.

Focused green command (Sonic 1 path is absolute so the ROM-gated tool test runs):

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B \
  -Dtest=TestChipWriteObserver,TestYm2612ChipSnapshot,TestPsgChipSnapshot,TestYm2612DacTiming,TestSmpsPhysicalDevice,TestSmpsSegaPcmTransport,TestSegaPcmCommandRouting,TestSmpsSessionDiagnostics,TestPhysicalChipCapture,TestSfxRenderToolEntryPoints \
  "-Dsonic1.rom.path=${S1_ROM}" test
```

The baseline whole-suite evidence is owned by the coordinated round; no main
worktree Maven command was run for this change.
