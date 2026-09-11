# Time Attack Phase 4 — Polish (Podium, Track Vote, Badges, Spectate, Minimap) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the main spec's §11 phase-4 polish: podium presentation at round end, next-track vote (3 options), open-character standings badges + ghost nameplates/opacity states, spectate pan after finishing, and a minimap fed by the phase-3 roster channel.

**Architecture:** All round-flow authority stays in the engine-free hub (`HostRoundEngine` gains a `VOTE` phase and a vote tally; identical for player-host and relay). Everything visual is client-side presentation in `com.openggf.game.timeattack.mp` over data the protocol already carries (`RoundEnd` final standings, `StandingsRow.character`, roster `FarPlayer`s). Three new control messages (`TrackVoteOffer`/`TrackVoteTally`/`TrackVoteResult`) activate the reserved `TrackVote` message.

**Tech Stack:** Java 21, Jackson (control JSON), Netty/JDK WebSocket (already in place from phases 2–3), JUnit 5, existing `PixelFont` HUD rendering.

## Global Constraints

- Branch: `feature/multiplayer-time-attack` (owner-set name; based on `next`, NOT develop). All commits carry the repo's doc trailers (`prepare-commit-msg` auto-appends the block — fill it in; run `git config core.hooksPath .githooks` once if hooks aren't installed).
- Prerequisite: phases 1–3 are implemented on the branch (phase 1 is; phases 2–3 execute from their own plans first). Task 0 verifies.
- JUnit 5 / Jupiter only. No JUnit 4 imports.
- ArchUnit fence (`TestNetIsolationRules`): `com.openggf.net.protocol..`, `net.hub..`, `net.host..`, `net.client..`, `net.identity..`, `net.master..` must stay engine-free (only JDK/Netty/Jackson + `com.openggf.game.ghost.GhostFrame`/`GhostFrameCodec`). **All phase-4 engine glue goes in `com.openggf.game.timeattack.mp` or `com.openggf.game.timeattack` — never in `net.*`.**
- Trust model: v1 rooms stay casual/"unverified times". Nothing in this plan verifies times; podium reads the same client-reported standings.
- New config keys need `SonicConfiguration` enum constant + `ConfigCatalog` meta + a CONFIGURATION.md row (`TestConfigCatalog` gates this).
- PowerShell: quote Maven props, e.g. `mvn "-Dtest=com.openggf.net.hub.TestHostRoundEngineVote" test`.
- Track keys are strings `gameId + ":" + zone + ":" + act` (e.g. `"s3k:0:0"`) — the same key format `BundledProfileSource` uses.

## Contracts consumed from phases 1–3 (all on the shared branch)

- `ControlMessage` (sealed, `com.openggf.net.protocol`): `record TrackVote(String trackKey)` — **defined + Jackson-registered since phase 2, sent by nobody**; `record RoundConfig(String gameId, int zone, int act, int windowSeconds, String characterPolicy, String lockedCharacter)` (`characterPolicy` is `"LOCKED"`/`"OPEN"`); `record StandingsRow(int slot, String displayName, String character, int bestTimeFrames, int rank)`; `record RoundEnd(List<StandingsRow> finalStandings)`; `record RoomCreate(RoomDescriptor room, String routing, int directPort, String determinismFingerprint)`; `record Heartbeat(String roomId, int playerCount)`.
- `com.openggf.net.hub.HostRoundEngine`: `enum Phase { LOBBY, COUNTDOWN, RUNNING, ROUND_END }`, `ROUND_END_LINGER_MILLIS = 10_000`, ctor `(LongSupplier hubClockMillis, Consumer<ControlMessage> broadcaster)`, `boolean startRound(RoundConfig)`, `void onTick()`, `Phase phase()`, `List<StandingsRow> standings()`.
- `com.openggf.net.host.RoomHost` + `RoomHostConfig(String roomName, String gameId, int zone, int act, String characterPolicy, String lockedCharacter, int maxPlayers, String requiredDeterminismFingerprint)`; RoomHost currently **rejects** `RoundConfigure` for a different track (`// phase 2: one-track rooms; track vote is phase 4`).
- `com.openggf.net.client.ClientRaceSession`: `enum Phase { LOBBY, COUNTDOWN, RUNNING, ROUND_END }`, `void onControl(ControlMessage)`, `standings()`, `players()`, `roundConfig()`, `localSlot()`, `hubNowEstimateMillis()`.
- `com.openggf.net.client.RemoteGhostRegistry`: `record RemoteGhost(int slot, String displayName, String character, RemoteGhostPlayback.RenderState state)`, `List<RemoteGhost> advanceAll(int excludeSlot)`, `record FarPlayer(int slot, String displayName, String character, int cellX, int cellY, int status)` (64-px cells), `List<FarPlayer> farPlayers(int excludeSlot)`.
- `com.openggf.game.timeattack.mp.MultiplayerRaceCoordinator`: implements `TimeAttackRuntime.AttemptListener`; `pump()`, `holdGameplay()`, `afterLevelFrame()`, `hudState()`, `attachRuntime(TimeAttackRuntime)`, owns the `RemoteGhostRegistry` + cached `advanceAll` result; `remoteActiveGhosts()` maps to `ActiveGhost("net:" + slot, character, state.frame())`.
- `com.openggf.game.timeattack.mp.MultiplayerHudRenderer` / `MultiplayerHudState(boolean active, String phase, long remainingWindowMillis, long remainingCountdownMillis, List<StandingsRow> standings, List<String> chatLines, boolean connectionLost, String kickReason, int totalPlayers, List<FarPlayer> farPlayers)` (last two added by phase-3 Task 15).
- `com.openggf.game.timeattack.TimeAttackRuntime`: `isActive()`, `isAttemptActive()` (ARMED|RUNNING), `voidCurrentAttempt()`, `TimeAttackAttempt.Phase { ARMED, RUNNING, FINISHED, VOID }`; `TimeAttackTrackCatalog.tracksFor(gameId)` → `Track(gameId, zone, act, label, characters)`; `TimeAttackTimeFormat.frames(int)`; ghosts render via `ActiveGhost(String slotId, String characterCode, GhostFrame frame)` → `GhostRenderer.renderForLayer(...)` (already distance-fades).
- Engine: `com.openggf.camera.Camera` — `setFrozen(boolean)`, `getFrozen()`, `setX(short)/setY(short)`, `getMinX()/getMaxX()/getMinY()/getMaxY()`; `com.openggf.control.InputHandler.isKeyPressed(int)`, `.logical()` → `LogicalInputSnapshot(menuUp, menuDown, menuLeft, menuRight, menuAccept, menuBack, …)`.
- `GameLoop.updateLevelMode` already calls `coordinator.pump()` / `holdGameplay()` / `afterLevelFrame()` per frame (phase-2 Task 14).

## What phase 4 explicitly does NOT build (deferred to the verifier plan / post-v1)

- Anything verification-related: `RecordingRequest`, uploads, verdicts, `verified=true`, podium-waits-for-verdicts (the verifier plan extends the podium linger).
- Community moderation, ranked leaderboards, OAuth.
- A graphical 2D minimap — v1 minimap is a text progress strip over the roster channel (PixelFont is the only HUD drawing surface; a textured minimap is future work).

---

### Task 0: Branch check

**Files:** none (verification only).

- [ ] **Step 1: Verify branch + prerequisites**

```bash
git checkout feature/multiplayer-time-attack && git pull --ff-only
test -f src/main/java/com/openggf/net/hub/HostRoundEngine.java && \
test -f src/main/java/com/openggf/net/master/MasterServerMain.java && \
test -f src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java && echo PREREQS-OK
```

Expected: `PREREQS-OK`. If any file is missing, STOP — phases 2–3 must be executed first (their plans are in this directory).

- [ ] **Step 2: Baseline build**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

---

### Task 1: Protocol — vote messages + vote pool threading

**Files:**
- Modify: `src/main/java/com/openggf/net/protocol/ControlMessage.java`
- Modify: `src/main/java/com/openggf/net/protocol/Protocol.java` (if message-type registry constants live there; otherwise the `@JsonSubTypes` list on `ControlMessage`)
- Test: `src/test/java/com/openggf/net/protocol/TestVoteMessages.java`

**Interfaces:**
- Consumes: existing `ControlMessage` sealed hierarchy, `ControlCodec.encode/decode`, `RoomCreate`, `Heartbeat`.
- Produces (later tasks rely on these exact shapes):
  - `record TrackVoteOffer(List<String> trackKeys, long voteEndsAtHubMillis) implements ControlMessage {}`
  - `record VoteCount(String trackKey, int votes) {}` — nested plain data record (like `StandingsRow`, NOT a message)
  - `record TrackVoteTally(List<VoteCount> counts) implements ControlMessage {}`
  - `record TrackVoteResult(String trackKey) implements ControlMessage {}`
  - `RoomCreate` gains a final component: `List<String> voteTrackKeys` (empty/never null = vote disabled)
  - `record RoomTrackUpdate(String roomId, int zone, int act) implements ControlMessage {}` (host → master, direct rooms, after a vote changes the track)

- [ ] **Step 1: Write the failing round-trip test**

