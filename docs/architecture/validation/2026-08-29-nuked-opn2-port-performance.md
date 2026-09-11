# Nuked-OPN2 port performance (round 1 and optimisation pass)

**Date:** 2026-08-29
**Branch / commit:** `feature/ai-nuked-opn2-fm-core` at `4da04a09d`
(worktree `.worktrees/nuked-opn2`), compared against `develop` at `8290558c4`
(throwaway `git clone --shared` under the session scratchpad; neither the main
checkout nor this worktree was modified for the comparison).
**Machine:** AMD Ryzen 9 9950X (16C/32T), Linux 7.2.0, OpenJDK 21.0.11 (Arch),
G1 GC, default JIT. All numbers are single-threaded wall time on one core.

## Verdict

**Pass.** The cycle-accurate core costs about ten times more than the
sample-level core it replaces, but at the engine's output rate it stays well
inside the 10 %-of-one-core budget, and it allocates nothing in steady state.

| Measurement | Rate | Median ns / sample | CPU of one core |
|---|---|---|---|
| `NukedOpn2.clock` x 24, native frames (1 s per run) | 53 267 Hz | 1 139 - 1 163 (two runs) | **6.1 - 6.2 %** |
| `Ym2612Chip` facade, this branch (`renderStereo`, 1024-frame chunks) | 44 100 Hz | 1 496 | **6.6 %** |
| `Ym2612Chip` facade, `develop` (same harness, same rate) | 44 100 Hz | 141.5 | 0.62 % |
| Whole SMPS stack fade window, this branch (`TestSmpsFadeAudioThroughput`) | 44 100 Hz | - | 16.01x realtime = **6.25 %** |
| Whole SMPS stack fade window, `develop` | 44 100 Hz | - | 153.18x realtime = 0.65 % |

The facade at 44.1 kHz is more expensive per output sample than the bare core
per native frame because every output sample still needs 53 267 / 44 100 = 1.21
native frames (29 internal cycles) plus the resampler; 1 496 ns / 1.21 = 1 237 ns
per native frame, so the facade adds roughly 8 % over the bare core. The old
core's ~140 ns/sample was a per-sample operator model with no sequencer, so the
~10.6x ratio is the expected price of clocking all 24 slots per frame.

Per-frame cost at 60 Hz: 735 output samples x 1 496 ns = **1.10 ms** of the
16.67 ms frame budget for FM on this branch, versus 0.10 ms on `develop`.

## Allocation

* `ThreadMXBean.getCurrentThreadAllocatedBytes` around each timed 1 s run:
  **0 bytes** for all 10 runs of the native-rate core bench and all 10 runs of
  the facade bench (both this branch and `develop`).
* `-Xlog:gc` on the same bench process: no GC pause logged during the run
  (only the initial "Using G1" line).
* The whole-stack fade bench reports `medianAllocatedBytes=31944` per 4.5 s
  window on **both** trees, so that residual belongs to the SMPS driver /
  sequencer and is unchanged by the port.

## Method

### Micro-bench (`NukedPerfBench`, source below)

* Programs all six channels with a two-carrier algorithm-4 voice (LFO on, AMS/PMS
  set, both output pins enabled) and keys them on, so the core does real
  envelope/phase/LFO work rather than running silent.
* **Native rate:** 53 267 frames x 24 `clock(int[])` calls per run, summing the
  MOL/MOR pin values as the facade does. 3 warm-up runs, then 10 timed runs;
  median reported. CPU % = median ns/sample x 53 267 / 1e9.
* **Facade:** `new Ym2612Chip()` at `getOutputSampleRate()` (44 100 Hz),
  `renderStereo(left, right, 1024)` chunks for 1 s per run with one key-on
  write per chunk to keep the write path live. Same warm-up / run structure.
  The `develop` build was driven through the identical public signatures
  (`write(port, reg, val)`, `renderStereo`, `getOutputSampleRate`) only.
* Compiled with `javac` against each tree's `target/classes`; no Maven
  involvement, no ROM.

Raw output, this branch:

