# Unified Audio Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace split software/OpenAL audio ownership with one deterministic software-mixed stereo presentation stream that speakers, rewind, live recording, and offline trace capture observe without changing voice ownership.

**Architecture:** `AudioManager` owns a bounded command queue, deterministic voice registry, `AudioPresentationMixer`, final-PCM history, and a single `AudioPresentationProducer`. SMPS remains composite inside each `SmpsDriver`, sample-backed voices cover raw SEGA PCM and decoded WAV music/SFX, and output devices become packet sinks that never advance audio. Live and offline capture attach non-consuming handles to the same packets; recording audio failure swaps only that handle to clocked silence.

**Tech Stack:** Java 17, JUnit 5/Jupiter, Maven, LWJGL OpenAL, existing SMPS/YM2612/PSG synthesizers, FFmpeg/ffprobe, existing rewind and capture frameworks.

## Global Constraints

- Authoritative design: `docs/architecture/designs/2026-07-23-unified-audio-presentation-design.md`.
- `AudioPresentationMixer` is the sole owner of audible voice cursors.
- The final mixed PCM is the sole presentation truth.
- OpenAL queues that PCM but does not own independent music or SFX playback state.
- An existing `SmpsDriver` is adapted as one `SmpsCompositeVoice`.
- At most 32 simultaneous sample-backed one-shot SFX voices are admitted; music, raw SEGA PCM, and SMPS composites have dedicated slots outside that count.
- Bounded admission uses a 256-entry command queue whose final 32 entries are reserved for structural commands.
- Render-discovered lifecycle changes such as voice completion enter a separate fixed 64-entry deferred-mutation list that is applied immediately after the render traversal; completion of more than 64 voices in one tick is collapsed into one deterministic registry sweep rather than growing or dropping state.
- The outer presented-frame boundary is the only producer clock.
- `Engine.display()` invokes the producer exactly once after all simulation work for
  that display frame and before speaker/capture consumption; `GameLoop.stepInternal()`
  never presents audio.
- Fast-forward may execute multiple simulation steps inside one outer frame, but those
  steps only enqueue commands. They still yield exactly one producer, speaker, capture,
  and audio-clock packet for the outer frame.
- Headless/trace drivers that do not call `Engine.display()` must explicitly invoke one
  equivalent outer presentation boundary per framebuffer/captured frame.
- Neither OpenAL nor recording may advance a voice, history cursor, or crossfade.
- Every tick has one explicit `PresentationMode`: `FORWARD`, `SILENT`, or `REVERSE`.
- `FORWARD` applies queued commands at the frame boundary, advances active voices, mixes and saturates one packet, appends it to forward history, and broadcasts it.
- `SILENT` does not advance voice cursors or append history.
- `REVERSE` does not advance voices or append history.
- LWJGL/OpenAL owns a bounded speaker FIFO containing only final mixer PCM.
- Its capacity is two seconds at the negotiated sample rate.
- The producer never blocks on this FIFO.
- No-device and headless sinks consume-and-discard speaker packets immediately; they do not accumulate a FIFO or backpressure the producer.
- Entering reverse presentation flushes queued forward OpenAL PCM and reprimes the device queue from reverse packets before playback resumes.
- Starting recording observes the producer's active presentation state.
- Recording never owns or advances a second playback timeline.
- Each submitted video frame receives exactly one stereo PCM packet.
- If no fresh audio exists for that presented frame, the packet is explicit silence rather than stale PCM.
- If attachment fails, `LiveCaptureController` substitutes a `ClockedSilenceAudioHandle` and still starts video.
- If a live tap fails while draining, the controller atomically closes it once, logs once, replaces only the audio handle with a silence handle at the same clock phase, and continues monotonic video frame submission.
- The deterministic software baseline supports unsigned 8-bit PCM and signed little-endian 16-bit PCM, mono duplication to stereo and native stereo, source sample-rate conversion to the negotiated presentation rate, per-voice pitch expressed as a fixed-point source-frame step, linear interpolation between source frames, exact loop wrapping for music and completion for one-shot SFX, and existing route gain applied before wide accumulation.
- No decoding, file access, process launch, logging formatting, or collection growth occurs inside voice rendering.
- Accumulation uses reusable wide integer buffers and one final saturation pass.
- Cleanup order is recording tap, voice registry/history, then OpenAL sink.
- Failure cleanup is idempotent.
- Use JUnit 5 only.
- Preserve unrelated worktree changes and linked disassembly resources.
- Before each task, `git status --short --untracked-files=no` must be empty.
- Each task receives an independent plan-compliance and code-quality review before the next dependent task.

---

## File and ownership map

| File | Responsibility |
| --- | --- |
| `audio/presentation/PresentationMode.java` | Explicit `FORWARD`, `SILENT`, `REVERSE` producer mode |
| `audio/presentation/PresentationVoice.java` | Allocation-free stereo contribution, lifecycle, and snapshot contract |
| `audio/presentation/PresentationVoiceSource.java` | Index-based allocation-free ordered voice iteration |
| `audio/presentation/PresentationVoiceSnapshot.java` | Sealed durable cursor/state snapshots for composite and sample voices |
| `audio/presentation/AudioPresentationMixer.java` | Stable-order wide accumulation and one saturation pass |
| `audio/presentation/DecodedPcm.java` | Immutable validated interleaved PCM asset |
| `audio/presentation/DecodedPcmCache.java` | Asset-identity cache populated outside rendering |
| `audio/presentation/SampleBackedVoice.java` | Fixed-point pitch, interpolation, looping/completion, gain, cursor restore |
| `audio/presentation/SmpsCompositeVoice.java` | One `SmpsDriver` composite preserving sequencer arbitration and snapshots |
| `audio/presentation/AudioVoiceRegistry.java` | Dedicated music/PCM/SMPS slots plus bounded sample-SFX admission |
| `audio/presentation/AudioPresentationCommand.java` | Fully resolved frame-boundary mutation commands |
| `audio/presentation/AudioPresentationCommandQueue.java` | 256-entry queue, 32 reserved structural entries, safe coalescing/drain |
| `audio/presentation/AudioPresentationSourceFactory.java` | Pre-render construction of SMPS, WAV, and raw-PCM voices |
| `audio/presentation/SmpsAssetKey.java` | Immutable primitive/key identity for one resolved SMPS SFX route |
| `audio/presentation/ResolvedSmpsSfxSource.java` | Key plus primitive pitch/priority/continuous metadata only |
| `audio/presentation/SmpsSfxInstantiation.java` | Owner-thread cache-only SFX construction boundary used by the registry |
| `audio/smps/SmpsCoordFlagRuntimeState.java` | Game/session-scoped mutable coord-flag counters with snapshot/reset |
| `audio/smps/SmpsCoordFlagHandlerOwner.java` | Supplies shared game handlers backed by the session runtime state |
| `audio/presentation/AudioPresentationCommandResolver.java` | Complete `AudioCommand` to immutable presentation-command mapping |
| `audio/presentation/AudioPresentationFrameView.java` | Reused synchronous final-PCM view with explicit no-retention contract |
| `audio/presentation/AudioPresentationListener.java` | Non-owning final-packet listener |
| `audio/presentation/AudioPresentationProducer.java` | Sole clock, modes, history, reverse cursor, release crossfade, broadcast |
| `audio/presentation/AudioPresentationSnapshot.java` | Registry structure and durable logical voice cursor snapshot |
| `audio/presentation/AudioPresentationDependencyResolver.java` | Recreates decoded/sample and composite voices from snapshot identities |
| `audio/presentation/AudioPresentationParityProbe.java` | Migration-only counters/cursors proving continuous shadow parity |
| `audio/output/AudioPresentationSink.java` | Final-PCM device boundary only |
| `audio/output/NoDeviceAudioSink.java` | Immediate discard sink with no backpressure |
| `audio/output/SpeakerPacketFifo.java` | Two-second bounded speaker-only FIFO and one-second newest-tail reprime |
| `audio/output/OpenAlPcmSink.java` | Device init, PCM aggregation, reverse flush/reprime, failure callback |
| `audio/AudioManager.java` | Owns producer/registry/sink; resolves public audio commands and compatibility APIs |
| `audio/AudioBackend.java` | Transitional source-loader/profile boundary; no presentation ownership after migration |
| `audio/AbstractSmpsAudioBackend.java` | Supplies composite SMPS construction during migration, then loses device/history ownership |
| `audio/LWJGLAudioBackend.java` | Reduced to `OpenAlPcmSink` composition; no WAV/music/SFX sources |
| `audio/HeadlessSmpsAudioBackend.java` | Source/profile compatibility without device behavior |
| `audio/WavDecoder.java` | Strict RIFF PCM validation and immutable decoded output |
| `audio/LiveCaptureAudioHandle.java` | Non-consuming final-packet capture API |
| `audio/ClockedSilenceAudioHandle.java` | Phase-preserving stereo silence fallback |
| `audio/debug/StandaloneAudioPresentationHost.java` | Game-bound isolated manager/session/handler owner for SoundTestApp |
| `audio/runtime/*` | Temporary deterministic-runtime compatibility removed after trace migration |
| `capture/LiveCaptureController.java` | Video lifecycle; audio-only attach/drain failure degradation |
| `capture/DrainPcmAudioTap.java` | Offline compatibility tap over unified producer |
| `tools/TraceCaptureTool.java` | Unified offline capture lifecycle |
| `tools/TraceCaptureSession.java` | Unified offline session lifecycle |
| `GameLoop.java` | Selects one presentation mode for every frame it presents |
| `Engine.java` | Owns the one producer call at the outer display/presentation boundary |
| `GameLoop.java` | Runs one or more simulation steps and reports one outer-frame presentation mode; never presents |
| `TestAudioPresentationArchitectureGuard.java` | Proves no independent OpenAL music/SFX ownership remains |
| `CHANGELOG.md` | User-visible unified playback/capture behavior |
| `docs/status/known-discrepancies.md` | Removes the temporary live-recording audio limitation |
| `docs/architecture/validation/2026-07-23-unified-audio-presentation-report.md` | Automated, media, ROM-matrix, and failure-injection evidence |

## Interface ledger

All later tasks use these exact signatures:

```java
public enum PresentationMode { FORWARD, SILENT, REVERSE }

public interface PresentationVoice {
    long voiceId();
    int priority();
    void mixInto(long[] accumulation, int stereoFrames);
    boolean isComplete();
    void stop();
    PresentationVoiceSnapshot snapshot();
}

public interface PresentationVoiceSource {
    int orderedVoiceCount();
    PresentationVoice orderedVoiceAt(int index);
}

public sealed interface PresentationVoiceSnapshot
        permits PresentationVoiceSnapshot.Smps, PresentationVoiceSnapshot.Sample {
    record Smps(long voiceId, int priority, Integer musicId,
                AudioSourceDescriptor sourceDescriptor, int maxStereoFrames,
                SmpsDriverSnapshot driver) implements PresentationVoiceSnapshot {}
    record Sample(long voiceId, int priority, String assetId, Integer musicId,
                  AudioSourceDescriptor sourceDescriptor, long sourcePositionQ32,
                  long sourceStepQ32, int gainQ16, boolean looping,
                  boolean stopped) implements PresentationVoiceSnapshot {}
}

public final class DecodedPcm {
    public DecodedPcm(String assetId, int channels, int sampleRate, short[] samples);
    public String assetId();
    public int channels();
    public int sampleRate();
    public int sourceFrames();
    public short sample(int sourceFrame, int channel);
    public short[] copySamples();
}

public final class DecodedPcmCache {
    public DecodedPcm getOrDecode(String assetId, Supplier<InputStream> source) throws IOException;
    public DecodedPcm registerUnsigned8Mono(String assetId, byte[] source,
                                           int sourceRate);
    public void clear();
}

public final class SampleBackedVoice implements PresentationVoice {
    public static SampleBackedVoice oneShot(long id, int priority, DecodedPcm pcm,
                                            int outputRate, float pitch, float gain);
    public static SampleBackedVoice loopingMusic(long id, DecodedPcm pcm,
                                                 int outputRate, float gain);
    public static SampleBackedVoice unsigned8Mono(long id, int priority, String assetId,
                                                  byte[] pcm, int sourceRate,
                                                  int outputRate, float gain);
    public void restore(PresentationVoiceSnapshot.Sample snapshot);
}

public final class SmpsCompositeVoice implements PresentationVoice {
    public SmpsCompositeVoice(long id, int priority, SmpsDriver driver,
                              int maxStereoFrames);
    public SmpsDriver driver();
    public void restore(PresentationVoiceSnapshot.Smps snapshot,
                        SmpsDriverSnapshot.DependencyResolver resolver);
}

public sealed interface AudioPresentationCommand {
    boolean structural();
    boolean droppableSampleStart();
    Object coalescingKey();
}

public final class AudioPresentationCommandQueue {
    public static final int CAPACITY = 256;
    public static final int STRUCTURAL_RESERVE = 32;
    public void submit(AudioPresentationCommand command,
                       BooleanSupplier ownerThreadBoundary,
                       Consumer<AudioPresentationCommand> synchronousApply);
    public void applyPending(Consumer<AudioPresentationCommand> applier);
    public int size();
}

public final class AudioVoiceRegistry implements PresentationVoiceSource {
    public static final int MAX_SAMPLE_SFX_VOICES = 32;
    public static final int MAX_DEFERRED_MUTATIONS = 64;
    public void apply(AudioPresentationCommand command);
    public int orderedVoiceCount();
    public PresentationVoice orderedVoiceAt(int index);
    public AudioPresentationSnapshot snapshot();
    public void restore(AudioPresentationSnapshot snapshot,
                        AudioPresentationDependencyResolver resolver);
    public void stopTransientVoices();
    public void clear();
}

public record MusicVoiceEntry(int musicId,
                              AudioSourceDescriptor sourceDescriptor,
                              PresentationVoice voice) {}

public record MusicSlotSnapshot(int musicId,
                                AudioSourceDescriptor sourceDescriptor,
                                long voiceId) {}

public record AudioPresentationSnapshot(
        long nextVoiceId,
        List<PresentationVoiceSnapshot> voices,
        MusicSlotSnapshot activeMusic,
        List<MusicSlotSnapshot> overrideStack,
        Long standaloneSmpsVoiceId,
        Long rawPcmVoiceId,
        int fmMuteMask,
        int fmSoloMask,
        int psgMuteMask,
        int psgSoloMask,
        boolean sfxBlocked,
        boolean pendingRestore,
        boolean speedShoesEnabled,
        int speedMultiplier,
        SmpsCoordFlagRuntimeState.Snapshot coordFlagRuntimeState) {}

public interface AudioPresentationDependencyResolver {
    DecodedPcm resolvePcm(String assetId);
    SmpsCompositeVoice recreateSmps(PresentationVoiceSnapshot.Smps snapshot);
}

public final class AudioPresentationFrameView {
    public int stereoFrames();
    public long presentationFrame();
    public PresentationMode mode();
    public short sampleAt(int stereoFrame, int channel);
    public void copyTo(short[] target, int targetOffsetStereoFrames);
}

@FunctionalInterface
public interface AudioPresentationListener {
    /**
     * The view is valid only for the duration of this synchronous callback.
     * Consumers retaining PCM must copy the active range before returning.
     */
    void onPresentationFrame(AudioPresentationFrameView frame);
}

public interface AudioPresentationSink extends AutoCloseable {
    int sampleRate();
    void accept(AudioPresentationFrameView frame);
    void onReverseBoundary();
    @Override void close();
}

public final class AudioPresentationProducer {
    public AudioPresentationProducer(int sampleRate, int frameRate, int historyFrames,
                                     int crossfadeFrames, AudioVoiceRegistry registry,
                                     AudioPresentationCommandQueue commands,
                                     AudioPresentationMixer mixer,
                                     AudioPresentationSink sink);
    public void present(long commandFrame, PresentationMode mode);
    public LiveCaptureAudioHandle attachCapture(int frameRate);
    public void beginReverse(double rate);
    public void setReverseRate(double rate);
    public void endReverse();
    public void clearHistory();
    public void setHistoryArmed(boolean armed);
    public AudioPresentationSnapshot snapshot();
    public void restore(AudioPresentationSnapshot snapshot,
                        AudioPresentationDependencyResolver resolver,
                        boolean preservePresentation);
    public void replaceSink(AudioPresentationSink sink);
    public void close();
}

public final class ClockedSilenceAudioHandle implements LiveCaptureAudioHandle {
    public ClockedSilenceAudioHandle(int sampleRate, int frameRate);
    public static ClockedSilenceAudioHandle atPhase(AudioFrameClock.Snapshot phase);
    public long totalStereoFrames();
    public AudioFrameClock.Snapshot clockSnapshot();
}

public interface LiveCaptureAudioHandle extends AutoCloseable {
    int sampleRate();
    int frameRate();
    int maxStereoFramesPerPacket();
    int drainPresentationFrame(short[] target);
    long totalStereoFrames();
    AudioFrameClock.Snapshot clockSnapshot();
    @Override void close();
}
```

---

### Task 1: Add allocation-free voice mixing primitives

