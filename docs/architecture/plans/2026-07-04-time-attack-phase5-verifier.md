# Time Attack Phase 5 — Replay Verification Service (openggf-verifier) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the post-v1 security content (security spec §6, §11 row "4+"): the `openggf-verifier` worker (headless engine + operator ROM replays attempt input recordings), the master-side verification job queue and recording upload flow, signed verdicts into the existing `verdicts` table, verified rooms flipping on (`verified=true`, TRUSTED gate, pending→verified standings), and casual-room spot-checking.

**Architecture:** The master stays engine-free and ROM-free — it only queues jobs, stores recording blobs, verifies Ed25519 verdict signatures, and applies consequences (standings flip/reject, sanctions). All replay happens in `com.openggf.tools.verifier.VerifierMain`, a tools-style main class in the same jar that polls the master over HTTPS, replays via a new engine-side `AttemptReplayHarness`, and signs verdicts with its own `PlayerIdentity`. Recording uploads go client→master over HTTPS (`PUT /recordings/{hash}`), never the game WebSocket.

**Tech Stack:** Java 21, Netty HTTP routes on the existing master pipeline, `java.net.http.HttpClient` (client + verifier), JDK Ed25519, SQLite `IdentityStore` (verdicts table exists since phase 3), JUnit 5.

**Empirical basis (measured — read before sizing the verifier/pool):** the
verifier's replay-cost, memory, and warm-pool assumptions are validated by two
reproducible benchmarks committed under `src/test/java/com/openggf/tests/trace/`:
`s1/TestVerifierCostBenchmark` (pure input-replay cost + per-level footprint) and
`TestVerifierPoolBenchmark` (decode-all-levels-once, service N random jobs warm).
Measured on S1+S2 (S3K pending green traces): a full run replays in ~0.1–2 s
single-core; all 36 S1+S2 levels held resident = ~64 MB (~1.8 MB/level); warm
per-job cost is dominated by replay, not the ~125–140 ms/level decode. Model
`AttemptReplayHarness` on this warm-level path and reuse `HeadlessTestFixture`'s
`withSharedLevel` reuse (no per-job `loadZoneAndAct`). **Do not** reset a warm
worker via lightweight session-reuse without confirming the palette drain fix is
present (`LevelFrameStep.beginFrame()`, CHANGELOG) — without it, palette-cycling
zones degrade ~11× across reused sessions. See security spec §6.6.

## Global Constraints

- Branch: `feature/multiplayer-time-attack` (based on `next`). Doc-trailer commit policy applies to every commit.
- Prerequisites: phases 1–4 implemented on the branch (Task 0 verifies). The phase-4 podium linger is extended here to wait for pending verdicts.
- ArchUnit fence (`TestNetIsolationRules`): everything under `com.openggf.net..` (incl. `net.master`) stays engine-free. **The verifier worker therefore lives in `com.openggf.tools.verifier`** (tools may import both engine and `net.*`); the replay harness lives engine-side in `com.openggf.game.timeattack`.
- **Verified rooms are always relay-routed** (master must observe finishes and own the round outcome; a player-host is untrusted by definition). `RoomCreate` with `verified=true` and routing `DIRECT` is rejected.
- No ROM bytes ever transit the network; the verifier operator supplies their own ROM(s) (security spec §12 / main spec §7). Recording blobs are input masks + descriptor only.
- Exact strings reused from phases 1–3 (do not re-derive): determinism fingerprint `engineVersion + ":" + Integer.toHexString(romChecksum)`; input-recording hash = hex of `AttemptInputRecording.sha256()`; ghost-stream hash = hex of `GhostStreamPublisher.streamHashSha256()` (SHA-256 over the 7-byte `GhostFrameCodec` encoding of every published frame in order); fingerprints = lowercase-hex SHA-256 of X.509 public key (64 chars).
- JUnit 5 only; PowerShell-quote Maven `-D` props; ROM-gated tests use the existing `-Ds3k.rom.path=...` gate idiom.

## Contracts consumed from phases 1–4 (all on the shared branch)

- `com.openggf.game.timeattack`: `AttemptInputRecording` (`appendFrame(heldMask, startHeld)`, `heldMaskAt(frame)` with `START_HELD_BIT=0x20`, `encode()/decode()`, `sha256()`, `MAX_FRAMES=36_000`; frame 0 = spawn, idle frames = zero masks), `AttemptStartDescriptor(gameId, zone, act, character, fingerprint)`, `DeterminismFingerprint(engineVersion, romChecksum).asString()`, `TimeAttackAttempt` (`onFrame(heldMask, endOfLevelActive, checkpointIndex)`, `Phase {ARMED, RUNNING, FINISHED, VOID}`, `firstInputFrame()`, `finishFrame()`, `finalTimeFrames()`), `TimeAttackRuntime.AttemptListener.onAttemptFinished(int attemptOrdinal, int timeFrames, int firstInputFrame, int finishFrame, byte[] inputRecordingSha256)`, `GhostStore` (`.ggfinputs` sidecars).
- `com.openggf.net.protocol`: `ControlMessage.AttemptFinish(attemptId, timeFrames, firstInputFrame, finishFrame, inputRecordingHashHex, ghostStreamHashHex, inputRecordingRef)`; `RecordingRequest(int attemptId, String expectedHashHex, String uploadUrl)` — **reserved since phase 2, sent by nobody until now**; `StandingsRow(slot, displayName, character, bestTimeFrames, rank)`; `RoomDescriptor(..., boolean verified)`; `RoomSummary(..., boolean verified)`; `RoomCreate(room, routing, directPort, determinismFingerprint, voteTrackKeys)`.
- `com.openggf.net.hub.HostRoundEngine` (post phase-4): `Phase {LOBBY, COUNTDOWN, RUNNING, ROUND_END, VOTE}`, `onAttemptFinish(...) → FinishOutcome(slot, rank, outsideBroadcastCap)`, `ROUND_END_LINGER_MILLIS=10_000`, `standings()`, vote machinery.
- `com.openggf.net.identity.PlayerIdentity`: `loadOrCreate(Path)`, `fingerprint()`, `publicKeyEncoded()`, `sign(byte[])`, `static verify(byte[] pub, byte[] msg, byte[] sig)`.
- `com.openggf.net.master`: `IdentityStore` (+ `SqliteIdentityStore`; `verdicts` table already exists: `id, fingerprint, attempt_ref, input_recording_hash, result, verifier_signature, timestamp`), `TrustLadder` (`Tier {NEW, ESTABLISHED, TRUSTED, SANCTIONED}`, `tierOf`, `sanction(SanctionRecord)` — BAN demotes + `resetCleanRounds`), `MasterConfig` (Jackson-YAML, `defaults()`), `MasterServer` (Netty pipeline: `HttpServerCodec` → `HttpObjectAggregator(8192)` → `WebSocketServerProtocolHandler("/master", ...)` → handlers), `AdminEndpoint` (localhost `com.sun.net.httpserver`, Bearer `adminToken`), `SessionRegistry`, `RoomBroker`, `RelayRoomManager`, `SessionTokenIssuer` (`issue()/isValid()`), `RoomHostHooks.RoundOutcomeListener.onRoundComplete(fingerprint, clean)`.
- `com.openggf.game.timeattack.mp.MultiplayerRaceCoordinator` (owns transport + session; `AttemptListener` impl sends `AttemptFinish`), `HudTextLayout.standingsLine(row, policy)` (phase 4).
- Headless boot idiom: the trace-replay suite / `HeadlessTestRunner` (`stepFrame(up, down, left, right, jump)`), phase-1's per-track headless load helper in `TestTimeAttackTrackCatalogRomValidation`.

