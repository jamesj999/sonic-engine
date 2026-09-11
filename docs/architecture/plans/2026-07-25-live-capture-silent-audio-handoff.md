# Live Recording Silent From the Master Title Screen — Handoff

Date: 2026-07-25

> **Resolved.** The measurement the handoff asked for was run first and it
> disproved the leading hypothesis below. See [Outcome](#outcome) at the end —
> read that before the hypothesis section, which is kept only as a record of
> what was ruled out.

## State

- Worktree: `/home/farrell/code/projects/OpenGGF-live-av-recording`
- `origin/develop` is green at `116651690`, which **contains a partial fix**
  (`fix(capture): carry the recording lease across a producer rebuild`).
- No open branch. Start fresh from `origin/develop`.

## The bug

Press `Shift+O` on the master title screen, then enter a game. The recording's
video is fine and the **speakers are fine** — user-confirmed — but the MKV has no
audio for the whole session.

Originally it also threw, once, per recording:

```
WARNING: Live viewport recording audio failed; continuing with stereo silence
java.lang.IllegalStateException: Live capture audio handle is no longer attached
    at AudioManager$ManagerLiveCaptureAudioHandle.drainPresentationFrame(AudioManager.java:2083)
    at LiveCaptureController.drainAudioOrSilence(LiveCaptureController.java:240)
```

## What is already fixed on develop

`Engine.initializeGlobalGameplayServices()` installs `LWJGLAudioBackend` when
gameplay starts. `setBackend` tears the presentation producer down and rebuilds
it, and the teardown retired the live capture lease outright. `116651690` carries
the lease across the rebuild instead, preserving clock phase and frame totals; a
genuine teardown still retires it.

That removed the exception. **It did not restore the audio.**

## Why it is still silent — the leading hypothesis

`LiveCaptureController.prepareAudioHandle()` runs once at `start()` and binds the
controller to the *title-screen* producer's geometry:

```java
captureSampleRate = sampleRate;
pcm = new short[Math.multiplyExact(maxStereoFrames, 2)];
```

The lease is now carried across the rebuild, but `pcm` is still sized for the old
producer. If the new producer's `maxStereoFramesPerPacket()` is larger,
`audio.drainPresentationFrame(pcm)` throws *"target is too small"*, the catch in
`drainAudioOrSilence` replaces `audio` with `ClockedSilenceAudioHandle`
permanently, and `warnAudioOnce` logs **once per recording** — easy to miss.

That matches the symptom exactly: no crash, speaker fine, recording silent for
the rest of the session.

### Why the geometry changes at all

`AudioManager.ensureShadowPresentation()`, around line 1879:

```java
int sampleRate = Math.max(1, presentationSink != null
        ? presentationSink.sampleRate()
        : backend != null ? backend.outputSampleRate() : 48_000);
int frameRate = configuredFrameRate();
```

The sample rate is **not** ours — it comes from the device. On the title screen
there is no real backend, so the producer uses the `48_000` literal. Entering
gameplay installs `LWJGLAudioBackend` and the rebuilt producer adopts whatever
OpenAL reports, which may be 44100. `frameRate` is config-derived and stable by
comparison.

## Do this first: measure, do not guess

I guessed twice on this bug and the code had the answer both times. Before
editing anything, instrument or log, across the master-title → gameplay
transition:

- the producer's `sampleRate` before and after the rebuild;
- the lease's `maxStereoFramesPerPacket()` before and after;
- `LiveCaptureController.pcm.length` and `captureSampleRate`;
- whether `drainAudioOrSilence`'s catch block is entered, and with what.

The fix depends on which value moves:

| Observation | Fix |
|---|---|
| Sample rate changes | Pin the presentation sample rate from config so it is identical either side of the swap, and ask the device to match. The lease carry then just works, with no buffer or ffmpeg reconciliation needed. This is the better fix and the user's instinct — we should be choosing this value, not adopting it. |
| Only the packet size changes | Grow `pcm` on rebind. `drainAudioOrSilence`'s catch block already does exactly this for the silence handle; the drain path needs it *before* the drain, not only after a failure. |

If the sample rate does change, note that `captureSampleRate` was already handed
to ffmpeg as `-ar` at `recorder.start()`. Fixing only the buffer would then give
**pitch-shifted** audio rather than silence — a worse failure, because it looks
like it worked. Pinning the rate removes that hazard entirely.

Related precedent: `beginCaptureMode` already **rejects** sample-rate mismatches
for the offline lease. The codebase treats this as a real hazard; the live path
never handled it.

## Verification — read this before writing a test

The test I shipped with the partial fix,
`TestLiveCaptureSurvivesBackendSwap`, asserts:

```java
assertEquals(2, recording.drainPresentationFrame(new short[4]),
        "the recording must keep receiving packets after the backend swap");
```

That asserts the **frame count**, against a `NullAudioBackend` that produces
silence anyway. It passes identically whether real audio flows or not. It proved
the lease is attached and clocked; it proved nothing about audibility. That is
why the fix looked verified and the recording stayed silent.

The replacement must assert **non-zero PCM through a real source across the
swap**. `TestUnifiedAudioPresentationIntegration` and the three
`@RequiresRom` `*UnifiedAudioPresentationRomIntegration` classes already do
exactly this — drive each phase to silence first so a later non-zero packet is
attributable, and assert speaker and capture receive byte-identical copies of one
producer packet. Follow that pattern.

Keep or strengthen `TestLiveCaptureSurvivesBackendSwap`'s other two cases; the
teardown-still-retires case is real coverage.

## Reproduction

ROM symlinks are at the worktree root (`s1.gen`, `s2.gen`, `s3k.gen`, hashes
verified). Manual: `./dev.sh`, `Shift+O` on the master title screen, enter a game,
stop, inspect the MKV:

```bash
ffprobe -v error -show_entries stream=codec_name,channels -of csv=p=0 <file>.mkv
ffmpeg -v error -i <file>.mkv -map 0:a:0 -f s16le -acodec pcm_s16le -ac 2 -ar 48000 - | xxd | head
```

Non-zero bytes after the transition point is the pass condition.

## Gates

```bash
mvn -Dmse=off -Dtest='com.openggf.audio.**,com.openggf.capture.**' test
mvn -Dmse=off -Ds1.rom.path=s1.gen -Ds2.rom.path=s2.gen -Ds3k.rom.path=s3k.gen test
```

`mvn test` regenerates `docs/status/rewind-round-trip-gaps.md`; restore it. Commit trailers per
`CLAUDE.md`; this is a `fix` touching `src/main/` so `Changelog: updated` with
`CHANGELOG.md` staged.

## Outcome

The measurement was run before any edit, through a harness wiring the real
`AudioManager`, the real producer, a real music source and a real
`LiveCaptureController`. Three transitions were driven and observed:

| Transition | Result |
|---|---|
| Backend swap alone, 48 kHz → 48 kHz | audio flows; the carry works |
| Backend swap alone, 48 kHz → 44.1 kHz | `IllegalArgumentException: snapshot clock rate does not match this clock`, thrown out of `ensureShadowPresentation` |
| `resetState()` **then** the backend swap | speaker audible, capture silent, `IllegalStateException: Live capture audio handle is no longer attached` — the reported failure, byte for byte |

The device rate on this machine was measured directly (OpenAL negotiates 48000,
matching the `48_000` literal the title screen uses), so the geometry never
moved and neither the packet-size nor the sample-rate hypothesis was the cause.

**The cause was a rebuild the handoff did not account for.** Entering a game
rebuilds the presentation *twice*, and the first one is not the backend swap:

```
Engine.exitMasterTitleScreen
  -> resetForGameplayFromMasterTitle -> audioManager.resetState()   <-- lease retired here
  -> initializeGame -> initializeGlobalGameplayServices -> setBackend(LWJGL)
```

`resetState()` retired the lease outright, so the recording was already dead
before the carry added in `116651690` could apply. The exception was therefore
never removed — the report that it had been was mistaken.

Fixed by treating `resetState()` as the presentation rebuild it is: it marks the
lease for rebind on the same terms as `setBackend`, and only `destroy()` retires.
The rate-change crash found alongside it is fixed too — a lease that cannot
follow the producer is retired with a warning naming both rates, rather than
crashing or recording pitch-shifted audio.

`TestLiveCaptureSurvivesBackendSwap` was rewritten as the handoff asked: it
asserts non-zero PCM through a real source across the transition and that the
recorder and speaker receive byte-identical copies of one packet. It was
verified RED against the exact production message before the fix.
