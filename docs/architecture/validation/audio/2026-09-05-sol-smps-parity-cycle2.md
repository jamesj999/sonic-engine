# Second Sol SMPS parity delivery cycle

Status: delivered via develop merge `d004188d9`, pushed through `c7d04fbc3`;
post-merge verification and completed-worktree cleanup succeeded.
Baseline: develop `3fd7a15fc`. Candidate branch:
`bugfix/ai-audio-parity-cycle2`, worktree `audio-parity-cycle2`.

## Bounded behavior repairs

- S3K overridden PSG attack (`d8488e080`): retain the reset envelope cursor
  until the first unoverridden volume service. Retail `zFinishTrackUpdate`
  resets it, but `zUpdatePSGTrack` returns at the override gate before
  `zDoVolEnv`. Independent review checked config applicability, attack,
  release, tie and rest behavior. The focused test sets ownership directly;
  the original-driver oracle supplies the production integration evidence.
- S2 DAC cadence (`bc249d9b5`, review `4daabfb51`): use the retail 295-cycle
  compressed-byte budget in the loader's success and fallback paths. This
  replaces 288 without changing shared chip arithmetic or S1/S3K loaders.
  The test separates ROM metadata from cadence consumption, checks rates
  1 and 23, and measures loaded kick completion. Its nine-output-frame bound
  follows resampler lookahead and output phase rounding, not fixture fitting.
  The worker's 295-to-288 mutation produced two assertion failures.
- S3K EC cursor rewind (`eae7934cd`): replace the existing game-specific
  handler's guarded decrement with an unsigned byte decrement. The rest clear,
  volume clamp and no-direct-write behavior were already correct. Review
  rejected moving those semantics into a new shared configuration/context API:
  the existing handler is the smallest accurate owner. Focused cases include
  zero wrap, underflow clamp, non-PSG rejection and both PSG/YM write observation.
- S1 availability naming (`f6374613e`): the test now explicitly asserts
  authenticated reference unavailability. Its fail-closed assertion is unchanged;
  no resumed-service or PCM match is claimed.

The envelope repair moved the S3K ordered/state frontier from 1592 to 1594;
the cursor repair then moved it to service 1652, event 0: reference YM2612
port 1 register 80 value FF, engine PSG value C8. The prefix through service
1651 matches; this is not full-run parity. Future envelope consumption from
cursor FF remains unverified: retail zero-extends it and reads envelope+255,
whereas Java has a bounded envelope array. Do not normalize that state away.

## Verification

Commands use JDK 21 and all three absolute discovered ROM paths. Set the ROM
variables below to absolute paths; relative paths in worktrees silently skip
ROM-gated tests.

```bash
mvn -Dmse=off "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" test -B
mvn -Dmse=off -Pguards "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" test -B
mvn -Dmse=off "-Dsonic1.rom.path=$S1_ROM_PATH" "-Dsonic2.rom.path=$S2_ROM_PATH" "-Ds3k.rom.path=$S3K_ROM_PATH" -Dtest=TestSonic2DacCadence,TestYm2612DacTiming,TestRomAudioIntegration,TestS3kOverriddenPsgEnvelopeTiming,TestS3kOracleRequestSidecarWiring test -B
```

| Run | Result |
|---|---|
| Initial assembled focused selection | 28 tests, 0 failures/errors/skips |
| First updated baseline ordinary | Incomplete: native fork exit 134 in `TestEditorToggleIntegration`; 16,612 reported executions, no assertion failures/errors, 43 skips; not a completed suite |
| Updated baseline ordinary retry, `3fd7a15fc` | 16,641 tests, 0 failures/errors, 43 skips |
| Updated baseline guards, `3fd7a15fc` | 609 tests, 0 failures/errors/skips |
| Fresh baseline editor-class check | 29 tests, 0 failures/errors/skips |
| Final candidate ordinary, `c8b506a34` source | 16,650 tests, 0 failures/errors, 43 skips; baseline outcomes preserved after the explicit S1 rename, nine new passing outcomes |
| Final candidate guards | 609 tests, 0 failures/errors/skips; exact baseline identity/outcome equality |
| Final candidate focused selection | 70 tests, 0 failures/errors/skips |
| Post-merge ordinary, `d004188d9` | 16,650 tests, 0 failures/errors, 43 skips; exact candidate identity/outcome equality |
| Post-merge guards, `d004188d9` | 609 tests, 0 failures/errors/skips; exact candidate identity/outcome equality |

