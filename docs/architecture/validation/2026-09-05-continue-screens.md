# Continue screens: 0.6 validation

## Contract and implementation

The production Game Over path now opens ROM-backed Continue screens in S1,
S2 and locked-on S3K. Per-game providers own countdowns, native art and
character departure. Existing menu input, palette fades, player animation/DPLC,
pattern rendering, PLC lifecycle and death-restart helpers retain their roles.
Start is a logical input edge; S1 waits for Sonic to land, S1/S2 accept P1,
and S3K accepts either controller. Fades advance the ROM V-int clock while
holding the screen object loop. The final fade clock reaches the level reload.

Acceptance spends one continue, restores three lives and resets score without
clearing emerald progress. S1/S2 clear the checkpoint. S3K retains it and
publishes the lives/continues save before loading; its saved timer can therefore
be restored by the checkpoint owner. Timeout returns to title without spending
a continue. Continue is a non-rewindable menu boundary.

The S2 implementation also corrects the existing Continue music request from
the empty mailbox value `0` to the ROM's `0x9C`. A ROM operand assertion and
the real music loader protect that correction. The shipped retained-Super-flag
animation choices are preserved. The S2 HTZ stale-DMA VRAM alias remains
unmodelled and is recorded in [known bugs](../../status/known-bugs.md).

## Evidence and limitations

All ordinary and trace runs use Java 21, `-Dmse=off`, and absolute paths to the
three root ROMs. Their CRC32/SHA-1 identities match `AGENTS.md`. Ordinary
release comparisons have display access: an earlier sandbox run skipped 20
additional GL cases and was rejected as equivalent evidence. Guards run in a
separate Maven JVM with `LUA_BIN=/usr/bin/lua5.4`.

Updated base `4d0c99095` passed `mvn -Dmse=off test -B` with 16,584 executions,
no failures/errors and 23 skips. Its separate `-Pguards` run passed all 609
executions without skips. The unrelated S3K one-up audio change was reconciled
before the candidate comparison; the changelog conflict retained both entries.

Candidate `8d06f64fc` passed the ordinary suite with 16,626 executions,
no failures/errors and the same 23 skip identities/reasons. Comparing report
identities found no changed outcomes. Surefire's repeated nested ICZ class
execution overwrote six method records; a focused
`-Dtest=TestSonic3kIczRewindRoundTrip` run passed all nine tests and recovered
those six identities. Its guard run caught the `GameLoop` size ratchet
(3,138 effective lines against 3,072). Follow-up `723fd8bf3` extracts
`GameLoopContinueCoordinator`, reducing the loop to 3,070 effective lines
without increasing that limit. Its focused suite passes all 42 executions
without skips. The final ordinary run on `723fd8bf3` completes at 15:17:53
with 16,626 executions, zero failures/errors and the baseline's 23 skips.
The separate guard run completes at 15:15:15 with all 609 passing; every
guard test identity matches the updated base.

Focused coverage includes real GameLoop Game Over -> Continue -> level reload
round trips for each ROM, inventory/checkpoint/save policy, Start/fade handling,
countdown and departure timings, ROM operands, all character art variants,
headless drawing through departure, render dispatch and V-int continuity.

Hidden-window OpenGL captures of waiting, accepted and departure scenes were
produced for all three games. Inspection caught and corrected S2's palette
inheritance: the Continue palette replaces line 3 while level/player lines
remain. These captures use minimal session setup, not a complete recorded
death route. They establish rendering coverage, not human listening approval
or full frame-by-frame Continue trace parity.

Raw logs, XML reports, comparison JSON and visual captures are retained outside
the checkout at
`$TASK_EVIDENCE/continue-screens-2026-09-05` (the session's external evidence
directory).

## Trace baseline

The updated-base `mvn -Dmse=off -Ptrace-replay test -B` run completes at
15:17:02 with 852 executions, seven failures, zero errors and six skips.
These are existing failures, not an all-green trace claim:

| Test | Baseline failure |
|---|---|
| `TestS1CompleteEmeraldRunChain` | 14 failed axes; segment 33 loses production ownership at BK2 cursor 210396; first reported segment-physics mismatch is segment 12 frame 101, `queue.s1_nemesis_plc.prepared` |
| `TestS2CompleteEmeraldRunChain` | 11 failed axes; interior walk exceeds destination 101691; first dynamic-art edge expected 10308, actual 10268 |
| `TestS2EhzHalfpipeRoundTripChain` | Two failed axes; first dynamic-art edge expected 9675, actual 9610 |
| `TestS3kSonicTailsCompleteEmeraldRunChain` | Interior walk exceeds destination 8817 |
| `TestS3kAizTraceReplay.replayMatchesTrace` | 37 errors; first frame 20713, `air`, expected 0, actual 1 |
| `TestS3kReplayReferenceClosureIntegration.replayMatchesTrace` | 113 errors; first frame 25589, `player_animation_id`, expected 0x0013, actual 0x0005 |
| `TestTraceRunReplayWalkerControlFlow.metadataOnlySpecialStagePlanRejectsNonContiguousStoredRows` | Parser diagnostic assertion expects a different failure; expected 48 CSV columns, got two |

The candidate `723fd8bf3` trace run completes at 15:25:16 with the same
852 executions, seven failures, zero errors and six skips. All 850 unique
test identities and their outcomes match the updated base. Failure messages
match exactly after normalizing worktree paths, including every reported
first-error frame/field. No frontier moved and this comparison did not select
a new trace target, so the frontier ledger is unchanged.

## Integration

Merged into `develop` as `be5a9e596`. The latest base addition `eb324f5c6`
changes only the unrelated audio validation document; its executable source
matches the tested base `4d0c99095`. The final merge had no conflicts.

Post-integration checks run in isolated checkouts of that exact merge commit,
avoiding another session's main-workspace Maven run:

- `mvn -Dmse=off package -B` with all three ROM properties: **16,626 executions,
  zero failures/errors, 23 existing skips**, completed at 15:34:35. Both ordinary
  and dependency-inclusive jars were built successfully.
- `LUA_BIN=/usr/bin/lua5.4 mvn -Dmse=off -Pguards test -B`: **609 executions,
  zero failures/errors/skips**, completed at 15:31:44 in a separate JVM.
- Ordinary XML comparison includes every updated-base test identity, with no
  changed outcomes or missing identities; all 39 added unique identities pass.
  The 42-execution increase includes repeated nested/parameterized execution.
  All guard identities and outcomes match. The final ordinary report also
  retains the six ICZ records lost to report overwriting in earlier runs.

The ROM arguments used for ordinary, focused and trace runs are
`-Dsonic1.rom.path`, `-Dsonic2.rom.path` and `-Ds3k.rom.path`, each set to the
verified absolute root ROM path. The trace result above applies to identical
executable source in the merge; intervening changes are documentation only.
Release notes, local Markdown targets, `git diff --check` and repository push
policy were checked. No source changed after the verified merge.
