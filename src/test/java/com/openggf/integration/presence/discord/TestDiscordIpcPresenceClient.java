package com.openggf.integration.presence.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.integration.presence.PresenceFormatter;
import com.openggf.integration.presence.PresenceManager;
import com.openggf.integration.presence.PresencePayload;
import com.openggf.integration.presence.PresenceSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class TestDiscordIpcPresenceClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void connect_sendsHandshakeWithOpenGgfApplicationId() throws IOException {
        FakeTransport transport = new FakeTransport();
        DiscordIpcPresenceClient client = new DiscordIpcPresenceClient(() -> transport);

        client.connect();

        Frame frame = transport.frames.get(0);
        JsonNode json = MAPPER.readTree(frame.json);
        assertEquals(0, frame.opcode);
        assertEquals(1, json.get("v").asInt());
        assertEquals("1510395080652099754", json.get("client_id").asText());
    }

    @Test
    void update_sendsSetActivityWithNonceAndPayload() throws IOException {
        FakeTransport transport = new FakeTransport();
        DiscordIpcPresenceClient client = new DiscordIpcPresenceClient(() -> transport);

        client.connect();
        client.update(new PresencePayload("OpenGGF - Sonic 2",
                "Emerald Hill Zone Act 1 as Sonic - 0:01"));

        Frame frame = transport.frames.get(1);
        JsonNode json = MAPPER.readTree(frame.json);
        assertEquals(1, frame.opcode);
        assertEquals("SET_ACTIVITY", json.get("cmd").asText());
        assertNotNull(json.get("nonce").asText());
        assertEquals("OpenGGF - Sonic 2",
                json.at("/args/activity/details").asText());
        assertEquals("Emerald Hill Zone Act 1 as Sonic - 0:01",
                json.at("/args/activity/state").asText());
    }

    @Test
    void update_reusesStableStartTimestampAcrossActivityUpdates() throws IOException {
        FakeTransport transport = new FakeTransport();
        DiscordIpcPresenceClient client = new DiscordIpcPresenceClient(() -> transport);

        client.connect();
        client.update(new PresencePayload("OpenGGF - Sonic 2",
                "Emerald Hill Zone Act 1 as Sonic - 0:01"));
        client.update(new PresencePayload("OpenGGF - Sonic 2",
                "Emerald Hill Zone Act 1 as Sonic - 0:16"));

        JsonNode first = MAPPER.readTree(transport.frames.get(1).json);
        JsonNode second = MAPPER.readTree(transport.frames.get(2).json);
        long firstStart = first.at("/args/activity/timestamps/start").asLong();
        long secondStart = second.at("/args/activity/timestamps/start").asLong();

        assertTrue(firstStart > 0);
        assertEquals(firstStart, secondStart);
    }

    @Test
    void clear_sendsSetActivityWithNullActivity() throws IOException {
        FakeTransport transport = new FakeTransport();
        DiscordIpcPresenceClient client = new DiscordIpcPresenceClient(() -> transport);

        client.connect();
        client.clear();

        JsonNode json = MAPPER.readTree(transport.frames.get(1).json);
        assertTrue(json.at("/args/activity").isNull());
    }

    @Test
    void close_closesTransport() throws IOException {
        FakeTransport transport = new FakeTransport();
        DiscordIpcPresenceClient client = new DiscordIpcPresenceClient(() -> transport);

        client.connect();
        client.close();

        assertTrue(transport.closed);
    }

    @Test
    void managerCloseInterruptsBlockedFactoryOpenWithoutReleasingItFromClientClose()
            throws Exception {
        BlockingOpenFactory factory = new BlockingOpenFactory();
        DiscordIpcPresenceClient client = new DiscordIpcPresenceClient(factory);
        PresenceManager manager = new PresenceManager(true, true, true,
                PresenceSnapshot::menu, new PresenceFormatter(), client, () -> 0L);
        ExecutorService gameThread = Executors.newSingleThreadExecutor();
        Future<?> tick = gameThread.submit(manager::tick);
        try {
            assertTrue(factory.openEntered.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(1_500), manager::close);
            assertTrue(factory.openInterrupted.await(5, TimeUnit.SECONDS),
                    "manager close did not interrupt the blocked factory open");
        } finally {
            factory.releaseOpen();
            tick.get(5, TimeUnit.SECONDS);
            gameThread.shutdownNow();
            assertTrue(gameThread.awaitTermination(5, TimeUnit.SECONDS));
            manager.close();
        }
    }

    @Test
    void connect_lateTransportAfterCloseIsDiscardedAndClosed() throws Exception {
        BlockingOpenFactory factory = new BlockingOpenFactory();
        DiscordIpcPresenceClient client = new DiscordIpcPresenceClient(factory);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> connect = executor.submit(() -> {
            client.connect();
            return null;
        });
        try {
            assertTrue(factory.openEntered.await(5, TimeUnit.SECONDS));
            client.close();
            factory.releaseOpen();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> connect.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertTrue(factory.transportCreated.await(5, TimeUnit.SECONDS));
            assertTrue(factory.transport.closed);
        } finally {
            factory.releaseOpen();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void close_unblocksBlockedFrameSend() throws Exception {
        BlockingFrameTransport transport = new BlockingFrameTransport();
        DiscordIpcPresenceClient client = new DiscordIpcPresenceClient(() -> transport);
        client.connect();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> update = executor.submit(() -> {
            client.update(new PresencePayload("OpenGGF - Sonic 2", "Emerald Hill Zone"));
            return null;
        });
        try {
            assertTrue(transport.frameSendEntered.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(250), client::close);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> update.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertTrue(transport.closed);
        } finally {
            transport.releaseSend();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private record Frame(int opcode, String json) {
    }

    private static final class FakeTransport implements DiscordIpcTransport {
        private final List<Frame> frames = new ArrayList<>();
        private boolean closed;

        @Override
        public void send(int opcode, String json) {
            frames.add(new Frame(opcode, json));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class BlockingOpenFactory implements DiscordIpcTransportFactory {
        private final CountDownLatch openEntered = new CountDownLatch(1);
        private final CountDownLatch openInterrupted = new CountDownLatch(1);
        private final CountDownLatch releaseOpen = new CountDownLatch(1);
        private final CountDownLatch transportCreated = new CountDownLatch(1);
        private volatile FakeTransport transport;

        @Override
        public DiscordIpcTransport open() throws IOException {
            openEntered.countDown();
            try {
                releaseOpen.await();
            } catch (InterruptedException interrupted) {
                openInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IOException("blocked Discord factory open interrupted", interrupted);
            }
            FakeTransport opened = new FakeTransport();
            transport = opened;
            transportCreated.countDown();
            return opened;
        }

        private void releaseOpen() {
            releaseOpen.countDown();
        }
    }

    private static final class BlockingFrameTransport implements DiscordIpcTransport {
        private final CountDownLatch frameSendEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSend = new CountDownLatch(1);
        private volatile boolean closed;

        @Override
        public void send(int opcode, String json) throws IOException {
            if (opcode == 0) {
                return;
            }
            frameSendEntered.countDown();
            try {
                releaseSend.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("blocked Discord frame send interrupted", interrupted);
            }
            if (closed) {
                throw new IOException("Discord frame transport closed");
            }
        }

        @Override
        public void close() {
            closed = true;
            releaseSend.countDown();
        }

        private void releaseSend() {
            releaseSend.countDown();
        }
    }
}