## What this plan explicitly does NOT build (still deferred)

- Community moderation workflows (reports, reviewer queues) — sanctions remain operator-issued (admin endpoint); security spec §12.
- Ranked/persistent leaderboards; OAuth/account binding; Postgres `IdentityStore`; anti-cheat analytics over stored recordings.
- Multi-master/federation; defending a fully compromised master.

---

### Task 0: Branch check

**Files:** none.

- [ ] **Step 1: Verify prerequisites**

```bash
git checkout feature/multiplayer-time-attack && git pull --ff-only
test -f src/main/java/com/openggf/net/master/MasterServerMain.java && \
grep -q "VOTE" src/main/java/com/openggf/net/hub/HostRoundEngine.java && \
grep -q "RecordingRequest" src/main/java/com/openggf/net/protocol/ControlMessage.java && echo PREREQS-OK
```

Expected: `PREREQS-OK`. Missing vote phase ⇒ execute the phase-4 plan first.

- [ ] **Step 2: Baseline** — `mvn -q compile` → SUCCESS.

---

### Task 1: Protocol — `verifyState` on standings rows + VerdictCodec

**Files:**
- Modify: `src/main/java/com/openggf/net/protocol/ControlMessage.java`
- Create: `src/main/java/com/openggf/net/protocol/VerdictCodec.java`
- Modify: `src/main/java/com/openggf/game/timeattack/mp/HudTextLayout.java`
- Test: `src/test/java/com/openggf/net/protocol/TestVerdictCodec.java`

**Interfaces:**
- Produces:
  - `StandingsRow` gains a final component `String verifyState` — values `"NONE"` (casual), `"PENDING"`, `"VERIFIED"`. Every existing construction site appends `"NONE"` (sweep `grep -rn "new ControlMessage.StandingsRow(" src/`). Rejected finishes are *removed* from standings, never displayed.
  - `VerdictCodec` (engine-free, shared by master and verifier):

```java
public final class VerdictCodec {
    public static final String RESULT_PASS = "PASS";
    public static final String RESULT_FAIL_DIVERGENT = "FAIL_DIVERGENT";
    public static final String RESULT_FAIL_TIME_MISMATCH = "FAIL_TIME_MISMATCH";
    public static final String RESULT_FAIL_GHOST_HASH = "FAIL_GHOST_HASH";
    public static final String RESULT_FAIL_TRACK_MISMATCH = "FAIL_TRACK_MISMATCH"; // recording is for another track/character/build
    public static final String RESULT_VOID_NO_UPLOAD = "VOID_NO_UPLOAD"; // master-issued, unsigned

    /** Canonical signed bytes: UTF-8 of jobId + "\n" + attemptRef + "\n" + recordingHashHex + "\n" + result. */
    public static byte[] canonicalBytes(String jobId, String attemptRef,
            String recordingHashHex, String result);
    public static boolean isPass(String result);   // RESULT_PASS only
    public static boolean isFail(String result);   // the four FAIL_* values
    /** The ONLY results a worker may post: PASS or FAIL_*. VOID_NO_UPLOAD is master-issued
     *  (unsigned, timeout code only) — a leased verifier must not be able to sign it (or any
     *  unknown string) to clear a pending row WITHOUT triggering the cheat sanction. */
    public static boolean isWorkerResult(String result);   // isPass(result) || isFail(result)
}
```

  - `attemptRef` format (used everywhere from here on): `roomId + "#" + slot + "#" + attemptId`, e.g. `"r-7#3#12"`.
  - `HudTextLayout.standingsLine` appends `" .."` for `verifyState.equals("PENDING")` and `" *"` for `"VERIFIED"`.

- [ ] **Step 1: Failing test**

```java
package com.openggf.net.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TestVerdictCodec {

    @Test
    void canonicalBytesAreStableAndNewlineDelimited() {
        byte[] bytes = VerdictCodec.canonicalBytes("job-1", "r-7#3#12", "abcd", VerdictCodec.RESULT_PASS);
        assertEquals("job-1\nr-7#3#12\nabcd\nPASS", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void resultClassification() {
        assertTrue(VerdictCodec.isPass(VerdictCodec.RESULT_PASS));
        assertTrue(VerdictCodec.isFail(VerdictCodec.RESULT_FAIL_DIVERGENT));
        assertTrue(VerdictCodec.isFail(VerdictCodec.RESULT_FAIL_TIME_MISMATCH));
        assertTrue(VerdictCodec.isFail(VerdictCodec.RESULT_FAIL_GHOST_HASH));
        assertTrue(VerdictCodec.isFail(VerdictCodec.RESULT_FAIL_TRACK_MISMATCH));
        assertFalse(VerdictCodec.isFail(VerdictCodec.RESULT_VOID_NO_UPLOAD));
        assertFalse(VerdictCodec.isPass(VerdictCodec.RESULT_VOID_NO_UPLOAD));
        assertTrue(VerdictCodec.isWorkerResult(VerdictCodec.RESULT_PASS));
        assertTrue(VerdictCodec.isWorkerResult(VerdictCodec.RESULT_FAIL_DIVERGENT));
        assertFalse(VerdictCodec.isWorkerResult(VerdictCodec.RESULT_VOID_NO_UPLOAD)); // master-only
        assertFalse(VerdictCodec.isWorkerResult("BOGUS"));
        assertFalse(VerdictCodec.isWorkerResult(null));
    }

    @Test
    void standingsRowCarriesVerifyState() throws Exception {
        ControlMessage.StandingsRow row =
                new ControlMessage.StandingsRow(1, "ana", "sonic", 1885, 1, "PENDING");
        ControlMessage msg = new ControlMessage.StandingsDelta(java.util.List.of(row));
        assertEquals(msg, ControlCodec.decode(ControlCodec.encode("t", msg)).message());
    }
}
```

- [ ] **Step 2: Verify failure** — `mvn "-Dtest=com.openggf.net.protocol.TestVerdictCodec" test` → compile error.

- [ ] **Step 3: Implement** — records/constants per Interfaces; sweep and append `"NONE"` at all `StandingsRow` construction sites (HostRoundEngine, tests, HUD fixtures); `HudTextLayout` suffix logic + adjust its tests.

- [ ] **Step 4: Run** — `mvn "-Dtest=com.openggf.net.protocol.*,com.openggf.net.hub.*,com.openggf.game.timeattack.mp.TestHudTextLayout" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/protocol/ src/main/java/com/openggf/game/timeattack/mp/HudTextLayout.java src/test/java/
git commit -m "feat(net): standings verify-state + canonical verdict codec"
```

---

### Task 2: Client custody — recording handoff + AttemptRecordingVault

**Files:**
- Modify: `src/main/java/com/openggf/game/timeattack/TimeAttackRuntime.java` (AttemptListener signature)
- Create: `src/main/java/com/openggf/game/timeattack/mp/AttemptRecordingVault.java`
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java`
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestAttemptRecordingVault.java`

**Interfaces:**
- Produces:
  - `AttemptListener.onAttemptFinished` gains a final parameter: `(int attemptOrdinal, int timeFrames, int firstInputFrame, int finishFrame, byte[] inputRecordingSha256, AttemptInputRecording recording)` — the runtime already holds the recording at finish time; pass the reference (callee must not mutate). Update the coordinator implementation and any test fakes.
  - `AttemptRecordingVault` (pure, clock-injected — security spec §6.4 client custody):

