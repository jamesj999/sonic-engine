# FM core benchmark

This opt-in tool compares OpenGGF's production Java Nuked-OPN2 port with the
pinned C Nuked reference and pinned C++ ymfm on one ROM-free synthetic six-FM
workload. It is a correctness/provenance harness with local timing diagnostics,
not an audio-fidelity gate or a backend-selection benchmark.

The entry point verifies every compiled upstream input, builds only below the
invoking worktree's `target/`, checks matching Java and C Nuked 64-bit FNV-1a
hashes over the ordered, signed, interleaved stereo samples, compares snapshot
replays sample by sample for all three implementations, and proves each
snapshot check is live by keying off a channel as an active negative control. The final
`result.json` records source pins, tool versions, host identity, Git state,
measurement dimensions, timings, and validation outcomes. It always marks
timings `publishable: false`; publication requires a separately reviewed run on
a quiet, documented host.

## Requirements

- JDK 21 (`java` and `javac`)
- Python 3
- a C compiler and a C++14 compiler (`CC` / `CXX` may override `cc` / `c++`)
- Git and network access only when fetching sources
- optional Linux `taskset` when `--cpu` is supplied

Fetch the exact external revisions into disposable build output:

```bash
tools/audio/fm-core-benchmark/fetch-sources.sh \
  --output "$PWD/target/fm-core-sources"
```

Run the default local diagnostic:

```bash
tools/audio/fm-core-benchmark/run.sh \
  --output "$PWD/target/fm-core-benchmark/run-1" \
  --nuked-source "$PWD/target/fm-core-sources/nuked" \
  --ymfm-source "$PWD/target/fm-core-sources/ymfm"
```

`--frames`, `--warmups`, and `--iterations` change measurement dimensions.
`--cpu N` is optional; without it there is no affinity assumption. Results,
objects, executables, fetched sources, and class files remain below `target/`.

Run the fast malformed-input and failure-control tests with:

```bash
tools/audio/fm-core-benchmark/tests/test-tool.sh
```

### Java profile mode

The fixed-work profile driver separates six-channel sustain, channel release,
and a synthetic DAC write stream. Preparing it compiles and hashes every Java
input without running the workload:

```bash
tools/audio/fm-core-benchmark/profile-java.sh --prepare-only \
  --output "$PWD/target/fm-core-profile/prepare"
```

On an otherwise quiet host, `--record` produces `profile.jfr` and a JDK
`hot-methods` view. It deliberately reports no wall-clock timing and marks its
provenance non-publishable. The synthetic DAC phase clocks address/data writes
as well as output frames and is not a game-capture substitute.

### JNI correctness proof

The Linux-only JNI experiment builds an ephemeral shared library from the
pinned Nuked C source and exercises actual interleaved stereo PCM transfer:

```bash
tools/audio/fm-core-benchmark/run-jni-proof.sh \
  --output "$PWD/target/fm-core-jni-proof/run-1" \
  --nuked-source "$PWD/target/fm-core-sources/nuked"
```

It checks Java/C samples, arbitrary clock chunking, restore of a seven-cycle
partial frame into a fresh handle, active key-off controls, invalid capacity
and snapshot rejection before mutation, idempotent close, use after close, and
loading after the generated library is relocated. The result hashes every
compiled input and records compiler flags. This proves only a research bridge
on the tested Linux host. Its opaque snapshot is valid only for the exact same
library build; it is not a persistence or compatibility format. No generated
library is a repository or release artifact.

Passing `--capture path/to/complete-bus.jsonl` first applies the fail-closed
capture validator and then compares Java/C Nuked PCM for the complete declared
raw-YM segment. Null endpoints, overflow, missing ordinals, non-monotonic or
out-of-range writes, interpolation/restored-unknown origins, and state-changing
YM boundaries reject. The only post-start exception is the typed
`OUTPUT_GATE_CHANGE`, which cannot affect raw chip pins; accepting it explicitly
means the result does not reconstruct presentation PCM. PSG remains in the
source artifact but is only counted by this YM-only consumer.

The separately pinned ymfm candidate can consume the same admitted input:

```bash
tools/audio/fm-core-benchmark/run-fast-capture.sh \
  --output "$PWD/target/fm-core-fast-capture/run-1" \
  --ymfm-source "$PWD/target/fm-core-sources/ymfm" \
  --capture path/to/complete-bus.jsonl
```

This correctness-only run checks deterministic rendering, native snapshot
replay, and an active key-off control. ymfm generates whole frames rather than
Nuked internal cycles, so writes at cycles `24n..24n+23` are applied before
ymfm frame `n`. That quantization is recorded in `result.json`; neither its
checksum nor determinism establishes fidelity equivalence.

The focused tool checks are:

```bash
tools/audio/fm-core-benchmark/tests/test-profile-tool.sh
tools/audio/fm-core-benchmark/tests/test-jni-proof.sh \
  --nuked-source "$PWD/target/fm-core-sources/nuked"
tools/audio/fm-core-benchmark/tests/test-fast-capture.sh \
  --ymfm-source "$PWD/target/fm-core-sources/ymfm"
tools/audio/fm-core-benchmark/tests/test-capture-validator.sh
```

## Pins, licensing, and exclusions

Nuked-OPN2 is pinned at commit
`335747d78cb0abbc3b55b004e62dad9763140115`, tree
`6637a500d1da3b08cbc0cec1532ab305197b8978`, under
`LGPL-2.1-or-later`. This matches the existing production-port pin in
`tools/audio/nuked-opn2/PIN.md`. ymfm is pinned at commit
`81aec25ccbb98f4873a255f7551ac4dadac59b4a`, tree
`03f76ed27b1281357c91005e99d043eebd5119c1`, under `BSD-3-Clause`.
`nuked.lock` and `ymfm.lock` hash the licence and every source/header consumed
by these builds. Do not distribute generated linked binaries without satisfying
the applicable licence obligations.

The harness accepts no ROM or movie input. Do not commit fetched upstream
trees, ROMs, BK2s, expanded register streams, generated PCM/WAV, objects,
executables, JDKs, profiles, or benchmark logs. In particular, the old
engine-derived stream experiment omitted the fixtures' internally streamed DAC
bytes; that limitation belongs only to those fixtures. Current production
`playDac` callbacks do observe real streamed DAC bytes (since `0ae29a261`).

The order-, sign-, and channel-sensitive Java/C stream hash is a compact guard
for this synthetic stream, not a collision-free sample proof; the repository's
bit-exact port tests carry the independent sample-level proof. ymfm has a
different output model, scale, and phase, so its hash is intentionally not
compared with Nuked. The harness does not
exercise timers, IRQ/CSM integration, physical output transfer, resampling,
audio scheduling, full-game load, listening quality, or non-Linux portability.
The standalone JNI proof closes only its stated PCM-transfer and lifecycle
questions; it does not establish production integration, packaging, native
snapshot portability, or Windows/macOS support.