**Files:**
- Create: `src/main/java/com/openggf/audio/presentation/PresentationMode.java`
- Create: `src/main/java/com/openggf/audio/presentation/PresentationVoice.java`
- Create: `src/main/java/com/openggf/audio/presentation/PresentationVoiceSource.java`
- Create: `src/main/java/com/openggf/audio/presentation/PresentationVoiceSnapshot.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationMixer.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationMixer.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Existing interleaved signed 16-bit stereo convention from `AudioStream`.
- Produces: `PresentationMode`, `PresentationVoice`, `PresentationVoiceSnapshot`, and:

```java
public final class AudioPresentationMixer {
    public AudioPresentationMixer(int maxStereoFrames);
    public short[] mix(PresentationVoiceSource voices, int stereoFrames);
    public int maxStereoFrames();
}
```

- [ ] **Step 1: Write failing ordering, saturation, failure-isolation, and reuse tests**

Create `TestAudioPresentationMixer` with concrete fake voices:

```java
@Test void mixesVoicesInListOrderAndSaturatesOnce() {
    AudioPresentationMixer mixer = new AudioPresentationMixer(2);
    PresentationVoiceSource voices = fixedVoices(
            voice(1, 20_000, -20_000),
            voice(2, 20_000, -20_000));
    short[] out = mixer.mix(voices, 2);
    assertArrayEquals(new short[] {32767, -32768, 32767, -32768}, out);
}

@Test void reusesOutputAndWideAccumulationBuffers() {
    AudioPresentationMixer mixer = new AudioPresentationMixer(4);
    PresentationVoiceSource voices = fixedVoices();
    assertSame(mixer.mix(voices, 4), mixer.mix(voices, 4));
}

@Test void rejectsFramesBeyondDeclaredCapacity() {
    assertThrows(IllegalArgumentException.class,
            () -> new AudioPresentationMixer(2).mix(fixedVoices(), 3));
}

@Test void throwingVoiceCannotLeakItsPartialContribution() {
    PresentationVoice writesThenThrows = voiceThatWritesThenThrows(30_000, 30_000);
    short[] out = new AudioPresentationMixer(1, failed::add)
            .mix(fixedVoices(voice(1, 100, 200), writesThenThrows), 1);
    assertArrayEquals(new short[] {100, 200}, out);
    assertEquals(List.of(writesThenThrows), failed);
}
```

Add a fake that records invocation order and one that throws. Assert the mixer invokes
`1, 2, 3` deterministically, reports the throwing voice through a constructor-injected
`Consumer<PresentationVoice>` failure callback, clears that voice's contribution, and
continues mixing the remaining voices without allocating a replacement output array.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -Dtest=com.openggf.audio.presentation.TestAudioPresentationMixer test
```

Expected: compilation fails because the `audio.presentation` contracts do not exist.

- [ ] **Step 3: Implement the minimal voice and mixer contracts**

Implement the ledger signatures. `AudioPresentationMixer` allocates
`long[] accumulation`, `long[] voiceScratch`, and `short[] output` in its constructor
from `maxStereoFrames`. It validates `0 <= stereoFrames <= maxStereoFrames`, clears only
the active ranges, and for each voice clears `voiceScratch`, asks the voice to render
into that scratch, and merges scratch into accumulation only after the render returns
successfully. A voice that writes and then throws therefore contributes nothing. After
all successful voices, saturate once with:

```java
private static short saturate(long sample) {
    return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
}
```

Provide the tested overload:

```java
AudioPresentationMixer(int maxStereoFrames, Consumer<PresentationVoice> failedVoice)
```

The failure callback defers removal; it must not mutate the list being traversed.
Neither the success nor failure path allocates a per-voice buffer during rendering.
Iteration is an index loop over `PresentationVoiceSource.orderedVoiceCount()` and
`orderedVoiceAt(i)`; it never calls `List.of`, `stream`, `iterator`, `toArray`, or a
registry-side `mixInto`.

- [ ] **Step 4: Run focused tests and the existing synthesis regressions**

Run:

```bash
mvn -Dtest=TestAudioPresentationMixer,AudioRegressionTest,TestSmpsFadeHybridParity test
```

Expected: all selected tests pass; existing playback remains on the legacy backend.

- [ ] **Step 5: Update changelog and commit exact files**

Add under the current unreleased section of `CHANGELOG.md`:

```markdown
- Began consolidating audible sources behind an allocation-free software presentation mixer.
```

Run:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/audio/presentation/PresentationMode.java \
  src/main/java/com/openggf/audio/presentation/PresentationVoice.java \
  src/main/java/com/openggf/audio/presentation/PresentationVoiceSource.java \
  src/main/java/com/openggf/audio/presentation/PresentationVoiceSnapshot.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationMixer.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationMixer.java
git commit -m "feat(audio): add presentation voice mixer

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: Decode and render sample-backed WAV and raw PCM voices

**Files:**
- Create: `src/main/java/com/openggf/audio/presentation/DecodedPcm.java`
- Create: `src/main/java/com/openggf/audio/presentation/DecodedPcmCache.java`
- Create: `src/main/java/com/openggf/audio/presentation/SampleBackedVoice.java`
- Modify: `src/main/java/com/openggf/audio/WavDecoder.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestSampleBackedVoice.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestDecodedPcmCache.java`
- Create: `src/test/java/com/openggf/audio/TestWavDecoderValidation.java`
- Modify: `src/test/java/com/openggf/audio/TestSegaPcmCommandRouting.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 1 `PresentationVoice` and `PresentationVoiceSnapshot.Sample`.
- Produces: `DecodedPcm`, `DecodedPcmCache`, `SampleBackedVoice`, and:

```java
public static DecodedPcm WavDecoder.decodePcm(String assetId, InputStream source)
        throws IOException;
```

- [ ] **Step 1: Write failing WAV validation and conversion tests**

Create in-memory RIFF fixtures for 8-bit mono, 16-bit stereo, and malformed headers.
Use these exact assertions:

```java
@Test void decodesUnsigned8BitMonoToSignedSamples() throws Exception {
    DecodedPcm pcm = WavDecoder.decodePcm("u8", wav(1, 8, 22050,
            new byte[] {0, (byte) 128, (byte) 255}));
    assertEquals(1, pcm.channels());
    assertArrayEquals(new short[] {-32768, 0, 32512}, pcm.copySamples());
}

@Test void rejectsUnsupportedChannelsBitsRatesAndTruncation() {
    assertAll(
        () -> assertThrows(IOException.class, () -> decode(wavHeader(3, 16, 48000, 6), 6)),
        () -> assertThrows(IOException.class, () -> decode(wavHeader(1, 24, 48000, 3), 3)),
        () -> assertThrows(IOException.class, () -> decode(wavHeader(1, 16, 0, 2), 2)),
        () -> assertThrows(IOException.class, () -> decode(declaredDataLargerThanFile(), 0)));
}
```

Also assert block alignment, byte rate, RIFF/chunk bounds, odd-chunk padding, and data
length alignment to complete source frames.

- [ ] **Step 2: Run WAV validation test and verify RED**

Run:

```bash
mvn -Dtest=TestWavDecoderValidation test
```

Expected: compilation fails because `DecodedPcm` and `decodePcm` do not exist.

- [ ] **Step 3: Implement strict decoding and cache**

Implement `DecodedPcm` with defensive construction (`samples.clone()`), positive
channels/rate validation, `sourceFrames() == samples.length / channels`, allocation-free
`sample(frame, channel)` access for voices, and an explicit `copySamples()` clone only
for callers that need ownership. Do not expose the internal array through a record
accessor; rendering must call `sample(...)` and must not clone.
`WavDecoder.decodePcm` supports only PCM format 1, channels 1/2, bits 8/16, positive
sample rate, bounded chunks, and little-endian signed 16-bit conversion.

Implement `DecodedPcmCache` as a bounded-session `HashMap<String, DecodedPcm>`.
`getOrDecode` calls the supplier and decoder only on a miss; `clear()` is invoked at
game/profile teardown, never from render. `registerUnsigned8Mono` validates the identity
and rate, converts/copies the supplied bytes immediately into an immutable `DecodedPcm`,
and rejects a second registration whose metadata/content differs. Raw SEGA PCM voices
retain only that registered asset ID plus their cursor; neither commands nor snapshots
retain a mutable caller `byte[]`.

- [ ] **Step 4: Write failing pitch, interpolation, looping, completion, gain, and restore tests**

Create `TestSampleBackedVoice`:

```java
@Test void monoDuplicatesAndLinearInterpolationUsesFixedPointStep()
@Test void stereoChannelsRemainIndependent()
@Test void pitchChangesSourceFrameStepWithoutChangingOutputPacketSize()
@Test void loopingMusicWrapsExactlyAcrossPacketBoundary()
@Test void oneShotCompletesAndStopIsExplicit()
@Test void gainIsAppliedBeforeWideAccumulation()
@Test void snapshotRestoreReproducesTheNextPacketBitExactly()
@Test void unsignedRawSegaPcmUsesExistingYmDacGain()
@Test void rawPcmRegistrationCopiesCallerBytesAndKeepsStableAssetIdentity()
```

For the interpolation fixture, render source `[0, 1000, 2000]` at half-speed and
assert stereo output `[0,0, 500,500, 1000,1000, 1500,1500]`. For restore, snapshot
after three output frames, render four, restore, and assert the next four are identical.

- [ ] **Step 5: Run sample voice tests and verify RED**

Run:

```bash
mvn -Dtest=TestSampleBackedVoice,TestDecodedPcmCache test
```

Expected: compilation fails because `SampleBackedVoice` and `DecodedPcmCache` do not exist.

- [ ] **Step 6: Implement sample voices without render-time allocation**

Use Q32.32 cursor math:

```java
long sourceStepQ32 =
        Math.max(1L, Math.round((sourceRate * (double) pitch / outputRate) * (1L << 32)));
int index = (int) (sourcePositionQ32 >>> 32);
long fraction = sourcePositionQ32 & 0xFFFF_FFFFL;
int interpolated = left + (int) (((long) (right - left) * fraction) >> 32);
accumulation[out] += ((long) interpolated * gainQ16) >> 16;
```

Looping voices wrap Q32.32 position modulo `((long) sourceFrames) << 32`; one-shots mark
complete at end and contribute zero thereafter. Keep `PcmSampleStream` as a
transitional legacy-backend adapter until Task 13. Update
`TestSegaPcmCommandRouting` with sample parity assertions against the new raw voice and
restore-after-removal coverage through `DecodedPcmCache`; no production route changes
in this commit.

- [ ] **Step 7: Verify focused tests and commit exact files**

Run:

```bash
mvn -Dtest=TestWavDecoderValidation,TestSampleBackedVoice,TestDecodedPcmCache,TestSegaPcmCommandRouting test
```

Expected: all selected tests pass.

Add to the same unreleased changelog section:

```markdown
- Added deterministic WAV and raw-PCM decoding, interpolation, looping, pitch, and cursor restoration.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/audio/WavDecoder.java \
  src/main/java/com/openggf/audio/presentation/DecodedPcm.java \
  src/main/java/com/openggf/audio/presentation/DecodedPcmCache.java \
  src/main/java/com/openggf/audio/presentation/SampleBackedVoice.java \
  src/test/java/com/openggf/audio/TestWavDecoderValidation.java \
  src/test/java/com/openggf/audio/TestSegaPcmCommandRouting.java \
  src/test/java/com/openggf/audio/presentation/TestSampleBackedVoice.java \
  src/test/java/com/openggf/audio/presentation/TestDecodedPcmCache.java
git commit -m "feat(audio): add deterministic sample voices

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: Adapt each complete SMPS driver as one composite voice

**Files:**
- Create: `src/main/java/com/openggf/audio/presentation/SmpsCompositeVoice.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestSmpsCompositeVoice.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestSmpsDriverSnapshot.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `SmpsDriver.read(short[], int)`, `captureSnapshot()`,
  `restoreSnapshot(...)`, `stopAll()`, and Task 1 voice contracts.
- Produces: ledger `SmpsCompositeVoice`; music plus its SFX sequencers stay in one driver.

- [ ] **Step 1: Write failing composite rendering and snapshot tests**

Use the existing `AudioTestFixtures` SMPS fixtures and assert:

```java
@Test void musicAndOwnedSfxRenderThroughOneComposite()
@Test void driverChannelLocksAndPriorityRemainInsideComposite()
@Test void dacFallbackAndContinuousSfxRemainInsideComposite()
@Test void standaloneSfxDriverIsASeparateCompositeOnlyWithoutMusicOwner()
@Test void snapshotRestoreReproducesDriverStateAndNextPcm()
@Test void stopDelegatesToDriverStopAll()
```

The first test must inspect `voice.driver()` identity before and after adding SFX and
assert no second presentation voice is created. The standalone test must construct the
existing no-music SFX path and assert exactly one separate composite.
Construct every fixture with an explicit `maxStereoFrames`; assert a request above that
capacity fails before calling `SmpsDriver.read`.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
mvn -Dtest=TestSmpsCompositeVoice test
```

Expected: compilation fails because `SmpsCompositeVoice` does not exist.

- [ ] **Step 3: Implement the composite adapter**

Accept `maxStereoFrames` in the constructor and allocate one reusable
`short[maxStereoFrames * 2]` scratch outside rendering.
`mixInto` clears only the active stereo range, calls `driver.read(scratch, frames * 2)`,
adds returned samples to `long[]`, and marks complete from `driver.isComplete()`.
Snapshot delegates to `driver.captureSnapshot()` and restore delegates to:

```java
driver.restoreSnapshot(snapshot.driver(), resolver);
```

Do not copy sequencers into standalone voices and do not reproduce channel arbitration,
priority, DAC, or continuous-SFX logic in the adapter.

- [ ] **Step 4: Run composite and existing SMPS parity suites**

Run:

```bash
mvn -Dtest=TestSmpsCompositeVoice,TestSmpsDriverSnapshot,TestSmpsFadeHybridParity,TestSmpsFadeAudioThroughput,TestSonic1SmpsLoaderSfxDispatchBoundary test
```

Expected: all selected tests pass and snapshot parity remains unchanged.

- [ ] **Step 5: Update changelog and commit exact files**

Add:

```markdown
- Preserved SMPS music, SFX arbitration, DAC, and continuous effects as one composite presentation voice.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/audio/presentation/SmpsCompositeVoice.java \
  src/test/java/com/openggf/audio/presentation/TestSmpsCompositeVoice.java \
  src/test/java/com/openggf/audio/driver/TestSmpsDriverSnapshot.java
git commit -m "feat(audio): adapt SMPS as a composite voice

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 4: Add the bounded command queue and deterministic voice registry

**Files:**
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationCommand.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationCommandQueue.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioVoiceRegistry.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationSnapshot.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationDependencyResolver.java`
- Create: `src/main/java/com/openggf/audio/presentation/SmpsAssetKey.java`
- Create: `src/main/java/com/openggf/audio/presentation/SmpsSfxInstantiation.java`
- Create: `src/main/java/com/openggf/audio/presentation/ResolvedSmpsSfxSource.java`
- Create: `src/main/java/com/openggf/audio/smps/SmpsCoordFlagRuntimeState.java`
- Create: `src/main/java/com/openggf/audio/smps/SmpsCoordFlagHandlerOwner.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandQueue.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestAudioVoiceRegistry.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 2 sample voices and Task 3 composite voices.
- Produces: ledger command queue/registry/snapshot and these exact command records:

```java
public record SmpsAssetKey(String gameId, Route route,
                           int sfxId, String sfxName) {
    public enum Route { BASE_ID, BASE_NAME, DONOR_ID, FALLBACK_NAME }
}

public record ResolvedSmpsSfxSource(
        long standaloneVoiceId,
        SmpsAssetKey assetKey,
        int pitchQ16,
        int priority,
        int continuousSfxId,
        int trackCount,
        int maxStereoFrames) {}

public interface SmpsSfxInstantiation {
    SmpsSequencer instantiateCached(ResolvedSmpsSfxSource source,
                                    SmpsDriver currentOwner);
    SmpsCompositeVoice instantiateStandaloneCached(ResolvedSmpsSfxSource source);
}

public final class SmpsCoordFlagRuntimeState {
    public record Snapshot(int spindashRevCounter) {}
    public int spindashRevCounter();
    public void setSpindashRevCounter(int value);
    public Snapshot snapshot();
    public void restore(Snapshot snapshot);
    public void reset();
}

public final class SmpsCoordFlagHandlerOwner {
    public SmpsCoordFlagHandlerOwner(SmpsCoordFlagRuntimeState state);
    public void register(String gameId,
            Function<SmpsCoordFlagRuntimeState, CoordFlagHandler> factory);
    public CoordFlagHandler handlerFor(String gameId);
    public SmpsCoordFlagRuntimeState state();
    public void reset();
}

