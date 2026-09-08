package com.openggf.capture;

import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.runtime.AudioFrameClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LiveCapturePresentationCoordinatorTest {
    @Test
    void presentOrdersCaptureThenScreenshotThenIndicator() {
        List<String> calls = new ArrayList<>();
        LiveCaptureController controller = noOpController(calls);
        LiveCapturePresentationCoordinator coordinator =
                new LiveCapturePresentationCoordinator(controller, calls::add);

        coordinator.present(new CaptureViewport(0, 0, 320, 224),
                () -> { }, () -> { });

        assertEquals(List.of("capture", "screenshot", "indicator"), calls);
        controller.close();
    }

    @Test
    void inactiveCaptureStillRunsScreenshotAndIndicatorSeam() {
        List<String> calls = new ArrayList<>();
        LiveCaptureController controller = noOpController(calls);
        new LiveCapturePresentationCoordinator(controller, calls::add)
                .present(new CaptureViewport(0, 0, 320, 224),
                        () -> { }, () -> { });
        assertEquals(List.of("capture", "screenshot", "indicator"), calls);
        controller.close();
    }

    @Test
    void activePresentationDrainsAndSubmitsExactlyOnce() throws Exception {
        Harness harness = new Harness();
        harness.controller.start(harness.viewport, 60);

        new LiveCapturePresentationCoordinator(harness.controller)
                .present(harness.viewport, () -> { }, () -> { });

        verify(harness.audio, times(1)).drainPresentationFrame(any(short[].class));
        verify(harness.recorder, times(1)).submit(any(CapturedFrame.class));
        harness.controller.close();
    }

    @Test
    void stopEdgePresentationSubmitsZeroFrames() throws Exception {
        Harness harness = new Harness();
        harness.controller.start(harness.viewport, 60);
        harness.controller.requestStop(LiveCaptureController.StopReason.USER);

        new LiveCapturePresentationCoordinator(harness.controller)
                .present(harness.viewport, () -> { }, () -> { });

        verify(harness.recorder, never()).submit(any(CapturedFrame.class));
        harness.controller.close();
    }

    @Test
    void viewportOriginOnlyChangeStopsBeforeGrab() throws Exception {
        Harness harness = new Harness();
        harness.controller.start(harness.viewport, 60);

        new LiveCapturePresentationCoordinator(harness.controller)
                .present(new CaptureViewport(1, 0, 320, 224), () -> { }, () -> { });

        verify(harness.grabber, never()).grab();
        verify(harness.recorder, never()).submit(any(CapturedFrame.class));
        harness.controller.close();
    }

    private static final class Harness {
        private final CaptureViewport viewport = new CaptureViewport(0, 0, 320, 224);
        private final LiveCaptureAudioHandle audio = mock(LiveCaptureAudioHandle.class);
        private final AudioFrameClock audioClock =
                new AudioFrameClock(48_000, 60);
        private final VideoFrameGrabber grabber = mock(VideoFrameGrabber.class);
        private final CaptureRecorder recorder = mock(CaptureRecorder.class);
        private final LiveCaptureController controller;

        private Harness() {
            when(audio.sampleRate()).thenReturn(48_000);
            when(audio.frameRate()).thenReturn(60);
            when(audio.maxStereoFramesPerPacket()).thenReturn(800);
            when(audio.drainPresentationFrame(any(short[].class)))
                    .thenAnswer(ignored -> audioClock.samplesForNextFrame());
            when(audio.totalStereoFrames())
                    .thenAnswer(ignored -> audioClock.totalSamplesProduced());
            when(audio.clockSnapshot())
                    .thenAnswer(ignored -> audioClock.captureSnapshot());
            when(grabber.grab()).thenReturn(new byte[viewport.rgbaByteSize()]);
            when(recorder.grabFrame(any(VideoFrameGrabber.class), any(short[].class), anyInt(), anyLong()))
                    .thenAnswer(call -> new CapturedFrame(grabber.grab(), viewport.width(), viewport.height(),
                            call.getArgument(1), call.getArgument(2), call.getArgument(3)));
            controller = new LiveCaptureController(new LiveCaptureController.Dependencies(
                    rate -> audio, () -> 48_000,
                    ignored -> grabber, (ignored, rate) -> recorder,
                    Executors.newSingleThreadExecutor(), Duration.ofSeconds(1)));
        }
    }

    private static LiveCaptureController noOpController(List<String> calls) {
        return new LiveCaptureController(new LiveCaptureController.Dependencies(
                rate -> new LiveCaptureAudioHandle() {
                    private final AudioFrameClock clock =
                            new AudioFrameClock(48_000, rate);
                    public int sampleRate() { return 48_000; }
                    public int frameRate() { return rate; }
                    public int maxStereoFramesPerPacket() { return 800; }
                    public int drainPresentationFrame(short[] target) {
                        return clock.samplesForNextFrame();
                    }
                    public long totalStereoFrames() {
                        return clock.totalSamplesProduced();
                    }
                    public AudioFrameClock.Snapshot clockSnapshot() {
                        return clock.captureSnapshot();
                    }
                    public void close() {}
                },
                () -> 48_000,
                viewport -> new VideoFrameGrabber() {
                    public int width() { return viewport.width(); }
                    public int height() { return viewport.height(); }
                    public byte[] grab() { return new byte[viewport.rgbaByteSize()]; }
                },
                (viewport, rate) -> { throw new AssertionError(); },
                Executors.newSingleThreadExecutor(),
                Duration.ZERO));
    }
}