```java
package com.openggf.net.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestVoteMessages {

    @Test
    void voteMessagesRoundTripThroughCodec() throws Exception {
        ControlMessage[] messages = {
                new ControlMessage.TrackVoteOffer(List.of("s3k:0:0", "s3k:0:1", "s3k:1:0"), 123_456L),
                new ControlMessage.TrackVoteTally(List.of(
                        new ControlMessage.VoteCount("s3k:0:0", 2),
                        new ControlMessage.VoteCount("s3k:0:1", 0))),
                new ControlMessage.TrackVoteResult("s3k:0:1"),
                new ControlMessage.RoomTrackUpdate("r-1", 0, 1),
        };
        for (ControlMessage message : messages) {
            String wire = ControlCodec.encode("tok", message);
            ControlCodec.DecodedControl decoded = ControlCodec.decode(wire);
            assertEquals(message, decoded.message());
        }
    }

    @Test
    void roomCreateCarriesVotePool() throws Exception {
        ControlMessage.RoomDescriptor desc = new ControlMessage.RoomDescriptor(
                "room", "s3k", 0, 0, "OPEN", null, 16, false);
        ControlMessage.RoomCreate create = new ControlMessage.RoomCreate(
                desc, "RELAY", 0, "0.6:cafe1234", List.of("s3k:0:0", "s3k:0:1"));
        ControlCodec.DecodedControl decoded = ControlCodec.decode(ControlCodec.encode("tok", create));
        assertEquals(create, decoded.message());
        assertEquals(2, ((ControlMessage.RoomCreate) decoded.message()).voteTrackKeys().size());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.protocol.TestVoteMessages" test`
Expected: COMPILE FAILURE (`TrackVoteOffer` not defined).

- [ ] **Step 3: Implement**

In `ControlMessage.java`: add the four records + `VoteCount` exactly as in Interfaces, add `voteTrackKeys` as the last component of `RoomCreate`, extend the sealed `permits` list, and register the three new messages in `@JsonSubTypes` with names `"TrackVoteOffer"`, `"TrackVoteTally"`, `"TrackVoteResult"`, `"RoomTrackUpdate"`. Call sites: TEST call sites (phase-3 broker/registry tests) append `List.of()`; the PRODUCTION path must not silently lose the pool — `MasterClient.createRoom` (phase-3 Task 13: `createRoom(RoomDescriptor descriptor, String routing, int directPort, String determinismFingerprint)`) gains a final `List<String> voteTrackKeys` parameter threaded into the `RoomCreate` message, and both production creators pass the catalog pool (Task 5: `Engine.hostTimeAttackRoom` for LAN rooms via `RoomHostConfig`, `ServerBrowserScreen` CREATE for master-brokered DIRECT and RELAY rooms via `MasterClient`). Hygiene caps in the decode/validation layer (wherever phase 2 Task 1 put per-type checks): `voteTrackKeys.size() <= 32`, each key `length() <= 32` and matching `[a-z0-9]{1,8}:[0-9]{1,3}:[0-9]{1,3}`; `TrackVoteOffer.trackKeys.size() <= 8`.

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.net.protocol.TestVoteMessages" test` → PASS.
Run: `mvn "-Dtest=com.openggf.net.protocol.*Test*,com.openggf.tests.TestNetIsolationRules" test` → PASS (no fence break, no broken call sites).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/protocol/ src/test/java/com/openggf/net/protocol/TestVoteMessages.java
git commit -m "feat(net): track-vote control messages + room vote pool"
```
Trailers: `Changelog: updated` is NOT required per-commit mid-phase; the final task stages the CHANGELOG. Use `Changelog: n/a: changelog updated once at end of phase-4 plan` on intermediate `feat` commits touching `src/main/`.

---

### Task 2: HostRoundEngine — VOTE phase, tally, next-config

**Files:**
- Modify: `src/main/java/com/openggf/net/hub/HostRoundEngine.java`
- Test: `src/test/java/com/openggf/net/hub/TestHostRoundEngineVote.java`

**Interfaces:**
- Consumes: Task 1 messages; existing `HostRoundEngine` phase machine and `broadcaster`.
- Produces:
  - `Phase` enum gains `VOTE` (full set: `LOBBY, COUNTDOWN, RUNNING, ROUND_END, VOTE`)
  - `public static final long VOTE_WINDOW_MILLIS = 15_000;` `public static final int VOTE_OPTION_COUNT = 3;`
  - `public void setVoteTrackPool(List<String> trackKeys)` — delegates to the 2-arg overload with a null predicate; `public void setVoteTrackPool(List<String> trackKeys, java.util.function.Predicate<String> knownTrack)` — empty pool ⇒ voting disabled (today's ROUND_END→LOBBY behavior unchanged). Pool keys are re-validated per round when options are picked (`isEligibleVoteKey`): parseable `game:zone:act`, same `gameId` as the active round config, not the current track, **and accepted by `knownTrack` when one is supplied** — the engine is fence-bound and cannot consult `TimeAttackTrackCatalog` itself, so track EXISTENCE is an injected predicate (relay rooms: bundled-profile-backed, Task 3; player-host rooms: null — the pool is built from the host's own catalog by `VoteTrackPools`, and a modified LAN host already owns its whole round engine). Malformed, cross-game, unknown, or stale entries are silently excluded, so `closeVote()`'s winner parse can never throw, a vote can never switch the room's game (the descriptor/registry track updates only carry zone/act), and `votedNextConfig` can never name a track that does not exist
  - `public void onTrackVote(int slot, String trackKey)` — ignored outside VOTE / for non-offered keys; last vote per slot wins; broadcasts `TrackVoteTally`
  - `public List<String> voteOptions()` — current offer ([] outside VOTE)
  - `public ControlMessage.RoundConfig votedNextConfig()` — non-null after a vote completes WITH a winner, cleared by `startRound`; stays null on a zero-vote close (current track retained)
  - `closeVote()` ALWAYS broadcasts a `TrackVoteResult` — with the winner, or with the CURRENT track key when no valid votes were cast. Clients only leave `VOTE` on `TrackVoteResult` (Task 4), so a silent zero-vote close would strand every client in the vote screen
  - `startRound` is legal from `LOBBY` always; from `ROUND_END` only when the vote pool is empty (a vote room must let its vote run — otherwise the host can start during the linger and bypass the configured vote); never during `VOTE`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.openggf.net.protocol.ControlMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestHostRoundEngineVote {

    private long now;
    private final List<ControlMessage> sent = new ArrayList<>();
    private HostRoundEngine engine;

    private static ControlMessage.RoundConfig config() {
        return new ControlMessage.RoundConfig("s3k", 0, 0, 120, "OPEN", null);
    }

    @BeforeEach
    void setUp() {
        now = 1_000_000L;
        engine = new HostRoundEngine(() -> now, sent::add);
        engine.setVoteTrackPool(List.of("s3k:0:0", "s3k:0:1", "s3k:1:0", "s3k:1:1"));
    }

    /** Runs a full round to ROUND_END expiry. */
    private void runRoundToEnd() {
        assertTrue(engine.startRound(config()));
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();                                  // -> RUNNING
        now += config().windowSeconds() * 1000L;
        engine.onTick();                                  // -> ROUND_END
        assertEquals(HostRoundEngine.Phase.ROUND_END, engine.phase());
        now += HostRoundEngine.ROUND_END_LINGER_MILLIS;
        engine.onTick();                                  // -> VOTE (pool present)
    }

    @Test
    void roundEndEntersVoteAndOffersThreeOptionsExcludingCurrentTrack() {
        runRoundToEnd();
        assertEquals(HostRoundEngine.Phase.VOTE, engine.phase());
        List<String> options = engine.voteOptions();
        assertEquals(3, options.size());
        assertFalse(options.contains("s3k:0:0"), "current track must not be offered");
        ControlMessage.TrackVoteOffer offer = sent.stream()
                .filter(m -> m instanceof ControlMessage.TrackVoteOffer)
                .map(m -> (ControlMessage.TrackVoteOffer) m)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(options, offer.trackKeys());
        assertEquals(now + HostRoundEngine.VOTE_WINDOW_MILLIS, offer.voteEndsAtHubMillis());
    }

    @Test
    void majorityWinsAndBecomesNextConfig() {
        runRoundToEnd();
        List<String> options = engine.voteOptions();
        engine.onTrackVote(1, options.get(1));
        engine.onTrackVote(2, options.get(1));
        engine.onTrackVote(3, options.get(0));
        now += HostRoundEngine.VOTE_WINDOW_MILLIS;
        engine.onTick();                                  // vote closes -> LOBBY
        assertEquals(HostRoundEngine.Phase.LOBBY, engine.phase());
        ControlMessage.TrackVoteResult result = sent.stream()
                .filter(m -> m instanceof ControlMessage.TrackVoteResult)
                .map(m -> (ControlMessage.TrackVoteResult) m)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(options.get(1), result.trackKey());
        ControlMessage.RoundConfig next = engine.votedNextConfig();
        assertNotNull(next);
        String[] parts = options.get(1).split(":");
        assertEquals(parts[0], next.gameId());
        assertEquals(Integer.parseInt(parts[1]), next.zone());
        assertEquals(Integer.parseInt(parts[2]), next.act());
        assertEquals(120, next.windowSeconds());
        assertEquals("OPEN", next.characterPolicy());
    }

    @Test
    void lastVotePerSlotWinsAndTallyBroadcast() {
        runRoundToEnd();
        List<String> options = engine.voteOptions();
        engine.onTrackVote(1, options.get(0));
        engine.onTrackVote(1, options.get(2));            // revote: replaces
        ControlMessage.TrackVoteTally tally = sent.stream()
                .filter(m -> m instanceof ControlMessage.TrackVoteTally)
                .map(m -> (ControlMessage.TrackVoteTally) m)
                .reduce((a, b) -> b).orElseThrow();
        int total = tally.counts().stream().mapToInt(ControlMessage.VoteCount::votes).sum();
        assertEquals(1, total);
        assertEquals(options.get(2), tally.counts().stream()
                .filter(c -> c.votes() == 1).findFirst().orElseThrow().trackKey());
    }

    @Test
    void tieBreaksToEarliestOfferedAndZeroVotesKeepsCurrentTrack() {
        runRoundToEnd();
        List<String> options = engine.voteOptions();
        engine.onTrackVote(1, options.get(2));
        engine.onTrackVote(2, options.get(0));            // 1-1 tie
        now += HostRoundEngine.VOTE_WINDOW_MILLIS;
        engine.onTick();
        assertEquals(options.get(0), engine.votedNextConfig().gameId() + ":"
                + engine.votedNextConfig().zone() + ":" + engine.votedNextConfig().act());

        // second round: nobody votes -> current track retained, result STILL broadcast
        ControlMessage.RoundConfig second = engine.votedNextConfig();
        assertTrue(engine.startRound(second));
        assertNull(engine.votedNextConfig(), "startRound clears the voted config");
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();
        now += 120_000L;
        engine.onTick();
        now += HostRoundEngine.ROUND_END_LINGER_MILLIS;
        engine.onTick();
        now += HostRoundEngine.VOTE_WINDOW_MILLIS;
        engine.onTick();
        assertNull(engine.votedNextConfig());
        assertEquals(HostRoundEngine.Phase.LOBBY, engine.phase());
        ControlMessage.TrackVoteResult retained = sent.stream()
                .filter(m -> m instanceof ControlMessage.TrackVoteResult)
                .map(m -> (ControlMessage.TrackVoteResult) m)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(second.gameId() + ":" + second.zone() + ":" + second.act(), retained.trackKey(),
                "zero votes must still broadcast a result — clients only leave VOTE on TrackVoteResult");
    }

    @Test
    void emptyPoolSkipsVoteEntirely() {
        engine = new HostRoundEngine(() -> now, sent::add);   // no pool set
        assertTrue(engine.startRound(config()));
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();
        now += 120_000L;
        engine.onTick();
        now += HostRoundEngine.ROUND_END_LINGER_MILLIS;
        engine.onTick();
        assertEquals(HostRoundEngine.Phase.LOBBY, engine.phase());
        assertTrue(sent.stream().noneMatch(m -> m instanceof ControlMessage.TrackVoteOffer));
    }

    @Test
    void invalidVotesIgnored() {
        runRoundToEnd();
        engine.onTrackVote(1, "s2:99:99");                // not offered
        engine.onTrackVote(1, null);
        now += HostRoundEngine.VOTE_WINDOW_MILLIS;
        engine.onTick();
        assertNull(engine.votedNextConfig());             // zero valid votes
    }

    @Test
    void startRoundRejectedDuringVote() {
        runRoundToEnd();
        assertFalse(engine.startRound(config()));
        assertEquals(HostRoundEngine.Phase.VOTE, engine.phase());
    }

    @Test
    void malformedCrossGameAndUnknownKeysNeverOffered() {
        engine = new HostRoundEngine(() -> now, sent::add);
        // "s3k:99:99" is parseable and same-game but NOT a real track — only the
        // injected knownTrack predicate can catch it (the engine is fence-bound
        // and cannot consult TimeAttackTrackCatalog itself).
        engine.setVoteTrackPool(
                List.of("bad", "s2:0:0", "s3k:9:9:9", "s3k:x:0", "s3k:99:99", "s3k:0:1", "s3k:1:0"),
                java.util.Set.of("s3k:0:0", "s3k:0:1", "s3k:1:0")::contains);
        runRoundToEnd();
        assertEquals(HostRoundEngine.Phase.VOTE, engine.phase());
        assertEquals(List.of("s3k:0:1", "s3k:1:0"), engine.voteOptions(),
                "only parseable, same-game, KNOWN tracks may be offered — a vote must never crash the close,"
                        + " switch gameId, or land the room on a nonexistent track");
    }

    @Test
    void startRoundDuringRoundEndOnlyLegalWithoutVotePool() {
        assertTrue(engine.startRound(config()));
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();
        now += config().windowSeconds() * 1000L;
        engine.onTick();
        assertEquals(HostRoundEngine.Phase.ROUND_END, engine.phase());
        assertFalse(engine.startRound(config()), "a vote room cannot skip its vote from the linger");

        HostRoundEngine plain = new HostRoundEngine(() -> now, sent::add); // no pool
        assertTrue(plain.startRound(config()));
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        plain.onTick();
        now += config().windowSeconds() * 1000L;
        plain.onTick();
        assertEquals(HostRoundEngine.Phase.ROUND_END, plain.phase());
        assertTrue(plain.startRound(config()), "no-pool rooms keep the phase-2/3 ROUND_END restart");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestHostRoundEngineVote" test`