record ReplaceMusic(MusicVoiceEntry music) implements AudioPresentationCommand {}
record PushMusicOverride(MusicVoiceEntry music) implements AudioPresentationCommand {}
record RestoreMusicOverride() implements AudioPresentationCommand {}
record EndMusicOverride(int musicId) implements AudioPresentationCommand {}
record AddSmpsSfx(ResolvedSmpsSfxSource source) implements AudioPresentationCommand {}
record StartSampleSfx(SampleBackedVoice voice) implements AudioPresentationCommand {}
record ReplaceRawPcm(SampleBackedVoice voice) implements AudioPresentationCommand {}
record StopRawPcm() implements AudioPresentationCommand {}
record StopMusic() implements AudioPresentationCommand {}
record StopAllSfx() implements AudioPresentationCommand {}
record FadeMusic(int steps, int delay) implements AudioPresentationCommand {}
record SetVoiceGain(long voiceId, int gainQ16) implements AudioPresentationCommand {}
record SetVoicePitch(long voiceId, long sourceStepQ32) implements AudioPresentationCommand {}
record SetSpeedShoes(boolean enabled) implements AudioPresentationCommand {}
record SetSpeedMultiplier(int multiplier) implements AudioPresentationCommand {}
record ChangeMusicTempo(int dividingTiming) implements AudioPresentationCommand {}
record ResetRingAlternation(boolean ringLeft) implements AudioPresentationCommand {}
record ToggleMute(ChannelType type, int channel) implements AudioPresentationCommand {}
record ToggleSolo(ChannelType type, int channel) implements AudioPresentationCommand {}
record RewindBoundary() implements AudioPresentationCommand {}
record HardReset() implements AudioPresentationCommand {}
```

Every music slot and override-stack entry therefore carries `musicId`,
`AudioSourceDescriptor`, and `voiceId` together; an override never relies on a global
"current music" side channel. Every existing `AudioCommand` has an explicit resolved
presentation record, including
`EndMusicOverride`, `ChangeMusicTempo`, and `ResetRingAlternation`. Commands contain
only immutable scalar/descriptor identity, decoded sample voices, or a key plus
primitive SMPS SFX metadata. They never contain `AbstractSmpsData`, `DacData`,
`SmpsSequencerConfig`, a mutable handler, a prebuilt SFX `SmpsSequencer`, `Consumer`,
`Runnable`, or another callback.

All except `StartSampleSfx`, `SetVoiceGain`, `SetVoicePitch`, `SetSpeedShoes`, and
`SetSpeedMultiplier` are structural. Only `StartSampleSfx` is droppable. Scalar
commands coalesce only with the same record type and target key.

- [ ] **Step 1: Write failing capacity, reserve, eviction, coalescing, and ordering tests**

Create tests with numbered commands:

```java
@Test void normalCommandsCannotConsumeFinalThirtyTwoStructuralSlots()
@Test void structuralAdmissionEvictsOldestDroppableSampleStart()
@Test void fullStructuralQueueSynchronouslyDrainsAtAssertedOwnerBoundary()
@Test void fullStructuralQueueRejectsSubmissionDuringRendering()
@Test void sameTargetScalarCommandsCoalesceWithoutCrossingAnotherCommand()
@Test void moreThanBothQueueRegionsAppliesEveryStructuralCommandInOriginalOrder()
@Test void renderingNeverDrainsExternalCommands()
@Test void everyAudioCommandVariantHasOneResolvedPresentationCommand()
@Test void resolvedCommandsContainNoConsumerRunnableOrMutableClosure()
```

For overflow, fill all 256 entries with structural commands, submit entry 257 with
`ownerThreadBoundary -> true`, and assert the apply log is `0..255` before entry 256
is admitted. With the supplier returning false, assert `IllegalStateException`.

- [ ] **Step 2: Run queue tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationCommandQueue test
```

Expected: compilation fails because queue and commands do not exist.

- [ ] **Step 3: Implement the fixed queue**

Use fixed `AudioPresentationCommand[] entries = new AudioPresentationCommand[256]`;
do not use a growing collection. Reserve indexes `224..255` for structural admission.
On structural full-queue admission:

1. scan from oldest to newest and remove the oldest `droppableSampleStart()`;
2. if none exists, assert `ownerThreadBoundary.getAsBoolean()`;
3. synchronously apply all pending entries in original order;
4. admit the structural command.

Coalescing scans backward only until a non-coalescible command boundary; replace a
same-key scalar in place. `applyPending` is forbidden while registry rendering is active.

- [ ] **Step 4: Write failing registry admission, ordering, and deferred completion tests**

Create `TestAudioVoiceRegistry`:

```java
@Test void iterationOrderIsMusicThenSmpsThenRawPcmThenSampleSfxByVoiceId()
@Test void thirtySecondSampleSfxIsAdmittedAndThirtyThirdEqualPriorityIsRejected()
@Test void higherPrioritySampleReplacesOnlyOldestStrictlyLowerPrioritySample()
@Test void sampleOverflowCannotEvictMusicRawPcmOrSmpsComposite()
@Test void rawPcmReplacementStopsThePriorRawPcmVoice()
@Test void stopAllSfxPreservesMusic()
@Test void sixtyFourCompletionsUseDeferredSlots()
@Test void sixtyFiveCompletionsCollapseIntoOneDeterministicSweep()
@Test void throwingVoiceIsWarnedAndRemovedAtFrameBoundary()
@Test void snapshotRestorePreservesStructureAndDurableCursors()
@Test void restoreIntoEmptyRegistryRecreatesEveryDedicatedAndSampleSlot()
@Test void snapshotIncludesMusicIdentityDescriptorMuteSoloAndOverrideFlags()
@Test void nestedFallbackWavOverridesEndByMusicIdAndRestoreTheirOwnSlotMetadata()
@Test void removedRawPcmVoiceRecreatesFromItsRegisteredImmutableAsset()
@Test void sameBoundaryMusicReplacementThenSfxAttachesToTheReplacementDriver()
@Test void sameBoundaryPushRestoreThenSfxAttachesToTheFinalRestoredDriver()
@Test void cacheMissRejectsOnlyThatSfxStartDeterministicallyWithoutIo()
@Test void overlappingNoMusicSfxReuseOneStandaloneCompositeAndDriverArbitration()
@Test void continuousRetriggerExtendsMusicOwnerWithoutCreatingSequencer()
@Test void continuousRetriggerExtendsStandaloneOwnerWithoutCreatingSequencer()
@Test void continuousExtensionReturnsBeforeAConfiguredFailingCacheLookup()
@Test void coordFlagRuntimeStateSnapshotsRestoresAndResetsWithRegistryLifecycle()
@Test void registryExposesOnlyIndexedPreallocatedVoiceStorageNotAMixBypass()
```

Inject a warning consumer and assert one rejection warning per rejected voice id.

- [ ] **Step 5: Run registry tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioVoiceRegistry test
```

Expected: compilation fails because registry and snapshot do not exist.

- [ ] **Step 6: Implement registry slots and fixed deferred mutations**

Use dedicated fields for active music, override stack, standalone SMPS, raw PCM; use a
fixed `SampleBackedVoice[32]` for one-shots and a fixed `long[64]` deferred-removal
array. Stable mixing order is dedicated music/composite slots first, then sample SFX
sorted at admission by monotonically increasing `voiceId`.
Maintain a fixed `PresentationVoice[] orderedVoices` plus integer
`orderedVoiceCount`, rebuilt only at frame-boundary mutation. `orderedVoiceAt(i)` reads
that array directly. The registry has no `mixInto`; only
`AudioPresentationMixer.mix(registry, frames)` calls `PresentationVoice.mixInto`, so
transactional scratch/failure isolation cannot be bypassed.

When deferred removals exceed 64, set `completionSweepRequired`; after traversal,
perform one fixed-array sweep of every registry slot. Never grow a collection or remove
during traversal.

Apply commands strictly in queue order at the non-rendering outer-frame boundary.
Inject `SmpsSfxInstantiation` and one game/session-scoped
`SmpsCoordFlagHandlerOwner` into the registry. For `AddSmpsSfx`, select the owner in
this exact order: active music composite, otherwise the existing standalone SMPS
composite, otherwise no owner. Before construction or any other mutation, if an owner
exists call
`owner.driver().extendContinuousSfx(source.continuousSfxId(), source.trackCount())`;
when it returns true, return immediately without cache lookup, sequencer construction,
or duplicate attachment. Otherwise instantiate and attach against the selected driver;
only when no owner exists call `instantiateStandaloneCached(source)` and install that
single standalone composite. Thus overlapping no-music SFX share one driver and retain
its channel/priority arbitration.

Both instantiation methods assert the owner thread/non-rendering boundary. A missing
cache key rejects/removes only that start with one deterministic warning and performs
no loader, ROM, file, decode, or fallback work. This intentionally occurs after earlier
same-boundary `ReplaceMusic`, `PushMusicOverride`, or `RestoreMusicOverride`, so the
final driver receives or extends the SFX.

Snapshots contain every recreation input in the ledger: every active/override
`MusicSlotSnapshot(musicId, sourceDescriptor, voiceId)`, all voice snapshots,
standalone/raw slot IDs, mute/solo masks, `sfxBlocked`, `pendingRestore`, speed state,
next voice ID, and `SmpsCoordFlagRuntimeState.Snapshot`. Sample snapshots repeat
nullable music ID/source metadata so nested
fallback-WAV overrides remain self-describing when an `EndMusicOverride(id)` removes an
inner entry and later restore recreates the outer entry. `restore` first stops and
removes all live voices, then uses
`AudioPresentationDependencyResolver.resolvePcm(assetId)` for both decoded WAV and
registered immutable raw PCM data, and `recreateSmps(snapshot)` for composites; the
resolver must honor the snapshot's declared `maxStereoFrames` when it constructs each
composite. Tests must restore into a newly empty registry—not mutate surviving voice
instances—and compare the next ten packets and all slot/flag identities.
`clear`/hard reset resets the shared coord-flag runtime state; ordinary music/SFX stop,
override changes, and cache reconstruction do not. Restore applies the coord-flag
snapshot before recreating sequencers so all reconstructed configs observe the restored
counter. Handler registrations/identities live for the game session, survive a hard
audio reset with their state zeroed, and are discarded only with the session owner.

- [ ] **Step 7: Verify queue/registry/voice suites**

Run:

```bash
mvn -Dtest=TestAudioPresentationCommandQueue,TestAudioVoiceRegistry,TestAudioPresentationMixer,TestSampleBackedVoice,TestSmpsCompositeVoice test
```

Expected: all selected tests pass.

- [ ] **Step 8: Update changelog and commit exact files**

Add:

```markdown
- Added bounded frame-boundary audio commands and deterministic voice admission without dropping structural state.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/audio/presentation/AudioPresentationCommand.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationCommandQueue.java \
  src/main/java/com/openggf/audio/presentation/AudioVoiceRegistry.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationSnapshot.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationDependencyResolver.java \
  src/main/java/com/openggf/audio/presentation/SmpsAssetKey.java \
  src/main/java/com/openggf/audio/presentation/SmpsSfxInstantiation.java \
  src/main/java/com/openggf/audio/presentation/ResolvedSmpsSfxSource.java \
  src/main/java/com/openggf/audio/smps/SmpsCoordFlagRuntimeState.java \
  src/main/java/com/openggf/audio/smps/SmpsCoordFlagHandlerOwner.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandQueue.java \
  src/test/java/com/openggf/audio/presentation/TestAudioVoiceRegistry.java
git commit -m "feat(audio): bound presentation voice commands

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 5: Make one allocation-free producer own cadence, final PCM, history, rewind, and taps

**Files:**
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationFrameView.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationListener.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationProducer.java`
- Create: `src/main/java/com/openggf/audio/output/AudioPresentationSink.java`
- Create: `src/main/java/com/openggf/audio/output/NoDeviceAudioSink.java`
- Modify: `src/main/java/com/openggf/audio/runtime/PcmHistoryRing.java`
- Modify: `src/main/java/com/openggf/audio/runtime/PresentationAudioCapture.java`
- Modify: `src/main/java/com/openggf/audio/runtime/LiveAudioCaptureTap.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/audio/LiveCaptureAudioHandle.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationProducer.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationProducerRewind.java`
- Create: `src/test/java/com/openggf/audio/output/TestNoDeviceAudioSink.java`
- Modify: `src/test/java/com/openggf/audio/AudioManagerLiveCaptureTest.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioManagerRuntimeInstallation.java`
- Modify: `src/test/java/com/openggf/capture/LiveCaptureControllerTest.java`
- Modify: `src/test/java/com/openggf/capture/LiveCapturePresentationCoordinatorTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `AudioFrameClock`, `PcmHistoryRing`, Task 4 registry/queue, Task 1
  mixer, and the ledger interfaces.
- Produces: ledger producer, reusable synchronous frame view, listener, sink, and
  no-device sink.

`LiveCaptureAudioHandle` retains its public methods and requires every implementation
and test double to add:

```java
long totalStereoFrames();
AudioFrameClock.Snapshot clockSnapshot();
```

There is no lossy default. The producer handle and all doubles return the exact capture
clock phase.

During the migration, add the same phase method to `PresentationAudioCapture` and
`LiveAudioCaptureTap`, and have `AudioManager.ManagerLiveCaptureAudioHandle` delegate
to it. Update every anonymous/test implementation found by:

```bash
rg -l "LiveCaptureAudioHandle" src/main src/test
```

Expected list is exactly the production interface/manager/controller plus
`AudioManagerLiveCaptureTest`, `TestAudioManagerRuntimeInstallation`,
`LiveCaptureControllerTest`, and `LiveCapturePresentationCoordinatorTest`.

- [ ] **Step 1: Write failing single-clock mode and packet-equality tests**

Create `TestAudioPresentationProducer`:

```java
@Test void sixtyNtscPacketsAt48000ContainExactly48000StereoFrames()
@Test void fiftyPalPacketsAt48000ContainExactly48000StereoFrames()
@Test void forwardAppliesCommandsMixesAppendsHistoryAndBroadcastsOnce()
@Test void silentBroadcastsFreshClockSizedZerosWithoutAdvancingVoiceOrHistory()
@Test void reverseBroadcastsHistoryWithoutAdvancingVoiceOrAppendingHistory()
@Test void sinkAndTwoCaptureHandlesReceiveEqualCopiesOfOneProducerPacket()
@Test void captureDrainNeverAdvancesProducerAndSecondDrainReturnsFreshSilence()
@Test void noDeviceSinkDoesNotAccumulateOrBackpressureAcrossOneHour()
@Test void everyPresentedFrameReusesTheSameSynchronousViewObject()
@Test void retainedConsumersMustCopyBecauseTheViewMutatesOnTheNextPresent()
@Test void warmedProducerAllocatesNoFramePacketOrConsumerArray()
@Test void frameViewExposesSampleAtAndCopyToButNoRawSampleArray()
@Test void sampleAtCopyToSpeakerAndTwoCaptureCopiesAreBitEqual()
```

Use a counting voice, counting sink, and two capture handles. For each presented frame,
assert voice render count increments only in `FORWARD`, sink accept count increments
exactly once in every mode, and each handle returns the same samples through its own
constructor-allocated buffer. Assert `onPresentationFrame` is synchronous, the same view
identity is reused, and sinks/listeners copy before returning if they retain PCM.
The view keeps its backing array private; tests read through `sampleAt(frame, channel)`
and copy through `copyTo(preallocatedTarget, offset)`.

- [ ] **Step 2: Run producer tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationProducer,TestNoDeviceAudioSink test
```

Expected: compilation fails because producer/frame-view/sink do not exist.

- [ ] **Step 3: Implement producer forward and silent modes**

The producer preallocates:

```java
int maxFrames = (sampleRate + frameRate - 1) / frameRate;
short[] silence = new short[maxFrames * 2];
AudioFrameClock clock = new AudioFrameClock(sampleRate, frameRate);
```

`present(commandFrame, FORWARD)` obtains one count from `clock`, applies the entire
pending queue in original order, mixes, writes history only when armed, populates the
one preallocated `AudioPresentationFrameView`, and broadcasts it synchronously.
`SILENT` also applies the entire pending queue in original order—including sample
starts and scalar changes—but does not render or advance any resulting voice. It clears
the active portion of `silence`, skips history, and broadcasts. The producer guards
against reentrant `present` and asserts it is called by its owner thread.

Each attached capture owns an `AudioFrameClock(sampleRate, frameRate)` and one pending
packet buffer. On broadcast it calls `frame.copyTo(pending, 0)`. On drain it asks its
clock for the required count, copies the pending packet if it is fresh, zero-pads any
shortfall, clears freshness, increments `totalStereoFrames`, and returns the required
count. No listener changes producer cadence.

No `AudioPresentationPacket`, `record`, list, array, or wrapper is constructed by
`present`. The producer mutates one constructor-allocated frame view; consumers may
read it only during their callback.

- [ ] **Step 4: Add SILENT queue-order tests**

Add:

```java
@Test void silentAppliesStructuralSampleAndScalarCommandsInOriginalOrder()
@Test void silentStartThenStopLeavesNoVoiceWithoutRenderingEitherCommand()
@Test void silentSpeedChangeThenMusicReplacementPreservesDependencyOrder()
```

Queue `StartSampleSfx(A)`, `SetSpeedMultiplier(2)`, `ReplaceMusic(B)`, and
`StopAllSfx`; present `SILENT`; assert the apply log has that exact order, neither A nor
B rendered, and the resulting registry contains B with multiplier 2 and no transient A.

- [ ] **Step 5: Write failing rewind boundary, rate, epoch, and crossfade tests**

Create `TestAudioPresentationProducerRewind`:

```java
@Test void reverseEntryFlushesSinkBeforeFirstReversePacket()
@Test void reverseRateChangesOnlyProducerOwnedCursor()
@Test void captureAttachedDuringHeldRewindGetsTheNextSameReversePacket()
@Test void reverseDoesNotRenderOrAppendForwardHistory()
@Test void releaseCommitsSelectedLogicalSnapshotAndCrossfadesExactlyOnce()
@Test void hardBoundaryEpochMakesStaleReverseCursorReturnSilence()
@Test void repeatedForwardReverseAndCaptureAttachDetachPreserveOneTimeline()
```

Record sink events and assert exact order:

```java
assertEquals(List.of("forward", "reverse-boundary", "reverse",
                     "reverse-boundary", "crossfade", "forward"), sink.events());
```

For immediate capture, call `beginReverse(1.0)`, present one reverse frame, attach,
present the next reverse frame, then assert speaker and capture PCM are equal.

- [ ] **Step 6: Run rewind tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationProducerRewind test
```

Expected: tests fail because reverse mode and sink boundaries are not implemented.

- [ ] **Step 7: Implement producer-owned reverse state and epoch behavior**

Move all reverse cursor ownership to the producer. Extend `PcmHistoryRing.ReverseCursor`
with package-neutral diagnostics:

