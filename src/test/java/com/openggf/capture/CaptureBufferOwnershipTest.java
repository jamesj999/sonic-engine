package com.openggf.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CaptureBufferOwnershipTest {
    private static final class GatedEncoder implements CaptureEncoder {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        boolean fail;
        public void open(Path p, int w, int h, int fps, int sampleRate) {}
        public void encode(CapturedFrame frame) throws CaptureException {
            entered.countDown();
            try { gate.await(); } catch (InterruptedException e) {
                throw new CaptureException("interrupted", e);
            }
            if (fail) throw new IllegalStateException("unchecked encoder failure");
        }
        public Path finish() { return Path.of("out"); }
        public void abort() { gate.countDown(); }
    }

    private static CapturedFrame owned(int index, AtomicInteger releases) {
        return CapturedFrame.ownedPixels(new byte[]{(byte) index, 0, 0, 0}, 1, 1,
                new short[0], 0, index, releases::incrementAndGet);
    }

    @Test void poolReusesPixelsOnlyAfterTheirOwnerReleasesThem() {
        GatedEncoder encoder = new GatedEncoder();
        CaptureRecorder recorder = new CaptureRecorder(encoder, BackpressurePolicy.BLOCK,
                1, Path.of("target"), "pool", "test");
        VideoFrameGrabber grabber = new VideoFrameGrabber() {
            int value;
            public int width() { return 1; }
            public int height() { return 1; }
            public byte[] grab() { throw new AssertionError("must grab directly into owned pixels"); }
            public void grabInto(byte[] target) { target[0] = (byte) ++value; }
        };
        CapturedFrame inFlight = recorder.grabFrame(grabber, new short[0], 0, 0);
        CapturedFrame queued = recorder.grabFrame(grabber, new short[0], 0, 1);
        Set<byte[]> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.add(inFlight.rgba());
        identities.add(queued.rgba());
        for (int i = 0; i < 100; i++) {
            CapturedFrame next = recorder.grabFrame(grabber, new short[0], 0, i + 2);
            identities.add(next.rgba());
            assertEquals(1, inFlight.rgba()[0]);
            assertEquals(2, queued.rgba()[0]);
            next.release();
            next.release(); // return is idempotent
        }
        assertEquals(3, identities.size(), "queue + worker + producer is a fixed bound");
        inFlight.release();
        queued.release();
    }

    @Test void dropAndAbortReleaseEveryOwnedFrame() throws Exception {
        GatedEncoder encoder = new GatedEncoder();
        EncoderSink sink = new EncoderSink(encoder, BackpressurePolicy.DROP_OLDEST, 1);
        AtomicInteger releases = new AtomicInteger();
        sink.open(Path.of("out"), 1, 1, 60, 48_000);
        sink.submit(owned(0, releases));
        assertTrue(encoder.entered.await(1, TimeUnit.SECONDS));
        sink.submit(owned(1, releases));
        sink.submit(owned(2, releases));
        assertEquals(1, releases.get(), "only the dropped queue frame is free");
        sink.abort();
        assertEquals(3, releases.get());
        assertThrows(CaptureException.class, () -> sink.submit(owned(3, releases)));
        assertEquals(4, releases.get(), "post-abort rejection returns ownership too");
    }

    @Test void failPolicyAndUncheckedEncoderFailureReleaseAllStorage() throws Exception {
        GatedEncoder encoder = new GatedEncoder();
        encoder.fail = true;
        EncoderSink sink = new EncoderSink(encoder, BackpressurePolicy.FAIL, 1);
        AtomicInteger releases = new AtomicInteger();
        sink.open(Path.of("out"), 1, 1, 60, 48_000);
        sink.submit(owned(0, releases));
        assertTrue(encoder.entered.await(1, TimeUnit.SECONDS));
        sink.submit(owned(1, releases));
        assertThrows(CaptureException.class, () -> sink.submit(owned(2, releases)));
        assertEquals(1, releases.get());
        encoder.gate.countDown();
        assertThrows(CaptureException.class, sink::stop);
        assertEquals(3, releases.get());
    }

    @Test void dropSubmitCannotConsumeStopMarker() throws Exception {
        GatedEncoder encoder = new GatedEncoder();
        EncoderSink sink = new EncoderSink(encoder, BackpressurePolicy.DROP_OLDEST, 1);
        AtomicInteger releases = new AtomicInteger();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        sink.open(Path.of("out"), 1, 1, 60, 48_000);
        sink.submit(owned(0, releases));
        assertTrue(encoder.entered.await(1, TimeUnit.SECONDS));
        Thread stopper = new Thread(() -> {
            try { sink.stop(); } catch (Throwable e) { stopFailure.set(e); }
        });
        stopper.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (stopper.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        try {
            assertEquals(Thread.State.TIMED_WAITING, stopper.getState());
            assertThrows(CaptureException.class, () -> sink.submit(owned(1, releases)));
        } finally {
            encoder.gate.countDown();
        }
        stopper.join(1000);
        assertFalse(stopper.isAlive(), "stop marker must survive the racing dropping producer");
        assertNull(stopFailure.get());
        assertEquals(2, releases.get());
        assertEquals(0, sink.droppedCount());
    }

    @Test void timedOutAbortDoesNotRecyclePixelsStillReadByEncoder() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        CaptureEncoder encoder = new CaptureEncoder() {
            public void open(Path p, int w, int h, int fps, int rate) {}
            public void encode(CapturedFrame frame) {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { gate.await(); done = true; } catch (InterruptedException ignored) {}
                }
                assertEquals(7, frame.rgba()[0]);
            }
            public Path finish() { return Path.of("out"); }
            public void abort() {}
        };
        EncoderSink sink = new EncoderSink(encoder, BackpressurePolicy.BLOCK, 1, 10);
        sink.open(Path.of("out"), 1, 1, 60, 48_000);
        sink.submit(owned(7, releases));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        sink.submit(owned(8, releases));
        try {
            sink.abort();
            assertEquals(1, releases.get(), "only queued storage can be returned before encode exits");
        } finally {
            gate.countDown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (releases.get() != 2 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(2, releases.get());
    }

    @Test void firstWaitIsCountedEvenWhenCapacityReturnsBeforeHealthCheck() throws Exception {
        GatedEncoder encoder = new GatedEncoder();
        EncoderSink sink = new EncoderSink(encoder, BackpressurePolicy.BLOCK, 1);
        AtomicInteger releases = new AtomicInteger();
        sink.open(Path.of("out"), 1, 1, 60, 48_000);
        sink.submit(owned(0, releases));
        assertTrue(encoder.entered.await(1, TimeUnit.SECONDS));
        sink.submit(owned(1, releases));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try { sink.submit(owned(2, releases)); } catch (Throwable e) { failure.set(e); }
        });
        producer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (producer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.TIMED_WAITING, producer.getState());
        encoder.gate.countDown();
        producer.join(1000);
        sink.stop();
        assertFalse(producer.isAlive());
        assertNull(failure.get());
        assertEquals(1, sink.exhaustedFrameCount());
        assertTrue(sink.totalBlockedNanos() > 0);
        assertEquals(sink.totalBlockedNanos(), sink.worstBlockedNanos());
        assertEquals(3, releases.get());
    }
}
