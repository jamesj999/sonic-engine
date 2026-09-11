# S3K Repeated-SFX First-Divergence Audit

**Date:** 2026-08-24
**ROM:** locked-on Sonic 3 & Knuckles, SHA-1
`cfbf98c36c776677290a872547ac47c53d2761d6`
**Engine base:** `4618e882bdee5ca9c9f94f8344a60833f6e20ee1`

## Finding

The first playback divergence is request/service phase, not missing same-ID
polyphony and not whole-sequencer physical-role cleanup.

The locked-on driver updates every active SFX track before it transfers and
consumes the 68K request cells. OpenGGF admits a presentation request
immediately, removing the old same-ID sequencer before the next SFX pass. For
the boss's every-third-update B4 requests, the native FM5 residence therefore
receives its current-boundary modulation service before replacement; the engine
does not. This makes successive explosions sound prematurely truncated.

## Source proof

- `docs/skdisasm/Sound/Z80 Sound Driver.asm:653-701`:
  `zUpdateEverything` calls `zUpdateSFXTracks`, then falls into
  `zUpdateMusic`; only there does it call `zFillSoundQueue` and three
  `zCycleSoundQueue` operations.
- `docs/skdisasm/Sound/Z80 Sound Driver.asm:1619-1629,2628-2644`:
  the three-cell internal queue is rotated in order; `zFillSoundQueue` copies
  music plus both SFX input cells and clears the inputs.
- `docs/skdisasm/s3.asm:1649-1673`: 68K `Play_SFX` supports two different IDs
  per frame. It ignores an ID already in `zSFXNumber0`, fills empty slot 0,
  otherwise overwrites slot 1.
- `docs/skdisasm/sonic3k.asm:176829-176857`: boss explosion child objects—not
  the controller—load `sfx_Explode` and call `Play_SFX`. ROM instruction PCs
  are `$83F60/$83F62` for `Obj_BossExplosion1` and `$83F96/$83F98` for
  `Obj_BossExplosionOffset`.

This is the shipped `fix_sndbugs=0` route. No bug-fixed branch is substituted.

## Native evidence

### Retained gameplay movie

Input movie:
`src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2`,
SHA-256
`6837de0f67db7eb68f20b6f6df6a2872713a613d8b4dbc804847209c16b56e97`.

Two fresh runs of the managed preflight produced byte-identical JSON,
SHA-256 `8320079e657af25b00bb58fc2f7da4a444c6e324606737b04077777052ef8a8e`.
The output is retained under the task scratch directory, not committed as
runtime input.

- movie frames: 21,309;
- physical FM5 Explosion reloads: 134, including three maximal runs with
  three-frame spacing (the first begins at 5,743);
- first complete Collapse residence: frames 1,558 through 1,678 inclusive,
  exactly 121 frames;
- later SFX traffic while Collapse PSG3 remains resident: 1,640, 1,641, 1,647,
  1,653, 1,656, 1,657, 1,663, 1,669, 1,672, and 1,673;
- ordinary trace events drained: 14,041,158; overflow: zero.

The selector reads native Z80 track active bits and data pointers. Frames are
reported results, not runtime selection keys.

### Injected repeated B4

Two fresh 160-frame runs through the current locked parity+PCM core were
byte-identical, SHA-256
`4bbce8c45d924f5acba364d1b234ceb93ca7aff6c79801e7870cbb30266daea8`.
Terminal evidence is 465 parity writes, 739,589 PCM rows, zero overflow, zero
fault, and body SHA-256
`a55278dff957e836c304049a84ddd26b270e6dacba0fa8b4b331a5fef58d7c5b`.

B4 was written to the native request input every three frames from 0 through
30. Every request reloaded the one physical FM5 residence. Between successive
requests the incumbent received its ordinary track updates; the replacement
was then prepared and installed later in the same driver boundary. The final
residence modulated after the last request and reached its native key-off at
frame 57.

## Boundary comparison

| Boundary | Native locked-on driver | OpenGGF base | Result |
|---|---|---|---|
| 68K/game request | B4 child requests repeat every third object update | Production boss child publishes the same logical request cadence | No earlier contrary evidence |
| Current SFX service | Incumbent FM5 is updated first | Incumbent is removed by immediate command admission | **First divergence** |
| Queue consumption | Two SFX cells consumed after current SFX pass | No S3K input-cell phase exists | Diverges as consequence |
| Physical admission | Same FM5 residence is key-off/reloaded | Same physical role is handed to the replacement | Shape is broadly correct, but too early |
| Final lifecycle | Final residence reaches normal stop | Isolated final residence can finish | Not the first divergence |

The strict Java RED records the engine's current output around each repeated
request: immediate admission preparation appears before the intended old-track
service, and the next attack is split across later output packets. That RED is
an ordering test; admission-preparation writes are not treated as native track
service writes.

## Correction owner

The smallest truthful owner is `SmpsDriver`, gated by the existing typed
`SmpsSequencerConfig.SfxStartTiming` policy:

- `NEXT_DRIVER_UPDATE` models the two locked-on S3K request cells and consumes
  them after the active SFX pass;
- `SAME_DRIVER_UPDATE` preserves existing S1/S2 admission behavior.

The correction must snapshot and atomically roll back both pending cells and
must preserve existing per-role handoff. It must not branch on B4, Collapse, a
boss, a zone, or any captured frame.

## S1/S2 cross-game ruling

The same admission delay must not be applied to Sonic 1 or Sonic 2:

- `docs/s1disasm/s1.sounddriver.asm:180-245` cycles the sound queue and calls
  `PlaySoundID` before the music and SFX track loops in the same 68K update.
- `docs/s2disasm/s2.sounddriver.asm:400-455` cycles the queue and calls
  `zPlaySoundByIndex` before `zUpdateMusic` and the following SFX loops.

Those drivers therefore authenticate `SAME_DRIVER_UPDATE`; locked-on S3K's
SFX-first loop authenticates `NEXT_DRIVER_UPDATE`. The implementation uses
that existing typed policy and has explicit controls for both same-boundary
profiles rather than a game-name branch.

A second cross-game discovery arose while testing request publication:
constructing a deferred S3K sequencer used to call `onSfxStart` before its 68K
input cell had been consumed. Consequently, even a duplicate request rejected
by S3K slot 0 could mutate coordination state. The callback now occurs at
deferred consumption. S1 and S2 retain the callback at immediate admission,
matching their queue-before-service order above. The regression test proves
zero S3K starts before the boundary, one start for the retained request after
consumption, and unchanged immediate behavior for both S1/S2 policy shapes.

A third discovery arose in the live presentation path: a driver containing
only a deferred S3K input cell was reported complete because completion looked
only at installed sequencers. Presentation could therefore discard an
isolated request before the Z80 boundary consumed it. Driver completion now
requires both input cells to be empty. S1 and S2 cannot enter this state: their
`SAME_DRIVER_UPDATE` path installs or rejects a request synchronously and never
uses the deferred cells. Their completion behavior is consequently unchanged,
and the S1/S2 ROM integration controls remain green.