```java
public record CursorState(double sourceFrame, long oldestReadableFrame,
                          double rate, long epoch) {}
public CursorState state();
```

`beginReverse(rate)` creates one producer cursor and calls `sink.onReverseBoundary()`.
`present(..., REVERSE)` reads the clock-sized packet from that cursor, zero-fills an
exhausted/stale tail, and broadcasts. `endReverse()` commits the cursor, records the last
reverse stereo frame, arms one release crossfade, and calls `sink.onReverseBoundary()`.
The first next `FORWARD` packet applies the configured sample-count crossfade, then
normal output resumes. Capture attachment copies no cursor; it simply subscribes before
the next producer packet.

- [ ] **Step 8: Verify focused producer/history/runtime tests**

Run:

```bash
mvn -Dtest=TestAudioPresentationProducer,TestAudioPresentationProducerRewind,TestNoDeviceAudioSink,TestAudioRingBuffers,TestPcmHistoryRing,TestLiveAudioCaptureTap test
```

Expected: all selected tests pass. Legacy runtime tests remain green because production
audio has not switched to the new producer.

- [ ] **Step 9: Update changelog and commit exact files**

Add:

```markdown
- Centralized final-PCM cadence, silence, history, reverse playback, crossfade, and non-consuming taps in one producer.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/audio/LiveCaptureAudioHandle.java \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/main/java/com/openggf/audio/output/AudioPresentationSink.java \
  src/main/java/com/openggf/audio/output/NoDeviceAudioSink.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationFrameView.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationListener.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationProducer.java \
  src/main/java/com/openggf/audio/runtime/PcmHistoryRing.java \
  src/main/java/com/openggf/audio/runtime/PresentationAudioCapture.java \
  src/main/java/com/openggf/audio/runtime/LiveAudioCaptureTap.java \
  src/test/java/com/openggf/audio/AudioManagerLiveCaptureTest.java \
  src/test/java/com/openggf/audio/TestAudioManagerRuntimeInstallation.java \
  src/test/java/com/openggf/capture/LiveCaptureControllerTest.java \
  src/test/java/com/openggf/capture/LiveCapturePresentationCoordinatorTest.java \
  src/test/java/com/openggf/audio/output/TestNoDeviceAudioSink.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationProducer.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationProducerRewind.java
git commit -m "feat(audio): centralize presentation packet production

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 6: Build source factories and prove component parity without production routing

**Files:**
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationCommandResolver.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSmpsSequencerConfig.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandResolver.java`
- Create: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationSourceParity.java`
- Modify: `src/test/java/com/openggf/audio/TestDonorAudioRouting.java`
- Modify: `src/test/java/com/openggf/audio/TestSegaPcmCommandRouting.java`
- Modify: `src/test/java/com/openggf/tests/TestSonic3kCoordFlagParity.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Existing loader/profile routing in `AudioManager`, Task 2 cache/sample
  voices, Task 3 composites, and Task 4 queue/registry.
- Produces:

```java
public final class AudioPresentationSourceFactory implements SmpsSfxInstantiation {
    public AudioPresentationSourceFactory(BooleanSupplier ownerThreadBoundary,
            SmpsCoordFlagHandlerOwner coordFlagHandlers);
    public MusicVoiceEntry musicSmps(String gameId, int musicId, long voiceId,
            AbstractSmpsData data,
            DacData dac, SmpsSequencerConfig config, AudioSourceDescriptor descriptor,
            int maxStereoFrames);
    public void warmSmpsSfxAsset(SmpsAssetKey key, AbstractSmpsData data,
            DacData dac, SmpsSequencerConfig config);
    public ResolvedSmpsSfxSource resolveSmpsSfx(long standaloneVoiceId,
            SmpsAssetKey assetKey, int pitchQ16, int priority, int continuousSfxId,
            int trackCount, int maxStereoFrames);
    @Override public SmpsSequencer instantiateCached(
            ResolvedSmpsSfxSource source, SmpsDriver currentOwner);
    @Override public SmpsCompositeVoice instantiateStandaloneCached(
            ResolvedSmpsSfxSource source);
    public SmpsCompositeVoice recreateSmps(PresentationVoiceSnapshot.Smps snapshot,
            SmpsDriverSnapshot.DependencyResolver dependencies);
    public MusicVoiceEntry fallbackMusic(long voiceId, int musicId,
            AudioSourceDescriptor descriptor) throws IOException;
    public SampleBackedVoice fallbackSfx(long voiceId, String name,
            int priority, float pitch) throws IOException;
    public SampleBackedVoice segaPcm(long voiceId, DecodedPcm registeredPcm);
}

public final class AudioPresentationCommandResolver {
    public void submit(AudioCommand command);
    public void submitRawPcm(byte[] pcm, int sourceRate);
    public void stopRawPcm();
}
```

This task does not install a producer, alter `AudioManager`, or route a production
command. Legacy OpenAL remains the only audible path.

- [ ] **Step 1: Write failing complete route-resolution tests**

Create `TestAudioPresentationCommandResolver` with a fake loader/factory and assert one
resolved queue command for every `AudioCommand` record and route:

```java
@Test void resolvesBaseDonorAndFallbackMusic()
@Test void resolvesBaseNameBaseIdDonorFallbackAndAlternatingRingSfx()
@Test void musicOwnedSmpsSfxAttachesOnlyWhenRegistryAppliesCommand()
@Test void noMusicSmpsSfxCreatesOneStandaloneComposite()
@Test void resolvesFadeStopRestoreTempoSpeedAndOverrideCommands()
@Test void resolvesEndOverrideTempoAndRingResetToExplicitImmutableRecords()
@Test void resolvesRawSegaPcmReplaceAndStop()
@Test void malformedFallbackAssetWarnsAndRejectsOnlyThatVoice()
@Test void resolvedCommandsNeverContainConsumerRunnableOrCallbackFields()
@Test void resolvingSmpsSfxDoesNotMutateTheOwnerDriverBeforeQueueApply()
@Test void queuedSfxContainsOnlyAssetKeyAndPrimitiveMetadata()
@Test void mutatingOriginalLoadedObjectsAfterWarmAndQueueDoesNotChangeAppliedSfx()
@Test void reconstructedConfigsShareTheSessionCoordFlagHandlerOwner()
@Test void offOwnerThreadInstantiationIsRejectedBeforeCacheLookup()
@Test void cacheMissAtApplyRejectsWithoutLoaderRomDecodeOrFallbackCalls()
@Test void replacementAndOverrideCommandsBeforeSfxSelectTheFinalCurrentDriver()
@Test void overlappingNoMusicSfxUseOneStandaloneDriverAndArbitrate()
@Test void continuousRetriggerExtendsMusicAndStandaloneWithoutDuplicateSequencer()
@Test void musicMutationAndResetThenSfxObservesTheSamePresentationCounter()
@Test void sfxMutationThenMusicObservesTheSamePresentationCounter()
@Test void overrideAndSnapshotRecreationUseTheSamePresentationHandlerOwner()
@Test void arbitraryHandlerEmbeddedInProfileConfigIsNeverUsedForPresentation()
```

Assert WAV decode calls occur during `submit`, never while
`AudioPresentationMixer.mix(registry, frames)` traverses the registry.

- [ ] **Step 2: Run resolver tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationCommandResolver test
```

Expected: compilation fails because source factory/resolver do not exist.

- [ ] **Step 3: Extract SMPS construction without changing legacy playback**

Move the existing music-driver construction and shared driver/sequencer configuration
helpers from `AbstractSmpsAudioBackend.playSmps*` into
`AudioPresentationSourceFactory`, preserving:

```java
driver.setRegion(PAL_or_NTSC);
driver.setDacInterpolate(config.getBoolean(SonicConfiguration.DAC_INTERPOLATE));
driver.setOutputSampleRate(outputSampleRate);
sequencer.setSpeedShoes(speedShoesEnabled);
sequencer.setSpeedMultiplier(speedMultiplier);
sequencer.setFm6DacOff(config.getBoolean(SonicConfiguration.FM6_DAC_OFF));
sequencer.setFallbackVoiceData(data);
driver.addSequencer(sequencer, isSfx);
```

Have the backend call the music helper during the transitional commit so legacy OpenAL
still plays, but inject a backend-private `legacyCoordFlagHandlers` owner into that
legacy helper; never pass the presentation-session owner to the legacy backend. Its
legacy SFX path may call a separate factory attachment helper, but the
presentation resolver must only call `resolveSmpsSfx` and therefore must not construct
or attach an SFX sequencer before registry apply. Do not change which route reaches the
speaker yet.

- [ ] **Step 4: Implement a standalone immutable resolver**

The resolver accepts already-resolved loader/profile dependencies and writes only
fully resolved command records to an injected `AudioPresentationCommandQueue`.
Construct/decode WAV data and music SMPS drivers before queue submission. For SFX,
Every presentation SMPS construction path—base/donor music, override music, music-owned
SFX, standalone SFX, and snapshot recreation—must call one internal
`copyPresentationConfig(gameId, sourceConfig)` helper. It copies only static config
values and always replaces/ignores `sourceConfig.getCoordFlagHandler()` with
`coordFlagHandlers.handlerFor(gameId)`. The presentation factory never accepts an
arbitrary profile/singleton handler as runtime ownership, even when the supplied profile
config embeds one. `recreateSmps` rebuilds every snapshot sequencer through this same
helper and presentation owner. The production
`AudioPresentationDependencyResolver.recreateSmps` delegates to this factory method;
it must not return a config directly from
`SmpsDriverSnapshot.DependencyResolver.resolveConfig`.

`warmSmpsSfxAsset` validates the resolved route and defensively snapshots every required
SMPS-data byte/table, DAC sample/mapping, and non-handler configuration value into a
private cache keyed by `SmpsAssetKey`; later mutation of loader-owned inputs cannot
change it. The cache stores no coord-flag handler/factory. `submit` never mutates
`AudioManager`, a backend, or a live registry.
`resolveSmpsSfx` returns the Task 4 key-and-primitives-only
`ResolvedSmpsSfxSource`; it does not construct a `SmpsSequencer` and does not call
`owner.driver().addSequencer`.

At ordered registry apply, `instantiateCached`/`instantiateStandaloneCached` assert the
owner thread and perform cache-only lookup. They defensively create fresh mutable
`AbstractSmpsData`, `DacData`, and `SmpsSequencerConfig` for every applied SFX, but each
reconstructed config obtains its game handler from the shared
`SmpsCoordFlagHandlerOwner`; `handlerFor(gameId)` returns the same handler identity for
that game for the lifetime of the session. `Sonic3kCoordFlagHandler` therefore reads/writes the same
session `SmpsCoordFlagRuntimeState.spindashRevCounter` across normal starts, continuous
extensions, music/standalone ownership, and later plays. A miss throws the registry's deterministic cache-miss exception without
loader/ROM/file/decode/fallback access; the registry warns and removes only that start.
Continuous-SFX extension/identity rules remain in the driver.
`submitRawPcm` assigns a stable asset identity, calls
`DecodedPcmCache.registerUnsigned8Mono` before submission, and places only the
registered `DecodedPcm`/asset-backed voice in `ReplaceRawPcm`.

- [ ] **Step 5: Write failing all-source mix and toggle-invariance tests**

Create `TestAudioPresentationSourceParity`:

```java
@Test void smpsMusicSmpsSfxWavSfxAndRawPcmAppearInOnePacket()
@Test void fallbackMusicLoopsWhileMultiplePitchedWavSfxCompleteIndependently()
@Test void segaPcmReplacementAndStopPreserveExistingRules()
@Test void musicOverrideStackRestoresCompositeAndLoopingSampleCursor()
@Test void thirtyThreeSampleSfxObeyPriorityAdmission()
@Test void malformedVoiceDoesNotStopOtherVoices()
@Test void legacyAndPresentationSmpsDriversRenderTheSameFirstTenPackets()
@Test void legacyAndPresentationRawPcmRenderTheSameCursorSequence()
@Test void legacyFallbackWavMetadataMatchesDecodedPresentationAsset()
```

Modify `TestSonic3kCoordFlagParity` so its normal and continuous shared-counter cases
enter through `AudioPresentationSourceFactory` plus ordered `AudioVoiceRegistry.apply`,
and assert: music mutation/reset then SFX, SFX then music, override, continuous
retrigger, and snapshot recreation all observe the same presentation-session counter.

Use deterministic non-zero fixtures with disjoint amplitudes and assert final mixed
samples, cursor completion, loop points, and priority decisions. For SMPS, construct the
legacy driver and factory driver from the same `AbstractSmpsData`/`DacData`, render ten
identical packet sizes, and assert bit equality. Do not assert only command dispatch.

- [ ] **Step 6: Run routing tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationSourceParity,TestDonorAudioRouting,TestSegaPcmCommandRouting,TestSonic3kCoordFlagParity test
```

Expected: source parity assertions fail until factory construction matches existing
SMPS/raw/WAV behavior.

- [ ] **Step 7: Complete factory parity without production ownership changes**

Make `AbstractSmpsAudioBackend` delegate only its construction helpers to the shared
factory while retaining legacy ownership and playback. The presentation resolver uses
the same factory independently. No source/voice instance is shared between legacy and
presentation paths; component tests prove equivalent initial state and cursor output.

- [ ] **Step 8: Verify routing, snapshots, and existing audio suites**

Run:

```bash
mvn -Dtest=TestAudioPresentationCommandResolver,TestAudioPresentationSourceParity,TestDonorAudioRouting,TestSegaPcmCommandRouting,TestSonic3kCoordFlagParity,AudioRegressionTest test
```

Expected: all selected tests pass. Manual playback remains legacy OpenAL in this commit,
so the branch retains usable normal audio.

- [ ] **Step 9: Update changelog and commit exact files**

Add:

```markdown
- Unified SMPS, fallback WAV, pitched SFX, and raw SEGA PCM command resolution while retaining audible legacy output during migration.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationSourceFactory.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationCommandResolver.java \
  src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSmpsSequencerConfig.java \
  src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java \
  src/test/java/com/openggf/audio/TestDonorAudioRouting.java \
  src/test/java/com/openggf/audio/TestSegaPcmCommandRouting.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandResolver.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationSourceParity.java \
  src/test/java/com/openggf/tests/TestSonic3kCoordFlagParity.java
git commit -m "feat(audio): resolve all sources into presentation voices

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 7: Tick an inaudible shadow producer exactly once per presented frame

**Files:**
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Create: `src/main/java/com/openggf/audio/presentation/AudioPresentationParityProbe.java`
- Create: `src/test/java/com/openggf/audio/TestShadowAudioPresentationRouting.java`
- Create: `src/test/java/com/openggf/TestGameLoopAudioPresentationModes.java`
- Modify: `src/test/java/com/openggf/TestEngineLiveCapturePresentation.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestRunner.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestHeldRewindAudioStepCost.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 6 resolved factories/commands and Task 5 producer.
- Produces:

```java
void AudioManager.presentShadowFrame(PresentationMode mode);
AudioPresentationParityProbe.Snapshot AudioManager.shadowParitySnapshotForTesting();
PresentationMode GameLoop.presentationModeForOuterFrame(boolean modalPicker,
                                                         boolean frameStepRequested);
void AudioManager.toggleMute(ChannelType type, int channel);
void AudioManager.toggleSolo(ChannelType type, int channel);
boolean AudioManager.isMuted(ChannelType type, int channel);
boolean AudioManager.isSoloed(ChannelType type, int channel);

public final class AudioPresentationParityProbe {
    public record Snapshot(long presentedFrames, long forwardFrames, long silentFrames,
            long reverseFrames, long totalStereoFrames, long commandCount,
            long historyEpoch) {}
}
```

Legacy backend output remains the only speaker source in this task. The shadow producer
uses `NoDeviceAudioSink`, cannot attach live/offline recording, and exists solely to
prove continuous state before cutover.

- [ ] **Step 1: Write failing production-branch tick coverage**

Create `TestGameLoopAudioPresentationModes` with a package-private outer-boundary
presentation probe:

```java
@Test void normalLevelTitleMenuSpecialAndBonusDisplayFramesTickShadowForwardOnce()
@Test void ordinaryPauseDisplayFrameTicksShadowSilentOnce()
@Test void pausedFrameStepDisplayFrameTicksShadowSilentOnce()
@Test void heldLiveRewindDisplayFrameTicksShadowReverseOnce()
@Test void heldTraceRewindDisplayFrameTicksShadowReverseOnce()
@Test void everySimulationEarlyReturnStillGetsOneOuterPresentationTick()
@Test void modalShaderPickerGetsOneSilentOuterTickWhenGameLoopIsSkipped()
@Test void nineFastForwardSimulationStepsYieldOneShadowProducerAndClockPacket()
@Test void headlessRunnerExplicitlyTicksOneOuterBoundaryPerStepFrameCall()
```

Drive the actual branch helpers already used by `TestGameLoop`,
`TestGameLoopSpecialStageRewindGate`, `TestGameLoopRewindBoundaryPolicy`, and
`TestEngineLiveCapturePresentation`. Enumerate legal disclaimer, master title, title,
level select, data select, credits, level, title card, bonus stage, special stage,
live rewind, trace rewind, pause, frame-step, and modal-picker returns.

- [ ] **Step 2: Run branch tests and verify RED**

Run:

```bash
mvn -Dtest=TestGameLoopAudioPresentationModes,TestEngineLiveCapturePresentation test
```

Expected: compilation fails because shadow presentation entrypoints do not exist.

- [ ] **Step 3: Construct and feed the shadow without changing audible routing**

