# Unified Audio Presentation Design

## Status

Approved design for replacing split OpenAL/software audio ownership with one
software-mixed presentation stream. This design is a prerequisite for reliable
live viewport recording.

## Problem

OpenGGF currently has two incompatible presentation paths:

- SMPS and selected PCM streams can be rendered through
  `StreamBackedDeterministicAudioRuntime`.
- Normal LWJGL playback also owns legacy OpenAL sources, including static WAV
  music and independently pitched WAV SFX.

Live recording originally assumed the deterministic runtime was already active.
Enabling it globally made normal gameplay audio disappear. Scoping it to a
recording lease restored normal playback, but starting the lease still changed
audio ownership: special-stage ring/SFX voices disappeared while recording,
fallback WAV voices were not captured, and starting during an active rewind
could not transfer the current reverse cursor.

The root problem is split ownership. Recording must observe the final audible
PCM without changing which component generates or owns it.

## Goals

1. Preserve title, gameplay, special-stage, music, ring, and SFX audio across
   Sonic 1, Sonic 2, and Sonic 3&K.
2. Mix SMPS music/SFX, raw SEGA PCM, fallback WAV music, and pitched WAV SFX
   into one software-owned stereo presentation stream.
3. Make OpenAL an output sink for the final mixed PCM, not an independent
   musical or SFX owner.
4. Let live recording attach and detach a non-consuming tap without changing
   voice ownership, command routing, playback cursors, or OpenAL source mode.
5. Start recording immediately during held rewind by forking the currently
   audible reverse cursor at its exact position.
6. Keep pause, frame-step silence, rewind release/crossfade, and repeated
   recording deterministic.
7. Degrade audio-capture failures to correctly clocked stereo silence while
   video recording and gameplay continue.
8. Avoid allocation, decoding, file I/O, and unbounded work in the real-time
   mixing loop.

## Non-Goals

- Changing ROM-facing audio timing, priorities, or sound-selection behavior.
- Adding effects, spatial audio, new codecs, or user-facing mixing controls.
- Depending on optional OpenAL loopback extensions.
- Maintaining a second capture-only mirror of every audible voice.
- Preserving the superseded global-LWJGL or recording-lease runtime switches.

## Architecture

```text
Audio commands
     |
     v
Software voice registry
  - composite SMPS drivers
  - raw SEGA PCM
  - decoded WAV music
  - decoded/pitched WAV SFX
     |
     v
AudioPresentationMixer
  - one AudioFrameClock
  - reusable accumulation/output buffers
  - saturating stereo mix
     |
     +----> Presentation history / rewind cursor
     |
     +----> non-consuming live-recording tap
     |
     v
OpenAL PCM sink
```

`AudioPresentationMixer` is the sole owner of audible voice cursors. The final
mixed PCM is the sole presentation truth. OpenAL queues that PCM but does not
own independent music or SFX playback state.

## Components

The mixer and registry are owned by `AudioManager`, above both the LWJGL and
headless/no-device sinks. Replacing or losing an output device therefore never
destroys logical voices, presentation history, or capture state.

### Presentation voice contract

Every audible source implements a small software voice contract with:

- stereo rendering into a caller-owned accumulation buffer;
- current cursor and completion state;
- gain and pitch where the existing route supports them;
- looping for fallback music;
- explicit stop semantics;
- snapshot/restore data required by rewind.

An existing `SmpsDriver` is adapted as one `SmpsCompositeVoice`. Music and the
SFX sequencers already hosted by that driver are never flattened into
independent voices: YM2612/PSG ownership, channel locks and stealing, priority,
DAC fallback, continuous-SFX extension, and `SmpsDriverSnapshot` remain inside
the composite. A standalone SFX `SmpsDriver` remains a separate composite only
when the existing rules would create one because no music driver can own it.

