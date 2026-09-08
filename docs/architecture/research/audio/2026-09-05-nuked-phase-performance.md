# Java Nuked phase arithmetic: small measured gain

Date: 2026-09-05. Integration baseline:
`53db2da7026fd9d94d95881788fc0b618d22202c` (`develop`).
Development branch: `feature/ai-nuked-phase-perf`.

## Decision and limits

Retain the combination of a 256-byte signed-detune table and a PMS=0
arithmetic bypass. Java Nuked remains the sole production FM core; no native
dependency, old-core import, clock skipping, or fidelity mode is introduced.
This is a modest local audio-rendering improvement, not a whole-game FPS claim.
GraalVM native-image, lower-end CPUs, and other music remain unmeasured.

The table evaluates exactly the pinned `ym3438.c` function
`OPN2_PhaseCalcIncrement`: clamp keycode to 28, calculate magnitude, apply DT's
sign. The original calculation is retained as `calculateSignedDetune`, with
its named steps and branches; Java class initialization calls it once for
each table entry. Its input domain is 8 DT values by 32 keycodes. At PMS=0 both reference
LFO shifts are 7; the high seven bits of the hardware's 11-bit frequency
therefore contribute zero. Only that arithmetic is bypassed. LFO updates,
all chip clocks, register writes, masks, phase increments and snapshots retain
their original ownership and timing. No mutable state fields were added.

The two changes together performed better than either alone. This is consistent
with reducing the hot method's arithmetic and changing its compilation shape,
but these timings do not identify the responsible machine-code transformation.
Do not attribute the gain to confirmed JIT inlining without new compiler evidence.

## Measurement

Ryzen 9 9950X, Linux x86-64, OpenJDK 21.0.11, CPU affinity 6, normal boost
enabled. This was a desktop, not a reserved benchmark host. Each arm used four
fresh JVMs per round, reversing arm order between adjacent forks. Benchmark
phases were sequential and completed before the Maven verification runs.

Numbers below are medians of four per-JVM medians, in ns per stereo frame;
lower is better. The percent is time reduction, not throughput increase.

| Workload / round | Baseline | Combined phase change | Less time |
|---|---:|---:|---:|
| AIZ1 initial | 1297.03 | 1268.62 | 2.19% |
| AIZ1 confirmation | 1352.44 | 1315.85 | 2.71% |
| Active-PMS synthetic confirmation | 893.35 | 874.95 | 2.06% |

AIZ1 won 6 of 8 paired forks; synthetic confirmation won 4 of 4. One
confirmation AIZ pair was a near tie in the other direction. Absolute times
moved between rounds, so the defensible conclusion is a small promising gain,
not a precise platform-wide guarantee or a formal significance claim.

Per-fork medians, retained to expose dispersion:

| Round | Baseline forks 1–4 | Combined forks 1–4 |
|---|---|---|
| AIZ1 initial | 1293.0035, 1333.1337, 1298.4551, 1295.6037 | 1309.7830, 1263.2918, 1273.6026, 1263.6402 |
| AIZ1 confirmation | 1352.5682, 1359.3512, 1313.8425, 1352.3059 | 1328.3142, 1315.5111, 1316.1844, 1280.6930 |
| Synthetic confirmation | 896.5445, 902.5321, 879.5012, 890.1609 | 873.6340, 870.9511, 876.8291, 876.2615 |

Synthetic: maintained `tools/audio/fm-core-benchmark/JavaNukedBenchmark.java`,
262,144 native stereo frames, eight warmups and nine measurements per JVM,
six sustained FM voices with active PMS=3. Every process's final validation
reported checksum `1646411168050564101`, zero snapshot errors, and 96 changed
samples under the negative control. This harness validates a separate render,
not the return value of each timed iteration.

AIZ1: S3K music ID 1, FM/PSG/DAC, 44,100 Hz, 256-frame reads, ten seconds per
render; four warmups and seven measurements per JVM. Every render, including
warmups, asserted PCM FNV `16718153805703898014`. This uses the direct/tool
stream path, not the live presentation or gameplay path. ROM SHA-1:
`cfbf98c36c776677290a872547ac47c53d2761d6`.

Candidate classes override only Nuked on the classpath. The shared dependency
JAR has unchanged audio sources since `c9f472a4a`; the JAR used for this round
has SHA-256 `b618e747571ff7958e695cf22381e36c0910ba907c749b2dce544d9f14e646fe`.
The older JIT investigation's JAR hash is not this artifact's identity.

Raw logs, all prototype sources/classes, checker source, `AizEnvelopeBenchmark`
and `AudioSpeedProbe` are archived locally outside the repository at
`<research-root>/nuked-next-gains-20260905/evidence/`.
The portable maintained synthetic harness and C matrix generator remain in
the repository; the external archive is a local research artifact, not a CI
dependency. Prototype phase source SHA-256:
`0c953426367dff516ee7ddb495aa10cd71f6de7e7f3978912d1636a90a268d5f`;
table source: `c6118dca69405fce38efe01acdbeec06db20a21bacdf6abb3e8883d3c6557b2e`.
`javap -p -c` output for the production core matches the measured prototype
exactly. Table initialization is intentionally written more literally than
the prototype: it calls the retained original detune calculation rather than
the spike's compact equivalent expression. This runs once, outside the hot
path; the independent C matrix verifies the generated behavior.

