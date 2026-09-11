# Nuked-OPN2 adapter parity harness

`adapter_parity_harness.c` drives the pinned upstream `ym3438.c` (fetched with
`../fetch-source.sh`, never vendored) with exactly the bus pacing and frame
summation that `com.openggf.audio.synth.Ym2612Chip` applies, so the Java
facade can be pinned sample-for-sample against the C build without a ROM.

The pacing is `ADDRESS_HOLD` / `DATA_HOLD` at the top of the C file — an
address strobe, 1 clock, a data strobe, then 34 clocks (2 + the 32-cycle busy
window, the first clock at which a status poll reads not-busy) — and must
equal `Ym2612Chip.ADDRESS_SETTLE_CYCLES` / `DATA_SETTLE_CYCLES`; change both
together and regenerate.

`generate-adapter-scripts.py` writes the register scripts that
`TestYm2612ChipNukedParity` runs; `regenerate-expectations.sh` rebuilds the
harness, runs every script through it and rewrites `expected.txt` next to the
scripts (`src/test/resources/audio/nuked-opn2/adapter/`). Regenerate only when
the scripts or the adapter's documented pacing change, and say so in the
commit; a changed checksum with unchanged scripts means the facade or the
port drifted from the C build.

```bash
tools/audio/nuked-opn2/fetch-source.sh --output /abs/path/to/nuked-src
tools/audio/nuked-opn2/harness/regenerate-expectations.sh /abs/path/to/nuked-src
```

## Port-level bit-exactness harness

`bitexact_harness.c` is the pin-level twin of
`com.openggf.audio.synth.nuked.NukedOpn2ScriptRunner` (test sources): it
clocks the pinned `ym3438.c` with `OPN2_Clock` and streams the raw MOL/MOR
value of every internal cycle as little-endian int16 stereo, with no summing,
scaling or resampling, so the Java port can be compared sample-for-sample.
Both sides read the same script grammar (`type`, `pace`, `write`, `reg`,
`clock`, `at`, `status`, `irq`, `dump`; see the C file header). The script
bodies under `src/test/resources/audio/nuked-opn2/port/` are:

- the synthetic set from `generate-bitexact-scripts.py` — the
  `TestNukedOpn2PortSmoke` patch and a sweep over EG rates, SSG-EG modes, LFO
  settings, detune/multiple, channel 3 special mode and CSM, timers, DAC
  writes, the LSI test registers, bus edge cases and a seeded fuzz;
- real SMPS write logs (`s1-`, `s2-`, `s3k-`) captured with
  `com.openggf.tools.audio.FmSfxRenderTool --rate internal` and converted by
  `log-to-bitexact-script.py`, in which `at <frame>` stamps are exact multiples
  of 24 cycles.

`regenerate-bitexact-expectations.sh` runs every body under all four chip-type
flag sets through the C build and rewrites `expected.txt` (cycle count,
per-cycle stream checksum, side-log line count and checksum);
`TestNukedOpn2BitExactScripts` asserts those on the port. The validation
record with the full per-script results is
`docs/architecture/validation/2026-08-29-nuked-opn2-port-bit-exactness.md`.

```bash
tools/audio/nuked-opn2/harness/regenerate-bitexact-expectations.sh /abs/path/to/nuked-src
```

## Isolated phase arithmetic matrix

`phase-matrix-harness.c` includes the same verified upstream source and calls
its actual static `OPN2_PhaseCalcIncrement` function. It emits three FNV-1a
fingerprints over little-endian 32-bit phase increments for
`TestNukedOpn2PhaseMatrix`: every 11-bit frequency / LFO phase at zero PMS,
every signed detune / keycode pair, and an active-PMS boundary matrix.
This supplements, rather than replaces, the cycle-level scripts above.

```bash
tools/audio/nuked-opn2/fetch-source.sh --output /abs/path/to/nuked-src
mkdir -p target/nuked-phase
cc -O2 -I /abs/path/to/nuked-src tools/audio/nuked-opn2/harness/phase-matrix-harness.c -o target/nuked-phase/phase-matrix
target/nuked-phase/phase-matrix
mvn -Dtest=TestNukedOpn2PhaseMatrix test
```

Expected outputs: `zero-pms 65536 88bfe960c6f67325`,
`signed-detune 256 59778aea1488df65`, and
`active-pms 1032192 ea93f3fc6c934311`. Do not regenerate expectations to
accommodate an optimization; unchanged reference behavior is the acceptance gate.