```
NUKED_NATIVE median_ns_per_sample=1139.4 cpu_pct_one_core_at_53267Hz=6.07 runs=[1139.7941314509922, 1139.1663318752699, 1140.0967390692174, 1138.9526911596297, 1138.5336512287158, 1138.3040531661254, 1139.4158296881747, 1142.9415210167647, 1144.3619126288322, 1136.3206300336044] allocBytesPerRun=[0, 0, 0, 0, 0, 0, 0, 0, 0, 0] sink=0
FACADE_NEW outputRate=44100.0 median_ns_per_output_sample=1496.1 cpu_pct_one_core=6.60 runs=[1496.145283446712, 1496.1536734693877, 1496.0885941043084, 1492.7422902494332, 1491.0109977324264, 1491.4653968253967, 1495.3761224489797, 1497.298344671202, 1500.7090702947846, 1489.819342403628] allocBytesPerRun=[0, 0, 0, 0, 0, 0, 0, 0, 0, 0] sink=4848858
NUKED_NATIVE median_ns_per_sample=1162.9 cpu_pct_one_core_at_53267Hz=6.19 runs=[1163.23744532262, 1162.9406198959957, 1161.776109035613, 1162.3010306568794, 1162.852948354516, 1164.5255035950963, 1162.6588319222033, 1169.8053954606041, 1168.6050087296076, 1157.2965250530347] allocBytesPerRun=[0, 0, 0, 0, 0, 0, 0, 0, 0, 0] sink=96821748
```

Raw output, `develop`:

```
FACADE_OLD_DEVELOP outputRate=44100.0 median_ns_per_output_sample=141.5 cpu_pct_one_core=0.62 runs=[133.83839002267572, 135.42979591836735, 137.81961451247165, 140.51172335600907, 150.03385487528345, 167.176485260771, 180.15897959183673, 141.51628117913833, 151.57287981859412, 123.50195011337868] allocBytesPerRun=[0, 0, 0, 0, 0, 0, 0, 0, 0, 0] sink=9651490
```

### Whole audio frame (`TestSmpsFadeAudioThroughput`)

Existing `performance-measurement` harness: SmpsDriver -> SmpsSequencer ->
Ym2612Chip / PsgChip / BlipResampler, EHZ music from the S2 ROM, 1.5 s
untimed pre-roll, then a 4.5 s fade window timed in 1024-frame chunks, median
of 5 iterations. Run in both trees with

```
mvn -Dmse=off -B "-Dtest=TestSmpsFadeAudioThroughput" \
    "-Dsonic1.rom.path=/abs/s1.gen" "-Dsonic2.rom.path=/abs/s2.gen" "-Ds3k.rom.path=/abs/s3k.gen" test
```

(the clone additionally needed `SONIC_ROM_PATH` because `ensureRomAvailable`
reads the generic `sonic.rom.path` / env / config lookup, not `sonic2.rom.path`.)

```
this branch: FADE_THROUGHPUT median=16.01 unit=renderedSecPerWallSec medianAllocatedBytes=31944 allocatedSupported=true iterations=[15.74,15.97,16.01,16.03,16.11] preRollSec=1.5 fadeWindowFrames=198656 bufferFrames=1024 sampleRate=44100
develop:     FADE_THROUGHPUT median=153.18 unit=renderedSecPerWallSec medianAllocatedBytes=31944 allocatedSupported=true iterations=[129.65,151.65,153.18,154.41,154.96] preRollSec=1.5 fadeWindowFrames=198656 bufferFrames=1024 sampleRate=44100
```

## Headroom and follow-ups

* 6.6 % of one core at 44.1 kHz leaves ~1.5x headroom to the 10 % gate; the
  audio thread is already separate from the game loop, so the 1.1 ms/frame is
  not on the render path.
* The bare-core figure (47 ns per `clock`) is the floor for any further win
  without touching the cycle model; the facade overhead (~8 %) is small. If a
  future round needs more, the candidates are in the facade (resampler and
  frame mixing), not in the port, whose structure must stay traceable to
  `ym3438.c`.
* Not measured this round: a low-end target (the 9950X is a fast desktop core).
  Scaling the 6.6 % by a 3-4x slower core still lands under 30 %, but a real
  measurement on the slowest supported machine belongs in a later round before
  release sign-off.