Expected: COMPILE FAILURE (`Phase.VOTE`, `setVoteTrackPool` missing).

- [ ] **Step 3: Implement**

Add to `HostRoundEngine`:

```java
public static final long VOTE_WINDOW_MILLIS = 15_000;
public static final int VOTE_OPTION_COUNT = 3;

private final List<String> voteTrackPool = new ArrayList<>();
private java.util.function.Predicate<String> knownTrack;
private int voteRotation;
private List<String> voteOptions = List.of();
private final Map<Integer, String> votesBySlot = new LinkedHashMap<>();
private long voteEndsAt;
private ControlMessage.RoundConfig votedNextConfig;

public void setVoteTrackPool(List<String> trackKeys) {
    setVoteTrackPool(trackKeys, null);
}

public void setVoteTrackPool(List<String> trackKeys, java.util.function.Predicate<String> knownTrack) {
    this.knownTrack = knownTrack;
    voteTrackPool.clear();
    if (trackKeys != null) {
        trackKeys.stream().filter(Objects::nonNull).distinct().forEach(voteTrackPool::add);
    }
}

public List<String> voteOptions() {
    return List.copyOf(voteOptions);
}

public ControlMessage.RoundConfig votedNextConfig() {
    return votedNextConfig;
}

public void onTrackVote(int slot, String trackKey) {
    if (phase != Phase.VOTE || trackKey == null || !voteOptions.contains(trackKey)) {
        return;
    }
    votesBySlot.put(slot, trackKey);
    broadcaster.accept(new ControlMessage.TrackVoteTally(currentTally()));
}

private List<ControlMessage.VoteCount> currentTally() {
    List<ControlMessage.VoteCount> counts = new ArrayList<>();
    for (String option : voteOptions) {
        int votes = (int) votesBySlot.values().stream().filter(option::equals).count();
        counts.add(new ControlMessage.VoteCount(option, votes));
    }
    return counts;
}

private String currentTrackKey() {
    ControlMessage.RoundConfig c = currentConfig;   // the field startRound stored
    return c == null ? "" : c.gameId() + ":" + c.zone() + ":" + c.act();
}

private void beginVote() {
    voteOptions = pickVoteOptions();
    if (voteOptions.size() < 2) {          // not enough candidates -> skip vote
        voteOptions = List.of();
        phase = Phase.LOBBY;
        return;
    }
    votesBySlot.clear();
    voteEndsAt = clock.getAsLong() + VOTE_WINDOW_MILLIS;
    phase = Phase.VOTE;
    broadcaster.accept(new ControlMessage.TrackVoteOffer(voteOptions, voteEndsAt));
}

/** Malformed, cross-game, unknown, or current-track keys are never offered — a hostile or
 *  stale pool entry must not crash the winner parse, switch the room's game via a vote,
 *  or vote the room onto a track that does not exist. */
private boolean isEligibleVoteKey(String key) {
    if (key == null || key.equals(currentTrackKey())) {
        return false;
    }
    String[] parts = key.split(":");
    if (parts.length != 3 || currentConfig == null || !parts[0].equals(currentConfig.gameId())) {
        return false;
    }
    try {
        if (Integer.parseInt(parts[1]) < 0 || Integer.parseInt(parts[2]) < 0) {
            return false;
        }
    } catch (NumberFormatException e) {
        return false;
    }
    return knownTrack == null || knownTrack.test(key);
}

private List<String> pickVoteOptions() {
    List<String> candidates = voteTrackPool.stream()
            .filter(this::isEligibleVoteKey)
            .toList();
    List<String> picked = new ArrayList<>();
    for (int i = 0; i < candidates.size() && picked.size() < VOTE_OPTION_COUNT; i++) {
        picked.add(candidates.get((voteRotation + i) % candidates.size()));
    }
    voteRotation = candidates.isEmpty() ? 0 : (voteRotation + VOTE_OPTION_COUNT) % candidates.size();
    return picked;
}

private void closeVote() {
    String winner = null;
    int best = 0;
    for (String option : voteOptions) {     // earliest-offered wins ties
        int votes = (int) votesBySlot.values().stream().filter(option::equals).count();
        if (votes > best) {
            best = votes;
            winner = option;
        }
    }
    if (winner != null) {
        String[] parts = winner.split(":"); // parseable by construction: options passed isEligibleVoteKey
        votedNextConfig = new ControlMessage.RoundConfig(parts[0],
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                currentConfig.windowSeconds(), currentConfig.characterPolicy(),
                currentConfig.lockedCharacter());
    }
    // ALWAYS broadcast — clients leave VOTE only on TrackVoteResult; a zero-vote close
    // announces the retained current track instead of stranding every client.
    broadcaster.accept(new ControlMessage.TrackVoteResult(
            winner != null ? winner : currentTrackKey()));
    voteOptions = List.of();
    votesBySlot.clear();
    phase = Phase.LOBBY;
}
```

