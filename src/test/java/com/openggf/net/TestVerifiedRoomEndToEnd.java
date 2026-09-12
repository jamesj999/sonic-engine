package com.openggf.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.timeattack.AttemptInputRecording;
import com.openggf.game.timeattack.AttemptStartDescriptor;
import com.openggf.ghost.GhostFrame;
import com.openggf.net.client.GhostStreamPublisher;
import com.openggf.net.client.MasterClient;
import com.openggf.net.client.RaceClient;
import com.openggf.net.client.RaceConnection;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.master.IdentityStore;
import com.openggf.net.master.MasterConfig;
import com.openggf.net.master.MasterServer;
import com.openggf.net.master.ControlledMasterServer;
import com.openggf.net.master.VerificationJobQueue;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.VerdictCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(90)
class TestVerifiedRoomEndToEnd {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT = "0.6:cafe1234";

    @Test
    void trustedRelayFinishUploadsAndBecomesVerified(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("master.yaml");
        Files.writeString(yaml, """
                port: 0
                adminPort: 0
                plaintextForTest: true
                dbPath: ids.db
                adminToken: test-admin
                identityPowBits: 1
                attackModePowBits: 1
                verifierRegistrationToken: register-me
                verifiedUploadDeadlineSeconds: 15
                """);
        ControlledMasterServer controlled = new ControlledMasterServer();
        MasterServer server = controlled.start(MasterConfig.load(yaml), dir);
        MasterClient hostMaster = null;
        MasterClient newcomer = null;
        RaceConnection race = null;
        try {
            String httpBase = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newHttpClient();
            PlayerIdentity worker = PlayerIdentity.loadOrCreate(dir.resolve("worker"));
            WorkerAuth workerAuth = registerWorker(http, httpBase, worker);

            PlayerIdentity host = PlayerIdentity.loadOrCreate(dir.resolve("host"));
            hostMaster = MasterClient.connect(
                    URI.create("ws://127.0.0.1:" + server.port() + "/master"),
                    host, "HOST", FINGERPRINT, null).get(10, TimeUnit.SECONDS);
            onBroker(server, () -> server.trustForTest(host.fingerprint()));
            ControlMessage.RoomDescriptor descriptor = new ControlMessage.RoomDescriptor(
                    "Verified", "s3k", 0, 0, "OPEN", null, 8, true);
            String roomId = hostMaster.createRoom(descriptor, "RELAY", 0,
                    FINGERPRINT).get(10, TimeUnit.SECONDS).roomId();
            ControlMessage.RoomListResult browser = hostMaster.listRooms("s3k", 0)
                    .get(10, TimeUnit.SECONDS);
            assertTrue(browser.rooms().stream().anyMatch(room ->
                    room.roomId().equals(roomId) && room.verified()));

            PlayerIdentity newIdentity = PlayerIdentity.loadOrCreate(dir.resolve("new"));
            newcomer = MasterClient.connect(
                    URI.create("ws://127.0.0.1:" + server.port() + "/master"),
                    newIdentity, "NEW", FINGERPRINT, null).get(10, TimeUnit.SECONDS);
            MasterClient finalNewcomer = newcomer;
            assertThrows(Exception.class, () -> finalNewcomer.requestJoin(roomId)
                    .get(10, TimeUnit.SECONDS));
            newcomer.close();
            newcomer = null;

            race = hostMaster.joinRoom(roomId, host, "HOST", FINGERPRINT)
                    .get(10, TimeUnit.SECONDS);
            race.sendControl(new ControlMessage.RoundConfigure(
                    new ControlMessage.RoundConfig("s3k", 0, 0, 5, "OPEN", null)));
            await(race, event -> message(event, ControlMessage.RoundStart.class), 10_000);
            controlled.finishCountdown(roomId);

            AttemptInputRecording recording = new AttemptInputRecording(
                    new AttemptStartDescriptor("s3k", 0, 0, "sonic", FINGERPRINT));
            recording.appendFrame(0, false);
            recording.appendFrame(8, false);
            byte[] encoded = recording.encode();
            String hash = HexFormat.of().formatHex(recording.sha256());
            GhostStreamPublisher publisher = new GhostStreamPublisher(race::sendBinary);
            race.sendControl(new ControlMessage.AttemptStart(1));
            publisher.beginAttempt(1);
            for (int frame = 0; frame <= 101; frame++) {
                publisher.onFrame(new GhostFrame(100 + frame, 200, 1,
                        false, false, false, 2, false));
            }
            publisher.finishAttempt();
            race.sendControl(new ControlMessage.AttemptFinish(
                    1, 100, 1, 101, hash,
                    HexFormat.of().formatHex(publisher.streamHashSha256()), null));
            ControlMessage.RecordingRequest request = awaitPendingAndRequest(race, 10_000);
            assertEquals(hash, request.expectedHashHex());

            HttpResponse<Void> upload = http.send(
                    HttpRequest.newBuilder(URI.create(httpBase + request.uploadUrl()))
                            .header("Authorization", "Bearer " + race.uploadSessionToken())
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(encoded)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(204, upload.statusCode());

            HttpResponse<String> leased = http.send(
                    workerRequest(httpBase + "/verifier/jobs", workerAuth).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, leased.statusCode());
            VerificationJobQueue.Job job = JSON.readValue(
                    leased.body(), VerificationJobQueue.Job.class);
            byte[] signature = worker.sign(VerdictCodec.canonicalBytes(
                    job.jobId(), job.attemptRef(), job.inputRecordingHashHex(),
                    VerdictCodec.RESULT_PASS));
            byte[] verdict = JSON.writeValueAsBytes(Map.of(
                    "jobId", job.jobId(), "result", VerdictCodec.RESULT_PASS,
                    "signatureBase64", Base64.getEncoder().encodeToString(signature)));
            HttpResponse<Void> posted = http.send(
                    workerRequest(httpBase + "/verifier/verdicts", workerAuth)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(verdict)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(204, posted.statusCode());
            await(race, event -> event instanceof RaceClient.Control control
                    && control.message() instanceof ControlMessage.StandingsDelta delta
                    && delta.rows().stream().anyMatch(row ->
                    "VERIFIED".equals(row.verifyState())), 10_000);
            List<IdentityStore.VerdictRecord> verdicts = onBroker(server,
                    () -> server.verdictsForTest(host.fingerprint()));
            assertEquals(1, verdicts.size());
            assertEquals(VerdictCodec.RESULT_PASS, verdicts.getFirst().result());
        } finally {
            if (race != null) race.close();
            if (newcomer != null) newcomer.close();
            if (hostMaster != null) hostMaster.close();
            server.close();
        }
    }

    private static WorkerAuth registerWorker(HttpClient http, String base,
                                             PlayerIdentity identity)
            throws Exception {
        byte[] body = JSON.writeValueAsBytes(Map.of(
                "pubKeyBase64", Base64.getEncoder().encodeToString(
                        identity.publicKeyEncoded()),
                "fingerprints", Set.of(FINGERPRINT)));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + "/verifier/register"))
                        .header("Authorization", "Bearer register-me")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode json = JSON.readTree(response.body());
        return new WorkerAuth(json.get("workerId").asText(),
                json.get("workerToken").asText());
    }