## Optimisation pass

**Date:** 2026-08-29 (same day, second session)
**Branch / commit:** `feature/ai-nuked-opn2-perf` (worktree
`.worktrees/nuked-opn2-perf`, branched from `feature/ai-nuked-opn2-fm-core` at
`d2f775bcc`); one commit per step, listed below.
**Machine / JVM:** unchanged (Ryzen 9 9950X, OpenJDK 21.0.11, default JIT and
GC, no JVM flags). Same `NukedPerfBench` (appendix; the only edit is that
`RUNS` now reads `-Druns`, default 10), compiled against the worktree's
`target/classes`, 3 warm-up + 10 timed runs, median.

### Result

| Measurement | Before (`d2f775bcc`) | After | Gain |
|---|---|---|---|
| Bare core, ns per 24-cycle frame (`nuked` mode alone) | 1 159 | **885** | 1.31x |
| Bare core, ns per clock | 48.3 | **36.9** | |
| Bare core, % of one core at 53 267 Hz | 6.18 % | **4.71 %** | |
| Facade, ns per 44.1 kHz sample (`facade` mode alone) | 1 470 | **1 138** | 1.29x |
| Facade, % of one core at 44.1 kHz | 6.48 % | **5.02 %** | |
| Bare / facade in one JVM (`all` mode, as round 1 reported) | 1 159 / 1 470 | 898 / 1 172 | |
| Allocation per timed run, both benches | 0 B | **0 B** | |
| `TestSmpsFadeAudioThroughput` | 16.01x realtime | **20.32x realtime = 4.92 %** | |

The 2x target was **not** reached, and the calibration below says why: the
pinned `ym3438.c` compiled with `gcc -O2` runs the identical patch at
**530 ns per frame** (22 ns per clock; the two builds produce the same
`sink` checksum), so 2x from 1 159 ns would have meant matching native C.
The port now sits at 1.67x the C time, down from 2.19x. Every step is
bit-exact: the gate (`TestNukedOpn2BitExactScripts` 732 scripts,
`TestYm2612ChipNukedParity` 68, `TestYm2612HardwareBehaviour`,
`TestNukedOpn2PortSmoke`, `TestNukedOpn2StateEquality`,
`TestYm2612ChipSnapshot`, `TestVirtualSynthesizerSnapshot`,
`TestChipWriteObserver`; 888 tests) was run after every step and never
regenerated. The C harnesses under `tools/audio/nuked-opn2/harness/` are
untouched.

### Steps (each its own commit, each gated)

| Step | Commit | Change | ns/frame | Delta |
|---|---|---|---|---|
| 0 | `d2f775bcc` | baseline, re-measured | 1 159 | |
| 1 | `7c1f6b178` | `(cycles + k) % 24` -> `wrap24` conditional subtract (operand is 0..47); `cycles % 12`, `(channel + 1) % 6` likewise | 1 105 | -4.7 % |
| 2 | `817e81249` | `FM_ALGORITHM[op][k][connect]` packed as bits of `FM_ALGORITHM_BITS[op*8+connect]`; `PG_LFO_SH1/2` and `EG_STEPHI` flattened; all derived from the C tables in the static initialiser | 1 000 | -9.5 % |
| 3 | `c9c37bb74` | JIT inlining: local copy of the `chip` reference in every per-cycle method (bytecode -3 bytes per access); `doRegWrite` split into per-cycle guards + slot / channel / mode decode helpers | 940 | -6.0 % |
| 4 | `97cc5a077` | per-slot fields that a function reads several times but never writes are loaded once (`ssg_eg`, `eg_state`, `eg_ssg_enable`, `eg_kon`, `fb`, `fm_out[prevslot]`); `+=`/`&=` pairs on `pg_phase`, `pg_inc` become one store; `SLOT_OP` / `SLOT_CHANNEL` tables for `slot / 6`, `cycles % 6` | 885 | -5.9 % |

Cumulative: 1 159 -> 885 ns, **-23.6 %**.

### Profile

* **JFR** (`jdk.ExecutionSample`) attributes ~46 % of samples to `clock()`
  itself (everything C2 inlined) and the rest to the six callees C2 refused to
  inline; it cannot see inside the inlined body.
