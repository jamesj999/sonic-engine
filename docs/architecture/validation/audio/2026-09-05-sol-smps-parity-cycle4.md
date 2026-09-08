# SMPS parity cycle 4: S3K PSG stop and covered-noise restore

Status: delivered on develop as merge `1e33747f1`, verified and pushed through
`250b3409b`; completed worktree and merged local branch removed.
full-game audio parity and authenticity remain open.

## Source boundary

Retail `cfStopTrack` emits the stopped PSG track's `DF FF FF` transaction,
then (only while updating SFX) clears the covered music override and reaches
`zStopPSGTrack`. That routine preserves playing/rest state and writes the exact
stored `PSGNoise` byte only when noise mode is set and the raw byte is negative
(`docs/skdisasm/Sound/Z80 Sound Driver.asm:3443-3469, 3521-3533, 4226-4249`).
Generic teardown, replacement and reap entry points are not established by
that source and do not use this restore cause. Retail E3 jumps to
`cfStopTrack` after its silence prelude, but Java E3 parity is not yet modelled;
this candidate does not claim it.

The candidate therefore carries the exact ending F2 track through
`CoordFlagContext` and `SmpsSequencerHost`. It clears only that owner's tone
lock, channel-2 admission claim, and (for the exact noise-form PSG3 track) its
matching noise lock before one F2-specific music callback. Generic override
callbacks retain their established behavior.

## Review corrections and negative controls

- The first claim test began with an inactive SFX track, so admission never
  recorded claim 2. It now proves the active admission claim before stopping;
  retaining that claim fails the callback-time assertion
  (`target/mutation-s3k-noise-retain-claim2.log`).
- Raw restore uses `85`, plus the `7F` no-write / `80` write boundary, so an
  accidental `E0 | low-nibble` reconstruction cannot pass.
- An inactive sibling scan could misclassify a tone-ending track. The exact F2
  track is now passed by identity; stale-noise-sibling and duplicate-active-slot
  controls prove no cross-release and fail-closed behavior.
- The cycle-3 runtime-tail harness initially retained the known-gap legacy
  `C0 00 FF` suffix. Its initial two failures are preserved in
  `target/e7-runtime-tail-initial-red.log`; only that prefix became the
  source-proven `E7`. The unique `DF FF FF` marker, 122/87 timing, attenuation
  ladders, and characterized later AIZ1 writes remain asserted.
- The expanded focused run then exposed an unintended non-F2 silence loss in
  `stopAllSfx` (`target/e7-verification/focused/run.log`). Cleanup silence was
  restored, but review found that generic callbacks could still enter the raw
  restore policy. A dedicated F2 restore cause closes that leak. Routing generic
  callbacks through it makes the negative control fail
  (`target/e7-verification/focused/mutation-generic-routed-through-f2.log`).
- A public-path mutation preserved normal SFX admission but routed only the
  generic release callback through the F2 setter. `stopAllSfx` then emitted the
  music source's raw `85` between the effect's physical `DF` and `FF` writes,
  failing the actual-bus assertion; restoring the generic callback passes
  (`target/e7-verification/focused/mutation-driver-release-callback-to-f2.log`,
  `target/e7-verification/focused/restored-driver-public-path.log`). The
  asserted `DF FF` sequence is the established Java generic cleanup boundary,
  not a projection of retail E4: retail `zStopSFX` reaches `cfStopTrack` through
  `zSilenceStopTrack` / `cfSilenceStopTrack`, while Java's separately owned
  `S3kE4StopSfxPlan` and `stopAllSfxWithoutRestoreWrites` boundary remains the
  retail-E4 modelling seam.

## Verification ledger

All ROM-backed commands use JDK 21 and absolute S1 REV01, S2 REV01 and locked-on
S3K ROM paths.

