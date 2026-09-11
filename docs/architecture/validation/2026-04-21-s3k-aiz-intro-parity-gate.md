# S3K AIZ Intro Parity Gate Validation

Captured on `2026-04-21` in worktree `feature/ai-s2-s3k-trace-recorder-v3`.

## Replay Command

```powershell
mvn --% -Dmse=off -Dtest=com.openggf.tests.trace.s3k.TestS3kReplayCheckpointDetector,com.openggf.tests.trace.s3k.TestS3kElasticWindowController,com.openggf.tests.trace.s3k.TestS3kRequiredCheckpointGuard,com.openggf.tests.trace.s3k.TestS3kAizTraceReplay -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

## Observed Output

- Surefire report: `target/surefire-reports/TEST-com.openggf.tests.trace.s3k.TestS3kAizTraceReplay.xml`
- Trace JSON report: `missing` (`target/trace-reports/s3k_aiz1_report.json`)
- Trace context report: `missing` (`target/trace-reports/s3k_aiz1_context.txt`)
- First notable replay line: `<error message="Required engine checkpoint missing at trace frame 1651: expected aiz1_fire_transition_begin" type="java.lang.IllegalStateException">`
- `Elastic window drift budget exhausted for gameplay_start` still present: `False`

## Interpretation

- The old `gameplay_start` drift-budget failure is gone, so the harness-alignment gate succeeded.
- The replay is still red; the new earliest failing region is `aiz1_fire_transition_begin` at trace frame `1651`.
- Stop at the gate here and do not start engine intro fixes in this task.