* **`-XX:+PrintInlining`** on the baseline: `doRegWrite` (1 792 bytes),
  `envelopeAdsr` (681), `envelopePrepare` (532), `chOutput` (399),
  `fmPrepare` (358) and `phaseCalcIncrement` (344) are all "hot method too big"
  (C2 `FreqInlineSize` = 325). Setting `-XX:FreqInlineSize=2500` on the
  baseline was worth ~9 %, almost all of it `doRegWrite` (the per-cycle
  guards were paying a full call every cycle). After step 3 the remaining
  three oversize callees are `envelopeAdsr` (579), `envelopePrepare` (433)
  and `phaseCalcIncrement` (330); forcing them in (`FreqInlineSize=700`)
  measured *worse* (972 vs 940), and so did every smaller threshold
  (250: 956, 200: 982, 150: 1 089, 100: 1 073), so the default is the sweet
  spot and no further splitting was done.
* **`-XX:+TrustFinalNonStaticFields`**: no gain (1 166 vs 1 159), so the
  51 `final int[]` references are not the cost; the bounds checks and reloads
  behind them are.
* **PC sampling** (no `perf` on this machine, JDK has no `hsdis`): the
  bench was run as a child of `gdb`, interrupted 1 500 times at 20 ms, and the
  PCs binned against a `disassemble` of the live C2 `clock()` nmethod
  (13.5 KB of code). 1 033 samples fell in the nmethod. The histogram is
  **flat** - the hottest 256-byte bucket (the `fmGenerate` LOGSIN/EXP chain)
  holds 12 %, `fmPrepare` about 17 %, and no instruction exceeds 3 %. The
  listing shows the systemic cost instead: 93 `vmovd` GPR<->XMM spills and
  873 stack-slot references in 2 863 instructions (register pressure from the
  single inlined body), an `arr.length` load plus compare before nearly every
  per-slot access, and int[] loads re-issued after every int[] store because
  C2 cannot prove two state arrays are distinct objects. Those are the shape of
  a struct-of-arrays port on a JIT that does not trust final fields, not a
  handful of slow lines, which is why the remaining steps were all shape
  changes rather than local fixes.
* **Ablation** (removing one pipeline stage at a time in a scratch copy) was
  tried and rejected as a cost breakdown: the savings sum to far more than the
  total and removing the trivial `doIo` "saved" 210 ns, i.e. it measures JIT
  compilation-shape effects, not stage cost.

### Tried and reverted

* **Polling output availability once per frame** in
  `Ym2612Chip.renderStereo` (`hasOutputSample()` can only change when
  `cycles` wraps to 0, so a `clockFrame()` inner loop stops at the same
  cycle): 1 160-1 170 ns/sample against 1 134-1 142 for the plain per-cycle
  loop, two runs each, i.e. ~2 % slower from the extra loop level. Reverted;
  the facade is left as it was, apart from the core it wraps.
* **More inlining** (see profile): worse at every threshold above or below the
  default.

### Facade

`Ym2612Chip` was not changed. Its overhead over the bare core is now
1 138 ns/sample / (53 267 / 44 100) = 942 ns per native frame against 885 bare,
i.e. ~6 % for DAC servicing, mute lookup, frame summation and the 16-tap
stereo FIR; the write-pacing logic was deliberately left alone because a
concurrent branch is changing it.

### Verification

Per-step gate: see the step table (888/0 after every step; the 732-script
pin took 46-52 s per run). Final runs on `97cc5a077` (all three ROM paths passed as absolute `-D` properties, `-Dmse=off`):

