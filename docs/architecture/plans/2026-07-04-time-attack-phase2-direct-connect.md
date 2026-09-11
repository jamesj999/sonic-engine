# Multiplayer Time Attack Phase 2 — Race Session + Direct Connect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Host/join a LAN-testable direct-connect time-attack room (≤ 8 players): live rounds with a timed window, unlimited instant retries, streamed non-colliding ghosts, standings — plus the full phase-2 security content (protocol hygiene, `GhostStreamValidator` with pacing, all reserved wire fields live).

**Architecture:** Four new engine-free network packages — `com.openggf.net.protocol` (versioned JSON control + binary ghost codecs), `com.openggf.net.hub` (`GhostHub`, `GhostStreamValidator`, `HostRoundEngine`, `RoomHost` — shared verbatim with the phase-3 master relay), `com.openggf.net.host` (Netty WebSocket server), `com.openggf.net.client` (`RaceClient` on the JDK WebSocket, `ClientRaceSession`, jitter-buffered `RemoteGhostPlayback`, `GhostStreamPublisher`) — plus engine glue in `com.openggf.game.timeattack.mp` (`MultiplayerRaceCoordinator`, `LiveLevelProfileFactory`, lobby/HUD UI) that rides the phase-1 `TimeAttackRuntime` and `GhostRenderer` unchanged except for three explicit seams. Each client simulates and times only its own player; the network carries only cosmetic ghost streams and reliable event messages (main spec §1).

**Specs:** `docs/architecture/designs/2026-07-04-multiplayer-time-attack-design.md` (§4–§10, §11 phase 2) and `docs/architecture/designs/2026-07-04-time-attack-security-design.md` (§3, §6.2, §7, §11 phase 2).

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), Jackson databind (already a dependency) for JSON control frames, Netty 4.1 (NEW dependency, host server only), JDK `java.net.http.WebSocket` client, JDK Ed25519 via phase-1 `PlayerIdentity`.

## Global Constraints

- Branch: `feature/multiplayer-time-attack`, based on **`next`** — NOT `develop`, never master (both set by the project owner; the name also overrides the repo's default `feature/ai-*` convention). Phase 1 is being implemented on this SAME branch in `.worktrees/time-attack-phase1` — phase 2 continues on it once the phase-1 tasks land there. Execute in an isolated worktree (superpowers:using-git-worktrees).
- JUnit 5 / Jupiter only — no `org.junit.*` (JUnit 4) imports.
- Every commit needs the trailer block (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`), each `updated` or `n/a`. A `feat:` commit touching `src/main/` must have `Changelog: updated` (staged CHANGELOG.md) or `Changelog: n/a: <reason>`. Task 1 adds the CHANGELOG entry; later commits use `Changelog: n/a: phase-2 entry added in c1 of this branch`.
- PowerShell: quote Maven props — `mvn "-Dtest=com.openggf.net.protocol.TestControlCodec" test`.
- Never `git add -A` (shared repo, concurrent sessions). Stage exact paths.
- **No ROM asset/content bytes ever cross the wire** — only positions, resolved render frames/flags, times, chat, checksums/fingerprints (main spec §7).
- **Engine-free fence (main spec §6.2):** `com.openggf.net.protocol`, `net.hub`, `net.host`, `net.client` must not import engine packages. Sole allowed `com.openggf` imports outside `net..`: `com.openggf.game.ghost.GhostFrame` and `GhostFrameCodec` (the canonical 7-byte codec shared by file and wire — both are pure, zero-import classes per the phase-1 plan). Task 16 freezes this with ArchUnit.
- **Threading rule:** all room/hub state is touched only on the room's single Netty event loop (host side) or the game thread (client side). The client network thread only enqueues into a concurrent queue drained once per frame (main spec §6.1).
- New config keys need a `ConfigCatalog` entry (`TestConfigCatalog` fails otherwise) and a CONFIGURATION.md row.
- Wire frame layout is FIXED by phase 1 (spec §7): 7 bytes = x u16 BE, y u16 BE, mappingFrame u8, flags u8 (bit0 hFlip, bit1 vFlip, bit2 finished), layer u8 (bits0-2 priorityBucket, bit3 highPriority, bits4-7 reserved zero). Never re-encode — reuse `GhostFrameCodec`.
- Trust model: v1 rooms are casual/unverified; `RoomDescriptor.verified` is always `false` in phase 2, but the field is live on the wire (security spec §11).
- TLS is NOT required in phase 2 — player-host direct connect may be plaintext `ws://` (security spec §7.3); `wss://` arrives with the master in phase 3.

## Phase-1 contract this plan consumes

These come from `docs/architecture/plans/2026-07-04-solo-ghost-racing-phase1.md` (the in-flight implementation's contract). Tasks 1–12 depend only on the starred pure classes; Tasks 13–15 touch phase-1 engine files and MUST reconcile against the as-built code first (read the file before editing — the worktree may have drifted in details while keeping these interfaces).

- ★ `com.openggf.game.ghost.GhostFrame` — record `(int x, int y, int mappingFrame, boolean hFlip, boolean vFlip, boolean finished, int priorityBucket, boolean highPriority)`.
- ★ `com.openggf.game.ghost.GhostFrameCodec` — `BYTES == 7`, `encode(GhostFrame, byte[], int)`, `decode(byte[], int)`.
- ★ `com.openggf.net.identity.PlayerIdentity` — `loadOrCreate(Path)`, `fingerprint()` (64-char hex), `sign(byte[])`, `static verify(byte[] pubKeyEncoded, byte[] msg, byte[] sig)`, `publicKeyEncoded()`.
- ★ `com.openggf.game.timeattack.DeterminismFingerprint` — `asString()` = `engineVersion + ":" + hex(romChecksum)`.
- `com.openggf.game.timeattack.TimeAttackRuntime` — `armForLaunch(TimeAttackLaunchRequest)`, `onLevelReady()`, `beforeLevelFrame(InputHandler)`, `afterLevelFrame()`, `requestRetry()`, `deactivate()`, `isAttemptRunning()`, `hudState()`; internally owns `TimeAttackAttempt`, `AttemptInputRecording`, ghost capture, `GhostStore` best-save.
- `com.openggf.game.timeattack.TimeAttackLaunchRequest` — record `(String gameId, int zone, int act, String character, List<Path> extraGhosts)`.
- `com.openggf.sprites.ghost.ActiveGhost` — record `(String slotId, String characterCode, GhostFrame frame)`; `GhostRenderer.renderForLayer(List<ActiveGhost>, int bucket, boolean highPriority, int playerCentreX, int playerCentreY)`.
- `com.openggf.game.ghost.GhostRenderRegistry` — gameplay-owned, hosted on `GameplayModeContext`.
- `com.openggf.game.timeattack.TimeAttackMenu` / `TimeAttackTrackCatalog` — master-title sub-mode + curated track list (`Track(String gameId, int zone, int act, String label, List<String> characters)`).
- `AttemptInputRecording.sha256()` — 32-byte hash carried in `AttemptFinish` and the `.ggfghost` header.

## What phase 2 explicitly does NOT build (deferred)

- Master server, server browser, lobby brokering, relay routing, `IdentityStore`/trust ladder, TLS, PoW — phase 3 (security spec §11). Direct-connect timeout falls back to a clean join failure, NOT relay (main spec §9).
- Roster channel, spatial bucketing, backpressure degradation ladder — phase 3 scale work. At ≤ 8 players everyone is "near" and relevance filtering is skipped (main spec §4.4). The `Roster` binary packet type id is reserved now (Task 2).
- Podium presentation, track vote UI, spectate — phase 4. `TrackVote`/`RecordingRequest` message types ship in the protocol now (security §11 "every protocol field exists by phase 2–3") but nothing sends them.
- Verifier service; `AttemptFinish.inputRecordingRef` is live on the wire but always `null`.

---

### Task 0: Branch setup

**Files:** none (git only)

- [ ] **Step 1: Check out the shared feature branch**

The branch already exists — phase 1 is implemented on it (worktree `.worktrees/time-attack-phase1`). Phase 2 CONTINUES on the same branch; do not create a new one, and do not rebase it yourself while the phase-1 worktree is active (shared-repo rule: never mutate a branch another session is working on).

```bash
git fetch origin
git checkout feature/multiplayer-time-attack && git pull --ff-only
git config core.hooksPath .githooks
```

Expected: on `feature/multiplayer-time-attack`, clean tree (`git status --porcelain` empty), and the phase-1 task commits visible in `git log --oneline -15`. The branch is based on `next` (project owner's direction — not `develop`); its merge target is likewise decided by the owner, not this plan. The branch name is the owner's choice — do not "correct" it to the `feature/ai-*` convention.

---

### Task 1: Control protocol — message model + envelope codec

**Files:**
- Create: `src/main/java/com/openggf/net/protocol/Protocol.java`
- Create: `src/main/java/com/openggf/net/protocol/ProtocolViolationException.java`
- Create: `src/main/java/com/openggf/net/protocol/ControlMessage.java`
- Create: `src/main/java/com/openggf/net/protocol/ControlCodec.java`
- Test: `src/test/java/com/openggf/net/protocol/TestControlCodec.java`
- Modify: `CHANGELOG.md` (Unreleased entry: "Multiplayer time attack (phase 2): direct-connect LAN rooms — host/join by address, live rounds with timed window and instant retries, streamed ghosts, standings, protocol hygiene and ghost-stream validation.")

**Interfaces:**
- Produces:
  - `Protocol`: `int VERSION = 1`; `int MAX_CONTROL_BYTES = 8192`; `int MAX_BINARY_BYTES = 4096`; `int MAX_CHAT_CHARS = 200`; `long CHAT_MIN_INTERVAL_MILLIS = 2000`; `int MAX_PLAYERS_DIRECT = 8` (main spec §2: player-hosted rooms cap at 8).
  - `ProtocolViolationException extends RuntimeException` — thrown by every decode path on malformed/oversized/unknown input; connection handlers catch it and disconnect (security spec §7.3: unknown/undecodable message → disconnect).
  - `ControlMessage` — sealed interface; all control messages are nested records (list below). Nested plain-data records (`RoomDescriptor`, `PlayerInfo`, `RoundConfig`, `RoundSnapshot`, `StandingsRow`) do NOT implement the interface.
  - `ControlCodec.encode(String tokenOrNull, ControlMessage msg)` → JSON text `{"v":1,"token":...,"msg":{"type":"Hello",...}}`; `ControlCodec.decode(String text)` → `DecodedControl(String token, ControlMessage message)`; rejects text > `MAX_CONTROL_BYTES`, wrong/missing `v`, missing/unknown `msg.type`, malformed JSON — all as `ProtocolViolationException`.
- Message set (main spec §7 + security spec §11 phase 2 — reserved fields LIVE from day one):
  - Handshake: `Hello(int protocolVersion, String pubKeyBase64, String displayName, String determinismFingerprint)`, `Welcome(int protocolVersion, String nonceBase64, String serverId)`, `AuthProof(String signatureBase64)`, `JoinAccepted(String sessionToken, int playerSlot, RoomDescriptor room, RoundSnapshot round)`, `JoinRejected(String reason)`, `Kick(String reason)`.
  - Room: `RoomState(List<PlayerInfo> players)`, `SelectCharacter(String character)`, `Chat(String text)`, `ChatBroadcast(int slot, String displayName, String text)`, `Ping(long t0ClientMillis)`, `Pong(long t0ClientMillis, long hubMillis)`.
  - Round: `RoundConfigure(RoundConfig config)` (host player → hub), `RoundStart(RoundConfig config, long countdownEndsAtHubMillis, long deadlineHubMillis)`, `RoundEnd(List<StandingsRow> finalStandings)`, `StandingsDelta(List<StandingsRow> rows)`.
  - Attempts: `AttemptStart(int attemptId)`, `AttemptFinish(int attemptId, int timeFrames, int firstInputFrame, int finishFrame, String inputRecordingHashHex, String ghostStreamHashHex, String inputRecordingRef)`, `AttemptReset(int attemptId)`.
  - Reserved (defined + round-trip tested, sent by nobody in phase 2): `TrackVote(String trackKey)`, `RecordingRequest(int attemptId, String expectedHashHex, String uploadUrl)`.
  - Data records: `RoomDescriptor(String name, String gameId, int zone, int act, String characterPolicy, String lockedCharacter, int maxPlayers, boolean verified)` (characterPolicy: `"LOCKED"` or `"OPEN"`); `PlayerInfo(int slot, String fingerprint, String displayName, String character)`; `RoundConfig(String gameId, int zone, int act, int windowSeconds, String characterPolicy, String lockedCharacter)`; `RoundSnapshot(String phase, RoundConfig config, long countdownEndsAtHubMillis, long deadlineHubMillis, List<StandingsRow> standings)`; `StandingsRow(int slot, String displayName, String character, int bestTimeFrames, int rank)`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestControlCodec {
    @Test
    void roundTripsHelloWithToken() {
        ControlMessage.Hello hello = new ControlMessage.Hello(Protocol.VERSION, "cHVia2V5", "Farrell", "0.6:cafe1234");
        String wire = ControlCodec.encode("tok123", hello);
        ControlCodec.DecodedControl back = ControlCodec.decode(wire);
        assertEquals("tok123", back.token());
        assertEquals(hello, back.message());
    }

    @Test
    void roundTripsNullTokenAndNestedRecords() {
        ControlMessage.JoinAccepted accepted = new ControlMessage.JoinAccepted(
                "tok", 2,
                new ControlMessage.RoomDescriptor("LAN Room", "s3k", 0, 0, "OPEN", null, 8, false),
                new ControlMessage.RoundSnapshot("LOBBY", null, 0L, 0L, List.of()));
        ControlCodec.DecodedControl back = ControlCodec.decode(ControlCodec.encode(null, accepted));
        assertNull(back.token());
        assertEquals(accepted, back.message());
        assertFalse(((ControlMessage.JoinAccepted) back.message()).room().verified()); // v1: always false, field live
    }

    @Test
    void roundTripsEveryMessageType() {
        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 300, "LOCKED", "sonic");
        List<ControlMessage.StandingsRow> rows =
                List.of(new ControlMessage.StandingsRow(0, "A", "sonic", 3600, 1));
        List<ControlMessage> all = List.of(
                new ControlMessage.Hello(1, "a", "b", "c"),
                new ControlMessage.Welcome(1, "bm9uY2U=", "serverfp"),
                new ControlMessage.AuthProof("c2ln"),
                new ControlMessage.JoinRejected("room full"),
                new ControlMessage.Kick("protocol violation"),
                new ControlMessage.RoomState(List.of(new ControlMessage.PlayerInfo(0, "fp", "A", "sonic"))),
                new ControlMessage.SelectCharacter("tails"),
                new ControlMessage.Chat("hi"),
                new ControlMessage.ChatBroadcast(0, "A", "hi"),
                new ControlMessage.Ping(12345L),
                new ControlMessage.Pong(12345L, 99999L),
                new ControlMessage.RoundConfigure(cfg),
                new ControlMessage.RoundStart(cfg, 1000L, 301000L),
                new ControlMessage.RoundEnd(rows),
                new ControlMessage.StandingsDelta(rows),
                new ControlMessage.AttemptStart(1),
                new ControlMessage.AttemptFinish(1, 3600, 12, 3612, "ab".repeat(32), "cd".repeat(32), null),
                new ControlMessage.AttemptReset(1),
                new ControlMessage.TrackVote("s3k:0:0"),
                new ControlMessage.RecordingRequest(1, "ab".repeat(32), null));
        for (ControlMessage msg : all) {
            assertEquals(msg, ControlCodec.decode(ControlCodec.encode("t", msg)).message(),
                    "round-trip failed for " + msg.getClass().getSimpleName());
        }
    }

    @Test
    void rejectsOversizedText() {
        String big = "{\"v\":1,\"msg\":{\"type\":\"Chat\",\"text\":\"" + "x".repeat(Protocol.MAX_CONTROL_BYTES) + "\"}}";
        assertThrows(ProtocolViolationException.class, () -> ControlCodec.decode(big));
    }

    @Test
    void rejectsUnknownTypeWrongVersionAndGarbage() {
        assertThrows(ProtocolViolationException.class,
                () -> ControlCodec.decode("{\"v\":1,\"msg\":{\"type\":\"Nope\"}}"));
        assertThrows(ProtocolViolationException.class,
                () -> ControlCodec.decode("{\"v\":2,\"msg\":{\"type\":\"Ping\",\"t0ClientMillis\":1}}"));
        assertThrows(ProtocolViolationException.class, () -> ControlCodec.decode("{\"v\":1}"));
        assertThrows(ProtocolViolationException.class, () -> ControlCodec.decode("not json at all"));
    }

    @Test
    void ignoresUnknownFieldsForForwardCompat() {
        // A phase-3 client may add fields; phase-2 peers must not explode on them.
        ControlCodec.DecodedControl back = ControlCodec.decode(
                "{\"v\":1,\"token\":null,\"msg\":{\"type\":\"Ping\",\"t0ClientMillis\":7,\"futureField\":true}}");
        assertEquals(new ControlMessage.Ping(7L), back.message());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.protocol.TestControlCodec" test`
Expected: COMPILATION ERROR (classes not found).

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.protocol;

/** Wire-protocol constants (main spec §7, security spec §7.3). */
public final class Protocol {
    public static final int VERSION = 1;
    /** Hard pre-parse cap on a control (text) frame. */
    public static final int MAX_CONTROL_BYTES = 8192;
    /** Hard pre-parse cap on a binary (ghost) frame. */
    public static final int MAX_BINARY_BYTES = 4096;
    public static final int MAX_CHAT_CHARS = 200;
    /** Server-side chat rate limit: 1 msg / 2 s / player (main spec §6.2). */
    public static final long CHAT_MIN_INTERVAL_MILLIS = 2000;
    /** Player-hosted direct-connect rooms are capped at 8 (main spec §2/§4.4). */
    public static final int MAX_PLAYERS_DIRECT = 8;

    private Protocol() {
    }
}
```

```java
package com.openggf.net.protocol;

/** Malformed/oversized/unknown wire input. Handlers catch this and disconnect (security spec §7.3). */
public class ProtocolViolationException extends RuntimeException {
    public ProtocolViolationException(String message) {
        super(message);
    }

    public ProtocolViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.openggf.net.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * All control-channel messages (main spec §7). JSON text frames, discriminated by "type".
 * Security-reserved fields (token, hashes, verified flag, inputRecordingRef, TrackVote,
 * RecordingRequest) are live on the wire from phase 2 (security spec §11) even where unused.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ControlMessage.Hello.class, name = "Hello"),
        @JsonSubTypes.Type(value = ControlMessage.Welcome.class, name = "Welcome"),
        @JsonSubTypes.Type(value = ControlMessage.AuthProof.class, name = "AuthProof"),
        @JsonSubTypes.Type(value = ControlMessage.JoinAccepted.class, name = "JoinAccepted"),
        @JsonSubTypes.Type(value = ControlMessage.JoinRejected.class, name = "JoinRejected"),
        @JsonSubTypes.Type(value = ControlMessage.Kick.class, name = "Kick"),
        @JsonSubTypes.Type(value = ControlMessage.RoomState.class, name = "RoomState"),
        @JsonSubTypes.Type(value = ControlMessage.SelectCharacter.class, name = "SelectCharacter"),
        @JsonSubTypes.Type(value = ControlMessage.Chat.class, name = "Chat"),
        @JsonSubTypes.Type(value = ControlMessage.ChatBroadcast.class, name = "ChatBroadcast"),
        @JsonSubTypes.Type(value = ControlMessage.Ping.class, name = "Ping"),
        @JsonSubTypes.Type(value = ControlMessage.Pong.class, name = "Pong"),
        @JsonSubTypes.Type(value = ControlMessage.RoundConfigure.class, name = "RoundConfigure"),
        @JsonSubTypes.Type(value = ControlMessage.RoundStart.class, name = "RoundStart"),
        @JsonSubTypes.Type(value = ControlMessage.RoundEnd.class, name = "RoundEnd"),
        @JsonSubTypes.Type(value = ControlMessage.StandingsDelta.class, name = "StandingsDelta"),
        @JsonSubTypes.Type(value = ControlMessage.AttemptStart.class, name = "AttemptStart"),
        @JsonSubTypes.Type(value = ControlMessage.AttemptFinish.class, name = "AttemptFinish"),
        @JsonSubTypes.Type(value = ControlMessage.AttemptReset.class, name = "AttemptReset"),
        @JsonSubTypes.Type(value = ControlMessage.TrackVote.class, name = "TrackVote"),
        @JsonSubTypes.Type(value = ControlMessage.RecordingRequest.class, name = "RecordingRequest"),
})
public sealed interface ControlMessage {

    // --- nested plain-data records (not messages) ---

    record RoomDescriptor(String name, String gameId, int zone, int act, String characterPolicy,
                          String lockedCharacter, int maxPlayers, boolean verified) {
    }

    record PlayerInfo(int slot, String fingerprint, String displayName, String character) {
    }

    record RoundConfig(String gameId, int zone, int act, int windowSeconds, String characterPolicy,
                       String lockedCharacter) {
    }

    record RoundSnapshot(String phase, RoundConfig config, long countdownEndsAtHubMillis,
                         long deadlineHubMillis, List<StandingsRow> standings) {
    }

    record StandingsRow(int slot, String displayName, String character, int bestTimeFrames, int rank) {
    }

    // --- handshake (security spec §3: nonce + serverId binding) ---

    record Hello(int protocolVersion, String pubKeyBase64, String displayName,
                 String determinismFingerprint) implements ControlMessage {
    }

    record Welcome(int protocolVersion, String nonceBase64, String serverId) implements ControlMessage {
    }

    record AuthProof(String signatureBase64) implements ControlMessage {
    }

    record JoinAccepted(String sessionToken, int playerSlot, RoomDescriptor room,
                        RoundSnapshot round) implements ControlMessage {
    }

    record JoinRejected(String reason) implements ControlMessage {
    }

    record Kick(String reason) implements ControlMessage {
    }

    // --- room ---

    record RoomState(List<PlayerInfo> players) implements ControlMessage {
    }

    record SelectCharacter(String character) implements ControlMessage {
    }

    record Chat(String text) implements ControlMessage {
    }

    record ChatBroadcast(int slot, String displayName, String text) implements ControlMessage {
    }

    record Ping(long t0ClientMillis) implements ControlMessage {
    }

    record Pong(long t0ClientMillis, long hubMillis) implements ControlMessage {
    }

    // --- rounds ---

    record RoundConfigure(RoundConfig config) implements ControlMessage {
    }

    record RoundStart(RoundConfig config, long countdownEndsAtHubMillis,
                      long deadlineHubMillis) implements ControlMessage {
    }

    record RoundEnd(List<StandingsRow> finalStandings) implements ControlMessage {
    }

    record StandingsDelta(List<StandingsRow> rows) implements ControlMessage {
    }

    // --- attempts (times are client-reported and unverified in v1 — main spec §2) ---

    record AttemptStart(int attemptId) implements ControlMessage {
    }

    record AttemptFinish(int attemptId, int timeFrames, int firstInputFrame, int finishFrame,
                         String inputRecordingHashHex, String ghostStreamHashHex,
                         String inputRecordingRef) implements ControlMessage {
    }

    record AttemptReset(int attemptId) implements ControlMessage {
    }

    // --- reserved for later phases (defined so no protocol break is ever needed) ---

    record TrackVote(String trackKey) implements ControlMessage {
    }

    record RecordingRequest(int attemptId, String expectedHashHex, String uploadUrl) implements ControlMessage {
    }
}
```

```java
package com.openggf.net.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/** Envelope codec for control messages: {"v":1,"token":...,"msg":{...}} (main spec §7). */
public final class ControlCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record DecodedControl(String token, ControlMessage message) {
    }

    private ControlCodec() {
    }

    public static String encode(String tokenOrNull, ControlMessage message) {
        try {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("v", Protocol.VERSION);
            if (tokenOrNull == null) {
                envelope.putNull("token");
            } else {
                envelope.put("token", tokenOrNull);
            }
            envelope.set("msg", MAPPER.valueToTree(message));
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new ProtocolViolationException("failed to encode " + message.getClass().getSimpleName(), e);
        }
    }

    public static DecodedControl decode(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_CONTROL_BYTES) {
            throw new ProtocolViolationException("control frame exceeds " + Protocol.MAX_CONTROL_BYTES + " bytes");
        }
        try {
            JsonNode root = MAPPER.readTree(text);
            JsonNode version = root.get("v");
            if (version == null || version.asInt(-1) != Protocol.VERSION) {
                throw new ProtocolViolationException("unsupported protocol version " + version);
            }
            JsonNode msg = root.get("msg");
            if (msg == null || !msg.isObject()) {
                throw new ProtocolViolationException("missing msg body");
            }
            JsonNode token = root.get("token");
            String tokenValue = (token == null || token.isNull()) ? null : token.asText();
            return new DecodedControl(tokenValue, MAPPER.treeToValue(msg, ControlMessage.class));
        } catch (ProtocolViolationException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolViolationException("undecodable control frame", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.protocol.TestControlCodec" test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/protocol/Protocol.java src/main/java/com/openggf/net/protocol/ProtocolViolationException.java src/main/java/com/openggf/net/protocol/ControlMessage.java src/main/java/com/openggf/net/protocol/ControlCodec.java src/test/java/com/openggf/net/protocol/TestControlCodec.java CHANGELOG.md
git commit -m "feat(timeattack): phase-2 control protocol messages and envelope codec

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: Binary ghost packet codecs

**Files:**
- Create: `src/main/java/com/openggf/net/protocol/GhostPackets.java`
- Test: `src/test/java/com/openggf/net/protocol/TestGhostPackets.java`

**Interfaces:**
- Consumes: `GhostFrameCodec.BYTES` (phase 1), `Protocol`, `ProtocolViolationException`.
- Produces (all big-endian; first byte is the packet type):
  - Type ids: `GhostPackets.TYPE_GHOST_FRAMES = 0x01`, `TYPE_GHOST_AGGREGATE = 0x02`, `TYPE_ROSTER_RESERVED = 0x03` (phase 3 — reserved, never emitted).
  - `GhostFrames` layout (client → hub, main spec §7): `u8 type, u32 attemptId, u32 startFrameIndex, u8 frameCount (1..MAX_UPSTREAM_FRAMES_PER_PACKET=3), frameCount×7 frame bytes`.
  - `GhostAggregate` layout (hub → client): `u8 type, u32 hubTick, u8 entryCount (0..255 decode cap), then per entry: u8 playerSlot, u32 attemptId, u32 startFrameIndex, u8 frameCount (1..MAX_AGGREGATE_FRAMES_PER_ENTRY=30), frameCount×7 frame bytes`.
  - Records: `FramesBatch(int attemptId, int startFrameIndex, int frameCount, byte[] frameData)`; `AggregateEntry(int playerSlot, int attemptId, int startFrameIndex, int frameCount, byte[] frameData)`; `Aggregate(int hubTick, List<AggregateEntry> entries)`.
  - Methods: `byte[] encodeFrames(int attemptId, int startFrameIndex, byte[] frameData)` (frameData length = n×7, n in 1..3); `FramesBatch decodeFrames(byte[] packet)`; `byte[] encodeAggregate(int hubTick, List<AggregateEntry> entries)`; `Aggregate decodeAggregate(byte[] packet)`.
  - Hardening (security spec §7.3): every decode validates type byte, exact packet length (no trailing bytes), counts in range, total size ≤ `Protocol.MAX_BINARY_BYTES` — `ProtocolViolationException` otherwise. No allocation from an unvalidated count.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.protocol;

import com.openggf.game.ghost.GhostFrameCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostPackets {
    private static byte[] frames(int n) {
        byte[] data = new byte[n * GhostFrameCodec.BYTES];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        return data;
    }

    @Test
    void roundTripsFramesBatch() {
        byte[] packet = GhostPackets.encodeFrames(7, 300, frames(3));
        assertEquals(0x01, packet[0]);
        GhostPackets.FramesBatch back = GhostPackets.decodeFrames(packet);
        assertEquals(7, back.attemptId());
        assertEquals(300, back.startFrameIndex());
        assertEquals(3, back.frameCount());
        assertArrayEquals(frames(3), back.frameData());
    }

    @Test
    void roundTripsAggregate() {
        List<GhostPackets.AggregateEntry> entries = List.of(
                new GhostPackets.AggregateEntry(0, 7, 300, 3, frames(3)),
                new GhostPackets.AggregateEntry(5, 2, 0, 1, frames(1)));
        byte[] packet = GhostPackets.encodeAggregate(1234, entries);
        assertEquals(0x02, packet[0]);
        GhostPackets.Aggregate back = GhostPackets.decodeAggregate(packet);
        assertEquals(1234, back.hubTick());
        assertEquals(2, back.entries().size());
        assertEquals(5, back.entries().get(1).playerSlot());
        assertArrayEquals(frames(1), back.entries().get(1).frameData());
    }

    @Test
    void emptyAggregateIsLegal() {
        GhostPackets.Aggregate back = GhostPackets.decodeAggregate(GhostPackets.encodeAggregate(1, List.of()));
        assertTrue(back.entries().isEmpty());
    }

    @Test
    void encodeFramesRejectsBadSizes() {
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeFrames(1, 0, new byte[0]));
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeFrames(1, 0, new byte[8])); // not ×7
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeFrames(1, 0, frames(4)));   // > 3
    }

    @Test
    void decodeRejectsHostileInput() {
        byte[] good = GhostPackets.encodeFrames(7, 300, frames(3));
        byte[] wrongType = good.clone();
        wrongType[0] = 0x02;
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeFrames(wrongType));

        byte[] hostileCount = good.clone();
        hostileCount[9] = (byte) 200; // claims 200 frames in a 31-byte packet
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeFrames(hostileCount));

        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 3);
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeFrames(truncated));

        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 2);
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeFrames(trailing));

        assertThrows(ProtocolViolationException.class,
                () -> GhostPackets.decodeAggregate(new byte[Protocol.MAX_BINARY_BYTES + 1]));
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeAggregate(new byte[0]));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.protocol.TestGhostPackets" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.protocol;

import com.openggf.game.ghost.GhostFrameCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary ghost packets (main spec §7). Frame bytes are the canonical 7-byte
 * GhostFrameCodec encoding — byte-identical to a .ggfghost file body.
 */
public final class GhostPackets {
    public static final int TYPE_GHOST_FRAMES = 0x01;
    public static final int TYPE_GHOST_AGGREGATE = 0x02;
    /** Phase-3 roster channel (main spec §4.3) — type id reserved now, never emitted in phase 2. */
    public static final int TYPE_ROSTER_RESERVED = 0x03;

    /** Upstream batching: 3 sampled frames per packet at 20 pkt/s (main spec §4.4). */
    public static final int MAX_UPSTREAM_FRAMES_PER_PACKET = 3;
    /** Per-ghost frame cap inside one aggregate tick (normal is 3; allows catch-up flushes). */
    public static final int MAX_AGGREGATE_FRAMES_PER_ENTRY = 30;

    private static final int FRAME_BYTES = GhostFrameCodec.BYTES;
    private static final int FRAMES_HEADER = 1 + 4 + 4 + 1;
    private static final int ENTRY_HEADER = 1 + 4 + 4 + 1;
    private static final int AGGREGATE_HEADER = 1 + 4 + 1;

    public record FramesBatch(int attemptId, int startFrameIndex, int frameCount, byte[] frameData) {
    }

    public record AggregateEntry(int playerSlot, int attemptId, int startFrameIndex, int frameCount,
                                 byte[] frameData) {
    }

    public record Aggregate(int hubTick, List<AggregateEntry> entries) {
    }

    private GhostPackets() {
    }

    public static byte[] encodeFrames(int attemptId, int startFrameIndex, byte[] frameData) {
        int count = validFrameCount(frameData, MAX_UPSTREAM_FRAMES_PER_PACKET);
        ByteBuffer out = ByteBuffer.allocate(FRAMES_HEADER + frameData.length);
        out.put((byte) TYPE_GHOST_FRAMES).putInt(attemptId).putInt(startFrameIndex)
                .put((byte) count).put(frameData);
        return out.array();
    }

