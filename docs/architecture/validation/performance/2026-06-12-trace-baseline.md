# 2026-06-12 Trace Baseline

Command:

```powershell
mvn "-Dmse=off" "-Dtest=*TraceReplay" "-Ds1.rom.path=s1.gen" "-Ds2.rom.path=s2.gen" "-Ds3k.rom.path=s3k.gen" test
```

Context:

- Branch: `bugfix/ai-release-remediation`
- Worktree: clean before the sweep.
- Result: build failed after the trace replay sweep with `Java heap space`.
- Surefire summary before the fork failure: `Tests run: 89, Failures: 57, Errors: 1, Skipped: 0`.
- Scope note: this is a partial-but-useful baseline for performance work. The OOM means it must not be treated as a clean all-trace pass/fail certification.

| Trace replay class | Status | Errors | Frontier |
|---|---:|---:|---|
| `TestTraceReplayStartPositionPolicy` | PASS | 0 | full trace |
| `s1.TestS1Credits00Ghz1TraceReplay` | PASS | 0 | full trace |
| `s1.TestS1Credits01Mz2TraceReplay` | PASS | 0 | full trace |
| `s1.TestS1Credits02Syz3TraceReplay` | PASS | 0 | full trace |
| `s1.TestS1Credits03Lz3TraceReplay` | PASS | 0 | full trace |
| `s1.TestS1Credits04Slz3TraceReplay` | PASS | 0 | full trace |
| `s1.TestS1Credits05Sbz1TraceReplay` | PASS | 0 | full trace |
| `s1.TestS1Credits06Sbz2TraceReplay` | PASS | 0 | full trace |
| `s1.TestS1Credits07Ghz1bTraceReplay` | PASS | 0 | full trace |
| `s1.TestS1FzCompleteRunTraceReplay` | FAIL | 155 | frame 713 -- y_speed mismatch (expected=0x0000, actual=-0700) |
| `s1.TestS1Ghz1CompleteRunTraceReplay` | FAIL | 689 | frame 527 -- rolling mismatch (expected=1, actual=0) |
| `s1.TestS1Ghz1TraceReplay` | PASS | 0 | full trace |
| `s1.TestS1Ghz2CompleteRunTraceReplay` | FAIL | 237 | frame 2370 -- y mismatch (expected=0x0267, actual=0x0266) |
| `s1.TestS1Ghz3CompleteRunTraceReplay` | FAIL | 1096 | frame 370 -- y_speed mismatch (expected=-0220, actual=-0320) |
| `s1.TestS1Lz1CompleteRunTraceReplay` | FAIL | 3117 | frame 302 -- y_speed mismatch (expected=-0100, actual=0x0000) |
| `s1.TestS1Lz2CompleteRunTraceReplay` | FAIL | 2102 | frame 1089 -- y mismatch (expected=0x03A8, actual=0x03AD) |
| `s1.TestS1Lz3CompleteRunTraceReplay` | FAIL | 3229 | frame 466 -- y mismatch (expected=0x0807, actual=0x0007) |
| `s1.TestS1Mz1CompleteRunTraceReplay` | FAIL | 450 | frame 1260 -- rolling mismatch (expected=0, actual=1) |
| `s1.TestS1Mz1TraceReplay` | FAIL | 222 | frame 3224 -- y_speed mismatch (expected=0x02C8, actual=0x01C8) |
| `s1.TestS1Mz2CompleteRunTraceReplay` | FAIL | 1034 | frame 1295 -- y mismatch (expected=0x0451, actual=0x044C) |
| `s1.TestS1Mz3CompleteRunTraceReplay` | FAIL | 1308 | frame 996 -- rolling mismatch (expected=1, actual=0) |
| `s1.TestS1Sbz1CompleteRunTraceReplay` | FAIL | 960 | frame 1367 -- rolling mismatch (expected=1, actual=0) |
| `s1.TestS1Sbz2CompleteRunTraceReplay` | FAIL | 993 | frame 576 -- y mismatch (expected=0x0763, actual=0x075C) |
| `s1.TestS1Sbz3CompleteRunTraceReplay` | FAIL | 4686 | frame 1421 -- camera_y mismatch (expected=0x038C, actual=0x0388) |
| `s1.TestS1Slz1CompleteRunTraceReplay` | FAIL | 788 | frame 672 -- y mismatch (expected=0x01D1, actual=0x01CC) |
| `s1.TestS1Slz2CompleteRunTraceReplay` | FAIL | 270 | frame 651 -- g_speed mismatch (expected=0x1000, actual=0x10AE) |
| `s1.TestS1Slz3CompleteRunTraceReplay` | FAIL | 1550 | frame 718 -- y_speed mismatch (expected=0x0000, actual=0x0610) |
| `s1.TestS1Syz1CompleteRunTraceReplay` | FAIL | 417 | frame 250 -- y_speed mismatch (expected=-0610, actual=-0510) |
| `s1.TestS1Syz2CompleteRunTraceReplay` | FAIL | 336 | frame 1088 -- x_speed mismatch (expected=0x02E8, actual=0x02F4) |
| `s1.TestS1Syz3CompleteRunTraceReplay` | FAIL | 714 | frame 1392 -- x_speed mismatch (expected=-0200, actual=0x0200) |
| `s2.TestS2Arz2LevelSelectTraceReplay` | FAIL | 1790 | frame 899 -- y_speed mismatch (expected=-02D0, actual=-01D0) |
| `s2.TestS2ArzLevelSelectTraceReplay` | FAIL | 640 | frame 990 -- y mismatch (expected=0x03A3, actual=0x039E) |
| `s2.TestS2Cnz2LevelSelectTraceReplay` | FAIL | 1403 | frame 728 -- y mismatch (expected=0x0571, actual=0x056C) |
| `s2.TestS2CnzLevelSelectTraceReplay` | FAIL | 529 | frame 202 -- tails_x mismatch (expected=0x0265, actual=0x0264) |
| `s2.TestS2Cpz2LevelSelectTraceReplay` | FAIL | 1353 | frame 763 -- tails_g_speed mismatch (expected=-0018, actual=0x0000) |
| `s2.TestS2CpzLevelSelectTraceReplay` | FAIL | 814 | frame 724 -- tails_x_speed mismatch (expected=0x003C, actual=-00C4) |
| `s2.TestS2DezEndingLevelSelectTraceReplay` | FAIL | 137 | frame 1557 -- x_speed mismatch (expected=0x0000, actual=0x003C) |
| `s2.TestS2Ehz1TraceReplay` | PASS | 0 | full trace |
| `s2.TestS2Htz2LevelSelectTraceReplay` | FAIL | 1093 | frame 831 -- tails_cpu_jumping mismatch (expected=0x0001, actual=0x0000) |
| `s2.TestS2HtzLevelSelectTraceReplay` | FAIL | 1182 | frame 419 -- tails_cpu_interact mismatch (expected=0x0000, actual=0x0018) |
| `s2.TestS2Mcz2LevelSelectTraceReplay` | FAIL | 1038 | frame 1807 -- tails_x_speed mismatch (expected=-0018, actual=0x00E8) |
| `s2.TestS2MczLevelSelectTraceReplay` | FAIL | 323 | frame 3005 -- tails_x_speed mismatch (expected=0x02CD, actual=0x01CD) |
| `s2.TestS2Mtz2LevelSelectTraceReplay` | FAIL | 3394 | frame 645 -- tails_x_speed mismatch (expected=0x00C1, actual=-0200) |
| `s2.TestS2Mtz3LevelSelectTraceReplay` | FAIL | 2996 | frame 461 -- tails_cpu_interact mismatch (expected=0x006A, actual=-0001) |
| `s2.TestS2MtzLevelSelectTraceReplay` | FAIL | 1510 | frame 375 -- tails_cpu_interact mismatch (expected=0x0001, actual=-0001) |
| `s2.TestS2Ooz2LevelSelectTraceReplay` | FAIL | 1073 | frame 222 -- tails_cpu_interact mismatch (expected=0x0000, actual=0x001F) |
| `s2.TestS2OozLevelSelectTraceReplay` | FAIL | 1047 | frame 1645 -- tails_x_speed mismatch (expected=0x0018, actual=-00E8) |
| `s2.TestS2SczLevelSelectTraceReplay` | FAIL | 60 | frame 6370 -- y mismatch (expected=0x057D, actual=0x0578) |
| `s2.TestS2WfzLevelSelectTraceReplay` | PASS | 0 | full trace |
| `s3k.TestS3kAizCompleteRunTraceReplay` | FAIL | 3615 | frame 1095 -- x_speed mismatch (expected=0x0000, actual=0x000C) |
| `s3k.TestS3kAizTraceReplay` | FAIL | 1722 | frame 3135 -- tails_g_speed mismatch (expected=0x039E, actual=0x0000) |
| `s3k.TestS3kCnzCompleteRunTraceReplay` | FAIL | 5608 | frame 0 -- y_speed mismatch (expected=0x0000, actual=0x0038) |
| `s3k.TestS3kCnzTraceReplay` | ERROR | n/a | input alignment frame 39672 -- BK2 input=0x0010, trace input=0x0000. Check `bk2_frame_offset` in `metadata.json`. |
| `s3k.TestS3kHczCompleteRunTraceReplay` | FAIL | 2775 | frame 9482 -- air mismatch (expected=1, actual=0) |
| `s3k.TestS3kIczCompleteRunTraceReplay` | FAIL | 3155 | frame 3323 -- rings mismatch (expected=2, actual=1) |
| `s3k.TestS3kMgzCompleteRunTraceReplay` | FAIL | 8444 | frame 738 -- rings mismatch (expected=17, actual=18) |
| `s3k.TestS3kMgzTraceReplay` | FAIL | n/a | input alignment frame 33271 -- BK2 input=0x0001, trace input=0x0009. Check `bk2_frame_offset` in `metadata.json`. |
| `s3k.TestS3kMhzCompleteRunTraceReplay` | FAIL | 2708 | frame 0 -- tails_cpu_routine mismatch (expected=0x000C, actual=0x0006) |