```java
public final class AttemptRecordingVault {
    public static final long GRACE_MILLIS = 10 * 60_000;   // round duration + grace handled by caller

    public AttemptRecordingVault(java.util.function.LongSupplier clockMillis);
    public void put(String hashHex, byte[] encodedRecording);       // keyed by recording hash
    public java.util.Optional<byte[]> get(String hashHex);
    public void onRoundEnd();          // stamps every held entry to expire at now + GRACE_MILLIS
    public int evictExpired();         // returns evicted count; entries without a stamp are kept
    public int size();
}
```

  - Coordinator: in `onAttemptFinished`, `vault.put(hex(inputRecordingSha256), recording.encode())`; on `RoundEnd` control message (already observed via session pump), call `vault.onRoundEnd()`; `evictExpired()` once per `pump()`.

- [ ] **Step 1: Failing test**

```java
package com.openggf.game.timeattack.mp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TestAttemptRecordingVault {

    @Test
    void keepsUntilRoundEndPlusGraceThenEvicts() {
        long[] now = {0};
        AttemptRecordingVault vault = new AttemptRecordingVault(() -> now[0]);
        vault.put("aa", new byte[] {1});
        vault.put("bb", new byte[] {2});
        now[0] += 3_600_000;                       // long round: still held (no stamp yet)
        assertEquals(0, vault.evictExpired());
        vault.onRoundEnd();
        now[0] += AttemptRecordingVault.GRACE_MILLIS - 1;
        assertEquals(0, vault.evictExpired());
        assertTrue(vault.get("aa").isPresent());
        now[0] += 2;
        assertEquals(2, vault.evictExpired());
        assertTrue(vault.get("aa").isEmpty());
    }

    @Test
    void newAttemptAfterRoundEndIsNotStampedByOldRound() {
        long[] now = {0};
        AttemptRecordingVault vault = new AttemptRecordingVault(() -> now[0]);
        vault.put("aa", new byte[] {1});
        vault.onRoundEnd();
        vault.put("bb", new byte[] {2});           // next round's attempt
        now[0] += AttemptRecordingVault.GRACE_MILLIS + 1;
        assertEquals(1, vault.evictExpired());
        assertTrue(vault.get("bb").isPresent());
    }
}
```

- [ ] **Step 2: Verify failure** — class not found.

- [ ] **Step 3: Implement** — `LinkedHashMap<String, Entry(byte[] bytes, long expiresAt /* -1 = unstamped */)>`; listener signature change + all implementors/fakes; coordinator wiring per Interfaces.

- [ ] **Step 4: Run** — `mvn "-Dtest=com.openggf.game.timeattack.**" test` → PASS (listener fakes updated).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/ src/test/java/com/openggf/game/timeattack/
git commit -m "feat(timeattack): attempt-recording custody vault + listener recording handoff"
```

---

### Task 3: Client upload — RecordingRequest handling + HTTPS uploader

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/mp/RecordingUploader.java`
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java`
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestRecordingUploader.java`

**Interfaces:**
- Produces:

```java
public final class RecordingUploader implements AutoCloseable {
    public RecordingUploader(String sessionToken, boolean trustInsecure);  // trustInsecure reuses the phase-3 masterTrustInsecure config semantics
    /** PUT uploadUrl (ABSOLUTE http/https — resolve first, see below), body = recording bytes,
     *  headers: Authorization: Bearer <token>, Content-Type: application/octet-stream.
     *  Runs on an internal single-thread executor; never blocks the caller. One retry after 2 s on IO failure. */
    public void upload(String uploadUrl, byte[] recording, java.util.function.Consumer<Boolean> onDone);

    /** The master's DEFAULT RecordingRequest carries a path-only uploadUrl ("/recordings/" + hash,
     *  because a blank publicBaseUrl means the master cannot know its own public address — Task 6).
     *  HttpClient cannot send a relative URI, so the caller resolves against the configured master
     *  URL first: wss://host:port/master + "/recordings/ab" -> "https://host:port/recordings/ab"
     *  (ws:// -> http://; the /master path is dropped). Absolute http(s) URLs pass through unchanged. */
    public static String resolveUploadUrl(String uploadUrlOrPath, String configuredMasterUrl);
    @Override public void close();
}
```

- Coordinator: on inbound `ControlMessage.RecordingRequest(attemptId, expectedHashHex, uploadUrl)` → `vault.get(expectedHashHex)` → present: `uploader.upload(RecordingUploader.resolveUploadUrl(request.uploadUrl(), configuredMasterUrl), ...)` where `configuredMasterUrl` is the `timeAttack.net.masterUrl` config value (path-only default URLs are unusable without this resolution); absent: also check the `GhostStore` best-run sidecar (`.ggfinputs` whose `AttemptInputRecording.decode(...).sha256()` hex matches) for spot-checks that arrive after eviction; still absent: log and ignore (the master voids on deadline). `shutdown()` closes the uploader.

- [ ] **Step 1: Failing test** — spin a throwaway `com.sun.net.httpserver.HttpServer` on port 0 inside the test:

```java
package com.openggf.game.timeattack.mp;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TestRecordingUploader {

    @Test
    void putsBodyWithBearerToken() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/recordings/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try (RecordingUploader uploader = new RecordingUploader("tok123", true)) {
            CountDownLatch done = new CountDownLatch(1);
            boolean[] ok = new boolean[1];
            uploader.upload("http://127.0.0.1:" + server.getAddress().getPort() + "/recordings/aa",
                    new byte[] {1, 2, 3}, result -> { ok[0] = result; done.countDown(); });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(ok[0]);
            assertEquals("Bearer tok123", auth.get());
            assertArrayEquals(new byte[] {1, 2, 3}, body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesPathOnlyUploadUrlsAgainstTheConfiguredMasterUrl() {
        assertEquals("https://m.example:27900/recordings/ab",
                RecordingUploader.resolveUploadUrl("/recordings/ab", "wss://m.example:27900/master"));
        assertEquals("http://127.0.0.1:1234/recordings/ab",
                RecordingUploader.resolveUploadUrl("/recordings/ab", "ws://127.0.0.1:1234/master"));
        assertEquals("https://cdn.example/recordings/ab",
                RecordingUploader.resolveUploadUrl("https://cdn.example/recordings/ab", "wss://m.example/master"));
    }
}
```

- [ ] **Step 2: Verify failure** — class not found.

- [ ] **Step 3: Implement** — `java.net.http.HttpClient` (insecure-trust SSLContext when `trustInsecure`, same helper the phase-3 `MasterClient` uses), `Executors.newSingleThreadExecutor` with daemon threads, 10 s request timeout, one retry, `onDone.accept(status/100 == 2)`.

- [ ] **Step 4: Run** — `mvn "-Dtest=com.openggf.game.timeattack.mp.TestRecordingUploader" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/mp/ src/test/java/com/openggf/game/timeattack/mp/TestRecordingUploader.java
git commit -m "feat(timeattack): out-of-band HTTPS recording upload on RecordingRequest"
```

---

### Task 4: Master pure logic — VerifierRegistry + VerificationJobQueue

**Files:**
- Create: `src/main/java/com/openggf/net/master/VerifierRegistry.java`
- Create: `src/main/java/com/openggf/net/master/VerificationJobQueue.java`
- Test: `src/test/java/com/openggf/net/master/TestVerificationJobQueue.java`, `src/test/java/com/openggf/net/master/TestVerifierRegistry.java`

**Interfaces:**
- Produces (both clock-injected, single-threaded on the broker loop like the rest of `net.master`):