After backend negotiation, construct a separate registry/queue/mixer/producer with
`NoDeviceAudioSink`. Every public `AudioManager` command continues its existing legacy
backend call and also submits the fully resolved Task 6 command to the shadow queue in
the same owner-thread order. `playSegaPcm` and `stopSegaPcm` follow the same rule.

Remove presentation calls and the `audioUpdatedThisStep` guard from
`GameLoop.stepInternal()` and its helpers. Simulation steps continue to issue audio
commands in their exact order, but no simulation step advances the shadow producer.
Task 7 retains the legacy backend's existing per-simulation device/source pump through
a direct transitional helper with no "presented" flag; that pump is not the producer
clock and Task 9 removes it with legacy source ownership.
`GameLoop.presentationModeForOuterFrame(...)` only reports the mode for the frame that
will be displayed:

```java
normal rendered display frame      -> FORWARD
ordinary pause or frame-step       -> SILENT
held live/trace rewind             -> REVERSE
modal picker skipping GameLoop     -> SILENT
```

After `update()` has completed all ordinary and fast-forward `stepInternal()` calls,
`Engine.display()` queries that mode and calls `presentShadowFrame(mode)` exactly once,
before any speaker/capture consumer drains the frame. `AudioManager.update()` pumps
only the legacy device and never ticks shadow audio. The existing live-capture shortcut
runs before this boundary, so an attachment made while rewind is held observes this
same outer frame's reverse packet.

Because `HeadlessTestRunner` bypasses `Engine.display()`, each public `stepFrame*` and
recording-step method performs its simulation driver call and then explicitly calls the
same manager outer-boundary hook once with `FORWARD` (or the test-selected mode). Its
`stepIdleFrames(n)` delegates through those presented-frame methods rather than adding a
second tick. This is test/headless boundary ownership, not a call from
`RecordingFrameDriver` or `LevelFrameStep`.

- [ ] **Step 4: Write failing command-order and ownership-continuity tests**

Create `TestShadowAudioPresentationRouting`:

```java
@Test void everyLegacyCommandHasOneSameOrderShadowCommand()
@Test void sixtyPresentedFramesTickShadowExactlySixtyTimes()
@Test void fastForwardCommandsQueueInSimulationOrderButMixOneOuterPacket()
@Test void silentAppliesAllCommandsInOrderWithoutAdvancingShadowVoices()
@Test void rewindRatesAndHardBoundariesReachBothPathsAtTheSameFrame()
@Test void shadowNeverRestartsWhenCaptureShortcutIsPressed()
@Test void shadowNeverOpensADeviceOrARecordingTap()
@Test void muteAndSoloQueriesUseTheShadowCompositeState()
@Test void legacyBackendRemainsTheUninterruptedAudibleOwnerForTheWholeShadowRun()
@Test void legacyAndPresentationUseDistinctCoordFlagOwnersAndState()
@Test void dualShadowRenderingMutatesEachOwnerOnceWithoutCrossMutation()
```

Use actual `AudioManager.playMusic`, `playSfx`, ring alternation, donor routes, raw PCM,
fade, stop, restore, override, speed, tempo, mute, and solo APIs. Assert the parity
probe counts commands and exactly one shadow tick at each production frame boundary.
At shadow construction create two different owner/state pairs: a separately
instantiated `SmpsCoordFlagHandlerOwner legacyCoordFlagHandlers` inside
`AbstractSmpsAudioBackend`, and
`SmpsCoordFlagHandlerOwner presentationCoordFlagHandlers` inside the presentation
session/factory. Both rebuild S3K
configs by copying static profile values and replacing the profile handler with their
own owner handler. Assert handler/state identities differ, one dual-rendered coord flag
mutates each counter once (not one shared counter twice), and equal legacy/presentation
commands leave equal counter values.
Do not require equality between legacy OpenAL consumption cursors and the frame-clocked
shadow cursor: OpenAL is callback/buffer driven. Instead assert legacy backend/source
identities remain unchanged and exclusively audible throughout this task, with no stop,
restart, queue flush, or source replacement caused by shadow construction or ticking.
Do not call a test-only manual shadow tick.

- [ ] **Step 5: Run shadow continuity tests and verify RED**

Run:

```bash
mvn -Dtest=TestShadowAudioPresentationRouting,TestGameLoopAudioPresentationModes,TestHeldRewindAudioStepCost test
```

Expected: ordering/outer-boundary assertions fail until every production display frame
and command route feeds shadow state exactly once.

- [ ] **Step 6: Verify production playback remains legacy and usable**

Run:

```bash
mvn -Dtest=TestShadowAudioPresentationRouting,TestDonorAudioRouting,TestSegaPcmCommandRouting,TestAudioBackendOverrideStackRestore,AudioRegressionTest test
```

Expected: all selected tests pass. Assert in the test that the live backend still owns
the audible source and the shadow sink is `NoDeviceAudioSink`; this commit cannot
silence, restart, or replace current playback.

- [ ] **Step 7: Update changelog and commit exact files**

Add:

```markdown
- Added a continuously ticked inaudible presentation shadow to prove command order and frame ownership before speaker migration.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/Engine.java \
  src/main/java/com/openggf/GameLoop.java \
  src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/main/java/com/openggf/audio/presentation/AudioPresentationParityProbe.java \
  src/main/java/com/openggf/game/rewind/LiveRewindManager.java \
  src/test/java/com/openggf/TestEngineLiveCapturePresentation.java \
  src/test/java/com/openggf/TestGameLoopAudioPresentationModes.java \
  src/test/java/com/openggf/audio/TestShadowAudioPresentationRouting.java \
  src/test/java/com/openggf/tests/HeadlessTestRunner.java \
  src/test/java/com/openggf/game/rewind/TestHeldRewindAudioStepCost.java
git commit -m "test(audio): tick presentation shadow continuously

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 8: Prove snapshot and rewind authority before speaker cutover

**Files:**
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/audio/rewind/AudioLogicalSnapshot.java`
- Modify: `src/main/java/com/openggf/audio/rewind/AudioBackendLogicalSnapshot.java`
- Modify: `src/main/java/com/openggf/audio/rewind/AudioPresentationPolicy.java`
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Create: `src/test/java/com/openggf/audio/TestAudioPresentationSnapshotParity.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioLogicalSnapshot.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioKeyframeReplay.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java`
- Modify: `src/test/java/com/openggf/tests/TestSonic3kCoordFlagParity.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: continuously ticked Task 7 shadow and existing legacy snapshots.
- Produces a transitional dual snapshot:

```java
public record AudioLogicalSnapshot(
        boolean ringLeft,
        long commandTimelineFrame,
        int commandTimelineNextOrder,
        int commandEntryCount,
        AudioBackendLogicalSnapshot backend,
        AudioPresentationSnapshot presentation,
        Set<String> donorGameIds,
        Set<DonorSfxBindingSnapshot> donorBindings) {}
```

Both states are captured/restored in this task while legacy remains audible. Task 9
promotes `presentation`; Task 13 removes `backend`.
For this shadow-only interval, `AudioBackendLogicalSnapshot` adds
`SmpsCoordFlagRuntimeState.Snapshot legacyCoordFlagRuntimeState`; the presentation
counter remains in `AudioPresentationSnapshot`. Capture/restore both distinct owners at
the same boundary.

- [ ] **Step 1: Write failing snapshot parity tests**

Create `TestAudioPresentationSnapshotParity`:

```java
@Test void presentationSnapshotContainsEveryMusicSlotDescriptorAndFlag()
@Test void controlledReferenceRendererMatchesSmpsAndSampleBitsAndCursors()
@Test void speedTempoMuteSoloRingAndContinuousSfxStateRecreates()
@Test void rawPcmReplacementAndCompletionRecreate()
@Test void nestedFallbackWavOverrideSlotsRetainMusicIdDescriptorAndVoiceId()
@Test void removedRawPcmAssetRecreatesThroughDependencyResolver()
@Test void coordFlagRuntimeCounterRestoresBeforeSequencerReconstruction()
@Test void dualShadowSnapshotsRestoreIndependentEqualCoordFlagCounters()
@Test void restoreAfterRemovingAllVoicesRecreatesTheSameNextTenPackets()
@Test void heldReverseRestoreDefersBothPathsAndDoesNotRenderForward()
@Test void releaseStopsTransientVoicesAndKeepsDurableMusicAtSelectedCursor()
@Test void hardBoundaryClearsBothHistoryEpochsAtTheSameFrame()
```

Drive actual manager commands for 120 production frames and verify both transitional
snapshots are captured at the same owner-thread boundary. For bit/cursor parity, build a
controlled reference renderer from the same resolved sources, sample rate, and exact
`AudioFrameClock` packet sizes; advance reference and presentation once per test frame
and compare PCM/cursors. Never compare production OpenAL callback cursors to shadow
cursors, and never drain or advance the production shadow outside its presentation tick.

For restore, stop and remove every live registry voice, then call
`producer.restore(snapshot, AudioPresentationDependencyResolver, false)`. The resolver
recreates decoded PCM by asset identity and composites with the snapshot's declared
`maxStereoFrames`; assert every slot, ID/descriptor, mute/solo mask,
`sfxBlocked`/`pendingRestore`, override, coord-flag runtime counter, and next ten
packets match. Extend `TestSonic3kCoordFlagParity` to snapshot after a spindash counter
mutation, disturb both independent shadow states, restore both, reconstruct later
legacy and presentation configs, and verify each observes its own restored counter with
equal values and different owner/state identities.

- [ ] **Step 2: Run parity tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationSnapshotParity,TestAudioLogicalSnapshot test
```

Expected: failures identify missing presentation snapshot fields or restore ordering.

- [ ] **Step 3: Capture and restore both states transactionally**

Add `presentation` to `AudioLogicalSnapshot`. Capture backend and producer snapshots at
the same owner-thread boundary. Restore both before replaying later timeline commands.
During held reverse, restore logical structures/cursors but preserve presentation
history and reverse cursor in both paths. At release, apply the same
`AudioPresentationPolicy` to both, then compare durable cursor state before returning.

If either restore fails, keep the last complete snapshot in both paths, warn once, and
do not partially commit one path.

- [ ] **Step 4: Verify keyframe, rewind, and next-packet parity**

Run:

```bash
mvn -Dtest=TestAudioPresentationSnapshotParity,TestAudioLogicalSnapshot,TestAudioKeyframeReplay,TestAudioManagerRewindSuppression,TestLiveRewindManagerAudioCleanup,TestHeldRewindAudioStepCost,TestSonic3kCoordFlagParity test
```

Expected: all tests pass and legacy remains the only audible output. The review report
must include frame/cursor/snapshot parity evidence before Task 9 starts.

- [ ] **Step 5: Update changelog and commit exact files**

Add:

```markdown
- Proved unified voice snapshots and rewind cursors with an exact-clock reference renderer while legacy output remained uninterrupted.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/main/java/com/openggf/audio/rewind/AudioBackendLogicalSnapshot.java \
  src/main/java/com/openggf/audio/rewind/AudioLogicalSnapshot.java \
  src/main/java/com/openggf/audio/rewind/AudioPresentationPolicy.java \
  src/main/java/com/openggf/game/rewind/LiveRewindManager.java \
  src/test/java/com/openggf/audio/TestAudioKeyframeReplay.java \
  src/test/java/com/openggf/audio/TestAudioLogicalSnapshot.java \
  src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java \
  src/test/java/com/openggf/audio/TestAudioPresentationSnapshotParity.java \
  src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java \
  src/test/java/com/openggf/tests/TestSonic3kCoordFlagParity.java
git commit -m "test(audio): prove presentation snapshot parity

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 9: Replace independent OpenAL sources with the proven final-PCM producer

**Files:**
- Create: `src/main/java/com/openggf/audio/output/SpeakerPacketFifo.java`
- Create: `src/main/java/com/openggf/audio/output/OpenAlPcmSink.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/audio/AudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/rewind/AudioBackendLogicalSnapshot.java`
- Create: `src/main/java/com/openggf/audio/debug/StandaloneAudioPresentationHost.java`
- Modify: `src/main/java/com/openggf/audio/debug/SoundTestApp.java`
- Create: `src/test/java/com/openggf/audio/output/TestSpeakerPacketFifo.java`
- Create: `src/test/java/com/openggf/audio/output/TestOpenAlPcmSink.java`
- Create: `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioBackendBypassGuard.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioBackendLifetime.java`
- Modify: `src/test/java/com/openggf/audio/TestLWJGLAudioBackendSnapshot.java`
- Modify: `src/test/java/com/openggf/audio/TestShadowAudioPresentationRouting.java`
- Modify: `src/test/java/com/openggf/tests/TestSonic3kCoordFlagParity.java`
- Create: `src/test/java/com/openggf/audio/debug/TestSoundTestPresentationHost.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 5 sink/view, Task 7 continuously ticked shadow, and Task 8 proven
  snapshots/cursors.
- Produces:

```java
public final class SpeakerPacketFifo {
    public SpeakerPacketFifo(int sampleRate);
    public void offer(short[] samples, int stereoFrames);
    public int drain(short[] target, int maxStereoFrames);
    public void flush();
    public int queuedStereoFrames();
    public long droppedStereoFrames();
}

public final class OpenAlPcmSink implements AudioPresentationSink {
    public interface Device {
        int initialize();
        void enqueue(short[] stereoPcm, int stereoFrames, int sampleRate);
        void update();
        void flush();
        void pause();
        void resume();
        void close();
    }
    public OpenAlPcmSink(Device device, Consumer<Throwable> failureHandler,
                         LongSupplier nanoTime, Consumer<String> warning);
    public void updateDevice();
}

public final class StandaloneAudioPresentationHost implements AutoCloseable {
    public static StandaloneAudioPresentationHost open(
            String gameId, SonicConfigurationService config,
            PerformanceProfiler profiler, boolean noDevice);
    public void playMusic(AbstractSmpsData data, DacData dac);
    public void playSfx(AbstractSmpsData data, DacData dac, float pitch);
    public void toggleMute(ChannelType type, int channel);
    public void toggleSolo(ChannelType type, int channel);
    public boolean isMuted(ChannelType type, int channel);
    public boolean isSoloed(ChannelType type, int channel);
    public void setSpeedShoes(boolean enabled);
    public void presentFrame();
    @Override public void close();
}

public final class AudioManager {
    public static AudioManager createStandalonePresentation(
            String gameId,
            GameAudioProfile profile,
            SonicConfigurationService config,
            PerformanceProfiler profiler,
            AudioPresentationSink sink,
            SmpsCoordFlagHandlerOwner coordFlagHandlers);
}
```

- [ ] **Step 1: Write failing FIFO capacity and newest-tail tests**

Create `TestSpeakerPacketFifo`:

```java
@Test void capacityIsExactlyTwoSecondsAtNegotiatedRate()
@Test void overflowDropsOldestUntilOneSecondIsFree()
@Test void newestTailIsRetainedInOriginalSampleOrder()
@Test void producerOfferNeverBlocksWhenConsumerStalls()
@Test void flushDropsOnlySpeakerPackets()
```

At sample rate 10, fill 20 frames, offer 4 more, then assert the queue retains the newest
10-frame tail followed by the 4 new frames and reports 10 dropped frames.

- [ ] **Step 2: Run FIFO tests and verify RED**

Run:

```bash
mvn -Dtest=TestSpeakerPacketFifo test
```

Expected: compilation fails because `SpeakerPacketFifo` does not exist.

- [ ] **Step 3: Implement the fixed speaker FIFO**

Use one `short[sampleRate * 2 seconds * 2 channels]` ring allocated in the constructor.
On insufficient capacity, discard oldest frames until at least `sampleRate` frames are
free; preserve newest-tail ordering. `offer` contains no waits, locks on device calls,
or allocation.

- [ ] **Step 4: Write failing sink aggregation, warning, boundary, and failure tests**

Create `TestOpenAlPcmSink` with a fake `Device`:

```java
@Test void aggregatesVariablePresentationPacketsInto1024FrameDeviceBuffers()
@Test void reverseBoundaryFlushesDeviceAndSpeakerFifoBeforeReprime()
@Test void stalledDeviceDoesNotBlockProducerAndDropsSpeakerOnlyPcm()
@Test void overrunWarningIsRateLimitedToOncePerSecond()
@Test void enqueueFailureAtomicallyRequestsNoDeviceReplacementOnce()
@Test void updateFailureClearsStaleSpeakerPacketsAndRequestsReplacementOnce()
@Test void closeIsIdempotent()
@Test void replacingOnlyTheSinkPreservesProducerRegistryHistoryClockAndNextPacket()
```

For aggregation, feed PAL-sized 960-frame packets and assert device enqueues exact
1024-frame buffers without changing sample order.

- [ ] **Step 5: Run sink tests and verify RED**

Run:

```bash
mvn -Dtest=TestOpenAlPcmSink test
```

Expected: compilation fails because `OpenAlPcmSink` does not exist.

- [ ] **Step 6: Implement sink and failure replacement**

Allocate all OpenAL buffers/direct scratch during `Device.initialize`. `accept` copies
through `frame.copyTo(preallocatedScratch, 0)` into `SpeakerPacketFifo`;
`updateDevice` drains fixed 1,024-frame chunks. On the first
device exception, invoke the manager failure callback:

```java
failure -> producer.replaceSink(new NoDeviceAudioSink(currentSampleRate))
```

Clear the failed sink FIFO and close the device once. Do not clear voices, history,
capture handles, command queue, or producer clock.

- [ ] **Step 7: Write the failing source-ownership architecture guard**

