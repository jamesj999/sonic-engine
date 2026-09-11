# Audio Memory Benchmark Step Results 2026-04-17

## Baseline Reference
- Audio render median: 9.04275
- Audio render alloc median: 0.23711395263671875
- Audio render peak heap median: 15.832378387451172
- Results tally median: 28.47635
- Results tally alloc median: 0.8379135131835938
- Results tally peak heap median: 21.80840301513672

## Step Template

### Step N - Name
- Files changed:
- Regression gate:
- Benchmark runs:
- Audio median before:
- Audio median after:
- Audio delta:
- Audio alloc median before:
- Audio alloc median after:
- Audio alloc delta:
- Audio peak heap median before:
- Audio peak heap median after:
- Audio peak heap delta:
- Tally median before:
- Tally median after:
- Tally delta:
- Tally alloc median before:
- Tally alloc median after:
- Tally alloc delta:
- Tally peak heap median before:
- Tally peak heap median after:
- Tally peak heap delta:
- Notes:
- Decision:

## Step 1 - BlipResampler History Right-Sizing
- Files changed:
  - `src/main/java/com/openggf/audio/synth/BlipResampler.java`
  - `src/test/java/com/openggf/tests/TestBlipResampler.java`
- Regression gate:
  - Targeted RED check in isolated worktree: FAIL as expected against `HEAD` because the history arrays were still `16384`
  - Narrow gate (`TestBlipResampler,TestYm2612ChipBasics`): PASS
  - Full gate: PASS
- Benchmark runs:
  - One 10-run benchmark set completed successfully
- Audio median before: `9.04275 ms/sec`
- Audio median after: `9.25270 ms/sec`
- Audio delta: `+0.20995 ms/sec`
- Audio alloc median before: `0.23711395263671875 MB`
- Audio alloc median after: `0.23711395263671875 MB`
- Audio alloc delta: `+0.00000000000000000 MB`
- Audio peak heap median before: `15.832378387451172 MB`
- Audio peak heap median after: `15.399749755859375 MB`
- Audio peak heap delta: `-0.43262863159179688 MB`
- Tally median before: `28.47635 ms/run`
- Tally median after: `27.32550 ms/run`
- Tally delta: `-1.15085 ms/run`
- Tally alloc median before: `0.8379135131835938 MB`
- Tally alloc median after: `0.84922027587890625 MB`
- Tally alloc delta: `+0.01130676269531250 MB`
- Tally peak heap median before: `21.80840301513672 MB`
- Tally peak heap median after: `21.360443115234375 MB`
- Tally peak heap delta: `-0.44795989990234375 MB`
- Notes:
  - Added a structural resampler test that enforces the smaller retained-history target and verified that it fails against pre-change `HEAD` in an isolated worktree.
  - Reduced the retained stereo history from `16384` to `8192` samples without changing filter taps, sinc tables, or interpolation arithmetic.
  - Benchmark medians show lower peak heap in both benchmark families and a materially faster tally-stress run, with the main audio timing median slightly slower but still within the existing run-to-run noise band seen in prior benchmark work.
- Decision:
  - Keep the reduced-history production change.
  - Keep the new structural characterization test.
