package com.openggf.net;

import com.openggf.ghost.GhostFrame;
import com.openggf.net.client.GhostStreamPublisher;
import com.openggf.net.client.MasterClient;
import com.openggf.net.client.RaceClient;
import com.openggf.net.client.RaceConnection;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.master.MasterServer;
import com.openggf.net.master.ControlledMasterServer;
import com.openggf.net.master.TestMasterServer;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-loopback phase-3 acceptance gate for browser, relay, round, and isolation. */
@Timeout(120)
class TestMasterEndToEnd {
    private static final String FP = "0.6:cafe";
    private final List<MasterClient> clients = new ArrayList<>();
    private MasterServer server;

    @AfterEach
    void tearDown() {
        clients.forEach(MasterClient::close);
        if (server != null) {
            server.close();
        }
    }

    @Test
    void relayRoomBrowsesRacesRejectsTeleportAndDissolves(@TempDir Path dir)
            throws Exception {
        ControlledMasterServer controlled = new ControlledMasterServer();
        server = controlled.start(TestMasterServer.testConfig(), dir);
        PlayerIdentity hostIdentity = PlayerIdentity.loadOrCreate(dir.resolve("host"));
        MasterClient hostMaster = connect(hostIdentity, "HOST");
        establish(hostIdentity);
        String roomId = hostMaster.createRoom(new ControlMessage.RoomDescriptor(
                        "E2E", "s3k", 0, 0, "OPEN", null, 64, false),
                "RELAY", 0, FP).get(10, TimeUnit.SECONDS).roomId();
        RaceConnection host = hostMaster.joinRoom(roomId, hostIdentity, "HOST", FP)
                .get(15, TimeUnit.SECONDS);

        RaceConnection guest1 = join(dir.resolve("guest1"), "G1", roomId);
        RaceConnection guest2 = join(dir.resolve("guest2"), "G2", roomId);
        PlayerIdentity guest3Identity = PlayerIdentity.loadOrCreate(dir.resolve("guest3"));
        MasterClient guest3Master = connect(guest3Identity, "G3");
        await(host, event -> event instanceof RaceClient.Control control
                && control.message() instanceof ControlMessage.RoomState state
                && state.players().size() == 3, 10_000);
        controlled.publishPlayerCounts(roomId);
        ControlMessage.RoomListResult listed = guest3Master.listRooms("s3k", 0)
                .get(10, TimeUnit.SECONDS);
        assertEquals(1, listed.rooms().size());
        assertEquals(3, listed.rooms().getFirst().playerCount());
        RaceConnection guest3 = guest3Master.joinRoom(roomId, guest3Identity, "G3", FP)
                .get(15, TimeUnit.SECONDS);

        host.sendControl(new ControlMessage.RoundConfigure(
                new ControlMessage.RoundConfig("s3k", 0, 0, 20, "OPEN", null)));
        awaitControl(host, ControlMessage.RoundStart.class, 10_000);
        awaitControl(guest1, ControlMessage.RoundStart.class, 10_000);
        awaitControl(guest2, ControlMessage.RoundStart.class, 10_000);
        awaitControl(guest3, ControlMessage.RoundStart.class, 10_000);
        controlled.finishCountdown(roomId);

        runAttempt(host, 24, 100);
        runAttempt(guest1, 20, 104);
        runAttempt(guest2, 22, 108);
        runAttempt(guest3, 26, 112);
        await(guest1, RaceClient.GhostData.class::isInstance, 10_000);
        await(guest1, RaceClient.Roster.class::isInstance, 10_000);

        ControlMessage.StandingsDelta standings = awaitStandings(host, 4, 10_000);
        assertEquals("G1", standings.rows().getFirst().displayName());
        host.sendControl(new ControlMessage.StandingsPageRequest(0));
        ControlMessage.StandingsPage page = (ControlMessage.StandingsPage)
                awaitControl(host, ControlMessage.StandingsPage.class, 10_000);
        assertEquals(4, page.rows().size());

        guest3.close();
        await(host, event -> event instanceof RaceClient.Control control
                && control.message() instanceof ControlMessage.RoomState state
                && state.players().size() == 3, 10_000);
        PlayerIdentity attackerIdentity = PlayerIdentity.loadOrCreate(dir.resolve("attacker"));
        MasterClient attackerMaster = connect(attackerIdentity, "BAD");
        RaceConnection attacker = attackerMaster.joinRoom(
                        roomId, attackerIdentity, "BAD", FP)
                .get(15, TimeUnit.SECONDS);
        GhostStreamPublisher malicious = new GhostStreamPublisher(attacker::sendBinary);
        malicious.beginAttempt(1);
        for (int frame = 0; frame < 30; frame++) {
            malicious.onFrame(frame((frame & 1) == 0 ? 100 : 2000));
        }
        await(attacker, RaceClient.Disconnected.class::isInstance, 10_000);
        assertTrue(host.isOpen() && guest1.isOpen() && guest2.isOpen(),
                "sanctioning the attacker must not disturb healthy racers");

        host.close();
        await(guest1, RaceClient.Disconnected.class::isInstance, 10_000);
        MasterClient after = connect(
                PlayerIdentity.loadOrCreate(dir.resolve("after")), "AFTER");
        assertTrue(after.listRooms("s3k", 0).get(10, TimeUnit.SECONDS).rooms().isEmpty());
    }

