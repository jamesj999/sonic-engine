package com.openggf.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestAudioBackendLifetime {

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
    }

    @Test
    void setBackendDestroysCandidateWhenInitFailsBeforeFallingBack() {
        FailingBackend candidate = new FailingBackend();

        AudioManager.getInstance().setBackend(candidate);

        assertEquals(1, candidate.initCalls);
        assertEquals(1, candidate.destroyCalls);
        assertInstanceOf(NullAudioBackend.class, AudioManager.getInstance().getBackend());
    }

    @Test
    void speakerInitializationFailureKeepsTheSelectedBackendAndMixerAlive() {
        SinkFailingBackend candidate = new SinkFailingBackend();

        AudioManager.getInstance().setBackend(candidate);
        AudioManager.getInstance().presentFrame(
                com.openggf.audio.presentation.PresentationMode.SILENT);

        assertSame(candidate, AudioManager.getInstance().getBackend());
        assertEquals(1, candidate.initCalls);
        assertEquals(0, candidate.destroyCalls);
    }

    private static final class FailingBackend extends NullAudioBackend {
        int initCalls;
        int destroyCalls;

        @Override
        public void init() {
            initCalls++;
            throw new IllegalStateException("simulated init failure");
        }

        @Override
        public void destroy() {
            destroyCalls++;
        }
    }

    private static final class SinkFailingBackend
            extends NullAudioBackend {
        int initCalls;
        int destroyCalls;

        @Override public void init() {
            initCalls++;
        }

        @Override
        public com.openggf.audio.output.AudioPresentationSink
                createPresentationSink(
                java.util.function.Consumer<Throwable> failureHandler,
                java.util.function.Consumer<String> warningHandler) {
            throw new IllegalStateException("simulated device failure");
        }

        @Override public void destroy() {
            destroyCalls++;
        }
    }
}