Raw SEGA PCM and WAV assets use decoded sample-backed voices. The SEGA PCM
voice preserves its current replacement/preemption rules. Decoding and
resampling setup occur outside the real-time mixing loop.

### Software voice registry

The registry owns active music and SFX voices and preserves existing policies:

- music replacement and override stack behavior;
- SFX priority, continuous-SFX extension, and stop-all behavior;
- SEGA PCM start/stop commands;
- independent fallback WAV SFX with pitch;
- fallback WAV music looping;
- per-game audio profile routing.

The registry provides deterministic iteration order. Voice creation/removal is
applied at frame boundaries so the mixer never traverses a concurrently
mutating collection. Structural commands are never silently dropped.
Bounded admission uses a 256-entry command queue whose final 32 entries are
reserved for structural commands. Structural commands include play/replace/
stop music, override push/pop/restore, fade, stop-all-SFX, rewind-boundary,
registry ownership, and hard-boundary reset. They are never coalesced.
Redundant pending gain, pitch, speed-shoes, and speed-multiplier changes for the
same target may be coalesced without reordering other commands.

When admitting a structural command to a full queue, the registry first evicts
the oldest queued droppable sample-voice start. If none exists, the game-thread
producer, at an asserted owner-thread/non-rendering boundary, synchronously
drains and applies the entire pending queue in original order after the
permitted same-target scalar coalescing, then admits the new command. This
preserves dependencies such as a speed change preceding music replacement.
External command submission is forbidden while voice rendering is active.
Render-discovered lifecycle changes such as voice completion enter a separate
fixed 64-entry deferred-mutation list that is applied immediately after the
render traversal; completion of more than 64 voices in one tick is collapsed
into one deterministic registry sweep rather than growing or dropping state.
Rendering never drains the external command queue concurrently, and overflow
never blocks on OpenAL or recording. Tests exceed both the normal 224-entry
region and all 32 reserved entries, assert original-order application, and
exercise deferred-mutation overflow.

At most 32 simultaneous sample-backed one-shot SFX voices are admitted; music,
raw SEGA PCM, and SMPS composites have dedicated slots outside that count. An
SFX start at the limit may replace only the lowest-priority older sample SFX
when the new voice has strictly higher priority; otherwise it is rejected with
one warning. It cannot evict music, raw SEGA PCM, an SMPS composite, an
equal/higher-priority SFX, or a structural command. These limits are named
constants and are not new user configuration.

### Audio presentation mixer

The engine presentation tick is the only producer clock. It invokes the
producer exactly once for every presented frame. Neither OpenAL nor recording
may advance a voice, history cursor, or crossfade.

Every tick has one explicit `PresentationMode`:

- `FORWARD`: normal gameplay, legal/title/menu screens, special/bonus stages,
  title cards, editor, and every other non-paused rendered mode;
- `SILENT`: ordinary pause, paused frame-step presentation, and modal
  shader-picker frames;
- `REVERSE`: every frame while held rewind presentation is active.

All modes obtain the exact stereo-frame count from one
`AudioFrameClock(sampleRate, effectiveFrameRate)`, but their mutations differ:

- `FORWARD` applies queued commands at the frame boundary, advances active
  voices, mixes and saturates one packet, appends it to forward history, and
  broadcasts it.
- `SILENT` does not advance voice cursors or append history. It broadcasts a
  newly cleared clock-sized zero packet. Structural commands that must take
  effect while paused are applied without rendering or advancing voices.
- `REVERSE` does not advance voices or append history. It advances only the
  producer-owned reverse cursor and broadcasts that history packet.

OpenAL may aggregate or split these packets into its 1,024-frame device
buffers, but it never requests synthesis or advances a cursor. No voice writes
directly to OpenAL.

### OpenAL PCM sink

LWJGL/OpenAL owns a bounded speaker FIFO containing only final mixer PCM. Its
capacity is two seconds at the negotiated sample rate. It exposes device
initialization, negotiated sample rate, enqueue/update, and cleanup. It does
not decode WAV files or own voice cursors.