Wire into the existing machine:
- In `onTick()`, replace the `ROUND_END` → `LOBBY` transition body with: if the linger elapsed → `if (voteTrackPool.isEmpty()) { phase = Phase.LOBBY; } else { beginVote(); }`. Add a new branch: `case VOTE -> { if (clock.getAsLong() >= voteEndsAt) closeVote(); }`.
- In `startRound(...)`: reject when `phase == Phase.VOTE`, and when `phase == Phase.ROUND_END && !voteTrackPool.isEmpty()` (vote rooms cannot skip the vote from the linger; no-pool rooms keep the phase-2/3 ROUND_END restart); on success set `votedNextConfig = null`.
- Adjust field names (`phase`, `clock`, `broadcaster`, `currentConfig`) to the actual phase-2 field names in the file — same semantics.

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.net.hub.TestHostRoundEngineVote,com.openggf.net.hub.TestHostRoundEngine*" test`
Expected: PASS, including the existing phase-2/3 engine tests (empty pool preserves old behavior).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/HostRoundEngine.java src/test/java/com/openggf/net/hub/TestHostRoundEngineVote.java
git commit -m "feat(net): VOTE phase with rotating 3-option offer and tally in HostRoundEngine"
```

---

### Task 3: RoomHost + master plumbing — vote dispatch, config acceptance, descriptor refresh

**Files:**
- Modify: `src/main/java/com/openggf/net/host/RoomHost.java` (+ `RoomHostConfig` record in the same package — locate both from the as-built phase-2/3 tree)
- Modify: `src/main/java/com/openggf/net/hub/RoomHostHooks.java` (append the `knownVoteTrack` predicate component)
- Modify: `src/main/java/com/openggf/net/master/SessionRegistry.java`
- Modify: `src/main/java/com/openggf/net/master/RoomBroker.java`
- Modify: `src/main/java/com/openggf/net/master/RelayRoomManager.java`
- Test: `src/test/java/com/openggf/net/host/TestRoomHostVote.java`, extend `src/test/java/com/openggf/net/master/TestSessionRegistry.java`

**Interfaces:**
- Consumes: Task 1 `RoomCreate.voteTrackKeys` / `RoomTrackUpdate`, Task 2 `setVoteTrackPool`/`onTrackVote`/`votedNextConfig`.
- Produces:
  - `RoomHostConfig` gains final component `List<String> voteTrackKeys` (all construction sites updated; direct-connect hosts fill it engine-side in Task 5)
  - `RoomHost` forwards member `TrackVote` → `round.onTrackVote(slot, key)`; accepts a `RoundConfigure` whose track matches EITHER the room's original track OR `round.votedNextConfig()`; on an accepted track change updates its room descriptor and (direct rooms) sends `RoomTrackUpdate` alongside the next `Heartbeat`
  - `SessionRegistry.updateTrack(String roomId, String hostFingerprint, int zone, int act)` — replaces the stored `RoomEntry.descriptor` zone/act (owner-checked); `RoomBroker` dispatches `RoomTrackUpdate` to it; `RelayRoomManager` calls it directly when a relay room's vote completes

- [ ] **Step 1: Write the failing tests**

`TestRoomHostVote` (drive `RoomHost` exactly the way the phase-2 `TestRoomHost*` tests do — same fake-connection harness; copy its setup helper):

```java
@Test
void memberTrackVoteReachesEngineAndVotedTrackIsStartable() {
    // 1. host + 2 members admitted; round runs to ROUND_END then VOTE (advance fake clock)
    // 2. member sends ControlMessage.TrackVote(options.get(1)) -> engine tally reflects it
    // 3. vote closes; host sends RoundConfigure with the WINNER track -> accepted (startRound true)
    // 4. host sends RoundConfigure with an UNOFFERED other track -> rejected (unchanged phase-2 rule)
}

@Test
void voteResultUpdatesRoomDescriptorTrack() {
    // after step 3 above, RoomHost's descriptor zone/act == winner zone/act
}
```

Write these as real tests against the actual phase-2 harness (fake `HubConnection`s, manual clock) — the executor lifts the admission boilerplate from the existing `TestRoomHost` file verbatim.

`TestSessionRegistry` addition:

```java
@Test
void updateTrackReplacesDescriptorForOwnerOnly() {
    SessionRegistry.RoomEntry entry = createRoom();                    // existing helper
    registry.updateTrack(entry.roomId(), entry.hostFingerprint(), 1, 1);
    assertEquals(1, registry.find(entry.roomId()).orElseThrow().descriptor().zone());
    registry.updateTrack(entry.roomId(), "not-the-owner", 2, 0);       // ignored
    assertEquals(1, registry.find(entry.roomId()).orElseThrow().descriptor().zone());
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn "-Dtest=com.openggf.net.host.TestRoomHostVote,com.openggf.net.master.TestSessionRegistry" test` → FAIL/compile error.

- [ ] **Step 3: Implement**

- `RoomHostConfig`: append `List<String> voteTrackKeys`. `RoomHostHooks` gains a final component `java.util.function.Predicate<String> knownVoteTrack` (null = pool trusted as catalog-built; update `none()` and all construction sites). `RoomHost` constructor calls `round.setVoteTrackPool(config.voteTrackKeys(), hooks.knownVoteTrack())`. `RelayRoomManager` supplies the predicate from its `TrackValidationProfileSource` (the bundled table IS the exported catalog):

```java
key -> {
    String[] parts = key.split(":");
    try {
        return parts.length == 3 && profiles.profileFor(parts[0],
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2])).isPresent();
    } catch (NumberFormatException e) {
        return false;
    }
}
```

  Player-host rooms pass null (their pool comes from their own `TimeAttackTrackCatalog` via `VoteTrackPools`; a modified LAN host owns its round engine anyway — the trust boundary this closes is the master's).
- `RoomHost` member dispatch: add `case ControlMessage.TrackVote vote -> round.onTrackVote(memberSlot, vote.trackKey());` (host member may vote too).
- `RoundConfigure` validation: replace the strict same-track check with:

```java
private boolean trackAllowed(ControlMessage.RoundConfig config) {
    if (config.gameId().equals(roomGameId) && config.zone() == roomZone && config.act() == roomAct) {
        return true;
    }
    ControlMessage.RoundConfig voted = round.votedNextConfig();
    return voted != null && voted.gameId().equals(config.gameId())
            && voted.zone() == config.zone() && voted.act() == config.act();
}
```

On acceptance with a changed track: update `roomZone`/`roomAct` (the fields backing the descriptor) so subsequent joins/lobby snapshots advertise the new track, and — when this host is master-registered (direct rooms) — send `new ControlMessage.RoomTrackUpdate(roomId, zone, act)` on the master connection next heartbeat tick.
- `RoomBroker`: dispatch `RoomTrackUpdate` → validate BEFORE applying, with the same `knownTrack` predicate as `RoomCreate` (`SessionRegistry.updateTrack` stays dumb storage — owner check only; validation is the broker's job): look up the entry, build `entry.descriptor().gameId() + ":" + msg.zone() + ":" + msg.act()`, and require `knownTrack.test(key)` → on failure DROP the update and record a violation/strike (creation-time pool validation is worthless if the update path accepts `RoomTrackUpdate(roomId, 99, 99)` later; a legitimate vote-driven update always passes because the winner key was drawn from validated options). Only then `registry.updateTrack(msg.roomId(), senderFingerprint, msg.zone(), msg.act())`. `RoomCreate` additionally validates the pool: every `voteTrackKeys` entry must parse as `game:zone:act` with `game.equals(descriptor.gameId())` AND be a known track — `RoomBroker` gains a `Predicate<String> knownTrack` constructor dependency, wired in `MasterServer` from the same `BundledProfileSource` lambda as the relay hook above (the ROM-free master's track knowledge is the bundled table) — → else `RoomCreateRejected("invalid vote track pool")` (the engine's `isEligibleVoteKey` filter is defense-in-depth, not the only gate — a cross-game, malformed, or NONEXISTENT pool is rejected at the door). Extend the phase-3 `TestRoomBroker` with a `roomCreateRejectsInvalidVotePool` case (`voteTrackKeys = List.of("s2:0:0")` on an `s3k` room → rejected; `List.of("bad")` → rejected; `List.of("s3k:99:99")` → rejected as unknown, using a fake predicate accepting only `s3k:0:0`/`s3k:0:1`; valid known same-game pool → created) and a `roomTrackUpdateRejectsUnknownTrack` case: the OWNER of a created `s3k:0:0` room sends `RoomTrackUpdate(roomId, 99, 99)` → the registry entry's zone/act are unchanged (assert via `registry.find`), then sends `RoomTrackUpdate(roomId, 0, 1)` (known) → applied.
- `SessionRegistry.updateTrack`: owner check, rebuild `RoomEntry` with a descriptor copy carrying the new zone/act.
- `RelayRoomManager`: relay rooms live master-side — after their `RoomHost` accepts a track change (expose a `Runnable onTrackChanged` hook or poll the descriptor on the room tick), call `registry.updateTrack(...)` with the room's host fingerprint.
- Thread the pool: `RoomBroker`'s `RoomCreate` handling passes `create.voteTrackKeys()` into `SessionRegistry.create(...)` → store on `RoomEntry` (append component `List<String> voteTrackKeys`) → `RelayRoomManager.createRelayRoom` copies it into the relay room's `RoomHostConfig`.

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.net.host.*,com.openggf.net.master.*" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/host/ src/main/java/com/openggf/net/master/ src/test/java/com/openggf/net/
git commit -m "feat(net): vote dispatch, voted-track round config, browser descriptor refresh"
```

---

### Task 4: ClientRaceSession — VOTE phase mirror + podium accessors

**Files:**
- Modify: `src/main/java/com/openggf/net/client/ClientRaceSession.java`
- Test: `src/test/java/com/openggf/net/client/TestClientRaceSessionVote.java`

