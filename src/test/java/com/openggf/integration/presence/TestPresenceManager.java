package com.openggf.integration.presence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPresenceManager {

    @Test
    void tick_disabledConfigDoesNotConnectOrUpdate() {
        FakeClient client = new FakeClient();
        FakeProvider provider = new FakeProvider(PresenceSnapshot.menu());
        FakeClock clock = new FakeClock();
        try (PresenceManager manager = manager(false, provider, client, clock)) {
            manager.tick();

            assertEquals(0, client.connects.get());
            assertEquals(List.of(), client.updates);
        }
    }

    @Test
    void tick_firstEnabledTickConnectsAndPublishesMenuPresence() throws Exception {
        FakeClient client = new FakeClient();
        FakeProvider provider = new FakeProvider(PresenceSnapshot.menu());
        FakeClock clock = new FakeClock();
        try (PresenceManager manager = manager(true, provider, client, clock)) {
            manager.tick();
            client.awaitUpdate();

            assertEquals(1, client.connects.get());
            assertEquals("OpenGGF - In Menus", client.updates.get(0).details());
        }
    }

    @Test
    void tick_meaningfulSnapshotChangePublishesImmediately() throws Exception {
        FakeClient client = new FakeClient();
        FakeProvider provider = new FakeProvider(PresenceSnapshot.menu());
        FakeClock clock = new FakeClock();
        try (PresenceManager manager = manager(true, provider, client, clock)) {
            manager.tick();
            client.awaitUpdate();
            provider.snapshot = PresenceSnapshot.gameplay("Sonic 2", "Emerald Hill Zone", 1,
                    "Sonic", "0:01", 60);
            manager.tick();
            client.awaitUpdate();

            assertEquals(2, client.updates.size());
            assertEquals("OpenGGF - Sonic 2", client.updates.get(1).details());
        }
    }

    @Test
    void tick_menuModeChangesPublishImmediately() throws Exception {
        FakeClient client = new FakeClient();
        FakeProvider provider = new FakeProvider(PresenceSnapshot.menu("Master Title", null));
        FakeClock clock = new FakeClock();
        try (PresenceManager manager = manager(true, provider, client, clock)) {
            manager.tick();
            client.awaitUpdate();
            provider.snapshot = PresenceSnapshot.menu("Level Select", "Sonic 2");
            manager.tick();
            client.awaitUpdate();

            assertEquals(2, client.updates.size());
            assertEquals("OpenGGF - Master Title", client.updates.get(0).details());
            assertEquals("OpenGGF - Sonic 2", client.updates.get(1).details());
            assertEquals("Level Select", client.updates.get(1).state());
        }
    }

    @Test
    void tick_timerOnlyChangesAreThrottledToFifteenSeconds() throws Exception {
        FakeClient client = new FakeClient();
        FakeProvider provider = new FakeProvider(PresenceSnapshot.gameplay("Sonic 2",
                "Emerald Hill Zone", 1, "Sonic", "0:01", 60));
        FakeClock clock = new FakeClock();
        try (PresenceManager manager = manager(true, provider, client, clock)) {
            manager.tick();
            client.awaitUpdate();
            provider.snapshot = PresenceSnapshot.gameplay("Sonic 2", "Emerald Hill Zone", 1,
                    "Sonic", "0:02", 120);
            clock.now = 14_999;
            manager.tick();
            clock.now = 15_000;
            manager.tick();
            client.awaitUpdate();

            assertEquals(2, client.updates.size());
            assertEquals("Emerald Hill Zone Act 1 as Sonic - 0:02", client.updates.get(1).state());
        }
    }

    @Test
    void tick_clientFailureClosesAndDisablesPresenceForRun() throws Exception {
        FakeClient client = new FakeClient();
        client.failUpdate = true;
        FakeProvider provider = new FakeProvider(PresenceSnapshot.menu());
        FakeClock clock = new FakeClock();
        try (PresenceManager manager = manager(true, provider, client, clock)) {
            manager.tick();
            client.awaitClose();
            manager.tick();

            assertEquals(1, client.connects.get());
            assertEquals(1, client.closes.get());
            assertFalse(manager.isEnabledForRun());
        }
    }

    @Test
    void tick_capturesOnGameThreadAndWritesOffThread() throws Exception {
        FakeClient client = new FakeClient();
        FakeProvider provider = new FakeProvider(PresenceSnapshot.menu());
        FakeClock clock = new FakeClock();
        try (PresenceManager manager = manager(true, provider, client, clock)) {
            Thread gameThread = Thread.currentThread();
            manager.tick();
            client.awaitUpdate();

            assertEquals(gameThread, provider.captureThread);
            assertNotEquals(gameThread, client.lastUpdateThread);
        }
    }

    @Test
    void tick_doesNotWaitForBlockedConnect_andCloseIsBounded() throws Exception {
        BlockingClient client = BlockingClient.blockingConnectAndStalledClose();
        FakeProvider provider = new FakeProvider(PresenceSnapshot.menu());
        try (PresenceManager manager = manager(true, provider, client, new FakeClock())) {
            ExecutorService gameThread = Executors.newSingleThreadExecutor();
            Future<?> tick = gameThread.submit(manager::tick);
            try {
                assertTrue(client.operationEntered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofMillis(250), () -> tick.get());
                assertTimeoutPreemptively(Duration.ofMillis(1_500), manager::close);
            } finally {
                client.releaseClose();
                tick.get(5, TimeUnit.SECONDS);
                gameThread.shutdownNow();
                assertTrue(gameThread.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(client.closeFinished.await(5, TimeUnit.SECONDS),
                        "stalled close helper did not finish after release");
            }
            assertTrue(client.closeCalls.get() >= 1);
        }
    }

    @Test
    void tick_coalescesLatestPayloadWhileWriteIsBlocked() throws Exception {
        BlockingClient client = BlockingClient.blockingUpdate();
        FakeProvider provider = new FakeProvider(PresenceSnapshot.menu());
        try (PresenceManager manager = manager(true, provider, client, new FakeClock())) {
            ExecutorService gameThread = Executors.newSingleThreadExecutor();
            Future<?> tick = gameThread.submit(manager::tick);
            try {
                assertTrue(client.operationEntered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofMillis(250), () -> tick.get());

                for (int index = 0; index < 100; index++) {
                    provider.snapshot = PresenceSnapshot.menu("Menu " + index, "Sonic 2");
                    manager.tick();
                }
                assertEquals(1, client.updateCalls.get(), "blocked writer must not run queued updates yet");
                client.releaseOperation();
                client.awaitUpdate();
                client.awaitUpdate();
            } finally {
                manager.close();
                tick.get(5, TimeUnit.SECONDS);
                gameThread.shutdownNow();
                assertTrue(gameThread.awaitTermination(5, TimeUnit.SECONDS));
            }

            assertEquals(2, client.updateCalls.get(), "one blocked write plus one latest slot is bounded");
            assertEquals("Menu 99", client.updates.get(1).state());
            assertTrue(client.closeCalls.get() >= 1);
        }
    }

    private static PresenceManager manager(boolean enabled,
                                           PresenceSnapshotProvider provider,
                                           PresenceClient client,
                                           LongSupplier clock) {
        return new PresenceManager(enabled, true, true, provider,
                new PresenceFormatter(), client, clock);
    }

    private static final class FakeProvider implements PresenceSnapshotProvider {
        private PresenceSnapshot snapshot;
        private volatile Thread captureThread;

        private FakeProvider(PresenceSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public PresenceSnapshot capture() {
            captureThread = Thread.currentThread();
            return snapshot;
        }
    }

    private static final class FakeClient implements PresenceClient {
        private final AtomicInteger connects = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private boolean failUpdate;
        private final List<PresencePayload> updates = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final BlockingQueue<Boolean> updateSignals = new LinkedBlockingQueue<>();
        private final BlockingQueue<Boolean> closeSignals = new LinkedBlockingQueue<>();
        private volatile Thread lastUpdateThread;

        @Override
        public void connect() {
            connects.incrementAndGet();
        }

        @Override
        public void update(PresencePayload payload) throws IOException {
            if (failUpdate) {
                throw new IOException("boom");
            }
            lastUpdateThread = Thread.currentThread();
            updates.add(payload);
            updateSignals.offer(Boolean.TRUE);
        }

        @Override
        public void clear() {
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closeSignals.offer(Boolean.TRUE);
        }

        private void awaitUpdate() throws InterruptedException {
            assertTrue(updateSignals.poll(5, TimeUnit.SECONDS), "presence update did not arrive");
        }

        private void awaitClose() throws InterruptedException {
            assertTrue(closeSignals.poll(5, TimeUnit.SECONDS), "presence close did not arrive");
        }
    }

    private static final class BlockingClient implements PresenceClient {
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger updateCalls = new AtomicInteger();
        private final CountDownLatch operationEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        private final CountDownLatch closeFinished = new CountDownLatch(1);
        private final BlockingQueue<Boolean> updateSignals = new LinkedBlockingQueue<>();
        private final List<PresencePayload> updates = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final boolean blockConnect;
        private final boolean blockClose;

        private BlockingClient(boolean blockConnect, boolean blockClose) {
            this.blockConnect = blockConnect;
            this.blockClose = blockClose;
        }

        private static BlockingClient blockingConnectAndStalledClose() {
            return new BlockingClient(true, true);
        }

        private static BlockingClient blockingUpdate() {
            return new BlockingClient(false, false);
        }

        @Override
        public void connect() throws IOException {
            if (!blockConnect) {
                return;
            }
            operationEntered.countDown();
            awaitRelease();
        }

        @Override
        public void update(PresencePayload payload) throws IOException {
            updateCalls.incrementAndGet();
            if (blockConnect) {
                return;
            }
            operationEntered.countDown();
            awaitRelease();
            updates.add(payload);
            updateSignals.offer(Boolean.TRUE);
        }

        @Override
        public void clear() {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            release.countDown();
            try {
                if (blockClose) {
                    try {
                        closeRelease.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            } finally {
                closeFinished.countDown();
            }
        }

        private void releaseOperation() {
            release.countDown();
        }

        private void releaseClose() {
            closeRelease.countDown();
        }

        private void awaitUpdate() throws InterruptedException {
            assertTrue(updateSignals.poll(5, TimeUnit.SECONDS), "presence update did not arrive");
        }

        private void awaitRelease() throws IOException {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("blocked fake client interrupted", interrupted);
            }
        }
    }

    private static final class FakeClock implements LongSupplier {
        private long now;

        @Override
        public long getAsLong() {
            return now;
        }
    }
}
