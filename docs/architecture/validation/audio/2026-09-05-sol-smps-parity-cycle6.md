# S3K SMPS parity cycle 6: diagnostic ring dispatch

Status: merged through `ad152601b`; combined post-merge verification passed.
Push and cleanup status are in the
[group ledger](2026-09-05-sol-smps-parity-delivery-group.md).

Retail `zPlaySound_CheckRing` toggles boot-zeroed `zRingSpeaker` only when raw
request `33h` reaches dispatch, selecting Sound34/Sound33 in turn
(`Sound/Z80 Sound Driver.asm:547,1919-1928`, shipped `fix_sndbugs=0`). The
oracle adapter instead loaded Sound33 directly. The bounded correction lives
in the diagnostic capture callback, not production audio.

An incomplete production `AudioRequestService` was rejected: the current
forward boundary permits only one consequence, whereas retail clears both SFX
mailboxes during the 1-up override and normally cycles all three queue slots
(`:658-701`). Such a service could retain suppressed requests and reorder or
delay music/secondary requests. No test in this bounded repair claims the
capture's 1-up/fade suppression behavior; that comparison remains unverified.

## Evidence

- Initial hard-oracle run after the transform failed the old pin at service
  2409 (`MUS_PSG1.overridden`, reference `true`, engine `false`), proving the
  prior 2357 mismatch moved without changing the comparator.
- Bypass mutation (`RingDispatch.select(33h) -> 33h`) fails at the old service
  2357 frontier. Log: `target/audio-parity-cycle6-ring-dispatch/mutation-bypass.log`.
- `TestS3kCaptureRingDispatch` exercises the real pending request callbacks:
  two `33h` requests remain unconsumed before service and select `[34h,33h]`
  in service order; explicit `34h`, non-ring IDs and a new boot instance do
  not incorrectly advance/carry state.
- `firstRawRingSelectsFm5WithExactReferenceWrites` compares the service-2357
  non-DAC write sequence and pins FM5 key-off plus its four SSG-EG clears.

## Final candidate verification

Candidate head `0797b5d8037c4c5db240cab034172ce327849524` includes the
current develop baseline. JDK 21 and absolute S1 REV01, S2 REV01 and locked-on
S3K ROM paths were supplied throughout.

- Focused selector/frontier/FM5 write gate: 4 passed, 0 failed/errors/skipped.
  The exact service comparison excludes DAC `2Ah` bytes and terminal `2Bh=0`
  under the already documented PCM partitioning limitation; DAC parity remains
  open.
- `mvn -Dmse=off ... test -B`: 16,669 tests, 0 failures/errors, 43 skipped;
  `target/audio-parity-cycle6-ring-dispatch/ordinary-final.log` and
  `ordinary-reports/`.
- `mvn -Dmse=off -Pguards ... test -B`: 609 tests, 0 failures/errors/skips;
  `target/audio-parity-cycle6-ring-dispatch/guards.log` and `guards-reports/`.

Root's exact refreshed-baseline comparison preserves every ordinary/guard
outcome and adds three passing ordinary identities. Combined develop verification
also preserves every candidate outcome; the group ledger records the complete
delivery evidence without claiming production mailbox or DAC parity.
