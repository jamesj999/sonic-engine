package com.openggf.audio.output;

import com.openggf.audio.presentation.AudioPresentationFrameView;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bounded final-PCM speaker sink. The device receives fixed-size stereo
 * buffers and never owns a mixer voice, decoder, or presentation cursor.
 */
public final class OpenAlPcmSink implements AudioPresentationSink {
    public static final int DEVICE_BUFFER_FRAMES = 1_024;
    private static final long WARNING_INTERVAL_NANOS = 1_000_000_000L;
    private static final int OPENAL_BUFFER_COUNT = 8;
    // Match the former streamer: one 21 ms buffer is not enough to absorb
    // ordinary game-loop scheduling jitter without stopping the AL source.
    private static final int DEVICE_QUEUE_TARGET = 3;
    private static final int DEVICE_PRIME_FRAMES =
            DEVICE_BUFFER_FRAMES * DEVICE_QUEUE_TARGET;

    public interface Device {
        int initialize();

        void enqueue(short[] stereoPcm, int stereoFrames, int sampleRate);

        int update();

        void flush();

        void pause();

        void resume();

        void close();
    }

    private final Device device;
    private final Consumer<Throwable> failureHandler;
    private final LongSupplier nanoTime;
    private final Consumer<String> warning;
    private final int sampleRate;
    private final SpeakerPacketFifo fifo;
    private final short[] packetScratch;
    private final short[] deviceScratch =
            new short[DEVICE_BUFFER_FRAMES * 2];

    private boolean warned;
    private long lastWarningNanos;
    private boolean paused;
    private boolean failed;
    private boolean closed;

