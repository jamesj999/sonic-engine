# S3K 1-up SFX suppression

## Finding and reference

Baseline: `develop` at `46d7357918d50ceb901eda5904183f76f25e1478`.
The live presentation path admitted jump and ring effects during the extra-life
jingle. Its existing rewind-captured `sfxBlocked` flag had no production setter
at override entry.

Retail `docs/skdisasm/Sound/Z80 Sound Driver.asm` `zUpdateMusic` (662–679)
clears both SFX request mailboxes and skips queue processing while
`zFadeToPrevFlag == 29h`. `zFadeInToPrevious` (2725–2727) clears that flag
before the restoration fade begins. This is request discard, not channel
priority or postponement. The shipped `fix_sndbugs=0` path is the reference.
This investigation does not establish how already-running SFX should behave
at jingle entry.

The existing movie is
`src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2`.
Two independent 9,000-frame captures through
`tools/audio/run_s3k_audio_oracle_reference_v2.sh` were byte-identical:

```text
reference JSONL SHA-256:
9b48f638918fa5ee9fab67be2fd3949f0f09bdff66730c7a2a64d816f64cd9a1
observer core compressed SHA-256 (artifact-lock.json):
25ee305d8bcac2567d60fd04c14238784ddd018808d4dafe7d5ef2b8372677b6
ROM SHA-1:
cfbf98c36c776677290a872547ac47c53d2761d6
```

Each capture contains 8,854 completed driver services. In the capture's frame
coordinates, the fade-to-previous flag becomes `29h` at 7700, `FFh` at 7919,
and zero at 7920. Frame-entry mailboxes contain jump (`62h`) requests at
7733, 7777, 7819, 7864, and 7906. No additional ring collection occurs during
this interval in the gameplay trace; ring overlap below is synthetic.

Frame-entry mailboxes are sampled observations, **not a complete request
transcript**. A nonzero mailbox at service completion can also be a later
68K submission; it is not evidence that the suppression branch admitted it.

Production diagnostics preserve that distinction. Ordinary SFX reach the
registry admission boundary and report `BLOCKED` there. Ring suppression must
precede speaker selection so it cannot advance the left/right phase; the
manager-owned pre-selection gate therefore reports the raw ring request as a
separate `BLOCKED` admission outcome before returning. It does not claim that a
speaker-specific effect was selected or admitted.

Local raw captures and the controlled production probe are retained under
`<agent-scratch>/s3k-oneup-overlap-20260905/`.
The probe plays AIZ1, starts the 1-up through `AudioManager`, then submits a
jump 33 frames later (the observed offset) or a synthetic ring at that offset.
It is not a full-game replay and does not hydrate engine state from the trace.

On the baseline, the jump probe creates SFX `62h` and the ring probe creates
SFX `34h` while music `2Ah` is active. After the correction, neither creates
an SFX sequencer and both produce exactly the same ordered chip-write list as
the no-request control (328 writes in the observed presentation frame).

## Correction and tests

The S3K host policy enables suppression for the existing override lifecycle.
The registry sets its existing snapshot/rollback-owned flag at override entry
and clears it at restoration, ordinary replacement, or global stop. No new
timer, game-name branch in shared code, or parallel state flag is introduced.
Caller-owned ring selection checks that same gate before toggling speakers;
the raw request observer still sees the submission. Other SMPS SFX reach the
registry's existing rejected-admission path.

`TestS3kOneUpRestoreRom` adds five test cases covering jump/ring discard,
ring-speaker stability, no deferred effects, acceptance during restoration,
snapshot restoration, ordinary music replacement, no-saved-song completion,
and global fade-stop cleanup. The three primary cases failed on the baseline.
The two cleanup cases then caught sticky-gate defects in the first candidate;
both were corrected before full validation. Independent read-only review
verified those release paths and found no remaining merge-blocking issue.

## Coverage limits

The passing S3K driver-oracle prefix is still the separate 5,400-frame intro
window, which contains no 1-up request. Its adapter bypasses production
presentation admission. These live-path regressions close this specific
coverage gap; they do **not** turn the full-game audio oracle green or establish
complete ordered-write parity for the newly captured interval. A future
full-run gate must compare submissions, discard/admission, and output through
the production owner rather than treating all observed requests as admissions.

## Validation

JDK 21; all test commands use `-Dmse=off` and absolute paths for the three ROMs.
Focused command: `mvn -Dmse=off -Ds3k.rom.path=<absolute-s3k-rom> -Dtest=TestS3kOneUpRestoreRom test -B`.
Full commands: `mvn -Dmse=off <three-ROM-properties> test -B` and the same with
`-Pguards` in a separate invocation.

Baseline ordinary suite: 16,579 tests, zero failures/errors, 43 skips.
Baseline structural guards: 609 tests, zero failures/errors/skips.
Final focused run: 12 tests, zero failures/errors/skips.
Development ordinary suite: 16,584 tests, zero failures/errors, 43 skips.
The distinct XML identity/status/message comparison preserves all 15,633
baseline outcomes and adds exactly five passing cases. Execution totals come
from the summed per-class and terminal Maven log summaries, not XML testcase
counts: nested-suite XML reports contain duplicated entries.
Development structural guards: 609 tests, zero failures/errors/skips, with
exactly the same distinct outcomes as the baseline.

Implementation commit `751e655b2` merged without conflicts into `develop` as
`4d0c99095`. Post-merge ordinary verification ran 16,584 tests with zero
failures/errors and 43 skips; its distinct outcomes preserve every baseline
outcome and add the same five passing cases. Post-merge fresh guards ran 609
tests with zero failures/errors/skips and identical outcomes to the baseline.
The external evidence directory retains `baseline-verification.tar.gz`,
`development-verification.tar.gz`, and `postmerge-verification.tar.gz` with
the Maven logs and XML reports. `compare_suites.py` checks both completed log
totals and distinct XML identity/status/message sets.