    private static HttpRequest.Builder workerRequest(String url, WorkerAuth worker) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("X-Worker-Id", worker.id())
                .header("Authorization", "Bearer " + worker.token());
    }

    private static RaceClient.InboundEvent await(
            RaceConnection connection, Predicate<RaceClient.InboundEvent> match,
            long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (RaceClient.InboundEvent event : connection.drainInbound()) {
                if (match.test(event)) return event;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for event");
    }

    private static ControlMessage.RecordingRequest awaitPendingAndRequest(
            RaceConnection connection, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        boolean pendingSeen = false;
        ControlMessage.RecordingRequest request = null;
        while (System.currentTimeMillis() < deadline) {
            for (RaceClient.InboundEvent event : connection.drainInbound()) {
                if (event instanceof RaceClient.Control control) {
                    if (control.message() instanceof ControlMessage.StandingsDelta delta
                            && delta.rows().stream().anyMatch(row ->
                            "PENDING".equals(row.verifyState()))) {
                        pendingSeen = true;
                    } else if (control.message() instanceof ControlMessage.RecordingRequest found) {
                        request = found;
                    }
                }
            }
            if (pendingSeen && request != null) return request;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for pending standings + recording request");
    }

    private static boolean message(RaceClient.InboundEvent event, Class<?> type) {
        return event instanceof RaceClient.Control control
                && type.isInstance(control.message());
    }

    private static void onBroker(MasterServer server, Runnable action) throws Exception {
        onBroker(server, () -> { action.run(); return null; });
    }

    private static <T> T onBroker(MasterServer server,
                                  java.util.concurrent.Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try { result.complete(action.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private record WorkerAuth(String id, String token) { }
}