    public static FramesBatch decodeFrames(byte[] packet) {
        ByteBuffer in = checked(packet, TYPE_GHOST_FRAMES, FRAMES_HEADER);
        int attemptId = in.getInt();
        int startFrameIndex = in.getInt();
        int count = in.get() & 0xFF;
        if (count < 1 || count > MAX_UPSTREAM_FRAMES_PER_PACKET) {
            throw new ProtocolViolationException("frames batch count " + count);
        }
        byte[] frameData = takeExactly(in, count);
        if (in.hasRemaining()) {
            throw new ProtocolViolationException("frames packet has trailing bytes");
        }
        return new FramesBatch(attemptId, startFrameIndex, count, frameData);
    }

    public static byte[] encodeAggregate(int hubTick, List<AggregateEntry> entries) {
        int size = AGGREGATE_HEADER;
        for (AggregateEntry entry : entries) {
            validFrameCount(entry.frameData(), MAX_AGGREGATE_FRAMES_PER_ENTRY);
            size += ENTRY_HEADER + entry.frameData().length;
        }
        if (entries.size() > 255 || size > Protocol.MAX_BINARY_BYTES) {
            throw new ProtocolViolationException("aggregate too large: " + entries.size()
                    + " entries, " + size + " bytes");
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        out.put((byte) TYPE_GHOST_AGGREGATE).putInt(hubTick).put((byte) entries.size());
        for (AggregateEntry entry : entries) {
            out.put((byte) entry.playerSlot()).putInt(entry.attemptId()).putInt(entry.startFrameIndex())
                    .put((byte) entry.frameCount()).put(entry.frameData());
        }
        return out.array();
    }

    public static Aggregate decodeAggregate(byte[] packet) {
        ByteBuffer in = checked(packet, TYPE_GHOST_AGGREGATE, AGGREGATE_HEADER);
        int hubTick = in.getInt();
        int entryCount = in.get() & 0xFF;
        List<AggregateEntry> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            if (in.remaining() < ENTRY_HEADER) {
                throw new ProtocolViolationException("aggregate truncated at entry " + i);
            }
            int slot = in.get() & 0xFF;
            int attemptId = in.getInt();
            int startFrameIndex = in.getInt();
            int count = in.get() & 0xFF;
            if (count < 1 || count > MAX_AGGREGATE_FRAMES_PER_ENTRY) {
                throw new ProtocolViolationException("aggregate entry count " + count);
            }
            entries.add(new AggregateEntry(slot, attemptId, startFrameIndex, count, takeExactly(in, count)));
        }
        if (in.hasRemaining()) {
            throw new ProtocolViolationException("aggregate has trailing bytes");
        }
        return new Aggregate(hubTick, entries);
    }

    private static int validFrameCount(byte[] frameData, int max) {
        if (frameData.length < FRAME_BYTES || frameData.length % FRAME_BYTES != 0
                || frameData.length / FRAME_BYTES > max) {
            throw new ProtocolViolationException("frame data length " + frameData.length);
        }
        return frameData.length / FRAME_BYTES;
    }

    private static ByteBuffer checked(byte[] packet, int expectedType, int minLength) {
        if (packet.length < minLength || packet.length > Protocol.MAX_BINARY_BYTES) {
            throw new ProtocolViolationException("binary packet length " + packet.length);
        }
        if ((packet[0] & 0xFF) != expectedType) {
            throw new ProtocolViolationException("unexpected packet type " + (packet[0] & 0xFF));
        }
        ByteBuffer in = ByteBuffer.wrap(packet);
        in.get(); // skip type byte
        return in;
    }