### Other candidates not retained

- Deferred SSG latch stores: about 2.4% synthetic, only 1.2% AIZ1 in the first
  sweep, with inconsistent pairs; not enough evidence to bundle it.
- 8,192-entry exponential-output table: about 1.4% synthetic and 0.7% AIZ1;
  not retained for a weak gain and additional table footprint.
- Signed detune alone: roughly 0.7% synthetic, close to noise.
- Zero-PMS bypass alone: AIZ1 1314.77 vs baseline 1297.03, about 1.4% slower.
- Earlier forced JIT inlining and envelope-increment lookup work remains
  rejected; this change does not add JVM compiler directives.

All six prototype arms passed all 732 existing pinned-C cycle scripts,
checking cycle counts, ordered PCM, status/IRQ and state-dump side-log hashes.
That is bounded script evidence, not exhaustive equality of every chip state.

## Correctness and integration verification

`TestNukedOpn2PhaseMatrix` has independent literal fingerprints generated by
calling the actual pinned C function, not a second Java rendition of the
optimization. It covers 65,536 zero-PMS frequency/LFO inputs, 256 detune/keycode
inputs, and 1,032,192 active-PMS boundary combinations. The generator and
regeneration instructions are in `tools/audio/nuked-opn2/harness/`.
Pinned C and header hashes were verified against `tools/audio/nuked-opn2/PIN.md`.

The three matrix tests passed against the unchanged core. Temporarily reversing
negative detune made all three fail (three assertion failures, zero errors);
the mutation was removed when implementing the correct signed table. Independent
review found no arithmetic defect and prompted extending the matrix through
the internal MULTI maximum of 30 (the register value is doubled). The original
mutation check preceded that additional coverage.

Full verification uses JDK 21, `mvn -Dmse=off -B test` and a separate
`mvn -Dmse=off -B -Pguards test`, both with absolute ROM properties
`-Dsonic1.rom.path=<absolute-main-workspace>/s1.gen`,
`-Dsonic2.rom.path=<absolute-main-workspace>/s2.gen`, and
`-Ds3k.rom.path=<absolute-main-workspace>/s3k.gen` (local prefixes redacted).
Report directories are preserved per invocation to avoid stale XML counts.
Surefire console totals and XML testcase totals differ because nested-suite
reporting repeats some summaries. Parent/child XML also duplicates testcase
rows with run-dependent multiplicity. Comparisons deduplicate exact
`(classname, name, status)` triples, preserving any failing status even if a
passing duplicate exists, and check the console build result as well.

Focused command (all selected classes are ROM-free):

```bash
mvn -Dmse=off -B -Dtest=TestNukedOpn2PhaseMatrix,TestNukedOpn2BitExactScripts,TestNukedOpn2PortSmoke,TestNukedOpn2StateEquality,TestYm2612ChipNukedParity,TestYm2612ChipSnapshot test
```

- Baseline ordinary suite: 16,541 tests, zero failures/errors, 43 skips.
- Baseline separate guards: 609 tests, zero failures/errors/skips.
- Initial focused run: 813 tests, zero failures/errors/skips (before expanding
  the matrix's input domain; the number of JUnit cases stays the same).
- Initial full development: 16,544 tests, zero failures/errors, 43 skips;
  all 15,595 distinct baseline identity/status triples are retained, adding
  only the three new passing matrix tests. Raw duplicate-row counts differed
  (890 baseline, 915 development) due to nested reporting, not lost tests.
  This run preceded making the one-time table builder a literal retained calculation.
- Final development ordinary suite: 16,544 tests, zero failures/errors,
  43 skips; again all baseline identity/status triples retained with only the
  three new passing tests. A subsequent comment-only wording edit does not
  change executable behavior. Independent review also accepted the retained
  calculation and expanded matrix.
- Final development separate guards: 609 tests, zero failures/errors/skips;
  identical baseline identity/status coverage.
- Final focused rerun: 813 tests, zero failures/errors/skips.
- Post-merge ordinary suite at `0c4afd689`: 16,544 tests, zero failures/errors,
  43 skips. Every baseline identity/status triple retained; only the three
  new passing matrix tests added.
- Post-merge separate guards: 609 tests, zero failures/errors/skips; identical
  baseline identity/status coverage. No new regression in either suite.

Implementation commit `8dfe31306` merged into `develop` as `0c4afd689` without
conflicts or intervening upstream changes. Existing main-workspace submodule
changes were preserved. The integration record also changes the release note
from "CPU time" to "rendering time" to describe the wall-clock measurement precisely.

Main and worktree verification logs/XML were archived under
`<research-root>/nuked-next-gains-20260905/verification-main/` and
`verification-development/`; the testcase comparison script is retained in
the sibling `evidence/` directory. Release wording and this final record are
documentation-only follow-ups to the verified merge.