The producer never blocks on this FIFO. If it would overrun, the sink discards
the oldest speaker-only packets until at least one second of capacity is free,
retains the newest tail, and reprimes from that tail. It emits at most one
overrun warning per second. History and recording packets are never discarded
by speaker overflow.

No-device and headless sinks consume-and-discard speaker packets immediately;
they do not accumulate a FIFO or backpressure the producer. An OpenAL enqueue
or device-update failure atomically replaces the device sink with the
no-device sink, clears stale speaker packets, and leaves voices, history,
rewind, and capture running.

Entering reverse presentation flushes queued forward OpenAL PCM and reprimes
the device queue from reverse packets before playback resumes. This makes the
audible reverse boundary the selected history position rather than accepting
device-queue latency. Releasing rewind follows the same flush/reprime boundary
after the single reverse-to-forward crossfade packet.

If OpenAL initialization or output fails, the engine switches to a no-device
sink. The mixer, rewind history, and recording remain operational.

### Presentation history and rewind

One history ring stores final audible PCM after all voices are mixed. The
producer-owned audible state is either:

- forward, consuming newly mixed packets; or
- reverse, reading backward from the history ring at the requested rate.

During reverse presentation, forward sample generation is frozen and only
reverse-history packets are broadcast. Gameplay rewind may continue restoring
logical voice-registry snapshots at keyframes, but those restored voices do
not render into history. On release, the selected logical snapshot is
committed, the existing `AudioPresentationPolicy` transient-SFX cleanup is
applied, and one crossfade bridges into newly generated forward PCM.

Logical snapshots include the registry structure and durable voice cursors.
SMPS composites retain the existing `SmpsDriverSnapshot` path. Looping music
voices are restored. Transient WAV/PCM SFX follow the existing transient-SFX
policy: their logical instances may be restored for identity/command
consistency but are stopped at the reverse-release policy boundary rather than
reintroduced into audible forward output. Raw SEGA boot PCM is transient and
uses the same rule.

Starting recording observes the producer's active presentation state:

- in forward playback, the tap starts at the current forward presentation
  boundary;
- during held rewind, it attaches before the next producer-generated reverse
  packet and receives the same packet as the speaker.

Recording never owns or advances a second playback timeline. A diagnostic
cursor fork may copy position, rate, bounds, and history epoch for assertions,
but both consumers receive the one producer-selected packet. History arming,
hard-boundary epoch clearing, stale-cursor silence, and deferred logical
restore behavior remain explicit and test-covered.

### Live recording tap

Shift+O attaches a capture-owned `AudioFrameClock` and a non-consuming tap to
the final presentation stream. It does not:

- replace an audio runtime;
- rebind voices;
- flush OpenAL;
- migrate source cursors;
- change command routing.

Each submitted video frame receives exactly one stereo PCM packet. If no fresh
audio exists for that presented frame, the packet is explicit silence rather
than stale PCM.

The capture sample rate and frame clock are selected before tap attachment. If
attachment fails, `LiveCaptureController` substitutes a
`ClockedSilenceAudioHandle` and still starts video. If a live tap fails while
draining, the controller atomically closes it once, logs once, replaces only
the audio handle with a silence handle at the same clock phase, and continues
monotonic video frame submission. Framebuffer grab, video submission,
temporary-file write, encoder, and mux failures retain the whole-recorder abort
path: an unwritable encoder audio file cannot be recovered merely by producing
silence.

### Sample-backed voice decoding

The deterministic software baseline supports the formats currently accepted by
the OpenAL path:

- unsigned 8-bit PCM and signed little-endian 16-bit PCM;
- mono duplication to stereo and native stereo;
- source sample-rate conversion to the negotiated presentation rate;
- per-voice pitch expressed as a fixed-point source-frame step;
- linear interpolation between source frames;
- exact loop wrapping for music and completion for one-shot SFX;
- existing route gain applied before wide accumulation.