```java
public final class VerifierRegistry {
    public record Worker(String workerId, byte[] publicKeyEncoded, String workerToken,
                         java.util.Set<String> fingerprints, long lastSeenMillis) {}
    public VerifierRegistry(java.util.function.LongSupplier clock, long staleAfterMillis);
    /** workerId = identity fingerprint of the pubkey (recomputed, never trusted); returns the worker (new random token on first register, refreshed lastSeen after). */
    public Worker register(byte[] publicKeyEncoded, java.util.Set<String> fingerprints);
    public java.util.Optional<Worker> authenticate(String workerId, String workerToken); // refreshes lastSeen
    public boolean verifierAvailable(String determinismFingerprint);   // any non-stale worker supporting it
    public int expireStale();
}

public final class VerificationJobQueue {
    public enum State { AWAITING_UPLOAD, QUEUED, LEASED, DONE, VOID }
    public record Job(String jobId, String roomId, int slot, String identityFingerprint,
                      String attemptRef, String determinismFingerprint, String trackKey,
                      String character, int claimedTimeFrames, int firstInputFrame,
                      int finishFrame, String inputRecordingHashHex, String ghostStreamHashHex,
                      boolean spotCheck, long createdAtMillis) {}

    public VerificationJobQueue(java.util.function.LongSupplier clock, long leaseMillis);
    /** -> AWAITING_UPLOAD; returns jobId ("vj-" + counter). Per-job deadline: verified-room
     *  finishes use the SHORT verifiedUploadDeadlineSeconds window, spot-checks the long
     *  uploadDeadlineSeconds one (Task 7/11 wiring). */
    public String submit(Job job, long uploadDeadlineAtMillis);
    public void onRecordingUploaded(String recordingHashHex);         // all AWAITING_UPLOAD jobs with that hash -> QUEUED
    public java.util.Optional<Job> lease(String workerId, java.util.Set<String> supportedFingerprints); // records the leaseholder
    /** DONE only when the job is LEASED by THAT worker; empty for never-leased jobs or a
     *  foreign workerId — an authenticated verifier must not be able to close another
     *  worker's job (the verdict signature proves authorship, not lease ownership). */
    public java.util.Optional<Job> complete(String jobId, String workerId);
    public java.util.List<Job> voidExpiredUploads();                  // AWAITING_UPLOAD past ITS deadline -> VOID; returned for consequence routing
    public int requeueExpiredLeases();                                // LEASED past lease -> QUEUED, leaseholder cleared
    public State stateOf(String jobId);
}
```

- [ ] **Step 1: Failing tests** (representative cases — write all of these):

```java
// TestVerificationJobQueue
@Test void submitAwaitsUploadThenQueuesOnMatchingHash() { ... }
@Test void leaseOnlyMatchingFingerprintAndOnlyQueued() {
    // job fingerprint "0.6:cafe": worker supporting only "0.7:beef" leases nothing;
    // matching worker gets it exactly once (second lease -> empty)
}
@Test void uploadDeadlineVoidsAndReturnsJobOnce() { ... }
@Test void perJobUploadDeadlinesVoidIndependently() {
    // two jobs submitted with different uploadDeadlineAtMillis: advancing past the earlier
    // deadline voids only that job; the later one stays AWAITING_UPLOAD
}
@Test void expiredLeaseRequeuesAndCanBeLeasedAgain() { ... } // and the requeued job is completable by its NEW leaseholder only
@Test void completeRequiresTheLeaseholder() {
    // never-leased QUEUED job -> complete(jobId, "w1") empty, state unchanged;
    // leased by "w1" -> complete(jobId, "w2") empty and still LEASED;
    // complete(jobId, "w1") -> job returned, DONE; second complete -> empty (idempotent)
}

// TestVerifierRegistry
@Test void registerComputesWorkerIdFromPubkeyAndIssuesToken() {
    // workerId equals lowercase-hex SHA-256 of the encoded key; authenticate(id, token) works;
    // authenticate with wrong token -> empty
}
@Test void staleWorkerNotAvailableUntilReRegister() { ... }
```

Use real `PlayerIdentity.loadOrCreate(@TempDir)` keys for the registry test (JDK Ed25519, no mocking).

- [ ] **Step 2: Verify failure** — compile errors.

- [ ] **Step 3: Implement** — plain maps + counters, `SecureRandom` hex token (reuse the `SessionTokenIssuer` token style), SHA-256 fingerprint helper copied from `PlayerIdentity`'s (or expose a static `PlayerIdentity.fingerprintOf(byte[] encoded)` and reuse — preferred; add it with a one-line test).

- [ ] **Step 4: Run** — `mvn "-Dtest=com.openggf.net.master.TestVerificationJobQueue,com.openggf.net.master.TestVerifierRegistry" test` → PASS. Fence: `mvn "-Dtest=com.openggf.tests.TestNetIsolationRules" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/ src/main/java/com/openggf/net/identity/ src/test/java/com/openggf/net/
git commit -m "feat(net): verifier registry + fingerprint-routed verification job queue"
```

---

### Task 5: Verdict persistence + consequences — IdentityStore.addVerdict, cheat demotion

**Files:**
- Modify: `src/main/java/com/openggf/net/master/IdentityStore.java`, `SqliteIdentityStore.java`
- Create: `src/main/java/com/openggf/net/master/VerdictConsequences.java`
- Test: `src/test/java/com/openggf/net/master/TestVerdictPersistence.java`

**Interfaces:**
- Produces:
  - `IdentityStore` additions (SQLite writes into the **existing** `verdicts` table):

