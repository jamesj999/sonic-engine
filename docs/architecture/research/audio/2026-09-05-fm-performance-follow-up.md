# FM performance follow-up (2026-09-05)

## Decision

Do not change the production FM backend or the Java Nuked core in this round.
This follow-up closes two previously explicit feasibility gaps with maintained,
ROM-free tooling: actual stereo PCM crosses the research JNI boundary, and a
partial native output frame survives snapshot/restore into a fresh handle. It
also prepares a deterministic JFR driver and a fail-closed consumer for the new
complete physical-bus capture format. Neither result is production integration
or release evidence.

Performance work waited until the coordinator confirmed there were no Maven or
candidate workloads running. The host was still an interactive desktop and was
not exclusively reserved, so all numbers remain local research diagnostics,
not publication or lower-end evidence. No physical-input runner reports timing:
that would conflate JNI-per-event overhead or ymfm's fidelity-affecting
quantization with a production backend. Consequently there is no Java
optimisation to retain from this round.

## Existing Java baseline

The current production core already includes four measured optimisation passes:

- `7c1f6b178` removed hot slot modulo divisions;
- `817e81249` flattened multidimensional table lookups;
- `c9c37bb74` made the per-cycle pipeline more amenable to inlining; and
- `97cc5a077` cached per-slot state where the C source rereads it.

Those changes and their historical measurements remain documented by their
commits and the preceding
`2026-09-04-fm-core-performance-exploration.md`. Repeating or speculating about
them without a fresh profile would not identify the current bottleneck. The new
`profile-java.sh` therefore has separate fixed-work sustain, release, and DAC
phases, hashes every compiled Java input, and emits JFR plus the JDK hot-method
view only in explicit `--record` mode. Its compile-only mode was validated here;
record mode was run only after the build-idle signal. The DAC phase includes the
address/data service clocks and is synthetic, not representative game input.

The JFR recording used 20 passes of 100,000 frames per phase on CPU 6 under
OpenJDK 21.0.11. Of 878 hot-method samples, `NukedOpn2.clock` held 65.38%,
`envelopeAdsr` 14.12%, `envelopePrepare` 8.09%, `chOutput` 7.63%, and
`doRegWrite` 1.59%; harness `frame` accounted for 1.14%. `clock` is large and
contains/inlines much of the pipeline, so its share does not isolate an
individual expression's cost.

Three baseline processes each used three warmups and ten 53,267-frame
iterations on CPU 6. The median of their per-process medians was 878.81
ns/frame for Java Nuked, 535.79 for C Nuked, and 165.51 for C++ ymfm. This puts
the raw synthetic ratios near 1.64x Java-to-C Nuked, 3.24x C-Nuked-to-ymfm, and
5.31x Java-to-ymfm on this host only.

One bounded trial replaced the remaining `egQuotient` modulo-three update with
a ternary wrap. The state documents and preserves the 0..2 invariant, reset
initializes zero, snapshots copy it, and the pinned C changes it only with
`(value + 1) % 3` at cycle 1. All 896 focused tests passed, including 732
C-derived per-cycle vectors and 68 facade parity cases. Three equal-order
candidate processes measured 883.73 ns/frame for Java (+0.56%, a regression),
while unchanged C Nuked and ymfm controls also slowed to 541.26 and 173.13.
The apparent change is noise/host drift rather than a gain; the production edit
was reverted.

## Actual-PCM JNI proof

`run-jni-proof.sh` builds a target-only shared library from the already pinned
Nuked C source and calls it through a small research JNI interface. On this
Linux x86-64 host, both HotSpot 21.0.11 and an Oracle GraalVM Native Image
21.0.12+7.1 executable passed the same assertions:

- Java and C Nuked produced identical signed, ordered, interleaved stereo PCM;
- arbitrary `1, 23, 24, 1024`-cycle call chunking did not change samples;
- a snapshot taken seven cycles into a 24-cycle output frame replayed exactly
  after restore into a newly allocated handle;
- a key-off branch changed 100 later frames, proving the comparison was active;
- undersized PCM output and corrupt snapshot inputs were rejected before state
  mutation; and
- close was idempotent, use after close was rejected, and an absolute shared
  library path still loaded after relocation within the generated output.

The HotSpot result contains no timing and records the exact hashes of the three
production Java core inputs, Java and C harness inputs, pinned `ym3438.c` and
`ym3438.h`, JDK/compiler identity, fixed `-O2 -fPIC -shared -Wl,-z,defs` flags,
host identity, and Git state. The Native Image check used the already verified
archive whose SHA-256 is recorded in the preceding report; its executable and
the generated shared library remained below `target/` and are not deliverables.
The freshly built Native Image executable also consumed the normalized AIZ1
capture through the JNI PCM path: all 233,222 YM events produced the same
799,005 Java/C frames with zero mismatch.