```
# Gate (run after every step; this is the step-4 run)
mvn -Dmse=off -B "-Dtest=TestNukedOpn2BitExactScripts,TestNukedOpn2PortSmoke,TestYm2612ChipNukedParity,TestYm2612HardwareBehaviour,TestNukedOpn2StateEquality,TestYm2612ChipSnapshot,TestVirtualSynthesizerSnapshot,TestChipWriteObserver" test
  Tests run: 888, Failures: 0, Errors: 0, Skipped: 0   (732 bit-exact scripts in 46 s)

# Whole audio tree
mvn -Dmse=off -B "-Dtest=com/openggf/audio/**/*" test
  Tests run: 1651, Failures: 0, Errors: 0, Skipped: 8   (120 classes; the 8 skips are
  AudioRegressionTest's opt-in reference captures and TestSmpsRepeatedPlaybackBenchmark, as before)

# Structural guards
mvn -Dmse=off -Pguards -B test
  Tests run: 550, Failures: 0, Errors: 0, Skipped: 0

# Whole-stack fade window
mvn -Dmse=off -B "-Dtest=TestSmpsFadeAudioThroughput" test
  FADE_THROUGHPUT median=20.32 unit=renderedSecPerWallSec medianAllocatedBytes=31944 allocatedSupported=true iterations=[19.61,20.32,20.31,20.35,20.37] preRollSec=1.5 fadeWindowFrames=198656 bufferFrames=1024 sampleRate=44100
  (round 1 on the same tree: 16.01; the 31 944 B/window residual is the SMPS driver's, unchanged)

# Micro-bench, final (this worktree, target/classes of 97cc5a077)
java -cp bench:target/classes perf.NukedPerfBench nuked
  NUKED_NATIVE median_ns_per_sample=885.7 cpu_pct_one_core_at_53267Hz=4.71 allocBytesPerRun=[0 x10]
java -cp bench:target/classes perf.NukedPerfBench facade
  FACADE outputRate=44100.0 median_ns_per_output_sample=1138.3 cpu_pct_one_core=5.02 allocBytesPerRun=[0 x10]
java -cp bench:target/classes perf.NukedPerfBench all
  NUKED_NATIVE median_ns_per_sample=898.3 ... FACADE median_ns_per_output_sample=1172.4 allocBytesPerRun=[0 x10] (both)
```

### Calibration: the C build

```c
/* cbench.c: the NukedPerfBench patch and loop against the pinned ym3438.c */
OPN2_Reset(&chip); OPN2_SetChipType(ym3438_mode_ym2612);
/* ... identical register programme via OPN2_Write + 24 OPN2_Clock per strobe ... */
for (f = 0; f < 53267; f++) { for (c = 0; c < 24; c++) { OPN2_Clock(&chip, b); l += b[0]; r += b[1]; } sink += labs(l) + labs(r); }
```

`gcc -O2` and `gcc -O3 -march=native` both: **530 ns per frame**, `sink`
96821748 = the Java bench's `sink` for the same run count, so the two are
clocking the same state.

## Appendix: micro-bench source