```java
record VerdictRecord(String fingerprint, String attemptRef, String inputRecordingHashHex,
                     String result, String verifierSignatureBase64 /* null for VOID_NO_UPLOAD */,
                     long timestampMillis) {}
void addVerdict(VerdictRecord verdict);
java.util.List<VerdictRecord> verdictsFor(String fingerprint);
```

  - `VerdictConsequences` (single choke point so room routing and sanctions can't drift apart):

```java
public final class VerdictConsequences {
    public VerdictConsequences(IdentityStore store, TrustLadder ladder,
            java.util.function.LongSupplier clock, long cheatBanMillis /* <=0 -> permanent */);
    /** Persists the verdict; on a FAIL_* result issues sanction type "BAN" reason "cheat verdict: " + result
     *  (issuer "verifier:" + workerId) — TrustLadder.sanction demotes + resets clean rounds. Returns true when the result is a pass. */
    public boolean apply(IdentityStore.VerdictRecord verdict, String workerId);
}
```

- [ ] **Step 1: Failing test**

```java
@Test void addVerdictRoundTripsThroughSqlite(@TempDir Path dir) {
    try (SqliteIdentityStore store = new SqliteIdentityStore(dir.resolve("ids.db"))) {
        store.persistOnDurableEvent("fp1", 0, 0);
        store.addVerdict(new IdentityStore.VerdictRecord("fp1", "r-1#2#3", "abcd",
                VerdictCodec.RESULT_PASS, "sigB64", 42));
        var verdicts = store.verdictsFor("fp1");
        assertEquals(1, verdicts.size());
        assertEquals("r-1#2#3", verdicts.get(0).attemptRef());
    }
}

@Test void failVerdictSanctionsAndDemotes(@TempDir Path dir) {
    // build store + NewIdentityCache + TrustLadder with defaults, fingerprint at ESTABLISHED
    // (persist + recordCleanRound x10 + aged first-seen), then:
    // consequences.apply(FAIL_DIVERGENT verdict, "worker-1") -> false
    // ladder.tierOf(fp) == Tier.SANCTIONED (BAN active); verdict row present
}

@Test void voidNoUploadPersistsWithoutSanction(@TempDir Path dir) {
    // apply(VOID_NO_UPLOAD, null signature) -> false, but tier unchanged and no sanction rows
}
```

- [ ] **Step 2: Verify failure**, **Step 3: Implement** (SQL: `INSERT INTO verdicts(fingerprint, attempt_ref, input_recording_hash, result, verifier_signature, timestamp) VALUES (?,?,?,?,?,?)`; `verdictsFor` ordered by `id`), **Step 4: Run** `mvn "-Dtest=com.openggf.net.master.TestVerdictPersistence,com.openggf.net.master.TestIdentityStore*,com.openggf.net.master.TestTrustLadder*" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/ src/test/java/com/openggf/net/master/TestVerdictPersistence.java
git commit -m "feat(net): verdict persistence + cheat-verdict sanction consequences"
```

---

### Task 6: Master HTTP surface — recordings + verifier API, MasterConfig fields

**Files:**
- Modify: `src/main/java/com/openggf/net/master/MasterConfig.java`
- Create: `src/main/java/com/openggf/net/master/MasterHttpRoutes.java` (Netty `SimpleChannelInboundHandler<FullHttpRequest>`)
- Modify: `src/main/java/com/openggf/net/master/MasterServer.java` (pipeline)
- Create: `src/main/java/com/openggf/net/master/RecordingBlobStore.java`
- Test: `src/test/java/com/openggf/net/master/TestMasterHttpRoutes.java`

**Interfaces:**
- `MasterConfig` new fields (compact-ctor defaults in parens): `int maxRecordingBytes` (65_536), `long uploadDeadlineSeconds` (180 — SPOT-CHECK jobs only; the player may have left the post-round screen), `long verifiedUploadDeadlineSeconds` (15 — verified-room finish jobs; short because the `RecordingRequest` fires at finish time while the client is still connected, and the podium hold must OUTLAST this window — Task 7), `long recordingRetentionDays` (3), `long verdictGraceMillis` (10_000), `int spotCheckTopTimes` (1), `long cheatBanDays` (0 = permanent), `String verifierRegistrationToken` (null = verifier API disabled), `long verifierStaleSeconds` (120), `long verifierLeaseSeconds` (300), `String publicBaseUrl` ("" = derive `https://<host>` is impossible server-side; when blank, `RecordingRequest.uploadUrl` is sent as a path-only `"/recordings/" + hash` and clients resolve it against their configured master URL).
- `RecordingBlobStore(Path dir)`: `void put(String hashHex, byte[] bytes)` (writes `dir/<hashHex>`), `Optional<byte[]> get(String hashHex)`, `int deleteOlderThan(long cutoffMillis)`; hash-shaped-name validation (`[0-9a-f]{64}`) — never trust path input.
- `MasterHttpRoutes` — inserted in the pipeline after the (now enlarged) `HttpObjectAggregator(config.maxRecordingBytes() + 8192)` and **before** `WebSocketServerProtocolHandler`; passes WebSocket-upgrade requests and `/master` through untouched (`ctx.fireChannelRead`). Routes:
  - `PUT /recordings/{hash}` — `Authorization: Bearer <sessionToken>` valid per `SessionTokenIssuer.isValid` else 401; body ≤ `maxRecordingBytes` else 413; SHA-256(body) hex == `{hash}` else 400; store + `jobQueue.onRecordingUploaded(hash)`; 204.
  - `POST /verifier/register` — 404 unless `verifierRegistrationToken` configured; `Authorization: Bearer <verifierRegistrationToken>` else 401; JSON body `{"pubKeyBase64": "...", "fingerprints": ["0.6:cafe1234"]}` → `{"workerId": "...", "workerToken": "..."}`.
  - `GET /verifier/jobs` — headers `X-Worker-Id` + `Authorization: Bearer <workerToken>` via `VerifierRegistry.authenticate` else 401; lease → 200 JSON of the `Job` record, or 204.
  - `GET /recordings/{hash}` — worker auth (same headers) else 401; blob or 404.
  - `POST /verifier/verdicts` — worker auth; JSON `{"jobId": "...", "result": "PASS", "signatureBase64": "..."}`; FIRST `VerdictCodec.isWorkerResult(result)` else 400 with nothing applied (a signature makes a result authentic, not legitimate: a leased verifier posting a signed `VOID_NO_UPLOAD` or `"BOGUS"` would otherwise remove a pending row while dodging the FAIL_* cheat sanction — `VOID_NO_UPLOAD` may only ever originate from the master's own upload-timeout code); then `PlayerIdentity.verify(worker.publicKeyEncoded(), VerdictCodec.canonicalBytes(jobId, job.attemptRef(), job.inputRecordingHashHex(), result), sig)` else 400; `jobQueue.complete(jobId, workerId)` — empty (never leased, or leased by a DIFFERENT worker) → 409 rejected, nothing applied: the signature proves who wrote the verdict, only the lease proves the master assigned this job to them; then `VerdictConsequences.apply` + room routing callback (Task 7); 204. Test all four rejection cases in `TestMasterHttpRoutes`: verdict for an unleased job; verdict from worker B for worker A's lease; a correctly-signed `VOID_NO_UPLOAD` from the leaseholder → 400, job stays LEASED, no verdict row, no room routing; a correctly-signed `"BOGUS"` result → same.
  - Anything else under `/recordings` or `/verifier` → 404. All handlers run on the broker loop via `server.execute(...)` for queue/registry access (single-thread discipline of `net.master`).

- [ ] **Step 1: Failing test** — drive `MasterHttpRoutes` with Netty `EmbeddedChannel` (no sockets): upload happy path + wrong hash 400 + oversized 413 + bad token 401; register→lease→verdict happy path with a real `PlayerIdentity` signing; bad signature 400. Write each as its own `@Test` with `FullHttpRequest` construction (`Unpooled.wrappedBuffer`, headers as specified).

- [ ] **Step 2: Verify failure**, **Step 3: Implement** per Interfaces (route + auth + caps first, storage second — no parser sees unbounded input; this extends the phase-2 §7.3 hygiene posture to HTTP).

- [ ] **Step 4: Run** — `mvn "-Dtest=com.openggf.net.master.TestMasterHttpRoutes,com.openggf.net.master.TestMasterConfig*" test` → PASS; fence test PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/ src/test/java/com/openggf/net/master/TestMasterHttpRoutes.java
git commit -m "feat(net): master HTTPS recording uploads + verifier job/verdict API"
```

---

### Task 7: Verified rooms — creation/join gates, pending standings, verdict routing, podium hold

**Files:**
- Modify: `src/main/java/com/openggf/net/master/RoomBroker.java`, `RelayRoomManager.java`, `MasterServer.java` (wiring)
- Modify: `src/main/java/com/openggf/net/hub/HostRoundEngine.java`
- Modify: `src/main/java/com/openggf/net/host/RoomHost.java`
- Test: `src/test/java/com/openggf/net/hub/TestHostRoundEngineVerified.java`, extend `src/test/java/com/openggf/net/master/TestRoomBroker*.java`

**Interfaces:**
- Broker gates (security spec §6.3/§4): `RoomCreate` with `room.verified() == true` requires routing `"RELAY"` (else `RoomCreateRejected("verified rooms are relay-only")`), creator `TrustLadder.Tier.TRUSTED` (else `"verified rooms require TRUSTED"`), and `verifierRegistry.verifierAvailable(create.determinismFingerprint())` (else `"no verifier available for this build"`). `RoomJoinRequest` to a verified room requires TRUSTED (else `RoomJoinRejected("verified rooms require TRUSTED")`). The room descriptor keeps `verified=true` through `SessionRegistry` → browser (`RoomSummary.verified`) — the badge goes live.
- `HostRoundEngine` additions:

```java
public void setVerifiedRoom(boolean verified);            // default false
public void setPendingHoldMillis(long millis);            // default 10_000; the master wires
        // verifiedUploadDeadlineSeconds * 1000 + verdictGraceMillis — the hold MUST outlast the
        // verified upload window, or legitimate pending rows get dropped (~20 s) while the
        // client is still allowed to upload
public int pendingVerdictCount();
/** In a verified room a finish enters standings with verifyState="PENDING" (casual: "NONE").
 *  FinishOutcome gains the attemptRef data the hooks need: */
public record FinishOutcome(int slot, int rank, boolean outsideBroadcastCap, int attemptId) {}
/** pass -> row flips to "VERIFIED"; fail -> row removed, ranks recomputed; both rebroadcast StandingsDelta. Unknown slot/attempt ignored. */
public void onVerdict(int slot, int attemptId, boolean pass);
/** The accepted AttemptFinish backing the slot's CURRENT standings row (null when none) —
 *  stored on every accepted improving finish, cleared by startRound. Spot-checking (Task 11)
 *  needs the ORIGINAL recording/ghost hashes and frame fields to build a verification job;
 *  StandingsRow carries only display data (name, time, rank), so without this retention a
 *  round-end spot-check has nothing to verify against. Applies to casual AND verified rooms. */
public ControlMessage.AttemptFinish bestFinish(int slot);
```

  ROUND_END→(VOTE|LOBBY) transition is deferred while `pendingVerdictCount() > 0`, up to `ROUND_END_LINGER_MILLIS + pendingHoldMillis`. Pending rows are removed ONLY by a verdict or an upload void inside the hold; because the hold outlasts `verifiedUploadDeadlineSeconds`, every pending row resolves (verdict or `VOID_NO_UPLOAD`) before the hold can expire in normal operation. If the hold DOES expire with rows still pending (verifier stalled), the remaining PENDING rows are removed AND the master voids their jobs via the normal `onVerdict(slot, attemptId, false)` routing — queue and standings must never disagree about an attempt's fate (unproven times don't podium — spec §6.3 "podium waits for pending verdicts").
- `RoomHost` gains an optional hook interface (null for player-hosted rooms):

```java
public interface VerificationHooks {
    /** Called for every accepted finish in a verified room, and for spot-check selections. */
    void onFinishNeedingVerification(String roomId, int slot, String identityFingerprint,
            ControlMessage.AttemptFinish finish, String trackKey, String character,
            String determinismFingerprint, boolean spotCheck);
}
```

- Master wiring (`RelayRoomManager` implements the hook): build `VerificationJobQueue.Job` (attemptRef = `roomId + "#" + slot + "#" + finish.attemptId()`), `jobQueue.submit(job, now + (spotCheck ? config.uploadDeadlineSeconds() : config.verifiedUploadDeadlineSeconds()) * 1000)`, send `RecordingRequest(finish.attemptId(), finish.inputRecordingHashHex(), uploadUrlFor(hash))` to that member. Keep `Map<String jobId, RoomRef(roomId, slot, attemptId, boolean spotCheck)>`; on verdict completion or `voidExpiredUploads()`, standings routing BRANCHES on `spotCheck`: verified-room finish jobs (`spotCheck=false`) route `engine.onVerdict(slot, attemptId, pass)` on the room's event loop (VOID/FAIL ⇒ `pass=false`); spot-check jobs route NOTHING into standings — casual times are client-reported by design (main spec §2) and their rows were never PENDING, so a spot-check FAIL's consequence is the `VerdictConsequences` sanction + persisted verdict row only (and a spot-check VOID is just the unsigned verdict row). Without this branch a spot-check FAIL/VOID would mutate a casual room's standings through a code path Task 11 promises never touches them.

- [ ] **Step 1: Failing tests**

`TestHostRoundEngineVerified` (same harness style as the vote test):

```java
@Test void verifiedFinishEntersPendingAndFlipsVerifiedOnPassVerdict() { ... }
@Test void failVerdictRemovesRowAndRecomputesRanks() { ... }      // 3 finishers, middle fails -> ranks 1,2
@Test void roundEndHoldsForPendingThroughTheFullHoldWindow() {
    // pending row at ROUND_END: linger expiry does NOT release while pendingHoldMillis remains;
    // a PASS verdict inside the hold flips it VERIFIED and releases; a second scenario lets the
    // hold expire -> row removed (the master voids the job via onVerdict(false) — Task 7 wiring)
}
@Test void casualRoomRowsStayNoneAndNothingHolds() { ... }
@Test void bestFinishRetainsTheAcceptedOriginalPerSlot() {
    // accepted finish -> bestFinish(slot) returns THAT AttemptFinish (same hashes/frames);
    // a slower later finish does not replace it; a faster one does; startRound clears it;
    // bestFinish(unknownSlot) == null
}
```

Broker test additions: the three creation rejections + the TRUSTED join gate (reuse the existing broker test fixture; put the creator at TRUSTED via the ladder helpers).

- [ ] **Step 2: Verify failure**, **Step 3: Implement** per Interfaces. `FinishOutcome` change: sweep constructors (phase-3/4 call sites gain `finish.attemptId()`).

- [ ] **Step 4: Run** — `mvn "-Dtest=com.openggf.net.hub.*,com.openggf.net.master.*,com.openggf.net.host.*" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/ src/test/java/com/openggf/net/
git commit -m "feat(net): verified rooms live - trust gates, pending standings, verdict routing"
```

---

### Task 8: Engine — shared GhostFrameSampler extraction

**Files:**
- Create: `src/main/java/com/openggf/game/ghost/GhostFrameSampler.java`
- Modify: `src/main/java/com/openggf/game/timeattack/TimeAttackRuntime.java` (delegate to it)
- Test: `src/test/java/com/openggf/game/ghost/TestGhostFrameSampler.java`

**Interfaces:**
- Produces: `public static GhostFrame GhostFrameSampler.sample(AbstractPlayableSprite sprite, boolean finished)` — extracted verbatim from the phase-1 sampling code in `TimeAttackRuntime` (centre x/y, resolved `mappingFrame`, `renderHFlip`/`renderVFlip`, priority bucket + high-priority — the exact post-animation values the runtime publishes today). The runtime calls the shared method; the replay harness (Task 9) calls the same one — **this is what makes the replayed ghost-stream hash byte-comparable to the live one.**

- [ ] **Step 1: Failing test** — locate the runtime's current sampling block (`grep -n "GhostFrame(" src/main/java/com/openggf/game/timeattack/TimeAttackRuntime.java`), then write a test that builds a headless sprite (the phase-1 runtime tests already construct one — reuse that helper), sets position/flip/priority, and asserts `GhostFrameSampler.sample(...)` equals the frame the runtime publishes via `onFrameSampled` for the same sprite state.

- [ ] **Step 2: Verify failure**, **Step 3: Implement** (pure move — no behavior change), **Step 4: Run** `mvn "-Dtest=com.openggf.game.ghost.TestGhostFrameSampler,com.openggf.game.timeattack.*" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/ src/test/java/com/openggf/game/
git commit -m "refactor(timeattack): extract shared GhostFrameSampler for live and replay paths"
```

---

### Task 9: Engine — AttemptReplayHarness (headless deterministic replay)

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/AttemptReplayHarness.java`
- Test: `src/test/java/com/openggf/game/timeattack/TestAttemptReplayHarness.java` (ROM-gated)

**Interfaces:**
- Produces:

```java
public final class AttemptReplayHarness {
    public record Result(boolean finished, int firstInputFrame, int finishFrame,
                         int finalTimeFrames, String ghostStreamHashHex, int framesSimulated,
                         String failureReason /* null on clean replay */) {}

    /** Replays an input-only attempt recording from the canonical start state.
     *  Loads the ROM at romPath, verifies DeterminismFingerprint matches recording.start().fingerprint()
     *  (mismatch -> Result with failureReason "fingerprint mismatch", nothing simulated),
     *  boots the recording's gameId/zone/act/character headless, then feeds heldMaskAt(f) for
     *  every recorded frame, driving a TimeAttackAttempt with the sim's real endOfLevelActive /
     *  checkpointIndex and hashing GhostFrameSampler.sample(...) with SHA-256 per frame.
     *  Stops at FINISHED, VOID, or end of recorded frames. */
    public static Result replay(AttemptInputRecording recording, java.nio.file.Path romPath);
}
```

- Input mask decomposition: bits 0–4 are the `AbstractPlayableSprite` INPUT_* held bits exactly as `AttemptInputRecording` documents; map them onto `HeadlessTestRunner.stepFrame(up, down, left, right, jump)` using the INPUT_* constants (read them from `AbstractPlayableSprite` — do not re-guess bit order). `START_HELD_BIT` (0x20) is ignored during replay (pause has no sim effect in an attempt).
- Bootstrap: reuse the exact headless load path phase 1's `TestTimeAttackTrackCatalogRomValidation` helper uses (module detect from ROM, `@FullReset`-style singleton reset, level load, `GroundSensor.setLevelManager`, `Camera.updatePosition(true)`) — the same bootstrap-contract discipline as the trace suite. Timing truth lives in `TimeAttackAttempt` (shared with the live client), so `firstInputFrame`/`finishFrame`/`finalTimeFrames` are derived, not trusted.

- [ ] **Step 1: Failing test** (gate like `TestRomLogic`: skip when the S3K ROM property/file is absent):

```java
@Test
void replayIsDeterministicAndDoctoredInputsDiverge() {
    // 1. Build a recording by SIMULATING first: boot the harness's own bootstrap for
    //    ("s3k", 0, 0, "sonic"), drive ~600 frames of scripted input (hold right 300,
    //    jump at 120 and 400) while appending each frame's mask to an AttemptInputRecording.
    // 2. replay(recording, romPath) twice -> identical Results (same ghostStreamHashHex,
    //    same framesSimulated) — determinism.
    // 3. Doctor a copy: flip frame 200's mask (add JUMP). replay -> ghostStreamHashHex differs.
    // 4. Truncated hostile recording (chop 100 frames) -> finished=false unless it finished earlier;
    //    framesSimulated == recorded frame count.
}

@Test
void fingerprintMismatchRefusesToSimulate() {
    // recording with start fingerprint "0.0:00000000" -> failureReason "fingerprint mismatch"
}
```

Scripted-input recording construction keeps the test self-contained — no trace fixtures needed; a short run that never reaches the signpost is fine (asserts hash determinism, not finish).

- [ ] **Step 2: Verify failure**, **Step 3: Implement**, **Step 4: Run** — `mvn "-Dtest=com.openggf.game.timeattack.TestAttemptReplayHarness" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" test` → PASS (and SKIPPED cleanly without the ROM).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/ src/test/java/com/openggf/game/timeattack/
git commit -m "feat(timeattack): headless deterministic attempt replay harness"
```

---

### Task 10: openggf-verifier worker

**Files:**
- Create: `src/main/java/com/openggf/tools/verifier/VerifierMain.java`
- Create: `src/main/java/com/openggf/tools/verifier/VerifierWorker.java` (loop logic, HTTP-client-injected for tests)
- Test: `src/test/java/com/openggf/tools/verifier/TestVerifierWorker.java`

**Interfaces:**
- `VerifierMain` CLI: `--master https://host:27900 --registration-token <tok> --rom <path> [--rom <path>...] [--data ./verifier-data] [--trust-insecure] [--once]`. Identity: `PlayerIdentity.loadOrCreate(dataDir)`. Supported fingerprints computed at startup: for each ROM, boot enough to compute `DeterminismFingerprint` (engine version + ROM checksum) and remember ROM-path-by-fingerprint.
- `VerifierWorker` (testable core):

```java
public final class VerifierWorker {
    public interface MasterApi {                        // implemented over java.net.http in VerifierMain
        String register(byte[] pubKeyEncoded, java.util.Set<String> fingerprints); // -> workerToken (stores workerId too)
        java.util.Optional<String> pollJobJson();       // GET /verifier/jobs -> body or empty on 204
        byte[] fetchRecording(String hashHex);          // GET /recordings/{hash}
        void postVerdict(String jobId, String result, byte[] signature);
    }
    public interface Replayer {                          // production: AttemptReplayHarness::replay via the fingerprint->ROM map
        AttemptReplayHarness.Result replay(AttemptInputRecording recording, String determinismFingerprint);
    }

    public VerifierWorker(MasterApi api, Replayer replayer, PlayerIdentity identity);

    /** One poll cycle; returns true if a job was processed. Decision table (in order):
     *  recording bytes' sha256 hex != job.inputRecordingHashHex -> FAIL_DIVERGENT (tampered blob)
     *  recording.start() doesn't match the job — trackKey != start gameId:zone:act, or
     *      character/determinism fingerprint differ              -> FAIL_TRACK_MISMATCH, WITHOUT replaying
     *      (the recording is otherwise valid, so replay alone cannot catch this: a client could
     *      upload a genuine run from a DIFFERENT same-build track or character and have it
     *      "verify" this room's claim — the job's track/character bind the claim, not the blob)
     *  result.failureReason != null || !result.finished        -> FAIL_DIVERGENT
     *  finalTimeFrames != claimed || firstInputFrame/finishFrame mismatch -> FAIL_TIME_MISMATCH
     *  ghostStreamHashHex != job.ghostStreamHashHex             -> FAIL_GHOST_HASH (§6.5 position cross-check)
     *  else                                                     -> PASS
     *  Signature = identity.sign(VerdictCodec.canonicalBytes(jobId, attemptRef, recordingHash, result)). */
    public boolean pollOnce();
}
```

- [ ] **Step 1: Failing test** — fake `MasterApi` + fake `Replayer`; one `@Test` per decision-table row asserting the posted result string and that the posted signature verifies via `PlayerIdentity.verify` against the worker's own pubkey; plus `pollOnce()` false on 204. The track-mismatch rows get explicit cases: a recording whose `AttemptStartDescriptor` is `("s3k", 1, 0, ...)` against a job with `trackKey = "s3k:0:0"` → `FAIL_TRACK_MISMATCH` and the fake `Replayer` must NOT have been invoked; same for a character mismatch (`"tails"` vs job `"sonic"`) and a fingerprint mismatch.

- [ ] **Step 2: Verify failure**, **Step 3: Implement** (`VerifierMain`: register, then loop `pollOnce()` with 2 s idle sleep; `--once` processes one job and exits — CI hook), **Step 4: Run** `mvn "-Dtest=com.openggf.tools.verifier.TestVerifierWorker" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/tools/verifier/ src/test/java/com/openggf/tools/verifier/
git commit -m "feat(tools): openggf-verifier worker - poll, replay, signed verdicts"
```

---

### Task 11: Spot-checking + retention GC

**Files:**
- Modify: `src/main/java/com/openggf/net/master/RelayRoomManager.java` (round-outcome spot-check selection)
- Modify: `src/main/java/com/openggf/net/master/MasterServer.java` (periodic tick: `voidExpiredUploads` routing, `requeueExpiredLeases`, `expireStale` workers, `RecordingBlobStore.deleteOlderThan(now - recordingRetentionDays)`)
- Test: `src/test/java/com/openggf/net/master/TestSpotCheck.java`

**Interfaces:**
- Spot-check rule (security spec §6.3): when a **casual** relay room's round reaches ROUND_END, and `verifierRegistry.verifierAvailable(room fingerprint)`, select the top `config.spotCheckTopTimes()` standings rows and, FOR EACH, read `engine.bestFinish(row.slot())` on the room's loop — the retained original `AttemptFinish` (Task 7) — then invoke the Task-7 hook with `spotCheck=true` and that ORIGINAL message (a `StandingsRow` has no attemptId, hashes, or frame fields; a job built from anything but the original finish would verify fabricated data). `bestFinish` null (defensive — a row without a retained finish) → skip that row. Spot-check jobs are submitted with the LONG `uploadDeadlineSeconds` window — the player may have left the post-round screen; verified finishes use the short `verifiedUploadDeadlineSeconds` window (Task 7). Spot-check jobs skip standings routing entirely — a FAIL still sanctions via `VerdictConsequences` (post-hoc), a VOID (no upload) records the verdict row only. Per-identity throttle: at most one spot-check per fingerprint per hour (in-memory map, clock-injected) so repeat winners aren't re-verified every round.

- [ ] **Step 1: Failing test** — drive the relay round-outcome path with a fake hook recorder: casual room + verifier available → top-1 selected with `spotCheck=true` AND the hook's `AttemptFinish` is the finisher's ORIGINAL message (assert `inputRecordingHashHex`/`ghostStreamHashHex`/`timeFrames`/`firstInputFrame`/`finishFrame` all equal what was submitted — a synthesized finish would make the verifier check fabricated data); verified room → NOT selected (already verified per-finish); no verifier available → nothing; same fingerprint twice within an hour → second skipped. Plus the routing-isolation case: complete a spot-check job with a FAIL verdict (and, separately, let one VOID on upload timeout) → the identity is sanctioned / the verdict row persisted, but the casual room's `standings()` are byte-identical before and after — spot-check consequences must NEVER route into `engine.onVerdict` (Task 7's `RoomRef.spotCheck` branch).

- [ ] **Step 2: Verify failure**, **Step 3: Implement**, **Step 4: Run** — `mvn "-Dtest=com.openggf.net.master.TestSpotCheck" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/ src/test/java/com/openggf/net/master/TestSpotCheck.java
git commit -m "feat(net): casual-room spot-checking + recording retention GC"
```

---

### Task 12: End-to-end, adversarial coverage, docs

**Files:**
- Create: `src/test/java/com/openggf/net/TestVerifiedRoomEndToEnd.java` (in-JVM master + fake worker, no engine/ROM)
- Modify: the phase-3 protocol fuzzer target list (add `MasterHttpRoutes` request fuzz: random method/path/header/body permutations must never crash the channel)
- Modify: `CHANGELOG.md`, `docs/status/known-discrepancies.md` n/a, `AGENTS.md`/`CLAUDE.md` n/a unless surfaces changed
- Test: full sweep

**Interfaces:** none new.

- [ ] **Step 1: Write the end-to-end test**

Scenario (all in one JVM, `MasterServer.start` with `plaintextForTest`, EmbeddedChannel or loopback per the phase-3 e2e idiom):
1. Register a verifier worker (real `PlayerIdentity`) for fingerprint `"0.6:cafe1234"`.
2. TRUSTED identity creates a verified relay room (assert browser `RoomSummary.verified == true`); NEW identity join → rejected.
3. Member finishes (`AttemptFinish` with recording hash H, stream hash G) → standings row `PENDING`; member receives `RecordingRequest(attemptId, H, url)`.
4. `PUT /recordings/H` with matching bytes → job leased by the worker; worker (fake `Replayer` returning a matching Result) posts signed `PASS` → row flips `VERIFIED`; `verdicts` table has the signed row.
5. Second finisher posts a claim whose fake replay mismatches time → `FAIL_TIME_MISMATCH` → row removed, identity SANCTIONED, verdict row persisted.
6. Third finisher never uploads → clock past `verifiedUploadDeadlineSeconds` (the SHORT verified-room window) → `VOID_NO_UPLOAD`, row removed, no sanction — and the removal happens via the upload void INSIDE the podium hold, not by the hold expiring.
7. Round end: podium (ROUND_END) held while a verdict was pending, released after; casual sibling room round → top time spot-check job created.

- [ ] **Step 2: Run** — `mvn "-Dtest=com.openggf.net.TestVerifiedRoomEndToEnd" test` → PASS (integration of green tasks; failures = wiring gaps).

- [ ] **Step 3: Fuzz + adversarial additions** — extend the phase-3 fuzz CI test with the HTTP route fuzzer (assert no crash/hang, bounded allocations); add adversarial cases to `GhostLoadTestTool` modes only if the tool gained a verified-room mode (optional, note-only otherwise).

- [ ] **Step 4: Full verification**

`mvn "-Dtest=com.openggf.net.**,com.openggf.game.timeattack.**,com.openggf.tools.verifier.**" test` → PASS; ROM-gated harness test with the S3K ROM property → PASS; must-keep-green S3K set → PASS; `mvn "-Dtest=com.openggf.tests.TestNetIsolationRules" test` → PASS.

- [ ] **Step 5: CHANGELOG + commit**

Add under Unreleased: `- Multiplayer time attack phase 5: openggf-verifier replay verification service, verified rooms (TRUSTED-gated, pending->verified standings), HTTPS recording uploads, signed verdicts with cheat sanctions, casual-room spot-checks.`

```bash
git add CHANGELOG.md src/test/java/com/openggf/net/TestVerifiedRoomEndToEnd.java <fuzzer file>
git commit -m "feat(net): verified-room end-to-end + adversarial coverage + changelog"
```
Trailers: `Changelog: updated`; `S3K-Known-Discrepancies: n/a`; others per staged files.

---

## Task dependency notes for parallel execution

- Task 1 blocks 5, 7, 10, 12. Task 2 blocks 3. Task 4 blocks 6, 7, 10, 11.
- Engine track (Tasks 8 → 9) is independent of the master track (Tasks 4 → 7) and can run fully in parallel; they meet only in Task 10 (worker uses the harness) and Task 12.
- Task 3 (client uploader) is independent of everything except Task 2.

## Deferred checklist (recorded so nothing silently drops)

- Community moderation workflows; ranked leaderboards; OAuth atop keypairs; Postgres `IdentityStore`; input-entropy/anomaly analytics over stored recordings (security spec §12).
- Verifier horizontal scaling / job priorities beyond FIFO-with-lease; verified-room support for player-hosted (direct) rooms — intentionally excluded, relay-only.
- `AttemptFinish.inputRecordingRef` stays null (recordings are pulled by hash on demand; the ref field remains reserved for a future push/CDN flow — no protocol break).

## Self-review notes (spec coverage)

- Security spec §6.1 separate service ✔ (Task 10, tools-side, master stays engine/ROM-free). §6.2 mechanics ✔ (input-only recording consumed as-is; fingerprint routing Task 4; spawn-anchored timing re-derived by `TimeAttackAttempt` in Task 9). §6.3 verified rooms flow ✔ (Task 7: pending→verified, podium waits, TRUSTED gate; casual spot-check Task 11 incl. post-hoc sanction). §6.4 custody/upload lifecycle ✔ (Tasks 2–3 client, Task 6 master, retention GC Task 11; deadline-miss voids + records Task 7). §6.5 coverage boundaries ✔ (ghost-hash cross-check in the worker decision table; pacing stays the phase-2 hub's job — unchanged here). §5 verdict signing boundary ✔ (VerdictCodec + worker keypair; VOID_NO_UPLOAD explicitly unsigned/master-attributed; moderation stays on the audit-logged admin path). §10 testing ✔ (legit-pass/doctored-fail in Task 9 + fake-worker decision table in Task 10 + e2e Task 12 + HTTP fuzz).
- Type-consistency check done: `attemptRef` format, `VerdictCodec` result strings, `verifyState` values, `FinishOutcome` 4-component shape, and the `Job` record are used identically across Tasks 1–12.