The native snapshot is deliberately an opaque, versioned byte array valid only
for the exact same library build. It copies the pinned C context plus the JNI
bridge's partial-frame sums/cycle. It is not stable across compiler, platform,
core revision, or bridge layout, and must never be used as a save-state format.
The proof does not cover concurrent handles with different upstream-global chip
modes, audio-thread scheduling, resampling, packaging, unloading, or crash
containment.

## Complete physical-bus consumer

`validate-capture.py` admits only a complete
`openggf-physical-chip-bus-v1` constructor-reset YM segment with non-null
`ym_replay_start_ordinal` and `terminal_ym_cycle`, no overflow or dropped events,
contiguous ordinals, monotonic in-range raw bus cycles, ports `0..3`, byte
values, and reconstructable `EXTERNAL_BUS` or `DAC_STREAM` origins. Older files
without endpoint fields reject rather than acquiring guessed duration.

The diagnostic prefix remains in JSONL. It may contain no YM strobe and any YM
boundary there must be at clock zero. After the declared start, reset, restore,
rollback, model mutation, unknown boundaries, DAC interpolation, and restored-
unknown DAC origin all reject. `OUTPUT_GATE_CHANGE` is the sole allowed YM
boundary because it changes presentation output without changing raw Nuked pin
state. Allowing it explicitly means the candidate reconstructs raw YM pins, not
the engine's presentation PCM. PSG events remain independently clocked and are
counted but ignored only for this explicitly YM-only comparison.

The optional capture path in `run-jni-proof.sh` normalises the admitted YM
strobes, replays them at their exact internal cycles into Java and C Nuked, and
compares every complete raw stereo frame through the exact terminal cycle. A
small committed synthetic capture exercises the contract and the typed output-
gate exception.

A diagnostic AIZ1 capture generated from the working `bbf28b7dc` tree exercised
the full consumer path. Its SHA-256 was
`979bb9f20e74f11e54322b47b206c264c14da011316bbadedfe6603cdd77ba72`;
the header declared 240,956 events, no overflow, replay start ordinal 21, and
terminal cycle 19,176,120. After retaining/counting 7,715 PSG events and one
typed output-gate boundary, the consumer replayed 233,222 YM strobes (214,788
real `DAC_STREAM`, 18,434 `EXTERNAL_BUS`) and compared 799,005 Java/C Nuked raw
stereo frames sample by sample with zero mismatch. The producer honestly marked
the working tree dirty, so this is investigative evidence to regenerate from a
reviewed commit, not release evidence. No corpus, expanded event stream, PCM,
or result output is committed.

`run-fast-capture.sh` feeds that same admitted input to pinned C++ ymfm. The
candidate generated 799,005 frames deterministically across two replays; its
independent native save/restore comparison had zero errors and its key-off
negative control changed 109 frames. It emits a checksum but no PCM or timing.
Because ymfm generates one whole frame at a time, the tool explicitly maps all
writes at native cycles `24n..24n+23` before ymfm frame `n`. That quantization
differs from Nuked's per-cycle pipeline: deterministic handling of complete
input proves tooling activity and repeatability, not waveform, subframe, or
hardware fidelity.

## What this changes about the alternatives

1. **Profile Java first.** This remains the lowest-risk next action. A candidate
   must be selected from a quiet-host current-core profile, pass sample/state
   equality and active controls, and show a meaningful repeated equal-order
   improvement before production code changes. The speedup is unknown.
2. **Batched native Nuked is technically more credible, not production-ready.**
   The earlier checksum-only bridge now has actual PCM and snapshot/lifecycle
   evidence on one Linux host. The same pinned synthesis core lowers semantic
   risk, but JNI error containment, ownership, platform builds, packaging,
   distribution compliance, audio-thread impact, and low-end benefit remain
   unknown.
3. **ymfm remains a fast-fidelity candidate, not a drop-in backend.** Complete
   input admission and deterministic replay now work, but performance is
   irrelevant until its different subframe/output model is assessed against
   hardware and listening evidence. No equivalence claim follows from its
   historical synthetic throughput or the new deterministic checksum.
4. Retired-core resurrection and a clean-room core retain the provenance,
   licence, fidelity, and high-cost limitations ranked in the preceding report.

## Licensing, portability, and release boundary

Nuked remains pinned at `335747d78cb0abbc3b55b004e62dad9763140115`
under `LGPL-2.1-or-later`; the tool verifies all compiled upstream inputs. The
research bridge dynamically loads a generated shared object, but that fact does
not settle release packaging or LGPL obligations. No legal conclusion is made.
ymfm remains pinned under `BSD-3-Clause`. No upstream source, JDK, native binary,
profile, ROM, capture corpus, or generated audio is committed.

Only Linux x86-64/JDK 21 was exercised. Windows, macOS, other architectures,
lower-end CPUs, native library discovery in packaged applications, signing,
installer behaviour, and supported Native Image packaging are unproven. The
0.6 release gates remain package/suite/guard/trace regression evidence and human
listening QA; a backend replacement and all performance budgets remain deferred
research unless a candidate run exposes a release-impacting regression.