**Interfaces:**
- Consumes: Task 1 messages.
- Produces:
  - client `Phase` enum gains `VOTE`
  - `List<String> voteOptions()` ([] outside VOTE), `List<ControlMessage.VoteCount> voteCounts()`, `long voteRemainingMillis()` (−1 outside VOTE; hub-clock based like `remainingWindowMillis`), `String lastVoteResultTrackKey()` (null until a result, cleared on next `RoundStart`)
  - `List<ControlMessage.StandingsRow> podiumTop(int n)` — first n of the `RoundEnd` final standings ([] before any RoundEnd)
  - `ControlMessage.StandingsRow localStandingsRow()` — row with `slot == localSlot()`, else null

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.client;

import static org.junit.jupiter.api.Assertions.*;

import com.openggf.net.protocol.ControlMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestClientRaceSessionVote {

    @Test
    void voteOfferTallyResultFlow() {
        long[] now = {50_000L};
        ClientRaceSession session = new ClientRaceSession(() -> now[0]);
        session.onControl(new ControlMessage.TrackVoteOffer(List.of("s3k:0:1", "s3k:1:0"), 65_000L));
        assertEquals(ClientRaceSession.Phase.VOTE, session.phase());
        assertEquals(2, session.voteOptions().size());
        assertTrue(session.voteRemainingMillis() > 0);

        session.onControl(new ControlMessage.TrackVoteTally(
                List.of(new ControlMessage.VoteCount("s3k:0:1", 3))));
        assertEquals(3, session.voteCounts().get(0).votes());

        session.onControl(new ControlMessage.TrackVoteResult("s3k:0:1"));
        assertEquals(ClientRaceSession.Phase.LOBBY, session.phase());
        assertEquals("s3k:0:1", session.lastVoteResultTrackKey());
        assertTrue(session.voteOptions().isEmpty());
        assertEquals(-1, session.voteRemainingMillis());
    }

    @Test
    void podiumAccessorsFromRoundEnd() {
        ClientRaceSession session = new ClientRaceSession(() -> 0L);
        // minimal join so localSlot() == 2 — reuse the phase-2 test helper for applyJoin
        session.applyJoin(new ControlMessage.JoinAccepted("tok", 2, null, null));
        session.onControl(new ControlMessage.RoundEnd(List.of(
                new ControlMessage.StandingsRow(0, "ana", "sonic", 3000, 1),
                new ControlMessage.StandingsRow(1, "bob", "tails", 3100, 2),
                new ControlMessage.StandingsRow(2, "you", "knuckles", 3200, 3),
                new ControlMessage.StandingsRow(3, "dan", "sonic", 3300, 4))));
        assertEquals(3, session.podiumTop(3).size());
        assertEquals("ana", session.podiumTop(3).get(0).displayName());
        assertEquals(3, session.localStandingsRow().rank());
    }

    @Test
    void zeroVoteResultWithRetainedTrackStillLeavesVote() {
        ClientRaceSession session = new ClientRaceSession(() -> 0L);
        session.onControl(new ControlMessage.TrackVoteOffer(List.of("s3k:0:1", "s3k:1:0"), 5_000L));
        assertEquals(ClientRaceSession.Phase.VOTE, session.phase());
        // Zero-vote close: the hub announces the RETAINED current track — not an offered key.
        session.onControl(new ControlMessage.TrackVoteResult("s3k:0:0"));
        assertEquals(ClientRaceSession.Phase.LOBBY, session.phase());
        assertEquals("s3k:0:0", session.lastVoteResultTrackKey());
        assertTrue(session.voteOptions().isEmpty());
    }
}
```

(If `JoinAccepted`'s actual component list differs, match the phase-2 record — the intent is only to set `localSlot`.)

- [ ] **Step 2: Run to verify it fails** — compile error on `Phase.VOTE`.

- [ ] **Step 3: Implement**

New fields: `List<String> voteOptions = List.of(); List<ControlMessage.VoteCount> voteCounts = List.of(); long voteEndsAtHubMillis = -1; String lastVoteResult;`. In `onControl`: `TrackVoteOffer` → store options, `voteEndsAtHubMillis`, set base phase `VOTE`, clear counts; `TrackVoteTally` → store counts; `TrackVoteResult` → store key, clear options/counts/deadline, base phase `LOBBY`; `RoundStart` additionally clears `lastVoteResult`. `voteRemainingMillis()` = `voteEndsAtHubMillis < 0 ? -1 : Math.max(0, voteEndsAtHubMillis - hubNowEstimateMillis())`. `podiumTop(n)` = first `min(n, size)` of the stored final standings (store the `RoundEnd` list separately from the live `standings()` if phase 2 overwrites it — keep both consistent with the existing field usage). `localStandingsRow()` scans for `localSlot()`.

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.net.client.TestClientRaceSessionVote,com.openggf.net.client.TestClientRaceSession*" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/client/ClientRaceSession.java src/test/java/com/openggf/net/client/TestClientRaceSessionVote.java
git commit -m "feat(net): client vote mirror + podium accessors"
```

---

### Task 5: Coordinator — vote input, HUD state expansion, vote pool at host time

**Files:**
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java`
- Create: `src/main/java/com/openggf/game/timeattack/mp/VoteTrackPools.java` (shared catalog→pool helper)
- Modify: the engine host-room bootstrap (where `Engine.hostTimeAttackRoom` builds `RoomHostConfig`) — fill `voteTrackKeys` from the helper
- Modify: `src/main/java/com/openggf/net/client/MasterClient.java` (`createRoom` gains the pool parameter — Task 1)
- Modify: `src/main/java/com/openggf/game/timeattack/mp/ServerBrowserScreen.java` (CREATE passes the pool)
- Modify: `src/main/java/com/openggf/GameLoop.java` (one added call)
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestCoordinatorVoteInput.java`

**Interfaces:**
- Consumes: Tasks 3–4; `TimeAttackTrackCatalog.tracksFor(gameId)`; `InputHandler.isKeyPressed(int)`.
- Produces:
  - `public void pollLocalInput(InputHandler input)` — called by `GameLoop.updateLevelMode` right after `coordinator.pump()`; during `Phase.VOTE`, keys 1/2/3 (`GLFW.GLFW_KEY_1..3`) cast/replace the local vote; delegates spectate to Task 8's controller
  - package-private `void castVote(int optionIndex)` — the testable core: bounds-checked, sends `ControlMessage.TrackVote(voteOptions.get(i))`
  - `MultiplayerHudState` replaced by (single authoritative shape from here on):

```java
public record MultiplayerHudState(boolean active, String phase, long remainingWindowMillis,
        long remainingCountdownMillis, List<ControlMessage.StandingsRow> standings,
        List<String> chatLines, boolean connectionLost, String kickReason,
        int totalPlayers, List<RemoteGhostRegistry.FarPlayer> farPlayers,
        String characterPolicy, List<String> voteOptions,
        List<ControlMessage.VoteCount> voteCounts, long voteRemainingMillis,
        String voteResultTrackKey, List<ControlMessage.StandingsRow> podiumRows,
        int localRank) {}
```

  (`characterPolicy` from `session.roundConfig()` — `"OPEN"`/`"LOCKED"`/null pre-round; `podiumRows` = `session.podiumTop(3)`; `localRank` = `localStandingsRow() == null ? -1 : rank`.)
  - Vote pool at creation time — EVERY production room-creation path passes the same catalog pool (a missing pool silently disables voting for that room):

```java
public final class VoteTrackPools {
    private VoteTrackPools() { }

    /** Catalog tracks for the game as vote keys, e.g. "s3k:0:0". */
    public static List<String> forGame(String gameId) {
        return TimeAttackTrackCatalog.tracksFor(gameId).stream()
                .map(track -> track.gameId() + ":" + track.zone() + ":" + track.act())
                .toList();
    }
}
```

    Consumers: `Engine.hostTimeAttackRoom(...)` → `RoomHostConfig.voteTrackKeys` (LAN rooms); `ServerBrowserScreen` CREATE → `masterClient.createRoom(descriptor, routing, directPort, fingerprint, VoteTrackPools.forGame(gameId))` for BOTH master-brokered DIRECT and RELAY rooms (the broker threads it via `SessionRegistry.RoomEntry` into the relay `RoomHostConfig` — phase-4 Task 3 wiring).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack.mp;

import static org.junit.jupiter.api.Assertions.*;