    public OpenAlPcmSink(
            Device device,
            Consumer<Throwable> failureHandler,
            LongSupplier nanoTime,
            Consumer<String> warning) {
        this.device = Objects.requireNonNull(device, "device");
        this.failureHandler =
                Objects.requireNonNull(failureHandler, "failureHandler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.warning = Objects.requireNonNull(warning, "warning");
        int initializedRate;
        try {
            initializedRate = device.initialize();
        } catch (Throwable failure) {
            closeDeviceAfterFailure(failure);
            throw failure;
        }
        if (initializedRate <= 0) {
            IllegalStateException failure = new IllegalStateException(
                    "OpenAL device returned an invalid sample rate");
            closeDeviceAfterFailure(failure);
            throw failure;
        }
        sampleRate = initializedRate;
        fifo = new SpeakerPacketFifo(sampleRate);
        packetScratch = new short[Math.multiplyExact(sampleRate, 2)];
    }

    public static OpenAlPcmSink openDefault(
            Consumer<Throwable> failureHandler,
            Consumer<String> warning) {
        return new OpenAlPcmSink(new LwjglDevice(), failureHandler,
                System::nanoTime, warning);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public void accept(AudioPresentationFrameView frame) {
        Objects.requireNonNull(frame, "frame");
        if (closed || failed || paused) {
            return;
        }
        int stereoFrames = frame.stereoFrames();
        if (stereoFrames > sampleRate) {
            fail(new IllegalArgumentException(
                    "presentation packet exceeds one second"));
            return;
        }
        frame.copyTo(packetScratch, 0);
        long droppedBefore = fifo.droppedStereoFrames();
        fifo.offer(packetScratch, stereoFrames);
        if (fifo.droppedStereoFrames() != droppedBefore) {
            warnOverrun();
        }
    }

    public void updateDevice() {
        if (closed || failed || paused) {
            return;
        }
        try {
            int deviceQueuedBuffers = device.update();
            int requiredFrames = deviceQueuedBuffers == 0
                    ? DEVICE_PRIME_FRAMES : DEVICE_BUFFER_FRAMES;
            while (deviceQueuedBuffers < DEVICE_QUEUE_TARGET
                    && fifo.queuedStereoFrames() >= requiredFrames) {
                int drained = fifo.drain(
                        deviceScratch, DEVICE_BUFFER_FRAMES);
                device.enqueue(deviceScratch, drained, sampleRate);
                deviceQueuedBuffers++;
                requiredFrames = DEVICE_BUFFER_FRAMES;
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    @Override
    public void onReverseBoundary() {
        if (closed || failed) {
            return;
        }
        fifo.flush();
        try {
            device.flush();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    public void pause() {
        if (!closed && !failed && !paused) {
            paused = true;
            try {
                device.pause();
            } catch (Throwable failure) {
                fail(failure);
            }
        }
    }

    public void resume() {
        if (!closed && !failed && paused) {
            paused = false;
            try {
                device.resume();
            } catch (Throwable failure) {
                fail(failure);
            }
        }
    }

    public int queuedStereoFrames() {
        return fifo.queuedStereoFrames();
    }

    public long droppedStereoFrames() {
        return fifo.droppedStereoFrames();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        fifo.flush();
        device.close();
    }

    private void warnOverrun() {
        long now = nanoTime.getAsLong();
        if (!warned || now - lastWarningNanos >= WARNING_INTERVAL_NANOS) {
            warned = true;
            lastWarningNanos = now;
            warning.accept("OpenAL speaker FIFO overrun; dropped oldest PCM");
        }
    }

    private void fail(Throwable failure) {
        if (failed || closed) {
            return;
        }
        failed = true;
        fifo.flush();
        try {
            close();
        } catch (Throwable cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        failureHandler.accept(failure);
    }

    private void closeDeviceAfterFailure(Throwable primary) {
        try {
            device.close();
        } catch (Throwable cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private static final class LwjglDevice implements Device {
        private long device;
        private long context;
        private int presentationSource = -1;
        private final int[] buffers = new int[OPENAL_BUFFER_COUNT];
        private final int[] freeBuffers = new int[OPENAL_BUFFER_COUNT];
        private int freeCount;
        private ShortBuffer uploadScratch;
        private boolean initialized;
        private boolean closed;

        @Override
        public int initialize() {
            if (initialized) {
                throw new IllegalStateException(
                        "OpenAL device is already initialized");
            }
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0) {
                throw new IllegalStateException(
                        "Could not open the default OpenAL device");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer attributes = stack.ints(
                        ALC10.ALC_FREQUENCY, 48_000, 0);
                context = ALC10.alcCreateContext(device, attributes);
            }
            if (context == 0 || !ALC10.alcMakeContextCurrent(context)) {
                throw new IllegalStateException(
                        "Could not create the OpenAL context");
            }
            ALCCapabilities capabilities = ALC.createCapabilities(device);
            AL.createCapabilities(capabilities);
            presentationSource = AL10.alGenSources();
            for (int index = 0; index < buffers.length; index++) {
                buffers[index] = AL10.alGenBuffers();
                freeBuffers[index] = buffers[index];
            }
            freeCount = buffers.length;
            uploadScratch = MemoryUtil.memAllocShort(
                    DEVICE_BUFFER_FRAMES * 2);
            int negotiated = ALC10.alcGetInteger(
                    device, ALC10.ALC_FREQUENCY);
            initialized = true;
            return negotiated > 0 ? negotiated : 48_000;
        }

        @Override
        public void enqueue(
                short[] stereoPcm, int stereoFrames, int sampleRate) {
            reclaimProcessed();
            if (freeCount == 0) {
                throw new IllegalStateException(
                        "OpenAL presentation queue exhausted");
            }
            int buffer = freeBuffers[--freeCount];
            uploadScratch.clear();
            uploadScratch.put(stereoPcm, 0, stereoFrames * 2);
            uploadScratch.flip();
            AL10.alBufferData(buffer, AL10.AL_FORMAT_STEREO16,
                    uploadScratch, sampleRate);
            AL10.alSourceQueueBuffers(presentationSource, buffer);
            int state = AL10.alGetSourcei(
                    presentationSource, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING) {
                AL10.alSourcePlay(presentationSource);
            }
            checkError("enqueue final PCM");
        }

        @Override
        public int update() {
            reclaimProcessed();
            checkError("update final PCM");
            return AL10.alGetSourcei(
                    presentationSource, AL10.AL_BUFFERS_QUEUED);
        }

        @Override
        public void flush() {
            if (!initialized || presentationSource < 0) {
                return;
            }
            AL10.alSourceStop(presentationSource);
            int queued = AL10.alGetSourcei(
                    presentationSource, AL10.AL_BUFFERS_QUEUED);
            while (queued-- > 0) {
                AL10.alSourceUnqueueBuffers(presentationSource);
            }
            System.arraycopy(buffers, 0, freeBuffers, 0, buffers.length);
            freeCount = buffers.length;
            checkError("flush final PCM");
        }

        @Override
        public void pause() {
            if (initialized && presentationSource >= 0) {
                AL10.alSourcePause(presentationSource);
            }
        }

        @Override
        public void resume() {
            if (initialized && presentationSource >= 0
                    && AL10.alGetSourcei(presentationSource,
                    AL10.AL_BUFFERS_QUEUED) > 0) {
                AL10.alSourcePlay(presentationSource);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (presentationSource >= 0) {
                AL10.alSourceStop(presentationSource);
                AL10.alDeleteSources(presentationSource);
                presentationSource = -1;
            }
            Arrays.stream(buffers)
                    .filter(buffer -> buffer != 0)
                    .forEach(AL10::alDeleteBuffers);
            if (uploadScratch != null) {
                MemoryUtil.memFree(uploadScratch);
                uploadScratch = null;
            }
            if (context != 0) {
                ALC10.alcMakeContextCurrent(0);
                ALC10.alcDestroyContext(context);
                context = 0;
            }
            if (device != 0) {
                ALC10.alcCloseDevice(device);
                device = 0;
            }
            initialized = false;
        }

        private void reclaimProcessed() {
            if (!initialized || presentationSource < 0) {
                return;
            }
            int processed = AL10.alGetSourcei(
                    presentationSource, AL10.AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                if (freeCount == freeBuffers.length) {
                    throw new IllegalStateException(
                            "OpenAL free-buffer accounting overflow");
                }
                freeBuffers[freeCount++] =
                        AL10.alSourceUnqueueBuffers(presentationSource);
            }
        }

        private static void checkError(String operation) {
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) {
                throw new IllegalStateException(
                        "OpenAL error during " + operation + ": " + error);
            }
        }
    }
}