`TestAudioPresentationArchitectureGuard` scans `LWJGLAudioBackend.java` and
`OpenAlPcmSink.java` and asserts:

```java
assertFalse(source.contains("sfxSources"));
assertFalse(source.contains("musicSource"));
assertFalse(source.contains("playWav("));
assertFalse(source.contains("AL_LOOPING"));
assertFalse(source.contains("AL_PITCH"));
assertFalse(source.contains("alSourcei(source, AL_BUFFER"));
```

Allow exactly one OpenAL source declaration named `presentationSource` inside
`OpenAlPcmSink`; assert it receives only `AL_FORMAT_STEREO16` buffers produced by
`AudioPresentationFrameView`. Also scan all `src/main/java/com/openggf/audio` imports and
allow `org.lwjgl.openal` only under `audio/output/OpenAlPcmSink.java`.
Scan all production Java outside `AudioManager`,
`AudioPresentationSourceFactory`, and backend implementations and reject direct
`AudioBackend.play*`, `toggleMute`, `toggleSolo`, `isMuted`, or `isSoloed` calls.

- [ ] **Step 8: Run the guard and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationArchitectureGuard test
```

Expected: FAIL because `LWJGLAudioBackend` still owns music/SFX OpenAL sources.

- [ ] **Step 9: Atomically switch audible output and remove legacy source ownership**

In one change:

1. construct `OpenAlPcmSink` during LWJGL initialization;
2. install it with `producer.replaceSink`;
3. stop calling legacy backend play/update source hooks from `AudioManager`;
4. reduce `LWJGLAudioBackend` to profile/source-factory compatibility plus the sink;
5. delete WAV buffer maps, SFX source list, music source, stream buffers, `playWav`,
   `hookPlayWavSfx`, `fillBuffer`, and independent source cleanup;
6. remove the transitional per-simulation legacy pump from `GameLoop`; immediately
   after the one outer `presentShadowFrame(mode)` call in `Engine.display()`, call
   `AudioManager.update()` once so it pumps `OpenAlPcmSink.updateDevice()` only after
   that producer packet has been offered;
7. destroy `legacyCoordFlagHandlers` with the legacy source path and remove
   `legacyCoordFlagRuntimeState` from `AudioBackendLogicalSnapshot`; from this cutover
   onward only the presentation session owner/state is captured, restored, or mutated;
8. use `NoDeviceAudioSink` when device init fails without replacing the manager,
   registry, or producer.

Do not leave a configuration switch selecting old output.

Before replacing the sink, capture producer identity, registry snapshot, history epoch,
clock snapshot, active reverse cursor, and next expected test packet. After replacement,
assert identity/state are unchanged and the next packet matches. The cutover installs a
sink on the existing Task 7 producer; it never constructs a new producer, replays
commands, restarts a driver, or drains a voice.

- [ ] **Step 10: Migrate SoundTestApp and mute/solo ownership**

Write `TestSoundTestPresentationHost`:

```java
@Test void consoleAndInteractiveHostsTickTheSameManagerOwnedProducer()
@Test void directBackendPlaybackCallsAreAbsentFromSoundTestApp()
@Test void muteSoloAndSpeedCommandsReachTheActiveSmpsComposite()
@Test void noDeviceModeUsesNoDeviceSinkAndStillAdvancesVoices()
@Test void closeDestroysTapRegistryHistoryThenSinkExactlyOnce()
@Test void sonic3kHostMusicSfxResetAndRecreationShareOneSessionCounter()
@Test void cliGameOverrideIsPassedAsTheHostBoundGameId()
```

Expected RED command:

```bash
mvn -Dtest=TestSoundTestPresentationHost test
```

Expected: compilation fails because `StandaloneAudioPresentationHost` does not exist.

`StandaloneAudioPresentationHost.open(gameId, ...)` validates the explicit bound game
ID from `SoundTestApp.Options.gameId`, resolves that game's profile once, creates one
isolated `SmpsCoordFlagRuntimeState`/`SmpsCoordFlagHandlerOwner`, and passes all of them
to a non-singleton `AudioManager.createStandalonePresentation(...)`. That manager owns
the bound profile/source factory, queue, registry, mixer, producer, snapshots, and
chosen sink. The host contains no parallel voice registry or direct backend.

Host `playMusic`/`playSfx` do not accept a caller `SmpsSequencerConfig`; they route
through the bound-game source factory, copy the bound profile's static config values,
and replace its handler with the host's single owner handler. Snapshot recreation uses
that same source factory/owner. The S3K host test performs music→SFX and SFX→music
counter mutations, reset, snapshot recreation, and verifies one shared host-session
counter throughout. Change `SoundTestApp` fields and method parameters from
`AudioBackend` to this host, pass `options.gameId` exactly (including an explicit CLI
override), and have its audio thread call `presentFrame()`. Route SMPS music/SFX,
speed, mute, solo, state queries, and cleanup through the host.
Remove `backend instanceof LWJGLAudioBackend` and every direct `backend.play*`,
`backend.update`, or `backend.toggle*` call.

- [ ] **Step 11: Verify migration tests plus normal audio suites**

Run:

```bash
mvn -Dtest=TestSpeakerPacketFifo,TestOpenAlPcmSink,TestAudioPresentationArchitectureGuard,TestAudioBackendBypassGuard,TestAudioBackendLifetime,TestLWJGLAudioBackendSnapshot,TestShadowAudioPresentationRouting,TestAudioPresentationSnapshotParity,TestSoundTestPresentationHost,TestSonic3kCoordFlagParity,AudioRegressionTest test
```

Expected: all selected tests pass. The only live speaker route is final stereo PCM.
`TestSonic3kCoordFlagParity` and `TestShadowAudioPresentationRouting` additionally
assert the legacy coord-flag owner is unreachable after cutover and music, override,
SFX, standalone, and snapshot recreation still share the sole presentation owner.

- [ ] **Step 12: Update changelog and commit exact files**

Add:

```markdown
- Made OpenAL a bounded final-PCM sink so toggling recording cannot remove music, rings, or effects.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/Engine.java \
  src/main/java/com/openggf/GameLoop.java \
  src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
  src/main/java/com/openggf/audio/AudioBackend.java \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/main/java/com/openggf/audio/LWJGLAudioBackend.java \
  src/main/java/com/openggf/audio/rewind/AudioBackendLogicalSnapshot.java \
  src/main/java/com/openggf/audio/debug/SoundTestApp.java \
  src/main/java/com/openggf/audio/debug/StandaloneAudioPresentationHost.java \
  src/main/java/com/openggf/audio/output/SpeakerPacketFifo.java \
  src/main/java/com/openggf/audio/output/OpenAlPcmSink.java \
  src/test/java/com/openggf/audio/TestAudioBackendBypassGuard.java \
  src/test/java/com/openggf/audio/TestAudioBackendLifetime.java \
  src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java \
  src/test/java/com/openggf/audio/TestLWJGLAudioBackendSnapshot.java \
  src/test/java/com/openggf/audio/TestShadowAudioPresentationRouting.java \
  src/test/java/com/openggf/audio/debug/TestSoundTestPresentationHost.java \
  src/test/java/com/openggf/audio/output/TestSpeakerPacketFifo.java \
  src/test/java/com/openggf/audio/output/TestOpenAlPcmSink.java \
  src/test/java/com/openggf/tests/TestSonic3kCoordFlagParity.java
git commit -m "feat(audio): output only unified PCM through OpenAL

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 10: Promote the continuously ticked shadow to the sole presentation entrypoint

**Files:**
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Modify: `src/main/java/com/openggf/audio/rewind/AudioPresentationPolicy.java`
- Create: `src/test/java/com/openggf/audio/TestAudioManagerPresentationModes.java`
- Modify: `src/test/java/com/openggf/TestGameLoopAudioPresentationModes.java`
- Modify: `src/test/java/com/openggf/TestEngineLiveCapturePresentation.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestRunner.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestHeldRewindAudioStepCost.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 7 mode wiring, Task 8 snapshot parity, and Task 9 final-PCM sink.
- Produces:

```java
public void AudioManager.presentFrame(PresentationMode mode);
public void AudioManager.beginReverseAudioPresentation();
public void AudioManager.endReverseAudioPresentation();
public void AudioManager.setReversePlaybackRate(double rate);
public void AudioManager.clearPcmHistory();
public void AudioManager.setRewindHistoryArmed(boolean armed);
```

`presentShadowFrame` is renamed to `presentFrame` at the already-proven outer
`Engine.display()` and headless-runner boundaries without changing tick placement or
clock. `advanceGameplayFrameAudio()` and `advancePausedFrameStepAudio()` remain
deprecated delegates until Task 13 removes them; no `stepInternal()` path calls them.

- [ ] **Step 1: Write failing manager mode and command-boundary tests**

Create `TestAudioManagerPresentationModes`:

```java
@Test void forwardPresentsOnceAndAppliesCommandsBeforeRendering()
@Test void pausedAndFrameStepUseSilentWithoutMovingVoiceCursors()
@Test void reverseUsesProducerHistoryWithoutRenderingVoices()
@Test void structuralStopWhilePausedAppliesWithoutVoiceAdvance()
@Test void updatePumpsSinkButNeverPresentsAnotherPacket()
@Test void nineFastForwardStepsStillPresentOnePacketAtOuterBoundary()
@Test void ordinaryExceptionStillClosesTapRegistryHistoryThenSink()
```

Inject a producer spy and assert `AudioManager.update()` never calls `present`.

- [ ] **Step 2: Run manager tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioManagerPresentationModes test
```

Expected: compilation fails because the promoted
`AudioManager.presentFrame(PresentationMode)` name does not exist.

- [ ] **Step 3: Route manager presentation and rewind methods only to the producer**

Implement:

```java
public void presentFrame(PresentationMode mode) {
    producer.present(commandTimeline.currentFrame(), mode);
    audioFrameAdvanced = true;
}
```

`beginReverseAudioPresentation`, rate changes, release, clear, and history arming call
only the producer. Remove backend/runtime reverse calls. On `afterRewindRestore`:

- `SUPPRESSED_INTERNAL_RESTORE`: keep reverse state unchanged;
- `STOP_TRANSIENT_SFX_RESYNC_MUSIC`: stop transient voices and pop stale override;
- `STOP_TRANSIENT_SFX`: stop transient voices and preserve restored durable music;
- `STOP_ALL_PRESENTATION`: clear registry and history.

- [ ] **Step 4: Re-run executable-branch coverage against the promoted entrypoint**

Create a package-private `GameLoop.AudioPresentationProbe` callback and tests that drive:

```java
@Test void normalLevelTitleMenuSpecialAndBonusFramesPresentForwardOnce()
@Test void ordinaryPausePresentsSilentOnce()
@Test void pausedFrameStepPresentsSilentOnce()
@Test void heldLiveRewindPresentsReverseOnce()
@Test void heldTraceRewindPresentsReverseOnce()
@Test void everyEarlyReturnStillPresentsExactlyOnePacket()
@Test void modalShaderPickerPresentsSilentFromEngineWhenGameLoopIsSkipped()
@Test void nineFastForwardStepsOfferOneSpeakerCaptureAndClockPacket()
```

Enumerate every `return` reachable from `GameLoop.step()` in the test fixture: legal,
master-title, title, level select, data select, credits, level transition, special-stage
rewind, bonus rewind, and trace rewind. Assert the outer display boundary invokes one
and only one mode callback after `GameLoop.step()` returns. Force user-recording
fast-forward to execute the initial step plus eight extra `stepInternal()` calls and
assert exactly one producer packet, one speaker offer, one capture packet, and one
`AudioFrameClock` advance.

- [ ] **Step 5: Run branch tests and verify RED**

Run:

```bash
mvn -Dtest=TestGameLoopAudioPresentationModes,TestEngineLiveCapturePresentation test
```

Expected: tests fail if any branch still calls `presentShadowFrame`, an old runtime, or
more than one producer entrypoint.

- [ ] **Step 6: Rename the proven branch wiring without changing tick placement**

Keep the exact Task 7 outer-boundary placement; do not introduce an
`audioPresentedThisStep`/`audioUpdatedThisStep` flag inside `GameLoop`. Route:

```java
normal outer display frame           -> presentFrame(FORWARD)
ordinary paused display frame        -> presentFrame(SILENT)
paused frame-step display frame      -> presentFrame(SILENT)
held live/trace rewind display frame -> presentFrame(REVERSE)
```

`Engine.display()` chooses modal-picker `SILENT` or the mode reported by `GameLoop`,
then calls `audioManager.presentFrame(mode)` once after all simulation steps.
`HeadlessTestRunner` renames its explicit boundary call to the promoted method.
Do not move either call relative to Task 7 parity evidence. `GameLoop.stepInternal()`,
`RecordingFrameDriver`, `LevelFrameStep`, `LiveCaptureController`, OpenAL, and
`AudioManager.update()` never produce frames.

- [ ] **Step 7: Verify rewind restore, rate, pause, and exact-cost suites**

Run:

```bash
mvn -Dtest=TestAudioManagerPresentationModes,TestGameLoopAudioPresentationModes,TestEngineLiveCapturePresentation,TestLiveRewindManagerAudioCleanup,TestHeldRewindAudioStepCost,TestLiveRewindSpeedModifiers,TestGameLoopRewindBoundaryPolicy test
```

Expected: all selected tests pass; held rewind performs no forward synthesis.

- [ ] **Step 8: Update changelog and commit exact files**

Add:

```markdown
- Made every presented frame choose one explicit forward, silent, or reverse audio mode.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/Engine.java \
  src/main/java/com/openggf/GameLoop.java \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/main/java/com/openggf/audio/rewind/AudioPresentationPolicy.java \
  src/main/java/com/openggf/game/rewind/LiveRewindManager.java \
  src/test/java/com/openggf/TestEngineLiveCapturePresentation.java \
  src/test/java/com/openggf/TestGameLoopAudioPresentationModes.java \
  src/test/java/com/openggf/audio/TestAudioManagerPresentationModes.java \
  src/test/java/com/openggf/tests/HeadlessTestRunner.java \
  src/test/java/com/openggf/game/rewind/TestHeldRewindAudioStepCost.java \
  src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java
git commit -m "refactor(audio): promote unified presentation producer

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 11: Degrade live capture audio failures to phase-continuous stereo silence

**Files:**
- Create: `src/main/java/com/openggf/audio/ClockedSilenceAudioHandle.java`
- Create: `src/main/java/com/openggf/audio/DebugFailAfterFramesAudioHandle.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/capture/LiveCaptureController.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/test/java/com/openggf/audio/AudioManagerLiveCaptureTest.java`
- Create: `src/test/java/com/openggf/audio/ClockedSilenceAudioHandleTest.java`
- Create: `src/test/java/com/openggf/audio/DebugFailAfterFramesAudioHandleTest.java`
- Modify: `src/test/java/com/openggf/TestEngineLiveCapturePresentation.java`
- Modify: `src/test/java/com/openggf/capture/LiveCaptureControllerTest.java`
- Modify: `src/test/java/com/openggf/capture/LiveCaptureMediaSmokeTest.java`
- Modify: `src/test/java/com/openggf/capture/LiveCapturePresentationCoordinatorTest.java`
- Modify: `CONFIGURATION.md`
- Modify: `docs/architecture/designs/2026-07-23-unified-audio-presentation-design.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 5 `producer.attachCapture(frameRate)` and capture clock phase.
- Produces: ledger `ClockedSilenceAudioHandle` and simplified:

```java
public synchronized LiveCaptureAudioHandle AudioManager.beginLiveCaptureAudio(int frameRate);
```

This method only attaches to the already-running producer. It does not replace a runtime,
rebind sources, migrate cursors, or flush the sink.

The only manual failure hook is the dev-only JVM system property
`openggf.debug.liveCaptureAudioFailAfterFrames=N`. Absent or `-1` disables it. A
non-negative `N` wraps only the recording handle and throws before drain `N + 1`, after
exactly `N` successful audio-frame drains; it never alters the producer, speaker sink,
voices, or video path.

```java
public static LiveCaptureAudioHandle DebugFailAfterFramesAudioHandle.maybeWrap(
        LiveCaptureAudioHandle delegate, int failAfterFrames);
```

- [ ] **Step 1: Write failing silence cadence and phase tests**

Create `ClockedSilenceAudioHandleTest`:

```java
@Test void sixtyFramesAt48000ProduceExactly48000SilentStereoFrames()
@Test void fiftyNineFramesAt44100Use747And748AndTotal44100()
@Test void atPhaseContinuesTheNextClockPacketWithoutReset()
@Test void everyDrainClearsTheRequestedRange()
@Test void closeIsIdempotentAndFutureDrainsStaySilent()
```

For phase continuity, drain 17 packets from a normal silence handle, create
`atPhase(prior.clockSnapshot())`, and assert its next 43 packet lengths equal
packets 18..60 from an uninterrupted handle.

- [ ] **Step 2: Run silence tests and verify RED**

Run:

```bash
mvn -Dtest=ClockedSilenceAudioHandleTest test
```

Expected: compilation fails because `ClockedSilenceAudioHandle` does not exist.

- [ ] **Step 3: Implement clocked silence**

`ClockedSilenceAudioHandle` owns `AudioFrameClock`, clears exactly the next packet range,
increments a `totalStereoFrames` phase, and returns the requested frame count.
`atPhase(AudioFrameClock.Snapshot)` constructs a matching clock and calls
`restoreSnapshot`; it performs no loop proportional to recording duration.

Add `DebugFailAfterFramesAudioHandle` as a transparent `LiveCaptureAudioHandle`
decorator and test:

```java
@Test void absentAndMinusOneReturnTheOriginalHandle()
@Test void zeroFailsBeforeTheFirstDrainWithoutAdvancingPhase()
@Test void threeAllowsExactlyThreeDrainsThenFailsBeforeTheFourth()
@Test void wrapperDelegatesMetadataPhaseTotalAndClose()
```

`Engine` parses the property once per recording start with `-1` as the safe default and
uses the wrapper only when `N >= 0`. Malformed/less-than-`-1` values warn once and
disable injection. Keep the property out of `config.yaml`; document it in
`CONFIGURATION.md` as a development/validation JVM property. Add its exact name and
disabled/default semantics to the authoritative unified-audio spec as a validation hook
without weakening the requirement that real failures degrade identically.

- [ ] **Step 4: Write failing attach and mid-drain degradation tests**

Modify `LiveCaptureControllerTest`:

```java
@Test void audioAttachFailureStartsActiveVideoWithSilentStereoTrack()
@Test void audioDrainFailureClosesTapOnceAndContinuesCurrentFrameWithSilence()
@Test void audioDrainFailureLogsOnceAcrossRemainingFrames()
@Test void replacementSilenceContinuesFailedHandlesExactClockPhase()
@Test void audioCloseFailureDuringStopDoesNotAbortValidVideo()
@Test void videoGrabSubmitAudioFileEncoderAndMuxFailuresStillAbortWholeRecorder()
@Test void failurePreservesMonotonicFrameIndexesAndActiveState()
@Test void debugWrapperFailureUsesTheSameClockedSilenceDegradationPath()
```

The mid-drain test submits frames `0,1,2`; snapshot the handle clock immediately before
every drain, throw on audio frame 1, and assert recorder
still receives all indexes, frame 1 and 2 contain clock-sized zeros, state remains
`ACTIVE`, and the failed handle closes exactly once.

- [ ] **Step 5: Run controller tests and verify behavioral RED**

Run:

```bash
mvn -Dtest=DebugFailAfterFramesAudioHandleTest,LiveCaptureControllerTest,AudioManagerLiveCaptureTest test
```

Expected: current tests fail because audio attach/drain/close failures enter `FAILED`
and abort the whole recorder.

- [ ] **Step 6: Implement audio-only degradation**

Change start ordering:

```java
try {
    audio = deps.audio.open(frameRate);
} catch (Throwable failure) {
    warnAudioOnce(failure);
    audio = new ClockedSilenceAudioHandle(deps.audioSampleRate.getAsInt(), frameRate);
}
recorder.start(..., audio.sampleRate());
```

Add `IntSupplier audioSampleRate` to `LiveCaptureController.Dependencies`. On drain
failure:

1. before the drain, record `AudioFrameClock.Snapshot phaseBeforeDrain = audio.clockSnapshot()`;
2. if drain throws after advancing its own clock, ignore that mutated phase;
3. close failed handle once, suppressing close failure into the warning;
4. replace with `ClockedSilenceAudioHandle.atPhase(phaseBeforeDrain)`;
5. drain silence for the failed current frame;
6. submit video and silence at the unchanged frame index.

Do not catch framebuffer grab, `recorder.submit`, encoder audio-file write, recorder
stop, or mux failures in this audio-only branch; those retain `failAndAbort`.

- [ ] **Step 7: Simplify manager live attachment and prove toggle invariance**

Delete the recording-lease runtime replacement from `AudioManager.beginLiveCaptureAudio`.
The method checks single attachment, calls `producer.attachCapture(frameRate)`, wraps
identity-safe close, and never changes producer, sink, registry, history, command
routing, reverse state, or voice cursors.

Add assertions:

```java
@Test void attachDetachLeavesProducerRegistrySinkAndVoiceCursorIdentitiesUnchanged()
@Test void attachDuringHeldReverseReceivesNextAudibleReversePacketImmediately()
@Test void repeatedAttachDetachDoesNotFlushSpeakerFifoOrResetMusic()
```

- [ ] **Step 8: Run focused controller, capture, and media tests**

Run:

```bash
mvn -Dtest=ClockedSilenceAudioHandleTest,DebugFailAfterFramesAudioHandleTest,AudioManagerLiveCaptureTest,LiveCaptureControllerTest,LiveCaptureMediaSmokeTest,LiveCapturePresentationCoordinatorTest,TestEngineLiveCapturePresentation test
```

Expected: all selected tests pass. The media smoke fixture must use ffprobe to assert a
stereo audio stream exists when attachment is forced to fail.

- [ ] **Step 9: Update changelog and commit exact files**

Add:

```markdown
- Live recording now continues with phase-correct stereo silence when its audio tap is unavailable or fails.
```

Commit:

```bash
git add CHANGELOG.md \
  CONFIGURATION.md \
  docs/architecture/designs/2026-07-23-unified-audio-presentation-design.md \
  src/main/java/com/openggf/Engine.java \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/main/java/com/openggf/audio/ClockedSilenceAudioHandle.java \
  src/main/java/com/openggf/audio/DebugFailAfterFramesAudioHandle.java \
  src/main/java/com/openggf/capture/LiveCaptureController.java \
  src/test/java/com/openggf/audio/AudioManagerLiveCaptureTest.java \
  src/test/java/com/openggf/audio/ClockedSilenceAudioHandleTest.java \
  src/test/java/com/openggf/audio/DebugFailAfterFramesAudioHandleTest.java \
  src/test/java/com/openggf/TestEngineLiveCapturePresentation.java \
  src/test/java/com/openggf/capture/LiveCaptureControllerTest.java \
  src/test/java/com/openggf/capture/LiveCaptureMediaSmokeTest.java \
  src/test/java/com/openggf/capture/LiveCapturePresentationCoordinatorTest.java
git commit -m "fix(capture): continue with clocked silence

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a"
```

---

### Task 12: Preserve offline trace capture over the same unified producer

**Files:**
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/capture/DrainPcmAudioTap.java`
- Modify: `src/main/java/com/openggf/tools/TraceCaptureTool.java`
- Modify: `src/main/java/com/openggf/tools/TraceCaptureSession.java`
- Modify: `src/main/java/com/openggf/tools/HeadlessGameBoot.java`
- Modify: `src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java`
- Modify: `src/test/java/com/openggf/capture/DrainPcmAudioTapTest.java`
- Create: `src/test/java/com/openggf/tools/TraceCaptureSessionTest.java`
- Create: `src/test/java/com/openggf/tools/TestTraceCaptureUnifiedAudio.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: unified producer and its non-consuming capture handles.
- Produces compatibility APIs:

```java
public synchronized void AudioManager.beginCaptureMode(int sampleRate, int frameRate);
public synchronized int AudioManager.drainCaptureFrame(short[] target);
public synchronized void AudioManager.endCaptureMode();
```

These methods attach/detach one compatibility handle; they never replace the producer or
audio runtime. `DrainPcmAudioTap` remains an `AudioFrameTap` over `drainCaptureFrame`.

- [ ] **Step 1: Rewrite capture-mode tests to assert identity and source completeness**

Modify `AudioManagerCaptureModeTest`:

```java
@Test void beginCaptureAttachesWithoutReplacingProducerRegistryOrSink()
@Test void drainReturnsExactlyTheMostRecentUnifiedPacketOnce()
@Test void secondDrainReturnsClockedSilenceRatherThanStalePcm()
@Test void endCaptureDetachesOnlyCompatibilityHandle()
@Test void offlinePacketContainsSmpsWavAndRawPcmTogether()
@Test void headlessCaptureNeverOpensAnAudioDevice()
```

Retain 48 kHz and 44.1 kHz cadence coverage. For differing requested rate, initialize
the headless producer to that rate before source admission; assert capture mode rejects a
rate change after voices have begun instead of migrating cursors.

- [ ] **Step 2: Run compatibility tests and verify RED**

Run:

```bash
mvn -Dtest=AudioManagerCaptureModeTest,DrainPcmAudioTapTest test
```

Expected: failures show `beginCaptureMode` still installs
`StreamBackedDeterministicAudioRuntime`.

- [ ] **Step 3: Reimplement compatibility APIs as one producer lease**

Replace `preCaptureRuntime`/`captureRuntime` with:

```java
private LiveCaptureAudioHandle offlineCaptureHandle;
```

`beginCaptureMode` asserts no existing lease and matching producer sample rate, attaches
at `frameRate`, and stores the handle. `drainCaptureFrame` delegates to it.
`endCaptureMode` closes it idempotently. Headless initialization constructs the normal
producer with `NoDeviceAudioSink`, so the same SMPS/WAV/PCM registry renders offline.

- [ ] **Step 4: Write failing trace tool/session lifecycle tests**

Create `TestTraceCaptureUnifiedAudio`:

```java
@Test void sessionStartAndFinishAttachAndDetachOneUnifiedHandle()
@Test void toolFastForwardDrainsOrDiscardsEveryPresentedPacketWithoutBacklog()
@Test void eachCapturedFramebufferExplicitlyTicksOneHeadlessOuterAudioBoundary()
@Test void traceFramesContainFinalSmpsWavAndPcmPackets()
@Test void sessionFailureClosesCaptureHandleAndRecorder()
@Test void traceCaptureAndLiveCaptureCannotDestructivelyDrainEachOther()
```

Use a producer spy and assert no `setDeterministicAudioRuntime`, runtime replacement, or
OpenAL device initialization occurs.

- [ ] **Step 5: Run trace tests and verify RED**

Run:

```bash
mvn -Dtest=TestTraceCaptureUnifiedAudio,TraceCaptureSessionTest test
```

Expected: lifecycle assertions fail until tool/session use the unified lease.

- [ ] **Step 6: Migrate tool and session**

Keep their public CLI/API behavior. Start the compatibility lease before
`recorder.start`. The tool/session runs its simulation step(s), explicitly calls
`AudioManager.presentFrame(FORWARD)` once at its headless outer framebuffer boundary,
then `DrainPcmAudioTap` drains that packet after framebuffer grab. `GameLoop.step()` and
`stepInternal()` do not produce it. In every `finally`, close in this order:

```java
try {
    recorder.stop();
} finally {
    GameServices.audio().endCaptureMode();
}
```

During clip fast-forward, distinguish skipped simulation-only steps from outer
framebuffer frames: enqueue commands for every simulation step, but call
`presentFrame(FORWARD)` and drain into the existing discard buffer exactly once for
each outer frame the tool considers presented. This prevents stale PCM at clip start
without multiplying audio cadence by the number of simulation steps.

- [ ] **Step 7: Verify offline and live capture suites**

Run:

```bash
mvn -Dtest=AudioManagerCaptureModeTest,DrainPcmAudioTapTest,TestTraceCaptureUnifiedAudio,TraceCaptureSessionTest,LiveCaptureControllerTest,AudioManagerLiveCaptureTest test
```

Expected: all selected tests pass.

- [ ] **Step 8: Update changelog and commit exact files**

Add:

```markdown
- TraceCaptureTool and TraceCaptureSession now capture the same final SMPS/WAV/PCM packets as live recording.
```

Commit:

```bash
git add CHANGELOG.md \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/main/java/com/openggf/capture/DrainPcmAudioTap.java \
  src/main/java/com/openggf/tools/HeadlessGameBoot.java \
  src/main/java/com/openggf/tools/TraceCaptureSession.java \
  src/main/java/com/openggf/tools/TraceCaptureTool.java \
  src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java \
  src/test/java/com/openggf/capture/DrainPcmAudioTapTest.java \
  src/test/java/com/openggf/tools/TraceCaptureSessionTest.java \
  src/test/java/com/openggf/tools/TestTraceCaptureUnifiedAudio.java
git commit -m "feat(capture): unify offline presentation audio

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 13: Remove superseded runtime switching, backend presentation state, and handoff code

**Files:**
- Delete: `src/main/java/com/openggf/audio/runtime/DeterministicAudioRuntime.java`
- Delete: `src/main/java/com/openggf/audio/runtime/NoOpDeterministicAudioRuntime.java`
- Delete: `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java`
- Delete: `src/main/java/com/openggf/audio/runtime/FrameAudioMode.java`
- Delete: `src/main/java/com/openggf/audio/runtime/LiveAudioCaptureTap.java`
- Delete: `src/main/java/com/openggf/audio/runtime/PresentationAudioCapture.java`
- Delete: `src/main/java/com/openggf/audio/runtime/AudioOutputFifo.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/AudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Delete: `src/main/java/com/openggf/audio/rewind/AudioBackendLogicalSnapshot.java`
- Modify: `src/main/java/com/openggf/audio/rewind/AudioLogicalSnapshot.java`
- Delete: `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntime.java`
- Delete: `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntimeCommands.java`
- Delete: `src/test/java/com/openggf/audio/runtime/TestLiveAudioCaptureTap.java`
- Delete: `src/test/java/com/openggf/audio/runtime/TestAudioOutputFifo.java`
- Delete: `src/test/java/com/openggf/audio/TestDeterministicAudioRuntimeBoundary.java`
- Create: `src/test/java/com/openggf/audio/TestAudioPresentationBoundary.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioManagerRuntimeInstallation.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`
- Modify: `src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java`
- Modify: `src/test/java/com/openggf/audio/AudioManagerLiveCaptureTest.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandQueue.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationProducer.java`
- Modify: `src/test/java/com/openggf/audio/presentation/TestAudioPresentationProducerRewind.java`
- Delete: `src/main/java/com/openggf/audio/PcmSampleStream.java`
- Modify: `docs/status/known-discrepancies.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Tasks 5–12 replacements for all runtime functionality.
- Produces: one production presentation architecture with no global LWJGL enable switch,
  recording lease switch, dual history owner, or per-buffer synthesis handoff.

Keep `AudioFrameClock` and `PcmHistoryRing`; they are unified producer primitives.

- [ ] **Step 1: Extend the architecture guard to fail on every superseded owner**

Add exact source assertions:

```java
@Test void onlyProducerOwnsAudioFrameClockAndPcmHistory()
@Test void noRuntimeInstallationOrCaptureLeaseSwitchRemains()
@Test void backendHasNoPresentationHandoffOrReverseCursor()
@Test void noVoiceWritesDirectlyToOpenAl()
```

Scan for forbidden production tokens:

```text
setDeterministicAudioRuntime
applyDeterministicAudioRuntime
supportsDeterministicRuntimePresentation
supportsLiveCapturePresentation
presentationHandoff
handoffMusicData
handoffSfxData
runtimeProvidesPresentationPcm
deferredLiveCaptureRuntime
preCaptureRuntime
captureRuntime
```

Allow `AudioFrameClock` construction only in `AudioPresentationProducer`,
`ClockedSilenceAudioHandle`, and producer capture-handle internals. Allow
`PcmHistoryRing` ownership only in `AudioPresentationProducer`.

- [ ] **Step 2: Run the guard and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationArchitectureGuard test
```

Expected: FAIL listing current runtime and backend handoff owners.

- [ ] **Step 3: Remove superseded production classes and backend hooks**

Delete the listed runtime classes except `AudioFrameClock` and `PcmHistoryRing`.
Remove their fields/methods from `AudioManager`, `AudioBackend`, and
`AbstractSmpsAudioBackend`. Remove backend-owned:

- PCM history and reverse cursor;
- deterministic-runtime attachment;
- presentation handoff arrays/frame id;
- dual stream fill/mix and runtime ownership branches;
- live-capture support flags.

Delete `AudioBackendLogicalSnapshot.java`, remove the `backend` field and constructor
argument from `AudioLogicalSnapshot`, and update every capture/restore call and test to
use only `AudioPresentationSnapshot presentation`.

Retain source construction, SMPS sequencing helpers required by
`AudioPresentationSourceFactory`, logical source descriptors, profile routing, and
manager-facing compatibility methods implemented over the producer.

- [ ] **Step 4: Replace obsolete runtime tests with producer boundary assertions**

Move still-relevant assertions from deleted runtime tests into:

- `TestAudioPresentationProducer` for packet cadence/mixing;
- `TestAudioPresentationProducerRewind` for history/rate/crossfade;
- `TestAudioPresentationCommandQueue` for command ordering/reentrancy;
- `AudioManagerCaptureModeTest` for offline compatibility;
- `AudioManagerLiveCaptureTest` for live attachment.

Delete `TestDeterministicAudioRuntimeBoundary` and create
`TestAudioPresentationBoundary` with:

```java
@Test void audioManagerIsTheOnlyPresentationProducerEntryPoint()
```

Change `TestAudioManagerRuntimeInstallation` assertions to:

```java
@Test void backendReplacementChangesOnlyTheSinkAndPreservesLogicalVoices()
@Test void failedDeviceInitializationInstallsNoDeviceSink()
```

- [ ] **Step 5: Remove the known limitation and verify no stale references**

Update `docs/status/known-discrepancies.md` by deleting the live-recording split-audio/runtime-switch
limitation and add to `CHANGELOG.md`:

```markdown
- Removed the temporary deterministic-runtime and live-recording lease switches superseded by unified presentation audio.
```

Run:

```bash
rg -n "DeterministicAudioRuntime|FrameAudioMode|PresentationAudioCapture|presentationHandoff|runtimeProvidesPresentationPcm|supportsLiveCapturePresentation" src/main src/test
```

Expected: no matches.

- [ ] **Step 6: Run the complete audio/capture/rewind subset**

Run:

