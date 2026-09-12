package com.openggf.net;

import com.openggf.ghost.GhostFrame;
import com.openggf.net.client.GhostStreamPublisher;
import com.openggf.net.client.RaceClient;
import com.openggf.net.client.RemoteGhostPlayback;
import com.openggf.net.host.RaceHostServer;
import com.openggf.net.host.ControlledRaceHost;
import com.openggf.net.hub.HostRoundEngine;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase-2 acceptance gate: complete real-socket round with a latency-proxied guest. */
@Timeout(120)
class TestDirectConnectEndToEnd {
    private static final String FP = "0.6:cafe1234";
    private RaceHostServer server;
    private LatencyProxy proxy;

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) proxy.close();
        if (server != null) server.close();
    }

    private static RaceClient.InboundEvent await(
            RaceClient client, Predicate<RaceClient.InboundEvent> match,
            long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (RaceClient.InboundEvent event : client.drainInbound()) {
                if (match.test(event)) return event;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for inbound event");
    }

    private static boolean isMessage(RaceClient.InboundEvent event, Class<?> type) {
        return event instanceof RaceClient.Control control
                && type.isInstance(control.message());
    }

    private static GhostFrame frame(int x) {
        return new GhostFrame(x, 100, 1, false, false, false, 2, false);
    }

    private static void runAttempt(
            RaceClient client, int attemptId, int frames, int timeFrames) {
        GhostStreamPublisher publisher = new GhostStreamPublisher(client::sendBinary);
        client.sendControl(new ControlMessage.AttemptStart(attemptId));
        publisher.beginAttempt(attemptId);
        for (int i = 0; i < frames; i++) {
            publisher.onFrame(frame(100 + i));
        }
        publisher.finishAttempt();
        int finishFrame = frames - 1;
        int firstInputFrame = finishFrame - timeFrames;
        client.sendControl(new ControlMessage.AttemptFinish(attemptId, timeFrames,
                firstInputFrame, finishFrame, "ab".repeat(32),
                HexFormat.of().formatHex(publisher.streamHashSha256()), null));
    }

    @Test
    void fullRoundWithLatencyProxiedGuest(@TempDir Path dir) throws Exception {
        PlayerIdentity hostIdentity = PlayerIdentity.loadOrCreate(dir.resolve("host"));
        ControlledRaceHost clock = new ControlledRaceHost();
        server = clock.start(0,
                new RoomHostConfig("E2E", "s3k", 0, 0,
                        "OPEN", null, 8, FP,
                        List.of("s3k:0:0", "s3k:0:1", "s3k:1:0")),
                hostIdentity, TrackValidationProfileSource.none());
        proxy = new LatencyProxy("127.0.0.1", server.port(), 120);

        RaceClient host = RaceClient.connect(
                        URI.create("ws://127.0.0.1:" + server.port() + "/race"),
                        hostIdentity, "HOST", FP)
                .get(10, TimeUnit.SECONDS);
        RaceClient guest = RaceClient.connect(
                        URI.create("ws://127.0.0.1:" + proxy.port() + "/race"),
                        PlayerIdentity.loadOrCreate(dir.resolve("guest")), "GUEST", FP)
                .get(10, TimeUnit.SECONDS);

        guest.sendControl(new ControlMessage.Chat("gl hf"));
        await(host, event -> isMessage(event, ControlMessage.ChatBroadcast.class), 10_000);

        host.sendControl(new ControlMessage.RoundConfigure(
                new ControlMessage.RoundConfig("s3k", 0, 0, 4, "OPEN", null)));
        await(host, event -> isMessage(event, ControlMessage.RoundStart.class), 10_000);
        await(guest, event -> isMessage(event, ControlMessage.RoundStart.class), 10_000);
        clock.advance(HostRoundEngine.COUNTDOWN_MILLIS);

        runAttempt(host, 1, 30, 24);
        runAttempt(guest, 1, 30, 20);

        RaceClient.InboundEvent delta = await(host,
                event -> event instanceof RaceClient.Control control
                        && control.message() instanceof ControlMessage.StandingsDelta standings
                        && standings.rows().size() == 2,
                15_000);
        List<ControlMessage.StandingsRow> rows =
                ((ControlMessage.StandingsDelta) ((RaceClient.Control) delta).message()).rows();
        assertEquals("GUEST", rows.get(0).displayName());
        assertEquals(20, rows.get(0).bestTimeFrames());

        RemoteGhostPlayback playback = new RemoteGhostPlayback();
        long ghostDeadline = System.currentTimeMillis() + 15_000;
        boolean rendered = false;
        while (!rendered && System.currentTimeMillis() < ghostDeadline) {
            for (RaceClient.InboundEvent event : guest.drainInbound()) {
                if (event instanceof RaceClient.GhostData ghost) {
                    ghost.aggregate().entries().forEach(playback::onEntry);
                }
            }
            rendered = playback.advance().isPresent();
            Thread.sleep(20);
        }
        assertTrue(rendered, "latency-proxied guest never rendered host ghost");

        clock.advance(4_001);
        await(host, event -> isMessage(event, ControlMessage.RoundEnd.class), 15_000);
        await(guest, event -> isMessage(event, ControlMessage.RoundEnd.class), 15_000);

        clock.advance(HostRoundEngine.ROUND_END_LINGER_MILLIS);
        ControlMessage.TrackVoteOffer offer = (ControlMessage.TrackVoteOffer)
                ((RaceClient.Control) await(host,
                        event -> isMessage(event, ControlMessage.TrackVoteOffer.class),
                        15_000)).message();
        await(guest, event -> isMessage(event, ControlMessage.TrackVoteOffer.class), 10_000);
        String winner = offer.trackKeys().getFirst();
        guest.sendControl(new ControlMessage.TrackVote(winner));
        await(host, event -> event instanceof RaceClient.Control control
                        && control.message() instanceof ControlMessage.TrackVoteTally tally
                        && tally.counts().stream().anyMatch(count ->
                        count.trackKey().equals(winner) && count.votes() == 1),
                10_000);
        clock.advance(HostRoundEngine.VOTE_WINDOW_MILLIS);
        await(host, event -> event instanceof RaceClient.Control control
                        && control.message() instanceof ControlMessage.TrackVoteResult result
                        && result.trackKey().equals(winner),
                20_000);
        await(guest, event -> isMessage(event, ControlMessage.TrackVoteResult.class), 10_000);

        String[] winnerParts = winner.split(":");
        ControlMessage.RoundConfig next = new ControlMessage.RoundConfig(
                winnerParts[0], Integer.parseInt(winnerParts[1]),
                Integer.parseInt(winnerParts[2]), 4, "OPEN", null);
        host.sendControl(new ControlMessage.RoundConfigure(next));
        ControlMessage.RoundStart nextStart = (ControlMessage.RoundStart)
                ((RaceClient.Control) await(host,
                        event -> isMessage(event, ControlMessage.RoundStart.class),
                        10_000)).message();
        assertEquals(winnerParts[0], nextStart.config().gameId());
        assertEquals(Integer.parseInt(winnerParts[1]), nextStart.config().zone());
        assertEquals(Integer.parseInt(winnerParts[2]), nextStart.config().act());
        await(guest, event -> isMessage(event, ControlMessage.RoundStart.class), 10_000);

        guest.close();
        await(host, event -> event instanceof RaceClient.Control control
                        && control.message() instanceof ControlMessage.RoomState state
                        && state.players().size() == 1,
                10_000);
        host.close();
    }
}