| Run | Result | Evidence |
|---|---|---|
| Runtime-tail initial suffix | 3 tests, 2 expected failures | `target/e7-runtime-tail-initial-red.log` |
| Expanded focused before cleanup correction | 123 tests, 1 failure | `target/e7-verification/focused/run.log` |
| Expanded focused after first correction | 123 tests, 0 failures/errors/skips | `target/e7-verification/focused/restored-run.log` |
| Diagnostic ordinary before final cause split | 16,659 tests, 0 failures/errors, 43 skips | `target/e7-verification/ordinary/run.log` |
| Diagnostic ordinary after cause split, before updated develop | 16,660 tests, 0 failures/errors, 43 skips | `target/e7-verification/ordinary/pre-e73-post-cause-run.log` |
| F2/generic cause boundary | passed | `target/e7-verification/focused/cause-boundary.log` |
| Final expanded focused after merging `e73ca442f` | 125 tests, 0 failures/errors/skips | `target/e7-verification/focused/post-e73-final-run.log`; XML in `target/e7-verification/focused/post-e73-reports/` |
| Final ordinary after merging `e73ca442f` | 16,661 tests, 0 failures/errors, 43 skips | `target/e7-verification/ordinary/post-e73-final-run.log`; XML in `target/e7-verification/ordinary/post-e73-reports/` |
| Final guards, separate JVM after merging `e73ca442f` | 609 tests, 0 failures/errors/skips | `target/e7-verification/guards/post-e73-final-run.log`; XML in `target/e7-verification/guards/post-e73-reports/` |
| Post-merge ordinary, develop `1e33747f1` | 16,661 tests, 0 failures/errors, 43 unchanged skips | main `target/audio-parity-cycle4-postmerge-evidence/ordinary.log` and `ordinary-reports/` |
| Post-merge separate guards, develop `1e33747f1` | 609 tests, 0 failures/errors/skips | main `target/audio-parity-cycle4-postmerge-evidence/guards.log` and `guards-reports/` |

## Integration and exact comparison

The updated baseline is develop `e73ca442f`, whose source/test tree is the
verified third-cycle merge `ebb024201`. Its ordinary suite ran 16,654 tests
with no failures/errors and 43 skips; its separate guards ran 609 without
failures/errors/skips. Those complete logs and XML remain in
`target/audio-parity-cycle3-postmerge-evidence/`.

Candidate source `d14277e8f`, documented through `e57080d44`, passes 16,661
ordinary tests with no failures/errors and the same 43 skips. Exact
identity/status/message comparison preserves all 15,705 baseline outcomes
after one reviewed rename:
`f2ToneAndNoiseStopsKeepRetailPsgWriteCounts` becomes
`musicF2StopsWithOnlyItsDriverSilenceTransaction`, retaining its byte assertions
and adding stopped-state assertions. Seven new driver tests pass; no other
renames, removals, skipped outcomes or changed messages are allowed. All 609
guard outcomes are identical. Main-workspace comparison logs are
`target/audio-parity-cycle4-candidate-{ordinary,guards}-comparison.log`.

Verification commands, with the ROM variables set to absolute verified paths:

```sh
mvn -Dmse=off -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM" test -B
mvn -Dmse=off -Pguards -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM" test -B
```

Fetch and fast-forward pull left develop at `e73ca442f`; the candidate already
contained that baseline. Merge `1e33747f1` was conflict-free and preserves the
user's three dirty disassembly submodules. Post-merge commands run in the main
workspace with logs below `target/audio-parity-cycle4-postmerge-evidence/`.

Before cleanup, candidate logs/XML, negative controls and generated local
configuration/rewind reports were archived outside the repository at
`${EVIDENCE_ROOT}/cycle4-candidate-e57080d44.tar.gz`, SHA-256
`f5f9263a5b668227b986dc0a98a67c6b7b9b0616d366d07794449176e4f0e123`.
This is a report archive, not a shared Maven build tree.

Post-merge verification passed with exact candidate equality: all 15,712
ordinary distinct outcomes and all 609 guard outcomes are unchanged, with no
added or removed identities. This preserves the updated baseline plus the seven
reviewed additions. Exact comparison logs are
`target/audio-parity-cycle4-postmerge-{ordinary,guards}-comparison.log`.
Develop was pushed through `250b3409b`. Post-merge logs/XML and both comparison
pairs are archived at `${EVIDENCE_ROOT}/cycle4-postmerge-1e33747f1.tar.gz`, SHA-256
`0f9094ce492a15095bc0ea3892fecf2c65aea561d9b9e6ef92c64be80cbc9620`.

Cleanup verified clean tracked/untracked status and merged ancestry before
removing `.worktrees/sol-s3k-noise-restore` and local
`bugfix/ai-sol-s3k-noise-restore`, then pruned worktree metadata. Every ignored
path was inspected: ROM/reference entries were hook-created symlinks (original
targets untouched), configuration and the generated rewind report were archived,
and `target/` plus `saves/image-cache/s1/` were test-generated rebuildable output.
No unknown or user-authored work was discarded. The three new worker trees,
native capture tree, original ROMs and user-modified disassemblies remain intact.
