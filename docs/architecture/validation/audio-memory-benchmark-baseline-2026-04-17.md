# Audio Memory Benchmark Baseline

Prerequisites verified:
- `src/test/resources/audio-reference` contains the expected audio reference WAVs.
- Repo root contains Sonic 2 ROM images.

Regression gate:
- `mvn -Dmse=off "-Dtest=AudioRegressionTest,TestPsgChipGpgxParity,TestYm2612ChipBasics,TestYm2612Attack,TestYm2612InstrumentTone,TestYm2612SsgEg,TestYm2612TimerCSM,TestYm2612VoiceLengths,TestYm2612AlgorithmRouting,TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries,TestRomAudioIntegration,TestTitleScreenAudioRegression,TestSonic2PsgEnvelopesAgainstRom,TestBlipResampler,TestVirtualSynthesizerMix" test`
- Result: PASS (`Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`)

Benchmark collection:
- `mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkAudioRendering+benchmarkResultsTallyStressRendering" test`
- Ran 10 times and parsed all runs successfully.

| Run | Audio ms/sec | Audio x realtime | Audio alloc MB | Audio peak heap MB | Tally ms/run | Tally x realtime | Tally alloc MB | Tally peak heap MB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 12.7758 | 78.27298486200472 | 0.23711395263671875 | 15.368461608886719 | 28.8692 | 121.23647347345961 | 0.8265609741210938 | 21.86846160888672 |
| 2 | 7.9395 | 125.95251590150514 | 0.23711395263671875 | 15.800201416015625 | 25.7357 | 135.9978551195421 | 0.8491439819335938 | 21.800201416015625 |
| 3 | 10.1494 | 98.52799180247108 | 0.23711395263671875 | 15.99658203125 | 28.0835 | 124.62834048462621 | 0.8040695190429688 | 21.49658203125 |
| 4 | 7.4584 | 134.07701383674782 | 0.23711395263671875 | 15.832015991210938 | 30.0502 | 116.47177057057857 | 0.7814559936523438 | 21.832015991210938 |
| 5 | 11.1641 | 89.57282718714451 | 0.23711395263671875 | 15.857925415039062 | 25.7944 | 135.68836646714016 | 0.8945236206054688 | 21.857925415039062 |
| 6 | 8.3787 | 119.35025719980426 | 0.23711395263671875 | 15.647659301757812 | 39.9686 | 87.5687414620477 | 0.8040390014648438 | 21.147659301757812 |
| 7 | 12.7888 | 78.19341924183661 | 0.23711395263671875 | 15.901512145996094 | 31.3464 | 111.65556491335528 | 0.8266830444335938 | 21.901512145996094 |
| 8 | 8.433 | 118.58176212498518 | 0.23711395263671875 | 16.078887939453125 | 27.7538 | 126.10885716550527 | 0.8945236206054688 | 21.578887939453125 |
| 9 | 9.0789 | 110.14550220841731 | 0.23711395263671875 | 15.816604614257812 | 29.8446 | 117.27414674681518 | 0.8944625854492188 | 21.816604614257812 |
| 10 | 9.0066 | 111.02968933892922 | 0.23711395263671875 | 15.832740783691406 | 26.0136 | 134.54500722698896 | 0.8491744995117188 | 21.332740783691406 |

## Summary

- Audio median ms/sec: 9.04275
- Audio mean ms/sec: 9.71732
- Audio min/max ms/sec: 7.4584 / 12.7888
- Audio median alloc MB: 0.23711395263671875
- Audio median peak heap MB: 15.832378387451172
- Tally median ms/run: 28.47635
- Tally mean ms/run: 29.346
- Tally min/max ms/run: 25.7357 / 39.9686
- Tally median alloc MB: 0.8379135131835938
- Tally median peak heap MB: 21.80840301513672