Logs and reports remain under each worktree's `target/`. Compare exact test
identity, status and message, not just totals. Review intentional assertion
renames explicitly; never hide a removed failing test through normalization.
The raw ordinary comparison reports exactly one removed passing identity,
`TestS1OverrideResumeAudioOracle.exactFirstServiceAndNextPcmMatch`, and its
passing replacement `authenticatedOverrideResumeReferenceIsUnavailable`,
whose assertion body is unchanged. Normalizing only that reviewed pair leaves
zero removed/changed outcomes and nine new passing outcomes. Exact distinct
outcome counts are 15,692 on baseline and 15,701 on candidate; raw XML entries
include nested-suite duplication and are not execution totals.

The first baseline dump identifies `GL11.glGenTextures` without a current GL
context during editor level initialization. No second-cycle production changes
were present on that baseline. Its log and reports are archived under
`target/audio-parity-cycle2-baseline-crash-evidence/`; the retry does not erase
that failed execution record.
The same reused-fork no-context failure is documented in the
[July performance validation](../performance/2026-07-28-performance-followup-report.md).
Its historical standalone passes explain the suspected isolation issue but
do not substitute for this cycle's completed comparison.
This cycle's fresh `-Dtest=TestEditorToggleIntegration` invocation also passed
all 29 tests; its reports are retained separately from the complete baseline.

Final focused selection adds `TestPsgVolumeChangeSemantics`,
`TestS1OverrideResumeAudioOracle`, `TestOverrideResumeReferenceBundle` and
`TestEditorToggleIntegration` to the initial focused command above. The S1
class remains an unavailability check, not an authenticated parity comparison.

## Next bounded task, not included

Integration produced one documentation-only conflict between independently
appended frontier entries; both were preserved. The final develop merge had
no conflicts. Ordinary and guard comparisons each found zero changed candidate
outcomes after integration. The candidate, envelope and cadence worktrees and
their fully merged local branches were removed after push, then metadata was
pruned. Verification evidence, local configuration copies and the test-generated
rewind report were archived first. Original ROMs, user-modified disassembly
submodules and ongoing SFX-order/native worktrees were preserved.

Read-only source diagnosis of service 1652 identifies S3K SFX walk order.
Retail `zUpdateSFXTracks` walks seven fixed RAM slots, FM3 through FM6 then
PSG1 through PSG3. Flying's newly admitted FM4 voice must therefore precede
the older Collapse PSG track. S3K's configuration currently retains the
header-order default instead of selecting the existing channel-RAM-order
walker. A next task must test older-PSG/newer-FM ordering and restoration
neighbors before changing that configuration. This candidate does not fix
or hide the remaining ordered-write mismatch.

## Separate native observation evidence

TraceChaser implementation `be2ed462` and handover `7759b0a` remain local,
unpublished and diagnostic-only. No OpenGGF gitlink change is included.
This revision changes the native build identity and adds primary/reload
tempo-read markers. The earlier write-only core identity does not apply.

The lead independently verified duplicate 5,400-row raw captures with SHA-256
`a87a6840aa2960b6b84e587ff0308a446739885e807c3e1adab6196f14cc993f`
and extracted observations with SHA-256
`0e3b157cf4a7b6d41465aacf52819131b6bdcccd1e3ea577829141c5225dddaa`.
The 08 write at row 3072 is consumed by the primary read at row 3073;
the 00 write at row 4269 is read in the same row. Neither selects the
conditional reload read. Java replay must preserve that actual read ordering
and raw values 00 through FF; the existing multiplier setter cannot do so.

The worker reports 592 passing, 186 failing and 35 skipped native baseline
tests versus 596 passing, 186 failing and 35 skipped candidate tests in the
same standalone layout. The lead independently compared the sorted failure
and skip identity/message files: byte-identical. This is unchanged red
baseline evidence, not a full native-suite pass or production authentication.
Java phase consumption and authorized TraceChaser publication remain open.

A subsequent fresh two-build check found different absolute staging paths
embedded in the binaries. Local native fix `b03c263` uses one task-local
staging path sequentially; handover/artifact record `72c4eef` documents both
the preserved failed outputs and matching corrected builds. The worker reports
reproduced raw SHA-256
`c71151e964eb3b8d1d529b3f418c62c0c5e3ad2bbf97d3d1fcc7482868e3e668`
and compressed SHA-256
`28de4367d02824abd1255c39cf5e4cc66a8b42fec18325b39aca13919bebe608`.
This is a new build identity, not a relabeling of the earlier v3 captures.
Fresh S1 diagnostic capture work is separate from this Java delivery.
