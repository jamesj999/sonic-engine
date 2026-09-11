# Audio Top Wins Baseline 2026-04-17

## Preconditions

- Regression gate status: PASS
- Audio reference assets present under `src/test/resources/audio-reference`
- Sonic 2 ROM present in repo root

## Regression Gate

Command:

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest,TestPsgChipGpgxParity,TestYm2612ChipBasics,TestYm2612Attack,TestYm2612InstrumentTone,TestYm2612SsgEg,TestYm2612TimerCSM,TestYm2612VoiceLengths,TestYm2612AlgorithmRouting,TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries,TestRomAudioIntegration,TestTitleScreenAudioRegression,TestSonic2PsgEnvelopesAgainstRom" test
```

Result:

- Build success
- Tests run: `59`
- Failures: `0`
- Errors: `0`
- Skipped: `0`

## Benchmark Command

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkAudioRendering+benchmarkResultsTallyStressRendering" test
```

## Raw Runs

| Run | Audio ms/sec | Audio realtime x | Tally ms/run | Tally realtime x |
| --- | ---: | ---: | ---: | ---: |
| 1 | 9.9009 | 101.00091910836389 | 28.5387 | 122.64048467519544 |
| 2 | 9.9817 | 100.18333550397227 | 33.2577 | 105.23878680726568 |
| 3 | 9.0180 | 110.88933244621866 | 28.1508 | 124.33039203148756 |
| 4 | 19.8278 | 50.434238796033853 | 29.2038 | 119.84741711695052 |
| 5 | 8.9993 | 111.11975375862568 | 29.3706 | 119.16678583345251 |
| 6 | 8.5326 | 117.19757166631507 | 26.2642 | 133.26124534537507 |
| 7 | 8.6518 | 115.58288448646525 | 28.1381 | 124.38650797317516 |
| 8 | 7.9954 | 125.07191635190235 | 27.7682 | 126.04345978493384 |
| 9 | 10.2521 | 97.54099160172062 | 27.7186 | 126.2690034850245 |
| 10 | 9.2930 | 107.60787689658883 | 27.3194 | 128.11408742505324 |

## Summary

### Audio Render Benchmark

- Median: `9.1555 ms/sec`
- Mean: `10.24526 ms/sec`
- Min: `7.9954 ms/sec`
- Max: `19.8278 ms/sec`
- Stddev: `3.2636289452693608 ms/sec`

### Results Tally Stress Benchmark

- Median: `28.14445 ms/run`
- Mean: `28.57301 ms/run`
- Min: `26.2642 ms/run`
- Max: `33.2577 ms/run`
- Stddev: `1.7784495671511185 ms/run`

## Notes

- Audio render benchmark shows one major outlier on run 4 (`19.8278 ms/sec`).
- Results tally stress is materially tighter than the main audio render benchmark.
- Use median as the primary comparison metric for all subsequent optimization steps.