`WavDecoder` validation is tightened so malformed channel counts, bit depths,
rates, or truncated data fail before voice admission. Decoded immutable sample
data is cached by asset identity; voice cursors and pitch are per-instance.
Cache population and resampler setup occur outside the presentation tick.

“Unchanged audio” means command, lifecycle, priority, timing, and audible-source
parity. The deterministic software resampler becomes the new PCM reference;
bit equality with implementation-dependent OpenAL resampling is not required.

## Audio Failure Policy

Audio failure must be graceful and must not stop video recording or gameplay.

| Failure | Behavior |
|---|---|
| OpenAL device unavailable/fails | Switch to no-device sink; mixer and recording continue |
| Recording tap cannot attach | Start video recording with clocked stereo silence |
| Recording tap fails mid-session | Detach failed tap and submit clocked stereo silence for the remaining video |
| One malformed WAV/PCM asset | Warn, reject that voice, continue other voices |
| One software voice throws while rendering | Warn, remove that voice at the frame boundary, continue the mix |
| Encoder audio-file write fails | Abort the whole recorder; the container can no longer be guaranteed |
| Whole recorder/mux fails | Use the existing bounded abort and warning path |

The MKV retains a stereo audio track even when capture audio is unavailable.
Silence length is derived from the capture-owned clock so A/V duration remains
correct.

The sole manual audio-tap failure hook is the development-only JVM property
`openggf.debug.liveCaptureAudioFailAfterFrames=N`. It is disabled when absent
or set to `-1`. A non-negative `N` fails before drain `N + 1`, after exactly
`N` successful recording-handle drains, without changing the producer,
speaker sink, voices, or video path. Malformed values and values below `-1`
warn and use the disabled behavior. Real attachment, drain, and close failures
must follow the same clocked-silence degradation path; the hook does not weaken
that requirement.

## Concurrency and Real-Time Constraints

- Audio commands enter a bounded frame-boundary queue with reserved structural
  capacity, droppable-start eviction, and safe-boundary full-queue draining in
  original order.
- The mixer has one state owner; OpenAL and capture consumers receive immutable
  packet views or copies from bounded reusable pools.
- No decoding, file access, process launch, logging formatting, or collection
  growth occurs inside voice rendering.
- Accumulation uses reusable wide integer buffers and one final saturation pass.
- Voice and queue counts have explicit named bounds. Structural commands are
  never dropped; rejected voice starts warn once.
- Cleanup order is recording tap, voice registry/history, then OpenAL sink.
- Failure cleanup is idempotent.

## Migration

Migration is staged so every commit retains usable audio:

1. Introduce/test the `AudioManager`-owned mixer, composite SMPS adapter, and
   software sample voices without changing LWJGL output.
2. Route SEGA PCM and fallback WAV voices through the software registry while
   retaining legacy output behind parity tests.
3. Move final-packet history/rewind ownership into the manager-owned
   presentation producer.
4. Add speaker FIFO sinks for LWJGL and no-device/headless operation; migrate
   LWJGL to consume only final mixed PCM.
5. Remove legacy independent OpenAL music/SFX sources after an architecture
   guard proves no route uses them.
6. Attach live recording to final presentation packets and add
   `ClockedSilenceAudioHandle` degradation.
7. Preserve `beginCaptureMode` / `drainCaptureFrame` / `endCaptureMode` as
   compatibility APIs over the same producer, and migrate
   `TraceCaptureTool`/`TraceCaptureSession` tests before removing temporary
   runtime switching and handoff code.

The normal playback path must never again be changed by merely toggling
recording.

## Testing

### Unit and component tests

- exact stereo mixing, saturation, gain, pitch, looping, completion, and stop;
- decoded WAV and raw PCM resampling;
- unsigned 8-bit/signed 16-bit, mono/stereo, pitch, interpolation, loop-wrap,
  completion, cache, and malformed-input behavior;