```java
package perf;

import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.audio.synth.nuked.NukedOpn2;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Micro-bench: NukedOpn2 at native rate, Ym2612Chip facade at engine output rate. */
public final class NukedPerfBench {
    static final double NATIVE_RATE = 53267.0;
    static final int RUNS = Integer.getInteger("runs", 10);
    static final ThreadMXBean MX = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    // Six-channel patch: algorithm 4, two-carrier voice with TL/AR/DR/RR set.
    static void program(java.util.function.BiConsumer<Integer, int[]> w) {
        w.accept(0, new int[]{0x22, 0x08});           // LFO on, freq 0
        w.accept(0, new int[]{0x27, 0x00});
        w.accept(0, new int[]{0x2B, 0x00});
        for (int part = 0; part < 2; part++) {
            for (int ch = 0; ch < 3; ch++) {
                for (int op = 0; op < 4; op++) {
                    int o = ch + (op * 4);
                    w.accept(part, new int[]{0x30 + o, 0x71 + op});   // DT/MUL
                    w.accept(part, new int[]{0x40 + o, op < 2 ? 0x23 : 0x10}); // TL
                    w.accept(part, new int[]{0x50 + o, 0x5F});        // RS/AR
                    w.accept(part, new int[]{0x60 + o, 0x0A + op});   // AM/D1R
                    w.accept(part, new int[]{0x70 + o, 0x05});        // D2R
                    w.accept(part, new int[]{0x80 + o, 0x2A});        // D1L/RR
                    w.accept(part, new int[]{0x90 + o, 0x00});
                }
                w.accept(part, new int[]{0xB0 + ch, 0x34});           // FB/ALG
                w.accept(part, new int[]{0xB4 + ch, 0xF3});           // L/R + AMS/PMS
                w.accept(part, new int[]{0xA4 + ch, 0x22 + ch});      // block/fnum hi
                w.accept(part, new int[]{0xA0 + ch, 0x69 + ch * 7});
            }
        }
        for (int ch = 0; ch < 6; ch++) {
            w.accept(0, new int[]{0x28, 0xF0 | (ch < 3 ? ch : ch + 1)}); // key on
        }
    }

    static double median(double[] v) { double[] c = v.clone(); Arrays.sort(c); return c[c.length / 2]; }

    static void nukedNative() {
        NukedOpn2 chip = new NukedOpn2();
        chip.setChipType(NukedOpn2.MODE_YM2612);
        int[] pins = new int[2];
        program((port, rv) -> {
            chip.write(port * 2, rv[0]);
            for (int i = 0; i < NukedOpn2.CYCLES_PER_FRAME; i++) chip.clock(pins);
            chip.write(port * 2 + 1, rv[1]);
            for (int i = 0; i < NukedOpn2.CYCLES_PER_FRAME; i++) chip.clock(pins);
        });
        int frames = (int) NATIVE_RATE;
        long sink = 0;
        double[] nsPerSample = new double[RUNS];
        long[] alloc = new long[RUNS];
        for (int run = -3; run < RUNS; run++) { // 3 warm-up runs
            long a0 = MX.getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            for (int f = 0; f < frames; f++) {
                int l = 0, r = 0;
                for (int c = 0; c < NukedOpn2.CYCLES_PER_FRAME; c++) { chip.clock(pins); l += pins[0]; r += pins[1]; }
                sink += Math.abs(l) + Math.abs(r);
            }
            long t1 = System.nanoTime();
            long a1 = MX.getCurrentThreadAllocatedBytes();
            if (run >= 0) { nsPerSample[run] = (t1 - t0) / (double) frames; alloc[run] = a1 - a0; }
        }
        double med = median(nsPerSample);
        System.out.printf(Locale.ROOT, "NUKED_NATIVE median_ns_per_sample=%.1f cpu_pct_one_core_at_53267Hz=%.2f runs=%s allocBytesPerRun=%s sink=%d%n",
                med, med * NATIVE_RATE / 1e9 * 100.0, Arrays.toString(nsPerSample), Arrays.toString(alloc), sink);
    }

    static void facade(String label) {
        Ym2612Chip chip = new Ym2612Chip();
        double rate = chip.getOutputSampleRate();
        program((port, rv) -> chip.write(port, rv[0], rv[1]));
        int frames = (int) rate;
        int chunk = 1024;
        int[] left = new int[chunk], right = new int[chunk];
        long sink = 0;
        double[] nsPerSample = new double[RUNS];
        long[] alloc = new long[RUNS];
        for (int run = -3; run < RUNS; run++) {
            long a0 = MX.getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            int done = 0;
            while (done < frames) {
                int n = Math.min(chunk, frames - done);
                // Keep the sequencer-shaped write traffic alive: one retrigger per chunk.
                chip.write(0, 0x28, 0xF0 | (done & 2));
                chip.renderStereo(left, right, n);
                sink += left[0] ^ right[n - 1];
                done += n;
            }
            long t1 = System.nanoTime();
            long a1 = MX.getCurrentThreadAllocatedBytes();
            if (run >= 0) { nsPerSample[run] = (t1 - t0) / (double) frames; alloc[run] = a1 - a0; }
        }
        double med = median(nsPerSample);
        System.out.printf(Locale.ROOT, "%s outputRate=%.1f median_ns_per_output_sample=%.1f cpu_pct_one_core=%.2f runs=%s allocBytesPerRun=%s sink=%d%n",
                label, rate, med, med * rate / 1e9 * 100.0, Arrays.toString(nsPerSample), Arrays.toString(alloc), sink);
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "all";
        if (mode.equals("all") || mode.equals("nuked")) nukedNative();
        if (mode.equals("all") || mode.equals("facade")) facade(args.length > 1 ? args[1] : "FACADE");
    }
}
```