    private MasterClient connect(PlayerIdentity identity, String name) throws Exception {
        MasterClient client = MasterClient.connect(
                        URI.create("ws://127.0.0.1:" + server.port() + "/master"),
                        identity, name, FP, null)
                .get(15, TimeUnit.SECONDS);
        clients.add(client);
        return client;
    }

    private RaceConnection join(Path identityDir, String name, String roomId)
            throws Exception {
        PlayerIdentity identity = PlayerIdentity.loadOrCreate(identityDir);
        return connect(identity, name).joinRoom(roomId, identity, name, FP)
                .get(15, TimeUnit.SECONDS);
    }

    private void establish(PlayerIdentity identity) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        server.execute(() -> {
            server.establishForTest(identity.fingerprint());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    private static void runAttempt(RaceConnection connection, int timeFrames, int x) {
        GhostStreamPublisher publisher = new GhostStreamPublisher(connection::sendBinary);
        connection.sendControl(new ControlMessage.AttemptStart(1));
        publisher.beginAttempt(1);
        for (int frame = 0; frame < 30; frame++) {
            publisher.onFrame(frame(x + frame));
        }
        publisher.finishAttempt();
        int finishFrame = publisher.framesPublished() - 1;
        int firstInputFrame = finishFrame - timeFrames;
        connection.sendControl(new ControlMessage.AttemptFinish(1, timeFrames,
                firstInputFrame, finishFrame, "ab".repeat(32),
                HexFormat.of().formatHex(publisher.streamHashSha256()), null));
    }

    private static GhostFrame frame(int x) {
        return new GhostFrame(x, 100, 1, false, false, false, 2, false);
    }

    private static ControlMessage awaitControl(RaceConnection connection, Class<?> type,
                                               long timeoutMillis) throws Exception {
        return ((RaceClient.Control) await(connection,
                event -> event instanceof RaceClient.Control control
                        && type.isInstance(control.message()), timeoutMillis)).message();
    }

    private static ControlMessage.StandingsDelta awaitStandings(
            RaceConnection connection, int rows, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (RaceClient.InboundEvent event : connection.drainInbound()) {
                if (event instanceof RaceClient.Control control
                        && control.message() instanceof ControlMessage.StandingsDelta delta
                        && delta.rows().size() >= rows) {
                    return delta;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + rows + " standings rows");
    }

    private static RaceClient.InboundEvent await(
            RaceConnection connection, Predicate<RaceClient.InboundEvent> match,
            long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (RaceClient.InboundEvent event : connection.drainInbound()) {
                if (match.test(event)) {
                    return event;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for room event");
    }
}