- `SmpsCompositeVoice` channel arbitration, priority, DAC fallback,
  continuous-SFX behavior, and snapshot parity without splitting sequencers;
- stable multi-voice ordering and frame-boundary mutation;
- SMPS music plus simultaneous SMPS/WAV/PCM SFX;
- ring/SFX priority and continuous-SFX behavior;
- exact frame-clock packet sizes for NTSC and PAL;
- OpenAL sink two-second queue bound, oldest-speaker-only overrun discard,
  one-second reprime tail, and rate-limited warning;
- stalled-device, enqueue-failure, no-device long-run, and FIFO-overrun cases
  proving producer/capture continuity without gameplay blocking;
- exact equality between each producer packet and both consumer copies before
  OpenAL aggregation;
- reverse-entry forward-queue flush/reprime and reverse-release ordering;
- non-consuming speaker/recording equality;
- silent capture degradation at attach and mid-session;
- immediate capture start from an active reverse cursor;
- rewind rate changes, release crossfade, epoch reset, and repeated recording;
- ordinary exception and shutdown cleanup;
- command-capacity reservation, safe coalescing, deterministic voice overflow,
  overflow beyond both queue regions, synchronous safe-boundary full-queue
  drain in original order, deferred-mutation overflow, and proof that
  structural commands are never dropped.

### Integration tests

- special-stage rings and SFX remain audible and captured before, during, and
  after Shift+O;
- title and gameplay audio remain present in Sonic 1, Sonic 2, and Sonic 3&K;
- SEGA PCM, fallback WAV music, multiple pitched WAV SFX, and SMPS all appear
  in final captured PCM;
- pause and frame-step submit fresh silence;
- recording toggles do not reset music or voice cursors;
- capture-toggle continuity compares logical voice snapshots and cursors
  immediately before and after attach/detach;
- audio failure produces a valid silent-track MKV while video continues;
- injected tap failure preserves frame index, audio-clock phase, recorder
  state, uninterrupted video, and yields a playable clock-continuous MKV;
- FFmpeg/ffprobe confirm stereo FLAC duration remains within one sample of the
  video duration.
- `TraceCaptureTool` and `TraceCaptureSession` retain their offline API
  behavior through the unified producer.
- source/architecture guards prove no OpenAL source owns music or SFX after
  migration.
- ROM-backed final-PCM assertions cover known title, gameplay, ring, and
  special-stage events rather than only command dispatch.

### Manual ROM-backed matrix

For each supported game:

1. boot through the SEGA/title sequence;
2. enter gameplay and a special stage where available;
3. start recording during active music and repeated ring/SFX playback;
4. hold rewind, start a second recording while rewind remains held, release,
   pause, frame-step, and stop;
5. play both MKVs and confirm uninterrupted audible/captured sources;
6. inject an audio-capture failure and confirm video continues with silence.

Manual validation is required before final approval because prior synthetic
tests failed to expose production OpenAL ownership regressions.

## Acceptance Criteria

1. Normal audio is unchanged when recording is inactive.
2. Starting/stopping recording never changes audible voice availability or
   resets a cursor.
3. Special-stage ring sounds and SFX remain audible while recording.
4. Every audible SMPS, PCM, and fallback WAV source is represented in captured
   PCM.
5. Recording starts immediately during held rewind with synchronized reverse
   audio.
6. Speaker and recorder consume independent views of the same final PCM.
7. Audio capture failure yields correctly timed stereo silence; video and
   gameplay continue.
8. OpenAL owns only final PCM output after migration.
9. Headless/offline capture mixes the same SMPS, WAV, and PCM sources without
   opening an audio device.
10. No-device fallback preserves mixer, history, rewind, and capture state
    across LWJGL initialization failure.
11. All automated suites pass, and the three-game manual matrix passes before
   the branch is considered ready to merge.