```bash
mvn -Dtest='com.openggf.audio.**,com.openggf.capture.**,TestGameLoopAudioPresentationModes,TestEngineLiveCapturePresentation,TestLiveRewindManagerAudioCleanup,TestHeldRewindAudioStepCost,TestTraceCaptureUnifiedAudio' test
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit exact cleanup files**

Run:

```bash
git add -A CHANGELOG.md \
  docs/status/known-discrepancies.md \
  src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
  src/main/java/com/openggf/audio/AudioBackend.java \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/main/java/com/openggf/audio/PcmSampleStream.java \
  src/main/java/com/openggf/audio/rewind/AudioBackendLogicalSnapshot.java \
  src/main/java/com/openggf/audio/rewind/AudioLogicalSnapshot.java \
  src/main/java/com/openggf/audio/runtime/AudioOutputFifo.java \
  src/main/java/com/openggf/audio/runtime/DeterministicAudioRuntime.java \
  src/main/java/com/openggf/audio/runtime/FrameAudioMode.java \
  src/main/java/com/openggf/audio/runtime/LiveAudioCaptureTap.java \
  src/main/java/com/openggf/audio/runtime/NoOpDeterministicAudioRuntime.java \
  src/main/java/com/openggf/audio/runtime/PresentationAudioCapture.java \
  src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java \
  src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java \
  src/test/java/com/openggf/audio/AudioManagerLiveCaptureTest.java \
  src/test/java/com/openggf/audio/TestAudioManagerRuntimeInstallation.java \
  src/test/java/com/openggf/audio/TestAudioPresentationBoundary.java \
  src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java \
  src/test/java/com/openggf/audio/TestDeterministicAudioRuntimeBoundary.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationCommandQueue.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationProducer.java \
  src/test/java/com/openggf/audio/presentation/TestAudioPresentationProducerRewind.java \
  src/test/java/com/openggf/audio/runtime/TestAudioOutputFifo.java \
  src/test/java/com/openggf/audio/runtime/TestLiveAudioCaptureTap.java \
  src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntime.java \
  src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntimeCommands.java
git commit -m "refactor(audio): remove split presentation runtime

Changelog: updated
Guide: n/a
Known-Discrepancies: updated
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Before committing, verify `git diff --cached --name-only` contains no path outside the
exact Task 13 file list. If moving relevant tests changes additional test files, list
each path in the task review report and stage it explicitly instead of broadening the
commit silently.

---

### Task 14: Prove automated source, mode, failure, and ROM parity

**Files:**
- Create: `src/test/java/com/openggf/audio/TestUnifiedAudioPresentationIntegration.java`
- Create: `src/test/java/com/openggf/TestSonic1UnifiedAudioPresentationRomIntegration.java`
- Create: `src/test/java/com/openggf/TestSonic2UnifiedAudioPresentationRomIntegration.java`
- Create: `src/test/java/com/openggf/TestSonic3kUnifiedAudioPresentationRomIntegration.java`
- Modify: `src/test/java/com/openggf/tests/TestRomAudioIntegration.java`
- Modify: `src/test/java/com/openggf/TestEngineLiveCapturePresentation.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: completed unified producer, sink, live/offline capture, rewind, and all
  three game audio profiles.
- Produces: automated source/mode/failure and ROM-optional final-PCM evidence.

- [ ] **Step 1: Write failing source-presence and toggle-continuity integration tests**

Create `TestUnifiedAudioPresentationIntegration`:

```java
@Test void titleAndGameplayPacketsRemainNonZeroBeforeDuringAndAfterCapture()
@Test void specialStageRingAndSfxRemainInSpeakerAndCapturePcmDuringRecording()
@Test void smpsWavPitchedSfxAndSegaPcmAllReachFinalPacket()
@Test void togglePreservesEveryLogicalVoiceIdentityCursorAndSpeakerQueue()
@Test void startDuringHeldRewindImmediatelyMatchesSpeakerReversePcm()
@Test void pauseFrameStepAndModalSubmitFreshSilence()
@Test void releaseAppliesTransientPolicyAndOneCrossfade()
@Test void repeatedRecordingDoesNotLeakTapOrVoiceState()
@Test void deviceFailurePreservesMixerHistoryRewindAndCapture()
@Test void ordinaryEngineExceptionFinalizesRecorderAndCleansAudioInOrder()
```

Do not mock command dispatch in source-presence tests. Feed actual source factory assets,
present packets, and assert non-zero source-specific PCM contributions.

- [ ] **Step 2: Run integration tests and verify RED if any acceptance path is missing**

Run:

```bash
mvn -Dtest=TestUnifiedAudioPresentationIntegration test
```

Expected: all implemented paths pass; any failure identifies an acceptance gap to fix
in its owning task's files before continuing. The Task 14 commit must not carry a
production behavior fix without a fresh task review.

- [ ] **Step 3: Add ROM-backed final-PCM assertions for all games**

Create three top-level package-`com.openggf` classes (not nested classes and not under
`com.openggf.audio`) so each has one unambiguous class-level ROM condition and package
access to `TraceSessionLauncher` without widening production:

```java
@RequiresRom(SonicGame.SONIC_1)
class TestSonic1UnifiedAudioPresentationRomIntegration {
    @Test void sonic1TitleGameplayRingAndSpecialStageProduceFinalPcm() {}
}

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2UnifiedAudioPresentationRomIntegration {
    @Test void sonic2TitleGameplayRingAndSpecialStageProduceFinalPcm() {}
}

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kUnifiedAudioPresentationRomIntegration {
    @Test void sonic3kTitleGameplayRingAndSpecialStageProduceFinalPcm() {}
}
```

Use `TestEnvironment.currentRom()` infrastructure already used by
`TestRomAudioIntegration`. Reuse the boot/entry patterns from
`TestS1SpecialStageHeadlessBoot`, `TestS3kSpecialStageHeadlessBoot`,
`TestGameLoopSpecialStageEntryPresentation`, and
`TraceSessionLauncher.enterSpecialStageTrace(...)`.

Each test boots its actual `Sonic1`, `Sonic2`, or `Sonic3k` module from the injected ROM,
plays the module's mapped title/level/`GameMusic.SPECIAL_STAGE` route, triggers
`AudioManager.playSfx(GameSound.RING)` twice to cover left/right alternation, enters the
available special-stage provider through the cited helper, and presents frames through
the real manager via `HeadlessTestRunner`'s explicit outer boundary; do not call
`presentFrame` a second time in the test. Capture at least one final-PCM frame per event and assert non-zero
energy plus speaker/capture sample equality. It must not pass merely because an
`AudioCommand` was queued.

- [ ] **Step 4: Run the automated and ROM-optional parity suites**

Discover only root ROMs:

```bash
find . -maxdepth 1 -type f -name '*.gen' -print
```

Run with only the matching properties for hashes actually present:

```bash
mvn -Dtest=TestUnifiedAudioPresentationIntegration,TestSonic1UnifiedAudioPresentationRomIntegration,TestSonic2UnifiedAudioPresentationRomIntegration,TestSonic3kUnifiedAudioPresentationRomIntegration,TestRomAudioIntegration,TestEngineLiveCapturePresentation,TestAudioPresentationArchitectureGuard test
```

Expected: all non-assumed tests pass; each present verified ROM test passes. Missing ROMs
are reported as assumptions, never passes.

- [ ] **Step 5: Commit exact automated evidence**

Add:

```markdown
- Added final-PCM integration and ROM-backed source/toggle/rewind parity coverage.
```

Commit:

```bash
git add CHANGELOG.md \
  src/test/java/com/openggf/TestEngineLiveCapturePresentation.java \
  src/test/java/com/openggf/TestSonic1UnifiedAudioPresentationRomIntegration.java \
  src/test/java/com/openggf/TestSonic2UnifiedAudioPresentationRomIntegration.java \
  src/test/java/com/openggf/TestSonic3kUnifiedAudioPresentationRomIntegration.java \
  src/test/java/com/openggf/audio/TestAudioPresentationArchitectureGuard.java \
  src/test/java/com/openggf/audio/TestUnifiedAudioPresentationIntegration.java \
  src/test/java/com/openggf/tests/TestRomAudioIntegration.java
git commit -m "test(audio): prove unified source and ROM parity

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 15: Prove zero-allocation steady state and playable media continuity

**Files:**
- Create: `src/test/java/com/openggf/audio/TestAudioPresentationAllocationBudget.java`
- Modify: `src/test/java/com/openggf/capture/LiveCaptureMediaSmokeTest.java`
- Modify: `src/test/java/com/openggf/audio/AudioBenchmarkMemoryProbe.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 14 automated final-PCM fixtures.
- Produces: exact warmed allocation, bounded long-run, FFmpeg, and ffprobe evidence.

- [ ] **Step 1: Add allocation and long-run bounds tests**

Create `TestAudioPresentationAllocationBudget`:

```java
@Test void warmedForwardMixAllocatesNoPerFrameVoiceOrPacketArrays()
@Test void oneHourNoDeviceRunKeepsAllQueueAndVoiceCountsBounded()
@Test void stalledDeviceAndCaptureContinueWithoutProducerBlocking()
@Test void commandAndDeferredOverflowPreserveStructuralState()
```

Warm all buffers and JIT paths for 10,000 frames first. Use
`AudioBenchmarkMemoryProbe.measureTimedRun` for a second 10,000-frame loop. When
`RunResult.allocatedBytesSupported()` is false, skip only the byte-counter assertion
with a JUnit assumption; otherwise assert:

```java
assertEquals(0L, result.allocatedBytes(),
        "warmed producer allocated in its per-frame presentation loop");
```

Also capture mixer/view/buffer identities before and after and assert no growth in
mixer buffers, queue arrays, voice arrays, history storage, or speaker FIFO. The
identity/bound assertions always run even when the JVM byte counter is unavailable.

- [ ] **Step 2: Run allocation tests and verify RED**

Run:

```bash
mvn -Dtest=TestAudioPresentationAllocationBudget test
```

Expected: FAIL with non-zero warmed allocation until frame records/wrappers and
per-voice scratch allocation are eliminated.

- [ ] **Step 3: Extend media smoke tests for real and failed audio**

Generate two short MKVs:

1. mixed SMPS + WAV + PCM final audio;
2. injected tap failure after frame 17, followed by clocked silence.

Run ffprobe in the test and assert:

```java
assertEquals("flac", audio.codecName());
assertEquals(2, audio.channels());
assertEquals(video.frameCount(), submittedFrameCount);
assertTrue(Math.abs(audio.durationSeconds() - video.durationSeconds())
        <= 1.0 / sampleRate);
```

Decode the failure fixture with FFmpeg and assert samples before failure are non-zero,
all remaining samples are zero, timestamps remain monotonic, and video frames continue.

- [ ] **Step 4: Run media and allocation suites**

```bash
mvn -Dtest=TestAudioPresentationAllocationBudget,LiveCaptureMediaSmokeTest test
```

Expected: allocation tests pass; FFmpeg/ffprobe tests pass. Media tests may use JUnit
assumptions only when the executable is absent.

- [ ] **Step 5: Commit exact performance and media evidence**

Add:

```markdown
- Proved zero-allocation warmed presentation and clock-continuous mixed/silent FLAC media.
```

Commit:

```bash
git add CHANGELOG.md \
  src/test/java/com/openggf/audio/AudioBenchmarkMemoryProbe.java \
  src/test/java/com/openggf/audio/TestAudioPresentationAllocationBudget.java \
  src/test/java/com/openggf/capture/LiveCaptureMediaSmokeTest.java
git commit -m "test(audio): verify allocation and media continuity

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 16: Complete full-suite evidence, user-assisted listening, docs, and final review

**Files:**
- Modify: `docs/architecture/designs/2026-07-23-live-av-recording-design.md`
- Modify: `docs/architecture/validation/2026-07-23-live-av-recording-report.md`
- Create: `docs/architecture/validation/2026-07-23-unified-audio-presentation-report.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: green reviewed Tasks 1–15.
- Produces: final reproducible validation report and user-supplied audible-listening evidence.

- [ ] **Step 1: Run the complete Maven test suite**

Run:

```bash
mvn test
```

Expected: zero failures and zero errors. Record the exact passed/skipped counts in the
validation report. If the suite updates `docs/status/rewind-round-trip-gaps.md`, inspect the diff and
restore it unless the unified audio change genuinely altered rewind coverage.

- [ ] **Step 2: Run package and architecture verification**

Run:

```bash
mvn package
rg -n "alGenSources|alSourcePlay|AL_LOOPING|AL_PITCH" src/main/java/com/openggf/audio
rg -n "DeterministicAudioRuntime|runtimeProvidesPresentationPcm|deferredLiveCaptureRuntime" src/main src/test
git diff --check
```

Expected:

- package succeeds;
- OpenAL source tokens occur only in `OpenAlPcmSink` and refer to one final-PCM source;
- superseded runtime tokens have no matches;
- `git diff --check` prints nothing.

- [ ] **Step 3: Obtain the required user-assisted three-game listening matrix**

Audible listening cannot be delegated to a subagent or inferred from PCM energy.
First discover and hash root-level `.gen` files using the AGENTS.md hashes. A final
`PASS` requires one verified root ROM for each of S1, S2, and S3K and a user-confirmed
row for all three; a missing game ROM is `NOT RUN` and means the branch is not ready
for final approval. Do not weaken this to "all available games."

Provide the user with the worktree path and ask them to run `./dev.sh` against each of
the three verified root ROMs. The user performs and reports:

1. SEGA/title audio is audible;
2. enter gameplay and a special stage where available;
3. start Shift+O during active music and repeatedly trigger rings/SFX;
4. confirm speaker audio remains present during recording;
5. stop and play the MKV; confirm music/rings/SFX are captured;
6. hold rewind, press Shift+O while still held, then release rewind;
7. pause, frame-step, stop, and play the second MKV.
8. for that game, restart once with the documented dev-only injection command:

```bash
JAVA_TOOL_OPTIONS='-Dopenggf.debug.liveCaptureAudioFailAfterFrames=120' ./dev.sh
```

Start Shift+O, continue playing beyond 120 presented frames, then stop recording.
Confirm gameplay and video continue, speaker audio remains present, and the resulting
MKV changes from captured audio to correctly timed silence after the injected tap
failure. Remove `JAVA_TOOL_OPTIONS` for every ordinary run.

The agent records the verified ROM hash, game, path redacted to basename, ordinary and
injected output MKV basenames, ffprobe durations, and the user's exact pass/fail
observation for each row.
The agent may run ffprobe and inspect decoded PCM, but must not convert that into an
audibility claim. A missing ROM or unanswered listening/injection row is `NOT RUN`,
never a pass. Final approval pauses until all three game rows and their injected-silence
checks are user-confirmed. The hook is documented in Task 11, disabled by default, and
affects only the recording tap.

- [ ] **Step 4: Align prior design/report and write the final validation report**

Update the live-recording design/report to replace the temporary deterministic-runtime
lease wording with the unified producer/tap and clocked-silence behavior. Write
`2026-07-23-unified-audio-presentation-report.md` with:

```markdown
# Unified Audio Presentation Validation Report

## Reviewed range
## Automated test evidence
## Architecture-guard evidence
## Media and ffprobe evidence
## Three-game ROM matrix
## Failure-injection evidence
## Remaining limitations
## Acceptance criteria
```

Every acceptance criterion from the authoritative spec gets `PASS`, `FAIL`, or
`NOT RUN`, followed by the exact command/manual observation. Do not describe a missing
ROM row as green.

- [ ] **Step 5: Request final independent review and loop until green**

Generate the review package from the unified-audio spec baseline through current HEAD
plus the uncommitted Task 16 documentation diff. Ask a fresh reviewer to verify:

- all goals and non-goals;
- source parity, immediate held-rewind start, silence degradation;
- queue/deferred bounds and original ordering;
- exactly one producer call per presented frame;
- no independent OpenAL sources or capture mirror;
- trace compatibility;
- exception/shutdown cleanup;
- automated/media/manual evidence.

For every Critical or Important finding, create a focused failing test, implement the
smallest fix in the owning file, rerun its focused suite, regenerate the package, and
request another fresh review. Continue until the reviewer returns no Critical or
Important findings.

- [ ] **Step 6: Commit exact validation documents**

Add to `CHANGELOG.md`:

```markdown
- Validated uninterrupted title, gameplay, special-stage, rewind, and recording audio across the unified presentation path.
```

Commit:

```bash
git add CHANGELOG.md \
  docs/architecture/designs/2026-07-23-live-av-recording-design.md \
  docs/architecture/validation/2026-07-23-live-av-recording-report.md \
  docs/architecture/validation/2026-07-23-unified-audio-presentation-report.md
git commit -m "docs: validate unified audio presentation

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

- [ ] **Step 7: Verify the committed branch is clean and reproducible**

Run:

```bash
git status --short --untracked-files=no
git log -1 --format='%H%n%B'
mvn test
mvn package
```

Expected: clean tracked worktree, policy trailers present, zero test failures/errors,
and successful package. Update only the exact result counts/commit hash in the report
if the final rerun differs, amend the validation commit, then rerun
`git status --short --untracked-files=no`.

---

## Task execution and review protocol

For every task:

1. A fresh implementer subagent receives only the authoritative spec, this plan, the
   task number, current base SHA, and the current worktree path.
2. The implementer follows each RED/GREEN step, records commands/results, stages only
   listed files, and commits with the exact policy trailers.
3. A fresh reviewer receives the task brief, base/head SHAs, implementation report, and
   bounded diff package; it reviews plan compliance and code quality.
4. Critical or Important findings return to a fixing subagent, focused tests rerun, and
   a fresh reviewer examines the updated range.
5. The next task starts only after the current review is green and
   `git status --short --untracked-files=no` is empty.

No implementation task may restore a legacy recording-triggered runtime switch as a
temporary workaround. The only allowed migration overlap is Task 7's continuously
ticked inaudible shadow, which Task 9 removes by atomically making its proven final PCM
the live sink input.