    private static byte[] takeExactly(ByteBuffer in, int frameCount) {
        int byteCount = frameCount * FRAME_BYTES;
        if (in.remaining() < byteCount) {
            throw new ProtocolViolationException("packet truncated: need " + byteCount + " frame bytes");
        }
        byte[] frameData = new byte[byteCount];
        in.get(frameData);
        return frameData;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.protocol.TestGhostPackets" test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/protocol/GhostPackets.java src/test/java/com/openggf/net/protocol/TestGhostPackets.java
git commit -m "feat(timeattack): binary GhostFrames/GhostAggregate packet codecs

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: Identity handshake + session tokens

**Files:**
- Create: `src/main/java/com/openggf/net/hub/SessionTokenIssuer.java`
- Create: `src/main/java/com/openggf/net/hub/HostHandshake.java`
- Create: `src/main/java/com/openggf/net/client/ClientHandshake.java`
- Test: `src/test/java/com/openggf/net/hub/TestHandshake.java`

**Interfaces:**
- Consumes: `PlayerIdentity` (phase 1), `ControlMessage.{Hello,Welcome,AuthProof}`, `Protocol.VERSION`.
- Produces:
  - `SessionTokenIssuer`: `String issue()` (16 random bytes, lowercase hex, `SecureRandom`); `boolean isValid(String token)`; `void revoke(String token)`. Phase-2 tokens are opaque room-scoped random values — cheap membership validation only; identity-bound semantics arrive in phase 3 (security spec §7.3).
  - `HostHandshake(String serverId, String requiredDeterminismFingerprint)` — per-connection state machine. `Step onHello(ControlMessage.Hello hello)`; `Step onAuthProof(ControlMessage.AuthProof proof)`. `Step` is a sealed interface with records `SendWelcome(ControlMessage.Welcome welcome)`, `Reject(String reason)`, `Admit(String fingerprint, String displayName, byte[] publicKeyEncoded)`. Rejections: version mismatch, determinism fingerprint mismatch, invalid signature, out-of-order messages. Signature message bytes = `nonce ‖ serverId(UTF-8)` (security spec §3 — binding to serverId prevents cross-server replay). Fingerprint computed host-side as lowercase-hex SHA-256 of the presented public key (never trust a client-claimed fingerprint).
  - `ClientHandshake(PlayerIdentity identity, String displayName, String determinismFingerprint)`: `ControlMessage.Hello hello()`; `ControlMessage.AuthProof onWelcome(ControlMessage.Welcome welcome)` (throws `GeneralSecurityException`); `String serverId()` (available after `onWelcome`).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.hub;

import com.openggf.net.client.ClientHandshake;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestHandshake {
    private static final String FP = "0.6:cafe1234";

    @Test
    void happyPathAdmitsClient(@TempDir Path clientDir, @TempDir Path hostDir) throws Exception {
        PlayerIdentity client = PlayerIdentity.loadOrCreate(clientDir);
        PlayerIdentity host = PlayerIdentity.loadOrCreate(hostDir);
        ClientHandshake clientSide = new ClientHandshake(client, "Farrell", FP);
        HostHandshake hostSide = new HostHandshake(host.fingerprint(), FP);

        HostHandshake.Step step1 = hostSide.onHello(clientSide.hello());
        ControlMessage.Welcome welcome = ((HostHandshake.SendWelcome) step1).welcome();
        assertEquals(host.fingerprint(), welcome.serverId());

        HostHandshake.Step step2 = hostSide.onAuthProof(clientSide.onWelcome(welcome));
        HostHandshake.Admit admit = assertInstanceOf(HostHandshake.Admit.class, step2);
        assertEquals(client.fingerprint(), admit.fingerprint()); // derived from pubkey, not claimed
        assertEquals("Farrell", admit.displayName());
    }

    @Test
    void rejectsVersionAndFingerprintMismatch(@TempDir Path clientDir) throws Exception {
        PlayerIdentity client = PlayerIdentity.loadOrCreate(clientDir);
        ClientHandshake clientSide = new ClientHandshake(client, "x", FP);
        ControlMessage.Hello hello = clientSide.hello();

        HostHandshake wrongVersion = new HostHandshake("srv", FP);
        HostHandshake.Step rejected = wrongVersion.onHello(new ControlMessage.Hello(
                Protocol.VERSION + 1, hello.pubKeyBase64(), "x", FP));
        assertInstanceOf(HostHandshake.Reject.class, rejected);

        HostHandshake wrongRom = new HostHandshake("srv", "0.6:deadbeef");
        assertInstanceOf(HostHandshake.Reject.class, wrongRom.onHello(hello));
    }

    @Test
    void signatureBoundToServerIdCannotBeReplayedElsewhere(@TempDir Path clientDir, @TempDir Path hostDir)
            throws Exception {
        PlayerIdentity client = PlayerIdentity.loadOrCreate(clientDir);
        PlayerIdentity host = PlayerIdentity.loadOrCreate(hostDir);
        ClientHandshake clientSide = new ClientHandshake(client, "x", FP);

        HostHandshake serverA = new HostHandshake(host.fingerprint(), FP);
        ControlMessage.Welcome welcomeA = ((HostHandshake.SendWelcome) serverA.onHello(clientSide.hello())).welcome();
        ControlMessage.AuthProof proofForA = clientSide.onWelcome(welcomeA);

        // A different server identity presenting ANY nonce: A's proof must not admit there.
        HostHandshake serverB = new HostHandshake("differentserverfingerprint", FP);
        serverB.onHello(new ClientHandshake(client, "x", FP).hello());
        assertInstanceOf(HostHandshake.Reject.class, serverB.onAuthProof(proofForA));
    }

    @Test
    void outOfOrderMessagesReject(@TempDir Path hostDir) throws Exception {
        PlayerIdentity host = PlayerIdentity.loadOrCreate(hostDir);
        HostHandshake hostSide = new HostHandshake(host.fingerprint(), FP);
        assertInstanceOf(HostHandshake.Reject.class,
                hostSide.onAuthProof(new ControlMessage.AuthProof("c2ln")));
    }

    @Test
    void tokensIssueValidateAndRevoke() {
        SessionTokenIssuer issuer = new SessionTokenIssuer();
        String token = issuer.issue();
        assertEquals(32, token.length());
        assertTrue(issuer.isValid(token));
        assertFalse(issuer.isValid("deadbeef"));
        assertFalse(issuer.isValid(null));
        issuer.revoke(token);
        assertFalse(issuer.isValid(token));
        assertNotEquals(issuer.issue(), issuer.issue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestHandshake" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.hub;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque room-scoped session tokens (security spec §7.3). Phase 2: membership
 * validation only; identity-bound semantics arrive with the master in phase 3.
 */
public final class SessionTokenIssuer {
    private final SecureRandom random = new SecureRandom();
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public String issue() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        active.add(token);
        return token;
    }

    public boolean isValid(String token) {
        return token != null && active.contains(token);
    }

    public void revoke(String token) {
        if (token != null) {
            active.remove(token);
        }
    }
}
```

```java
package com.openggf.net.hub;

import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.Protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Host-side handshake state machine (security spec §3): version gate, determinism
 * fingerprint gate, then nonce challenge signed over nonce ‖ serverId.
 */
public final class HostHandshake {
    public sealed interface Step {
    }

    public record SendWelcome(ControlMessage.Welcome welcome) implements Step {
    }

    public record Reject(String reason) implements Step {
    }

    public record Admit(String fingerprint, String displayName, byte[] publicKeyEncoded) implements Step {
    }

    private enum State { EXPECT_HELLO, EXPECT_PROOF, DONE }

    private final String serverId;
    private final String requiredDeterminismFingerprint;
    private final SecureRandom random = new SecureRandom();

    private State state = State.EXPECT_HELLO;
    private byte[] nonce;
    private byte[] publicKeyEncoded;
    private String displayName;

    public HostHandshake(String serverId, String requiredDeterminismFingerprint) {
        this.serverId = serverId;
        this.requiredDeterminismFingerprint = requiredDeterminismFingerprint;
    }

    public Step onHello(ControlMessage.Hello hello) {
        if (state != State.EXPECT_HELLO) {
            return reject("handshake out of order");
        }
        if (hello.protocolVersion() != Protocol.VERSION) {
            return reject("protocol version mismatch");
        }
        if (!requiredDeterminismFingerprint.equals(hello.determinismFingerprint())) {
            return reject("determinism fingerprint mismatch (different game build or ROM)");
        }
        try {
            publicKeyEncoded = Base64.getDecoder().decode(hello.pubKeyBase64());
        } catch (IllegalArgumentException | NullPointerException e) {
            return reject("invalid public key");
        }
        displayName = hello.displayName() == null ? "" : hello.displayName();
        nonce = new byte[32];
        random.nextBytes(nonce);
        state = State.EXPECT_PROOF;
        return new SendWelcome(new ControlMessage.Welcome(Protocol.VERSION,
                Base64.getEncoder().encodeToString(nonce), serverId));
    }

    public Step onAuthProof(ControlMessage.AuthProof proof) {
        if (state != State.EXPECT_PROOF) {
            return reject("handshake out of order");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(proof.signatureBase64());
        } catch (IllegalArgumentException | NullPointerException e) {
            return reject("invalid signature encoding");
        }
        if (!PlayerIdentity.verify(publicKeyEncoded, signedBytes(nonce, serverId), signature)) {
            return reject("invalid signature");
        }
        state = State.DONE;
        return new Admit(sha256Hex(publicKeyEncoded), displayName, publicKeyEncoded.clone());
    }

    /** Shared with ClientHandshake: the exact bytes the client signs. */
    public static byte[] signedBytes(byte[] nonce, String serverId) {
        byte[] serverBytes = serverId.getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[nonce.length + serverBytes.length];
        System.arraycopy(nonce, 0, message, 0, nonce.length);
        System.arraycopy(serverBytes, 0, message, nonce.length, serverBytes.length);
        return message;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Step reject(String reason) {
        state = State.DONE;
        return new Reject(reason);
    }
}
```

```java
package com.openggf.net.client;

import com.openggf.net.hub.HostHandshake;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.Protocol;

import java.security.GeneralSecurityException;
import java.util.Base64;

/** Client-side handshake: send Hello, sign the Welcome nonce bound to serverId (security spec §3). */
public final class ClientHandshake {
    private final PlayerIdentity identity;
    private final String displayName;
    private final String determinismFingerprint;
    private String serverId;

    public ClientHandshake(PlayerIdentity identity, String displayName, String determinismFingerprint) {
        this.identity = identity;
        this.displayName = displayName;
        this.determinismFingerprint = determinismFingerprint;
    }

    public ControlMessage.Hello hello() {
        return new ControlMessage.Hello(Protocol.VERSION,
                Base64.getEncoder().encodeToString(identity.publicKeyEncoded()),
                displayName, determinismFingerprint);
    }

    public ControlMessage.AuthProof onWelcome(ControlMessage.Welcome welcome) throws GeneralSecurityException {
        serverId = welcome.serverId();
        byte[] nonce = Base64.getDecoder().decode(welcome.nonceBase64());
        byte[] signature = identity.sign(HostHandshake.signedBytes(nonce, serverId));
        return new ControlMessage.AuthProof(Base64.getEncoder().encodeToString(signature));
    }

    public String serverId() {
        return serverId;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.hub.TestHandshake" test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/SessionTokenIssuer.java src/main/java/com/openggf/net/hub/HostHandshake.java src/main/java/com/openggf/net/client/ClientHandshake.java src/test/java/com/openggf/net/hub/TestHandshake.java
git commit -m "feat(timeattack): identity handshake with serverId-bound nonce and session tokens

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 4: TrackValidationProfile + GhostStreamValidator (bounds, speed, rate, pacing, ladder)

**Files:**
- Create: `src/main/java/com/openggf/net/hub/TrackValidationProfile.java`
- Create: `src/main/java/com/openggf/net/hub/TrackValidationProfileSource.java`
- Create: `src/main/java/com/openggf/net/hub/ViolationSink.java`
- Create: `src/main/java/com/openggf/net/hub/GhostStreamValidator.java`
- Test: `src/test/java/com/openggf/net/hub/TestGhostStreamValidator.java`

**Interfaces:**
- Consumes: `GhostPackets.FramesBatch` (Task 2), `GhostFrame`/`GhostFrameCodec` (phase 1).
- Produces:
  - `TrackValidationProfile(int levelWidthPx, int levelHeightPx, int maxSpeedPxPerFrame, int maxFramesPerSecond)` (record). Constants: `GLOBAL_SPEED_CEILING_PX_PER_FRAME = 32` (conservative global ceiling per security spec §7.2 — max across characters/boosts + margin; a gross-spoofing filter that must never false-positive a legitimate run), `FRAME_RATE_CAP = 60`, `BOUNDS_MARGIN_PX = 64`.
  - `TrackValidationProfileSource` — `Optional<TrackValidationProfile> profileFor(String gameId, int zone, int act)`; `static TrackValidationProfileSource none()`. The hub consumes this interface: bundled table on the phase-3 master, `none()` in tests AND on a player host. A player host has no loaded level when the room starts (and gameplay state must not be read from the room's event loop), so the host path instead PUSHES a profile built on the game thread after its level loads — `Task 14's LiveLevelProfileFactory` → `RoomHost.applyTrackValidationProfile` (Task 7) → `GhostHub.applyProfile` (Task 5) → the validator's `updateProfile` below. Until the push lands, the validator runs in its explicit degraded mode (security spec §7.2).
  - `GhostStreamValidator.updateProfile(TrackValidationProfile profileOrNull)` — swaps the bounds/speed source WITHOUT touching attempt/stream state (attemptId, frame contiguity, rate bucket, pacing). A reset here would false-positive the `attempt-start` check for every guest already mid-attempt when the host's level finishes loading.
  - `ViolationSink` — `void onViolation(String kind, String detail)`; validator owners wrap it to add slot/fingerprint context.
  - `GhostStreamValidator(TrackValidationProfile profileOrNull, LongSupplier wallClockMillis, ViolationSink sink)`:
    - `enum Verdict { ACCEPT, ACCEPT_FLAGGED, DROP, KICK }` — `onBatch(GhostPackets.FramesBatch batch)` returns one per upstream batch.
    - No profile → **explicit degrade** (security spec §7.2): only track-independent checks run — monotonicity/contiguity, frame-rate cap, global speed ceiling.
    - Checks: (1) stale attemptId (< current) → `DROP` **silently, no violation** (main spec §7: stale-attempt frames dropped silently at every layer); (2) new attemptId (> current) must start at frameIndex 0 else violation `attempt-start`; (3) contiguity — `startFrameIndex != nextExpectedFrameIndex` → violation `frame-gap`; (4) token-bucket frame rate: capacity `RATE_BURST_FRAMES = 300`, refill `RATE_REFILL_PER_SECOND = 66` (60 fps + 10% jitter headroom; the burst capacity absorbs TCP-stall catch-up) → violation `rate-cap`; (5) per-frame bounds vs profile + `BOUNDS_MARGIN_PX` → violation `bounds`; (6) per-frame position delta > maxSpeed (profile's, else global ceiling; checked across batch boundaries via the previous accepted frame) → violation `speed`; (7) **pacing** (security spec §7.1 — the anti-slow-motion layer replay verification cannot provide): after `PACING_WARMUP_MILLIS = 3000` from the attempt's first batch, if `newestFrameIndex < elapsedMillis * PACING_MIN_FPS / 1000` (`PACING_MIN_FPS = 54`, ~10% tolerance for hiccups) → violation `pacing`, attempt permanently flagged, batch still relayed (`ACCEPT_FLAGGED`).
    - Ladder (security spec §7.2 — drop the batch → kick on repeat): violations 1..`KICK_THRESHOLD-1` → `DROP` (except pacing which flags but accepts); `KICK_THRESHOLD = 10` cumulative violations → `KICK`.
    - `boolean isAttemptFlagged()` — true once pacing flagged the CURRENT attempt (resets on a new attempt); a flagged attempt's `AttemptFinish` is rejected for standings (Task 6 consumes this).
    - `int violationCount()`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.hub;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostStreamValidator {
    private static final TrackValidationProfile PROFILE =
            new TrackValidationProfile(0x2A00, 0x0800, 16, 60);

    private long now = 100_000;
    private final List<String> violations = new ArrayList<>();

    private GhostStreamValidator validator(TrackValidationProfile profile) {
        return new GhostStreamValidator(profile, () -> now, (kind, detail) -> violations.add(kind));
    }

    private static byte[] frames(int startX, int step, int count) {
        byte[] data = new byte[count * GhostFrameCodec.BYTES];
        for (int i = 0; i < count; i++) {
            GhostFrameCodec.encode(new GhostFrame(startX + i * step, 0x0100, 1,
                    false, false, false, 2, false), data, i * GhostFrameCodec.BYTES);
        }
        return data;
    }

    private static GhostPackets.FramesBatch batch(int attemptId, int startIndex, byte[] frameData) {
        return GhostPackets.decodeFrames(GhostPackets.encodeFrames(attemptId, startIndex, frameData));
    }

    @Test
    void acceptsContiguousLegitimateStream() {
        GhostStreamValidator v = validator(PROFILE);
        for (int i = 0; i < 20; i++) {
            now += 50; // 20 Hz, 3 frames per packet = exactly 60 fps
            assertEquals(GhostStreamValidator.Verdict.ACCEPT,
                    v.onBatch(batch(1, i * 3, frames(100 + i * 6, 2, 3))));
        }
        assertEquals(0, v.violationCount());
        assertFalse(v.isAttemptFlagged());
    }

    @Test
    void dropsStaleAttemptSilentlyAndResetsOnNewAttempt() {
        GhostStreamValidator v = validator(PROFILE);
        assertEquals(GhostStreamValidator.Verdict.ACCEPT, v.onBatch(batch(2, 0, frames(100, 2, 3))));
        assertEquals(GhostStreamValidator.Verdict.DROP, v.onBatch(batch(1, 0, frames(100, 2, 3))));
        assertTrue(violations.isEmpty()); // stale = silent, not a violation
        assertEquals(GhostStreamValidator.Verdict.ACCEPT, v.onBatch(batch(3, 0, frames(500, 2, 3))));
    }

    @Test
    void newAttemptMustStartAtFrameZero() {
        GhostStreamValidator v = validator(PROFILE);
        assertEquals(GhostStreamValidator.Verdict.DROP, v.onBatch(batch(1, 30, frames(100, 2, 3))));
        assertEquals(List.of("attempt-start"), violations);
    }

    @Test
    void frameGapViolates() {
        GhostStreamValidator v = validator(PROFILE);
        v.onBatch(batch(1, 0, frames(100, 2, 3)));
        assertEquals(GhostStreamValidator.Verdict.DROP, v.onBatch(batch(1, 9, frames(112, 2, 3))));
        assertEquals(List.of("frame-gap"), violations);
    }

    @Test
    void teleportViolatesSpeedCapAcrossBatchBoundary() {
        GhostStreamValidator v = validator(PROFILE);
        v.onBatch(batch(1, 0, frames(100, 2, 3)));
        // next batch first frame jumps 500px from previous accepted frame (104 -> 604)
        assertEquals(GhostStreamValidator.Verdict.DROP, v.onBatch(batch(1, 3, frames(604, 2, 3))));
        assertEquals(List.of("speed"), violations);
    }

    @Test
    void outOfBoundsViolatesOnlyWithProfile() {
        // Walk to the boundary at a legitimate 2 px/frame — far below the speed cap —
        // then cross it, so ONLY the bounds check can fire (one violation per batch,
        // first failed check). Bounds limit = levelWidth 0x2A00 + BOUNDS_MARGIN 64 = 0x2A40.
        GhostStreamValidator withProfile = validator(PROFILE);
        assertEquals(GhostStreamValidator.Verdict.ACCEPT,
                withProfile.onBatch(batch(1, 0, frames(0x2A30, 2, 3)))); // 0x2A30..0x2A34: inside
        assertEquals(GhostStreamValidator.Verdict.DROP,
                withProfile.onBatch(batch(1, 3, frames(0x2A3E, 2, 3)))); // 0x2A3E..0x2A42: last > 0x2A40
        assertEquals(List.of("bounds"), violations);

        violations.clear();
        GhostStreamValidator degraded = validator(null); // no profile: bounds check skipped
        assertEquals(GhostStreamValidator.Verdict.ACCEPT,
                degraded.onBatch(batch(1, 0, frames(0x2A3E, 2, 3)))); // same out-of-level walk
        assertTrue(violations.isEmpty());
    }

    @Test
    void pacingFlagsSlowMotionButStillRelays() {
        GhostStreamValidator v = validator(PROFILE);
        v.onBatch(batch(1, 0, frames(100, 2, 3)));
        now += 10_000; // 10 s wall clock later the attempt has only 6 frames — deep below 54 fps
        GhostStreamValidator.Verdict verdict = v.onBatch(batch(1, 3, frames(106, 2, 3)));
        assertEquals(GhostStreamValidator.Verdict.ACCEPT_FLAGGED, verdict);
        assertTrue(v.isAttemptFlagged());
        assertEquals(List.of("pacing"), violations);
        // a fresh attempt clears the flag
        v.onBatch(batch(2, 0, frames(100, 2, 3)));
        assertFalse(v.isAttemptFlagged());
    }

    @Test
    void rateCapViolatesOnSustainedOverSixtyFps() {
        GhostStreamValidator v = validator(PROFILE);
        int index = 0;
        boolean rateTripped = false;
        // 200 batches (600 frames) with zero wall-clock progress: bucket (300) must run dry.
        for (int i = 0; i < 200 && !rateTripped; i++) {
            GhostStreamValidator.Verdict verdict = v.onBatch(batch(1, index, frames(100 + index * 2, 2, 3)));
            if (verdict != GhostStreamValidator.Verdict.ACCEPT) {
                rateTripped = true;
                assertTrue(violations.contains("rate-cap"));
            } else {
                index += 3;
            }
        }
        assertTrue(rateTripped);
    }

    @Test
    void updateProfileTightensChecksWithoutResettingStreamState() {
        GhostStreamValidator v = validator(null); // host level not loaded yet: degraded
        v.onBatch(batch(1, 0, frames(0x29F0, 2, 3))); // near the eventual boundary, no complaint
        v.updateProfile(PROFILE); // level loaded mid-attempt: pushed profile
        // contiguity survives the swap: next expected index is still 3
        assertEquals(GhostStreamValidator.Verdict.ACCEPT, v.onBatch(batch(1, 3, frames(0x29F6, 2, 3))));
        byte[] outOfLevel = new byte[GhostFrameCodec.BYTES];
        GhostFrameCodec.encode(new GhostFrame(0x2A00 + 65, 0x0100, 1, false, false, false, 2, false),
                outOfLevel, 0);
        assertEquals(GhostStreamValidator.Verdict.DROP, v.onBatch(batch(1, 6, outOfLevel)));
        assertFalse(violations.isEmpty()); // bounds (or speed) now enforced
    }

    @Test
    void tenViolationsKick() {
        GhostStreamValidator v = validator(PROFILE);
        v.onBatch(batch(1, 0, frames(100, 2, 3)));
        GhostStreamValidator.Verdict last = GhostStreamValidator.Verdict.ACCEPT;
        for (int i = 0; i < 10; i++) {
            last = v.onBatch(batch(1, 999, frames(100, 2, 3))); // repeated frame-gap
        }
        assertEquals(GhostStreamValidator.Verdict.KICK, last);
        assertEquals(10, v.violationCount());
    }
}
```

Violation-recording rule (tests and implementation are written to it): the validator records at most ONE violation per batch — the first failed check — and returns immediately (simpler ladder math; `KICK_THRESHOLD` stays 10). That is why `outOfBoundsViolatesOnlyWithProfile` walks to the boundary at legitimate speed before crossing it: a teleport past the boundary would trip `speed` first and `bounds` would never record. Teleport coverage lives in `teleportViolatesSpeedCapAcrossBatchBoundary`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestGhostStreamValidator" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.hub;

/**
 * Numeric track metadata for hub-side sanity checks (security spec §7.2).
 * Pure numbers — no ROM asset bytes — so it can live on the engine-free master.
 */
public record TrackValidationProfile(int levelWidthPx, int levelHeightPx, int maxSpeedPxPerFrame,
                                     int maxFramesPerSecond) {
    /** Conservative cross-character ceiling incl. springs/speed-shoes + margin. Never false-positives. */
    public static final int GLOBAL_SPEED_CEILING_PX_PER_FRAME = 32;
    public static final int FRAME_RATE_CAP = 60;
    public static final int BOUNDS_MARGIN_PX = 64;
}
```

```java
package com.openggf.net.hub;

import java.util.Optional;

/** Where bounds/caps come from: bundled table (master, phase 3) or live level (player host). */
public interface TrackValidationProfileSource {
    Optional<TrackValidationProfile> profileFor(String gameId, int zone, int act);

    static TrackValidationProfileSource none() {
        return (gameId, zone, act) -> Optional.empty();
    }
}
```

```java
package com.openggf.net.hub;

/** Receives validator violations; owners wrap with player context. Phase 2 logs; phase 3 persists. */
public interface ViolationSink {
    void onViolation(String kind, String detail);
}
```

```java
package com.openggf.net.hub;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.protocol.GhostPackets;

import java.util.function.LongSupplier;

/**
 * Per-player upstream ghost-stream sanity (security spec §7.1/§7.2): contiguity,
 * bounds, speed, rate caps, and wall-clock pacing — the anti-slow-motion layer.
 * One violation is recorded per offending batch; KICK_THRESHOLD cumulative
 * violations escalate to a kick.
 */
public final class GhostStreamValidator {
    public enum Verdict { ACCEPT, ACCEPT_FLAGGED, DROP, KICK }

    public static final int KICK_THRESHOLD = 10;
    public static final int RATE_BURST_FRAMES = 300;
    public static final int RATE_REFILL_PER_SECOND = 66;
    public static final long PACING_WARMUP_MILLIS = 3000;
    public static final int PACING_MIN_FPS = 54;

    private TrackValidationProfile profile; // nullable: explicit degrade to track-independent checks
    private final LongSupplier wallClockMillis;
    private final ViolationSink sink;

    private int currentAttemptId = Integer.MIN_VALUE;
    private int nextExpectedFrameIndex;
    private long attemptFirstSeenMillis;
    private boolean attemptFlagged;
    private GhostFrame previousFrame;
    private int violationCount;
    private double rateTokens = RATE_BURST_FRAMES;
    private long rateLastRefillMillis = Long.MIN_VALUE;

    public GhostStreamValidator(TrackValidationProfile profileOrNull, LongSupplier wallClockMillis,
                                ViolationSink sink) {
        this.profile = profileOrNull;
        this.wallClockMillis = wallClockMillis;
        this.sink = sink;
    }

    /**
     * Swaps the bounds/speed source without touching attempt/stream state. Used when a
     * player host's level finishes loading after guests are already streaming — a reset
     * here would false-positive the attempt-start check on their live attempts.
     */
    public void updateProfile(TrackValidationProfile profileOrNull) {
        this.profile = profileOrNull;
    }

    public Verdict onBatch(GhostPackets.FramesBatch batch) {
        long now = wallClockMillis.getAsLong();
        if (batch.attemptId() < currentAttemptId) {
            return Verdict.DROP; // stale attempt: silent at every layer (main spec §7)
        }
        if (batch.attemptId() > currentAttemptId) {
            if (batch.startFrameIndex() != 0) {
                return violate("attempt-start", "attempt " + batch.attemptId()
                        + " began at frame " + batch.startFrameIndex());
            }
            currentAttemptId = batch.attemptId();
            nextExpectedFrameIndex = 0;
            attemptFirstSeenMillis = now;
            attemptFlagged = false;
            previousFrame = null;
        } else if (batch.startFrameIndex() != nextExpectedFrameIndex) {
            return violate("frame-gap", "expected frame " + nextExpectedFrameIndex
                    + " got " + batch.startFrameIndex());
        }

        refillTokens(now);
        if (rateTokens < batch.frameCount()) {
            return violate("rate-cap", "sustained frame rate above " + RATE_REFILL_PER_SECOND + "/s");
        }
        rateTokens -= batch.frameCount();

        int maxSpeed = profile != null ? profile.maxSpeedPxPerFrame()
                : TrackValidationProfile.GLOBAL_SPEED_CEILING_PX_PER_FRAME;
        GhostFrame previous = previousFrame;
        for (int i = 0; i < batch.frameCount(); i++) {
            GhostFrame frame = GhostFrameCodec.decode(batch.frameData(), i * GhostFrameCodec.BYTES);
            if (previous != null) {
                int dx = Math.abs(frame.x() - previous.x());
                int dy = Math.abs(frame.y() - previous.y());
                if (dx > maxSpeed || dy > maxSpeed) {
                    return violate("speed", "delta " + dx + "," + dy + " exceeds " + maxSpeed);
                }
            }
            if (profile != null) {
                if (frame.x() > profile.levelWidthPx() + TrackValidationProfile.BOUNDS_MARGIN_PX
                        || frame.y() > profile.levelHeightPx() + TrackValidationProfile.BOUNDS_MARGIN_PX) {
                    return violate("bounds", "position " + frame.x() + "," + frame.y() + " outside level");
                }
            }
            previous = frame;
        }
        previousFrame = previous;
        nextExpectedFrameIndex = batch.startFrameIndex() + batch.frameCount();

        long elapsed = now - attemptFirstSeenMillis;
        if (elapsed > PACING_WARMUP_MILLIS
                && nextExpectedFrameIndex < elapsed * PACING_MIN_FPS / 1000) {
            if (!attemptFlagged) {
                attemptFlagged = true;
                violationCount++;
                sink.onViolation("pacing", "attempt " + currentAttemptId + " at "
                        + (nextExpectedFrameIndex * 1000L / Math.max(elapsed, 1)) + " fps");
            }
            return violationCount >= KICK_THRESHOLD ? Verdict.KICK : Verdict.ACCEPT_FLAGGED;
        }
        return Verdict.ACCEPT;
    }

    public boolean isAttemptFlagged() {
        return attemptFlagged;
    }

    public int violationCount() {
        return violationCount;
    }

    private void refillTokens(long now) {
        if (rateLastRefillMillis != Long.MIN_VALUE && now > rateLastRefillMillis) {
            rateTokens = Math.min(RATE_BURST_FRAMES,
                    rateTokens + (now - rateLastRefillMillis) * RATE_REFILL_PER_SECOND / 1000.0);
        }
        rateLastRefillMillis = now;
    }

    private Verdict violate(String kind, String detail) {
        violationCount++;
        sink.onViolation(kind, detail);
        return violationCount >= KICK_THRESHOLD ? Verdict.KICK : Verdict.DROP;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.hub.TestGhostStreamValidator" test`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/TrackValidationProfile.java src/main/java/com/openggf/net/hub/TrackValidationProfileSource.java src/main/java/com/openggf/net/hub/ViolationSink.java src/main/java/com/openggf/net/hub/GhostStreamValidator.java src/test/java/com/openggf/net/hub/TestGhostStreamValidator.java
git commit -m "feat(timeattack): ghost stream validator with pacing and violation ladder

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 5: GhostHub — ingest, ring buffers, 20 Hz aggregation

**Files:**
- Create: `src/main/java/com/openggf/net/hub/HubConnection.java`
- Create: `src/main/java/com/openggf/net/hub/GhostHub.java`
- Test: `src/test/java/com/openggf/net/hub/TestGhostHub.java`

**Interfaces:**
- Consumes: `GhostPackets` (Task 2), `GhostStreamValidator`/`TrackValidationProfileSource`/`ViolationSink` (Task 4).
- Produces:
  - `HubConnection` — transport abstraction so the hub is **shared verbatim** between the player-host path and the phase-3 master relay (main spec §6.1): `void sendText(String text)`, `void sendBinary(byte[] data)`, `void close(String reason)`, `String remoteHost()`.
  - `GhostHub(LongSupplier wallClockMillis, TrackValidationProfileSource profiles, HubViolationRecorder recorder)` with nested `@FunctionalInterface HubViolationRecorder { void record(int slot, String fingerprint, String kind, String detail); }`:
    - `void setTrack(String gameId, int zone, int act)` — resolves the validation profile, resets all per-player validators and pending buffers (called on round start).
    - `void addPlayer(int slot, String fingerprint, HubConnection connection)` / `void removePlayer(int slot)`.
    - `Verdict-driven ingest`: `void onBinary(int slot, byte[] packet)` — decode `GhostFrames` (undecodable → count as violation via recorder, drop), run the slot's validator, buffer accepted batches. `KICK` verdict → `connection.close("ghost stream violations")` + recorded; the caller (RoomHost, Task 7) observes the close via its disconnect path.
    - `void tick()` — one `GhostAggregate` per recipient containing every OTHER player's pending frames (≤ 8 players: everyone is near, no relevance filtering — main spec §4.4), then clears pending. Contiguous pending batches of the same attempt merge into one entry; an attempt change mid-tick emits multiple entries; per-entry frame count capped at `MAX_AGGREGATE_FRAMES_PER_ENTRY` with the remainder left pending for the next tick. Recipients with no entries get NO packet (don't wake idle sockets). Tick counter increments every call — `int tickCount()`.
    - Pending cap per player: `MAX_PENDING_FRAMES = 600` (10 s at 60 fps); overflow drops the OLDEST pending batches (a laggy stream catches up to live rather than replaying ancient frames; the real backpressure ladder is phase 3).
    - `boolean isAttemptFlagged(int slot)` — exposes the slot validator's pacing flag for Task 6.
    - `void applyProfile(TrackValidationProfile profileOrNull)` — the player-host push path (security spec §7.2): stores the profile for future validators and calls `updateProfile` on every existing player's validator WITHOUT resetting buffers or stream state (unlike `setTrack`, which is a full reset and is only safe between rounds). Called via `RaceHostServer.execute` once the host's level has loaded (Task 15 flow).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.hub;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostHub {
    static final class FakeConnection implements HubConnection {
        final List<byte[]> binary = new ArrayList<>();
        final List<String> text = new ArrayList<>();
        String closedReason;

        @Override public void sendText(String t) { text.add(t); }
        @Override public void sendBinary(byte[] d) { binary.add(d); }
        @Override public void close(String reason) { closedReason = reason; }
        @Override public String remoteHost() { return "127.0.0.1"; }
    }

    private long now = 50_000;
    private final List<String> recorded = new ArrayList<>();
    private GhostHub hub;
    private final FakeConnection a = new FakeConnection();
    private final FakeConnection b = new FakeConnection();
    private final FakeConnection c = new FakeConnection();

    @BeforeEach
    void setUp() {
        hub = new GhostHub(() -> now, TrackValidationProfileSource.none(),
                (slot, fp, kind, detail) -> recorded.add(slot + ":" + kind));
        hub.setTrack("s3k", 0, 0);
        hub.addPlayer(0, "fp-a", a);
        hub.addPlayer(1, "fp-b", b);
        hub.addPlayer(2, "fp-c", c);
    }

    private static byte[] frames(int startX, int count) {
        byte[] data = new byte[count * GhostFrameCodec.BYTES];
        for (int i = 0; i < count; i++) {
            GhostFrameCodec.encode(new GhostFrame(startX + i * 2, 256, 1, false, false, false, 2, false),
                    data, i * GhostFrameCodec.BYTES);
        }
        return data;
    }

    @Test
    void tickSendsEachRecipientEveryoneElsesFrames() {
        hub.onBinary(0, GhostPackets.encodeFrames(1, 0, frames(100, 3)));
        hub.onBinary(1, GhostPackets.encodeFrames(1, 0, frames(500, 3)));
        hub.tick();

        GhostPackets.Aggregate forA = GhostPackets.decodeAggregate(a.binary.get(0));
        assertEquals(1, forA.entries().size());
        assertEquals(1, forA.entries().get(0).playerSlot()); // A sees only B

        GhostPackets.Aggregate forC = GhostPackets.decodeAggregate(c.binary.get(0));
        assertEquals(2, forC.entries().size()); // C sees A and B
    }

    @Test
    void idleRecipientsGetNoPacketAndPendingClearsAfterTick() {
        hub.onBinary(0, GhostPackets.encodeFrames(1, 0, frames(100, 3)));
        hub.tick();
        assertTrue(a.binary.isEmpty()); // nothing pending from others for A
        assertEquals(1, b.binary.size());
        hub.tick();
        assertEquals(1, b.binary.size()); // pending consumed; no repeat
    }

    @Test
    void contiguousBatchesMergeIntoOneEntry() {
        hub.onBinary(0, GhostPackets.encodeFrames(1, 0, frames(100, 3)));
        hub.onBinary(0, GhostPackets.encodeFrames(1, 3, frames(106, 3)));
        hub.tick();
        GhostPackets.Aggregate forB = GhostPackets.decodeAggregate(b.binary.get(0));
        assertEquals(1, forB.entries().size());
        assertEquals(6, forB.entries().get(0).frameCount());
        assertEquals(0, forB.entries().get(0).startFrameIndex());
    }

    @Test
    void undecodableBinaryIsRecordedAndDropped() {
        hub.onBinary(0, new byte[] {0x7F, 1, 2});
        hub.tick();
        assertTrue(b.binary.isEmpty());
        assertEquals(List.of("0:undecodable"), recorded);
    }

    @Test
    void kickClosesConnection() {
        hub.onBinary(0, GhostPackets.encodeFrames(1, 0, frames(100, 3)));
        for (int i = 0; i < GhostStreamValidator.KICK_THRESHOLD; i++) {
            hub.onBinary(0, GhostPackets.encodeFrames(1, 999, frames(100, 3))); // repeated frame-gap
        }
        assertNotNull(a.closedReason);
        assertTrue(recorded.stream().allMatch(r -> r.startsWith("0:")));
    }

    @Test
    void removedPlayerNeitherSendsNorReceives() {
        hub.removePlayer(1);
        hub.onBinary(0, GhostPackets.encodeFrames(1, 0, frames(100, 3)));
        hub.tick();
        assertTrue(b.binary.isEmpty());
        GhostPackets.Aggregate forC = GhostPackets.decodeAggregate(c.binary.get(0));
        assertEquals(1, forC.entries().size());
    }

    @Test
    void perEntryFrameCapLeavesRemainderPending() {
        // 45 contiguous frames pending: first tick ships 30, second ships 15.
        for (int i = 0; i < 15; i++) {
            hub.onBinary(0, GhostPackets.encodeFrames(1, i * 3, frames(100 + i * 6, 3)));
            now += 50; // keep the rate bucket happy
        }
        hub.tick();
        GhostPackets.Aggregate first = GhostPackets.decodeAggregate(b.binary.get(0));
        assertEquals(GhostPackets.MAX_AGGREGATE_FRAMES_PER_ENTRY, first.entries().get(0).frameCount());
        hub.tick();
        GhostPackets.Aggregate second = GhostPackets.decodeAggregate(b.binary.get(1));
        assertEquals(45 - GhostPackets.MAX_AGGREGATE_FRAMES_PER_ENTRY, second.entries().get(0).frameCount());
        assertEquals(GhostPackets.MAX_AGGREGATE_FRAMES_PER_ENTRY, second.entries().get(0).startFrameIndex());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestGhostHub" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.hub;

/** Transport-agnostic peer connection: the hub never touches Netty/JDK sockets directly. */
public interface HubConnection {
    void sendText(String text);

    void sendBinary(byte[] data);

    void close(String reason);

    String remoteHost();
}
```

```java
package com.openggf.net.hub;

import com.openggf.net.protocol.GhostPackets;
import com.openggf.net.protocol.ProtocolViolationException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Aggregation engine (main spec §4): ingests upstream ghost frames into per-player
 * pending buffers and composes ONE GhostAggregate per recipient per 20 Hz tick.
 * Phase 2 is the ≤8-player degenerate case: everyone is "near", no relevance
 * filtering, no roster. Single-threaded by contract — callers pin it to one
 * event loop (host) exactly as the phase-3 master will.
 */
public final class GhostHub {
    @FunctionalInterface
    public interface HubViolationRecorder {
        void record(int slot, String fingerprint, String kind, String detail);
    }

    /** 10 s of pending frames; beyond this the oldest batches drop (catch up to live). */
    public static final int MAX_PENDING_FRAMES = 600;

    private static final class Player {
        final String fingerprint;
        final HubConnection connection;
        GhostStreamValidator validator;
        final ArrayDeque<GhostPackets.FramesBatch> pending = new ArrayDeque<>();
        int pendingFrames;

        Player(String fingerprint, HubConnection connection) {
            this.fingerprint = fingerprint;
            this.connection = connection;
        }
    }

    private final LongSupplier wallClockMillis;
    private final TrackValidationProfileSource profiles;
    private final HubViolationRecorder recorder;
    private final Map<Integer, Player> players = new TreeMap<>();
    private TrackValidationProfile currentProfile; // nullable: explicit degrade
    private int tickCount;

    public GhostHub(LongSupplier wallClockMillis, TrackValidationProfileSource profiles,
                    HubViolationRecorder recorder) {
        this.wallClockMillis = wallClockMillis;
        this.profiles = profiles;
        this.recorder = recorder;
    }

    public void setTrack(String gameId, int zone, int act) {
        currentProfile = profiles.profileFor(gameId, zone, act).orElse(null);
        for (Map.Entry<Integer, Player> entry : players.entrySet()) {
            resetPlayerStream(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Player-host push path: applies a live-level profile to current AND future
     * validators without resetting stream state (guests may already be mid-attempt
     * when the host's level finishes loading — see GhostStreamValidator.updateProfile).
     */
    public void applyProfile(TrackValidationProfile profileOrNull) {
        currentProfile = profileOrNull;
        for (Player player : players.values()) {
            player.validator.updateProfile(profileOrNull);
        }
    }

    public void addPlayer(int slot, String fingerprint, HubConnection connection) {
        Player player = new Player(fingerprint, connection);
        players.put(slot, player);
        resetPlayerStream(slot, player);
    }

    public void removePlayer(int slot) {
        players.remove(slot);
    }

    public void onBinary(int slot, byte[] packet) {
        Player player = players.get(slot);
        if (player == null) {
            return;
        }
        GhostPackets.FramesBatch batch;
        try {
            batch = GhostPackets.decodeFrames(packet);
        } catch (ProtocolViolationException e) {
            recorder.record(slot, player.fingerprint, "undecodable", e.getMessage());
            return;
        }
        GhostStreamValidator.Verdict verdict = player.validator.onBatch(batch);
        switch (verdict) {
            case ACCEPT, ACCEPT_FLAGGED -> buffer(player, batch);
            case DROP -> { /* dropped */ }
            case KICK -> player.connection.close("ghost stream violations");
        }
    }

    public void tick() {
        tickCount++;
        Map<Integer, List<GhostPackets.AggregateEntry>> drained = new TreeMap<>();
        for (Map.Entry<Integer, Player> entry : players.entrySet()) {
            List<GhostPackets.AggregateEntry> entries = drain(entry.getKey(), entry.getValue());
            if (!entries.isEmpty()) {
                drained.put(entry.getKey(), entries);
            }
        }
        for (Map.Entry<Integer, Player> recipient : players.entrySet()) {
            List<GhostPackets.AggregateEntry> forRecipient = new ArrayList<>();
            for (Map.Entry<Integer, List<GhostPackets.AggregateEntry>> sender : drained.entrySet()) {
                if (!sender.getKey().equals(recipient.getKey())) {
                    forRecipient.addAll(sender.getValue());
                }
            }
            if (!forRecipient.isEmpty()) {
                recipient.getValue().connection.sendBinary(GhostPackets.encodeAggregate(tickCount, forRecipient));
            }
        }
    }

    public boolean isAttemptFlagged(int slot) {
        Player player = players.get(slot);
        return player != null && player.validator.isAttemptFlagged();
    }

    public int tickCount() {
        return tickCount;
    }

    private void resetPlayerStream(int slot, Player player) {
        player.validator = new GhostStreamValidator(currentProfile, wallClockMillis,
                (kind, detail) -> recorder.record(slot, player.fingerprint, kind, detail));
        player.pending.clear();
        player.pendingFrames = 0;
    }

    private void buffer(Player player, GhostPackets.FramesBatch batch) {
        player.pending.addLast(batch);
        player.pendingFrames += batch.frameCount();
        while (player.pendingFrames > MAX_PENDING_FRAMES) {
            player.pendingFrames -= player.pending.removeFirst().frameCount();
        }
    }

    /** Merges contiguous same-attempt pending batches into entries, ≤ cap frames per entry. */
    private List<GhostPackets.AggregateEntry> drain(int slot, Player player) {
        List<GhostPackets.AggregateEntry> entries = new ArrayList<>();
        while (!player.pending.isEmpty()) {
            GhostPackets.FramesBatch head = player.pending.peekFirst();
            int attemptId = head.attemptId();
            int startIndex = head.startFrameIndex();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            int frames = 0;
            int nextIndex = startIndex;
            while (!player.pending.isEmpty()) {
                GhostPackets.FramesBatch next = player.pending.peekFirst();
                if (next.attemptId() != attemptId || next.startFrameIndex() != nextIndex
                        || frames + next.frameCount() > GhostPackets.MAX_AGGREGATE_FRAMES_PER_ENTRY) {
                    break;
                }
                player.pending.removeFirst();
                player.pendingFrames -= next.frameCount();
                bytes.writeBytes(next.frameData());
                frames += next.frameCount();
                nextIndex += next.frameCount();
            }
            if (frames == 0) {
                // Head batch alone exceeds the per-entry cap window: split it.
                GhostPackets.FramesBatch big = player.pending.removeFirst();
                player.pendingFrames -= big.frameCount();
                int take = GhostPackets.MAX_AGGREGATE_FRAMES_PER_ENTRY;
                int frameBytes = com.openggf.game.ghost.GhostFrameCodec.BYTES;
                byte[] first = java.util.Arrays.copyOfRange(big.frameData(), 0, take * frameBytes);
                byte[] rest = java.util.Arrays.copyOfRange(big.frameData(), take * frameBytes,
                        big.frameData().length);
                entries.add(new GhostPackets.AggregateEntry(slot, attemptId, big.startFrameIndex(), take, first));
                player.pending.addFirst(new GhostPackets.FramesBatch(attemptId,
                        big.startFrameIndex() + take, big.frameCount() - take, rest));
                player.pendingFrames += big.frameCount() - take;
                break; // cap reached this tick
            }
            entries.add(new GhostPackets.AggregateEntry(slot, attemptId, startIndex, frames, bytes.toByteArray()));
            if (frames >= GhostPackets.MAX_AGGREGATE_FRAMES_PER_ENTRY) {
                break; // remainder next tick
            }
        }
        return entries;
    }
}
```

Implementation note: upstream batches are ≤ 3 frames (Task 2 enforces at decode), so the "head batch alone exceeds the cap" branch is unreachable from real ingest — keep it anyway; the phase-3 relay may feed larger internal batches, and the guard makes `drain` total.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.hub.TestGhostHub" test`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/HubConnection.java src/main/java/com/openggf/net/hub/GhostHub.java src/test/java/com/openggf/net/hub/TestGhostHub.java
git commit -m "feat(timeattack): GhostHub ingest and 20Hz aggregate composition

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 6: HostRoundEngine — authoritative round state machine + standings

**Files:**
- Create: `src/main/java/com/openggf/net/hub/HostRoundEngine.java`
- Test: `src/test/java/com/openggf/net/hub/TestHostRoundEngine.java`

**Interfaces:**
- Consumes: `ControlMessage.{RoundConfig,RoundStart,RoundEnd,StandingsDelta,StandingsRow,RoundSnapshot,AttemptFinish}` (Task 1).
- Produces:
  - `HostRoundEngine(LongSupplier hubClockMillis, Consumer<ControlMessage> broadcaster)`:
    - `enum Phase { LOBBY, COUNTDOWN, RUNNING, ROUND_END }`; constants `COUNTDOWN_MILLIS = 3000`, `FINISH_GRACE_MILLIS = 2000`, `ROUND_END_LINGER_MILLIS = 10_000`.
    - `boolean startRound(ControlMessage.RoundConfig config)` — legal in LOBBY/ROUND_END only (returns false otherwise); sets `countdownEndsAt = now + COUNTDOWN_MILLIS`, `deadline = countdownEndsAt + windowSeconds*1000`, clears standings, broadcasts `RoundStart(config, countdownEndsAt, deadline)`, phase → COUNTDOWN.
    - `void onTick()` — COUNTDOWN→RUNNING at `countdownEndsAt`; RUNNING→ROUND_END at `deadline` (broadcast `RoundEnd(finalStandings)`); ROUND_END→LOBBY after `ROUND_END_LINGER_MILLIS`.
    - `void onAttemptFinish(int slot, String displayName, String character, ControlMessage.AttemptFinish finish, boolean attemptFlagged)` — accepted only when phase is RUNNING, `now <= deadline + FINISH_GRACE_MILLIS` (main spec §5: hard deadline cutoff; the grace covers transit of a finish that occurred before the deadline), `!attemptFlagged` (security spec §7.1: a pacing-flagged attempt's finish is rejected for standings), and `finish.timeFrames() > 0`. A slower time than the slot's existing best is ignored. On improvement: update, re-rank, broadcast `StandingsDelta` (full sorted rows — fine at ≤ 8; paged standings are a phase-3 concern).
    - `void onPlayerLeft(int slot)` — best time is KEPT for the round, row greys out client-side (main spec §9); no broadcast (RoomState conveys departure).
    - `List<ControlMessage.StandingsRow> standings()`; `Phase phase()`; `ControlMessage.RoundSnapshot snapshot()` (phase name string, config, both timestamps, standings — sent inside `JoinAccepted` so mid-round joiners sync instantly).
  - Ranking: ascending `bestTimeFrames`; players without a finish are NOT in standings; ranks are 1-based positions after sort; equal times share the earlier rank order by first-achieved (stable: earlier finisher first).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.hub;

import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestHostRoundEngine {
    private long now = 1_000_000;
    private final List<ControlMessage> broadcast = new ArrayList<>();
    private HostRoundEngine engine;
    private final ControlMessage.RoundConfig config =
            new ControlMessage.RoundConfig("s3k", 0, 0, 300, "OPEN", null);

    @BeforeEach
    void setUp() {
        engine = new HostRoundEngine(() -> now, broadcast::add);
    }

    private static ControlMessage.AttemptFinish finish(int attemptId, int frames) {
        return new ControlMessage.AttemptFinish(attemptId, frames, 10, 10 + frames,
                "ab".repeat(32), "cd".repeat(32), null);
    }

    @Test
    void fullRoundLifecycle() {
        assertEquals(HostRoundEngine.Phase.LOBBY, engine.phase());
        assertTrue(engine.startRound(config));
        assertEquals(HostRoundEngine.Phase.COUNTDOWN, engine.phase());
        ControlMessage.RoundStart start = (ControlMessage.RoundStart) broadcast.get(0);
        assertEquals(now + HostRoundEngine.COUNTDOWN_MILLIS, start.countdownEndsAtHubMillis());
        assertEquals(start.countdownEndsAtHubMillis() + 300_000, start.deadlineHubMillis());

        now = start.countdownEndsAtHubMillis();
        engine.onTick();
        assertEquals(HostRoundEngine.Phase.RUNNING, engine.phase());

        now = start.deadlineHubMillis() + 1;
        engine.onTick();
        assertEquals(HostRoundEngine.Phase.ROUND_END, engine.phase());
        assertInstanceOf(ControlMessage.RoundEnd.class, broadcast.get(broadcast.size() - 1));

        now += HostRoundEngine.ROUND_END_LINGER_MILLIS;
        engine.onTick();
        assertEquals(HostRoundEngine.Phase.LOBBY, engine.phase());
    }

    @Test
    void cannotStartRoundMidRound() {
        engine.startRound(config);
        assertFalse(engine.startRound(config));
        assertEquals(1, broadcast.stream().filter(m -> m instanceof ControlMessage.RoundStart).count());
    }

    @Test
    void standingsRankImprovementsAndIgnoreSlower() {
        engine.startRound(config);
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();

        engine.onAttemptFinish(0, "A", "sonic", finish(1, 4000), false);
        engine.onAttemptFinish(1, "B", "tails", finish(1, 3500), false);
        engine.onAttemptFinish(0, "A", "sonic", finish(2, 5000), false); // slower: ignored

        List<ControlMessage.StandingsRow> rows = engine.standings();
        assertEquals(2, rows.size());
        assertEquals("B", rows.get(0).displayName());
        assertEquals(1, rows.get(0).rank());
        assertEquals(4000, rows.get(1).bestTimeFrames());

        engine.onAttemptFinish(0, "A", "sonic", finish(3, 3000), false); // improvement re-ranks
        assertEquals("A", engine.standings().get(0).displayName());
        long deltas = broadcast.stream().filter(m -> m instanceof ControlMessage.StandingsDelta).count();
        assertEquals(3, deltas); // one per accepted finish, none for the ignored slower one
    }

    @Test
    void rejectsFinishOutsideWindowFlaggedOrInLobby() {
        engine.onAttemptFinish(0, "A", "sonic", finish(1, 4000), false); // LOBBY: ignored
        assertTrue(engine.standings().isEmpty());

        engine.startRound(config);
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();

        engine.onAttemptFinish(1, "B", "tails", finish(1, 3500), true); // pacing-flagged: rejected
        assertTrue(engine.standings().isEmpty());

        now += 300_000 + HostRoundEngine.FINISH_GRACE_MILLIS + 1;
        engine.onAttemptFinish(2, "C", "sonic", finish(1, 3000), false); // past deadline+grace
        assertTrue(engine.standings().isEmpty());
    }

    @Test
    void playerLeavingKeepsBestForTheRound() {
        engine.startRound(config);
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();
        engine.onAttemptFinish(0, "A", "sonic", finish(1, 4000), false);
        engine.onPlayerLeft(0);
        assertEquals(1, engine.standings().size());
    }

    @Test
    void snapshotCarriesPhaseConfigAndStandings() {
        engine.startRound(config);
        ControlMessage.RoundSnapshot snap = engine.snapshot();
        assertEquals("COUNTDOWN", snap.phase());
        assertEquals(config, snap.config());
        assertEquals(now + HostRoundEngine.COUNTDOWN_MILLIS, snap.countdownEndsAtHubMillis());
        assertTrue(snap.standings().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestHostRoundEngine" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.hub;

import com.openggf.net.protocol.ControlMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Authoritative room round state machine (main spec §6.1 RaceSession, host half):
 * lobby → countdown → window open → round end. Times are client-reported and
 * unverified in v1 (main spec §2); the engine only gates on window, pacing flag,
 * and monotonic improvement.
 */
public final class HostRoundEngine {
    public enum Phase { LOBBY, COUNTDOWN, RUNNING, ROUND_END }

    public static final long COUNTDOWN_MILLIS = 3000;
    public static final long FINISH_GRACE_MILLIS = 2000;
    public static final long ROUND_END_LINGER_MILLIS = 10_000;

    private record Best(String displayName, String character, int timeFrames, long achievedOrder) {
    }

    private final LongSupplier hubClockMillis;
    private final Consumer<ControlMessage> broadcaster;
    private final Map<Integer, Best> bests = new LinkedHashMap<>();

    private Phase phase = Phase.LOBBY;
    private ControlMessage.RoundConfig config;
    private long countdownEndsAt;
    private long deadline;
    private long roundEndAt;
    private long achievedCounter;

    public HostRoundEngine(LongSupplier hubClockMillis, Consumer<ControlMessage> broadcaster) {
        this.hubClockMillis = hubClockMillis;
        this.broadcaster = broadcaster;
    }

    public Phase phase() {
        return phase;
    }

    public boolean startRound(ControlMessage.RoundConfig newConfig) {
        if (phase != Phase.LOBBY && phase != Phase.ROUND_END) {
            return false;
        }
        long now = hubClockMillis.getAsLong();
        config = newConfig;
        countdownEndsAt = now + COUNTDOWN_MILLIS;
        deadline = countdownEndsAt + newConfig.windowSeconds() * 1000L;
        bests.clear();
        achievedCounter = 0;
        phase = Phase.COUNTDOWN;
        broadcaster.accept(new ControlMessage.RoundStart(config, countdownEndsAt, deadline));
        return true;
    }

    public void onTick() {
        long now = hubClockMillis.getAsLong();
        if (phase == Phase.COUNTDOWN && now >= countdownEndsAt) {
            phase = Phase.RUNNING;
        }
        if (phase == Phase.RUNNING && now > deadline) {
            phase = Phase.ROUND_END;
            roundEndAt = now;
            broadcaster.accept(new ControlMessage.RoundEnd(standings()));
        }
        if (phase == Phase.ROUND_END && now >= roundEndAt + ROUND_END_LINGER_MILLIS) {
            phase = Phase.LOBBY;
        }
    }

    public void onAttemptFinish(int slot, String displayName, String character,
                                ControlMessage.AttemptFinish finish, boolean attemptFlagged) {
        long now = hubClockMillis.getAsLong();
        if (phase != Phase.RUNNING || now > deadline + FINISH_GRACE_MILLIS
                || attemptFlagged || finish.timeFrames() <= 0) {
            return;
        }
        Best existing = bests.get(slot);
        if (existing != null && existing.timeFrames() <= finish.timeFrames()) {
            return;
        }
        long order = existing != null ? existing.achievedOrder() : achievedCounter++;
        bests.put(slot, new Best(displayName, character, finish.timeFrames(), order));
        broadcaster.accept(new ControlMessage.StandingsDelta(standings()));
    }

    public void onPlayerLeft(int slot) {
        // Best time kept for the round (main spec §9); rows grey out client-side via RoomState.
    }

    public List<ControlMessage.StandingsRow> standings() {
        List<Map.Entry<Integer, Best>> sorted = new ArrayList<>(bests.entrySet());
        sorted.sort((a, b) -> {
            int byTime = Integer.compare(a.getValue().timeFrames(), b.getValue().timeFrames());
            return byTime != 0 ? byTime : Long.compare(a.getValue().achievedOrder(), b.getValue().achievedOrder());
        });
        List<ControlMessage.StandingsRow> rows = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Integer, Best> entry = sorted.get(i);
            rows.add(new ControlMessage.StandingsRow(entry.getKey(), entry.getValue().displayName(),
                    entry.getValue().character(), entry.getValue().timeFrames(), i + 1));
        }
        return rows;
    }

    public ControlMessage.RoundSnapshot snapshot() {
        return new ControlMessage.RoundSnapshot(phase.name(), config, countdownEndsAt, deadline, standings());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.hub.TestHostRoundEngine" test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/HostRoundEngine.java src/test/java/com/openggf/net/hub/TestHostRoundEngine.java
git commit -m "feat(timeattack): authoritative host round engine with standings

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 7: RoomHost — admission, token enforcement, message dispatch

**Files:**
- Create: `src/main/java/com/openggf/net/hub/RoomHostConfig.java`
- Create: `src/main/java/com/openggf/net/hub/RoomHost.java`
- Test: `src/test/java/com/openggf/net/hub/TestRoomHost.java`

**Interfaces:**
- Consumes: everything from Tasks 1–6 + `PlayerIdentity`.
- Produces:
  - `RoomHostConfig(String roomName, String gameId, int zone, int act, String characterPolicy, String lockedCharacter, int maxPlayers, String requiredDeterminismFingerprint)` (record; `maxPlayers` clamped to `Protocol.MAX_PLAYERS_DIRECT` in the compact constructor).
  - `RoomHost(RoomHostConfig config, PlayerIdentity hostIdentity, LongSupplier wallClockMillis, TrackValidationProfileSource profiles)` — single-threaded by contract (the Netty event loop in Task 11 or the test thread here). Transport hooks:
    - `void onConnected(HubConnection connection)` — starts a per-connection `HostHandshake`; connections not admitted within `ADMISSION_TIMEOUT_MILLIS = 5000` are closed by `tick()` (security spec §7.3 handshake timeout).
    - `void onText(HubConnection connection, String text)` — `ControlCodec.decode` (`ProtocolViolationException` → close, security spec §7.3). Pre-admission: only `Hello`/`AuthProof` legal, driven through the handshake; `Admit` → assign lowest free slot (full → send `JoinRejected("room full")` + close), issue session token, `hub.addPlayer`, send `JoinAccepted(token, slot, descriptor, round.snapshot())`, broadcast `RoomState`. Duplicate fingerprint already in the room → `JoinRejected("identity already connected")`. Post-admission: **envelope token must match the connection's session token** or the message is dropped as a violation (3 strikes → close). Dispatch: `Chat` (rate limit `Protocol.CHAT_MIN_INTERVAL_MILLIS`, truncate to `MAX_CHAT_CHARS`, broadcast `ChatBroadcast`), `Ping` → reply `Pong(t0, now)`, `SelectCharacter` (LOBBY phase only; `LOCKED` policy forces `lockedCharacter`; broadcast `RoomState`), `RoundConfigure` (host-fingerprint player only; zone/act/game must match room descriptor in phase 2 — one-track rooms; calls `round.startRound` and `hub.setTrack`), `AttemptStart`/`AttemptReset` (bookkeeping only — the validator learns attempts from the binary stream), `AttemptFinish` → `round.onAttemptFinish(slot, name, character, finish, hub.isAttemptFlagged(slot))`. Client-illegal types (`Welcome`, `JoinAccepted`, `StandingsDelta`, hub-only broadcasts...) → violation.
    - `void onBinary(HubConnection connection, byte[] data)` — pre-admission → close; else `hub.onBinary(slot, data)`.
    - `void onDisconnected(HubConnection connection)` — revoke token, `hub.removePlayer`, `round.onPlayerLeft`, broadcast `RoomState`.
    - `void tick()` — `hub.tick()` + `round.onTick()` + admission-timeout sweep. Task 11 schedules this at 50 ms (20 Hz — main spec §4.1).
    - Host-side driving: `boolean requestStartRound(ControlMessage.RoundConfig config)` (used by the hosting player's UI via the server facade), `void applyTrackValidationProfile(TrackValidationProfile profile)` (delegates to `hub.applyProfile` — the host engine pushes this via `RaceHostServer.execute` once its level has loaded, closing the gap where a player host has no level at room-start; until it lands the validator runs degraded), `int playerCount()`, `List<ControlMessage.PlayerInfo> players()`, `HostRoundEngine round()`, `GhostHub hub()`.
  - Broadcasts are hub→client messages and carry `token = null`.

- [ ] **Step 1: Write the failing test** (reuses `FakeConnection` from Task 5's test — extract it to `src/test/java/com/openggf/net/hub/FakeHubConnection.java` as a top-level test fixture class with the same fields, and update `TestGhostHub` to use it)

```java
package com.openggf.net.hub;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.client.ClientHandshake;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestRoomHost {
    private static final String FP = "0.6:cafe1234";

    @TempDir
    Path dir;
    private long now = 1_000_000;
    private RoomHost room;
    private PlayerIdentity hostIdentity;

    @BeforeEach
    void setUp() throws Exception {
        hostIdentity = PlayerIdentity.loadOrCreate(dir.resolve("host"));
        room = new RoomHost(new RoomHostConfig("LAN", "s3k", 0, 0, "OPEN", null, 8, FP),
                hostIdentity, () -> now, TrackValidationProfileSource.none());
    }

    /** Drives a full handshake and returns the JoinAccepted the client received. */
    private ControlMessage.JoinAccepted admit(FakeHubConnection conn, String name, Path idDir) throws Exception {
        PlayerIdentity identity = PlayerIdentity.loadOrCreate(idDir);
        ClientHandshake handshake = new ClientHandshake(identity, name, FP);
        room.onConnected(conn);
        room.onText(conn, ControlCodec.encode(null, handshake.hello()));
        ControlMessage.Welcome welcome = (ControlMessage.Welcome) lastMessage(conn);
        room.onText(conn, ControlCodec.encode(null, handshake.onWelcome(welcome)));
        return (ControlMessage.JoinAccepted) conn.text.stream()
                .map(t -> ControlCodec.decode(t).message())
                .filter(m -> m instanceof ControlMessage.JoinAccepted)
                .findFirst().orElseThrow();
    }

    private static ControlMessage lastMessage(FakeHubConnection conn) {
        return ControlCodec.decode(conn.text.get(conn.text.size() - 1)).message();
    }

    @Test
    void admitsPlayersAssignsSlotsAndBroadcastsRoomState() throws Exception {
        FakeHubConnection a = new FakeHubConnection();
        FakeHubConnection b = new FakeHubConnection();
        ControlMessage.JoinAccepted joinA = admit(a, "A", dir.resolve("a"));
        ControlMessage.JoinAccepted joinB = admit(b, "B", dir.resolve("b"));
        assertEquals(0, joinA.playerSlot());
        assertEquals(1, joinB.playerSlot());
        assertFalse(joinA.room().verified());
        assertEquals("LOBBY", joinA.round().phase());
        // A received a RoomState after B joined listing both players
        ControlMessage.RoomState state = (ControlMessage.RoomState) lastMessage(a);
        assertEquals(2, state.players().size());
        assertEquals(2, room.playerCount());
    }

    @Test
    void rejectsWhenFullAndOnDuplicateIdentity() throws Exception {
        RoomHost tiny = new RoomHost(new RoomHostConfig("LAN", "s3k", 0, 0, "OPEN", null, 1, FP),
                hostIdentity, () -> now, TrackValidationProfileSource.none());
        FakeHubConnection a = new FakeHubConnection();
        PlayerIdentity idA = PlayerIdentity.loadOrCreate(dir.resolve("a"));
        ClientHandshake hsA = new ClientHandshake(idA, "A", FP);
        tiny.onConnected(a);
        tiny.onText(a, ControlCodec.encode(null, hsA.hello()));
        tiny.onText(a, ControlCodec.encode(null, hsA.onWelcome((ControlMessage.Welcome) lastMessage(a))));

        FakeHubConnection b = new FakeHubConnection();
        PlayerIdentity idB = PlayerIdentity.loadOrCreate(dir.resolve("b"));
        ClientHandshake hsB = new ClientHandshake(idB, "B", FP);
        tiny.onConnected(b);
        tiny.onText(b, ControlCodec.encode(null, hsB.hello()));
        tiny.onText(b, ControlCodec.encode(null, hsB.onWelcome((ControlMessage.Welcome) lastMessage(b))));
        assertInstanceOf(ControlMessage.JoinRejected.class, lastMessage(b));
        assertNotNull(b.closedReason);
    }

    @Test
    void enforcesSessionTokenOnAdmittedMessages() throws Exception {
        FakeHubConnection a = new FakeHubConnection();
        FakeHubConnection b = new FakeHubConnection();
        ControlMessage.JoinAccepted join = admit(a, "A", dir.resolve("a"));
        admit(b, "B", dir.resolve("b"));

        room.onText(a, ControlCodec.encode("wrongtoken", new ControlMessage.Chat("hi")));
        assertTrue(b.text.stream().map(t -> ControlCodec.decode(t).message())
                .noneMatch(m -> m instanceof ControlMessage.ChatBroadcast));

        room.onText(a, ControlCodec.encode(join.sessionToken(), new ControlMessage.Chat("hi")));
        assertTrue(b.text.stream().map(t -> ControlCodec.decode(t).message())
                .anyMatch(m -> m instanceof ControlMessage.ChatBroadcast));
    }

    @Test
    void chatIsRateLimited() throws Exception {
        FakeHubConnection a = new FakeHubConnection();
        FakeHubConnection b = new FakeHubConnection();
        String token = admit(a, "A", dir.resolve("a")).sessionToken();
        admit(b, "B", dir.resolve("b"));
        room.onText(a, ControlCodec.encode(token, new ControlMessage.Chat("one")));
        room.onText(a, ControlCodec.encode(token, new ControlMessage.Chat("two"))); // same millisecond: dropped
        long broadcasts = b.text.stream().map(t -> ControlCodec.decode(t).message())
                .filter(m -> m instanceof ControlMessage.ChatBroadcast).count();
        assertEquals(1, broadcasts);
    }

    @Test
    void onlyHostFingerprintMayStartRounds() throws Exception {
        FakeHubConnection guest = new FakeHubConnection();
        String guestToken = admit(guest, "G", dir.resolve("g")).sessionToken();
        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 300, "OPEN", null);
        room.onText(guest, ControlCodec.encode(guestToken, new ControlMessage.RoundConfigure(cfg)));
        assertEquals(HostRoundEngine.Phase.LOBBY, room.round().phase());

        // The hosting player connects with the host's own identity directory.
        FakeHubConnection hostConn = new FakeHubConnection();
        String hostToken = admit(hostConn, "Host", dir.resolve("host")).sessionToken();
        room.onText(hostConn, ControlCodec.encode(hostToken, new ControlMessage.RoundConfigure(cfg)));
        assertEquals(HostRoundEngine.Phase.COUNTDOWN, room.round().phase());
    }

    @Test
    void ghostFramesFlowToOtherPlayersAfterTick() throws Exception {
        FakeHubConnection a = new FakeHubConnection();
        FakeHubConnection b = new FakeHubConnection();
        admit(a, "A", dir.resolve("a"));
        admit(b, "B", dir.resolve("b"));
        byte[] frame = new byte[GhostFrameCodec.BYTES];
        GhostFrameCodec.encode(new GhostFrame(100, 200, 1, false, false, false, 2, false), frame, 0);
        room.onBinary(a, GhostPackets.encodeFrames(1, 0, frame));
        room.tick();
        assertEquals(1, b.binary.size());
        assertEquals(0, GhostPackets.decodeAggregate(b.binary.get(0)).entries().get(0).playerSlot());
    }

    @Test
    void admissionTimeoutClosesLoiterers() {
        FakeHubConnection loiterer = new FakeHubConnection();
        room.onConnected(loiterer);
        now += RoomHost.ADMISSION_TIMEOUT_MILLIS + 1;
        room.tick();
        assertNotNull(loiterer.closedReason);
    }

    @Test
    void malformedTextClosesConnection() throws Exception {
        FakeHubConnection a = new FakeHubConnection();
        admit(a, "A", dir.resolve("a"));
        room.onText(a, "garbage not json");
        assertNotNull(a.closedReason);
        assertEquals(0, room.playerCount());
    }

    @Test
    void disconnectRevokesAndBroadcasts() throws Exception {
        FakeHubConnection a = new FakeHubConnection();
        FakeHubConnection b = new FakeHubConnection();
        String tokenA = admit(a, "A", dir.resolve("a")).sessionToken();
        admit(b, "B", dir.resolve("b"));
        room.onDisconnected(a);
        assertEquals(1, room.playerCount());
        ControlMessage.RoomState state = (ControlMessage.RoomState) lastMessage(b);
        assertEquals(1, state.players().size());
        // stale token unusable even if the socket could somehow speak again
        room.onText(a, ControlCodec.encode(tokenA, new ControlMessage.Chat("ghost of a")));
        assertTrue(b.text.stream().map(t -> ControlCodec.decode(t).message())
                .noneMatch(m -> m instanceof ControlMessage.ChatBroadcast));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestRoomHost" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

`FakeHubConnection` (test fixture, `src/test/java/com/openggf/net/hub/FakeHubConnection.java`):

```java
package com.openggf.net.hub;

import java.util.ArrayList;
import java.util.List;

/** Recording HubConnection test double shared by hub-layer tests. */
final class FakeHubConnection implements HubConnection {
    final List<String> text = new ArrayList<>();
    final List<byte[]> binary = new ArrayList<>();
    String closedReason;

    @Override
    public void sendText(String t) {
        text.add(t);
    }

    @Override
    public void sendBinary(byte[] d) {
        binary.add(d);
    }

    @Override
    public void close(String reason) {
        closedReason = reason;
    }

    @Override
    public String remoteHost() {
        return "127.0.0.1";
    }
}
```

```java
package com.openggf.net.hub;

import com.openggf.net.protocol.Protocol;

/** Immutable room parameters for a player-hosted direct-connect room. */
public record RoomHostConfig(String roomName, String gameId, int zone, int act, String characterPolicy,
                             String lockedCharacter, int maxPlayers, String requiredDeterminismFingerprint) {
    public RoomHostConfig {
        maxPlayers = Math.min(Math.max(maxPlayers, 1), Protocol.MAX_PLAYERS_DIRECT);
    }
}
```

```java
package com.openggf.net.hub;

import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.Protocol;
import com.openggf.net.protocol.ProtocolViolationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-connection protocol driver for a hosted room: handshake admission, session-token
 * enforcement, control dispatch, and the hub/round tick. Single-threaded by contract —
 * Task 11 pins every call onto one Netty event loop, exactly as the phase-3 master will.
 */
public final class RoomHost {
    public static final long ADMISSION_TIMEOUT_MILLIS = 5000;
    private static final int TOKEN_STRIKE_LIMIT = 3;

    private static final class Member {
        final HubConnection connection;
        final HostHandshake handshake;
        final long connectedAt;
        boolean admitted;
        int slot = -1;
        String token;
        String fingerprint;
        String displayName;
        String character;
        long lastChatMillis = Long.MIN_VALUE;
        int tokenStrikes;

        Member(HubConnection connection, HostHandshake handshake, long connectedAt) {
            this.connection = connection;
            this.handshake = handshake;
            this.connectedAt = connectedAt;
        }
    }

    private final RoomHostConfig config;
    private final PlayerIdentity hostIdentity;
    private final LongSupplier wallClockMillis;
    private final SessionTokenIssuer tokens = new SessionTokenIssuer();
    private final GhostHub hub;
    private final HostRoundEngine round;
    private final Map<HubConnection, Member> members = new LinkedHashMap<>();

    public RoomHost(RoomHostConfig config, PlayerIdentity hostIdentity, LongSupplier wallClockMillis,
                    TrackValidationProfileSource profiles) {
        this.config = config;
        this.hostIdentity = hostIdentity;
        this.wallClockMillis = wallClockMillis;
        this.hub = new GhostHub(wallClockMillis, profiles, (slot, fp, kind, detail) ->
                System.getLogger(RoomHost.class.getName()).log(System.Logger.Level.WARNING,
                        "ghost violation slot=" + slot + " fp=" + fp + " " + kind + ": " + detail));
        this.round = new HostRoundEngine(wallClockMillis, this::broadcast);
        hub.setTrack(config.gameId(), config.zone(), config.act());
    }

    public void onConnected(HubConnection connection) {
        members.put(connection, new Member(connection,
                new HostHandshake(hostIdentity.fingerprint(), config.requiredDeterminismFingerprint()),
                wallClockMillis.getAsLong()));
    }

    public void onText(HubConnection connection, String text) {
        Member member = members.get(connection);
        if (member == null) {
            connection.close("not connected");
            return;
        }
        ControlCodec.DecodedControl decoded;
        try {
            decoded = ControlCodec.decode(text);
        } catch (ProtocolViolationException e) {
            drop(member, "protocol violation: " + e.getMessage());
            return;
        }
        if (!member.admitted) {
            handleHandshake(member, decoded.message());
            return;
        }
        if (!member.token.equals(decoded.token())) {
            if (++member.tokenStrikes >= TOKEN_STRIKE_LIMIT) {
                drop(member, "session token violations");
            }
            return;
        }
        dispatch(member, decoded.message());
    }

    public void onBinary(HubConnection connection, byte[] data) {
        Member member = members.get(connection);
        if (member == null || !member.admitted) {
            connection.close("binary before admission");
            if (member != null) {
                members.remove(connection);
            }
            return;
        }
        hub.onBinary(member.slot, data);
    }

    public void onDisconnected(HubConnection connection) {
        Member member = members.remove(connection);
        if (member != null && member.admitted) {
            tokens.revoke(member.token);
            hub.removePlayer(member.slot);
            round.onPlayerLeft(member.slot);
            broadcast(new ControlMessage.RoomState(players()));
        }
    }

    public void tick() {
        long now = wallClockMillis.getAsLong();
        List<Member> loiterers = new ArrayList<>();
        for (Member member : members.values()) {
            if (!member.admitted && now - member.connectedAt > ADMISSION_TIMEOUT_MILLIS) {
                loiterers.add(member);
            }
        }
        for (Member loiterer : loiterers) {
            drop(loiterer, "handshake timeout");
        }
        hub.tick();
        round.onTick();
    }

    public boolean requestStartRound(ControlMessage.RoundConfig roundConfig) {
        if (!config.gameId().equals(roundConfig.gameId()) || config.zone() != roundConfig.zone()
                || config.act() != roundConfig.act()) {
            return false; // phase 2: one-track rooms; track vote is phase 4
        }
        if (round.startRound(roundConfig)) {
            hub.setTrack(roundConfig.gameId(), roundConfig.zone(), roundConfig.act());
            return true;
        }
        return false;
    }

    /**
     * Player-host push path (security spec §7.2): the host engine builds the profile
     * on the game thread once its level is loaded and pushes it here via
     * RaceHostServer.execute — never resetting live guest streams.
     */
    public void applyTrackValidationProfile(TrackValidationProfile profile) {
        hub.applyProfile(profile);
    }

    public int playerCount() {
        return (int) members.values().stream().filter(m -> m.admitted).count();
    }

    public List<ControlMessage.PlayerInfo> players() {
        List<ControlMessage.PlayerInfo> list = new ArrayList<>();
        for (Member member : members.values()) {
            if (member.admitted) {
                list.add(new ControlMessage.PlayerInfo(member.slot, member.fingerprint,
                        member.displayName, member.character));
            }
        }
        return list;
    }

    public HostRoundEngine round() {
        return round;
    }

    public GhostHub hub() {
        return hub;
    }

    public ControlMessage.RoomDescriptor descriptor() {
        return new ControlMessage.RoomDescriptor(config.roomName(), config.gameId(), config.zone(),
                config.act(), config.characterPolicy(), config.lockedCharacter(), config.maxPlayers(),
                false /* v1 rooms are always unverified — main spec §2 */);
    }

    private void handleHandshake(Member member, ControlMessage message) {
        HostHandshake.Step step = switch (message) {
            case ControlMessage.Hello hello -> member.handshake.onHello(hello);
            case ControlMessage.AuthProof proof -> member.handshake.onAuthProof(proof);
            default -> new HostHandshake.Reject("unexpected message before admission");
        };
        switch (step) {
            case HostHandshake.SendWelcome welcome ->
                    member.connection.sendText(ControlCodec.encode(null, welcome.welcome()));
            case HostHandshake.Reject reject -> {
                member.connection.sendText(ControlCodec.encode(null,
                        new ControlMessage.JoinRejected(reject.reason())));
                drop(member, reject.reason());
            }
            case HostHandshake.Admit admit -> admitMember(member, admit);
        }
    }

    private void admitMember(Member member, HostHandshake.Admit admit) {
        if (members.values().stream().anyMatch(m -> m.admitted && m.fingerprint.equals(admit.fingerprint()))) {
            member.connection.sendText(ControlCodec.encode(null,
                    new ControlMessage.JoinRejected("identity already connected")));
            drop(member, "duplicate identity");
            return;
        }
        int slot = lowestFreeSlot();
        if (slot < 0) {
            member.connection.sendText(ControlCodec.encode(null,
                    new ControlMessage.JoinRejected("room full")));
            drop(member, "room full");
            return;
        }
        member.admitted = true;
        member.slot = slot;
        member.fingerprint = admit.fingerprint();
        member.displayName = admit.displayName().isEmpty()
                ? admit.fingerprint().substring(0, 8) : admit.displayName();
        member.character = "LOCKED".equals(config.characterPolicy())
                ? config.lockedCharacter() : "sonic";
        member.token = tokens.issue();
        hub.addPlayer(slot, member.fingerprint, member.connection);
        member.connection.sendText(ControlCodec.encode(null, new ControlMessage.JoinAccepted(
                member.token, slot, descriptor(), round.snapshot())));
        broadcast(new ControlMessage.RoomState(players()));
    }

    private void dispatch(Member member, ControlMessage message) {
        long now = wallClockMillis.getAsLong();
        switch (message) {
            case ControlMessage.Chat chat -> {
                if (now - member.lastChatMillis < Protocol.CHAT_MIN_INTERVAL_MILLIS) {
                    return; // rate limited (main spec §6.2)
                }
                member.lastChatMillis = now;
                String text = chat.text() == null ? "" : chat.text();
                if (text.isBlank()) {
                    return;
                }
                if (text.length() > Protocol.MAX_CHAT_CHARS) {
                    text = text.substring(0, Protocol.MAX_CHAT_CHARS);
                }
                broadcast(new ControlMessage.ChatBroadcast(member.slot, member.displayName, text));
            }
            case ControlMessage.Ping ping ->
                    member.connection.sendText(ControlCodec.encode(null,
                            new ControlMessage.Pong(ping.t0ClientMillis(), now)));
            case ControlMessage.SelectCharacter select -> {
                if (round.phase() == HostRoundEngine.Phase.LOBBY
                        && !"LOCKED".equals(config.characterPolicy())) {
                    member.character = select.character();
                    broadcast(new ControlMessage.RoomState(players()));
                }
            }
            case ControlMessage.RoundConfigure configure -> {
                if (member.fingerprint.equals(hostIdentity.fingerprint())) {
                    requestStartRound(configure.config());
                }
            }
            case ControlMessage.AttemptStart start -> { /* validator tracks attempts from binary */ }
            case ControlMessage.AttemptReset reset -> { /* peers see the ghost blink via a new attemptId */ }
            case ControlMessage.AttemptFinish finish ->
                    round.onAttemptFinish(member.slot, member.displayName, member.character, finish,
                            hub.isAttemptFlagged(member.slot));
            default -> drop(member, "illegal client message " + message.getClass().getSimpleName());
        }
    }

    private void broadcast(ControlMessage message) {
        String encoded = ControlCodec.encode(null, message);
        for (Member member : members.values()) {
            if (member.admitted) {
                member.connection.sendText(encoded);
            }
        }
    }

    private int lowestFreeSlot() {
        boolean[] used = new boolean[config.maxPlayers()];
        for (Member member : members.values()) {
            if (member.admitted && member.slot < used.length) {
                used[member.slot] = true;
            }
        }
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return -1;
    }

    private void drop(Member member, String reason) {
        members.remove(member.connection);
        if (member.admitted) {
            tokens.revoke(member.token);
            hub.removePlayer(member.slot);
            round.onPlayerLeft(member.slot);
        }
        member.connection.close(reason);
        if (member.admitted) {
            broadcast(new ControlMessage.RoomState(players()));
        }
    }
}
```

Also update `TestGhostHub` to use the extracted `FakeHubConnection` fixture (delete its inner `FakeConnection` class).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn "-Dtest=com.openggf.net.hub.TestRoomHost+com.openggf.net.hub.TestGhostHub" test`
Expected: PASS (9 + 7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/RoomHostConfig.java src/main/java/com/openggf/net/hub/RoomHost.java src/test/java/com/openggf/net/hub/TestRoomHost.java src/test/java/com/openggf/net/hub/FakeHubConnection.java src/test/java/com/openggf/net/hub/TestGhostHub.java
git commit -m "feat(timeattack): RoomHost admission, token enforcement, and dispatch

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 8: ClientRaceSession — round mirror + NTP-lite clock sync

**Files:**
- Create: `src/main/java/com/openggf/net/client/ClientRaceSession.java`
- Test: `src/test/java/com/openggf/net/client/TestClientRaceSession.java`

**Interfaces:**
- Consumes: `ControlMessage` (Task 1).
- Produces `ClientRaceSession(LongSupplier clientClockMillis)`:
  - `enum Phase { LOBBY, COUNTDOWN, RUNNING, ROUND_END }`; `CLOCK_SAMPLES_TARGET = 5`.
  - `void applyJoin(ControlMessage.JoinAccepted accepted)` — seeds room descriptor, slot, and the round snapshot (mid-round joiners land in the correct phase with standings — main spec §6.1).
  - `void onControl(ControlMessage message)` — handles `RoomState`, `RoundStart`, `RoundEnd`, `StandingsDelta`, `ChatBroadcast` (ring of the last `CHAT_LINES_KEPT = 8` lines, `"name: text"`), `Pong` (clock sample), `Kick` (stores `kickReason()`); ignores everything else.
  - **NTP-lite offset (main spec §5):** each `Pong` yields sample `offset = hubMillis − (t0 + now)/2`; `long clockOffsetMillis()` = median of up to the last `CLOCK_SAMPLES_TARGET` samples (0 before any sample); `boolean needsMoreClockSamples()`; `long hubNowEstimateMillis()` = `now + offset`.
  - Phase/time queries: `Phase phase()` — message-driven base phase, refined by the local clock (COUNTDOWN→RUNNING when `hubNowEstimate ≥ countdownEndsAt`; RUNNING→ROUND_END when `> deadline`); `long remainingWindowMillis()` (0-floored; `-1` when not COUNTDOWN/RUNNING); `long remainingCountdownMillis()`; `boolean isWindowOpen()` (phase == RUNNING). The hard deadline cutoff is local: an attempt still running at the deadline is void (main spec §5) — Task 14 wires that to `TimeAttackRuntime.voidCurrentAttempt()`.
  - State: `List<StandingsRow> standings()`, `List<PlayerInfo> players()`, `List<String> chatLines()`, `ControlMessage.RoundConfig roundConfig()`, `ControlMessage.RoomDescriptor room()`, `int localSlot()`, `String kickReason()`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.client;

import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestClientRaceSession {
    private long now = 500_000;

    private ClientRaceSession session() {
        ClientRaceSession s = new ClientRaceSession(() -> now);
        s.applyJoin(new ControlMessage.JoinAccepted("tok", 1,
                new ControlMessage.RoomDescriptor("LAN", "s3k", 0, 0, "OPEN", null, 8, false),
                new ControlMessage.RoundSnapshot("LOBBY", null, 0, 0, List.of())));
        return s;
    }

    @Test
    void clockOffsetIsMedianOfSamples() {
        ClientRaceSession s = session();
        assertEquals(0, s.clockOffsetMillis());
        assertTrue(s.needsMoreClockSamples());
        // hub clock runs 1000 ahead; symmetric RTT of 100ms
        long[] jitter = {0, -20, 5, 40, -5};
        for (long j : jitter) {
            long t0 = now;
            now += 100; // RTT
            s.onControl(new ControlMessage.Pong(t0, t0 + 50 + 1000 + j));
        }
        assertFalse(s.needsMoreClockSamples());
        assertEquals(1000, s.clockOffsetMillis()); // median kills the jitter
        assertEquals(now + 1000, s.hubNowEstimateMillis());
    }

    @Test
    void roundStartDrivesPhasesThroughLocalClock() {
        ClientRaceSession s = session();
        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 300, "OPEN", null);
        s.onControl(new ControlMessage.RoundStart(cfg, now + 3000, now + 3000 + 300_000));
        assertEquals(ClientRaceSession.Phase.COUNTDOWN, s.phase());
        assertEquals(3000, s.remainingCountdownMillis());
        assertFalse(s.isWindowOpen());

        now += 3000;
        assertEquals(ClientRaceSession.Phase.RUNNING, s.phase());
        assertTrue(s.isWindowOpen());
        assertEquals(300_000, s.remainingWindowMillis());

        now += 300_001;
        assertEquals(ClientRaceSession.Phase.ROUND_END, s.phase());
        assertFalse(s.isWindowOpen());
        assertEquals(-1, s.remainingWindowMillis());
    }

    @Test
    void midRoundJoinLandsRunningWithStandings() {
        ClientRaceSession s = new ClientRaceSession(() -> now);
        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 300, "OPEN", null);
        List<ControlMessage.StandingsRow> rows =
                List.of(new ControlMessage.StandingsRow(0, "A", "sonic", 3600, 1));
        s.applyJoin(new ControlMessage.JoinAccepted("tok", 2,
                new ControlMessage.RoomDescriptor("LAN", "s3k", 0, 0, "OPEN", null, 8, false),
                new ControlMessage.RoundSnapshot("RUNNING", cfg, now - 5000, now + 100_000, rows)));
        assertEquals(ClientRaceSession.Phase.RUNNING, s.phase());
        assertEquals(1, s.standings().size());
        assertEquals(2, s.localSlot());
    }

    @Test
    void standingsRoomStateChatAndKickUpdate() {
        ClientRaceSession s = session();
        s.onControl(new ControlMessage.RoomState(
                List.of(new ControlMessage.PlayerInfo(0, "fp", "A", "sonic"))));
        assertEquals(1, s.players().size());
        s.onControl(new ControlMessage.StandingsDelta(
                List.of(new ControlMessage.StandingsRow(0, "A", "sonic", 100, 1))));
        assertEquals(100, s.standings().get(0).bestTimeFrames());
        for (int i = 0; i < 10; i++) {
            s.onControl(new ControlMessage.ChatBroadcast(0, "A", "msg" + i));
        }
        assertEquals(8, s.chatLines().size());
        assertEquals("A: msg9", s.chatLines().get(7));
        assertNull(s.kickReason());
        s.onControl(new ControlMessage.Kick("violations"));
        assertEquals("violations", s.kickReason());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.client.TestClientRaceSession" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.client;

import com.openggf.net.protocol.ControlMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Client-side mirror of the room/round state (main spec §6.1) plus NTP-lite clock
 * offset estimation (main spec §5): the deadline is the only wall-clock-sensitive
 * moment, and clock error only affects whether a last-second start squeaks in.
 */
public final class ClientRaceSession {
    public enum Phase { LOBBY, COUNTDOWN, RUNNING, ROUND_END }

    public static final int CLOCK_SAMPLES_TARGET = 5;
    public static final int CHAT_LINES_KEPT = 8;

    private final LongSupplier clientClockMillis;
    private final Deque<Long> offsetSamples = new ArrayDeque<>();
    private final Deque<String> chat = new ArrayDeque<>();

    private ControlMessage.RoomDescriptor room;
    private int localSlot = -1;
    private Phase basePhase = Phase.LOBBY;
    private ControlMessage.RoundConfig roundConfig;
    private long countdownEndsAtHub;
    private long deadlineHub;
    private List<ControlMessage.StandingsRow> standings = List.of();
    private List<ControlMessage.PlayerInfo> players = List.of();
    private String kickReason;

    public ClientRaceSession(LongSupplier clientClockMillis) {
        this.clientClockMillis = clientClockMillis;
    }

    public void applyJoin(ControlMessage.JoinAccepted accepted) {
        room = accepted.room();
        localSlot = accepted.playerSlot();
        ControlMessage.RoundSnapshot snapshot = accepted.round();
        basePhase = Phase.valueOf(snapshot.phase());
        roundConfig = snapshot.config();
        countdownEndsAtHub = snapshot.countdownEndsAtHubMillis();
        deadlineHub = snapshot.deadlineHubMillis();
        standings = List.copyOf(snapshot.standings());
    }

    public void onControl(ControlMessage message) {
        switch (message) {
            case ControlMessage.RoomState state -> players = List.copyOf(state.players());
            case ControlMessage.RoundStart start -> {
                basePhase = Phase.COUNTDOWN;
                roundConfig = start.config();
                countdownEndsAtHub = start.countdownEndsAtHubMillis();
                deadlineHub = start.deadlineHubMillis();
                standings = List.of();
            }
            case ControlMessage.RoundEnd end -> {
                basePhase = Phase.ROUND_END;
                standings = List.copyOf(end.finalStandings());
            }
            case ControlMessage.StandingsDelta delta -> standings = List.copyOf(delta.rows());
            case ControlMessage.ChatBroadcast broadcast -> {
                chat.addLast(broadcast.displayName() + ": " + broadcast.text());
                while (chat.size() > CHAT_LINES_KEPT) {
                    chat.removeFirst();
                }
            }
            case ControlMessage.Pong pong -> {
                long now = clientClockMillis.getAsLong();
                offsetSamples.addLast(pong.hubMillis() - (pong.t0ClientMillis() + now) / 2);
                while (offsetSamples.size() > CLOCK_SAMPLES_TARGET) {
                    offsetSamples.removeFirst();
                }
            }
            case ControlMessage.Kick kick -> kickReason = kick.reason();
            default -> { /* not session state */ }
        }
    }

    public long clockOffsetMillis() {
        if (offsetSamples.isEmpty()) {
            return 0;
        }
        long[] sorted = offsetSamples.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    public boolean needsMoreClockSamples() {
        return offsetSamples.size() < CLOCK_SAMPLES_TARGET;
    }

    public long hubNowEstimateMillis() {
        return clientClockMillis.getAsLong() + clockOffsetMillis();
    }

    public Phase phase() {
        long hubNow = hubNowEstimateMillis();
        if (basePhase == Phase.COUNTDOWN && hubNow >= countdownEndsAtHub) {
            return hubNow > deadlineHub ? Phase.ROUND_END : Phase.RUNNING;
        }
        if (basePhase == Phase.RUNNING && hubNow > deadlineHub) {
            return Phase.ROUND_END;
        }
        return basePhase;
    }

    public long remainingCountdownMillis() {
        return phase() == Phase.COUNTDOWN ? Math.max(0, countdownEndsAtHub - hubNowEstimateMillis()) : 0;
    }

    public long remainingWindowMillis() {
        Phase current = phase();
        if (current != Phase.COUNTDOWN && current != Phase.RUNNING) {
            return -1;
        }
        return Math.max(0, deadlineHub - hubNowEstimateMillis());
    }

    public boolean isWindowOpen() {
        return phase() == Phase.RUNNING;
    }

    public List<ControlMessage.StandingsRow> standings() {
        return standings;
    }

    public List<ControlMessage.PlayerInfo> players() {
        return players;
    }

    public List<String> chatLines() {
        return new ArrayList<>(chat);
    }

    public ControlMessage.RoundConfig roundConfig() {
        return roundConfig;
    }

    public ControlMessage.RoomDescriptor room() {
        return room;
    }

    public int localSlot() {
        return localSlot;
    }

    public String kickReason() {
        return kickReason;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.client.TestClientRaceSession" test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/client/ClientRaceSession.java src/test/java/com/openggf/net/client/TestClientRaceSession.java
git commit -m "feat(timeattack): client race session mirror with NTP-lite clock sync

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 9: RemoteGhostPlayback + RemoteGhostRegistry — jitter buffer, catch-up, snap

**Files:**
- Create: `src/main/java/com/openggf/net/client/RemoteGhostPlayback.java`
- Create: `src/main/java/com/openggf/net/client/RemoteGhostRegistry.java`
- Test: `src/test/java/com/openggf/net/client/TestRemoteGhostPlayback.java`

**Interfaces:**
- Consumes: `GhostFrame`/`GhostFrameCodec` (phase 1), `GhostPackets.{Aggregate,AggregateEntry}` (Task 2), `ControlMessage.PlayerInfo` (Task 1).
- Produces:
  - `RemoteGhostPlayback` — one per remote ghost; implements the latency model of main spec §5 exactly:
    - Constants: `INITIAL_DELAY_FRAMES = 9` (~150 ms), `MIN_DELAY_FRAMES = 6`, `MAX_DELAY_FRAMES = 30`, `CATCHUP_SLACK_FRAMES = 15` (backlog > D+250 ms → 2×), `SNAP_BACKLOG_FRAMES = 60` (backlog > ~1 s → snap to `newest − D`), `EXTRAPOLATE_MAX_FRAMES = 6` (~100 ms), `DELAY_GROW_FRAMES = 3`, `DELAY_SHRINK_CLEAN_STREAK = 600` (grow fast on stalls, shrink slowly).
    - `void onEntry(GhostPackets.AggregateEntry entry)` — a NEW (higher) attemptId resets the buffer and cursor (peers see the ghost blink back to the start on retry); stale (lower) attemptIds are dropped silently; frames stored by frameIndex.
    - `record RenderState(GhostFrame frame, float opacityScale, boolean snapped)` — `opacityScale` is 1.0 normally, 0.5 while frozen after extrapolation runs out (main spec §5.3); `snapped` true on the advance that performed a snap (Task 14 may trigger the fade-out/in there).
    - `Optional<RenderState> advance()` — called once per render frame. Playback starts once `INITIAL_DELAY` frames buffer. Never plays backlog at 1×: backlog > `delay + CATCHUP_SLACK` → advance 2 frames; backlog > `SNAP_BACKLOG` → snap. Buffer dry → extrapolate with last per-frame velocity up to `EXTRAPOLATE_MAX_FRAMES`, then freeze at half opacity. Each dry advance grows `delay` by `DELAY_GROW_FRAMES` (capped); `DELAY_SHRINK_CLEAN_STREAK` consecutive clean advances shrink it by 1 (floored).
    - `int delayFrames()`, `boolean isStalled()`.
  - `RemoteGhostRegistry`:
    - `void onAggregate(GhostPackets.Aggregate aggregate)` — routes entries to per-slot playbacks (creating on first sight).
    - `void onRoomState(List<ControlMessage.PlayerInfo> players)` — retains names/characters, removes playbacks for departed slots.
    - `void reset()` — drops ALL playback state (roster kept). Called on `RoundStart` (Task 14): per-round attemptIds restart at 1, and `RemoteGhostPlayback`'s stale-drop rule (`attemptId < current`) would otherwise silently discard the entire next round's streams.
    - `record RemoteGhost(int slot, String displayName, String character, RemoteGhostPlayback.RenderState state)`; `List<RemoteGhost> advanceAll(int excludeSlot)` — advances every playback (skipping the local slot) and returns drawable ghosts.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.client;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestRemoteGhostPlayback {
    private static byte[] frames(int startIndex, int count) {
        byte[] data = new byte[count * GhostFrameCodec.BYTES];
        for (int i = 0; i < count; i++) {
            // x tracks the frame index directly so assertions can read position as index
            GhostFrameCodec.encode(new GhostFrame(startIndex + i, 256, 1, false, false, false, 2, false),
                    data, i * GhostFrameCodec.BYTES);
        }
        return data;
    }

    private static GhostPackets.AggregateEntry entry(int attemptId, int startIndex, int count) {
        return new GhostPackets.AggregateEntry(0, attemptId, startIndex, count, frames(startIndex, count));
    }

    /** Feeds frames so the buffer holds exactly [0, upTo). */
    private static RemoteGhostPlayback fed(int upTo) {
        RemoteGhostPlayback playback = new RemoteGhostPlayback();
        for (int i = 0; i < upTo; i += 3) {
            playback.onEntry(entry(1, i, Math.min(3, upTo - i)));
        }
        return playback;
    }

    @Test
    void waitsForInitialBufferThenPlaysFromFrameZero() {
        RemoteGhostPlayback playback = fed(RemoteGhostPlayback.INITIAL_DELAY_FRAMES - 3);
        assertTrue(playback.advance().isEmpty()); // not enough buffered yet
        playback.onEntry(entry(1, RemoteGhostPlayback.INITIAL_DELAY_FRAMES - 3, 3));
        RemoteGhostPlayback.RenderState first = playback.advance().orElseThrow();
        assertEquals(0, first.frame().x());
        assertEquals(1.0f, first.opacityScale());
        assertEquals(1, playback.advance().orElseThrow().frame().x()); // 1 frame per advance
    }

    @Test
    void catchesUpAtDoubleSpeedOnBacklog() {
        RemoteGhostPlayback playback = fed(9);
        playback.advance(); // cursor 0
        // burst: buffer runs far ahead (backlog > delay + slack)
        for (int i = 9; i < 60; i += 3) {
            playback.onEntry(entry(1, i, 3));
        }
        RemoteGhostPlayback.RenderState state = playback.advance().orElseThrow();
        assertEquals(2, state.frame().x()); // stepped 2, not 1
        assertFalse(state.snapped());
    }

    @Test
    void snapsOnHugeBacklog() {
        RemoteGhostPlayback playback = fed(9);
        playback.advance(); // cursor 0
        for (int i = 9; i < 120; i += 3) {
            playback.onEntry(entry(1, i, 3)); // > SNAP_BACKLOG_FRAMES ahead
        }
        RemoteGhostPlayback.RenderState state = playback.advance().orElseThrow();
        assertTrue(state.snapped());
        assertEquals(119 - playback.delayFrames(), state.frame().x());
    }

    @Test
    void extrapolatesThenFreezesOnStall() {
        RemoteGhostPlayback playback = fed(RemoteGhostPlayback.INITIAL_DELAY_FRAMES);
        for (int i = 0; i < RemoteGhostPlayback.INITIAL_DELAY_FRAMES; i++) {
            playback.advance(); // drain to the newest frame
        }
        int lastRealX = RemoteGhostPlayback.INITIAL_DELAY_FRAMES - 1;
        // stall: extrapolate up to EXTRAPOLATE_MAX_FRAMES with velocity +1/frame
        for (int k = 1; k <= RemoteGhostPlayback.EXTRAPOLATE_MAX_FRAMES; k++) {
            RemoteGhostPlayback.RenderState state = playback.advance().orElseThrow();
            assertEquals(lastRealX + k, state.frame().x(), "extrapolation step " + k);
            assertEquals(1.0f, state.opacityScale());
        }
        RemoteGhostPlayback.RenderState frozen = playback.advance().orElseThrow();
        assertEquals(0.5f, frozen.opacityScale()); // freeze + fade (main spec §5.3)
        assertTrue(playback.isStalled());
        assertTrue(playback.delayFrames() > RemoteGhostPlayback.INITIAL_DELAY_FRAMES); // grew on stall
    }

    @Test
    void newAttemptResetsBufferAndCursor() {
        RemoteGhostPlayback playback = fed(30);
        playback.advance();
        playback.onEntry(entry(2, 0, 3)); // retry: new attempt
        assertTrue(playback.advance().isEmpty()); // rebuffering for the new attempt
        playback.onEntry(entry(2, 3, 3));
        playback.onEntry(entry(2, 6, 3));
        assertEquals(0, playback.advance().orElseThrow().frame().x());
        // stale frames from attempt 1 are ignored
        playback.onEntry(entry(1, 30, 3));
        assertEquals(1, playback.advance().orElseThrow().frame().x());
    }

    @Test
    void registryRoutesAdvancesAndExcludesLocalSlot() {
        RemoteGhostRegistry registry = new RemoteGhostRegistry();
        registry.onRoomState(List.of(
                new com.openggf.net.protocol.ControlMessage.PlayerInfo(0, "fp0", "A", "sonic"),
                new com.openggf.net.protocol.ControlMessage.PlayerInfo(1, "fp1", "B", "tails")));
        byte[] enough = frames(0, 3);
        for (int i = 0; i < 12; i += 3) {
            registry.onAggregate(new GhostPackets.Aggregate(i, List.of(
                    new GhostPackets.AggregateEntry(0, 1, i, 3, frames(i, 3)),
                    new GhostPackets.AggregateEntry(1, 1, i, 3, frames(i, 3)))));
        }
        List<RemoteGhostRegistry.RemoteGhost> ghosts = registry.advanceAll(1);
        assertEquals(1, ghosts.size()); // slot 1 (local) excluded
        assertEquals("A", ghosts.get(0).displayName());
        assertEquals("sonic", ghosts.get(0).character());

        registry.onRoomState(List.of(
                new com.openggf.net.protocol.ControlMessage.PlayerInfo(1, "fp1", "B", "tails")));
        assertTrue(registry.advanceAll(1).isEmpty()); // slot 0 left the room
    }

    @Test
    void resetDropsPlaybacksSoNextRoundAttemptIdsRestart() {
        RemoteGhostRegistry registry = new RemoteGhostRegistry();
        registry.onRoomState(List.of(
                new com.openggf.net.protocol.ControlMessage.PlayerInfo(0, "fp0", "A", "sonic")));
        for (int i = 0; i < 12; i += 3) { // round 1: attempt 5
            registry.onAggregate(new GhostPackets.Aggregate(i, List.of(
                    new GhostPackets.AggregateEntry(0, 5, i, 3, frames(i, 3)))));
        }
        assertFalse(registry.advanceAll(-1).isEmpty());

        registry.reset(); // RoundStart: next round's attemptIds restart at 1
        for (int i = 0; i < 12; i += 3) {
            registry.onAggregate(new GhostPackets.Aggregate(i, List.of(
                    new GhostPackets.AggregateEntry(0, 1, i, 3, frames(i, 3)))));
        }
        // Without reset, attempt 1 < attempt 5 would be stale-dropped everywhere.
        assertEquals("A", registry.advanceAll(-1).get(0).displayName());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.client.TestRemoteGhostPlayback" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.client;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.protocol.GhostPackets;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptive jitter-buffered playback for one remote ghost (main spec §5):
 * frame-indexed, renders delay D behind newest; 2× catch-up on backlog, snap on
 * ~1 s backlog, extrapolate ≤ ~100 ms on stall then freeze at half opacity.
 * Frames arrive at full 60 Hz fidelity so playback is exact, never interpolated.
 */
public final class RemoteGhostPlayback {
    public static final int INITIAL_DELAY_FRAMES = 9;
    public static final int MIN_DELAY_FRAMES = 6;
    public static final int MAX_DELAY_FRAMES = 30;
    public static final int CATCHUP_SLACK_FRAMES = 15;
    public static final int SNAP_BACKLOG_FRAMES = 60;
    public static final int EXTRAPOLATE_MAX_FRAMES = 6;
    public static final int DELAY_GROW_FRAMES = 3;
    public static final int DELAY_SHRINK_CLEAN_STREAK = 600;

    public record RenderState(GhostFrame frame, float opacityScale, boolean snapped) {
    }

    private final Map<Integer, GhostFrame> frames = new HashMap<>();
    private int attemptId = Integer.MIN_VALUE;
    private int newestIndex = -1;
    private int cursor = -1;
    private int delay = INITIAL_DELAY_FRAMES;
    private int extrapolated;
    private int velocityX;
    private int velocityY;
    private int cleanStreak;
    private boolean stalled;

    public void onEntry(GhostPackets.AggregateEntry entry) {
        if (entry.attemptId() < attemptId) {
            return; // stale attempt: dropped silently at every layer (main spec §7)
        }
        if (entry.attemptId() > attemptId) {
            attemptId = entry.attemptId();
            frames.clear();
            newestIndex = -1;
            cursor = -1;
            extrapolated = 0;
            stalled = false;
        }
        for (int i = 0; i < entry.frameCount(); i++) {
            int index = entry.startFrameIndex() + i;
            frames.put(index, GhostFrameCodec.decode(entry.frameData(), i * GhostFrameCodec.BYTES));
            newestIndex = Math.max(newestIndex, index);
        }
    }

    public Optional<RenderState> advance() {
        if (cursor < 0) {
            if (newestIndex + 1 < delay) {
                return Optional.empty(); // still filling the initial jitter buffer
            }
            cursor = 0;
            return clean(cursor, false);
        }
        int backlog = newestIndex - cursor;
        if (backlog > SNAP_BACKLOG_FRAMES) {
            cursor = newestIndex - delay;
            return clean(cursor, true); // snap to newest − D with a fade (main spec §5.4)
        }
        int step = backlog > delay + CATCHUP_SLACK_FRAMES ? 2 : 1;
        if (cursor + step <= newestIndex) {
            cursor += step;
            return clean(cursor, false);
        }
        return dry();
    }

    public int delayFrames() {
        return delay;
    }

    public boolean isStalled() {
        return stalled;
    }

    private Optional<RenderState> clean(int index, boolean snapped) {
        GhostFrame frame = frames.get(index);
        if (frame == null) {
            return dry(); // unreachable with contiguous streams; total anyway
        }
        GhostFrame previous = frames.get(index - 1);
        if (previous != null) {
            velocityX = frame.x() - previous.x();
            velocityY = frame.y() - previous.y();
        }
        extrapolated = 0;
        stalled = false;
        if (++cleanStreak >= DELAY_SHRINK_CLEAN_STREAK) {
            cleanStreak = 0;
            delay = Math.max(MIN_DELAY_FRAMES, delay - 1); // shrink slowly (main spec §5.2)
        }
        frames.keySet().removeIf(k -> k < index - 4);
        return Optional.of(new RenderState(frame, 1.0f, snapped));
    }

    private Optional<RenderState> dry() {
        GhostFrame last = frames.get(cursor);
        if (last == null) {
            return Optional.empty();
        }
        cleanStreak = 0;
        delay = Math.min(MAX_DELAY_FRAMES, delay + DELAY_GROW_FRAMES); // grow fast on stalls
        if (extrapolated < EXTRAPOLATE_MAX_FRAMES) {
            extrapolated++;
            GhostFrame projected = new GhostFrame(
                    clampU16(last.x() + velocityX * extrapolated),
                    clampU16(last.y() + velocityY * extrapolated),
                    last.mappingFrame(), last.hFlip(), last.vFlip(), last.finished(),
                    last.priorityBucket(), last.highPriority());
            return Optional.of(new RenderState(projected, 1.0f, false));
        }
        stalled = true; // freeze + fade to half opacity with a lag marker (main spec §5.3)
        return Optional.of(new RenderState(last, 0.5f, false));
    }

    private static int clampU16(int value) {
        return Math.min(Math.max(value, 0), 0xFFFF);
    }
}
```

```java
package com.openggf.net.client;

import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Per-slot playback routing + roster names for the standings/nameplates. */
public final class RemoteGhostRegistry {
    public record RemoteGhost(int slot, String displayName, String character,
                              RemoteGhostPlayback.RenderState state) {
    }

    private record Roster(String displayName, String character) {
    }

    private final Map<Integer, RemoteGhostPlayback> playbacks = new LinkedHashMap<>();
    private final Map<Integer, Roster> roster = new LinkedHashMap<>();

    public void onAggregate(GhostPackets.Aggregate aggregate) {
        for (GhostPackets.AggregateEntry entry : aggregate.entries()) {
            playbacks.computeIfAbsent(entry.playerSlot(), s -> new RemoteGhostPlayback()).onEntry(entry);
        }
    }

    public void onRoomState(List<ControlMessage.PlayerInfo> players) {
        roster.clear();
        for (ControlMessage.PlayerInfo player : players) {
            roster.put(player.slot(), new Roster(player.displayName(), player.character()));
        }
        playbacks.keySet().removeIf(slot -> !roster.containsKey(slot));
    }

    /** New round: attemptIds restart at 1, so all playback state must drop (roster kept). */
    public void reset() {
        playbacks.clear();
    }

    public List<RemoteGhost> advanceAll(int excludeSlot) {
        List<RemoteGhost> ghosts = new ArrayList<>();
        for (Map.Entry<Integer, RemoteGhostPlayback> entry : playbacks.entrySet()) {
            if (entry.getKey() == excludeSlot) {
                continue;
            }
            Optional<RemoteGhostPlayback.RenderState> state = entry.getValue().advance();
            if (state.isPresent()) {
                Roster info = roster.getOrDefault(entry.getKey(), new Roster("?", "sonic"));
                ghosts.add(new RemoteGhost(entry.getKey(), info.displayName(), info.character(), state.get()));
            }
        }
        return ghosts;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.client.TestRemoteGhostPlayback" test`
Expected: PASS (7 tests). If `catchesUpAtDoubleSpeedOnBacklog` or `snapsOnHugeBacklog` disagree with the implementation by one frame, fix the TEST arithmetic against the implemented step order (cursor moves BEFORE the frame is read) — the invariants that must hold are: 2× only above `delay + CATCHUP_SLACK`, snap only above `SNAP_BACKLOG`, and never 1×-through-backlog.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/client/RemoteGhostPlayback.java src/main/java/com/openggf/net/client/RemoteGhostRegistry.java src/test/java/com/openggf/net/client/TestRemoteGhostPlayback.java
git commit -m "feat(timeattack): jitter-buffered remote ghost playback with catch-up and snap

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 10: GhostStreamPublisher — sampling, batching, stream hash

**Files:**
- Create: `src/main/java/com/openggf/net/client/GhostStreamPublisher.java`
- Test: `src/test/java/com/openggf/net/client/TestGhostStreamPublisher.java`

**Interfaces:**
- Consumes: `GhostFrame`/`GhostFrameCodec` (phase 1), `GhostPackets.encodeFrames` (Task 2).
- Produces `GhostStreamPublisher(BinarySender sender)` with nested `@FunctionalInterface BinarySender { void sendBinary(byte[] data); }`:
  - `void beginAttempt(int attemptId)` — attemptIds must be strictly increasing across retries (the validator and playback key on it); resets the frame counter and the running SHA-256.
  - `void onFrame(GhostFrame frame)` — appends to the current 3-frame batch; on the 3rd frame, encodes and sends one `GhostFrames` packet (60 Hz sampling → 20 packets/s — main spec §4.4). Also feeds the frame's 7 bytes into the running stream digest.
  - `void finishAttempt()` — flushes a partial trailing batch (1–2 frames) and seals the digest; `void abandonAttempt()` — drops the partial batch (retry: peers see the new attemptId, stale frames are dropped everywhere).
  - `byte[] streamHashSha256()` — hash over ALL 7-byte frame encodings of the attempt in order; this is `AttemptFinish.ghostStreamHashHex`'s source (security spec §6.5: lets the verifier cross-check the submitted ghost stream against replay output). Callable after `finishAttempt()`.
  - `int framesPublished()`, `int currentAttemptId()`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.client;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostStreamPublisher {
    private final List<byte[]> sent = new ArrayList<>();
    private final GhostStreamPublisher publisher = new GhostStreamPublisher(sent::add);

    private static GhostFrame frame(int x) {
        return new GhostFrame(x, 100, 1, false, false, false, 2, false);
    }

    @Test
    void batchesThreeFramesPerPacketWithRunningIndices() {
        publisher.beginAttempt(1);
        for (int i = 0; i < 7; i++) {
            publisher.onFrame(frame(100 + i));
        }
        assertEquals(2, sent.size()); // 6 frames shipped, 1 pending
        GhostPackets.FramesBatch first = GhostPackets.decodeFrames(sent.get(0));
        GhostPackets.FramesBatch second = GhostPackets.decodeFrames(sent.get(1));
        assertEquals(0, first.startFrameIndex());
        assertEquals(3, second.startFrameIndex());
        assertEquals(1, second.attemptId());

        publisher.finishAttempt();
        assertEquals(3, sent.size()); // trailing single frame flushed
        assertEquals(1, GhostPackets.decodeFrames(sent.get(2)).frameCount());
        assertEquals(7, publisher.framesPublished());
    }

    @Test
    void streamHashCoversExactFrameBytes() throws Exception {
        publisher.beginAttempt(3);
        publisher.onFrame(frame(1));
        publisher.onFrame(frame(2));
        publisher.finishAttempt();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] expectedBytes = new byte[14];
        com.openggf.game.ghost.GhostFrameCodec.encode(frame(1), expectedBytes, 0);
        com.openggf.game.ghost.GhostFrameCodec.encode(frame(2), expectedBytes, 7);
        assertArrayEquals(digest.digest(expectedBytes), publisher.streamHashSha256());
    }

    @Test
    void abandonDropsPartialBatchAndNewAttemptRestartsIndices() {
        publisher.beginAttempt(1);
        publisher.onFrame(frame(1));
        publisher.abandonAttempt();
        assertTrue(sent.isEmpty());

        publisher.beginAttempt(2);
        publisher.onFrame(frame(1));
        publisher.onFrame(frame(2));
        publisher.onFrame(frame(3));
        GhostPackets.FramesBatch batch = GhostPackets.decodeFrames(sent.get(0));
        assertEquals(2, batch.attemptId());
        assertEquals(0, batch.startFrameIndex());
        assertEquals(2, publisher.currentAttemptId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.client.TestGhostStreamPublisher" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.client;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.protocol.GhostPackets;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Samples the local player's resolved render state each frame and ships 3-frame
 * GhostFrames packets (main spec §6.1/§4.4). Also accumulates the attempt's
 * ghost-stream hash for AttemptFinish (security spec §6.5).
 */
public final class GhostStreamPublisher {
    @FunctionalInterface
    public interface BinarySender {
        void sendBinary(byte[] data);
    }

    private final BinarySender sender;
    private final byte[] batch = new byte[GhostPackets.MAX_UPSTREAM_FRAMES_PER_PACKET * GhostFrameCodec.BYTES];
    private int batchCount;
    private int attemptId;
    private int nextFrameIndex;
    private MessageDigest digest = newDigest();
    private byte[] sealedHash;

    public GhostStreamPublisher(BinarySender sender) {
        this.sender = sender;
    }

    public void beginAttempt(int newAttemptId) {
        attemptId = newAttemptId;
        nextFrameIndex = 0;
        batchCount = 0;
        digest = newDigest();
        sealedHash = null;
    }

    public void onFrame(GhostFrame frame) {
        GhostFrameCodec.encode(frame, batch, batchCount * GhostFrameCodec.BYTES);
        digest.update(batch, batchCount * GhostFrameCodec.BYTES, GhostFrameCodec.BYTES);
        batchCount++;
        if (batchCount == GhostPackets.MAX_UPSTREAM_FRAMES_PER_PACKET) {
            flush();
        }
    }

    public void finishAttempt() {
        flush();
        sealedHash = digest.digest();
    }

    public void abandonAttempt() {
        batchCount = 0;
    }

    public byte[] streamHashSha256() {
        if (sealedHash == null) {
            throw new IllegalStateException("attempt not finished");
        }
        return sealedHash.clone();
    }

    public int framesPublished() {
        return nextFrameIndex;
    }

    public int currentAttemptId() {
        return attemptId;
    }

    private void flush() {
        if (batchCount == 0) {
            return;
        }
        byte[] frameData = new byte[batchCount * GhostFrameCodec.BYTES];
        System.arraycopy(batch, 0, frameData, 0, frameData.length);
        sender.sendBinary(GhostPackets.encodeFrames(attemptId, nextFrameIndex, frameData));
        nextFrameIndex += batchCount;
        batchCount = 0;
    }
}
```

Note: `framesPublished()` counts SENT frames; frames still in a partial batch are included only after `flush`. The digest, however, must cover every `onFrame` call of a finished attempt — since `finishAttempt` flushes first, sent-vs-digested coverage is identical for finished attempts. `abandonAttempt` leaves the digest dirty, which is fine: the next `beginAttempt` resets it, and hashes are only read for finished attempts.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.client.TestGhostStreamPublisher" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/client/GhostStreamPublisher.java src/test/java/com/openggf/net/client/TestGhostStreamPublisher.java
git commit -m "feat(timeattack): ghost stream publisher with batching and stream hash

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 11: RaceHostServer — Netty WebSocket server with protocol hygiene

**Files:**
- Modify: `pom.xml` (add Netty dependency)
- Create: `src/main/java/com/openggf/net/host/RaceHostServer.java`
- Create: `src/main/java/com/openggf/net/host/RaceHostChannelHandler.java`
- Test: `src/test/java/com/openggf/net/host/TestRaceHostServer.java`

**Interfaces:**
- Consumes: `RoomHost`/`RoomHostConfig`/`HubConnection` (Task 7), `PlayerIdentity`, `TrackValidationProfileSource`, `Protocol`.
- Produces:
  - `RaceHostServer.start(int port, RoomHostConfig config, PlayerIdentity hostIdentity, TrackValidationProfileSource profiles)` → running server (port 0 = ephemeral; `int port()` returns the bound port). `void execute(Runnable task)` marshals host-UI calls (e.g. `room().requestStartRound`) onto the room's event loop; `RoomHost room()` (touch ONLY via `execute`); `void close()` (graceful shutdown).
  - Threading (main spec §6.2): ONE `NioEventLoopGroup(1)` — the room is pinned to a single event-loop thread; `RoomHost.tick()` scheduled there at `TICK_MILLIS = 50` (20 Hz).
  - WebSocket endpoint: path `/race`, plaintext `ws://` (TLS is phase 3).
  - Protocol hygiene in the pipeline (security spec §7.3):
    - `HttpServerCodec` + `HttpObjectAggregator(8192)` + `WebSocketServerProtocolHandler("/race", null, true, Protocol.MAX_CONTROL_BYTES)` — frames above the cap are rejected pre-parse by Netty — followed by `WebSocketFrameAggregator(Protocol.MAX_CONTROL_BYTES)`: fragmented (continuation) frames reassemble under the SAME cumulative cap, so a client cannot stream unbounded non-final fragments (over-cap → `TooLongFrameException` → `exceptionCaught` → close). Without the aggregator, fragments would also be silently dropped by the channel handler, which only matches complete Text/Binary frames.
    - `IdleStateHandler(60, 0, 0)` → idle read timeout closes the connection.
    - Per-IP concurrent connection cap `MAX_CONNECTIONS_PER_IP = 4` (checked at channelActive, over-cap connections closed immediately).
    - Per-connection message rate cap: token bucket `MSG_RATE_BURST = 120`, refill `MSG_RATE_PER_SECOND = 60` counting text+binary frames pre-dispatch; empty bucket → close (`message flood`). Legitimate load is ~20 binary + a few control frames/s. Combined with the per-IP connection cap this bounds per-IP throughput at 4× the per-connection cap — a dedicated per-IP rate accounting rig (security spec §7.3) lands with the master's shared infrastructure in phase 3.
    - Handshake timeout is `RoomHost.ADMISSION_TIMEOUT_MILLIS` via the existing tick sweep.
- Maven: add to `pom.xml` `<dependencies>` (a single-artifact dependency keeps the tree small; Netty's `all` bundle is acceptable here because only the host/master path touches it):

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-all</artifactId>
    <version>4.1.115.Final</version>
</dependency>
```

- [ ] **Step 1: Write the failing test** (real sockets on localhost, JDK WebSocket as the probe client)

```java
package com.openggf.net.host;

import com.openggf.net.client.ClientHandshake;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class TestRaceHostServer {
    private static final String FP = "0.6:cafe1234";
    private RaceHostServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** Minimal blocking WS probe that accumulates text frames into a queue. */
    private static final class Probe implements WebSocket.Listener {
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        volatile boolean closed;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                texts.add(partial.toString());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed = true;
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed = true; // abrupt server close may surface as an error, not a close frame
        }
    }

    private WebSocket connect(Probe probe) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/race"), probe)
                .get(10, TimeUnit.SECONDS);
    }

    private static ControlMessage awaitMessage(Probe probe, Class<?> type) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String text = probe.texts.poll(250, TimeUnit.MILLISECONDS);
            if (text != null) {
                ControlMessage message = ControlCodec.decode(text).message();
                if (type.isInstance(message)) {
                    return message;
                }
            }
        }
        throw new AssertionError("timed out waiting for " + type.getSimpleName());
    }

    @Test
    void fullHandshakeOverRealSocketAdmits(@TempDir Path dir) throws Exception {
        PlayerIdentity hostIdentity = PlayerIdentity.loadOrCreate(dir.resolve("host"));
        server = RaceHostServer.start(0, new RoomHostConfig("LAN", "s3k", 0, 0, "OPEN", null, 8, FP),
                hostIdentity, TrackValidationProfileSource.none());
        assertTrue(server.port() > 0);

        PlayerIdentity clientIdentity = PlayerIdentity.loadOrCreate(dir.resolve("client"));
        ClientHandshake handshake = new ClientHandshake(clientIdentity, "Probe", FP);
        Probe probe = new Probe();
        WebSocket ws = connect(probe);
        ws.sendText(ControlCodec.encode(null, handshake.hello()), true).join();
        ControlMessage.Welcome welcome = (ControlMessage.Welcome) awaitMessage(probe, ControlMessage.Welcome.class);
        ws.sendText(ControlCodec.encode(null, handshake.onWelcome(welcome)), true).join();
        ControlMessage.JoinAccepted accepted =
                (ControlMessage.JoinAccepted) awaitMessage(probe, ControlMessage.JoinAccepted.class);
        assertEquals(0, accepted.playerSlot());
        assertFalse(accepted.room().verified());
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void garbageTextClosesConnection(@TempDir Path dir) throws Exception {
        PlayerIdentity hostIdentity = PlayerIdentity.loadOrCreate(dir.resolve("host"));
        server = RaceHostServer.start(0, new RoomHostConfig("LAN", "s3k", 0, 0, "OPEN", null, 8, FP),
                hostIdentity, TrackValidationProfileSource.none());
        Probe probe = new Probe();
        WebSocket ws = connect(probe);
        ws.sendText("not json", true).join();
        awaitClosed(probe);
    }

    @Test
    void oversizedFragmentedTextClosesConnection(@TempDir Path dir) throws Exception {
        PlayerIdentity hostIdentity = PlayerIdentity.loadOrCreate(dir.resolve("host"));
        server = RaceHostServer.start(0, new RoomHostConfig("LAN", "s3k", 0, 0, "OPEN", null, 8, FP),
                hostIdentity, TrackValidationProfileSource.none());
        Probe probe = new Probe();
        WebSocket ws = connect(probe);
        // Each fragment is under the per-frame cap; the CUMULATIVE size (12000) is over
        // MAX_CONTROL_BYTES — only the WebSocketFrameAggregator's cap can reject this.
        String fragment = "x".repeat(6000);
        ws.sendText(fragment, false).join();
        ws.sendText(fragment, true).join();
        awaitClosed(probe);
    }

    private static void awaitClosed(Probe probe) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!probe.closed && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(probe.closed);
    }

    @Test
    void hostCanStartRoundViaExecute(@TempDir Path dir) throws Exception {
        PlayerIdentity hostIdentity = PlayerIdentity.loadOrCreate(dir.resolve("host"));
        server = RaceHostServer.start(0, new RoomHostConfig("LAN", "s3k", 0, 0, "OPEN", null, 8, FP),
                hostIdentity, TrackValidationProfileSource.none());
        PlayerIdentity clientIdentity = PlayerIdentity.loadOrCreate(dir.resolve("client"));
        ClientHandshake handshake = new ClientHandshake(clientIdentity, "Probe", FP);
        Probe probe = new Probe();
        WebSocket ws = connect(probe);
        ws.sendText(ControlCodec.encode(null, handshake.hello()), true).join();
        ControlMessage.Welcome welcome = (ControlMessage.Welcome) awaitMessage(probe, ControlMessage.Welcome.class);
        ws.sendText(ControlCodec.encode(null, handshake.onWelcome(welcome)), true).join();
        awaitMessage(probe, ControlMessage.JoinAccepted.class);

        server.execute(() -> server.room().requestStartRound(
                new ControlMessage.RoundConfig("s3k", 0, 0, 60, "OPEN", null)));
        ControlMessage.RoundStart start = (ControlMessage.RoundStart) awaitMessage(probe, ControlMessage.RoundStart.class);
        assertTrue(start.deadlineHubMillis() > start.countdownEndsAtHubMillis());
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}
```

- [ ] **Step 2: Add the Netty dependency, run test to verify it fails**

Add the `netty-all` dependency to `pom.xml` (place it beside the existing `jackson-databind` dependency).
Run: `mvn "-Dtest=com.openggf.net.host.TestRaceHostServer" test`
Expected: COMPILATION ERROR (RaceHostServer not found). If the pom edit is wrong, this fails earlier at dependency resolution — fix before proceeding.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.host;

import com.openggf.net.hub.RoomHost;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Player-host WebSocket server (main spec §6.1): a single-threaded Netty event loop
 * hosting one RoomHost — the same GhostHub/RoomHost the phase-3 master relay runs.
 * Plaintext ws:// in phase 2 (LAN/VPN context — security spec §7.3).
 */
public final class RaceHostServer {
    public static final long TICK_MILLIS = 50; // 20 Hz hub tick (main spec §4.1)
    public static final int MAX_CONNECTIONS_PER_IP = 4;

    private final EventLoopGroup group;
    private final Channel serverChannel;
    private final RoomHost room;

    private RaceHostServer(EventLoopGroup group, Channel serverChannel, RoomHost room) {
        this.group = group;
        this.serverChannel = serverChannel;
        this.room = room;
    }

    public static RaceHostServer start(int port, RoomHostConfig config, PlayerIdentity hostIdentity,
                                       TrackValidationProfileSource profiles) {
        EventLoopGroup group = new NioEventLoopGroup(1); // room pinned to one thread
        RoomHost room = new RoomHost(config, hostIdentity, System::currentTimeMillis, profiles);
        RaceHostChannelHandler.ConnectionCounter counter = new RaceHostChannelHandler.ConnectionCounter();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(8192))
                                    .addLast(new WebSocketServerProtocolHandler("/race", null, true,
                                            com.openggf.net.protocol.Protocol.MAX_CONTROL_BYTES))
                                    // Reassembles fragmented (continuation) frames with the same
                                    // cumulative cap: without it, fragments bypass the per-frame
                                    // cap via unbounded accumulation AND the handler below would
                                    // silently ignore ContinuationWebSocketFrames entirely.
                                    .addLast(new WebSocketFrameAggregator(
                                            com.openggf.net.protocol.Protocol.MAX_CONTROL_BYTES))
                                    .addLast(new IdleStateHandler(60, 0, 0))
                                    .addLast(new RaceHostChannelHandler(room, counter));
                        }
                    });
            Channel serverChannel = bootstrap.bind(port).sync().channel();
            group.next().scheduleAtFixedRate(room::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
            return new RaceHostServer(group, serverChannel, room);
        } catch (InterruptedException e) {
            group.shutdownGracefully();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while binding race host server", e);
        }
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** Marshal host-UI work (startRound, reads) onto the room's event loop. */
    public void execute(Runnable task) {
        group.next().execute(task);
    }

    /** Only touch on the room's event loop — use {@link #execute}. */
    public RoomHost room() {
        return room;
    }

    public void close() {
        serverChannel.close();
        group.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS);
    }
}
```

```java
package com.openggf.net.host;

import com.openggf.net.hub.HubConnection;
import com.openggf.net.hub.RoomHost;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges one WebSocket channel to the RoomHost. Everything here already runs on
 * the room's single event loop, so RoomHost calls need no further marshalling.
 * Pre-dispatch hygiene: per-IP connection cap + per-connection frame rate cap
 * (security spec §7.3).
 */
public final class RaceHostChannelHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    static final int MSG_RATE_BURST = 120;
    static final int MSG_RATE_PER_SECOND = 60;

    /** Shared per-server map of live connections per remote IP. */
    static final class ConnectionCounter {
        private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();

        boolean tryAcquire(String ip) {
            return perIp.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet()
                    <= RaceHostServer.MAX_CONNECTIONS_PER_IP;
        }

        void release(String ip) {
            AtomicInteger count = perIp.get(ip);
            if (count != null && count.decrementAndGet() <= 0) {
                perIp.remove(ip, count);
            }
        }
    }

    private final RoomHost room;
    private final ConnectionCounter counter;
    private HubConnection connection;
    private String remoteIp = "unknown";
    private boolean counted;
    private boolean registered;
    private double rateTokens = MSG_RATE_BURST;
    private long rateLastRefillNanos;

    RaceHostChannelHandler(RoomHost room, ConnectionCounter counter) {
        this.room = room;
        this.counter = counter;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress address) {
            remoteIp = address.getAddress().getHostAddress();
        }
        if (!counter.tryAcquire(remoteIp)) {
            counter.release(remoteIp);
            ctx.close(); // per-IP connection cap
            return;
        }
        counted = true;
        rateLastRefillNanos = System.nanoTime();
        ctx.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            connection = new NettyHubConnection(ctx.channel(), remoteIp);
            registered = true;
            room.onConnected(connection);
        } else if (event instanceof IdleStateEvent) {
            ctx.close(); // idle timeout
        }
        ctx.fireUserEventTriggered(event);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!registered || !allowMessage()) {
            ctx.close();
            return;
        }
        if (frame instanceof TextWebSocketFrame text) {
            room.onText(connection, text.text());
        } else if (frame instanceof BinaryWebSocketFrame binary) {
            room.onBinary(connection, ByteBufUtil.getBytes(binary.content()));
        }
        // ping/pong/close frames are handled by WebSocketServerProtocolHandler
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (registered) {
            room.onDisconnected(connection);
            registered = false;
        }
        if (counted) {
            counter.release(remoteIp);
            counted = false;
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private boolean allowMessage() {
        long now = System.nanoTime();
        rateTokens = Math.min(MSG_RATE_BURST,
                rateTokens + (now - rateLastRefillNanos) / 1_000_000_000.0 * MSG_RATE_PER_SECOND);
        rateLastRefillNanos = now;
        if (rateTokens < 1) {
            return false; // message flood
        }
        rateTokens -= 1;
        return true;
    }

    private record NettyHubConnection(Channel channel, String remoteHost) implements HubConnection {
        @Override
        public void sendText(String text) {
            channel.writeAndFlush(new TextWebSocketFrame(text));
        }

        @Override
        public void sendBinary(byte[] data) {
            channel.writeAndFlush(new BinaryWebSocketFrame(channel.alloc().buffer().writeBytes(data)));
        }

        @Override
        public void close(String reason) {
            channel.close();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.host.TestRaceHostServer" test`
Expected: PASS (4 tests, real loopback sockets, < 30 s).

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/openggf/net/host/RaceHostServer.java src/main/java/com/openggf/net/host/RaceHostChannelHandler.java src/test/java/com/openggf/net/host/TestRaceHostServer.java
git commit -m "feat(timeattack): Netty race host server with protocol hygiene

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 12: RaceClient — JDK WebSocket, network thread, per-frame drain

**Files:**
- Create: `src/main/java/com/openggf/net/client/FrameAssembler.java`
- Create: `src/main/java/com/openggf/net/client/RaceClient.java`
- Test: `src/test/java/com/openggf/net/client/TestFrameAssembler.java`
- Test: `src/test/java/com/openggf/net/client/TestRaceClientLoopback.java`

**Interfaces:**
- Consumes: `ClientHandshake` (Task 3), `ControlCodec`/`ControlMessage`/`GhostPackets` (Tasks 1–2), `PlayerIdentity`; test consumes `RaceHostServer` (Task 11).
- Produces `RaceClient`:
  - `static CompletableFuture<RaceClient> connect(URI wsUri, PlayerIdentity identity, String displayName, String determinismFingerprint)` — opens the JDK WebSocket, drives Hello→Welcome→AuthProof→JoinAccepted internally, completes with a joined client or completes exceptionally with `JoinRejectedException(String reason)` (nested class) / connect failure. **Join timeout `JOIN_TIMEOUT_MILLIS = 3000` covers the ENTIRE sequence** — TCP connect, WebSocket upgrade, AND the Hello→Welcome→AuthProof→JoinAccepted exchange (`orTimeout` on the returned future, aborting the socket on expiry). A server that accepts the socket but never answers still yields a clean failure within 3 s (main spec §9: timeout surfaces as a clean join failure; no relay fallback in phase 2). The `HttpClient` connect timeout is set to the same value as the transport-level backstop. Late-completion race: if `buildAsync` completes AFTER the timeout already failed `joined`, the connect callback must detect `joined.isDone()` and abort the fresh socket instead of sending Hello — otherwise a socket the timeout handler never saw (it was null at expiry) stays alive.
  - `sealed interface InboundEvent`: `record Control(ControlMessage message)`, `record GhostData(GhostPackets.Aggregate aggregate)`, `record Disconnected(String reason)`. Binary frames are decoded AND validated on the network thread — the game thread only ever sees parsed, typed events, so RaceClient is the single binary validation boundary.
  - `List<InboundEvent> drainInbound()` — the ONLY consumption point; called once per frame by the game thread (main spec §6.1: all game-state mutation stays on the game thread). Listener callbacks only enqueue.
  - `void sendControl(ControlMessage message)` — encodes with the session token and queues; `void sendBinary(byte[] data)`. Sends are serialized through an internal chain of `CompletableFuture`s (the JDK WebSocket forbids overlapping sends of the same kind).
  - `int playerSlot()`, `String sessionToken()`, `ControlMessage.JoinAccepted joinAccepted()`, `boolean isOpen()`, `void close()`.
  - Hardening: undecodable inbound text or binary (via `GhostPackets.decodeAggregate`, which also enforces size/type/count caps) enqueues `Disconnected("protocol violation")` and aborts the socket — a hostile HOST must not crash the client (security spec threat table), and no unvalidated bytes ever reach the game thread.
  - **Fragment caps (`FrameAssembler`, package-private):** the JDK WebSocket delivers messages in parts (`last=false`), and naive accumulation is unbounded before validation ever runs. `FrameAssembler.onTextPart(CharSequence, boolean last)` / `onBinaryPart(ByteBuffer, boolean last)` return the complete message on `last` (else null) and throw `ProtocolViolationException` BEFORE buffering a part that would push the running total over `MAX_CONTROL_BYTES` chars / `MAX_BINARY_BYTES` bytes, discarding state so a subsequent message starts clean. (The text cap counts chars, not UTF-8 bytes — as a memory cap that bounds the buffer at ~2× the byte cap, and `ControlCodec.decode` re-enforces the exact byte cap after reassembly.) The server side gets the same property from Netty's `WebSocketFrameAggregator` (Task 11).

- [ ] **Step 1: Write the failing tests**

`TestFrameAssembler` (pure — the fragment-cap hardening):

```java
package com.openggf.net.client;

import com.openggf.net.protocol.Protocol;
import com.openggf.net.protocol.ProtocolViolationException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class TestFrameAssembler {
    @Test
    void reassemblesFragmentedTextAndBinary() {
        FrameAssembler assembler = new FrameAssembler();
        assertNull(assembler.onTextPart("hel", false));
        assertEquals("hello", assembler.onTextPart("lo", true));
        assertNull(assembler.onBinaryPart(ByteBuffer.wrap(new byte[] {1, 2}), false));
        assertArrayEquals(new byte[] {1, 2, 3}, assembler.onBinaryPart(ByteBuffer.wrap(new byte[] {3}), true));
    }

    @Test
    void oversizedFragmentedTextThrowsBeforeBuffering() {
        FrameAssembler assembler = new FrameAssembler();
        String fragment = "x".repeat(Protocol.MAX_CONTROL_BYTES / 2 + 1);
        assertNull(assembler.onTextPart(fragment, false));
        // the second fragment would exceed the running cap — rejected without buffering
        assertThrows(ProtocolViolationException.class, () -> assembler.onTextPart(fragment, false));
    }

    @Test
    void oversizedFragmentedBinaryThrowsBeforeBuffering() {
        FrameAssembler assembler = new FrameAssembler();
        byte[] fragment = new byte[Protocol.MAX_BINARY_BYTES / 2 + 1];
        assertNull(assembler.onBinaryPart(ByteBuffer.wrap(fragment), false));
        assertThrows(ProtocolViolationException.class,
                () -> assembler.onBinaryPart(ByteBuffer.wrap(fragment), false));
    }

    @Test
    void recoversCleanlyAfterOversizeThrow() {
        FrameAssembler assembler = new FrameAssembler();
        assertThrows(ProtocolViolationException.class,
                () -> assembler.onTextPart("x".repeat(Protocol.MAX_CONTROL_BYTES + 1), true));
        assertEquals("ok", assembler.onTextPart("ok", true)); // state discarded on throw
    }
}
```

`TestRaceClientLoopback` (real sockets):

```java
package com.openggf.net.client;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.host.RaceHostServer;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class TestRaceClientLoopback {
    private static final String FP = "0.6:cafe1234";
    private RaceHostServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private RaceHostServer startServer(Path dir, String policy) throws Exception {
        return RaceHostServer.start(0,
                new RoomHostConfig("LAN", "s3k", 0, 0, policy, null, 8, FP),
                PlayerIdentity.loadOrCreate(dir.resolve("host")), TrackValidationProfileSource.none());
    }

    private RaceClient connect(Path idDir, String name) throws Exception {
        return RaceClient.connect(URI.create("ws://127.0.0.1:" + server.port() + "/race"),
                PlayerIdentity.loadOrCreate(idDir), name, FP).get(10, TimeUnit.SECONDS);
    }

    /** Polls drainInbound until an event matches or times out. */
    private static RaceClient.InboundEvent await(RaceClient client, Predicate<RaceClient.InboundEvent> match)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (RaceClient.InboundEvent event : client.drainInbound()) {
                if (match.test(event)) {
                    return event;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }

    @Test
    void connectsChatsAndStreamsGhostFrames(@TempDir Path dir) throws Exception {
        server = startServer(dir, "OPEN");
        RaceClient a = connect(dir.resolve("a"), "A");
        RaceClient b = connect(dir.resolve("b"), "B");
        assertEquals(0, a.playerSlot());
        assertEquals(1, b.playerSlot());
        assertTrue(a.isOpen());

        a.sendControl(new ControlMessage.Chat("hello lan"));
        RaceClient.InboundEvent chat = await(b, e -> e instanceof RaceClient.Control c
                && c.message() instanceof ControlMessage.ChatBroadcast);
        assertEquals("hello lan",
                ((ControlMessage.ChatBroadcast) ((RaceClient.Control) chat).message()).text());

        byte[] frame = new byte[GhostFrameCodec.BYTES];
        GhostFrameCodec.encode(new GhostFrame(100, 200, 1, false, false, false, 2, false), frame, 0);
        a.sendBinary(GhostPackets.encodeFrames(1, 0, frame));
        RaceClient.InboundEvent aggregate = await(b, e -> e instanceof RaceClient.GhostData);
        GhostPackets.Aggregate decoded = ((RaceClient.GhostData) aggregate).aggregate();
        assertEquals(0, decoded.entries().get(0).playerSlot());

        a.close();
        await(b, e -> e instanceof RaceClient.Control c && c.message() instanceof ControlMessage.RoomState state
                && state.players().size() == 1);
        b.close();
    }

    @Test
    void fingerprintMismatchSurfacesJoinRejected(@TempDir Path dir) throws Exception {
        server = startServer(dir, "OPEN");
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> RaceClient.connect(URI.create("ws://127.0.0.1:" + server.port() + "/race"),
                        PlayerIdentity.loadOrCreate(dir.resolve("c")), "C", "0.6:deadbeef")
                        .get(10, TimeUnit.SECONDS));
        assertInstanceOf(RaceClient.JoinRejectedException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("fingerprint"));
    }

    @Test
    void connectToDeadPortFailsCleanlyWithinTimeout(@TempDir Path dir) throws Exception {
        long start = System.currentTimeMillis();
        assertThrows(ExecutionException.class,
                () -> RaceClient.connect(URI.create("ws://127.0.0.1:1/race"),
                        PlayerIdentity.loadOrCreate(dir.resolve("d")), "D", FP)
                        .get(15, TimeUnit.SECONDS));
        assertTrue(System.currentTimeMillis() - start < 15_000); // clean failure, no hang (main spec §9)
    }

    @Test
    void serverThatAcceptsButNeverAnswersFailsWithinJoinTimeout(@TempDir Path dir) throws Exception {
        // Accepts the TCP connection but never speaks: neither the WS upgrade nor any
        // Welcome/JoinAccepted ever arrives. Only the whole-sequence orTimeout can save
        // this — the HttpClient connect timeout does NOT cover it.
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
            long start = System.currentTimeMillis();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> RaceClient.connect(URI.create("ws://127.0.0.1:" + silent.getLocalPort() + "/race"),
                            PlayerIdentity.loadOrCreate(dir.resolve("e")), "E", FP)
                            .get(15, TimeUnit.SECONDS));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 10_000, "failed in " + elapsed + "ms — join timeout did not fire");
            assertNotNull(failure.getCause()); // TimeoutException from orTimeout (or upgrade failure)
        }
    }
}
```

The same `orTimeout` path also covers a server that completes the WebSocket upgrade but never sends `Welcome`/`JoinAccepted` — `joined` is only completed by those messages, so post-upgrade silence times out identically (the mechanism under test is the whole-sequence budget, not any single stage). The `isDone()` guard in the connect callback (Step 3) additionally prevents a `buildAsync` that completes AFTER the timeout from resurrecting a live socket the timeout handler could not see.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.client.TestFrameAssembler+com.openggf.net.client.TestRaceClientLoopback" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.client;

import com.openggf.net.protocol.Protocol;
import com.openggf.net.protocol.ProtocolViolationException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Reassembles fragmented WebSocket messages under hard running caps (security spec
 * §7.3: no parser — or accumulator — ever sees unbounded input). Throws BEFORE
 * buffering an over-cap part and discards state, so the next message starts clean.
 * Text is capped in chars (bounds memory at ~2× the byte cap; ControlCodec re-checks
 * the exact UTF-8 byte cap after reassembly).
 */
final class FrameAssembler {
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

    /** Returns the complete message on the final part, else null. */
    String onTextPart(CharSequence data, boolean last) {
        if (text.length() + data.length() > Protocol.MAX_CONTROL_BYTES) {
            text.setLength(0);
            throw new ProtocolViolationException("fragmented text exceeds " + Protocol.MAX_CONTROL_BYTES);
        }
        text.append(data);
        if (!last) {
            return null;
        }
        String whole = text.toString();
        text.setLength(0);
        return whole;
    }

    /** Returns the complete packet on the final part, else null. */
    byte[] onBinaryPart(ByteBuffer data, boolean last) {
        if (binary.size() + data.remaining() > Protocol.MAX_BINARY_BYTES) {
            binary.reset();
            throw new ProtocolViolationException("fragmented binary exceeds " + Protocol.MAX_BINARY_BYTES);
        }
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        binary.writeBytes(bytes);
        if (!last) {
            return null;
        }
        byte[] whole = binary.toByteArray();
        binary.reset();
        return whole;
    }
}
```

```java
package com.openggf.net.client;

import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;
import com.openggf.net.protocol.ProtocolViolationException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Engine-side connection (main spec §6.1): the JDK WebSocket's callbacks run on its
 * own executor and ONLY enqueue; the game thread drains once per frame. Sends are
 * chained because the JDK WebSocket forbids overlapping sends of one kind.
 */
public final class RaceClient {
    /** Whole-join budget: TCP connect + WS upgrade + handshake exchange (main spec §9). */
    public static final long JOIN_TIMEOUT_MILLIS = 3000;

    public static final class JoinRejectedException extends RuntimeException {
        public JoinRejectedException(String reason) {
            super(reason);
        }
    }

    public sealed interface InboundEvent {
    }

    public record Control(ControlMessage message) implements InboundEvent {
    }

    /** A GhostAggregate already decoded and validated on the network thread. */
    public record GhostData(GhostPackets.Aggregate aggregate) implements InboundEvent {
    }

    public record Disconnected(String reason) implements InboundEvent {
    }

    private final ConcurrentLinkedQueue<InboundEvent> inbound = new ConcurrentLinkedQueue<>();
    private volatile WebSocket webSocket;
    private volatile ControlMessage.JoinAccepted joinAccepted;
    private volatile boolean open;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final Object sendLock = new Object();

    private RaceClient() {
    }

    public static CompletableFuture<RaceClient> connect(URI wsUri, PlayerIdentity identity,
                                                        String displayName, String determinismFingerprint) {
        RaceClient client = new RaceClient();
        ClientHandshake handshake = new ClientHandshake(identity, displayName, determinismFingerprint);
        CompletableFuture<RaceClient> joined = new CompletableFuture<>();

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final FrameAssembler assembler = new FrameAssembler();

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                try {
                    String text = assembler.onTextPart(data, last); // capped accumulation
                    if (text != null) {
                        handleText(ws, text);
                    }
                } catch (ProtocolViolationException e) {
                    client.fail(ws, "protocol violation");
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                try {
                    byte[] packet = assembler.onBinaryPart(data, last); // capped accumulation
                    if (packet != null) {
                        // decodeAggregate enforces type, counts, and MAX_BINARY_BYTES —
                        // a hostile host cannot feed junk to the game thread.
                        client.inbound.add(new GhostData(GhostPackets.decodeAggregate(packet)));
                    }
                } catch (ProtocolViolationException e) {
                    client.fail(ws, "protocol violation");
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                client.open = false;
                client.inbound.add(new Disconnected(reason == null || reason.isEmpty()
                        ? "connection closed" : reason));
                joined.completeExceptionally(new JoinRejectedException("connection closed during join"));
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                client.open = false;
                client.inbound.add(new Disconnected(String.valueOf(error.getMessage())));
                joined.completeExceptionally(error);
            }

            private void handleText(WebSocket ws, String text) {
                ControlMessage message;
                try {
                    message = ControlCodec.decode(text).message();
                } catch (ProtocolViolationException e) {
                    client.fail(ws, "protocol violation");
                    return;
                }
                if (client.joinAccepted == null) {
                    try {
                        switch (message) {
                            case ControlMessage.Welcome welcome ->
                                    client.enqueueSendText(ControlCodec.encode(null, handshake.onWelcome(welcome)));
                            case ControlMessage.JoinAccepted accepted -> {
                                client.joinAccepted = accepted;
                                client.open = true;
                                joined.complete(client);
                            }
                            case ControlMessage.JoinRejected rejected ->
                                    joined.completeExceptionally(new JoinRejectedException(rejected.reason()));
                            default -> client.inbound.add(new Control(message));
                        }
                    } catch (Exception e) {
                        joined.completeExceptionally(e);
                    }
                } else {
                    client.inbound.add(new Control(message));
                }
            }
        };

        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(JOIN_TIMEOUT_MILLIS))
                .buildAsync(wsUri, listener)
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        joined.completeExceptionally(error);
                        return;
                    }
                    // Publish the socket BEFORE the isDone check: if the timeout fires
                    // between the check and the send, its abort path can now see it.
                    client.webSocket = ws;
                    if (joined.isDone()) {
                        ws.abort(); // join already timed out/failed — no stray live socket
                        return;
                    }
                    client.enqueueSendText(ControlCodec.encode(null, handshake.hello()));
                });
        // The whole join sequence — not just the TCP connect — must fail cleanly within
        // the budget: a server that accepts the socket but never answers would otherwise
        // hang this future forever (main spec §9).
        joined.orTimeout(JOIN_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((c, error) -> {
                    if (error != null) {
                        WebSocket ws = client.webSocket;
                        if (ws != null) {
                            ws.abort();
                        }
                    }
                });
        return joined;
    }

    public List<InboundEvent> drainInbound() {
        List<InboundEvent> events = new ArrayList<>();
        InboundEvent event;
        while ((event = inbound.poll()) != null) {
            events.add(event);
        }
        return events;
    }

    public void sendControl(ControlMessage message) {
        enqueueSendText(ControlCodec.encode(sessionToken(), message));
    }

    public void sendBinary(byte[] data) {
        WebSocket ws = webSocket;
        if (ws == null || !open) {
            return;
        }
        synchronized (sendLock) {
            sendChain = sendChain.thenCompose(ignored -> ws.sendBinary(ByteBuffer.wrap(data), true))
                    .exceptionally(e -> null);
        }
    }

    public int playerSlot() {
        return joinAccepted != null ? joinAccepted.playerSlot() : -1;
    }

    public String sessionToken() {
        return joinAccepted != null ? joinAccepted.sessionToken() : null;
    }

    public ControlMessage.JoinAccepted joinAccepted() {
        return joinAccepted;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    private void enqueueSendText(String text) {
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        synchronized (sendLock) {
            sendChain = sendChain.thenCompose(ignored -> ws.sendText(text, true))
                    .exceptionally(e -> null);
        }
    }

    private void fail(WebSocket ws, String reason) {
        open = false;
        inbound.add(new Disconnected(reason));
        ws.abort();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.client.TestFrameAssembler+com.openggf.net.client.TestRaceClientLoopback" test`
Expected: PASS (4 + 4 tests). Note the dead-port test uses port 1 (nothing listens); if a sandboxed CI blocks the connect differently, any exceptional completion within the timeout is a pass. The silent-server test is the one that exercises the whole-sequence `orTimeout` — do not delete it if it looks redundant with the dead-port test; they cover different stages.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/client/FrameAssembler.java src/main/java/com/openggf/net/client/RaceClient.java src/test/java/com/openggf/net/client/TestFrameAssembler.java src/test/java/com/openggf/net/client/TestRaceClientLoopback.java
git commit -m "feat(timeattack): RaceClient with network-thread queue and per-frame drain

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 13: TimeAttackRuntime multiplayer seams (modify phase-1 code)

**Files:**
- Modify: `src/main/java/com/openggf/game/timeattack/TimeAttackRuntime.java` (phase-1 as-built — READ IT FIRST; the phase-1 plan's Task 10 defines its structure: `beginAttempt`/`tick` internals, `tickForTest` seam, `hudState()`)
- Test: `src/test/java/com/openggf/game/timeattack/TestTimeAttackRuntimeMultiplayerSeams.java`

**Interfaces:**
- Produces (three additive seams; NOTHING else in the phase-1 class changes):
  1. `interface AttemptListener` (nested in `TimeAttackRuntime`):
     - `void onAttemptBegan(int attemptOrdinal)` — fired when an attempt begins at spawn (`onLevelReady` / post-retry). `attemptOrdinal` starts at 1 and increments per begun attempt within one armed session — it is the wire `attemptId` (strictly increasing, as the validator and playback require).
     - `void onFrameSampled(int attemptOrdinal, GhostFrame frame)` — fired EVERY tick from the spawn frame (ARMED idle frames included) through the finishing tick, immediately after the runtime captures the frame into its ghost buffer and BEFORE `onAttemptFinished` fires on the finish tick. This is the network publisher's feed: every frame phase-1 ghost capture records is delivered exactly once, in order, so the wire stream stays spawn-anchored (recorded frame N = attempt frame N) and the ghost-stream hash covers spawn→finish inclusive. (A poll-style `lastSampledFrame()` accessor was rejected: it missed ARMED idle frames and the finishing frame, because `onAttemptFinished` fires inside the runtime tick before any post-tick poll runs.)
     - `void onAttemptFinished(int attemptOrdinal, int timeFrames, int firstInputFrame, int finishFrame, byte[] inputRecordingSha256)` — fired on the RUNNING→FINISHED transition, after the best-save decision, regardless of whether it was a new best — and always AFTER the finishing tick's `onFrameSampled`.
     - `void onAttemptVoided(int attemptOrdinal)` — fired when an attempt is voided (retry, cap, deadline void, taint).
  2. `void setAttemptListener(AttemptListener listener)` (null clears). `boolean isAttemptActive()` — true while the attempt phase is ARMED **or** RUNNING (phase-1's `isAttemptRunning()` is RUNNING-only, which is too narrow here: an ARMED attempt is already wire-visible via `onFrameSampled`, so multiplayer code must treat it as live). `void voidCurrentAttempt()` — public: voids any ACTIVE (ARMED or RUNNING) attempt and fires `onAttemptVoided`; FINISHED/VOID/no-attempt are no-ops (Task 14 calls this at the round deadline; internal retry/cap paths route through it so the listener always hears about abandoned attempts — including a retry pressed before first input, which abandons an ARMED attempt).
  3. `void setExtraGhostSupplier(Supplier<List<ActiveGhost>> supplier)` — extra ghosts merged into the runtime's render list each frame after its own best/import ghosts, truncated to the phase-1 renderer cap of 8 total (main spec §4.6: nearest-8 render cap; at ≤ 8 players a simple truncation is the degenerate case).
- Reconcile-first rule: the worktree implements phase 1 concurrently. Before editing, read the as-built `TimeAttackRuntime` and adapt mechanically (field/method names may differ from the phase-1 plan text, the SEMANTICS above may not). If phase 1 shipped `tickForTest(...)`/`beginAttemptForTest(...)` seams (its plan says it must), reuse them for this task's tests.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack;

import com.openggf.game.ghost.GhostFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestTimeAttackRuntimeMultiplayerSeams {
    private static GhostFrame frame(int x) {
        return new GhostFrame(x, 100, 1, false, false, false, 2, false);
    }

    private record Event(String kind, int ordinal) {
    }

    private static final class RecordingListener implements TimeAttackRuntime.AttemptListener {
        final List<Event> events = new ArrayList<>();
        final List<GhostFrame> sampled = new ArrayList<>();
        byte[] lastInputHash;
        int lastTimeFrames = -1;

        @Override
        public void onAttemptBegan(int attemptOrdinal) {
            events.add(new Event("began", attemptOrdinal));
        }

        @Override
        public void onFrameSampled(int attemptOrdinal, GhostFrame frame) {
            events.add(new Event("sampled", attemptOrdinal));
            sampled.add(frame);
        }

        @Override
        public void onAttemptFinished(int attemptOrdinal, int timeFrames, int firstInputFrame,
                                      int finishFrame, byte[] inputRecordingSha256) {
            events.add(new Event("finished", attemptOrdinal));
            lastTimeFrames = timeFrames;
            lastInputHash = inputRecordingSha256;
        }

        @Override
        public void onAttemptVoided(int attemptOrdinal) {
            events.add(new Event("voided", attemptOrdinal));
        }
    }

    private static TimeAttackRuntime armedRuntime(Path root) {
        GhostStore store = new GhostStore(root);
        TimeAttackRuntime runtime = new TimeAttackRuntime(store, root.resolve("identity"), () -> false);
        runtime.armForLaunch(new TimeAttackLaunchRequest("s3k", 0, 0, "sonic", List.of()));
        return runtime;
    }

    @Test
    void listenerHearsBeganFinishedWithOrdinalsAndHash(@TempDir Path root) {
        TimeAttackRuntime runtime = armedRuntime(root);
        RecordingListener listener = new RecordingListener();
        runtime.setAttemptListener(listener);

        runtime.beginAttemptForTest("0.6:cafe");
        runtime.tickForTest(0, false, false, -1, frame(10));    // spawn idle
        runtime.tickForTest(0x08, false, false, -1, frame(11)); // first input
        runtime.tickForTest(0x08, false, true, -1, frame(12));  // signpost
        // Every tick sampled — spawn idle AND the finishing tick — with finished LAST.
        assertEquals(List.of(new Event("began", 1),
                new Event("sampled", 1), new Event("sampled", 1), new Event("sampled", 1),
                new Event("finished", 1)), listener.events);
        assertEquals(List.of(10, 11, 12), listener.sampled.stream().map(GhostFrame::x).toList());
        assertEquals(1, listener.lastTimeFrames); // finishFrame 2 − firstInputFrame 1
        assertEquals(32, listener.lastInputHash.length);
    }

    @Test
    void voidCurrentAttemptFiresVoidAndNextAttemptIncrementsOrdinal(@TempDir Path root) {
        TimeAttackRuntime runtime = armedRuntime(root);
        RecordingListener listener = new RecordingListener();
        runtime.setAttemptListener(listener);

        runtime.beginAttemptForTest("0.6:cafe");
        runtime.tickForTest(0x08, false, false, -1, frame(10));
        runtime.voidCurrentAttempt();
        runtime.beginAttemptForTest("0.6:cafe"); // retry begins attempt 2
        assertEquals(List.of(new Event("began", 1), new Event("sampled", 1),
                new Event("voided", 1), new Event("began", 2)), listener.events);
    }

    @Test
    void voidedAttemptStopsSampling(@TempDir Path root) {
        TimeAttackRuntime runtime = armedRuntime(root);
        RecordingListener listener = new RecordingListener();
        runtime.setAttemptListener(listener);
        runtime.beginAttemptForTest("0.6:cafe");
        runtime.tickForTest(0, false, false, -1, frame(77));
        runtime.voidCurrentAttempt();
        runtime.tickForTest(0, false, false, -1, frame(78)); // level still ticking post-void
        assertEquals(1, listener.sampled.size()); // sampling fires only while ARMED/RUNNING
    }

    @Test
    void armedAttemptIsActiveAndVoidable(@TempDir Path root) {
        TimeAttackRuntime runtime = armedRuntime(root);
        RecordingListener listener = new RecordingListener();
        runtime.setAttemptListener(listener);
        runtime.beginAttemptForTest("0.6:cafe");
        runtime.tickForTest(0, false, false, -1, frame(10)); // idle only: still ARMED
        assertFalse(runtime.isAttemptRunning()); // phase-1 semantics: RUNNING-only
        assertTrue(runtime.isAttemptActive());   // multiplayer semantics: ARMED counts
        runtime.voidCurrentAttempt();            // deadline/retry while waiting at spawn
        assertEquals(new Event("voided", 1), listener.events.get(listener.events.size() - 1));
        assertFalse(runtime.isAttemptActive());
        runtime.voidCurrentAttempt();            // idempotent: no double-fire
        assertEquals(1, listener.events.stream().filter(e -> e.kind().equals("voided")).count());
    }

    @Test
    void extraGhostSupplierMergesAndCapsAtEight(@TempDir Path root) {
        TimeAttackRuntime runtime = armedRuntime(root);
        List<com.openggf.sprites.ghost.ActiveGhost> extras = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            extras.add(new com.openggf.sprites.ghost.ActiveGhost("net:" + i, "sonic", frame(i)));
        }
        runtime.setExtraGhostSupplier(() -> extras);
        runtime.beginAttemptForTest("0.6:cafe");
        runtime.tickForTest(0, false, false, -1, frame(1));
        // activeGhostsForTest() is the same list the render hook draws (add this
        // package-visible accessor if phase 1 did not expose one).
        assertEquals(8, runtime.activeGhostsForTest().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackRuntimeMultiplayerSeams" test`
Expected: COMPILATION ERROR (new members absent).

- [ ] **Step 3: Implement the seams in TimeAttackRuntime**

Read the as-built class, then add (adapting names to what phase 1 actually shipped):

```java
// --- nested near the top of TimeAttackRuntime ---
/** Multiplayer bridge (phase 2): hears attempt lifecycle without touching solo behavior. */
public interface AttemptListener {
    void onAttemptBegan(int attemptOrdinal);

    /** Every captured frame, spawn through finish inclusive — fired only while ARMED/RUNNING. */
    void onFrameSampled(int attemptOrdinal, GhostFrame frame);

    void onAttemptFinished(int attemptOrdinal, int timeFrames, int firstInputFrame, int finishFrame,
                           byte[] inputRecordingSha256);

    void onAttemptVoided(int attemptOrdinal);
}

private AttemptListener attemptListener;
private int attemptOrdinal;
private java.util.function.Supplier<java.util.List<ActiveGhost>> extraGhostSupplier;

public void setAttemptListener(AttemptListener listener) {
    this.attemptListener = listener;
}

public void setExtraGhostSupplier(java.util.function.Supplier<java.util.List<ActiveGhost>> supplier) {
    this.extraGhostSupplier = supplier;
}

/** ARMED or RUNNING — an ARMED attempt is already wire-visible, so it counts as live. */
public boolean isAttemptActive() {
    return attempt != null && (attempt.phase() == TimeAttackAttempt.Phase.ARMED
            || attempt.phase() == TimeAttackAttempt.Phase.RUNNING);
}

public void voidCurrentAttempt() {
    if (!isAttemptActive()) {
        return; // FINISHED/VOID/no-attempt: no-op — never double-fires the listener
    }
    attempt.voidAttempt();          // phase-1 TimeAttackAttempt API
    // ...existing discard-capture logic phase 1 runs on retry/void...
    if (attemptListener != null) {
        attemptListener.onAttemptVoided(attemptOrdinal);
    }
}
```

Wire-in points (locate the as-built equivalents):
1. Where an attempt begins at spawn (the code path `onLevelReady`/`beginAttemptForTest` reaches, where the phase-1 code constructs the fresh `TimeAttackAttempt` + `AttemptInputRecording`): increment `attemptOrdinal`, then `if (attemptListener != null) attemptListener.onAttemptBegan(attemptOrdinal);`.
2. In the per-frame tick, immediately after the phase-1 code appends the sampled frame (the `sampledFrame` parameter of `tickForTest`) to the ghost capture buffer — and ONLY on ticks where it does capture (ARMED/RUNNING): `if (attemptListener != null) attemptListener.onFrameSampled(attemptOrdinal, sampledFrame);`. This must run on the finishing tick too, BEFORE point 3 — the listener's ordering contract is what lets the coordinator seal the ghost-stream hash over the complete spawn→finish stream.
3. On the FINISHED transition, after the phase-1 best-save decision: `if (attemptListener != null) attemptListener.onAttemptFinished(attemptOrdinal, attempt.finalTimeFrames(), attempt.firstInputFrame(), attempt.finishFrame(), inputRecording.sha256());`.
4. Re-route the EXISTING internal void paths (retry request, 36 000-frame cap, debug-taint) through `voidCurrentAttempt()` so the listener hears every abandonment exactly once. Guard against double-fire: `voidCurrentAttempt` must be a no-op when no attempt is running.
5. Where the runtime assembles its `List<ActiveGhost>` for the render hook: after adding its own ghosts, `if (extraGhostSupplier != null) { for (ActiveGhost extra : extraGhostSupplier.get()) { if (ghosts.size() >= 8) break; ghosts.add(extra); } }`. Add package-visible `java.util.List<ActiveGhost> activeGhostsForTest()` returning that assembled list if phase 1 has no equivalent accessor.

- [ ] **Step 4: Run the new test AND the phase-1 runtime test**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackRuntimeMultiplayerSeams+com.openggf.game.timeattack.TestTimeAttackRuntime" test`
Expected: PASS both — the seams must not regress solo behavior.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/TimeAttackRuntime.java src/test/java/com/openggf/game/timeattack/TestTimeAttackRuntimeMultiplayerSeams.java
git commit -m "feat(timeattack): attempt listener, void, and extra-ghost seams in TimeAttackRuntime

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 14: MultiplayerRaceCoordinator + LiveLevelProfileFactory + GameLoop wiring

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/mp/RaceTransport.java`
- Create: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java`
- Create: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerHudState.java`
- Create: `src/main/java/com/openggf/game/timeattack/mp/LiveLevelProfileFactory.java`
- Modify: `src/main/java/com/openggf/net/client/RaceClient.java` (implement `RaceTransport` — one `implements` clause; the interface lives engine-side, so instead add an ADAPTER, see below, to keep the fence intact)
- Modify: `src/main/java/com/openggf/GameLoop.java` (hooks beside the phase-1 `timeAttackRuntime` hooks)
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestMultiplayerRaceCoordinator.java`

**Interfaces:**
- **Fence note:** `net.client` must not know engine types, so `RaceClient` does NOT implement the engine-side interface. `RaceTransport` (engine side) is satisfied by a tiny adapter over `RaceClient` created in Task 15's connect path; the coordinator only sees the interface.
- Produces:
  - `RaceTransport` — `List<RaceClient.InboundEvent> drainInbound(); void sendControl(ControlMessage m); void sendBinary(byte[] d); int playerSlot(); boolean isOpen(); void close();` (imports from `net.client`/`net.protocol` are fine — engine → net direction is allowed; only the reverse is fenced).
  - `MultiplayerRaceCoordinator(RaceTransport transport, ClientRaceSession session)` (+ a `LongSupplier clockMillis` overload for tests) — implements `TimeAttackRuntime.AttemptListener`; owns a `GhostStreamPublisher` over `transport::sendBinary` and a `RemoteGhostRegistry`. **Lifecycle: created at JOIN time and lives for the whole room membership** — the lobby needs the network pump long before any level exists, and the connection must survive between rounds. A `TimeAttackRuntime` is attached per round:
    - `void attachRuntime(TimeAttackRuntime runtime)` — registers `setAttemptListener(this)` + `setExtraGhostSupplier(this::remoteActiveGhosts)`; called when the round's level launches. `void detachRuntime()` — unregisters both hooks and clears cached remote ghosts, but does **NOT** touch the transport; called when the round ends and the player returns to the lobby. `boolean isRuntimeAttached()`. `void shutdown()` — `detachRuntime()` + `transport.close()`; the ONLY path that closes the socket (leaving the room, kick, or connection loss).
    - `void pump()` — **the single network pump, called every frame in BOTH contexts** (the lobby screen's update and, in-round, `beforeLevelFrame()` which simply delegates to it): drain transport: `Control` → `session.onControl` (+ `RoomState` also to `registry.onRoomState`; `RoundStart` also triggers `registry.reset()` — per-round attemptIds restart at 1 on every client while `RemoteGhostPlayback` keeps last round's HIGHER attemptId, so without the reset the entire next round's ghost streams would be stale-dropped; the hub resets its own validators via `setTrack` the same way); `GhostData` → `registry.onAggregate(ghost.aggregate())` (already decoded and validated by `RaceClient` on the network thread — Task 12); `Disconnected` → mark `connectionLost()`. Then ping scheduling: while `session.needsMoreClockSamples()` send a `Ping(nowMillis)` at most every 500 ms — clock sync must complete IN THE LOBBY, before the first deadline matters.
    - `boolean holdGameplay()` — true while a runtime is attached and `session.phase() == COUNTDOWN`. GameLoop holds the ENTIRE level frame while true (no gameplay tick, render only, `pump()` still runs): the attempt's frame 0 is then the first RUNNING tick and the world is still in canonical spawn state — ticking the world during countdown would drift it from the canonical start descriptor and break replay verifiability (security spec §6.2).
    - `void afterLevelFrame()` — (1) deadline enforcement (main spec §5.5 hard cutoff), only while attached: `if (runtime != null && !session.isWindowOpen() && runtime.isAttemptActive()) runtime.voidCurrentAttempt();` — `isAttemptActive` (ARMED or RUNNING, Task 13), NOT phase-1's RUNNING-only `isAttemptRunning`: a player idling at spawn past the deadline has a wire-visible ARMED attempt that must void and send `AttemptReset` like any other; (2) `remoteGhosts = registry.advanceAll(session.localSlot())` cached for the supplier. (Frame publication does NOT live here — see the listener below; a post-tick poll would miss ARMED idle frames and the finishing frame.)
    - `AttemptListener`: `onAttemptBegan(o)` → `publisher.beginAttempt(o)` + `transport.sendControl(new AttemptStart(o))`; `onFrameSampled(o, frame)` → `publisher.onFrame(frame)` — the runtime fires this for EVERY captured frame from spawn (ARMED idle included) through the finishing tick, before `onAttemptFinished`, so the wire stream is spawn-anchored and complete; `onAttemptFinished(o, time, first, finish, hash)` → `publisher.finishAttempt()` + `transport.sendControl(new AttemptFinish(o, time, first, finish, hex(hash), hex(publisher.streamHashSha256()), null))` — the stream hash seals over the exact frame set phase-1 ghost capture recorded; `onAttemptVoided(o)` → `publisher.abandonAttempt()` + `transport.sendControl(new AttemptReset(o))`.
    - `List<ActiveGhost> remoteActiveGhosts()` — maps cached `RemoteGhost`s to phase-1 `ActiveGhost("net:" + slot, character, state.frame())`. (Opacity-by-state and nameplates are phase-4 polish; the phase-1 renderer already distance-fades.)
    - `MultiplayerHudState hudState()` — `record MultiplayerHudState(boolean active, String phase, long remainingWindowMillis, long remainingCountdownMillis, List<ControlMessage.StandingsRow> standings, List<String> chatLines, boolean connectionLost, String kickReason)`.
  - `LiveLevelProfileFactory` (security spec §7.2: the player-host path builds the profile live from its loaded level): `static TrackValidationProfile fromLoadedLevelOrNull()` — reads `GameServices.levelOrNull()` ON THE GAME THREAD (never from the room's event loop — gameplay state is not thread-safe); null level → null (the validator stays in its explicit degraded mode). Level width/height: the loaded level's pixel dimensions — grep the phase-1/`LevelGeometry` accessors (`GameServices.level()` exposes the geometry record per CLAUDE.md `LevelGeometry`); speed = `TrackValidationProfile.GLOBAL_SPEED_CEILING_PX_PER_FRAME`, rate = `FRAME_RATE_CAP`. The host engine pushes the result via `server.execute(() -> server.room().applyTrackValidationProfile(profile))` once its level is ready (Task 15 flow item 4) — this is a PUSH model because the room starts before any level exists and hub code must not read gameplay state cross-thread.
- GameLoop wiring (read the as-built phase-1 hook block in `GameLoop.updateLevelMode` first):
  - `Engine` owns an optional `MultiplayerRaceCoordinator` from JOIN time (created by Task 15's host/join flow; `shutdown()` only on leaving the room). In the lobby (master-title mode) the `RaceLobbyScreen` calls `coordinator.pump()` every frame — that is the lobby's network pump.
  - In `updateLevelMode`: `if (coordinator != null) { coordinator.pump(); if (coordinator.holdGameplay()) { /* countdown: render only, skip the ENTIRE level tick — mirror the as-built pause/hold path phase 1 uses; runtime hooks and coordinator.afterLevelFrame are skipped too */ return; } }`. Then the normal frame; immediately AFTER `timeAttackRuntime.afterLevelFrame()`: `if (coordinator != null) coordinator.afterLevelFrame();`. (Frame publication rides the `onFrameSampled` listener INSIDE the runtime tick, so it needs no ordering here; the after-hook ordering matters only so the deadline check sees the tick's final attempt state.)
  - Retry keys, rewind suppression, editor blocking: unchanged — phase 1 already gates them on `isAttemptRunning()`.

- [ ] **Step 1: Write the failing test (fake transport, no sockets)**

```java
package com.openggf.game.timeattack.mp;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.game.timeattack.GhostStore;
import com.openggf.game.timeattack.TimeAttackLaunchRequest;
import com.openggf.game.timeattack.TimeAttackRuntime;
import com.openggf.net.client.ClientRaceSession;
import com.openggf.net.client.RaceClient;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestMultiplayerRaceCoordinator {
    private static final class FakeTransport implements RaceTransport {
        final ArrayDeque<RaceClient.InboundEvent> inbound = new ArrayDeque<>();
        final List<ControlMessage> sentControl = new ArrayList<>();
        final List<byte[]> sentBinary = new ArrayList<>();
        boolean open = true;

        @Override
        public List<RaceClient.InboundEvent> drainInbound() {
            List<RaceClient.InboundEvent> events = new ArrayList<>(inbound);
            inbound.clear();
            return events;
        }

        @Override public void sendControl(ControlMessage m) { sentControl.add(m); }
        @Override public void sendBinary(byte[] d) { sentBinary.add(d); }
        @Override public int playerSlot() { return 0; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }

    private long now = 1_000_000;

    private record Rig(FakeTransport transport, ClientRaceSession session, TimeAttackRuntime runtime,
                       MultiplayerRaceCoordinator coordinator) {
    }

    private static TimeAttackRuntime armedRuntime(Path root) {
        TimeAttackRuntime runtime = new TimeAttackRuntime(new GhostStore(root),
                root.resolve("identity"), () -> false);
        runtime.armForLaunch(new TimeAttackLaunchRequest("s3k", 0, 0, "sonic", List.of()));
        return runtime;
    }

    /** Coordinator exists from JOIN time; the runtime attaches only when {@code attach}. */
    private Rig rig(Path root, boolean attach) {
        FakeTransport transport = new FakeTransport();
        ClientRaceSession session = new ClientRaceSession(() -> now);
        session.applyJoin(new ControlMessage.JoinAccepted("tok", 0,
                new ControlMessage.RoomDescriptor("LAN", "s3k", 0, 0, "OPEN", null, 8, false),
                new ControlMessage.RoundSnapshot("LOBBY", null, 0, 0, List.of())));
        TimeAttackRuntime runtime = armedRuntime(root);
        MultiplayerRaceCoordinator coordinator =
                new MultiplayerRaceCoordinator(transport, session, () -> now);
        if (attach) {
            coordinator.attachRuntime(runtime);
        }
        return new Rig(transport, session, runtime, coordinator);
    }

    private Rig rig(Path root) {
        return rig(root, true);
    }

    private static GhostFrame frame(int x) {
        return new GhostFrame(x, 100, 1, false, false, false, 2, false);
    }

    @Test
    void attemptLifecycleSendsStartFramesFinishWithHashes(@TempDir Path root) {
        Rig rig = rig(root);
        rig.runtime().beginAttemptForTest("0.6:cafe");
        assertInstanceOf(ControlMessage.AttemptStart.class, rig.transport().sentControl.get(0));

        // Publication rides the onFrameSampled listener inside each runtime tick —
        // no coordinator call needed between ticks for the stream to be complete.
        for (int i = 0; i < 4; i++) {
            rig.runtime().tickForTest(i == 0 ? 0 : 0x08, false, i == 3, -1, frame(10 + i));
        }
        // 4 sampled frames (spawn idle + finish tick included): one 3-frame batch
        // mid-attempt plus the 1-frame remainder flushed by finishAttempt().
        assertEquals(2, rig.transport().sentBinary.size());
        assertEquals(1, GhostPackets.decodeFrames(rig.transport().sentBinary.get(1)).frameCount());
        ControlMessage.AttemptFinish finish = rig.transport().sentControl.stream()
                .filter(m -> m instanceof ControlMessage.AttemptFinish)
                .map(m -> (ControlMessage.AttemptFinish) m).findFirst().orElseThrow();
        assertEquals(1, finish.attemptId());
        assertEquals(64, finish.inputRecordingHashHex().length());
        assertEquals(64, finish.ghostStreamHashHex().length());
        assertNull(finish.inputRecordingRef()); // reserved, live on the wire
    }

    @Test
    void deadlinePassingVoidsRunningAttemptAndSendsReset(@TempDir Path root) {
        Rig rig = rig(root);
        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 10, "OPEN", null);
        rig.session().onControl(new ControlMessage.RoundStart(cfg, now, now + 10_000));
        rig.runtime().beginAttemptForTest("0.6:cafe");
        rig.runtime().tickForTest(0x08, false, false, -1, frame(10));
        rig.coordinator().afterLevelFrame();
        assertTrue(rig.runtime().isAttemptActive());

        now += 10_001; // window closes
        rig.coordinator().afterLevelFrame();
        assertFalse(rig.runtime().isAttemptActive()); // hard cutoff: void (main spec §5.5)
        assertTrue(rig.transport().sentControl.stream()
                .anyMatch(m -> m instanceof ControlMessage.AttemptReset));
    }

    @Test
    void deadlineVoidsArmedAttemptIdlingAtSpawn(@TempDir Path root) {
        Rig rig = rig(root);
        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 10, "OPEN", null);
        rig.session().onControl(new ControlMessage.RoundStart(cfg, now, now + 10_000));
        rig.runtime().beginAttemptForTest("0.6:cafe");
        rig.runtime().tickForTest(0, false, false, -1, frame(10)); // idle only: ARMED, but streaming
        rig.coordinator().afterLevelFrame();
        assertFalse(rig.runtime().isAttemptRunning()); // never left ARMED
        assertTrue(rig.runtime().isAttemptActive());

        now += 10_001; // window closes while the player waits at spawn
        rig.coordinator().afterLevelFrame();
        assertFalse(rig.runtime().isAttemptActive());
        assertTrue(rig.transport().sentControl.stream()
                .anyMatch(m -> m instanceof ControlMessage.AttemptReset)); // peers see the reset
    }

    @Test
    void inboundAggregateBecomesRemoteActiveGhosts(@TempDir Path root) {
        Rig rig = rig(root);
        rig.transport().inbound.add(new RaceClient.Control(new ControlMessage.RoomState(List.of(
                new ControlMessage.PlayerInfo(0, "fp0", "ME", "sonic"),
                new ControlMessage.PlayerInfo(3, "fp3", "PEER", "tails")))));
        // 12 frames from peer slot 3 → enough to pass the initial jitter buffer
        for (int i = 0; i < 12; i += 3) {
            byte[] data = new byte[3 * GhostFrameCodec.BYTES];
            for (int k = 0; k < 3; k++) {
                GhostFrameCodec.encode(frame(200 + i + k), data, k * GhostFrameCodec.BYTES);
            }
            rig.transport().inbound.add(new RaceClient.GhostData(new GhostPackets.Aggregate(i, List.of(
                    new GhostPackets.AggregateEntry(3, 1, i, 3, data)))));
        }
        rig.coordinator().pump();
        rig.coordinator().afterLevelFrame();
        List<com.openggf.sprites.ghost.ActiveGhost> ghosts = rig.coordinator().remoteActiveGhosts();
        assertEquals(1, ghosts.size());
        assertEquals("net:3", ghosts.get(0).slotId());
        assertEquals("tails", ghosts.get(0).characterCode());
    }

    @Test
    void hudStateReflectsSessionAndDisconnect(@TempDir Path root) {
        Rig rig = rig(root);
        rig.transport().inbound.add(new RaceClient.Control(new ControlMessage.StandingsDelta(
                List.of(new ControlMessage.StandingsRow(3, "PEER", "tails", 3600, 1)))));
        rig.coordinator().pump();
        MultiplayerHudState hud = rig.coordinator().hudState();
        assertTrue(hud.active());
        assertEquals(1, hud.standings().size());
        assertFalse(hud.connectionLost());

        rig.transport().inbound.add(new RaceClient.Disconnected("host gone"));
        rig.coordinator().pump();
        assertTrue(rig.coordinator().hudState().connectionLost());
    }

    @Test
    void pingsUntilClockSamplesSufficeThrottledPerInterval(@TempDir Path root) {
        Rig rig = rig(root, false); // lobby context: no runtime attached
        rig.coordinator().pump();
        assertEquals(1, rig.transport().sentControl.stream()
                .filter(m -> m instanceof ControlMessage.Ping).count());
        rig.coordinator().pump(); // same 500ms window: no second ping
        assertEquals(1, rig.transport().sentControl.stream()
                .filter(m -> m instanceof ControlMessage.Ping).count());
    }

    @Test
    void lobbyPumpProcessesRoomStateAndChatWithoutAnyRuntime(@TempDir Path root) {
        Rig rig = rig(root, false); // pre-round lobby: coordinator exists, no level, no runtime
        rig.transport().inbound.add(new RaceClient.Control(new ControlMessage.RoomState(List.of(
                new ControlMessage.PlayerInfo(0, "fp0", "ME", "sonic"),
                new ControlMessage.PlayerInfo(1, "fp1", "PEER", "tails")))));
        rig.transport().inbound.add(new RaceClient.Control(
                new ControlMessage.ChatBroadcast(1, "PEER", "gl hf")));
        rig.coordinator().pump();
        assertEquals(2, rig.session().players().size());
        assertEquals(List.of("PEER: gl hf"), rig.session().chatLines());
        assertFalse(rig.coordinator().isRuntimeAttached());
        assertFalse(rig.coordinator().holdGameplay());
    }

    @Test
    void holdGameplayOnlyWhileAttachedDuringCountdown(@TempDir Path root) {
        Rig rig = rig(root);
        assertFalse(rig.coordinator().holdGameplay()); // LOBBY
        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 60, "OPEN", null);
        rig.session().onControl(new ControlMessage.RoundStart(cfg, now + 3000, now + 63_000));
        assertTrue(rig.coordinator().holdGameplay());  // COUNTDOWN + attached
        now += 3000;
        assertFalse(rig.coordinator().holdGameplay()); // RUNNING
        rig.coordinator().detachRuntime();
        assertFalse(rig.coordinator().holdGameplay()); // detached: never held
    }

    @Test
    void countdownHoldKeepsAttemptSpawnAnchored(@TempDir Path root) {
        Rig rig = rig(root);
        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 60, "OPEN", null);
        rig.session().onControl(new ControlMessage.RoundStart(cfg, now + 3000, now + 63_000));
        rig.runtime().beginAttemptForTest("0.6:cafe"); // level loaded during countdown
        assertTrue(rig.coordinator().holdGameplay());
        // GameLoop contract: while holdGameplay() is true, NO level tick runs — so no
        // frames accrue and the world stays at canonical spawn (security spec §6.2).
        assertTrue(rig.transport().sentBinary.isEmpty());

        now += 3000; // countdown over: ticking begins
        assertFalse(rig.coordinator().holdGameplay());
        for (int i = 0; i < 3; i++) {
            rig.runtime().tickForTest(0x08, false, false, -1, frame(10 + i));
        }
        GhostPackets.FramesBatch first = GhostPackets.decodeFrames(rig.transport().sentBinary.get(0));
        assertEquals(0, first.startFrameIndex()); // frame 0 = first RUNNING tick
    }

    @Test
    void roundStartResetsRemoteGhostPlaybackForNewRound(@TempDir Path root) {
        Rig rig = rig(root);
        rig.transport().inbound.add(new RaceClient.Control(new ControlMessage.RoomState(List.of(
                new ControlMessage.PlayerInfo(0, "fp0", "ME", "sonic"),
                new ControlMessage.PlayerInfo(3, "fp3", "PEER", "tails")))));
        feedPeerFrames(rig, 5, 500); // round 1: peer streams attempt 5
        rig.coordinator().pump();
        rig.coordinator().afterLevelFrame();
        assertEquals(500, rig.coordinator().remoteActiveGhosts().get(0).frame().x());

        ControlMessage.RoundConfig cfg = new ControlMessage.RoundConfig("s3k", 0, 0, 60, "OPEN", null);
        rig.transport().inbound.add(new RaceClient.Control(
                new ControlMessage.RoundStart(cfg, now, now + 60_000)));
        rig.coordinator().pump(); // RoundStart → registry.reset()
        feedPeerFrames(rig, 1, 100); // round 2: attemptIds restart at 1
        rig.coordinator().pump();
        rig.coordinator().afterLevelFrame();
        // Without the reset, attempt 1 < attempt 5 would be stale-dropped and the old
        // round's frames would keep rendering.
        assertEquals(100, rig.coordinator().remoteActiveGhosts().get(0).frame().x());
    }

    /** Buffers 12 contiguous frames for slot 3 with x = baseX (constant) at the given attemptId. */
    private void feedPeerFrames(Rig rig, int attemptId, int baseX) {
        for (int i = 0; i < 12; i += 3) {
            byte[] data = new byte[3 * GhostFrameCodec.BYTES];
            for (int k = 0; k < 3; k++) {
                GhostFrameCodec.encode(frame(baseX), data, k * GhostFrameCodec.BYTES);
            }
            rig.transport().inbound.add(new RaceClient.GhostData(new GhostPackets.Aggregate(i, List.of(
                    new GhostPackets.AggregateEntry(3, attemptId, i, 3, data)))));
        }
    }

    @Test
    void detachKeepsConnectionOpenAndSecondRoundAttachWorks(@TempDir Path root) {
        Rig rig = rig(root);
        rig.runtime().beginAttemptForTest("0.6:cafe");
        rig.runtime().tickForTest(0x08, false, true, -1, frame(10)); // instant finish
        assertTrue(rig.transport().sentControl.stream()
                .anyMatch(m -> m instanceof ControlMessage.AttemptFinish));

        rig.coordinator().detachRuntime(); // round over: back to the lobby
        assertTrue(rig.transport().open, "detach must NOT close the connection");
        rig.runtime().tickForTest(0x08, false, false, -1, frame(11)); // stale runtime keeps ticking
        long startsAfterDetach = rig.transport().sentControl.stream()
                .filter(m -> m instanceof ControlMessage.AttemptStart).count();

        TimeAttackRuntime second = armedRuntime(root.resolve("round2"));
        rig.coordinator().attachRuntime(second); // next round: fresh armed session
        second.beginAttemptForTest("0.6:cafe");
        assertEquals(startsAfterDetach + 1, rig.transport().sentControl.stream()
                .filter(m -> m instanceof ControlMessage.AttemptStart).count());
        rig.coordinator().shutdown(); // leaving the room is what closes the socket
        assertFalse(rig.transport().open);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.TestMultiplayerRaceCoordinator" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.timeattack.mp;

import com.openggf.net.client.RaceClient;
import com.openggf.net.protocol.ControlMessage;

import java.util.List;

/** Engine-side view of the connection; RaceClient stays engine-free behind an adapter. */
public interface RaceTransport {
    List<RaceClient.InboundEvent> drainInbound();

    void sendControl(ControlMessage message);

    void sendBinary(byte[] data);

    int playerSlot();

    boolean isOpen();

    void close();
}
```

```java
package com.openggf.game.timeattack.mp;

import com.openggf.net.protocol.ControlMessage;

import java.util.List;

/** Snapshot for the in-round multiplayer HUD (Task 15 renders it). */
public record MultiplayerHudState(boolean active, String phase, long remainingWindowMillis,
                                  long remainingCountdownMillis,
                                  List<ControlMessage.StandingsRow> standings,
                                  List<String> chatLines, boolean connectionLost, String kickReason) {
}
```

```java
package com.openggf.game.timeattack.mp;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.timeattack.TimeAttackRuntime;
import com.openggf.net.client.ClientRaceSession;
import com.openggf.net.client.GhostStreamPublisher;
import com.openggf.net.client.RaceClient;
import com.openggf.net.client.RemoteGhostRegistry;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.sprites.ghost.ActiveGhost;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Bridges a live room to the engine (main spec §8). Created at JOIN time and alive
 * for the whole room membership: pump() is the single network drain in BOTH the
 * lobby and in-round contexts, and the connection survives between rounds — a
 * TimeAttackRuntime is attached per round and detached at round end. Only
 * shutdown() (leaving the room) closes the transport.
 */
public final class MultiplayerRaceCoordinator implements TimeAttackRuntime.AttemptListener {
    private static final long PING_INTERVAL_MILLIS = 500;

    private final RaceTransport transport;
    private final ClientRaceSession session;
    private final GhostStreamPublisher publisher;
    private final RemoteGhostRegistry registry = new RemoteGhostRegistry();
    private final java.util.function.LongSupplier clockMillis;

    private TimeAttackRuntime runtime; // attached for one round's gameplay, else null
    private List<ActiveGhost> remoteGhosts = List.of();
    private boolean connectionLost;
    private long lastPingAtMillis = Long.MIN_VALUE;

    public MultiplayerRaceCoordinator(RaceTransport transport, ClientRaceSession session) {
        this(transport, session, System::currentTimeMillis);
    }

    public MultiplayerRaceCoordinator(RaceTransport transport, ClientRaceSession session,
                                      java.util.function.LongSupplier clockMillis) {
        this.transport = transport;
        this.session = session;
        this.clockMillis = clockMillis;
        this.publisher = new GhostStreamPublisher(transport::sendBinary);
    }

    /** Round launched: hook the runtime. The coordinator itself predates any level. */
    public void attachRuntime(TimeAttackRuntime runtime) {
        this.runtime = runtime;
        runtime.setAttemptListener(this);
        runtime.setExtraGhostSupplier(this::remoteActiveGhosts);
    }

    /** Round over: release the runtime but KEEP the connection — the lobby reuses it. */
    public void detachRuntime() {
        if (runtime != null) {
            runtime.setAttemptListener(null);
            runtime.setExtraGhostSupplier(null);
            runtime = null;
        }
        remoteGhosts = List.of();
    }

    public boolean isRuntimeAttached() {
        return runtime != null;
    }

    /**
     * The single network pump — called every frame by the lobby screen AND (as
     * beforeLevelFrame) in-round. Nothing else drains the transport.
     */
    public void pump() {
        for (RaceClient.InboundEvent event : transport.drainInbound()) {
            switch (event) {
                case RaceClient.Control control -> {
                    session.onControl(control.message());
                    if (control.message() instanceof ControlMessage.RoomState state) {
                        registry.onRoomState(state.players());
                    } else if (control.message() instanceof ControlMessage.RoundStart) {
                        // Per-round attemptIds restart at 1; without this reset the
                        // playbacks would stale-drop the whole next round (the hub
                        // resets its validators the same way via setTrack).
                        registry.reset();
                    }
                }
                case RaceClient.GhostData ghost -> registry.onAggregate(ghost.aggregate());
                case RaceClient.Disconnected disconnected -> connectionLost = true;
            }
        }
        maybePing(); // clock sync must complete in the lobby, before deadlines matter
    }

    public void beforeLevelFrame() {
        pump();
    }

    /** Countdown: GameLoop holds the whole level frame so the world stays at canonical spawn. */
    public boolean holdGameplay() {
        return runtime != null && session.phase() == ClientRaceSession.Phase.COUNTDOWN;
    }

    public void afterLevelFrame() {
        if (runtime != null && !session.isWindowOpen() && runtime.isAttemptActive()) {
            runtime.voidCurrentAttempt(); // hard deadline cutoff (main spec §5.5) — ARMED included
        }
        remoteGhosts = toActiveGhosts(registry.advanceAll(session.localSlot()));
    }

    public List<ActiveGhost> remoteActiveGhosts() {
        return remoteGhosts;
    }

    public MultiplayerHudState hudState() {
        return new MultiplayerHudState(transport.isOpen() || connectionLost,
                session.phase().name(), session.remainingWindowMillis(),
                session.remainingCountdownMillis(), session.standings(), session.chatLines(),
                connectionLost, session.kickReason());
    }

    public ClientRaceSession session() {
        return session;
    }

    public void sendChat(String text) {
        transport.sendControl(new ControlMessage.Chat(text));
    }

    /** Leaving the room — the ONLY path that closes the socket. */
    public void shutdown() {
        detachRuntime();
        transport.close();
    }

    @Override
    public void onAttemptBegan(int attemptOrdinal) {
        publisher.beginAttempt(attemptOrdinal);
        transport.sendControl(new ControlMessage.AttemptStart(attemptOrdinal));
    }

    @Override
    public void onFrameSampled(int attemptOrdinal, GhostFrame frame) {
        publisher.onFrame(frame); // spawn-anchored: every captured frame, spawn through finish
    }

    @Override
    public void onAttemptFinished(int attemptOrdinal, int timeFrames, int firstInputFrame,
                                  int finishFrame, byte[] inputRecordingSha256) {
        publisher.finishAttempt();
        transport.sendControl(new ControlMessage.AttemptFinish(attemptOrdinal, timeFrames,
                firstInputFrame, finishFrame,
                HexFormat.of().formatHex(inputRecordingSha256),
                HexFormat.of().formatHex(publisher.streamHashSha256()),
                null /* inputRecordingRef reserved until the verifier exists */));
    }

    @Override
    public void onAttemptVoided(int attemptOrdinal) {
        publisher.abandonAttempt();
        transport.sendControl(new ControlMessage.AttemptReset(attemptOrdinal));
    }

    private List<ActiveGhost> toActiveGhosts(List<RemoteGhostRegistry.RemoteGhost> ghosts) {
        List<ActiveGhost> active = new ArrayList<>(ghosts.size());
        for (RemoteGhostRegistry.RemoteGhost ghost : ghosts) {
            active.add(new ActiveGhost("net:" + ghost.slot(), ghost.character(), ghost.state().frame()));
        }
        return active;
    }

    private void maybePing() {
        if (!session.needsMoreClockSamples() || !transport.isOpen()) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now - lastPingAtMillis >= PING_INTERVAL_MILLIS) {
            lastPingAtMillis = now;
            transport.sendControl(new ControlMessage.Ping(now));
        }
    }
}
```

`LiveLevelProfileFactory` (host side; read the as-built level-geometry accessor before finalizing):

```java
package com.openggf.game.timeattack.mp;

import com.openggf.game.GameServices;
import com.openggf.net.hub.TrackValidationProfile;

/**
 * Player-host profile builder (security spec §7.2): builds the validation profile
 * from the loaded level ON THE GAME THREAD; the host engine pushes the result onto
 * the room's event loop via RaceHostServer.execute → RoomHost.applyTrackValidationProfile.
 * No level loaded → null → the validator stays in its explicit degraded mode.
 */
public final class LiveLevelProfileFactory {
    private LiveLevelProfileFactory() {
    }

    public static TrackValidationProfile fromLoadedLevelOrNull() {
        var level = GameServices.levelOrNull();
        if (level == null) {
            return null;
        }
        // Use the engine's level pixel dimensions — grep LevelGeometry / the phase-1
        // level accessors for the exact getter names and adapt.
        return new TrackValidationProfile(level.getWidthPixels(), level.getHeightPixels(),
                TrackValidationProfile.GLOBAL_SPEED_CEILING_PX_PER_FRAME,
                TrackValidationProfile.FRAME_RATE_CAP);
    }
}
```

GameLoop wiring (after reading the as-built phase-1 block): add an optional coordinator reference (setter called by Engine), the `pump()` call, the `holdGameplay()` level-tick hold, and the `afterLevelFrame()` call as specified in the Interfaces section. Compile-check via `mvn -q compile`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.TestMultiplayerRaceCoordinator" test`
Expected: PASS (11 tests). The clock is injected (`LongSupplier clockMillis`, production default `System::currentTimeMillis` via the 2-arg constructor) so the ping/phase tests are deterministic — the rig passes `() -> now`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/mp/RaceTransport.java src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java src/main/java/com/openggf/game/timeattack/mp/MultiplayerHudState.java src/main/java/com/openggf/game/timeattack/mp/LiveLevelProfileFactory.java src/main/java/com/openggf/GameLoop.java src/test/java/com/openggf/game/timeattack/mp/TestMultiplayerRaceCoordinator.java
git commit -m "feat(timeattack): multiplayer race coordinator bridging runtime and room

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 15: Host/Join UI, lobby screen, in-round HUD, config keys

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/mp/MenuTextField.java`
- Create: `src/main/java/com/openggf/game/timeattack/mp/RaceLobbyScreen.java`
- Create: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerHudRenderer.java`
- Modify: `src/main/java/com/openggf/game/timeattack/TimeAttackMenu.java` (phase-1 as-built — add SOLO / HOST LAN / JOIN LAN mode row)
- Modify: `src/main/java/com/openggf/Engine.java` (host/join launch + teardown paths beside the phase-1 `launchTimeAttack`)
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`, `src/main/java/com/openggf/configuration/ConfigCatalog.java`, `CONFIGURATION.md`
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestMenuTextField.java`

This task is UI glue over as-built phase-1 screens — the exact render calls MUST be copied from the as-built `TimeAttackMenu`/`TimeAttackHudRenderer` idioms (font renderer, row highlighting, input polling with `input.logical()` menu accessors — check the gamepad-input-gap-sweep rule: every confirm gate must also honor `input.logical().menuAccept()`, not just raw keys).

**Interfaces:**
- Config keys (all three: enum constant + `ConfigCatalog` meta + CONFIGURATION.md row; `TestConfigCatalog` gates this):
  - `TIME_ATTACK_NET_HOST_PORT` → path `timeAttack.net.hostPort`, INT, default `27888`.
  - `TIME_ATTACK_NET_LAST_JOIN_ADDRESS` → path `timeAttack.net.lastJoinAddress`, STRING, default `""` (persisted after a successful join; prefills the join field).
  - `TIME_ATTACK_NET_DISPLAY_NAME` → path `timeAttack.net.displayName`, STRING, default `""` (empty → first 8 hex chars of the identity fingerprint, matching phase-1 ghost naming).
- `MenuTextField(int maxLength, String allowedExtraChars)` — pure, testable: `void feedChar(char c)` (letters, digits, plus any of `allowedExtraChars`), `void backspace()`, `String text()`, `void setText(String)`. A separate `void poll(InputHandler input)` shell maps pressed keys to `feedChar`/`backspace` following the `typedCharacter` idiom in `DisplayShaderPickerController.java:176` — EXTEND it with `GLFW_KEY_PERIOD → '.'` and `shift+GLFW_KEY_SEMICOLON → ':'` so `192.168.1.5:27888` is typeable. Join field: `new MenuTextField(64, ".:-")`; chat field: `new MenuTextField(Protocol.MAX_CHAT_CHARS, " .,:!?'-")`.
- Flow (all states owned by the master-title sub-mode that phase 1 established for `TimeAttackMenu`):
  1. Menu mode row: `SOLO` (phase-1 flow unchanged) / `HOST LAN` / `JOIN LAN`.
  2. `HOST LAN` → track picker (reuse phase-1 catalog UI) → character-policy row (`OPEN` / `LOCKED <character>`) → window minutes row (1/2/5/10, default 5) → GO: `Engine.hostTimeAttackRoom(...)`:
     - build `DeterminismFingerprint` the same way phase 1 does (AppVersion + ROM checksum) → `RoomHostConfig(roomName = displayName + "'s room", gameId, zone, act, policy, lockedChar, 8, fingerprint.asString())`;
     - `RaceHostServer.start(configuredPort, config, identity, TrackValidationProfileSource.none())` — the room starts with NO validation profile (no level is loaded yet, and the room's event loop must never read gameplay state); the profile is pushed in flow item 4 once the host's level is ready;
     - connect the host's OWN client: `RaceClient.connect(URI.create("ws://127.0.0.1:" + server.port() + "/race"), identity, displayName, fingerprint.asString())` — the hosting player plays through the same client stack as everyone else (main spec §6.1: hosting mode only changes who runs the hub);
     - wrap in the `RaceTransport` adapter, build `ClientRaceSession` (+ `applyJoin`), **create the `MultiplayerRaceCoordinator` NOW** (`new MultiplayerRaceCoordinator(transport, session)` — it lives for the whole room membership, not per round; Task 14) and `gameLoop.setMultiplayerCoordinator(coordinator)`, then show `RaceLobbyScreen`.
  3. `JOIN LAN` → address `MenuTextField` (prefilled from config) → GO: parse `host[:port]` (default port = config), `RaceClient.connect` with a 3 s timeout; failure → toast the reason, stay in the menu (main spec §9); success → persist address, create the coordinator exactly as in item 2, lobby.
  4. `RaceLobbyScreen` (rendered in the master-title mode): **its `update()` calls `coordinator.pump()` every title frame — that IS the lobby's network pump; without it RoomState/chat/RoundStart would never be processed and clock sync would never complete.** Shows room name + track label, player list (name, character, "HOST" badge by fingerprint == serverId, "unverified times" room label — main spec §2), chat log + chat field (ENTER sends via `coordinator.sendChat`), host-only footer "START ROUND (ENTER)" → sends `RoundConfigure(config)` **via the client connection** (the RoomHost host-fingerprint check authorizes it). On `RoundStart` (session phase leaves LOBBY): `Engine.launchTimeAttack(request)` with the room's track + the player's character, then `coordinator.attachRuntime(timeAttackRuntime)`. **When HOSTING, push the validation profile as soon as the level is ready** (right after `onLevelReady()`): `TrackValidationProfile profile = LiveLevelProfileFactory.fromLoadedLevelOrNull(); if (profile != null) server.execute(() -> server.room().applyTrackValidationProfile(profile));` — built on the game thread, applied on the room loop during the countdown, before guests' attempts start streaming; `applyProfile` never resets live guest streams, so a late push is safe too. **Countdown hold:** while `coordinator.holdGameplay()` is true (COUNTDOWN), GameLoop skips the ENTIRE level tick (render + `pump()` only — Task 14 wiring); no gameplay frame runs, so the attempt's frame 0 is the first RUNNING tick and the world is still in canonical spawn state (security spec §6.2 — ticking the world during countdown would drift it from the canonical start descriptor and break replay verifiability). Covered by Task 14's `holdGameplayOnlyWhileAttachedDuringCountdown` and `countdownHoldKeepsAttemptSpawnAnchored` tests.
  5. In-round: `MultiplayerHudRenderer.render(coordinator.hudState())` — countdown `3..2..1` center-screen during COUNTDOWN; window clock `W mm:ss` and standings panel (rank, name, mm:ss.ff via the phase-1 time formatter) top-right; `RoundEnd` → final standings overlay for the linger period, then `coordinator.detachRuntime()` (the transport stays OPEN — Task 14's non-closing detach) and Engine returns to the lobby screen for the next round.
  6. Disconnect/leave handling (main spec §9): `hudState().connectionLost()` or `kickReason() != null`, or the player backs out of the lobby → toast where applicable + full teardown: `coordinator.shutdown()` (the ONLY transport-closing path), `server.close()` if hosting, back to the Time Attack menu.
- Retry, rewind suppression, editor blocking, ghost saving: all phase-1 behavior, untouched. Personal bests still save locally during multiplayer rounds (same runtime path).

- [ ] **Step 1: Write the failing MenuTextField test**

```java
package com.openggf.game.timeattack.mp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestMenuTextField {
    @Test
    void acceptsAlnumAndAllowedExtras() {
        MenuTextField field = new MenuTextField(64, ".:-");
        for (char c : "192.168.1.5:27888".toCharArray()) {
            field.feedChar(c);
        }
        assertEquals("192.168.1.5:27888", field.text());
    }

    @Test
    void rejectsDisallowedCharsAndEnforcesMaxLength() {
        MenuTextField field = new MenuTextField(3, "");
        field.feedChar('a');
        field.feedChar('!'); // not allowed
        field.feedChar('b');
        field.feedChar('c');
        field.feedChar('d'); // over max
        assertEquals("abc", field.text());
    }

    @Test
    void backspaceAndSetText() {
        MenuTextField field = new MenuTextField(10, ".");
        field.setText("10.0.0.1");
        field.backspace();
        assertEquals("10.0.0.", field.text());
        field.setText("this-is-way-too-long!!");
        assertEquals(10, field.text().length()); // clamped and filtered
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.TestMenuTextField" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement MenuTextField**

```java
package com.openggf.game.timeattack.mp;

/** Minimal polled text field for menus (address/chat). Pure logic + a poll() shell. */
public final class MenuTextField {
    private final int maxLength;
    private final String allowedExtraChars;
    private final StringBuilder text = new StringBuilder();

    public MenuTextField(int maxLength, String allowedExtraChars) {
        this.maxLength = maxLength;
        this.allowedExtraChars = allowedExtraChars;
    }

    public void feedChar(char c) {
        if (text.length() >= maxLength) {
            return;
        }
        if (Character.isLetterOrDigit(c) || allowedExtraChars.indexOf(c) >= 0) {
            text.append(c);
        }
    }

    public void backspace() {
        if (!text.isEmpty()) {
            text.setLength(text.length() - 1);
        }
    }

    public String text() {
        return text.toString();
    }

    public void setText(String value) {
        text.setLength(0);
        for (char c : value.toCharArray()) {
            feedChar(c);
        }
    }
}
```

Then add the `poll(InputHandler input)` method following `DisplayShaderPickerController.typedCharacter` (letters a–z with shift, digits, space, minus, plus `GLFW_KEY_PERIOD → '.'`, `shift+GLFW_KEY_SEMICOLON → ':'`, `GLFW_KEY_BACKSPACE → backspace()`), calling `feedChar` per pressed key.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.TestMenuTextField" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Add config keys + catalog meta + CONFIGURATION.md rows**

Add the three enum constants to `SonicConfiguration` (with Javadoc), register meta in `ConfigCatalog` following the existing `of(...)` idiom and section placement (find the debug/test-mode section for the pattern), and add three CONFIGURATION.md rows.
Run: `mvn "-Dtest=com.openggf.configuration.TestConfigCatalog" test` — Expected: PASS.

- [ ] **Step 6: Build the menu additions, lobby screen, HUD renderer, Engine paths**

Follow the Flow spec above. Concrete anchors:
- `TimeAttackMenu`: read the as-built row/mode enum and add the mode row exactly like its existing rows; the JOIN field renders as `JOIN: <text>_`.
- `RaceLobbyScreen`: a class with `void update(InputHandler input)` + `void render()` called from the same master-title sub-mode dispatch that phase 1 added for `TimeAttackMenu` — mirror that dispatch (grep `MasterTitleScreen` for the phase-1 integration and add a sibling state).
- `MultiplayerHudRenderer`: render from `MultiplayerHudState` only (no session access) with the same text renderer the phase-1 HUD overlay uses; standings row format `"%d %-8s %s"` with the phase-1 frames→mm:ss.ff formatter.
- `Engine`: `hostTimeAttackRoom(...)`/`joinTimeAttackRoom(...)`/`leaveTimeAttackRoom()` per the Flow; store `RaceHostServer`+`RaceClient`+coordinator on Engine fields; ALL teardown paths (quit-to-title, disconnect, kick) route through `leaveTimeAttackRoom()`.
- Countdown gating in GameLoop per Flow item 4.

Verification for this step is compile + the existing suites (UI has no headless test surface):
Run: `mvn -q compile` then `mvn "-Dtest=com.openggf.game.timeattack.*" test`
Expected: BUILD SUCCESS, phase-1 + phase-2 timeattack tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/mp/MenuTextField.java src/main/java/com/openggf/game/timeattack/mp/RaceLobbyScreen.java src/main/java/com/openggf/game/timeattack/mp/MultiplayerHudRenderer.java src/main/java/com/openggf/game/timeattack/TimeAttackMenu.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/ConfigCatalog.java CONFIGURATION.md src/test/java/com/openggf/game/timeattack/mp/TestMenuTextField.java
git commit -m "feat(timeattack): host/join menus, race lobby, multiplayer HUD, net config keys

Changelog: n/a: phase-2 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a"
```

---

### Task 16: ArchUnit fence — net packages stay engine-free

**Files:**
- Create: `src/test/java/com/openggf/tests/TestNetIsolationRules.java`

**Interfaces:**
- Freezes main spec §6.2: `com.openggf.net.protocol`, `net.hub`, `net.host`, `net.client`, `net.identity` may depend only on the JDK, Netty, Jackson, other `com.openggf.net..` classes, and exactly `com.openggf.game.ghost.GhostFrame` + `GhostFrameCodec`. Follow the existing `TestArchUnitRules` idiom (`@AnalyzeClasses` + `@ArchTest`; see `src/test/java/com/openggf/tests/TestArchUnitRules.java` for imports/options). No freeze-store needed — this rule starts clean.

- [ ] **Step 1: Write the rule (this IS the test)**

```java
package com.openggf.tests;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Engine-free fence for the network stack (main spec §6.2): the master server must
 * run these packages with no LWJGL/engine classes on the path. Only the canonical
 * ghost frame codec is shared with the engine tree.
 */
@AnalyzeClasses(packages = "com.openggf", importOptions = ImportOption.DoNotIncludeTests.class)
public class TestNetIsolationRules {
    @ArchTest
    static final ArchRule NET_STACK_IS_ENGINE_FREE =
            noClasses().that().resideInAnyPackage(
                            "com.openggf.net.protocol..", "com.openggf.net.hub..",
                            "com.openggf.net.host..", "com.openggf.net.client..",
                            "com.openggf.net.identity..")
                    .should().dependOnClassesThat(
                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                    "are engine classes outside com.openggf.net and the ghost frame codec",
                                    javaClass -> javaClass.getPackageName().startsWith("com.openggf")
                                            && !javaClass.getPackageName().startsWith("com.openggf.net")
                                            && !javaClass.getName().equals("com.openggf.game.ghost.GhostFrame")
                                            && !javaClass.getName().equals("com.openggf.game.ghost.GhostFrameCodec")))
                    .because("the master server runs net.protocol/hub/host engine-free (main spec §6.2); "
                            + "only the canonical 7-byte ghost frame codec is shared");
}
```

- [ ] **Step 2: Run it**

Run: `mvn "-Dtest=com.openggf.tests.TestNetIsolationRules" test`
Expected: PASS. If it fails, a net-package class grew an engine import — fix the CLASS (move engine-touching code to `com.openggf.game.timeattack.mp`), never widen the allowlist.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/TestNetIsolationRules.java
git commit -m "test(timeattack): ArchUnit fence keeping net stack engine-free

Changelog: n/a: test-only commit
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 17: Loopback end-to-end round + LatencyProxy + docs + full verification

**Files:**
- Create: `src/test/java/com/openggf/net/LatencyProxy.java` (test util)
- Create: `src/test/java/com/openggf/net/TestDirectConnectEndToEnd.java`
- Modify: `CLAUDE.md` + `AGENTS.md` (add a short "Multiplayer time attack networking" note: the four net packages, the fence rule, hub threading contract, and that `GhostHub`/`RoomHost` are reused verbatim by the phase-3 master)
- Modify: `README.md` (staged at MERGE time per repo policy — one release-notes line)

**Interfaces:**
- `LatencyProxy(int listenPort0ForEphemeral, String targetHost, int targetPort, long delayMillis)` — plain TCP forwarder: accepts one client, connects to the target, two pump threads each delaying every read by `delayMillis` before forwarding. `int port()`, `void close()`. Byte-transparent: WebSocket runs through it unmodified.
- `TestDirectConnectEndToEnd` — the phase-2 acceptance gate (main spec §10 "Integration"): one JVM, real sockets — host server + host's own client + one remote client through a 120 ms `LatencyProxy`; a short 4-second round end to end:
  1. Start server (OPEN policy, `windowSeconds = 4`), connect host client direct + guest client via proxy.
  2. Guest sends chat → host client receives the broadcast through the room.
  3. Host client sends `RoundConfigure` → both clients receive `RoundStart`; wait out the 3 s countdown.
  4. Both clients simulate 60-frame attempts (drive `GhostStreamPublisher` + `AttemptStart`/`AttemptFinish` with distinct times).
  5. Assert: both receive `StandingsDelta` ranking the faster time first; each receives the OTHER's ghost frames via aggregates (proxy delay does not corrupt ordering: feed a `RemoteGhostPlayback` and assert it eventually renders); after the deadline both receive `RoundEnd` with final standings.
  6. Guest disconnects → host client receives shrunken `RoomState`.

- [ ] **Step 1: Write LatencyProxy**

```java
package com.openggf.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** Test-only TCP forwarder injecting fixed one-way delay (main spec §10 integration rig). */
public final class LatencyProxy implements AutoCloseable {
    private final ServerSocket server;
    private final List<Socket> sockets = new ArrayList<>();
    private final Thread acceptor;
    private volatile boolean closed;

    public LatencyProxy(String targetHost, int targetPort, long delayMillis) throws IOException {
        server = new ServerSocket(0);
        acceptor = new Thread(() -> {
            try {
                while (!closed) {
                    Socket client = server.accept();
                    Socket target = new Socket(targetHost, targetPort);
                    synchronized (sockets) {
                        sockets.add(client);
                        sockets.add(target);
                    }
                    pump(client.getInputStream(), target.getOutputStream(), delayMillis);
                    pump(target.getInputStream(), client.getOutputStream(), delayMillis);
                }
            } catch (IOException ignored) {
                // closed
            }
        }, "latency-proxy-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private static void pump(InputStream in, OutputStream out, long delayMillis) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (Exception ignored) {
                // closed
            }
        }, "latency-proxy-pump");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        synchronized (sockets) {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}
```

- [ ] **Step 2: Write the end-to-end test**

Structure (write it fully — the helpers mirror `TestRaceClientLoopback`'s `await` pattern):

```java
package com.openggf.net;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.net.client.GhostStreamPublisher;
import com.openggf.net.client.RaceClient;
import com.openggf.net.client.RemoteGhostPlayback;
import com.openggf.net.host.RaceHostServer;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;
import com.openggf.net.hub.TrackValidationProfileSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-2 acceptance: a full LAN round in one JVM with injected latency (main spec §10). */
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

    private static RaceClient.InboundEvent await(RaceClient client, Predicate<RaceClient.InboundEvent> match,
                                                 long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (RaceClient.InboundEvent event : client.drainInbound()) {
                if (match.test(event)) return event;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }

    private static boolean isMsg(RaceClient.InboundEvent e, Class<?> type) {
        return e instanceof RaceClient.Control c && type.isInstance(c.message());
    }

    private static GhostFrame frame(int x) {
        return new GhostFrame(x, 100, 1, false, false, false, 2, false);
    }

    /** Simulates a finished attempt: streams `frames` ghost frames then reports `timeFrames`. */
    private static void runAttempt(RaceClient client, int attemptId, int frames, int timeFrames) {
        GhostStreamPublisher publisher = new GhostStreamPublisher(client::sendBinary);
        client.sendControl(new ControlMessage.AttemptStart(attemptId));
        publisher.beginAttempt(attemptId);
        for (int i = 0; i < frames; i++) {
            publisher.onFrame(frame(100 + i));
        }
        publisher.finishAttempt();
        client.sendControl(new ControlMessage.AttemptFinish(attemptId, timeFrames, 5, 5 + timeFrames,
                "ab".repeat(32), java.util.HexFormat.of().formatHex(publisher.streamHashSha256()), null));
    }

    @Test
    void fullRoundWithLatencyProxiedGuest(@TempDir Path dir) throws Exception {
        server = RaceHostServer.start(0, new RoomHostConfig("E2E", "s3k", 0, 0, "OPEN", null, 8, FP),
                PlayerIdentity.loadOrCreate(dir.resolve("host")), TrackValidationProfileSource.none());
        proxy = new LatencyProxy("127.0.0.1", server.port(), 120);

        RaceClient host = RaceClient.connect(URI.create("ws://127.0.0.1:" + server.port() + "/race"),
                PlayerIdentity.loadOrCreate(dir.resolve("host")), "HOST", FP).get(10, TimeUnit.SECONDS);
        RaceClient guest = RaceClient.connect(URI.create("ws://127.0.0.1:" + proxy.port() + "/race"),
                PlayerIdentity.loadOrCreate(dir.resolve("guest")), "GUEST", FP).get(10, TimeUnit.SECONDS);

        // chat crosses the room despite latency
        guest.sendControl(new ControlMessage.Chat("gl hf"));
        await(host, e -> isMsg(e, ControlMessage.ChatBroadcast.class), 10_000);

        // host starts a 4s round through its own client connection
        host.sendControl(new ControlMessage.RoundConfigure(
                new ControlMessage.RoundConfig("s3k", 0, 0, 4, "OPEN", null)));
        ControlMessage.RoundStart start = (ControlMessage.RoundStart)
                ((RaceClient.Control) await(host, e -> isMsg(e, ControlMessage.RoundStart.class), 10_000)).message();
        await(guest, e -> isMsg(e, ControlMessage.RoundStart.class), 10_000);
        Thread.sleep(Math.max(0, 3100)); // countdown

        runAttempt(host, 1, 30, 3600);
        runAttempt(guest, 1, 30, 3000); // guest is faster

        // standings rank guest first on both clients
        RaceClient.InboundEvent delta = await(host, e -> isMsg(e, ControlMessage.StandingsDelta.class)
                && ((ControlMessage.StandingsDelta) ((RaceClient.Control) e).message()).rows().size() == 2, 15_000);
        List<ControlMessage.StandingsRow> rows =
                ((ControlMessage.StandingsDelta) ((RaceClient.Control) delta).message()).rows();
        assertEquals("GUEST", rows.get(0).displayName());
        assertEquals(3000, rows.get(0).bestTimeFrames());

        // each side receives the other's ghost frames; playback renders through the jitter buffer
        RemoteGhostPlayback playback = new RemoteGhostPlayback();
        long deadline = System.currentTimeMillis() + 15_000;
        boolean rendered = false;
        while (!rendered && System.currentTimeMillis() < deadline) {
            for (RaceClient.InboundEvent event : guest.drainInbound()) {
                if (event instanceof RaceClient.GhostData ghost) {
                    ghost.aggregate().entries().forEach(playback::onEntry);
                }
            }
            rendered = playback.advance().isPresent();
            Thread.sleep(20);
        }
        assertTrue(rendered, "guest never rendered the host ghost through 120ms latency");

        // round ends at the deadline on both clients
        await(host, e -> isMsg(e, ControlMessage.RoundEnd.class), 15_000);
        await(guest, e -> isMsg(e, ControlMessage.RoundEnd.class), 15_000);

        // guest leaves; host sees the roster shrink
        guest.close();
        await(host, e -> isMsg(e, ControlMessage.RoomState.class)
                && ((ControlMessage.RoomState) ((RaceClient.Control) e).message()).players().size() == 1, 10_000);
        host.close();
    }
}
```

- [ ] **Step 3: Run the end-to-end test**

Run: `mvn "-Dtest=com.openggf.net.TestDirectConnectEndToEnd" test`
Expected: PASS in well under the 120 s timeout (~20 s wall including the live 4 s round + 3 s countdown).

- [ ] **Step 4: Docs**

- `CLAUDE.md` + `AGENTS.md`: add a short section under the architecture notes: packages `net.protocol`/`net.hub`/`net.host`/`net.client` (engine-free, ArchUnit-fenced, only `GhostFrame`/`GhostFrameCodec` shared), hub single-thread contract, `RoomHost`/`GhostHub` reused verbatim by the phase-3 master, engine glue lives in `game.timeattack.mp`.
- CHANGELOG already updated in Task 1 — extend the entry if scope shifted.
- README release-notes line is staged at merge time (the repo's merge-into-`develop` rule; this branch's merge target is the owner's call — apply the rule at whatever merge lands it), not now.

- [ ] **Step 5: Full verification sweep**

```bash
mvn test
```

Expected: full suite green — specifically the phase-1 timeattack/ghost tests, all `com.openggf.net.*` tests, `TestNetIsolationRules`, `TestConfigCatalog`, `TestArchUnitRules`, and the must-keep-green S3K set (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`). Trace suites unaffected (no gameplay-physics changes). Manual LAN smoke (two machines or two instances) is the release-gate follow-up, not part of this task.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/openggf/net/LatencyProxy.java src/test/java/com/openggf/net/TestDirectConnectEndToEnd.java CLAUDE.md AGENTS.md
git commit -m "test(timeattack): direct-connect end-to-end round with latency proxy

Changelog: n/a: test and docs only
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: updated
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task dependency notes for parallel execution

- Tasks 1–2 (protocol) unblock everything; 3–10 are pure/headless and depend only on 1–2 (+ phase-1 pure classes already on the branch).
- Tasks 4→5→6→7 are sequential within `net.hub`. Tasks 8, 9, 10 (`net.client` pure) are parallel to the hub chain after Task 3.
- Task 11 needs 7; Task 12 needs 11. Tasks 13–15 need the as-built phase-1 engine code and are the ONLY tasks that do. Phases 1 and 2 share the branch (`feature/multiplayer-time-attack`), so "phase 1 available" simply means its task commits are on the branch; if the phase-1 worktree is still mid-implementation when the executor reaches Task 13, everything through Task 12 + 16 can land first.
- Task 16 can run any time after Task 12. Task 17 last.

## Deferred-to-phase-3 checklist (recorded so nothing silently drops)

- Relay routing + master server + server browser; roster channel (packet type 0x03 reserved); spatial bucketing; backpressure degradation ladder (queue-depth thresholds of main spec §4.5 — phase 2 has only Netty's own write buffering); TLS; `IdentityStore`/trust ladder/sanctions; attack-mode PoW; identity-creation PoW stamp; protocol fuzzing + adversarial `GhostLoadTestTool` modes in CI; `TrackValidationProfile` export tool + bundled table (phase 2 uses the live-level source only).