import com.openggf.net.client.ClientRaceSession;
import com.openggf.net.protocol.ControlMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestCoordinatorVoteInput {

    @Test
    void castVoteSendsOfferedKeyAndIgnoresOutOfRange() {
        List<ControlMessage> sent = new ArrayList<>();
        ClientRaceSession session = new ClientRaceSession(() -> 0L);
        MultiplayerRaceCoordinator coordinator =
                new MultiplayerRaceCoordinator(fakeTransport(sent), session);
        session.onControl(new ControlMessage.TrackVoteOffer(List.of("s3k:0:1", "s3k:1:0"), 10_000L));

        coordinator.castVote(1);
        assertEquals(new ControlMessage.TrackVote("s3k:1:0"), sent.get(sent.size() - 1));

        int before = sent.size();
        coordinator.castVote(2);          // only 2 options
        coordinator.castVote(-1);
        session.onControl(new ControlMessage.TrackVoteResult("s3k:1:0"));
        coordinator.castVote(0);          // vote over
        assertEquals(before, sent.size());
    }

    @Test
    void hudStateCarriesVoteAndPodiumFields() {
        List<ControlMessage> sent = new ArrayList<>();
        ClientRaceSession session = new ClientRaceSession(() -> 0L);
        MultiplayerRaceCoordinator coordinator =
                new MultiplayerRaceCoordinator(fakeTransport(sent), session);
        session.onControl(new ControlMessage.TrackVoteOffer(List.of("s3k:0:1"), 10_000L));
        MultiplayerHudState hud = coordinator.hudState();
        assertEquals("VOTE", hud.phase());
        assertEquals(List.of("s3k:0:1"), hud.voteOptions());
    }

    /** Same FakeTransport the phase-2 coordinator tests use — lift it from there. */
    private static RaceTransport fakeTransport(List<ControlMessage> sent) {
        return new RaceTransport() {
            @Override public List<com.openggf.net.client.RaceClient.InboundEvent> drainInbound() { return List.of(); }
            @Override public void sendControl(ControlMessage m) { sent.add(m); }
            @Override public void sendBinary(byte[] d) { }
            @Override public int playerSlot() { return 0; }
            @Override public boolean isOpen() { return true; }
            @Override public void close() { }
        };
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile error (`castVote` missing, record shape).

- [ ] **Step 3: Implement**

- `castVote(int i)`: `List<String> options = session.voteOptions(); if (i >= 0 && i < options.size() && session.phase() == ClientRaceSession.Phase.VOTE) transport.sendControl(new ControlMessage.TrackVote(options.get(i)));`
- `pollLocalInput(InputHandler input)`: `for (int i = 0; i < 3; i++) if (input.isKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_1 + i)) castVote(i);` then `updateSpectate(input)` (no-op stub until Task 8).
- Rebuild `hudState()` with the expanded record; update `MultiplayerHudRenderer` construction site compile errors by passing the new fields through (rendering itself is Task 6).
- `GameLoop.updateLevelMode`: after the existing `coordinator.pump();` add `coordinator.pollLocalInput(input);` (the method already has the frame's `InputHandler`).
- Vote pool wiring: create `VoteTrackPools`; `Engine.hostTimeAttackRoom(...)` passes `VoteTrackPools.forGame(gameId)` into `RoomHostConfig`; `MasterClient.createRoom` gains the final `List<String> voteTrackKeys` parameter (thread into the `RoomCreate` message; update its phase-3 test call sites with `List.of()`); `ServerBrowserScreen`'s CREATE action passes `VoteTrackPools.forGame(gameId)`.

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.*" test` → PASS (existing coordinator tests updated for the record change compile again).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/mp/ src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/net/client/MasterClient.java src/test/java/com/openggf/game/timeattack/mp/TestCoordinatorVoteInput.java src/test/java/com/openggf/net/client/TestMasterClient.java
git commit -m "feat(timeattack): vote input seam, expanded HUD state, vote pool on every create path"
```

---

### Task 6: MultiplayerHudRenderer — podium overlay, vote panel, character badges

**Files:**
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerHudRenderer.java`
- Create: `src/main/java/com/openggf/game/timeattack/mp/HudTextLayout.java` (pure text-composition helper so layout is unit-testable without GL)
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestHudTextLayout.java`

**Interfaces:**
- Consumes: Task 5's `MultiplayerHudState`; `TimeAttackTimeFormat.frames(int)`; `TimeAttackTrackCatalog` (trackKey → label).
- Produces: `HudTextLayout` static methods (pure, String-returning):

```java
public final class HudTextLayout {
    private HudTextLayout() { }

    /** "[S] " badge prefix when policy is OPEN, "" otherwise. */
    public static String characterBadge(String characterPolicy, String character);

    /** e.g. " 1 ana      [S] 0:31.42" — used by standings panel AND podium rows. */
    public static String standingsLine(ControlMessage.StandingsRow row, String characterPolicy);

    /** Podium block lines: "ROUND OVER", top-3 lines, "", "YOU: #4 0:33.10" (localRank<0 -> "YOU: no time"). */
    public static List<String> podiumLines(List<ControlMessage.StandingsRow> podiumRows,
            int localRank, List<ControlMessage.StandingsRow> standings, int localSlot,
            String characterPolicy);

    /** Vote block: "NEXT TRACK - VOTE 1-3 (12s)", "1 ANGEL ISLAND 2   2 votes", ... */
    public static List<String> voteLines(List<String> voteOptions,
            List<ControlMessage.VoteCount> voteCounts, long voteRemainingMillis,
            java.util.function.Function<String, String> trackLabeler);

    /** "NEXT: <label>" toast, or "" when no result yet. */
    public static String voteResultLine(String voteResultTrackKey,
            java.util.function.Function<String, String> trackLabeler);
}
```

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack.mp;

import static org.junit.jupiter.api.Assertions.*;

import com.openggf.net.protocol.ControlMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestHudTextLayout {

    @Test
    void badgeOnlyWhenOpenPolicy() {
        assertEquals("[S] ", HudTextLayout.characterBadge("OPEN", "sonic"));
        assertEquals("[K] ", HudTextLayout.characterBadge("OPEN", "knuckles"));
        assertEquals("", HudTextLayout.characterBadge("LOCKED", "sonic"));
        assertEquals("", HudTextLayout.characterBadge(null, "sonic"));
        assertEquals("[?] ", HudTextLayout.characterBadge("OPEN", null));
    }

    @Test
    void podiumLinesShapeAndLocalRank() {
        List<ControlMessage.StandingsRow> podium = List.of(
                new ControlMessage.StandingsRow(0, "ana", "sonic", 1885, 1),
                new ControlMessage.StandingsRow(1, "bob", "tails", 1990, 2));
        List<String> lines = HudTextLayout.podiumLines(podium, 4, podium, 9, "OPEN");
        assertEquals("ROUND OVER", lines.get(0));
        assertTrue(lines.get(1).contains("ana"));
        assertTrue(lines.get(1).contains("[S]"));
        assertTrue(lines.get(lines.size() - 1).startsWith("YOU: #4"));
        assertTrue(HudTextLayout.podiumLines(podium, -1, podium, 9, "OPEN")
                .get(lines.size() - 1).equals("YOU: no time"));
    }

    @Test
    void voteLinesNumberOptionsAndShowCountsAndSeconds() {
        List<String> lines = HudTextLayout.voteLines(
                List.of("s3k:0:1", "s3k:1:0"),
                List.of(new ControlMessage.VoteCount("s3k:0:1", 2),
                        new ControlMessage.VoteCount("s3k:1:0", 0)),
                12_400L, key -> "LBL " + key);
        assertTrue(lines.get(0).contains("VOTE 1-3"));
        assertTrue(lines.get(0).contains("(12s)"));
        assertTrue(lines.get(1).startsWith("1 LBL s3k:0:1"));
        assertTrue(lines.get(1).contains("2 votes"));
        assertEquals("NEXT: LBL s3k:1:0",
                HudTextLayout.voteResultLine("s3k:1:0", key -> "LBL " + key));
        assertEquals("", HudTextLayout.voteResultLine(null, k -> k));
    }
}
```

- [ ] **Step 2: Run to verify failure** — class not found.

- [ ] **Step 3: Implement**

`HudTextLayout` per the signatures: badge = `"[" + Character.toUpperCase(character.charAt(0)) + "] "` (null character → `"[?] "`); `standingsLine` = `String.format("%2d %-8s %s%s", row.rank(), row.displayName(), characterBadge(policy, row.character()), TimeAttackTimeFormat.frames(row.bestTimeFrames()))`; `podiumLines` = header + up to 3 standings lines + `""` + local line (rank from `localRank` and the local row's time when present in `standings`); `voteLines` header `"NEXT TRACK - VOTE 1-3 (" + (voteRemainingMillis / 1000) + "s)"` then `"%d %s   %d votes"` per option (count 0 when absent from `voteCounts`).

`MultiplayerHudRenderer.render(state)` additions (PixelFont drawing, same style as the existing panel):
- `state.phase().equals("ROUND_END")` → draw `podiumLines(...)` centered (x ≈ 96, y starting 60, LINE_HEIGHT spacing).
- `state.phase().equals("VOTE")` → draw `voteLines(...)` centered; trackLabeler resolves via `TimeAttackTrackCatalog.tracksFor(gameId)` matching zone/act (fall back to the raw key).
- After a result (`voteResultTrackKey != null` and phase `LOBBY`) → draw the `voteResultLine` toast under the room header.
- Standings panel rows switch to `HudTextLayout.standingsLine(row, state.characterPolicy())` — this is the open-character badge feature.

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.TestHudTextLayout" test` → PASS. `mvn -q compile` → SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/mp/ src/test/java/com/openggf/game/timeattack/mp/TestHudTextLayout.java
git commit -m "feat(timeattack): podium overlay, vote panel, open-character badges"
```

---

### Task 7: Ghost nameplates + finished-ghost opacity

**Files:**
- Modify: `src/main/java/com/openggf/game/ghost/ActiveGhost.java` (or the file that declares the record — locate with `grep -rn "record ActiveGhost" src/main/java`)
- Modify: `src/main/java/com/openggf/game/ghost/GhostRenderer.java`
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java` (`remoteActiveGhosts()`)
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestRemoteGhostPresentation.java`

**Interfaces:**
- Consumes: `RemoteGhostRegistry.RemoteGhost(slot, displayName, character, state)`, `GhostFrame.finished()`.
- Produces:
  - `ActiveGhost` becomes `record ActiveGhost(String slotId, String characterCode, GhostFrame frame, String nameplate, float opacityScale)` with convenience constructor `ActiveGhost(String slotId, String characterCode, GhostFrame frame)` → `(slotId, characterCode, frame, null, 1f)` — all phase-1 solo call sites keep compiling unchanged.
  - `GhostRenderer` multiplies its existing distance-fade opacity by `opacityScale`, and draws `nameplate` (PixelFont, centered above the ghost, world→screen via the camera offsets it already uses for the sprite) when non-null.
  - Coordinator: `remoteActiveGhosts()` sets `nameplate = displayName` for the **nearest 4** ghosts by `|ghost.x − localPlayer.centreX|` (main spec §4.6), `opacityScale = frame.finished() ? 0.55f : 1f`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack.mp;

import static org.junit.jupiter.api.Assertions.*;

import com.openggf.game.ghost.ActiveGhost;
import com.openggf.game.ghost.GhostFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestRemoteGhostPresentation {

    private static GhostFrame frameAt(int x, boolean finished) {
        return new GhostFrame(x, 100, 0, false, false, finished, 2, false);
    }

    @Test
    void nearestFourGetNameplatesFinishedGhostsDim() {
        // 6 remote ghosts at increasing distance from local player at x=1000
        List<ActiveGhost> ghosts = MultiplayerRaceCoordinator.presentRemoteGhosts(List.of(
                remote(1, "a", frameAt(1010, false)),
                remote(2, "b", frameAt(1050, true)),
                remote(3, "c", frameAt(1100, false)),
                remote(4, "d", frameAt(1200, false)),
                remote(5, "e", frameAt(1400, false)),
                remote(6, "f", frameAt(1900, false))), 1000);
        long named = ghosts.stream().filter(g -> g.nameplate() != null).count();
        assertEquals(4, named);
        assertNull(ghosts.stream().filter(g -> g.slotId().equals("net:6")).findFirst().orElseThrow().nameplate());
        assertEquals(0.55f, ghosts.stream().filter(g -> g.slotId().equals("net:2"))
                .findFirst().orElseThrow().opacityScale());
        assertEquals(1f, ghosts.stream().filter(g -> g.slotId().equals("net:1"))
                .findFirst().orElseThrow().opacityScale());
    }

    private static com.openggf.net.client.RemoteGhostRegistry.RemoteGhost remote(
            int slot, String name, GhostFrame frame) {
        // build with a RenderState wrapping the frame — reuse the phase-2 test helper for RenderState
        return new com.openggf.net.client.RemoteGhostRegistry.RemoteGhost(
                slot, name, "sonic", renderState(frame));
    }
}
```

(`renderState(frame)`: construct `RemoteGhostPlayback.RenderState` the way the phase-2 `TestRemoteGhostRegistry` does — copy that helper. Extract the mapping logic into a static `presentRemoteGhosts(List<RemoteGhost>, int localCentreX)` on the coordinator so it's testable without a transport.)

- [ ] **Step 2: Run to verify failure** — compile error (record components, `presentRemoteGhosts` missing).

- [ ] **Step 3: Implement**

- `ActiveGhost` record extension + convenience constructor as specified; sweep call sites (`grep -rn "new ActiveGhost(" src/`) — phase-1 sites keep the 3-arg form.
- `GhostRenderer`: multiply final draw alpha by `ghost.opacityScale()`; nameplate drawing: `font.drawText(ghost.nameplate(), screenX - ghost.nameplate().length() * 2, screenY - 24, 1, 1f, 1f, 1f, 0.8f)` where `screenX/screenY` are the sprite draw coordinates already computed (guard `nameplate != null` and the ghost actually on-screen). If `GhostRenderer` has no `PixelFont`, add it as an optional constructor/setter dependency (null → skip nameplates; solo path unchanged).
- Coordinator:

```java
static List<ActiveGhost> presentRemoteGhosts(
        List<RemoteGhostRegistry.RemoteGhost> remotes, int localCentreX) {
    Set<Integer> named = remotes.stream()
            .sorted(Comparator.comparingInt(g -> Math.abs(g.state().frame().x() - localCentreX)))
            .limit(4)
            .map(RemoteGhostRegistry.RemoteGhost::slot)
            .collect(Collectors.toSet());
    return remotes.stream().map(g -> new ActiveGhost(
            "net:" + g.slot(), g.character(), g.state().frame(),
            named.contains(g.slot()) ? g.displayName() : null,
            g.state().frame().finished() ? 0.55f : 1f)).toList();
}
```

`remoteActiveGhosts()` delegates to it with the local player's `getCentreX()` (via the runtime/`GameServices.spritesOrNull()` — whatever the coordinator already uses for relevance; if nothing, pass the last published `GhostFrame.x`).

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.TestRemoteGhostPresentation,com.openggf.game.ghost.*" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/ src/test/java/com/openggf/game/
git commit -m "feat(timeattack): ghost nameplates (nearest 4) + finished-ghost dimming"
```

---

### Task 8: Spectate pan after finishing

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/mp/SpectatePanController.java`
- Modify: `src/main/java/com/openggf/game/timeattack/TimeAttackRuntime.java` (one accessor)
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java` (wire into `pollLocalInput`)
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestSpectatePanController.java`

**Interfaces:**
- Consumes: `Camera` (`setFrozen`, `setX/setY`, `getMinX/getMaxX/getMinY/getMaxY`), `LogicalInputSnapshot` directions, `TimeAttackAttempt.Phase`.
- Produces:
  - `TimeAttackRuntime.isAttemptFinished()` — `attempt != null && attempt.phase() == TimeAttackAttempt.Phase.FINISHED`
  - `SpectatePanController`:

```java
public final class SpectatePanController {
    public static final int PAN_SPEED_PX = 8;
    private boolean active;

    /** dx/dy in {-1,0,1}; activates/deactivates camera freeze on transitions. */
    public void update(com.openggf.camera.Camera camera, boolean shouldBeActive, int dx, int dy);
    public boolean isActive();
}
```

  - Coordinator activation rule: `shouldBeActive = isRuntimeAttached() && runtime.isAttemptFinished() && session.phase() == ClientRaceSession.Phase.RUNNING` (window still open, player already finished). dx/dy from `input.logical()` (`menuLeft/menuRight/menuUp/menuDown`). Camera obtained via `GameServices.cameraOrNull()` (skip when null).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack.mp;

import static org.junit.jupiter.api.Assertions.*;

import com.openggf.camera.Camera;
import org.junit.jupiter.api.Test;

class TestSpectatePanController {

    private static Camera camera() {
        Camera camera = new Camera();
        camera.setMinX((short) 0);
        camera.setMinY((short) 0);
        // bounds setters for max: use the ones Camera exposes (setMaxX/setMaxY or targets)
        camera.setMaxX((short) 1000);
        camera.setMaxY((short) 500);
        camera.setX((short) 300);
        camera.setY((short) 200);
        return camera;
    }

    @Test
    void activatesFreezesPansClampsAndReleases() {
        Camera camera = camera();
        SpectatePanController controller = new SpectatePanController();

        controller.update(camera, true, 1, 0);
        assertTrue(controller.isActive());
        assertTrue(camera.getFrozen());
        assertEquals(300 + SpectatePanController.PAN_SPEED_PX, camera.getX());

        for (int i = 0; i < 200; i++) controller.update(camera, true, 1, 1);
        assertEquals(1000, camera.getX());       // clamped to maxX
        assertEquals(500, camera.getY());        // clamped to maxY

        controller.update(camera, false, 0, 0);
        assertFalse(controller.isActive());
        assertFalse(camera.getFrozen());
    }

    @Test
    void inactiveDoesNotTouchCamera() {
        Camera camera = camera();
        new SpectatePanController().update(camera, false, 1, 1);
        assertEquals(300, camera.getX());
        assertFalse(camera.getFrozen());
    }
}
```

(If `Camera` lacks a plain `setMaxX`, use the actual max-bound setter — check `Camera.java` around `getMaxX()`; the test intent is fixed bounds.)

- [ ] **Step 2: Run to verify failure** — class not found.

- [ ] **Step 3: Implement**

```java
public void update(Camera camera, boolean shouldBeActive, int dx, int dy) {
    if (camera == null) {
        return;
    }
    if (shouldBeActive && !active) {
        active = true;
        camera.setFrozen(true);
    } else if (!shouldBeActive && active) {
        active = false;
        camera.setFrozen(false);
        return;
    }
    if (!active) {
        return;
    }
    int x = clamp(camera.getX() + dx * PAN_SPEED_PX, camera.getMinX(), camera.getMaxX());
    int y = clamp(camera.getY() + dy * PAN_SPEED_PX, camera.getMinY(), camera.getMaxY());
    camera.setX((short) x);
    camera.setY((short) y);
}

private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
}
```

Runtime accessor + coordinator wiring per Interfaces (`pollLocalInput` computes `shouldBeActive`, dx = `(right?1:0) - (left?1:0)`, dy = `(down?1:0) - (up?1:0)` from the logical snapshot). Deactivation happens automatically when the deadline voids the attempt (`isAttemptFinished()` false) or the round ends (`phase != RUNNING`); `detachRuntime()` must also force `update(camera, false, 0, 0)` so leaving a room never strands a frozen camera.

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.TestSpectatePanController" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/ src/test/java/com/openggf/game/timeattack/mp/TestSpectatePanController.java
git commit -m "feat(timeattack): spectate camera pan after finishing"
```

---

### Task 9: Minimap strip over the roster channel

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/mp/MinimapLayout.java`
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerHudRenderer.java`
- Modify: `src/main/java/com/openggf/config/SonicConfiguration.java` + `ConfigCatalog` + `CONFIGURATION.md`
- Test: `src/test/java/com/openggf/game/timeattack/mp/TestMinimapLayout.java`

**Interfaces:**
- Consumes: `MultiplayerHudState.farPlayers` (`FarPlayer.cellX` in 64-px cells, `status` ∈ {0 idle, 1 running, 2 finished}), coordinator's cached `RemoteGhost` list (world x), local player x, level width (`GameServices.level()` geometry — the same width source `LiveLevelProfileFactory` used in phase 2).
- Produces:

```java
public final class MinimapLayout {
    public static final int COLUMNS = 40;
    /** glyph precedence when dots collide: '*' local > 'o' near ghost > '+' finished far > '.' running far */
    public record Dot(int xPx, char glyph) {}
    public static String compose(int levelWidthPx, List<Dot> dots);   // exactly COLUMNS chars
    public static char glyphForFarStatus(int status);                  // 2->'+', 1->'.', 0->' ' (idle hidden)
}
```

- Config key: enum `TIME_ATTACK_HUD_MINIMAP`, path `timeAttack.hud.minimap`, BOOL, default `true`, catalog description "Show the multiplayer minimap progress strip".

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack.mp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestMinimapLayout {

    @Test
    void composePlacesDotsProportionallyWithPrecedence() {
        String strip = MinimapLayout.compose(4000, List.of(
                new MinimapLayout.Dot(0, '.'),
                new MinimapLayout.Dot(2000, 'o'),
                new MinimapLayout.Dot(2000, '*'),      // same column: local wins
                new MinimapLayout.Dot(3999, '+')));
        assertEquals(MinimapLayout.COLUMNS, strip.length());
        assertEquals('.', strip.charAt(0));
        assertEquals('*', strip.charAt(MinimapLayout.COLUMNS / 2));
        assertEquals('+', strip.charAt(MinimapLayout.COLUMNS - 1));
    }

    @Test
    void outOfRangeClampsAndZeroWidthSafe() {
        assertEquals(MinimapLayout.COLUMNS, MinimapLayout.compose(0, List.of(
                new MinimapLayout.Dot(500, '*'))).length());
        String strip = MinimapLayout.compose(1000, List.of(new MinimapLayout.Dot(-50, 'o'),
                new MinimapLayout.Dot(99_999, 'o')));
        assertEquals('o', strip.charAt(0));
        assertEquals('o', strip.charAt(MinimapLayout.COLUMNS - 1));
    }

    @Test
    void farStatusGlyphs() {
        assertEquals('+', MinimapLayout.glyphForFarStatus(2));
        assertEquals('.', MinimapLayout.glyphForFarStatus(1));
        assertEquals(' ', MinimapLayout.glyphForFarStatus(0));
    }
}
```

- [ ] **Step 2: Run to verify failure** — class not found.

- [ ] **Step 3: Implement**

`compose`: char array of `COLUMNS` spaces; per dot `col = clamp((int) ((long) xPx * COLUMNS / Math.max(1, levelWidthPx)), 0, COLUMNS - 1)`; write only if precedence of new glyph ≥ existing (`precedence: '*'=3, 'o'=2, '+'=1, '.'=0, ' '=-1`). Renderer: when config `TIME_ATTACK_HUD_MINIMAP` is true, coordinator attached, and phase RUNNING — build dots (far players: `cellX * 64 + 32`, glyph by status, skipping idle; near ghosts: frame x, `'o'`; local: `'*'`), draw `"[" + strip + "]"` bottom-left with PixelFont. Level width: reuse the exact width source `LiveLevelProfileFactory.fromLoadedLevelOrNull()` uses (fall back to hiding the minimap when unavailable). Config key registration + `CONFIGURATION.md` row + `ConfigCatalog` meta (run `mvn "-Dtest=com.openggf.config.TestConfigCatalog" test` to confirm the meta gate).

- [ ] **Step 4: Run tests**

Run: `mvn "-Dtest=com.openggf.game.timeattack.mp.TestMinimapLayout,com.openggf.config.TestConfigCatalog" test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/ src/test/java/com/openggf/ CONFIGURATION.md
git commit -m "feat(timeattack): roster-fed minimap strip with config toggle"
```
Trailer note: this commit stages CONFIGURATION.md → `Configuration-Docs: updated`.

---

### Task 10: End-to-end round → podium → vote → next round + docs

**Files:**
- Create: `src/test/java/com/openggf/net/TestVoteRoundTrip.java` (in-JVM, no sockets: HostRoundEngine + 2 ClientRaceSessions over a broadcaster lambda)
- Modify: the phase-2 Task 17 loopback test class (add vote assertions to the real-socket path)
- Modify: `CHANGELOG.md`, `README.md` (only at merge time per repo policy), `docs/status/trace-frontier-log.md` n/a

**Interfaces:** consumes everything above; produces no new API.

- [ ] **Step 1: Write the in-JVM end-to-end test**

```java
package com.openggf.net;

import static org.junit.jupiter.api.Assertions.*;

import com.openggf.net.client.ClientRaceSession;
import com.openggf.net.hub.HostRoundEngine;
import com.openggf.net.protocol.ControlMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestVoteRoundTrip {

    @Test
    void fullCycleRoundPodiumVoteNextRound() {
        long[] now = {0};
        ClientRaceSession clientA = new ClientRaceSession(() -> now[0]);
        ClientRaceSession clientB = new ClientRaceSession(() -> now[0]);
        HostRoundEngine engine = new HostRoundEngine(() -> now[0], m -> {
            clientA.onControl(m);
            clientB.onControl(m);
        });
        engine.setVoteTrackPool(List.of("s3k:0:0", "s3k:0:1", "s3k:1:0", "s3k:1:1"));

        ControlMessage.RoundConfig config = new ControlMessage.RoundConfig("s3k", 0, 0, 60, "OPEN", null);
        assertTrue(engine.startRound(config));
        now[0] += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();
        engine.onAttemptFinish(1, "ana", "sonic",
                new ControlMessage.AttemptFinish(1, 1885, 10, 1895, "aa", "bb", null), false);
        now[0] += 60_000;
        engine.onTick();                                   // ROUND_END: podium data on clients
        assertEquals("ana", clientA.podiumTop(3).get(0).displayName());

        now[0] += HostRoundEngine.ROUND_END_LINGER_MILLIS;
        engine.onTick();                                   // VOTE
        assertEquals(ClientRaceSession.Phase.VOTE, clientA.phase());
        List<String> options = clientA.voteOptions();
        engine.onTrackVote(1, options.get(0));
        engine.onTrackVote(2, options.get(0));
        now[0] += HostRoundEngine.VOTE_WINDOW_MILLIS;
        engine.onTick();                                   // result -> LOBBY
        assertEquals(options.get(0), clientA.lastVoteResultTrackKey());

        ControlMessage.RoundConfig next = engine.votedNextConfig();
        assertNotNull(next);
        assertTrue(engine.startRound(next));               // next round on the voted track
        assertEquals(HostRoundEngine.Phase.COUNTDOWN, engine.phase());
    }
}
```

- [ ] **Step 2: Run** — `mvn "-Dtest=com.openggf.net.TestVoteRoundTrip" test` → PASS (this is integration of already-green tasks; failures point at wiring gaps).

- [ ] **Step 3: Extend the socket loopback test**

In the phase-2 Task 17 loopback class add one scenario: host + 2 clients, 2-second window, one finish, drive past ROUND_END linger, assert both clients received `TrackVoteOffer`, one client sends `TrackVote` over the real socket, assert the tally reaches the other client, drive past the vote window, assert `TrackVoteResult` arrives and a `RoundConfigure` for the winner is accepted.

- [ ] **Step 4: Full verification**

Run: `mvn -q test` scoped sanity first: `mvn "-Dtest=com.openggf.net.**,com.openggf.game.timeattack.**" test` → all PASS. Then the repo's must-keep-green S3K set: `mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test` (ROM present).

- [ ] **Step 5: Update CHANGELOG + commit**

Add under Unreleased: `- Multiplayer time attack phase 4: round podium, next-track vote, open-character standings badges, ghost nameplates, spectate pan after finishing, roster minimap.`

```bash
git add CHANGELOG.md src/test/java/com/openggf/net/TestVoteRoundTrip.java <loopback test file>
git commit -m "feat(timeattack): phase-4 polish end-to-end coverage + changelog"
```
Trailers: `Changelog: updated`; others per staged files.

---

## Task dependency notes for parallel execution

- Task 1 blocks 2–5, 10. Task 2 blocks 3, 4 (message flow), 10.
- Tasks 6, 7, 8, 9 are mutually independent once Task 5 lands (they consume `MultiplayerHudState`/coordinator seams).
- Task 7 only needs phase-1/2 types — it can run in parallel with Tasks 1–4 if needed.

## Deferred-from-phase-4 checklist (recorded so nothing silently drops)

- Podium holding for pending verdicts, `RecordingRequest`, verified rooms — verifier plan (`2026-07-04-time-attack-phase5-verifier.md`).
- Graphical (textured) minimap; spectate following a chosen ghost (v1 pan is free-camera only).
- Vote UI on gamepad (number-key cast only in v1 — run the `gamepad-input-gap-sweep` skill after this phase lands).

## Self-review notes (spec coverage)

- Main spec §11 phase 4: podium ✔ (Task 6 over `RoundEnd` + linger), track vote ✔ (Tasks 1–5), open-character badges ✔ (Task 6, `StandingsRow.character`), spectate pan ✔ (Task 8), minimap over roster ✔ (Task 9). §4.6 nameplates-nearest-4 ✔ (Task 7).
- No security-spec content lands here by design (§11 row "4+" is the verifier plan).
- Type-consistency check done: `VoteCount`, `voteTrackKeys`, `votedNextConfig`, `MultiplayerHudState` 17-component shape, and `ActiveGhost` 5-component shape are used identically across Tasks 1–10.
