# Multiplayer Time Attack Phase 3 — Master Server, Relay at Scale, Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A deployable, engine-free master server (`MasterServerMain`) providing the server browser, room brokering, and relay routing for rooms up to 256 players — with the full phase-3 security posture (TLS, SQLite `IdentityStore` + trust ladder, sanctions, PoW, fake-room defenses, fuzzing + adversarial harness in CI) and the hub scale mechanisms (relevance filtering, spatial bucketing, roster channel, backpressure ladder) gated by `GhostLoadTestTool`.

**Architecture:** The phase-2 `RoomHost`/`GhostHub` run UNCHANGED in shape on the master — relay rooms are `RoomHost` instances pinned to master event-loop threads (main spec §6.2), which is why phase 2 kept them transport-agnostic. Phase 3 adds: scale features inside `net.hub` (relevance/roster/backpressure, active only for rooms > 8); a new `com.openggf.net.master` package (session registry, broker, relay manager, identity store, trust ladder, PoW, admin); browser/broker control messages + `Roster`/relay-tunnel binary packets in `net.protocol`; `MasterClient` + roster consumption in `net.client`; a direct→relay fallback tunnel over the host's master connection; and the server-browser UI in `game.timeattack.mp`.

**Specs:** `docs/architecture/designs/2026-07-04-multiplayer-time-attack-design.md` (§4, §6.2, §6.3, §9, §10, §11 phase 3) and `docs/architecture/designs/2026-07-04-time-attack-security-design.md` (§3–§5, §7–§9, §10, §11 phase 3). Companion plans: `2026-07-04-solo-ghost-racing-phase1.md`, `2026-07-04-time-attack-phase2-direct-connect.md`.

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), Netty 4.1 (already added in phase 2; now also TLS via `SslContext`), Jackson (JSON control + YAML master config — both already dependencies), **sqlite-jdbc (NEW dependency)** for the `IdentityStore`, JDK `java.net.http.WebSocket` client (wss-capable via custom `SSLContext`).

## Global Constraints

- Branch: `feature/multiplayer-time-attack`, based on **`next`** — NOT `develop`, never master (owner directive; the name also overrides the repo's `feature/ai-*` convention). Phases 1–3 share this one branch; phase-3 tasks require the phase-2 tasks to have landed on it. Execute in an isolated worktree (superpowers:using-git-worktrees).
- JUnit 5 / Jupiter only — no `org.junit.*` (JUnit 4) imports.
- Every commit needs the trailer block (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`), each `updated` or `n/a`. A `feat:` commit touching `src/main/` must have `Changelog: updated` (staged CHANGELOG.md) or `Changelog: n/a: <reason>`. Task 1 adds the CHANGELOG entry; later commits use `Changelog: n/a: phase-3 entry added in c1 of this branch`.
- PowerShell: quote Maven props — `mvn "-Dtest=com.openggf.net.master.TestSessionRegistry" test`. Never `git add -A`; stage exact paths.
- **Engine-free fence (main spec §6.2):** `com.openggf.net.master` joins the fenced set (`net.protocol`, `net.hub`, `net.host`, `net.client`, `net.identity`) — no engine imports; allowed: JDK, Netty, Jackson, sqlite-jdbc, `com.openggf.net..`, and exactly `GhostFrame`/`GhostFrameCodec`. The master must run with no LWJGL and no ROM on the machine. Task 18 extends the ArchUnit rule.
- **Threading:** each relay room's `RoomHost` is touched only on ITS pinned event loop (rooms pinned to event-loop threads, no cross-room state — main spec §6.2). Master-global state (registry, identity store, trust ladder) is confined to a single dedicated master thread ("broker loop") — connections marshal onto it. SQLite is accessed only from the broker loop.
- **TLS (`wss://`) required on the master** (security spec §7.3) — `MasterConfig.tls*` paths are mandatory in production; a `plaintextForTest` flag exists ONLY for loopback tests and logs a loud warning. Player-hosted direct connect stays plaintext `ws://` (unchanged from phase 2).
- **No ROM asset/content bytes on the master, ever.** Track validation data is the numeric `TrackValidationProfile` table exported by an engine-side build tool and checked in as a resource (security spec §7.2) — Task 14.
- Protocol stays `Protocol.VERSION = 1`: phase-3 message types are additive and only ever exchanged with the master or inside relay rooms; a phase-2 peer never receives them from a player host. Reserved ids from phase 2 now go live: binary `TYPE_ROSTER = 0x03`.
- Privacy (security spec §5): IP addresses live only in short-TTL in-memory rate/cap caches, never in SQLite. Persisted data: fingerprints, display names, timestamps, tiers, sanctions.
- Trust-tier thresholds and PoW difficulties are `MasterConfig` values, not protocol constants (security spec §4) — tunable without client changes.
- Scale numbers are fixed by the specs: 20 Hz hub tick; near = full 60 Hz for the nearest ≤ 8 within ~1.5 screens (enter) / ~2.5 screens (exit hysteresis); 512-px spatial buckets; roster at 1 Hz, 64-px cells; backpressure ladder 64 KB → 256 KB → 1 MB/30 s (main spec §4).

## Contracts consumed from phases 1–2 (all on the shared branch)

- `net.protocol`: `Protocol`, `ControlMessage` (sealed + Jackson `@JsonSubTypes`), `ControlCodec.encode/decode`, `GhostPackets` (`TYPE_GHOST_FRAMES/AGGREGATE`, `FramesBatch`, `AggregateEntry`, `Aggregate`, `MAX_AGGREGATE_FRAMES_PER_ENTRY`), `ProtocolViolationException`.
- `net.hub`: `HubConnection` (sendText/sendBinary/close/remoteHost), `GhostHub` (setTrack/applyProfile/addPlayer/removePlayer/onBinary/tick/isAttemptFlagged/HubViolationRecorder), `GhostStreamValidator` (+`updateProfile`), `TrackValidationProfile`/`TrackValidationProfileSource`, `HostHandshake` (Steps: SendWelcome/Reject/Admit; `signedBytes`), `SessionTokenIssuer`, `HostRoundEngine` (Phase enum, startRound/onTick/onAttemptFinish/standings/snapshot), `RoomHost` (onConnected/onText/onBinary/onDisconnected/tick/requestStartRound/applyTrackValidationProfile/players/descriptor), `RoomHostConfig`.
- `net.host`: `RaceHostServer` (start/port/execute/room/close), `RaceHostChannelHandler` (+`ConnectionCounter`, `NettyHubConnection`).
- `net.client`: `ClientHandshake`, `RaceClient` (InboundEvent: `Control`/`GhostData`/`Disconnected`; drainInbound/sendControl/sendBinary/playerSlot/sessionToken/joinAccepted/isOpen/close; `JOIN_TIMEOUT_MILLIS`), `FrameAssembler`, `ClientRaceSession`, `RemoteGhostRegistry` (+`reset()`), `RemoteGhostPlayback`, `GhostStreamPublisher`.
- `net.identity`: `PlayerIdentity` (loadOrCreate/fingerprint/sign/verify/publicKeyEncoded).
- Engine glue (`game.timeattack.mp`): `RaceTransport`, `MultiplayerRaceCoordinator` (pump/attachRuntime/detachRuntime/holdGameplay/afterLevelFrame/hudState/shutdown), `LiveLevelProfileFactory`, `MenuTextField`, `RaceLobbyScreen`, `MultiplayerHudRenderer`; `TimeAttackMenu`/`TimeAttackTrackCatalog` (phase 1).
- Tests reuse: `FakeHubConnection` (net.hub test fixture), `LatencyProxy` (`src/test/java/com/openggf/net/`).

Phase-2 code changes in this plan are LISTED and additive: `RoomHostConfig` capacity clamp (Task 8), `RoomHost` chat-gate/round-outcome/relevance hooks (Task 8), `HubConnection.queuedBytes()` default method (Task 4), `GhostHub` scale mode (Tasks 3–4), `PlayerIdentity` creation-PoW stamp (Task 5), `RaceClient` small extraction of a `RaceConnection` interface (Task 12). Reconcile against as-built phase-2 code before editing — semantics here bind, names may have drifted.

## What phase 3 explicitly does NOT build (deferred to phase 4 / post-v1)

- `openggf-verifier` workers, verified-room enforcement (`verified` stays `false` everywhere), `RecordingRequest`/upload flow, spot-checking (security spec §6, §11 "4+").
- Podium presentation, track vote, spectate pan, minimap rendering (main spec §11 phase 4 — the roster CHANNEL ships now; the minimap UI that would consume it does not).
- Community moderation workflows (reports, reviewer queues) — sanctions are operator-issued via the admin endpoint only (security spec §9).
- Ranked/persistent leaderboards; OAuth/account binding.
- Postgres migration (the `IdentityStore` interface is the seam; SQLite only in v1 — security spec §5).

---

### Task 0: Branch check

**Files:** none (git only)

- [ ] **Step 1: Confirm the shared branch and phase-2 presence**

```bash
git fetch origin
git checkout feature/multiplayer-time-attack && git pull --ff-only
git config core.hooksPath .githooks
ls src/main/java/com/openggf/net/hub/RoomHost.java src/main/java/com/openggf/net/protocol/GhostPackets.java
```

Expected: on `feature/multiplayer-time-attack` (based on `next` — owner directive), clean tree, both phase-2 files present. If they are absent, STOP: phase 2 has not landed on the branch yet and Tasks 2+ cannot proceed.

---

### Task 1: Protocol additions — broker/browser messages, PoW, relay tunnel, Roster packet

**Files:**
- Modify: `src/main/java/com/openggf/net/protocol/ControlMessage.java` (new nested records + `@JsonSubTypes` entries)
- Modify: `src/main/java/com/openggf/net/protocol/GhostPackets.java` (Roster codec for the reserved `TYPE_ROSTER = 0x03`; new `TYPE_RELAY_GUEST_BINARY = 0x04`)
- Modify: `src/main/java/com/openggf/net/protocol/Protocol.java` (add `MAX_PLAYERS_RELAY = 256` — consumed by the roster codec cap here and the `RoomHostConfig` clamp in Task 8 — and `MAX_MASTER_FRAME_BYTES = 2 * MAX_CONTROL_BYTES + 1024` — tunnel framing headroom, see Interfaces)
- Modify: `src/main/java/com/openggf/net/protocol/ControlCodec.java` (extract the size check into a `decode(String text, int maxBytes)` overload — see Interfaces)
- Test: `src/test/java/com/openggf/net/protocol/TestPhase3Protocol.java`
- Modify: `CHANGELOG.md` (Unreleased entry: "Multiplayer time attack (phase 3): master server — server browser, room brokering, relay rooms to 256 players with relevance filtering/roster/backpressure, TLS, identity store + trust ladder, sanctions, proof-of-work admission, load-test and fuzz harnesses.")

**Interfaces:**
- Produces — new `ControlMessage` records (all added to the `@JsonSubTypes` list with their simple names; nested data records do NOT implement the interface):
  - Data: `RoomSummary(String roomId, String name, String gameId, int zone, int act, String characterPolicy, int playerCount, int maxPlayers, String routing, boolean verified)` (`routing`: `"DIRECT"` or `"RELAY"`).
  - Browser/broker (client ↔ master): `RoomCreate(RoomDescriptor room, String routing, int directPort, String determinismFingerprint)` (the fingerprint is advertised so joiners are gated BEFORE connecting — rooms advertise the required game/ROM, main spec §7); `RoomCreated(String roomId)`; `RoomCreateRejected(String reason)`; `RoomListRequest(String gameFilter, int page)` (null filter = all); `RoomListResult(List<RoomSummary> rooms, int page, int totalPages)`; `RoomJoinRequest(String roomId)`; `RoomJoinResult(String roomId, String routing, String directHost, int directPort, String hostServerId, String determinismFingerprint)` (direct: address + host identity fingerprint so the client can verify the host's `Welcome.serverId`; relay: address fields null/0); `RoomJoinRejected(String reason)`; `RoomLeave(String roomId)`; `Heartbeat(String roomId, int playerCount)` (host → master, direct rooms).
  - PoW (security spec §3/§8): `PowChallenge(String kind, String prefixBase64, int difficultyBits)` with kinds `"IDENTITY"` (creation stamp demanded for unknown identities) and `"JOIN"` (attack mode); `PowSolution(String kind, long nonce)`.
  - Relay attach + tunnel: `RelayAttach(String roomId)` (guest → master: switch this connection into room mode); `RelayGuestOpen(int guestId)`, `RelayGuestClose(int guestId, String reason)`, `RelayGuestText(int guestId, String text)` (master ↔ player-host tunnel for the direct→relay fallback, main spec §9).
  - Standings at scale (main spec §6.3 — never push the full list to hundreds of clients): `StandingsPageRequest(int page)`; `StandingsPage(List<StandingsRow> rows, int page, int totalPages)`; `RankUpdate(int rank, int bestTimeFrames)` (unicast to a finisher whose row may be outside the broadcast cap).
- Produces — `GhostPackets` additions:
  - `record RosterEntry(int playerSlot, int cellX, int cellY, int status)`; status constants `ROSTER_STATUS_IDLE = 0`, `ROSTER_STATUS_RUNNING = 1`, `ROSTER_STATUS_FINISHED = 2`.
  - `byte[] encodeRoster(List<RosterEntry> entries)` / `List<RosterEntry> decodeRoster(byte[] packet)` — layout: `u8 type=0x03, u16 entryCount BE, then per entry u8 slot, u16 cellX BE, u8 cellY, u8 status` (position quantized to 64-px cells; 5 bytes/player, 256 players ≈ 1.3 KB at 1 Hz — within main spec §4.3's budget). Count is `u16` because a full relay room has 256 entries — a `u8` count caps at 255, one short of `MAX_PLAYERS_RELAY`. cellX is `u16` because acts wider than 16 320 px exist and the phase-4 minimap consumes this field; cellY stays `u8` (no act approaches 16 320 px tall). Range discipline is split: the HUB's quantizer clamps into wire range (`x >>> 6` capped — Task 3); the CODEC **rejects** out-of-range fields with `ProtocolViolationException` on BOTH sides — encode refuses `slot`/`cellY` outside 0..255, `cellX` outside 0..65535, and `status` above `ROSTER_STATUS_FINISHED` before any narrowing cast (a bare cast would silently wrap on the wire), and decode refuses status bytes above `ROSTER_STATUS_FINISHED` (the other fields are width-bounded by the wire format; status is the one semantically constrained byte a hostile packet could smuggle through). Encode also rejects more than `Protocol.MAX_PLAYERS_RELAY` entries.
  - `TYPE_RELAY_GUEST_BINARY = 0x04`: `byte[] encodeRelayGuestBinary(int guestId, byte[] payload)` / `record RelayGuestBinary(int guestId, byte[] payload)` + `decodeRelayGuestBinary(byte[])` — layout `u8 type, u16 guestId BE, payload` (payload itself is a normal ghost packet; total still ≤ `Protocol.MAX_BINARY_BYTES`... the tunnel adds 3 bytes, so payload cap = `MAX_BINARY_BYTES - 3`, enforced).
  - **Tunnel framing headroom:** `RelayGuestText` nests a full control envelope (itself up to `Protocol.MAX_CONTROL_BYTES`) inside another control envelope AS A JSON STRING — worst-case string escaping (`"` → `\"`, `\` → `\\`) can nearly DOUBLE the inner bytes. Add `Protocol.MAX_MASTER_FRAME_BYTES = 2 * MAX_CONTROL_BYTES + 1024` (17 408) — sized for a fully-escaped near-cap inner envelope plus wrapper overhead. The raised cap applies at BOTH layers of the master connection, and only there:
    - **Transport:** the master pipeline's WS frame/aggregation caps (Task 12) and the master-client/host-link assembler (Task 13).
    - **Decode:** raising the frame cap alone is not enough — phase-2 `ControlCodec.decode(String)` rejects text over `MAX_CONTROL_BYTES`, so an accepted large frame would still die in the decoder. Extract the size check into `ControlCodec.decode(String text, int maxBytes)`; the existing `decode(String)` delegates with `MAX_CONTROL_BYTES` (every phase-2 call site keeps its tight cap), and the master-connection decode paths — `RoomBroker.onText` (Task 9) and `MasterClient`'s inbound routing (Task 13) — call the overload with `MAX_MASTER_FRAME_BYTES`.
    - Per-type validation gives `RelayGuestText.text` its own cap of `MAX_CONTROL_BYTES` (it carries exactly one inner envelope, which is itself capped). Room and direct-connect pipelines keep the tight phase-2 caps everywhere; the inner envelope is re-decoded at its destination under the NORMAL cap.
  - Same hardening discipline as phase 2: exact-length checks, count/range checks, `ProtocolViolationException`, never allocate from unvalidated counts.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestPhase3Protocol {
    @Test
    void roundTripsEveryNewControlMessage() {
        ControlMessage.RoomDescriptor desc =
                new ControlMessage.RoomDescriptor("Big", "s3k", 0, 0, "OPEN", null, 256, false);
        List<ControlMessage> all = List.of(
                new ControlMessage.RoomCreate(desc, "RELAY", 0, "0.6:cafe1234"),
                new ControlMessage.RoomCreated("room-1"),
                new ControlMessage.RoomCreateRejected("new identities cannot create rooms"),
                new ControlMessage.RoomListRequest("s3k", 0),
                new ControlMessage.RoomListResult(List.of(new ControlMessage.RoomSummary(
                        "room-1", "Big", "s3k", 0, 0, "OPEN", 12, 256, "RELAY", false)), 0, 1),
                new ControlMessage.RoomJoinRequest("room-1"),
                new ControlMessage.RoomJoinResult("room-1", "DIRECT", "192.168.1.5", 27888,
                        "hostfingerprint", "0.6:cafe1234"),
                new ControlMessage.RoomJoinRejected("determinism fingerprint mismatch"),
                new ControlMessage.RoomLeave("room-1"),
                new ControlMessage.Heartbeat("room-1", 5),
                new ControlMessage.PowChallenge("JOIN", "cHJlZml4", 22),
                new ControlMessage.PowSolution("JOIN", 123456789L),
                new ControlMessage.RelayAttach("room-1"),
                new ControlMessage.RelayGuestOpen(7),
                new ControlMessage.RelayGuestClose(7, "guest disconnected"),
                new ControlMessage.RelayGuestText(7, "{\"v\":1}"),
                new ControlMessage.StandingsPageRequest(2),
                new ControlMessage.StandingsPage(List.of(
                        new ControlMessage.StandingsRow(0, "A", "sonic", 3600, 41)), 2, 16),
                new ControlMessage.RankUpdate(41, 3600));
        for (ControlMessage msg : all) {
            assertEquals(msg, ControlCodec.decode(ControlCodec.encode("t", msg)).message(),
                    "round-trip failed for " + msg.getClass().getSimpleName());
        }
    }

    @Test
    void rosterRoundTripsAndQuantizes() {
        List<GhostPackets.RosterEntry> entries = List.of(
                new GhostPackets.RosterEntry(0, 300, 8, GhostPackets.ROSTER_STATUS_RUNNING),   // cellX > 255: needs u16
                new GhostPackets.RosterEntry(255, 40_000, 255, GhostPackets.ROSTER_STATUS_FINISHED));
        byte[] packet = GhostPackets.encodeRoster(entries);
        assertEquals(GhostPackets.TYPE_ROSTER, packet[0] & 0xFF);
        assertEquals(3 + 2 * 5, packet.length); // u16 count header + 5 bytes/player (u16 cellX)
        assertEquals(entries, GhostPackets.decodeRoster(packet));

        // A FULL relay room (256 entries) must round-trip — a u8 count would cap at 255.
        List<GhostPackets.RosterEntry> full = new java.util.ArrayList<>();
        for (int slot = 0; slot < 256; slot++) {
            full.add(new GhostPackets.RosterEntry(slot, slot * 64, 4, GhostPackets.ROSTER_STATUS_RUNNING));
        }
        assertEquals(256, GhostPackets.decodeRoster(GhostPackets.encodeRoster(full)).size());
    }

    @Test
    void rosterDecodeRejectsHostileInput() {
        byte[] good = GhostPackets.encodeRoster(List.of(
                new GhostPackets.RosterEntry(0, 1, 2, 0)));
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 1);
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeRoster(truncated));
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeRoster(trailing));
        byte[] wrongType = good.clone();
        wrongType[0] = 0x01;
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeRoster(wrongType));
        byte[] badStatus = good.clone();
        badStatus[badStatus.length - 1] = (byte) 255; // status is the last byte of the single entry
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.decodeRoster(badStatus));
    }

    @Test
    void relayGuestBinaryWrapsAndUnwraps() {
        byte[] inner = GhostPackets.encodeFrames(1, 0, new byte[com.openggf.game.ghost.GhostFrameCodec.BYTES]);
        byte[] wrapped = GhostPackets.encodeRelayGuestBinary(300, inner);
        assertEquals(0x04, wrapped[0] & 0xFF);
        GhostPackets.RelayGuestBinary back = GhostPackets.decodeRelayGuestBinary(wrapped);
        assertEquals(300, back.guestId());
        assertArrayEquals(inner, back.payload());
    }

    @Test
    void relayGuestBinaryEnforcesPayloadCap() {
        byte[] oversized = new byte[Protocol.MAX_BINARY_BYTES - 2]; // + 3-byte header > cap
        assertThrows(ProtocolViolationException.class,
                () -> GhostPackets.encodeRelayGuestBinary(1, oversized));
        assertThrows(ProtocolViolationException.class,
                () -> GhostPackets.decodeRelayGuestBinary(new byte[] {0x04, 0, 1}));
    }

    @Test
    void nearCapTunneledTextNeedsTheMasterDecodeCap() throws Exception {
        // Worst-case inner envelope: near-cap and dominated by quotes, so JSON string
        // escaping nearly doubles it inside the RelayGuestText wrapper.
        String inner = "\"".repeat(Protocol.MAX_CONTROL_BYTES - 200);
        ControlMessage wrapped = new ControlMessage.RelayGuestText(7, inner);
        String wire = ControlCodec.encode(null, wrapped);
        int wireBytes = wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(wireBytes > Protocol.MAX_CONTROL_BYTES, "escaped wrapper must exceed the room cap");
        assertTrue(wireBytes <= Protocol.MAX_MASTER_FRAME_BYTES, "must fit the master cap");
        assertEquals(wrapped, ControlCodec.decode(wire, Protocol.MAX_MASTER_FRAME_BYTES).message());
        assertThrows(ProtocolViolationException.class, () -> ControlCodec.decode(wire),
                "the phase-2 single-arg decode keeps its tight cap");
    }

    @Test
    void rosterEncodeRejectsOutOfRangeFieldsInsteadOfWrapping() {
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeRoster(
                List.of(new GhostPackets.RosterEntry(0, -1, 0, 0))));
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeRoster(
                List.of(new GhostPackets.RosterEntry(0, 0x10000, 0, 0))));
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeRoster(
                List.of(new GhostPackets.RosterEntry(0, 0, -1, 0))));
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeRoster(
                List.of(new GhostPackets.RosterEntry(0, 0, 256, 0))));
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeRoster(
                List.of(new GhostPackets.RosterEntry(256, 0, 0, 0))));
        assertThrows(ProtocolViolationException.class, () -> GhostPackets.encodeRoster(
                List.of(new GhostPackets.RosterEntry(0, 0, 0, GhostPackets.ROSTER_STATUS_FINISHED + 1))));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.protocol.TestPhase3Protocol" test`
Expected: COMPILATION ERROR (new records absent).

- [ ] **Step 3: Implement**

`ControlMessage.java` — add the nested records exactly as specified in Interfaces (each a one-to-three-line record implementing `ControlMessage`, except `RoomSummary` which is plain data) and register every new message type in the `@JsonSubTypes` array with its simple name, e.g.:

```java
        @JsonSubTypes.Type(value = ControlMessage.RoomCreate.class, name = "RoomCreate"),
        @JsonSubTypes.Type(value = ControlMessage.RoomCreated.class, name = "RoomCreated"),
        @JsonSubTypes.Type(value = ControlMessage.RoomCreateRejected.class, name = "RoomCreateRejected"),
        @JsonSubTypes.Type(value = ControlMessage.RoomListRequest.class, name = "RoomListRequest"),
        @JsonSubTypes.Type(value = ControlMessage.RoomListResult.class, name = "RoomListResult"),
        @JsonSubTypes.Type(value = ControlMessage.RoomJoinRequest.class, name = "RoomJoinRequest"),
        @JsonSubTypes.Type(value = ControlMessage.RoomJoinResult.class, name = "RoomJoinResult"),
        @JsonSubTypes.Type(value = ControlMessage.RoomJoinRejected.class, name = "RoomJoinRejected"),
        @JsonSubTypes.Type(value = ControlMessage.RoomLeave.class, name = "RoomLeave"),
        @JsonSubTypes.Type(value = ControlMessage.Heartbeat.class, name = "Heartbeat"),
        @JsonSubTypes.Type(value = ControlMessage.PowChallenge.class, name = "PowChallenge"),
        @JsonSubTypes.Type(value = ControlMessage.PowSolution.class, name = "PowSolution"),
        @JsonSubTypes.Type(value = ControlMessage.RelayAttach.class, name = "RelayAttach"),
        @JsonSubTypes.Type(value = ControlMessage.RelayGuestOpen.class, name = "RelayGuestOpen"),
        @JsonSubTypes.Type(value = ControlMessage.RelayGuestClose.class, name = "RelayGuestClose"),
        @JsonSubTypes.Type(value = ControlMessage.RelayGuestText.class, name = "RelayGuestText"),
        @JsonSubTypes.Type(value = ControlMessage.StandingsPageRequest.class, name = "StandingsPageRequest"),
        @JsonSubTypes.Type(value = ControlMessage.StandingsPage.class, name = "StandingsPage"),
        @JsonSubTypes.Type(value = ControlMessage.RankUpdate.class, name = "RankUpdate"),
```

`GhostPackets.java` — replace `TYPE_ROSTER_RESERVED` with the live constant and add the two codecs:

```java
    /** Live from phase 3: 1 Hz coarse state for every player (main spec §4.3). */
    public static final int TYPE_ROSTER = 0x03;
    /** Master ↔ player-host relay tunnel wrapper for guest binary frames (main spec §9). */
    public static final int TYPE_RELAY_GUEST_BINARY = 0x04;

    public static final int ROSTER_STATUS_IDLE = 0;
    public static final int ROSTER_STATUS_RUNNING = 1;
    public static final int ROSTER_STATUS_FINISHED = 2;

    public record RosterEntry(int playerSlot, int cellX, int cellY, int status) {
    }

    public record RelayGuestBinary(int guestId, byte[] payload) {
    }

    public static byte[] encodeRoster(List<RosterEntry> entries) {
        if (entries.size() > Protocol.MAX_PLAYERS_RELAY) {
            throw new ProtocolViolationException("roster has " + entries.size() + " entries");
        }
        ByteBuffer out = ByteBuffer.allocate(3 + entries.size() * 5);
        out.put((byte) TYPE_ROSTER).putShort((short) entries.size());
        for (RosterEntry entry : entries) {
            requireRange(entry.playerSlot(), 0xFF, "roster slot");
            requireRange(entry.cellX(), 0xFFFF, "roster cellX");
            requireRange(entry.cellY(), 0xFF, "roster cellY");
            requireRange(entry.status(), ROSTER_STATUS_FINISHED, "roster status");
            out.put((byte) entry.playerSlot()).putShort((short) entry.cellX())
                    .put((byte) entry.cellY()).put((byte) entry.status());
        }
        return out.array();
    }

    /** Reject rather than let a narrowing cast silently wrap on the wire. */
    private static void requireRange(int value, int maxInclusive, String field) {
        if (value < 0 || value > maxInclusive) {
            throw new ProtocolViolationException(field + " out of range: " + value);
        }
    }

    public static List<RosterEntry> decodeRoster(byte[] packet) {
        ByteBuffer in = checked(packet, TYPE_ROSTER, 3);
        int count = in.getShort() & 0xFFFF;
        if (count > Protocol.MAX_PLAYERS_RELAY || in.remaining() != count * 5) {
            throw new ProtocolViolationException("roster length mismatch for " + count + " entries");
        }
        List<RosterEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int slot = in.get() & 0xFF;
            int cellX = in.getShort() & 0xFFFF;
            int cellY = in.get() & 0xFF;
            int status = in.get() & 0xFF;
            if (status > ROSTER_STATUS_FINISHED) {
                // slot/cellX/cellY are width-bounded by the wire format; status is semantic
                throw new ProtocolViolationException("roster status out of range: " + status);
            }
            entries.add(new RosterEntry(slot, cellX, cellY, status));
        }
        return entries;
    }

    public static byte[] encodeRelayGuestBinary(int guestId, byte[] payload) {
        if (payload.length > Protocol.MAX_BINARY_BYTES - 3) {
            throw new ProtocolViolationException("relay payload " + payload.length + " over cap");
        }
        ByteBuffer out = ByteBuffer.allocate(3 + payload.length);
        out.put((byte) TYPE_RELAY_GUEST_BINARY).putShort((short) guestId).put(payload);
        return out.array();
    }

    public static RelayGuestBinary decodeRelayGuestBinary(byte[] packet) {
        ByteBuffer in = checked(packet, TYPE_RELAY_GUEST_BINARY, 3);
        int guestId = in.getShort() & 0xFFFF;
        if (!in.hasRemaining()) {
            throw new ProtocolViolationException("relay wrapper has empty payload");
        }
        byte[] payload = new byte[in.remaining()];
        in.get(payload);
        return new RelayGuestBinary(guestId, payload);
    }
```

(Reuses the existing private `checked(byte[], int, int)` helper. Add the missing `java.util.List` import if absent.)

`ControlCodec.java` — extract the size gate into an overload (phase-2 behavior unchanged at every existing call site):

```java
    public static DecodedControl decode(String text) {
        return decode(text, Protocol.MAX_CONTROL_BYTES);
    }

    /** Master-connection paths pass Protocol.MAX_MASTER_FRAME_BYTES (tunnel headroom, see Interfaces). */
    public static DecodedControl decode(String text, int maxBytes) {
        // ...move the existing byte-length check here, comparing against maxBytes,
        // then the existing Jackson parse + per-type validation unchanged...
    }
```

`Protocol.java`:

```java
    public static final int MAX_PLAYERS_RELAY = 256;
    /** Master-connection frame/decode cap: a RelayGuestText wrapper around a fully-escaped
     *  near-cap inner envelope can approach 2x MAX_CONTROL_BYTES (JSON string escaping). */
    public static final int MAX_MASTER_FRAME_BYTES = 2 * MAX_CONTROL_BYTES + 1024;
```

- [ ] **Step 4: Run tests to verify they pass — including phase-2 protocol tests**

Run: `mvn "-Dtest=com.openggf.net.protocol.*" test`
Expected: PASS (phase-2 `TestControlCodec`/`TestGhostPackets` unaffected + 5 new tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/protocol/ControlMessage.java src/main/java/com/openggf/net/protocol/GhostPackets.java src/main/java/com/openggf/net/protocol/Protocol.java src/main/java/com/openggf/net/protocol/ControlCodec.java src/test/java/com/openggf/net/protocol/TestPhase3Protocol.java CHANGELOG.md
git commit -m "feat(timeattack): phase-3 broker/PoW/relay control messages and roster packet

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: RelevanceClassifier — spatial bucketing, hysteresis, nearest-8

**Files:**
- Create: `src/main/java/com/openggf/net/hub/RelevanceClassifier.java`
- Test: `src/test/java/com/openggf/net/hub/TestRelevanceClassifier.java`

**Interfaces:**
- Produces `RelevanceClassifier` (main spec §4.2 — O(N + near-pairs) per tick, never O(N²)):
  - Constants: `BUCKET_PX = 512`; `NEAR_ENTER_PX = 480` (~1.5 × 320-px screens); `NEAR_EXIT_PX = 800` (~2.5 screens — exit hysteresis prevents flapping); `NEAR_CAP = 8` (nearest 8 get full 60 Hz fidelity — main spec §4.2/§4.6).
  - `void updatePosition(int slot, int x, int y)`; `void remove(int slot)`.
  - `void rebucket()` — hashes players into 512-px buckets along x (y checked within candidate buckets); called once per hub tick BEFORE `nearSetFor` queries.
  - `Set<Integer> nearSetFor(int slot)` — per recipient: candidates from buckets within range; a peer is near if Chebyshev distance (`max(|dx|,|dy|)`) ≤ `NEAR_ENTER_PX`, or ≤ `NEAR_EXIT_PX` if it was near last tick (hysteresis); result capped to the `NEAR_CAP` nearest by distance. The returned set is also stored as "last tick's near set" for the hysteresis.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.hub;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestRelevanceClassifier {
    @Test
    void nearWithinEnterDistanceFarBeyondIt() {
        RelevanceClassifier c = new RelevanceClassifier();
        c.updatePosition(0, 1000, 500);
        c.updatePosition(1, 1000 + RelevanceClassifier.NEAR_ENTER_PX, 500); // exactly at enter
        c.updatePosition(2, 1000 + RelevanceClassifier.NEAR_EXIT_PX + 100, 500); // far
        c.rebucket();
        assertEquals(Set.of(1), c.nearSetFor(0));
        assertEquals(Set.of(0), c.nearSetFor(1));
        assertTrue(c.nearSetFor(2).isEmpty());
    }

    @Test
    void hysteresisKeepsNearUntilExitDistance() {
        RelevanceClassifier c = new RelevanceClassifier();
        c.updatePosition(0, 1000, 500);
        c.updatePosition(1, 1200, 500);
        c.rebucket();
        assertEquals(Set.of(1), c.nearSetFor(0)); // entered

        c.updatePosition(1, 1000 + RelevanceClassifier.NEAR_EXIT_PX - 10, 500); // between enter and exit
        c.rebucket();
        assertEquals(Set.of(1), c.nearSetFor(0)); // stays near (hysteresis)

        c.updatePosition(1, 1000 + RelevanceClassifier.NEAR_EXIT_PX + 10, 500); // past exit
        c.rebucket();
        assertTrue(c.nearSetFor(0).isEmpty());

        c.updatePosition(1, 1000 + RelevanceClassifier.NEAR_ENTER_PX + 50, 500); // between again
        c.rebucket();
        assertTrue(c.nearSetFor(0).isEmpty()); // must RE-ENTER below enter distance
    }

    @Test
    void capsAtNearestEight() {
        RelevanceClassifier c = new RelevanceClassifier();
        c.updatePosition(0, 5000, 500);
        for (int i = 1; i <= 12; i++) {
            c.updatePosition(i, 5000 + i * 10, 500); // 12 peers, all within enter range
        }
        c.rebucket();
        Set<Integer> near = c.nearSetFor(0);
        assertEquals(RelevanceClassifier.NEAR_CAP, near.size());
        for (int i = 1; i <= RelevanceClassifier.NEAR_CAP; i++) {
            assertTrue(near.contains(i), "nearest peer " + i + " must win the cap");
        }
    }

    @Test
    void verticalDistanceCountsWithinCandidateBuckets() {
        RelevanceClassifier c = new RelevanceClassifier();
        c.updatePosition(0, 1000, 0);
        c.updatePosition(1, 1000, RelevanceClassifier.NEAR_EXIT_PX + 200); // same x, far in y
        c.rebucket();
        assertTrue(c.nearSetFor(0).isEmpty());
    }

    @Test
    void removedPlayersDisappearFromAllSets() {
        RelevanceClassifier c = new RelevanceClassifier();
        c.updatePosition(0, 1000, 500);
        c.updatePosition(1, 1100, 500);
        c.rebucket();
        assertEquals(Set.of(1), c.nearSetFor(0));
        c.remove(1);
        c.rebucket();
        assertTrue(c.nearSetFor(0).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestRelevanceClassifier" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.hub;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Near/far classification with spatial bucketing (main spec §4.2): players hashed
 * into 512-px buckets along x, y-checked within candidate buckets — O(N + near-pairs)
 * per tick, thousands of cheap operations at 256 players. Near = full 60 Hz in the
 * aggregate (nearest 8); far = roster only. Enter/exit hysteresis prevents flapping.
 */
public final class RelevanceClassifier {
    public static final int BUCKET_PX = 512;
    public static final int NEAR_ENTER_PX = 480;
    public static final int NEAR_EXIT_PX = 800;
    public static final int NEAR_CAP = 8;

    private record Position(int x, int y) {
    }

    private final Map<Integer, Position> positions = new HashMap<>();
    private final Map<Integer, List<Integer>> buckets = new HashMap<>();
    private final Map<Integer, Set<Integer>> previousNear = new HashMap<>();

    public void updatePosition(int slot, int x, int y) {
        positions.put(slot, new Position(x, y));
    }

    public void remove(int slot) {
        positions.remove(slot);
        previousNear.remove(slot);
    }

    public void rebucket() {
        buckets.clear();
        for (Map.Entry<Integer, Position> entry : positions.entrySet()) {
            buckets.computeIfAbsent(entry.getValue().x() / BUCKET_PX, b -> new ArrayList<>())
                    .add(entry.getKey());
        }
    }

    public Set<Integer> nearSetFor(int slot) {
        Position self = positions.get(slot);
        if (self == null) {
            return Set.of();
        }
        Set<Integer> wasNear = previousNear.getOrDefault(slot, Set.of());
        int bucketRange = NEAR_EXIT_PX / BUCKET_PX + 1;
        int selfBucket = self.x() / BUCKET_PX;
        List<Integer> candidates = new ArrayList<>();
        for (int b = selfBucket - bucketRange; b <= selfBucket + bucketRange; b++) {
            List<Integer> bucket = buckets.get(b);
            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }
        List<Integer> near = new ArrayList<>();
        for (int peer : candidates) {
            if (peer == slot) {
                continue;
            }
            Position other = positions.get(peer);
            int distance = Math.max(Math.abs(other.x() - self.x()), Math.abs(other.y() - self.y()));
            int threshold = wasNear.contains(peer) ? NEAR_EXIT_PX : NEAR_ENTER_PX;
            if (distance <= threshold) {
                near.add(peer);
            }
        }
        near.sort(Comparator.comparingInt(peer -> {
            Position other = positions.get(peer);
            return Math.max(Math.abs(other.x() - self.x()), Math.abs(other.y() - self.y()));
        }));
        Set<Integer> result = new LinkedHashSet<>(near.subList(0, Math.min(near.size(), NEAR_CAP)));
        previousNear.put(slot, new HashSet<>(result));
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.hub.TestRelevanceClassifier" test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/RelevanceClassifier.java src/test/java/com/openggf/net/hub/TestRelevanceClassifier.java
git commit -m "feat(timeattack): relevance classifier with spatial buckets and hysteresis

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: GhostHub scale mode — relevance-filtered aggregates + roster emission

**Files:**
- Modify: `src/main/java/com/openggf/net/hub/GhostHub.java`
- Test: `src/test/java/com/openggf/net/hub/TestGhostHubScaleMode.java`

**Interfaces:**
- Consumes: `RelevanceClassifier` (Task 2), `GhostPackets.encodeRoster` (Task 1).
- Produces (additive; the phase-2 constructor keeps its exact signature and behavior — player-hosted ≤ 8 rooms skip relevance filtering entirely, main spec §4.4):
  - New constructor `GhostHub(LongSupplier wallClockMillis, TrackValidationProfileSource profiles, HubViolationRecorder recorder, boolean relevanceFiltering)`; the existing 3-arg constructor delegates with `false`.
  - Position tracking: on every ACCEPTED batch, the hub decodes the LAST frame and records `(x, y)` into the classifier plus a per-player `lastStatus` (`ROSTER_STATUS_RUNNING`, or `ROSTER_STATUS_FINISHED` once a frame with the finished bit arrives; `IDLE` before any frame of the current attempt).
  - `tick()` with filtering on: `classifier.rebucket()`, then each recipient's aggregate contains only senders in `nearSetFor(recipient)` (still excluding self). With filtering off: phase-2 behavior byte-for-byte.
  - Roster: `ROSTER_INTERVAL_TICKS = 20` (1 Hz at the 20 Hz tick — main spec §4.3): every 20th tick, every player receives one `Roster` packet listing ALL players — including far ones AND players who have not streamed a frame yet (no tracked position → `cellX`/`cellY` 0, status `ROSTER_STATUS_IDLE`; the roster is the standings panel's presence feed, so idle players must appear). Positions quantized `x >>> 6` (clamped to 65535) / `y >>> 6` (clamped to 255). Only when filtering is on (small rooms have no far players).
  - `removePlayer` also removes from the classifier.

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

class TestGhostHubScaleMode {
    private long now = 50_000;
    private GhostHub hub;
    private final FakeHubConnection near = new FakeHubConnection();
    private final FakeHubConnection me = new FakeHubConnection();
    private final FakeHubConnection far = new FakeHubConnection();
    private final FakeHubConnection idle = new FakeHubConnection();

    @BeforeEach
    void setUp() {
        hub = new GhostHub(() -> now, TrackValidationProfileSource.none(),
                (slot, fp, kind, detail) -> { }, true);
        hub.setTrack("s3k", 0, 0);
        hub.addPlayer(0, "fp-me", me);
        hub.addPlayer(1, "fp-near", near);
        hub.addPlayer(2, "fp-far", far);
        hub.addPlayer(3, "fp-idle", idle); // never streams a frame
    }

    private static byte[] frames(int x, int count, boolean finishedLast) {
        byte[] data = new byte[count * GhostFrameCodec.BYTES];
        for (int i = 0; i < count; i++) {
            GhostFrameCodec.encode(new GhostFrame(x + i, 256, 1, false, false,
                    finishedLast && i == count - 1, 2, false), data, i * GhostFrameCodec.BYTES);
        }
        return data;
    }

    private void stream(int slot, int attemptId, int startIndex, int x) {
        hub.onBinary(slot, GhostPackets.encodeFrames(attemptId, startIndex, frames(x, 3, false)));
    }

    @Test
    void aggregatesIncludeOnlyNearSenders() {
        stream(0, 1, 0, 1000);      // me at x=1000
        stream(1, 1, 0, 1200);      // near me
        stream(2, 1, 0, 9000);      // far away
        hub.tick();

        GhostPackets.Aggregate forMe = GhostPackets.decodeAggregate(me.binary.get(0));
        assertEquals(1, forMe.entries().size());
        assertEquals(1, forMe.entries().get(0).playerSlot()); // only the near peer

        GhostPackets.Aggregate forFar = GhostPackets.decodeAggregate(far.binary.isEmpty()
                ? GhostPackets.encodeAggregate(0, List.of()) : far.binary.get(0));
        assertTrue(far.binary.isEmpty() || forFar.entries().isEmpty()); // nobody near the far player
    }

    @Test
    void rosterArrivesAtOneHertzForEveryone() {
        stream(0, 1, 0, 1000);
        stream(1, 1, 0, 1200);
        stream(2, 1, 0, 9000);
        for (int tick = 0; tick < GhostHub.ROSTER_INTERVAL_TICKS; tick++) {
            hub.tick();
        }
        // roster packets are TYPE_ROSTER; find one delivered to the far player
        List<GhostPackets.RosterEntry> roster = new ArrayList<>();
        for (byte[] packet : far.binary) {
            if ((packet[0] & 0xFF) == GhostPackets.TYPE_ROSTER) {
                roster = GhostPackets.decodeRoster(packet);
            }
        }
        assertEquals(4, roster.size()); // ALL players — far AND idle ones
        GhostPackets.RosterEntry farEntry = roster.stream()
                .filter(e -> e.playerSlot() == 2).findFirst().orElseThrow();
        assertEquals(9002 >>> 6, farEntry.cellX()); // last frame x quantized to 64-px cells
        assertEquals(GhostPackets.ROSTER_STATUS_RUNNING, farEntry.status());
        GhostPackets.RosterEntry idleEntry = roster.stream()
                .filter(e -> e.playerSlot() == 3).findFirst().orElseThrow();
        assertEquals(GhostPackets.ROSTER_STATUS_IDLE, idleEntry.status()); // listed despite never streaming
        assertEquals(0, idleEntry.cellX());
    }

    @Test
    void finishedBitFlipsRosterStatus() {
        hub.onBinary(1, GhostPackets.encodeFrames(1, 0, frames(1200, 3, true)));
        for (int tick = 0; tick < GhostHub.ROSTER_INTERVAL_TICKS; tick++) {
            hub.tick();
        }
        List<GhostPackets.RosterEntry> roster = new ArrayList<>();
        for (byte[] packet : me.binary) {
            if ((packet[0] & 0xFF) == GhostPackets.TYPE_ROSTER) {
                roster = GhostPackets.decodeRoster(packet);
            }
        }
        assertEquals(GhostPackets.ROSTER_STATUS_FINISHED, roster.stream()
                .filter(e -> e.playerSlot() == 1).findFirst().orElseThrow().status());
    }

    @Test
    void smallRoomModeIsUnchangedNoFilteringNoRoster() {
        GhostHub smallRoom = new GhostHub(() -> now, TrackValidationProfileSource.none(),
                (slot, fp, kind, detail) -> { });
        FakeHubConnection a = new FakeHubConnection();
        FakeHubConnection b = new FakeHubConnection();
        smallRoom.setTrack("s3k", 0, 0);
        smallRoom.addPlayer(0, "a", a);
        smallRoom.addPlayer(1, "b", b);
        smallRoom.onBinary(0, GhostPackets.encodeFrames(1, 0, frames(1000, 3, false)));
        smallRoom.onBinary(1, GhostPackets.encodeFrames(1, 0, frames(60_000 & 0xFFFF, 3, false)));
        for (int tick = 0; tick < GhostHub.ROSTER_INTERVAL_TICKS + 1; tick++) {
            smallRoom.tick();
        }
        // both received the other's frames despite the distance, and no roster packets exist
        assertTrue(a.binary.stream().anyMatch(p -> (p[0] & 0xFF) == GhostPackets.TYPE_GHOST_AGGREGATE));
        assertTrue(a.binary.stream().noneMatch(p -> (p[0] & 0xFF) == GhostPackets.TYPE_ROSTER));
        assertTrue(b.binary.stream().noneMatch(p -> (p[0] & 0xFF) == GhostPackets.TYPE_ROSTER));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestGhostHubScaleMode" test`
Expected: COMPILATION ERROR (4-arg constructor / `ROSTER_INTERVAL_TICKS` absent).

- [ ] **Step 3: Implement in GhostHub**

Additions (phase-2 code otherwise untouched — read the as-built class first):

```java
    public static final int ROSTER_INTERVAL_TICKS = 20; // 1 Hz at the 20 Hz tick (main spec §4.3)

    private final boolean relevanceFiltering;
    private final RelevanceClassifier classifier = new RelevanceClassifier();
    private final Map<Integer, Integer> lastStatus = new TreeMap<>();

    public GhostHub(LongSupplier wallClockMillis, TrackValidationProfileSource profiles,
                    HubViolationRecorder recorder) {
        this(wallClockMillis, profiles, recorder, false); // ≤8 player-host rooms: no filtering
    }

    public GhostHub(LongSupplier wallClockMillis, TrackValidationProfileSource profiles,
                    HubViolationRecorder recorder, boolean relevanceFiltering) {
        // ...existing field assignments...
        this.relevanceFiltering = relevanceFiltering;
    }
```

In `onBinary`, after a batch is ACCEPTED and buffered, decode its last frame once and track position/status:

```java
        GhostFrame lastFrame = GhostFrameCodec.decode(batch.frameData(),
                (batch.frameCount() - 1) * GhostFrameCodec.BYTES);
        classifier.updatePosition(slot, lastFrame.x(), lastFrame.y());
        lastStatus.put(slot, lastFrame.finished()
                ? GhostPackets.ROSTER_STATUS_FINISHED : GhostPackets.ROSTER_STATUS_RUNNING);
```

In `removePlayer`: `classifier.remove(slot); lastStatus.remove(slot);`.

In `tick()`, replace the recipient loop's sender-inclusion test: with filtering on, call `classifier.rebucket()` once at the top, compute `Set<Integer> nearSet = classifier.nearSetFor(recipientSlot)` per recipient, and include a sender's entries only when `nearSet.contains(senderSlot)`; with filtering off, keep the phase-2 "everyone but self" rule. IMPORTANT drain semantics with filtering: drained frames for a sender S must not be consumed only for recipients that are near S — drain each sender ONCE per tick (as phase 2 does) and distribute the drained entries to near recipients; far recipients simply never see them (their view is the roster; frames are cosmetic and non-replayable later, so dropping them for far recipients is correct and is the entire bandwidth win of §4.2).

Roster emission at the end of `tick()`:

```java
        if (relevanceFiltering && tickCount % ROSTER_INTERVAL_TICKS == 0) {
            List<GhostPackets.RosterEntry> roster = new ArrayList<>();
            for (Map.Entry<Integer, Player> entry : players.entrySet()) {
                var position = classifier.positionOf(entry.getKey()); // add this tiny accessor to RelevanceClassifier: record Pos(int x, int y); Pos positionOf(int slot) — null before any frame
                roster.add(new GhostPackets.RosterEntry(entry.getKey(),
                        position == null ? 0 : Math.min(position.x() >>> 6, 0xFFFF),
                        position == null ? 0 : Math.min(position.y() >>> 6, 255),
                        lastStatus.getOrDefault(entry.getKey(), GhostPackets.ROSTER_STATUS_IDLE)));
            }
            if (!roster.isEmpty()) {
                byte[] packet = GhostPackets.encodeRoster(roster);
                for (Player player : players.values()) {
                    player.connection.sendBinary(packet);
                }
            }
        }
```

Add to `RelevanceClassifier`: `public record Pos(int x, int y) {}` plus `public Pos positionOf(int slot)` returning the stored position or null (one-line accessor over the internal map; adjust the internal `Position` record to be this public `Pos`).

- [ ] **Step 4: Run scale tests AND the phase-2 hub tests**

Run: `mvn "-Dtest=com.openggf.net.hub.TestGhostHubScaleMode+com.openggf.net.hub.TestGhostHub" test`
Expected: PASS both — small-room behavior is regression-free.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/GhostHub.java src/main/java/com/openggf/net/hub/RelevanceClassifier.java src/test/java/com/openggf/net/hub/TestGhostHubScaleMode.java
git commit -m "feat(timeattack): relevance-filtered aggregates and 1Hz roster in GhostHub

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 4: Backpressure ladder

**Files:**
- Modify: `src/main/java/com/openggf/net/hub/HubConnection.java` (add `default int queuedBytes() { return 0; }`)
- Modify: `src/main/java/com/openggf/net/hub/GhostHub.java` (per-recipient ladder in `tick()`)
- Modify: `src/main/java/com/openggf/net/host/RaceHostChannelHandler.java` (`NettyHubConnection.queuedBytes()` from the channel's outbound buffer)
- Test: `src/test/java/com/openggf/net/hub/TestBackpressureLadder.java`

**Interfaces:**
- Produces (main spec §4.5 — a slow client only ever degrades its OWN view):
  - `HubConnection.queuedBytes()` — default 0 (phase-2 fakes and small rooms unaffected).
  - Ladder constants on `GhostHub`: `BP_DEGRADE_BYTES = 64 * 1024` (near cap drops to `BP_DEGRADED_NEAR_CAP = 4`, roster slows to every `2 × ROSTER_INTERVAL_TICKS` = 0.5 Hz), `BP_ROSTER_ONLY_BYTES = 256 * 1024` (no aggregates, roster only), `BP_DISCONNECT_BYTES = 1024 * 1024` and `BP_SUSTAINED_MILLIS = 30_000` (queue over `BP_DEGRADE_BYTES` for 30 s sustained) → `connection.close("slow consumer")` + removed from the room.
  - Applied per recipient at the top of the tick; active in BOTH hub modes (a slow phase-2 guest also deserves protection), but roster stages only matter with filtering on.
  - `NettyHubConnection.queuedBytes()`: `ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer(); return buffer == null ? 0 : (int) Math.min(Integer.MAX_VALUE, buffer.totalPendingWriteBytes());`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.hub;

import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostFrameCodec;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBackpressureLadder {
    /** FakeHubConnection with a settable queue depth. */
    static final class SlowConnection extends FakeHubConnection {
        int queued;

        @Override
        public int queuedBytes() {
            return queued;
        }
    }

    private long now = 50_000;
    private GhostHub hub;
    private final SlowConnection slow = new SlowConnection();
    private final FakeHubConnection healthy = new FakeHubConnection();
    private final FakeHubConnection sender = new FakeHubConnection();

    @BeforeEach
    void setUp() {
        hub = new GhostHub(() -> now, TrackValidationProfileSource.none(),
                (slot, fp, kind, detail) -> { }, true);
        hub.setTrack("s3k", 0, 0);
        hub.addPlayer(0, "sender", sender);
        hub.addPlayer(1, "slow", slow);
        hub.addPlayer(2, "healthy", healthy);
    }

    private void streamNearEveryone(int startIndex) {
        byte[] data = new byte[3 * GhostFrameCodec.BYTES];
        for (int i = 0; i < 3; i++) {
            GhostFrameCodec.encode(new GhostFrame(1000 + startIndex + i, 256, 1, false, false, false, 2, false),
                    data, i * GhostFrameCodec.BYTES);
        }
        hub.onBinary(0, GhostPackets.encodeFrames(1, startIndex, data));
        // slow + healthy stand near the sender
        byte[] near = new byte[GhostFrameCodec.BYTES];
        GhostFrameCodec.encode(new GhostFrame(1010, 256, 1, false, false, false, 2, false), near, 0);
        if (startIndex == 0) {
            hub.onBinary(1, GhostPackets.encodeFrames(1, 0, java.util.Arrays.copyOf(near, near.length)));
            hub.onBinary(2, GhostPackets.encodeFrames(1, 0, java.util.Arrays.copyOf(near, near.length)));
        }
    }

    @Test
    void rosterOnlyStageSuppressesAggregates() {
        streamNearEveryone(0);
        slow.queued = GhostHub.BP_ROSTER_ONLY_BYTES + 1;
        hub.tick();
        assertTrue(slow.binary.stream().noneMatch(p -> (p[0] & 0xFF) == GhostPackets.TYPE_GHOST_AGGREGATE),
                "roster-only stage must suppress aggregates");
        assertTrue(healthy.binary.stream().anyMatch(p -> (p[0] & 0xFF) == GhostPackets.TYPE_GHOST_AGGREGATE),
                "healthy clients are unaffected");
    }

    @Test
    void disconnectStageClosesImmediatelyOnHugeQueue() {
        slow.queued = GhostHub.BP_DISCONNECT_BYTES + 1;
        hub.tick();
        assertNotNull(slow.closedReason);
    }

    @Test
    void sustainedDegradeDisconnects() {
        slow.queued = GhostHub.BP_DEGRADE_BYTES + 1; // degraded but not huge
        for (int i = 0; i <= GhostHub.BP_SUSTAINED_MILLIS / 50; i++) {
            hub.tick();
            now += 50;
        }
        assertNotNull(slow.closedReason); // 30 s sustained over the degrade threshold
    }

    @Test
    void recoveryClearsTheSustainedClock() {
        slow.queued = GhostHub.BP_DEGRADE_BYTES + 1;
        for (int i = 0; i < 100; i++) { // 5 s degraded
            hub.tick();
            now += 50;
        }
        slow.queued = 0; // client caught up
        for (int i = 0; i < 700; i++) { // 35 s healthy
            hub.tick();
            now += 50;
        }
        assertNull(slow.closedReason);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestBackpressureLadder" test`
Expected: COMPILATION ERROR (constants/`queuedBytes` absent). Note `FakeHubConnection` must not be final and its fields package-visible — adjust the phase-2 fixture if needed.

- [ ] **Step 3: Implement**

`HubConnection`:

```java
    /** Outbound queue depth in bytes; 0 when unknown. Drives the §4.5 backpressure ladder. */
    default int queuedBytes() {
        return 0;
    }
```

`GhostHub` — constants + per-recipient ladder state:

```java
    public static final int BP_DEGRADE_BYTES = 64 * 1024;
    public static final int BP_ROSTER_ONLY_BYTES = 256 * 1024;
    public static final int BP_DISCONNECT_BYTES = 1024 * 1024;
    public static final long BP_SUSTAINED_MILLIS = 30_000;
    public static final int BP_DEGRADED_NEAR_CAP = 4;

    private final Map<Integer, Long> degradedSinceMillis = new TreeMap<>();
```

At the top of the per-recipient portion of `tick()` (before composing that recipient's aggregate):

```java
            int queued = recipient.getValue().connection.queuedBytes();
            long nowMillis = wallClockMillis.getAsLong();
            if (queued > BP_DISCONNECT_BYTES) {
                dropSlowConsumer(recipient.getKey(), recipient.getValue());
                continue;
            }
            if (queued > BP_DEGRADE_BYTES) {
                long since = degradedSinceMillis.computeIfAbsent(recipient.getKey(), s -> nowMillis);
                if (nowMillis - since >= BP_SUSTAINED_MILLIS) {
                    dropSlowConsumer(recipient.getKey(), recipient.getValue());
                    continue;
                }
            } else {
                degradedSinceMillis.remove(recipient.getKey());
            }
            boolean rosterOnly = queued > BP_ROSTER_ONLY_BYTES;
            int nearCap = queued > BP_DEGRADE_BYTES ? BP_DEGRADED_NEAR_CAP : RelevanceClassifier.NEAR_CAP;
            // rosterOnly → skip the aggregate for this recipient entirely;
            // nearCap → truncate this recipient's near set to its nearest `nearCap` entries.
```

`dropSlowConsumer(slot, player)`: `player.connection.close("slow consumer"); removePlayer(slot); degradedSinceMillis.remove(slot);` — iterate over a snapshot of the player map in `tick()` so removal is safe mid-loop. Roster cadence for degraded recipients: send roster only when `tickCount % (2 * ROSTER_INTERVAL_TICKS) == 0` for recipients over `BP_DEGRADE_BYTES` (0.5 Hz — main spec §4.5); implement by checking the recipient's queue at roster-send time. Collection iteration note: `players` mutation inside `tick()` means the recipient loop must run over `List.copyOf(players.entrySet())`.

`RaceHostChannelHandler.NettyHubConnection` — the record becomes a small class (records cannot add the needed behavior cleanly while keeping the channel field private is fine — keep it a record and add the method):

```java
        @Override
        public int queuedBytes() {
            io.netty.channel.ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
            return buffer == null ? 0 : (int) Math.min(Integer.MAX_VALUE, buffer.totalPendingWriteBytes());
        }
```

- [ ] **Step 4: Run the ladder, scale, and phase-2 hub tests**

Run: `mvn "-Dtest=com.openggf.net.hub.Test*" test`
Expected: PASS (all hub-layer suites green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/HubConnection.java src/main/java/com/openggf/net/hub/GhostHub.java src/main/java/com/openggf/net/host/RaceHostChannelHandler.java src/test/java/com/openggf/net/hub/TestBackpressureLadder.java src/test/java/com/openggf/net/hub/FakeHubConnection.java
git commit -m "feat(timeattack): backpressure degradation ladder in GhostHub

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 5: ProofOfWork + identity creation stamp

**Files:**
- Create: `src/main/java/com/openggf/net/identity/ProofOfWork.java`
- Modify: `src/main/java/com/openggf/net/identity/PlayerIdentity.java` (creation stamp: compute lazily, persist beside the keypair)
- Test: `src/test/java/com/openggf/net/identity/TestProofOfWork.java`

**Interfaces:**
- Produces:
  - `ProofOfWork` (static, engine-free): `boolean meetsDifficulty(byte[] sha256, int difficultyBits)` (that many leading zero BITS); `long solve(byte[] payload, int difficultyBits)` (iterates nonce 0,1,2,… until `sha256(payload ‖ nonceLE8)` meets difficulty); `boolean verify(byte[] payload, long nonce, int difficultyBits)`. Nonce is appended as 8 little-endian bytes.
  - `PlayerIdentity.creationPowNonce(int difficultyBits)` — the one-time identity creation stamp (security spec §3): payload = the encoded public key; solved once and persisted to `player-identity.pow` (format: `difficultyBits + "\n" + nonce`) so a real player pays the cost exactly once; recomputed only if a master demands HIGHER difficulty than stored. Solving at difficulty 20 ≈ ~1 s of client compute (target "a few seconds"); tests use 8–12 bits so they run in milliseconds.
  - Difficulty is MASTER CONFIG, not a protocol constant — the client learns it from `PowChallenge("IDENTITY", …, difficultyBits)` where the challenge prefix for the IDENTITY kind is ignored (the payload is the pubkey itself; the stamp must be reusable across masters, which is what makes it a creation stamp rather than a session challenge). For `"JOIN"` (attack mode), payload = the challenge prefix bytes — single-use, per-connection.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestProofOfWork {
    @Test
    void solveProducesVerifiableNonce() {
        byte[] payload = "challenge".getBytes(StandardCharsets.UTF_8);
        long nonce = ProofOfWork.solve(payload, 12);
        assertTrue(ProofOfWork.verify(payload, nonce, 12));
        assertTrue(ProofOfWork.verify(payload, nonce, 8));   // easier difficulty also satisfied
        assertFalse(ProofOfWork.verify(payload, nonce + 1, 12) && ProofOfWork.verify(payload, nonce + 2, 12)
                && ProofOfWork.verify(payload, nonce + 3, 12)); // neighbours are (almost surely) not all valid
        assertFalse(ProofOfWork.verify("other".getBytes(StandardCharsets.UTF_8), nonce, 12));
    }

    @Test
    void meetsDifficultyCountsLeadingZeroBits() {
        byte[] hash = new byte[32];
        hash[0] = 0x00;
        hash[1] = 0x0F; // 8 + 4 = 12 leading zero bits
        assertTrue(ProofOfWork.meetsDifficulty(hash, 12));
        assertFalse(ProofOfWork.meetsDifficulty(hash, 13));
    }

    @Test
    void identityCreationStampPersistsAndReloads(@TempDir Path dir) throws Exception {
        PlayerIdentity identity = PlayerIdentity.loadOrCreate(dir);
        long nonce = identity.creationPowNonce(10);
        assertTrue(ProofOfWork.verify(identity.publicKeyEncoded(), nonce, 10));
        assertTrue(Files.exists(dir.resolve("player-identity.pow")));

        PlayerIdentity reloaded = PlayerIdentity.loadOrCreate(dir);
        assertEquals(nonce, reloaded.creationPowNonce(10)); // no re-solve

        long harder = reloaded.creationPowNonce(12); // master demands more: re-solve + re-persist
        assertTrue(ProofOfWork.verify(identity.publicKeyEncoded(), harder, 12));
        assertEquals(harder, PlayerIdentity.loadOrCreate(dir).creationPowNonce(12));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.identity.TestProofOfWork" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.identity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hashcash-style proof of work (security spec §3/§8): sha256(payload ‖ nonceLE8)
 * with N leading zero bits. Rate-limits bulk identity farming and, in attack mode,
 * makes join floods compute-expensive while staying sub-second for real clients.
 */
public final class ProofOfWork {
    private ProofOfWork() {
    }

    public static boolean meetsDifficulty(byte[] sha256, int difficultyBits) {
        int fullBytes = difficultyBits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (sha256[i] != 0) {
                return false;
            }
        }
        int remainingBits = difficultyBits % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (sha256[fullBytes] & mask) == 0;
    }

    public static long solve(byte[] payload, int difficultyBits) {
        for (long nonce = 0; ; nonce++) {
            if (verify(payload, nonce, difficultyBits)) {
                return nonce;
            }
        }
    }

    public static boolean verify(byte[] payload, long nonce, int difficultyBits) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(payload);
            digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(nonce).array());
            return meetsDifficulty(digest.digest(), difficultyBits);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`PlayerIdentity` additions (read the as-built phase-1 class first; the identity dir `Path` must be retained as a field — add it in `loadOrCreate`'s construction path):

```java
    private static final String POW_FILE = "player-identity.pow";
    private final Path identityDir; // set in loadOrCreate

    /**
     * One-time identity creation stamp (security spec §3): PoW over our own pubkey,
     * persisted so a real player solves it exactly once. Re-solves only when a master
     * demands higher difficulty than the stored stamp.
     */
    public synchronized long creationPowNonce(int difficultyBits) throws IOException {
        Path powPath = identityDir.resolve(POW_FILE);
        if (Files.exists(powPath)) {
            String[] parts = Files.readString(powPath).trim().split("\n");
            int storedBits = Integer.parseInt(parts[0]);
            long storedNonce = Long.parseLong(parts[1]);
            if (storedBits >= difficultyBits
                    && ProofOfWork.verify(publicKeyEncoded(), storedNonce, difficultyBits)) {
                return storedNonce;
            }
        }
        long nonce = ProofOfWork.solve(publicKeyEncoded(), difficultyBits);
        Files.writeString(powPath, difficultyBits + "\n" + nonce);
        return nonce;
    }
```

- [ ] **Step 4: Run identity tests (new + phase-1)**

Run: `mvn "-Dtest=com.openggf.net.identity.*" test`
Expected: PASS (phase-1 `TestPlayerIdentity` unaffected + 3 new tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/identity/ProofOfWork.java src/main/java/com/openggf/net/identity/PlayerIdentity.java src/test/java/com/openggf/net/identity/TestProofOfWork.java
git commit -m "feat(timeattack): hashcash proof-of-work and persisted identity creation stamp

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 6: IdentityStore — SQLite persistence with write-on-merit

**Files:**
- Modify: `pom.xml` (add sqlite-jdbc)
- Create: `src/main/java/com/openggf/net/master/IdentityStore.java`
- Create: `src/main/java/com/openggf/net/master/SqliteIdentityStore.java`
- Create: `src/main/java/com/openggf/net/master/NewIdentityCache.java`
- Test: `src/test/java/com/openggf/net/master/TestSqliteIdentityStore.java`

**Interfaces:**
- Maven: beside the Netty dependency add

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.46.1.3</version>
</dependency>
```

- Produces:
  - `IdentityStore` (interface — the Postgres seam, security spec §5) `extends AutoCloseable`:
    - `record IdentityRecord(String fingerprint, long firstSeenMillis, long lastSeenMillis, String displayName, String tier, int cleanRounds)`
    - `record SanctionRecord(String fingerprint, String type, String reason, String issuer, long issuedAtMillis, long expiryMillis)` (`expiryMillis == Long.MAX_VALUE` = permanent)
    - `Optional<IdentityRecord> find(String fingerprint)`
    - `void persistOnDurableEvent(String fingerprint, long firstSeenMillis, long nowMillis)` — INSERT-if-absent (security spec §5.1: presenting a valid keypair does NOT create a row; only a durable event does), updates lastSeen when present
    - `void recordCleanRound(String fingerprint, long nowMillis)` (increments; caller ensures the row exists)
    - `void setDisplayName(String fingerprint, String displayName)`; `void setTier(String fingerprint, String tier)`
    - `void addSanction(SanctionRecord sanction)`; `List<SanctionRecord> activeSanctions(String fingerprint, long nowMillis)`
    - `int gcInactiveNewIdentities(long inactiveSinceMillis)` — purges rows with `tier = 'NEW'` and `lastSeen < inactiveSinceMillis`; **sanctions are keyed by fingerprint in their own table and are NEVER purged before expiry** (a banned key stays banned even if its identity row ages out — security spec §5.1)
  - `SqliteIdentityStore implements IdentityStore` — `new SqliteIdentityStore(Path dbFile)`: opens `jdbc:sqlite:<path>`, sets `PRAGMA journal_mode=WAL`, creates schema if absent. Schema (security spec §5.1): `identities(fingerprint TEXT PRIMARY KEY, first_seen INTEGER, last_seen INTEGER, display_name TEXT, tier TEXT, clean_rounds INTEGER)`; `sanctions(id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT, type TEXT, reason TEXT, issuer TEXT, issued_at INTEGER, expiry INTEGER)`; `verdicts(id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT, attempt_ref TEXT, input_recording_hash TEXT, result TEXT, verifier_signature TEXT, timestamp INTEGER)` — verdicts created now, written by the phase-4 verifier. Single-connection, single-thread use (the broker loop) — no pooling.
  - `NewIdentityCache(int maxSize, long ttlMillis, LongSupplier clock)` — the bounded in-memory home of NEW identities (security spec §5.1: mass key generation fills a fixed-size cache and never touches disk): `long firstSeenOf(String fingerprint)` (records `now` on first sight; refreshes recency LRU-style; expired entries re-record — eviction merely resets the first-seen clock, exactly the wanted cost profile); `int size()`; backed by `LinkedHashMap` access-order with `removeEldestEntry`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.master;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestSqliteIdentityStore {
    @Test
    void writeOnMeritPersistsAndFinds(@TempDir Path dir) throws Exception {
        try (SqliteIdentityStore store = new SqliteIdentityStore(dir.resolve("ids.db"))) {
            assertTrue(store.find("fp1").isEmpty()); // presenting a key creates NO row
            store.persistOnDurableEvent("fp1", 1000, 2000);
            IdentityStore.IdentityRecord record = store.find("fp1").orElseThrow();
            assertEquals(1000, record.firstSeenMillis());
            assertEquals("NEW", record.tier());
            assertEquals(0, record.cleanRounds());

            store.persistOnDurableEvent("fp1", 999_999, 3000); // second event: no first-seen rewrite
            assertEquals(1000, store.find("fp1").orElseThrow().firstSeenMillis());
            assertEquals(3000, store.find("fp1").orElseThrow().lastSeenMillis());
        }
    }

    @Test
    void cleanRoundsTierAndNamePersistAcrossReopen(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("ids.db");
        try (SqliteIdentityStore store = new SqliteIdentityStore(db)) {
            store.persistOnDurableEvent("fp1", 1000, 1000);
            store.recordCleanRound("fp1", 2000);
            store.recordCleanRound("fp1", 3000);
            store.setDisplayName("fp1", "Farrell");
            store.setTier("fp1", "ESTABLISHED");
        }
        try (SqliteIdentityStore store = new SqliteIdentityStore(db)) {
            IdentityStore.IdentityRecord record = store.find("fp1").orElseThrow();
            assertEquals(2, record.cleanRounds());
            assertEquals("Farrell", record.displayName());
            assertEquals("ESTABLISHED", record.tier());
        }
    }

    @Test
    void sanctionsSurviveIdentityGcAndExpire(@TempDir Path dir) throws Exception {
        try (SqliteIdentityStore store = new SqliteIdentityStore(dir.resolve("ids.db"))) {
            store.persistOnDurableEvent("banned", 1000, 1000);
            store.addSanction(new IdentityStore.SanctionRecord(
                    "banned", "BAN", "cheating", "operator", 1000, Long.MAX_VALUE));
            store.addSanction(new IdentityStore.SanctionRecord(
                    "banned", "TIMEOUT", "spam", "operator", 1000, 5000));

            assertEquals(2, store.activeSanctions("banned", 2000).size());
            assertEquals(1, store.activeSanctions("banned", 6000).size()); // timeout expired

            int purged = store.gcInactiveNewIdentities(999_999); // everything inactive
            assertEquals(1, purged);
            assertTrue(store.find("banned").isEmpty());
            assertEquals(1, store.activeSanctions("banned", 6000).size()); // ban outlives the row
        }
    }

    @Test
    void gcSparesNonNewTiers(@TempDir Path dir) throws Exception {
        try (SqliteIdentityStore store = new SqliteIdentityStore(dir.resolve("ids.db"))) {
            store.persistOnDurableEvent("vet", 1000, 1000);
            store.setTier("vet", "TRUSTED");
            store.persistOnDurableEvent("noob", 1000, 1000);
            assertEquals(1, store.gcInactiveNewIdentities(999_999));
            assertTrue(store.find("vet").isPresent());
        }
    }

    @Test
    void newIdentityCacheBoundsAndResetsOnEviction() {
        long[] now = {1000};
        NewIdentityCache cache = new NewIdentityCache(2, 10_000, () -> now[0]);
        assertEquals(1000, cache.firstSeenOf("a"));
        now[0] = 2000;
        assertEquals(2000, cache.firstSeenOf("b"));
        assertEquals(1000, cache.firstSeenOf("a")); // still cached
        now[0] = 3000;
        cache.firstSeenOf("c");                     // evicts LRU ("b")
        assertEquals(2, cache.size());
        now[0] = 4000;
        assertEquals(4000, cache.firstSeenOf("b")); // eviction reset b's clock

        now[0] = 20_000;                            // past TTL for everything
        assertEquals(20_000, cache.firstSeenOf("a"));
    }
}
```

- [ ] **Step 2: Add the dependency, run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.master.TestSqliteIdentityStore" test`
Expected: COMPILATION ERROR (classes absent). A dependency-resolution error means the pom edit is wrong — fix first.

- [ ] **Step 3: Write minimal implementation**

`IdentityStore.java` — the interface exactly as in Interfaces (records + 8 methods + `@Override void close()` narrowing to no checked exception is fine via `AutoCloseable`).

```java
package com.openggf.net.master;

import java.util.List;
import java.util.Optional;

/** Trust/sanction persistence seam (security spec §5). SQLite in v1; Postgres later. */
public interface IdentityStore extends AutoCloseable {
    record IdentityRecord(String fingerprint, long firstSeenMillis, long lastSeenMillis,
                          String displayName, String tier, int cleanRounds) {
    }

    record SanctionRecord(String fingerprint, String type, String reason, String issuer,
                          long issuedAtMillis, long expiryMillis) {
    }

    Optional<IdentityRecord> find(String fingerprint);

    /** Write-on-merit (security spec §5.1): rows exist only after a durable event. */
    void persistOnDurableEvent(String fingerprint, long firstSeenMillis, long nowMillis);

    void recordCleanRound(String fingerprint, long nowMillis);

    void setDisplayName(String fingerprint, String displayName);

    void setTier(String fingerprint, String tier);

    void addSanction(SanctionRecord sanction);

    List<SanctionRecord> activeSanctions(String fingerprint, long nowMillis);

    /** Purges inactive NEW-tier rows. Sanctions are never purged before expiry. */
    int gcInactiveNewIdentities(long inactiveSinceMillis);

    @Override
    void close();
}
```

```java
package com.openggf.net.master;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Single-file SQLite store (WAL) — security spec §5. Single-threaded by contract:
 * only the master broker loop touches it. KBs per identity; nightly file copy is
 * the backup story.
 */
public final class SqliteIdentityStore implements IdentityStore {
    private final Connection connection;

    public SqliteIdentityStore(Path dbFile) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS identities (
                          fingerprint TEXT PRIMARY KEY, first_seen INTEGER, last_seen INTEGER,
                          display_name TEXT, tier TEXT, clean_rounds INTEGER)""");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS sanctions (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT, type TEXT,
                          reason TEXT, issuer TEXT, issued_at INTEGER, expiry INTEGER)""");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS verdicts (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT, attempt_ref TEXT,
                          input_recording_hash TEXT, result TEXT, verifier_signature TEXT,
                          timestamp INTEGER)""");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to open identity store at " + dbFile, e);
        }
    }

    @Override
    public Optional<IdentityRecord> find(String fingerprint) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT first_seen, last_seen, display_name, tier, clean_rounds FROM identities WHERE fingerprint = ?")) {
            statement.setString(1, fingerprint);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new IdentityRecord(fingerprint, row.getLong(1), row.getLong(2),
                        row.getString(3), row.getString(4), row.getInt(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void persistOnDurableEvent(String fingerprint, long firstSeenMillis, long nowMillis) {
        execute("""
                INSERT INTO identities (fingerprint, first_seen, last_seen, display_name, tier, clean_rounds)
                VALUES (?, ?, ?, '', 'NEW', 0)
                ON CONFLICT(fingerprint) DO UPDATE SET last_seen = excluded.last_seen""",
                fingerprint, firstSeenMillis, nowMillis);
    }

    @Override
    public void recordCleanRound(String fingerprint, long nowMillis) {
        execute("UPDATE identities SET clean_rounds = clean_rounds + 1, last_seen = ? WHERE fingerprint = ?",
                nowMillis, fingerprint);
    }

    @Override
    public void setDisplayName(String fingerprint, String displayName) {
        execute("UPDATE identities SET display_name = ? WHERE fingerprint = ?", displayName, fingerprint);
    }

    @Override
    public void setTier(String fingerprint, String tier) {
        execute("UPDATE identities SET tier = ? WHERE fingerprint = ?", tier, fingerprint);
    }

    @Override
    public void addSanction(SanctionRecord sanction) {
        execute("INSERT INTO sanctions (fingerprint, type, reason, issuer, issued_at, expiry) VALUES (?, ?, ?, ?, ?, ?)",
                sanction.fingerprint(), sanction.type(), sanction.reason(), sanction.issuer(),
                sanction.issuedAtMillis(), sanction.expiryMillis());
    }

    @Override
    public List<SanctionRecord> activeSanctions(String fingerprint, long nowMillis) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT type, reason, issuer, issued_at, expiry FROM sanctions WHERE fingerprint = ? AND expiry > ?")) {
            statement.setString(1, fingerprint);
            statement.setLong(2, nowMillis);
            try (ResultSet row = statement.executeQuery()) {
                List<SanctionRecord> sanctions = new ArrayList<>();
                while (row.next()) {
                    sanctions.add(new SanctionRecord(fingerprint, row.getString(1), row.getString(2),
                            row.getString(3), row.getLong(4), row.getLong(5)));
                }
                return sanctions;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int gcInactiveNewIdentities(long inactiveSinceMillis) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM identities WHERE tier = 'NEW' AND last_seen < ?")) {
            statement.setLong(1, inactiveSinceMillis);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void execute(String sql, Object... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

```java
package com.openggf.net.master;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded LRU+TTL cache holding NEW identities' first-seen times (security spec §5.1):
 * identity spam fills THIS, never the disk. Eviction/expiry merely resets the clock —
 * imperceptible for a real player who plays a round within the TTL.
 */
public final class NewIdentityCache {
    private record Entry(long firstSeenMillis, long touchedMillis) {
    }

    private final int maxSize;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries;

    public NewIdentityCache(int maxSize, long ttlMillis, LongSupplier clock) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > NewIdentityCache.this.maxSize;
            }
        };
    }

    public long firstSeenOf(String fingerprint) {
        long now = clock.getAsLong();
        Entry entry = entries.get(fingerprint);
        if (entry == null || now - entry.touchedMillis() > ttlMillis) {
            entry = new Entry(now, now);
        } else {
            entry = new Entry(entry.firstSeenMillis(), now);
        }
        entries.put(fingerprint, entry);
        return entry.firstSeenMillis();
    }

    public int size() {
        return entries.size();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.master.TestSqliteIdentityStore" test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/openggf/net/master/IdentityStore.java src/main/java/com/openggf/net/master/SqliteIdentityStore.java src/main/java/com/openggf/net/master/NewIdentityCache.java src/test/java/com/openggf/net/master/TestSqliteIdentityStore.java
git commit -m "feat(timeattack): SQLite identity store with write-on-merit and new-identity cache

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 7: TrustLadder

**Files:**
- Create: `src/main/java/com/openggf/net/master/TrustLadder.java`
- Test: `src/test/java/com/openggf/net/master/TestTrustLadder.java`

**Interfaces:**
- Consumes: `IdentityStore`, `NewIdentityCache` (Task 6).
- Produces `TrustLadder(IdentityStore store, NewIdentityCache cache, Thresholds thresholds, LongSupplier clock)` (security spec §4):
  - `enum Tier { NEW, ESTABLISHED, TRUSTED, SANCTIONED }`.
  - `record Thresholds(long establishedAgeMillis, int establishedCleanRounds, long trustedAgeMillis, int trustedCleanRounds)` with `static Thresholds defaults()` = 48 h / 10 rounds / 14 days / 50 rounds (master-config-tunable — Task 8 wires config values in).
  - `Tier tierOf(String fingerprint)` — an active `BAN` sanction → `SANCTIONED` (rejected at handshake); no store row → `NEW` (age tracked in the cache); with a row → computed from BOTH server-observed age AND clean rounds (age alone never grants trust — clean rounds are the gating resource, security spec §5.1). Tier stored back via `setTier` when it changes (promotions are durable).
  - `void onCleanRound(String fingerprint)` — the durable event: `cache.firstSeenOf` (for the age origin) → `persistOnDurableEvent` → `recordCleanRound` → recompute/store tier. "Clean round" = completed round with no hub violations (Task 8's RoomHost outcome hook decides cleanliness; the ladder just counts). **Accrual pacing:** `ACCRUAL_MIN_INTERVAL_MILLIS = 5 * 60_000` — a clean round arriving within the interval of the same identity's previous accrual is silently ignored (in-memory map, broker-loop-confined). This is the "playing rounds is rate-limited per identity" control of security spec §5.1: without it, a creator looping 10-second round windows farms clean rounds arbitrarily fast (wall-clock age still gates tiers, but the rounds resource must not be free).
  - `void onDisplayNameClaim(String fingerprint, String displayName)` — the other durable event.
  - `void sanction(IdentityStore.SanctionRecord record)` — persists the identity row if absent (a sanction is durable by definition), adds the sanction; demotion is instant and destroys accrued standing (`setTier("NEW")` on BAN — burning a trusted identity costs days to rebuild, security spec §4).
  - Convenience gates: `boolean isBanned(String fingerprint)`; `boolean canCreateRoom(String fingerprint)` (tier != NEW && !banned — security spec §4/§8: NEW cannot create rooms); `boolean canChatYet(String fingerprint, long memberSinceMillis)` (non-NEW always; NEW read-only for `NEW_CHAT_MUTE_MILLIS = 5 * 60_000` per room).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.master;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestTrustLadder {
    private long now = 1_000_000;

    private TrustLadder ladder(Path dir) {
        IdentityStore store = new SqliteIdentityStore(dir.resolve("ids.db"));
        NewIdentityCache cache = new NewIdentityCache(1000, 3_600_000, () -> now);
        return new TrustLadder(store, cache, TrustLadder.Thresholds.defaults(), () -> now);
    }

    @Test
    void freshIdentityIsNewAndCannotCreateRooms(@TempDir Path dir) {
        TrustLadder ladder = ladder(dir);
        assertEquals(TrustLadder.Tier.NEW, ladder.tierOf("fp"));
        assertFalse(ladder.canCreateRoom("fp"));
        assertFalse(ladder.canChatYet("fp", now)); // read-only window just started
        assertTrue(ladder.canChatYet("fp", now - TrustLadder.NEW_CHAT_MUTE_MILLIS - 1));
    }

    @Test
    void ageAloneNeverPromotesRoundsAloneNeitherBothDo(@TempDir Path dir) {
        TrustLadder ladder = ladder(dir);
        ladder.onCleanRound("fp"); // durable: row exists, 1 round, age 0
        now += TrustLadder.Thresholds.defaults().establishedAgeMillis() + 1;
        assertEquals(TrustLadder.Tier.NEW, ladder.tierOf("fp")); // old enough, too few rounds

        for (int i = 0; i < 9; i++) {
            now += TrustLadder.ACCRUAL_MIN_INTERVAL_MILLIS + 1;
            ladder.onCleanRound("fp"); // now 10 rounds AND old enough
        }
        assertEquals(TrustLadder.Tier.ESTABLISHED, ladder.tierOf("fp"));
        assertTrue(ladder.canCreateRoom("fp"));

        TrustLadder fastFarm = ladder(dir.resolve("sub"));
        for (int i = 0; i < 60; i++) {
            now += TrustLadder.ACCRUAL_MIN_INTERVAL_MILLIS + 1;
            fastFarm.onCleanRound("farmer"); // 60 rounds in ~5 hours of wall clock
        }
        assertEquals(TrustLadder.Tier.NEW, fastFarm.tierOf("farmer")); // rounds alone never promote
    }

    @Test
    void trustedRequiresBothLongAgeAndFiftyRounds(@TempDir Path dir) {
        TrustLadder ladder = ladder(dir);
        ladder.onCleanRound("fp");
        now += TrustLadder.Thresholds.defaults().trustedAgeMillis() + 1;
        for (int i = 0; i < 49; i++) {
            now += TrustLadder.ACCRUAL_MIN_INTERVAL_MILLIS + 1;
            ladder.onCleanRound("fp");
        }
        assertEquals(TrustLadder.Tier.TRUSTED, ladder.tierOf("fp")); // 50 rounds + 14 days
    }

    @Test
    void banRejectsAtHandshakeAndDestroysStanding(@TempDir Path dir) {
        TrustLadder ladder = ladder(dir);
        ladder.onCleanRound("fp");
        now += TrustLadder.Thresholds.defaults().establishedAgeMillis() + 1;
        for (int i = 0; i < 9; i++) {
            now += TrustLadder.ACCRUAL_MIN_INTERVAL_MILLIS + 1;
            ladder.onCleanRound("fp");
        }
        assertEquals(TrustLadder.Tier.ESTABLISHED, ladder.tierOf("fp"));

        ladder.sanction(new IdentityStore.SanctionRecord("fp", "BAN", "cheating", "operator",
                now, Long.MAX_VALUE));
        assertEquals(TrustLadder.Tier.SANCTIONED, ladder.tierOf("fp"));
        assertTrue(ladder.isBanned("fp"));
        assertFalse(ladder.canCreateRoom("fp"));
    }

    @Test
    void timeoutSanctionExpires(@TempDir Path dir) {
        TrustLadder ladder = ladder(dir);
        ladder.sanction(new IdentityStore.SanctionRecord("fp", "BAN", "spam", "operator",
                now, now + 10_000));
        assertTrue(ladder.isBanned("fp"));
        now += 10_001;
        assertFalse(ladder.isBanned("fp"));
        assertEquals(TrustLadder.Tier.NEW, ladder.tierOf("fp")); // standing was destroyed
    }

    @Test
    void accrualPacingIgnoresBackToBackRounds(@TempDir Path dir) {
        TrustLadder ladder = ladder(dir);
        ladder.onCleanRound("fp");
        ladder.onCleanRound("fp"); // within the pacing interval: ignored (10-second-window farming)
        now += TrustLadder.Thresholds.defaults().establishedAgeMillis() + 1;
        for (int i = 0; i < 8; i++) {
            now += TrustLadder.ACCRUAL_MIN_INTERVAL_MILLIS + 1;
            ladder.onCleanRound("fp");
        }
        // 1 + 8 counted (the back-to-back second round was ignored) = 9 rounds < 10
        assertEquals(TrustLadder.Tier.NEW, ladder.tierOf("fp"));
        now += TrustLadder.ACCRUAL_MIN_INTERVAL_MILLIS + 1;
        ladder.onCleanRound("fp");
        assertEquals(TrustLadder.Tier.ESTABLISHED, ladder.tierOf("fp"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.master.TestTrustLadder" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.master;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Server-side trust attestation (security spec §4): regenerating a key always works
 * but lands at zero trust. Accrual needs wall-clock age AND participation; demotion
 * is instant and destroys standing — that asymmetry is the whole Sybil defense.
 */
public final class TrustLadder {
    public enum Tier { NEW, ESTABLISHED, TRUSTED, SANCTIONED }

    public static final long NEW_CHAT_MUTE_MILLIS = 5 * 60_000;
    /** Accrual pacing (security spec §5.1): at most one clean round counted per identity per interval. */
    public static final long ACCRUAL_MIN_INTERVAL_MILLIS = 5 * 60_000;

    public record Thresholds(long establishedAgeMillis, int establishedCleanRounds,
                             long trustedAgeMillis, int trustedCleanRounds) {
        public static Thresholds defaults() {
            return new Thresholds(48L * 3600_000, 10, 14L * 24 * 3600_000, 50);
        }
    }

    private final IdentityStore store;
    private final NewIdentityCache cache;
    private final Thresholds thresholds;
    private final LongSupplier clock;
    private final Map<String, Long> lastAccrualMillis = new HashMap<>();

    public TrustLadder(IdentityStore store, NewIdentityCache cache, Thresholds thresholds,
                       LongSupplier clock) {
        this.store = store;
        this.cache = cache;
        this.thresholds = thresholds;
        this.clock = clock;
    }

    public Tier tierOf(String fingerprint) {
        long now = clock.getAsLong();
        if (!store.activeSanctions(fingerprint, now).stream()
                .filter(s -> "BAN".equals(s.type())).toList().isEmpty()) {
            return Tier.SANCTIONED;
        }
        var record = store.find(fingerprint).orElse(null);
        if (record == null) {
            cache.firstSeenOf(fingerprint); // track age in the ephemeral cache only
            return Tier.NEW;
        }
        long age = now - record.firstSeenMillis();
        Tier computed = Tier.NEW;
        if (age >= thresholds.trustedAgeMillis() && record.cleanRounds() >= thresholds.trustedCleanRounds()) {
            computed = Tier.TRUSTED;
        } else if (age >= thresholds.establishedAgeMillis()
                && record.cleanRounds() >= thresholds.establishedCleanRounds()) {
            computed = Tier.ESTABLISHED;
        }
        if (!computed.name().equals(record.tier())) {
            store.setTier(fingerprint, computed.name()); // durable promotion/demotion
        }
        return computed;
    }

    public void onCleanRound(String fingerprint) {
        long now = clock.getAsLong();
        Long previous = lastAccrualMillis.get(fingerprint);
        if (previous != null && now - previous < ACCRUAL_MIN_INTERVAL_MILLIS) {
            return; // accrual pacing — security spec §5.1
        }
        lastAccrualMillis.put(fingerprint, now);
        long firstSeen = store.find(fingerprint)
                .map(IdentityStore.IdentityRecord::firstSeenMillis)
                .orElseGet(() -> cache.firstSeenOf(fingerprint));
        store.persistOnDurableEvent(fingerprint, firstSeen, now);
        store.recordCleanRound(fingerprint, now);
        tierOf(fingerprint); // recompute + persist tier
    }

    public void onDisplayNameClaim(String fingerprint, String displayName) {
        long now = clock.getAsLong();
        long firstSeen = store.find(fingerprint)
                .map(IdentityStore.IdentityRecord::firstSeenMillis)
                .orElseGet(() -> cache.firstSeenOf(fingerprint));
        store.persistOnDurableEvent(fingerprint, firstSeen, now);
        store.setDisplayName(fingerprint, displayName);
    }

    public void sanction(IdentityStore.SanctionRecord record) {
        long now = clock.getAsLong();
        store.persistOnDurableEvent(record.fingerprint(), cache.firstSeenOf(record.fingerprint()), now);
        store.addSanction(record);
        if ("BAN".equals(record.type())) {
            store.setTier(record.fingerprint(), Tier.NEW.name()); // standing destroyed
            // clean rounds also reset: demotion must cost days to rebuild (security spec §4)
            // (implement as an UPDATE identities SET clean_rounds = 0 — add a
            //  store.resetCleanRounds(fingerprint) method to IdentityStore, one UPDATE statement)
        }
    }

    public boolean isBanned(String fingerprint) {
        return tierOf(fingerprint) == Tier.SANCTIONED;
    }

    public boolean canCreateRoom(String fingerprint) {
        Tier tier = tierOf(fingerprint);
        return tier != Tier.NEW && tier != Tier.SANCTIONED;
    }

    public boolean canChatYet(String fingerprint, long memberSinceMillis) {
        if (tierOf(fingerprint) != Tier.NEW) {
            return true;
        }
        return clock.getAsLong() - memberSinceMillis > NEW_CHAT_MUTE_MILLIS;
    }
}
```

Add `void resetCleanRounds(String fingerprint)` to `IdentityStore` + `SqliteIdentityStore` (`UPDATE identities SET clean_rounds = 0 WHERE fingerprint = ?`) and call it in `sanction` for BANs — the timeout-expiry test asserts the identity lands back at NEW.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.master.TestTrustLadder+com.openggf.net.master.TestSqliteIdentityStore" test`
Expected: PASS (6 + 5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/TrustLadder.java src/main/java/com/openggf/net/master/IdentityStore.java src/main/java/com/openggf/net/master/SqliteIdentityStore.java src/test/java/com/openggf/net/master/TestTrustLadder.java
git commit -m "feat(timeattack): trust ladder with age-plus-rounds accrual and sanctions

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 8: MasterConfig + SessionRegistry + RoomHost scale hooks

**Files:**
- Create: `src/main/java/com/openggf/net/master/MasterConfig.java`
- Create: `src/main/java/com/openggf/net/master/SessionRegistry.java`
- Modify: `src/main/java/com/openggf/net/hub/RoomHostConfig.java` (clamp to `MAX_PLAYERS_RELAY` — the constant landed in Task 1; player-hosted DIRECT rooms stay ≤ 8 via the broker gate in Task 9)
- Modify: `src/main/java/com/openggf/net/hub/RoomHost.java` (three optional hooks — see Interfaces)
- Test: `src/test/java/com/openggf/net/master/TestSessionRegistry.java`

**Interfaces:**
- Produces `MasterConfig` — Jackson-YAML-loadable record with defaults (`static MasterConfig load(Path yamlFile)` via the existing `jackson-dataformat-yaml`; `static MasterConfig defaults()`):

```java
public record MasterConfig(
        Integer port,                 // null → 27900; explicit 0 → ephemeral bind (tests)
        String tlsCertPath,           // PEM chain; REQUIRED unless plaintextForTest
        String tlsKeyPath,            // PKCS#8 PEM key
        boolean plaintextForTest,     // default false; true logs a loud warning (tests only)
        String dbPath,                // default "master-identities.db"
        Integer adminPort,            // null → 27901; explicit 0 → ephemeral; binds 127.0.0.1 ONLY
        String adminToken,            // required for admin calls
        long establishedAgeHours,     // default 48   (security spec §4 — config, not protocol)
        int establishedCleanRounds,   // default 10
        long trustedAgeDays,          // default 14
        int trustedCleanRounds,       // default 50
        int identityPowBits,          // default 20 (~1s client compute — security spec §3)
        int attackModePowBits,        // default 22 (join stamp under attack — security spec §8)
        boolean attackMode,           // default false; admin-toggleable at runtime
        int maxRoomsPerIdentity,      // default 2  (fake-room defense — security spec §8)
        int maxRoomsPerIp,            // default 4
        long roomHeartbeatTimeoutSeconds, // default 30 — stale rooms expire (main spec §6.2)
        int browserPageSize,          // default 20 (list responses are capped — security spec §8)
        long identityGcInactiveDays,  // default 30 (security spec §5.1)
        int newIdentityCacheSize,     // default 10_000
        long newIdentityCacheTtlMinutes) // default 60
```

  with a compact constructor applying defaults for zero/null fields — EXCEPT `port` and `adminPort`, which are `Integer` precisely so that `null` (absent in YAML) means "use the default" while an explicit `0` survives and means "bind an ephemeral port" (loopback tests depend on this; a zero-means-default rule would silently rebind tests to 27900/27901). `TrustLadder.Thresholds thresholds()` derived from the four trust values.
- Produces `SessionRegistry(LongSupplier clock, MasterConfig config)` — the room directory (main spec §6.2; fake-room defenses per security spec §8):
  - `record RoomEntry(String roomId, ControlMessage.RoomDescriptor descriptor, String routing, String hostFingerprint, String hostAddress, int directPort, String determinismFingerprint, int playerCount, long lastHeartbeatMillis)`.
  - `RoomEntry create(ControlMessage.RoomDescriptor descriptor, String routing, String hostFingerprint, String hostAddress, int directPort, String determinismFingerprint)` — throws `RoomCreateException(String reason)` (nested checked exception) on: per-identity cap, per-IP cap (`hostAddress` keyed), duplicate room by same identity counted toward the cap. Room ids are `"r-" + monotonic counter` (registry-scoped). **Rooms exist only while their host holds a live authenticated connection** — the broker calls `removeByHostFingerprint` on disconnect (Task 9); heartbeats only refresh direct rooms between polls.
  - `void heartbeat(String roomId, int playerCount)`; `int expireStale()` — removes **DIRECT rooms only** whose lastHeartbeat is older than the config timeout. **RELAY rooms are exempt from heartbeat expiry:** their creator's master connection flips to ROOM mode at `RelayAttach` and can no longer send broker `Heartbeat`s — a relay room's liveness IS the relay room itself. Relay entries are refreshed (playerCount + lastHeartbeat) by `RelayRoomManager`'s periodic count publish (Task 10, marshalled onto the broker loop into `heartbeat(...)`) and removed via `hostLeft`/owner-disconnect teardown. `void remove(String roomId)`; `List<RoomEntry> removeByHostFingerprint(String hostFingerprint)`.
  - `List<RoomEntry> list(String gameFilterOrNull)` — newest first; `Optional<RoomEntry> find(String roomId)`; `int totalPages(String gameFilterOrNull)` using `config.browserPageSize()` and returning `0` when the filtered list is empty. The broker slices `list(...)` for the requested page and sends this `totalPages` in `RoomListResult`.
- Produces — `RoomHost` additive hooks (defaults preserve phase-2 behavior byte-for-byte; set via a new `RoomHostHooks` parameter object to avoid constructor explosion):

```java
public record RoomHostHooks(
        boolean relevanceFiltering,                                   // → GhostHub 4-arg ctor
        ChatGate chatGate,                                            // null = everyone may chat
        RoundOutcomeListener roundOutcomeListener,                    // null = no accrual
        String roundOwnerFingerprint,                                 // null = server identity owns round config
        java.util.function.Predicate<String> isNewPlayer) {           // null = nobody flagged; feeds the NEW badge (security spec §4)
    public interface ChatGate { boolean mayChat(String fingerprint, long memberSinceMillis); }
    public interface RoundOutcomeListener { void onRoundComplete(String fingerprint, boolean clean); }
    public static RoomHostHooks none() { return new RoomHostHooks(false, null, null, null, null); }
}
```

  - New constructor `RoomHost(RoomHostConfig config, PlayerIdentity hostIdentity, LongSupplier wallClockMillis, TrackValidationProfileSource profiles, RoomHostHooks hooks)`; the phase-2 4-arg constructor delegates with `RoomHostHooks.none()`.
  - `roundOwnerFingerprint` fixes relay authority without changing the phase-2 player-host path: when null, `RoundConfigure` remains host-identity-only exactly as phase 2; when set (relay rooms), `RoundConfigure` is authorized for that member fingerprint while `hostIdentity` remains the room server identity used for the signed `Welcome.serverId`.
  - `void expectFingerprint(HubConnection connection, String fingerprint)` — optional pre-admission binding used only by the master relay path. If present for a connection, the post-signature `HostHandshake.Admit.fingerprint()` MUST match or the room sends `JoinRejected("identity mismatch")` and closes. Remove the expectation on successful admit, rejection, admission timeout, or `onDisconnected`, so abandoned relay attaches do not leak map entries. Player-host/direct rooms never call this method.
  - `chatGate` consulted in the Chat dispatch (member records its admission time — add `long memberSinceMillis` to the Member state); gated messages are silently dropped (NEW-tier read-only window — security spec §4/§9).
  - `isNewPlayer` feeds the NEW badge (security spec §4 tier effects): `ControlMessage.PlayerInfo` gains a final `boolean newPlayer` component (append it; update phase-2 construction sites and tests mechanically — always `false` on player-hosted rooms), populated from the predicate when `RoomHost` builds `RoomState` broadcasts. Clients render the badge and disambiguate duplicate display names with a fingerprint-suffix tag (Task 16 — security spec §9: "disambiguate duplicate names by badge/fingerprint suffix"; `PlayerInfo` already carries the fingerprint).
  - `roundOutcomeListener` fired once per admitted member when the round transitions RUNNING→ROUND_END: `clean` = the member finished at least one attempt this round AND accumulated zero hub violations during it (track a per-member violation counter fed from the hub recorder + a finished-this-round flag set in the AttemptFinish dispatch; both reset at RoundStart). Trust accrues ONLY from master-observed relay rounds — player-hosted rooms pass `none()` and never accrue (v1-conservative: host-reported outcomes would be forgeable).
  - `RoomHostConfig` clamp change: `maxPlayers` now clamps to `1..Protocol.MAX_PLAYERS_RELAY` (256). Player-host UI keeps passing 8; the phase-2 test asserting the clamp updates its expectation only if it pinned the old bound (check `TestRoomHost`).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.master;

import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestSessionRegistry {
    private long now = 1_000_000;
    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry(() -> now, MasterConfig.defaults());
    }

    private static ControlMessage.RoomDescriptor desc(String name) {
        return new ControlMessage.RoomDescriptor(name, "s3k", 0, 0, "OPEN", null, 8, false);
    }

    @Test
    void createListFindAndFilter() throws Exception {
        SessionRegistry.RoomEntry a = registry.create(desc("A"), "DIRECT", "fp-a", "1.1.1.1", 27888, "0.6:cafe");
        registry.create(new ControlMessage.RoomDescriptor("B", "s2", 0, 0, "OPEN", null, 8, false),
                "RELAY", "fp-b", "2.2.2.2", 0, "0.6:cafe");
        assertTrue(a.roomId().startsWith("r-"));
        assertEquals(2, registry.list(null).size());
        assertEquals(1, registry.list("s3k").size());
        assertEquals(1, registry.totalPages(null));
        assertTrue(registry.find(a.roomId()).isPresent());
    }

    @Test
    void perIdentityAndPerIpCapsReject() throws Exception {
        MasterConfig config = MasterConfig.defaults(); // 2 per identity, 4 per IP
        registry = new SessionRegistry(() -> now, config);
        registry.create(desc("1"), "DIRECT", "fp", "1.1.1.1", 1, "f");
        registry.create(desc("2"), "DIRECT", "fp", "1.1.1.1", 2, "f");
        assertThrows(SessionRegistry.RoomCreateException.class,
                () -> registry.create(desc("3"), "DIRECT", "fp", "1.1.1.1", 3, "f"));

        registry.create(desc("4"), "DIRECT", "fp2", "9.9.9.9", 4, "f");
        registry.create(desc("5"), "DIRECT", "fp3", "9.9.9.9", 5, "f");
        registry.create(desc("6"), "DIRECT", "fp4", "9.9.9.9", 6, "f");
        registry.create(desc("7"), "DIRECT", "fp5", "9.9.9.9", 7, "f");
        assertThrows(SessionRegistry.RoomCreateException.class,
                () -> registry.create(desc("8"), "DIRECT", "fp6", "9.9.9.9", 8, "f"));
    }

    @Test
    void staleRoomsExpireHeartbeatKeepsAlive() throws Exception {
        SessionRegistry.RoomEntry room = registry.create(desc("A"), "DIRECT", "fp", "1.1.1.1", 1, "f");
        now += MasterConfig.defaults().roomHeartbeatTimeoutSeconds() * 1000 - 1;
        registry.heartbeat(room.roomId(), 3);
        assertEquals(0, registry.expireStale());
        assertEquals(3, registry.find(room.roomId()).orElseThrow().playerCount());

        now += MasterConfig.defaults().roomHeartbeatTimeoutSeconds() * 1000 + 1;
        assertEquals(1, registry.expireStale());
        assertTrue(registry.find(room.roomId()).isEmpty());
    }

    @Test
    void hostDisconnectRemovesAllTheirRooms() throws Exception {
        registry.create(desc("A"), "DIRECT", "fp", "1.1.1.1", 1, "f");
        registry.create(desc("B"), "DIRECT", "fp", "1.1.1.1", 2, "f");
        assertEquals(2, registry.removeByHostFingerprint("fp").size());
        assertTrue(registry.list(null).isEmpty()); // rooms exist only while the host is connected
    }

    @Test
    void relayRoomsAreExemptFromHeartbeatExpiry() throws Exception {
        SessionRegistry.RoomEntry relay = registry.create(desc("R"), "RELAY", "fp-r", "3.3.3.3", 0, "f");
        now += MasterConfig.defaults().roomHeartbeatTimeoutSeconds() * 1000 + 1;
        assertEquals(0, registry.expireStale());
        assertTrue(registry.find(relay.roomId()).isPresent()); // relay liveness = the room itself
    }

    @Test
    void configLoadsFromYamlWithDefaults(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path yaml = dir.resolve("master.yaml");
        java.nio.file.Files.writeString(yaml, """
                port: 12345
                attackMode: true
                identityPowBits: 8
                """);
        MasterConfig config = MasterConfig.load(yaml);
        assertEquals(12345, config.port());
        assertTrue(config.attackMode());
        assertEquals(8, config.identityPowBits());
        assertEquals(20, MasterConfig.defaults().identityPowBits());
        assertEquals(2, config.maxRoomsPerIdentity()); // unset → default
        assertEquals(48, config.establishedAgeHours());
        assertEquals(27900, MasterConfig.defaults().port()); // null → default...
        assertEquals(0, new MasterConfig(0, null, null, true, null, 0, "t",
                0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0, 0).port()); // ...but explicit 0 = ephemeral
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.master.TestSessionRegistry" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`MasterConfig` — record as specified; compact constructor replaces `0`/`null`/`false-where-defaulted` with defaults, EXCEPT `port`/`adminPort` (`Integer`: only `null` defaults — explicit `0` stays for ephemeral test binds; only `attackMode`/`plaintextForTest` stay literal booleans); `load` uses `new ObjectMapper(new YAMLFactory()).readValue(Files.readAllBytes(yamlFile), MasterConfig.class)` with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` disabled; `thresholds()` converts hours/days to millis.

`SessionRegistry`:

```java
package com.openggf.net.master;

import com.openggf.net.protocol.ControlMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Room directory (main spec §6.2). Fake-room defenses (security spec §8): rooms
 * exist only while the host's authenticated connection lives, per-identity and
 * per-IP creation caps, stale expiry. Broker-loop-confined (no locking).
 */
public final class SessionRegistry {
    public static final class RoomCreateException extends Exception {
        public RoomCreateException(String reason) {
            super(reason);
        }
    }

    public record RoomEntry(String roomId, ControlMessage.RoomDescriptor descriptor, String routing,
                            String hostFingerprint, String hostAddress, int directPort,
                            String determinismFingerprint, int playerCount, long lastHeartbeatMillis) {
    }

    private final LongSupplier clock;
    private final MasterConfig config;
    private final Map<String, RoomEntry> rooms = new LinkedHashMap<>();
    private long counter;

    public SessionRegistry(LongSupplier clock, MasterConfig config) {
        this.clock = clock;
        this.config = config;
    }

    public RoomEntry create(ControlMessage.RoomDescriptor descriptor, String routing,
                            String hostFingerprint, String hostAddress, int directPort,
                            String determinismFingerprint) throws RoomCreateException {
        long byIdentity = rooms.values().stream()
                .filter(r -> r.hostFingerprint().equals(hostFingerprint)).count();
        if (byIdentity >= config.maxRoomsPerIdentity()) {
            throw new RoomCreateException("room cap per identity reached");
        }
        long byIp = rooms.values().stream()
                .filter(r -> r.hostAddress().equals(hostAddress)).count();
        if (byIp >= config.maxRoomsPerIp()) {
            throw new RoomCreateException("room cap per address reached");
        }
        RoomEntry entry = new RoomEntry("r-" + (++counter), descriptor, routing, hostFingerprint,
                hostAddress, directPort, determinismFingerprint, 0, clock.getAsLong());
        rooms.put(entry.roomId(), entry);
        return entry;
    }

    public void heartbeat(String roomId, int playerCount) {
        RoomEntry entry = rooms.get(roomId);
        if (entry != null) {
            rooms.put(roomId, new RoomEntry(entry.roomId(), entry.descriptor(), entry.routing(),
                    entry.hostFingerprint(), entry.hostAddress(), entry.directPort(),
                    entry.determinismFingerprint(), playerCount, clock.getAsLong()));
        }
    }

    public int expireStale() {
        long cutoff = clock.getAsLong() - config.roomHeartbeatTimeoutSeconds() * 1000;
        int before = rooms.size();
        // DIRECT rooms only: relay rooms cannot heartbeat (their owner's connection is in
        // ROOM mode) — their liveness is the relay room itself (RelayRoomManager refreshes them).
        rooms.values().removeIf(r -> "DIRECT".equals(r.routing()) && r.lastHeartbeatMillis() < cutoff);
        return before - rooms.size();
    }

    public void remove(String roomId) {
        rooms.remove(roomId);
    }

    public List<RoomEntry> removeByHostFingerprint(String hostFingerprint) {
        List<RoomEntry> removed = new ArrayList<>();
        rooms.values().removeIf(r -> {
            if (r.hostFingerprint().equals(hostFingerprint)) {
                removed.add(r);
                return true;
            }
            return false;
        });
        return removed;
    }

    public Optional<RoomEntry> find(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    public List<RoomEntry> list(String gameFilterOrNull) {
        return rooms.values().stream()
                .filter(r -> gameFilterOrNull == null || r.descriptor().gameId().equals(gameFilterOrNull))
                .sorted(Comparator.comparing(RoomEntry::roomId).reversed())
                .toList();
    }

    public int totalPages(String gameFilterOrNull) {
        int count = list(gameFilterOrNull).size();
        if (count == 0) {
            return 0;
        }
        return (count + config.browserPageSize() - 1) / config.browserPageSize();
    }
}
```

`RoomHostConfig` compact constructor: `maxPlayers = Math.min(Math.max(maxPlayers, 1), Protocol.MAX_PLAYERS_RELAY);` (the constant landed in Task 1; the ≤ 8 DIRECT-room limit is enforced at the broker — Task 9 — not here, because relay rooms legitimately reuse this record at 256). `RoomHost`: add `RoomHostHooks` (new file `src/main/java/com/openggf/net/hub/RoomHostHooks.java` with the record above), 5-arg constructor storing hooks and passing `hooks.relevanceFiltering()` to the `GhostHub` 4-arg constructor; add a package-private/ordinary `Map<HubConnection, String> expectedFingerprints` plus public `expectFingerprint(...)` and enforce it immediately after `HostHandshake.Admit` before assigning a slot; `Member` gains `long memberSinceMillis` (set at admit) and `int violationsThisRound` / `boolean finishedThisRound` (reset when a `RoundStart` broadcast fires — hook into `startRound` success); Chat dispatch prepends `if (hooks.chatGate() != null && !hooks.chatGate().mayChat(member.fingerprint, member.memberSinceMillis)) return;`; `RoundConfigure` authorization checks `String owner = hooks.roundOwnerFingerprint() != null ? hooks.roundOwnerFingerprint() : hostIdentity.fingerprint(); if (member.fingerprint.equals(owner)) ...`; wire the hub violation recorder wrapper to also bump the member's `violationsThisRound`; on the round engine's RUNNING→ROUND_END transition (observe via `round.phase()` change across `tick()` — cache previous phase), fire `roundOutcomeListener.onRoundComplete(member.fingerprint, member.finishedThisRound && member.violationsThisRound == 0)` for every admitted member.

- [ ] **Step 4: Run new + phase-2 room tests**

Run: `mvn "-Dtest=com.openggf.net.master.TestSessionRegistry+com.openggf.net.hub.TestRoomHost" test`
Expected: PASS (5 + phase-2 suite; fix `TestRoomHost` only if it pinned the old 8-player clamp).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/MasterConfig.java src/main/java/com/openggf/net/master/SessionRegistry.java src/main/java/com/openggf/net/protocol/Protocol.java src/main/java/com/openggf/net/hub/RoomHostConfig.java src/main/java/com/openggf/net/hub/RoomHost.java src/main/java/com/openggf/net/hub/RoomHostHooks.java src/test/java/com/openggf/net/master/TestSessionRegistry.java
git commit -m "feat(timeattack): master config, session registry, and RoomHost scale hooks

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 9: RoomBroker — master-side connection state machine

**Files:**
- Create: `src/main/java/com/openggf/net/master/RoomBroker.java`
- Modify: `src/main/java/com/openggf/net/hub/HostHandshake.java` (null `requiredDeterminismFingerprint` = accept any — the ROM-free master cannot check it; rooms gate at JOIN instead)
- Test: `src/test/java/com/openggf/net/master/TestRoomBroker.java`

**Interfaces:**
- Consumes: Tasks 5–8 + `HostHandshake`/`SessionTokenIssuer`/`HubConnection`/`ControlCodec`.
- Produces `RoomBroker(PlayerIdentity masterIdentity, MasterConfig config, SessionRegistry registry, IdentityStore store, TrustLadder ladder, NewIdentityCache cache, LongSupplier clock, RelayRoomDirectory relays, DirectTunnelDirectory tunnels)` — one instance, broker-loop-confined. The explicit `IdentityStore` dependency is required for the identity-creation PoW gate: `TrustLadder.tierOf()` intentionally collapses both absent identities and durable NEW rows to `NEW`, but the broker must challenge only row-absent fingerprints. `RelayRoomDirectory` is a small interface implemented by Task 10's manager: `String createRelayRoom(SessionRegistry.RoomEntry entry); void noteGuestTier(String fingerprint, boolean isNew); boolean attach(HubConnection connection, String roomId, String fingerprint, String displayName); void hostLeft(String roomId);` (`attach` returns false = room gone; `noteGuestTier` feeds the room's chat gate on the broker loop before handoff so the gate never touches SQLite cross-thread — see Task 10). `DirectTunnelDirectory` is implemented by Task 11's `GuestTunnelRouter`: `void registerHost(String roomId, HubConnection hostConnection); OptionalInt openGuest(String roomId, HubConnection guestConnection); void unregisterHost(String roomId);`.
  - Transport hooks mirroring `RoomHost`: `onConnected(HubConnection)`, `onText(HubConnection, String)`, `onDisconnected(HubConnection)`, `tick()` (admission timeouts + `registry.expireStale()` + identity GC once per hour). `onText` decodes with `ControlCodec.decode(text, Protocol.MAX_MASTER_FRAME_BYTES)` — the master connection carries tunnel-wrapped envelopes that legitimately exceed the room cap (Task 1); every other decode site in the codebase keeps the single-arg tight-cap form.
  - Handshake: reuses `HostHandshake` with `requiredDeterminismFingerprint = null` (accept any — gate at join). After `Admit`:
    - banned → `JoinRejected("account sanctioned")` + close (security spec §4: rejected at handshake).
    - **Identity creation stamp** (security spec §3): if the fingerprint has no store row AND is not stamped this session, send `PowChallenge("IDENTITY", "", config.identityPowBits())`; the connection is PENDING_POW until a valid `PowSolution("IDENTITY", nonce)` verifies against the presented pubkey (`ProofOfWork.verify(publicKeyEncoded, nonce, bits)`). Failure → violation counter → close on 3.
    - **Attack mode** (security spec §8): when `config.attackMode()`, ALSO send `PowChallenge("JOIN", randomPrefixBase64, config.attackModePowBits())` — single-use payload = the random prefix bytes; must be solved before any broker command is accepted. (v1 simplification, documented: difficulty is the fixed `attackModePowBits` config value; §8's "difficulty scales with load" is operator-driven — raise the config and toggle attack mode via the admin endpoint under attack.)
    - Then issue an identity-bound session token (`SessionTokenIssuer.issue()`, recorded token→fingerprint; every later envelope must match BOTH the token and the connection's admitted fingerprint — the phase-2 field carries upgraded semantics with no protocol break, security spec §7.3), and send `JoinAccepted(token, -1 /* no room slot at the master */, null, null)`.
  - Commands (post-admission, token-checked): `RoomCreate` → routing must be `"DIRECT"` or `"RELAY"` (else strike); **DIRECT rooms additionally require `descriptor.maxPlayers() <= Protocol.MAX_PLAYERS_DIRECT` (8) → else `RoomCreateRejected("direct rooms are capped at 8 players - use relay routing")`** (main spec §2/§4.4: larger rooms are ALWAYS relay-routed; without this gate the Task-8 clamp raise would let a player host advertise a 256-player direct room with no relevance filtering) + `ladder.canCreateRoom` gate (NEW cannot create — security spec §8) + `registry.create` (host address = connection remoteHost) → `RELAY` routing also `relays.createRelayRoom(entry)`, `DIRECT` routing also `tunnels.registerHost(entry.roomId(), member.connection)` so later direct-fallback guests have a host sink → reply `RoomCreated(roomId)` / `RoomCreateRejected(reason)`; `RoomListRequest` → paginate `registry.list(filter)` by `config.browserPageSize()` and use `registry.totalPages(filter)` → `RoomListResult` (list responses are the largest reply the master sends; page size caps them — security spec §8) with a per-connection list rate limit (1 request / 2 s, silently dropped beyond); `RoomJoinRequest` → per-identity join rate limit first (max 5 requests / 10 s / fingerprint → `RoomJoinRejected("join rate limited")` — security spec §8 join floods), find room, **determinism fingerprint gate** (client's Hello fingerprint must equal the room's advertised one → else `RoomJoinRejected("determinism fingerprint mismatch")`), NEW-tier pressure rule (room ≥ 80% full and tier == NEW → `RoomJoinRejected("room under pressure")` — the v1 simplification of §8's join queue, documented), then record the grant in the member's `joinGrantedRooms` set and `DIRECT` → `RoomJoinResult` with host address/port/serverId; `RELAY` → `RoomJoinResult(routing="RELAY")` and the client follows with `RelayAttach(roomId)`. `RelayAttach` **requires `joinGrantedRooms.contains(roomId)`** → else `RoomJoinRejected("join not granted")` (attaching without a prior `RoomJoinRequest` would skip the fingerprint and pressure gates), then dispatches by room routing: RELAY rooms call `relays.noteGuestTier(...)` then `relays.attach(...)` and flip the channel to ROOM; DIRECT rooms call `tunnels.openGuest(roomId, member.connection)` and flip the channel to TUNNEL_GUEST with the returned guestId, or send `RoomJoinRejected("relay unavailable")` if the host sink is gone. `Heartbeat` → `registry.heartbeat` (only for rooms owned by this fingerprint); `RoomLeave` → registry removal if owner + `tunnels.unregisterHost(roomId)` for DIRECT rooms.
  - `onDisconnected` → `registry.removeByHostFingerprint(fingerprint)` (rooms die with the host connection), relay-room teardown for owned relay rooms via `relays.hostLeft(roomId)`, and DIRECT fallback teardown via `tunnels.unregisterHost(roomId)`.
  - Chat does NOT exist at the master browser level (chat is per-room) — a `Chat` message here is a violation.

- [ ] **Step 1: Write the failing test** (fake `HubConnection`s + fake `RelayRoomDirectory`/`DirectTunnelDirectory`; drives full handshakes with real `PlayerIdentity`s exactly like phase-2's `TestRoomHost` — reuse its `admit` helper shape)

```java
package com.openggf.net.master;

import com.openggf.net.client.ClientHandshake;
import com.openggf.net.hub.HubConnection;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.identity.ProofOfWork;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestRoomBroker {
    static final class FakeConnection implements HubConnection {
        final List<String> text = new ArrayList<>();
        String closedReason;

        @Override public void sendText(String t) { text.add(t); }
        @Override public void sendBinary(byte[] d) { }
        @Override public void close(String reason) { closedReason = reason; }
        @Override public String remoteHost() { return "10.0.0.1"; }
    }

    static final class FakeRelays implements RoomBroker.RelayRoomDirectory {
        final List<String> created = new ArrayList<>();
        final List<String> attached = new ArrayList<>();

        @Override public String createRelayRoom(SessionRegistry.RoomEntry entry) {
            created.add(entry.roomId());
            return entry.roomId();
        }

        @Override public void noteGuestTier(String fingerprint, boolean isNew) { }

        @Override public boolean attach(HubConnection connection, String roomId, String fingerprint,
                                        String displayName) {
            attached.add(roomId + ":" + fingerprint);
            return true;
        }

        @Override public void hostLeft(String roomId) { }
    }

    static final class FakeTunnels implements RoomBroker.DirectTunnelDirectory {
        final List<String> registered = new ArrayList<>();
        final List<String> opened = new ArrayList<>();

        @Override public void registerHost(String roomId, HubConnection hostConnection) {
            registered.add(roomId);
        }

        @Override public java.util.OptionalInt openGuest(String roomId, HubConnection guestConnection) {
            opened.add(roomId);
            return java.util.OptionalInt.of(7);
        }

        @Override public void unregisterHost(String roomId) {
            registered.remove(roomId);
        }
    }

    @TempDir
    Path dir;
    private long now = 1_000_000;
    private RoomBroker broker;
    private FakeRelays relays;
    private SqliteIdentityStore store;
    private TrustLadder ladder;
    private FakeTunnels tunnels;

    @BeforeEach
    void setUp() throws Exception {
        MasterConfig config = MasterConfig.defaults();
        store = new SqliteIdentityStore(dir.resolve("ids.db"));
        NewIdentityCache cache = new NewIdentityCache(1000, 3_600_000, () -> now);
        ladder = new TrustLadder(store, cache, config.thresholds(), () -> now);
        relays = new FakeRelays();
        tunnels = new FakeTunnels();
        SessionRegistry registry = new SessionRegistry(() -> now, config);
        broker = new RoomBroker(PlayerIdentity.loadOrCreate(dir.resolve("master")), config,
                registry, store, ladder, cache, () -> now, relays, tunnels);
    }

    private static ControlMessage lastMessage(FakeConnection c) {
        return ControlCodec.decode(c.text.get(c.text.size() - 1)).message();
    }

    /** Handshake incl. the IDENTITY PoW demanded for unknown identities. */
    private String admit(FakeConnection conn, Path idDir, String name) throws Exception {
        PlayerIdentity identity = PlayerIdentity.loadOrCreate(idDir);
        ClientHandshake handshake = new ClientHandshake(identity, name, "0.6:cafe");
        broker.onConnected(conn);
        broker.onText(conn, ControlCodec.encode(null, handshake.hello()));
        broker.onText(conn, ControlCodec.encode(null,
                handshake.onWelcome((ControlMessage.Welcome) lastMessage(conn))));
        if (lastMessage(conn) instanceof ControlMessage.PowChallenge challenge
                && "IDENTITY".equals(challenge.kind())) {
            long nonce = identity.creationPowNonce(challenge.difficultyBits());
            broker.onText(conn, ControlCodec.encode(null,
                    new ControlMessage.PowSolution("IDENTITY", nonce)));
        }
        return ((ControlMessage.JoinAccepted) lastMessage(conn)).sessionToken();
    }

    /** Promote a fingerprint past NEW so it may create rooms (clock advances satisfy accrual pacing). */
    private void establish(String fingerprint) {
        ladder.onCleanRound(fingerprint);
        now += TrustLadder.Thresholds.defaults().establishedAgeMillis() + 1;
        for (int i = 0; i < 9; i++) {
            now += TrustLadder.ACCRUAL_MIN_INTERVAL_MILLIS + 1;
            ladder.onCleanRound(fingerprint);
        }
    }

    @Test
    void unknownIdentityMustSolveCreationPow(@TempDir Path idDir) throws Exception {
        // Use tiny difficulty for tests: defaults() has identityPowBits 20 — rebuild broker with 8.
        // (Add a MasterConfig.withIdentityPowBits(int) wither, or construct via the canonical ctor.)
        FakeConnection conn = new FakeConnection();
        String token = admit(conn, idDir, "A");
        assertNotNull(token);
        assertTrue(conn.text.stream().map(t -> ControlCodec.decode(t).message())
                .anyMatch(m -> m instanceof ControlMessage.PowChallenge));
    }

    @Test
    void newTierCannotCreateEstablishedCan(@TempDir Path idDir) throws Exception {
        FakeConnection conn = new FakeConnection();
        String token = admit(conn, idDir, "A");
        ControlMessage.RoomDescriptor desc =
                new ControlMessage.RoomDescriptor("Room", "s3k", 0, 0, "OPEN", null, 8, false);
        broker.onText(conn, ControlCodec.encode(token,
                new ControlMessage.RoomCreate(desc, "DIRECT", 27888, "0.6:cafe")));
        assertInstanceOf(ControlMessage.RoomCreateRejected.class, lastMessage(conn));

        establish(PlayerIdentity.loadOrCreate(idDir).fingerprint());
        broker.onText(conn, ControlCodec.encode(token,
                new ControlMessage.RoomCreate(desc, "DIRECT", 27888, "0.6:cafe")));
        assertInstanceOf(ControlMessage.RoomCreated.class, lastMessage(conn));
        assertEquals(1, tunnels.registered.size()); // DIRECT rooms register host sink for fallback
    }

    @Test
    void relayCreateSpinsRelayRoomAndJoinAttaches(@TempDir Path hostDir, @TempDir Path guestDir)
            throws Exception {
        FakeConnection host = new FakeConnection();
        String hostToken = admit(host, hostDir, "HOST");
        establish(PlayerIdentity.loadOrCreate(hostDir).fingerprint());
        ControlMessage.RoomDescriptor desc =
                new ControlMessage.RoomDescriptor("Big", "s3k", 0, 0, "OPEN", null, 256, false);
        broker.onText(host, ControlCodec.encode(hostToken,
                new ControlMessage.RoomCreate(desc, "RELAY", 0, "0.6:cafe")));
        String roomId = ((ControlMessage.RoomCreated) lastMessage(host)).roomId();
        assertEquals(List.of(roomId), relays.created);

        FakeConnection guest = new FakeConnection();
        String guestToken = admit(guest, guestDir, "GUEST");
        broker.onText(guest, ControlCodec.encode(guestToken, new ControlMessage.RoomJoinRequest(roomId)));
        ControlMessage.RoomJoinResult result = (ControlMessage.RoomJoinResult) lastMessage(guest);
        assertEquals("RELAY", result.routing());
        broker.onText(guest, ControlCodec.encode(guestToken, new ControlMessage.RelayAttach(roomId)));
        assertEquals(1, relays.attached.size());
    }

    @Test
    void joinGatesOnDeterminismFingerprint(@TempDir Path hostDir, @TempDir Path guestDir) throws Exception {
        FakeConnection host = new FakeConnection();
        String hostToken = admit(host, hostDir, "HOST");
        establish(PlayerIdentity.loadOrCreate(hostDir).fingerprint());
        broker.onText(host, ControlCodec.encode(hostToken, new ControlMessage.RoomCreate(
                new ControlMessage.RoomDescriptor("R", "s3k", 0, 0, "OPEN", null, 8, false),
                "DIRECT", 27888, "0.6:AAAA")));
        String roomId = ((ControlMessage.RoomCreated) lastMessage(host)).roomId();

        FakeConnection guest = new FakeConnection(); // guest's Hello said 0.6:cafe
        String guestToken = admit(guest, guestDir, "GUEST");
        broker.onText(guest, ControlCodec.encode(guestToken, new ControlMessage.RoomJoinRequest(roomId)));
        assertInstanceOf(ControlMessage.RoomJoinRejected.class, lastMessage(guest));
    }

    @Test
    void bannedIdentityRejectedAtHandshake(@TempDir Path idDir) throws Exception {
        PlayerIdentity banned = PlayerIdentity.loadOrCreate(idDir);
        ladder.sanction(new IdentityStore.SanctionRecord(banned.fingerprint(), "BAN", "cheat",
                "operator", now, Long.MAX_VALUE));
        FakeConnection conn = new FakeConnection();
        assertThrows(Exception.class, () -> admit(conn, idDir, "BAD")); // no JoinAccepted arrives
        assertTrue(conn.text.stream().map(t -> ControlCodec.decode(t).message())
                .anyMatch(m -> m instanceof ControlMessage.JoinRejected));
        assertNotNull(conn.closedReason);
    }

    @Test
    void hostDisconnectRemovesTheirRooms(@TempDir Path hostDir) throws Exception {
        FakeConnection host = new FakeConnection();
        String token = admit(host, hostDir, "HOST");
        establish(PlayerIdentity.loadOrCreate(hostDir).fingerprint());
        broker.onText(host, ControlCodec.encode(token, new ControlMessage.RoomCreate(
                new ControlMessage.RoomDescriptor("R", "s3k", 0, 0, "OPEN", null, 8, false),
                "DIRECT", 27888, "0.6:cafe")));
        broker.onDisconnected(host);
        assertTrue(tunnels.registered.isEmpty());

        FakeConnection browser = new FakeConnection();
        String browserToken = admit(browser, hostDir.resolve("other"), "B");
        broker.onText(browser, ControlCodec.encode(browserToken,
                new ControlMessage.RoomListRequest(null, 0)));
        assertTrue(((ControlMessage.RoomListResult) lastMessage(browser)).rooms().isEmpty());
    }

    @Test
    void directRoomsAreCappedAtEightPlayers(@TempDir Path idDir) throws Exception {
        FakeConnection conn = new FakeConnection();
        String token = admit(conn, idDir, "HOST");
        establish(PlayerIdentity.loadOrCreate(idDir).fingerprint());
        broker.onText(conn, ControlCodec.encode(token, new ControlMessage.RoomCreate(
                new ControlMessage.RoomDescriptor("Huge", "s3k", 0, 0, "OPEN", null, 64, false),
                "DIRECT", 27888, "0.6:cafe")));
        assertInstanceOf(ControlMessage.RoomCreateRejected.class, lastMessage(conn)); // main spec §2: > 8 ⇒ relay only
        broker.onText(conn, ControlCodec.encode(token, new ControlMessage.RoomCreate(
                new ControlMessage.RoomDescriptor("Huge", "s3k", 0, 0, "OPEN", null, 64, false),
                "RELAY", 0, "0.6:cafe")));
        assertInstanceOf(ControlMessage.RoomCreated.class, lastMessage(conn));
    }

    @Test
    void relayAttachWithoutGrantedJoinIsRejected(@TempDir Path hostDir, @TempDir Path guestDir)
            throws Exception {
        FakeConnection host = new FakeConnection();
        String hostToken = admit(host, hostDir, "HOST");
        establish(PlayerIdentity.loadOrCreate(hostDir).fingerprint());
        broker.onText(host, ControlCodec.encode(hostToken, new ControlMessage.RoomCreate(
                new ControlMessage.RoomDescriptor("Big", "s3k", 0, 0, "OPEN", null, 64, false),
                "RELAY", 0, "0.6:cafe")));
        String roomId = ((ControlMessage.RoomCreated) lastMessage(host)).roomId();

        FakeConnection guest = new FakeConnection();
        String guestToken = admit(guest, guestDir, "GUEST");
        broker.onText(guest, ControlCodec.encode(guestToken, new ControlMessage.RelayAttach(roomId)));
        assertInstanceOf(ControlMessage.RoomJoinRejected.class, lastMessage(guest)); // skipped the join gates
        assertTrue(relays.attached.isEmpty());
    }
}
```

Test-speed note: `MasterConfig.defaults()` has `identityPowBits = 20` (~1 s solve) — acceptable once per identity per test run, but to keep the suite fast construct the broker's config via the canonical constructor with `identityPowBits = 8` in `setUp()` (write the full 21-argument call once in the test; that is also live documentation of the config surface).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.master.TestRoomBroker" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement RoomBroker**

Structure mirrors phase-2's `RoomHost` (Member map keyed by connection, admission timeout sweep in `tick()`, token strikes). Per-connection states: `EXPECT_HELLO → EXPECT_PROOF → PENDING_IDENTITY_POW? → PENDING_JOIN_POW? → ADMITTED`. Key fragments:

```java
    private void afterAdmit(Member member, HostHandshake.Admit admit) {
        member.fingerprint = admit.fingerprint();
        member.displayName = admit.displayName();
        member.publicKeyEncoded = admit.publicKeyEncoded();
        if (ladder.isBanned(member.fingerprint)) {
            reject(member, "account sanctioned"); // security spec §4: rejected at handshake
            return;
        }
        if (store.find(member.fingerprint).isEmpty()) { // absent durable row, not just NEW tier
            member.state = State.PENDING_IDENTITY_POW;
            member.connection.sendText(ControlCodec.encode(null,
                    new ControlMessage.PowChallenge("IDENTITY", "", config.identityPowBits())));
            return;
        }
        maybeJoinPowOrAdmit(member);
    }

    private void onPowSolution(Member member, ControlMessage.PowSolution solution) {
        boolean valid = switch (solution.kind()) {
            case "IDENTITY" -> member.state == State.PENDING_IDENTITY_POW
                    && ProofOfWork.verify(member.publicKeyEncoded, solution.nonce(), config.identityPowBits());
            case "JOIN" -> member.state == State.PENDING_JOIN_POW
                    && ProofOfWork.verify(member.joinChallenge, solution.nonce(), config.attackModePowBits());
            default -> false;
        };
        if (!valid) {
            strike(member, "invalid proof of work");
            return;
        }
        if (member.state == State.PENDING_IDENTITY_POW) {
            maybeJoinPowOrAdmit(member);
        } else {
            finishAdmission(member);
        }
    }

    private void maybeJoinPowOrAdmit(Member member) {
        if (config.attackMode()) {
            member.joinChallenge = new byte[16];
            random.nextBytes(member.joinChallenge);
            member.state = State.PENDING_JOIN_POW;
            member.connection.sendText(ControlCodec.encode(null, new ControlMessage.PowChallenge(
                    "JOIN", Base64.getEncoder().encodeToString(member.joinChallenge),
                    config.attackModePowBits())));
            return;
        }
        finishAdmission(member);
    }

    private void finishAdmission(Member member) {
        member.token = tokens.issue();
        tokenFingerprints.put(member.token, member.fingerprint); // identity-bound (security §7.3)
        member.state = State.ADMITTED;
        member.connection.sendText(ControlCodec.encode(null,
                new ControlMessage.JoinAccepted(member.token, -1, null, null)));
    }
```

Dispatch for admitted members implements the command list from Interfaces verbatim (RoomCreate/RoomListRequest with rate limit + paging/RoomJoinRequest with fingerprint + pressure gates/RelayAttach split by RELAY vs DIRECT fallback/Heartbeat/RoomLeave; anything else → strike). `RelayRoomDirectory` and `DirectTunnelDirectory` are nested interfaces on `RoomBroker` exactly as consumed above. `attackMode` is read from a mutable `AtomicBoolean` seeded from config so the admin endpoint (Task 12) can flip it at runtime — expose `void setAttackMode(boolean)`.

`HostHandshake` change: `if (requiredDeterminismFingerprint != null && !requiredDeterminismFingerprint.equals(hello.determinismFingerprint()))` — null accepts any; record the client's claimed fingerprint in `Admit` (add a `String determinismFingerprint` component to `Admit` so the broker can gate joins). Update phase-2 `RoomHost.admitMember` for the extra component (ignored there).

- [ ] **Step 4: Run broker + handshake + room tests**

Run: `mvn "-Dtest=com.openggf.net.master.TestRoomBroker+com.openggf.net.hub.TestHandshake+com.openggf.net.hub.TestRoomHost" test`
Expected: PASS (phase-2 suites green with the widened `Admit`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/RoomBroker.java src/main/java/com/openggf/net/hub/HostHandshake.java src/main/java/com/openggf/net/hub/RoomHost.java src/test/java/com/openggf/net/master/TestRoomBroker.java
git commit -m "feat(timeattack): master room broker with trust gates and PoW admission

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 10: RelayRoomManager — RoomHost-per-room pinned to event loops

**Files:**
- Create: `src/main/java/com/openggf/net/master/RelayRoomManager.java`
- Test: `src/test/java/com/openggf/net/master/TestRelayRoomManager.java`

**Interfaces:**
- Consumes: `RoomHost`/`RoomHostConfig`/`RoomHostHooks` (Task 8), `TrustLadder` (Task 7), `RoomBroker.RelayRoomDirectory` (Task 9), `TrackValidationProfileSource` (Task 14's bundled source; `none()` in tests).
- Produces `RelayRoomManager implements RoomBroker.RelayRoomDirectory`:
  - Constructor `RelayRoomManager(PlayerIdentity masterIdentity, TrustLadder ladder, TrackValidationProfileSource profiles, List<Executor> roomLoops, Executor brokerLoop, LongSupplier clock, PlayerCountSink playerCounts)` — `roomLoops` are the Netty event-loop executors (Task 12 passes `EventLoopGroup` children; tests pass `Runnable::run` direct executors), while `brokerLoop` receives relay trust accrual callbacks and player-count publishes. Nested `@FunctionalInterface interface PlayerCountSink { void accept(String roomId, int playerCount); }` — Task 12 wires `(roomId, count) -> registry.heartbeat(roomId, count)` so relay-room browser rows stay fresh and the registry's DIRECT-only expiry (Task 8) never orphans them; tests pass `(roomId, count) -> { }`. Rooms are assigned round-robin and EVERY interaction with a room marshals onto its loop (main spec §6.2: each room pinned to one event-loop thread, no cross-room state).
  - `String createRelayRoom(SessionRegistry.RoomEntry entry)` — builds `RoomHost` with `RoomHostConfig(entry name/track/policy, maxPlayers = entry.descriptor().maxPlayers(), requiredDeterminismFingerprint = entry.determinismFingerprint())` and `RoomHostHooks(relevanceFiltering = maxPlayers > 8, chatGate = ladder-backed NEW read-only window, roundOutcomeListener = clean-round accrual into the ladder — the ONLY accrual source in v1: master-observed relay rounds; player-hosted rounds are host-reported and forgeable, so they do not accrue (documented v1-conservative choice), roundOwnerFingerprint = entry.hostFingerprint())`. **The accrual listener marshals back onto the broker loop** (the ladder/SQLite are broker-loop-confined). The relay room's `hostIdentity` remains the master identity for `Welcome.serverId`; `roundOwnerFingerprint` is the creating player's fingerprint, so only that player can send `RoundConfigure`.
  - `boolean attach(HubConnection connection, String roomId, String fingerprint, String displayName)` — marshals `room.expectFingerprint(connection, fingerprint)` and then `room.onConnected(connection)` onto the room's loop; the caller (master channel handler, Task 12) reroutes all subsequent text/binary from that connection to `room.onText/onBinary` (also marshalled). Returns false when the room is gone. The guest then performs the NORMAL phase-2 room handshake (Hello→Welcome→AuthProof) against the relay `RoomHost` over the same socket, but the room rejects the handshake if the signed key's fingerprint differs from the broker-authenticated master identity. The room issues its own room-scoped token; hosting mode only changed who runs the hub (main spec §6.1).
  - `void hostLeft(String roomId)` — closes and removes the room (relay rooms are ephemeral; master restart drops rooms, acceptable v1 — main spec §9).
  - `void tickAll()` — schedules each room's `tick()` on its own loop every `TICK_MILLIS = 50` (Task 12 wires the scheduler; tests call it directly). Every `PLAYER_COUNT_INTERVAL_TICKS = 20` invocations (1 Hz), each room's loop task also snapshots `room.players().size()` and marshals it to the broker loop via `playerCounts` (the room map is read on the room's loop; the sink runs on the broker loop — no cross-thread registry touch). `int roomCount()`; `Optional<RoomAccess> find(String roomId)` where `record RoomAccess(RoomHost room, Executor loop)`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.master;

import com.openggf.net.client.ClientHandshake;
import com.openggf.net.hub.HubConnection;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestRelayRoomManager {
    static final class FakeConnection implements HubConnection {
        final List<String> text = new ArrayList<>();
        final List<byte[]> binary = new ArrayList<>();
        String closedReason;

        @Override public void sendText(String t) { text.add(t); }
        @Override public void sendBinary(byte[] d) { binary.add(d); }
        @Override public void close(String reason) { closedReason = reason; }
        @Override public String remoteHost() { return "10.0.0.2"; }
    }

    private static ControlMessage lastMessage(FakeConnection c) {
        return ControlCodec.decode(c.text.get(c.text.size() - 1)).message();
    }

    @Test
    void createAttachAndRoomHandshakeWork(@TempDir Path dir) throws Exception {
        long[] now = {1_000_000};
        var store = new SqliteIdentityStore(dir.resolve("ids.db"));
        var cache = new NewIdentityCache(100, 3_600_000, () -> now[0]);
        var ladder = new TrustLadder(store, cache, TrustLadder.Thresholds.defaults(), () -> now[0]);
        RelayRoomManager manager = new RelayRoomManager(PlayerIdentity.loadOrCreate(dir.resolve("m")),
                ladder, TrackValidationProfileSource.none(),
                List.of(Runnable::run), Runnable::run, () -> now[0], (roomId, count) -> { });

        var registry = new SessionRegistry(() -> now[0], MasterConfig.defaults());
        SessionRegistry.RoomEntry entry = registry.create(
                new ControlMessage.RoomDescriptor("Big", "s3k", 0, 0, "OPEN", null, 64, false),
                "RELAY", "host-fp", "1.1.1.1", 0, "0.6:cafe");
        assertEquals(entry.roomId(), manager.createRelayRoom(entry));
        assertEquals(1, manager.roomCount());

        // A guest attaches and completes the NORMAL room handshake against the relay RoomHost.
        FakeConnection guest = new FakeConnection();
        PlayerIdentity guestIdentity = PlayerIdentity.loadOrCreate(dir.resolve("guest"));
        // The attach binds the guest's REAL broker-authenticated fingerprint — the room
        // rejects any handshake that signs with a different key (see the tests below).
        assertTrue(manager.attach(guest, entry.roomId(), guestIdentity.fingerprint(), "GUEST"));
        ClientHandshake handshake = new ClientHandshake(guestIdentity, "GUEST", "0.6:cafe");
        var access = manager.find(entry.roomId()).orElseThrow();
        access.room().onText(guest, ControlCodec.encode(null, handshake.hello()));
        access.room().onText(guest, ControlCodec.encode(null,
                handshake.onWelcome((ControlMessage.Welcome) lastMessage(guest))));
        assertInstanceOf(ControlMessage.JoinAccepted.class, lastMessage(guest));

        manager.hostLeft(entry.roomId());
        assertEquals(0, manager.roomCount());
        assertFalse(manager.attach(new FakeConnection(), entry.roomId(), "x", "X"));
    }

    @Test
    void wrongFingerprintGuestIsRejectedByTheRoom(@TempDir Path dir) throws Exception {
        long[] now = {1_000_000};
        var store = new SqliteIdentityStore(dir.resolve("ids.db"));
        var ladder = new TrustLadder(store, new NewIdentityCache(100, 3_600_000, () -> now[0]),
                TrustLadder.Thresholds.defaults(), () -> now[0]);
        RelayRoomManager manager = new RelayRoomManager(PlayerIdentity.loadOrCreate(dir.resolve("m")),
                ladder, TrackValidationProfileSource.none(),
                List.of(Runnable::run), Runnable::run, () -> now[0], (roomId, count) -> { });
        var registry = new SessionRegistry(() -> now[0], MasterConfig.defaults());
        SessionRegistry.RoomEntry entry = registry.create(
                new ControlMessage.RoomDescriptor("Big", "s3k", 0, 0, "OPEN", null, 64, false),
                "RELAY", "host-fp", "1.1.1.1", 0, "0.6:cafe"); // determinism matches: only the IDENTITY differs
        manager.createRelayRoom(entry);

        FakeConnection guest = new FakeConnection();
        manager.attach(guest, entry.roomId(), "guest-fp", "GUEST"); // expectation bound to "guest-fp"
        ClientHandshake handshake = new ClientHandshake(
                PlayerIdentity.loadOrCreate(dir.resolve("guest")), "GUEST", "0.6:cafe"); // signs a DIFFERENT key
        var access = manager.find(entry.roomId()).orElseThrow();
        access.room().onText(guest, ControlCodec.encode(null, handshake.hello()));
        access.room().onText(guest, ControlCodec.encode(null,
                handshake.onWelcome((ControlMessage.Welcome) lastMessage(guest))));
        assertInstanceOf(ControlMessage.JoinRejected.class, lastMessage(guest)); // mismatch proven at AuthProof
    }

    @Test
    void relayAttachBindsBrokerIdentityAndCreatorCanStartRound(@TempDir Path dir) throws Exception {
        long[] now = {1_000_000};
        var store = new SqliteIdentityStore(dir.resolve("ids.db"));
        var ladder = new TrustLadder(store, new NewIdentityCache(100, 3_600_000, () -> now[0]),
                TrustLadder.Thresholds.defaults(), () -> now[0]);
        RelayRoomManager manager = new RelayRoomManager(PlayerIdentity.loadOrCreate(dir.resolve("m")),
                ladder, TrackValidationProfileSource.none(),
                List.of(Runnable::run), Runnable::run, () -> now[0], (roomId, count) -> { });
        PlayerIdentity creatorIdentity = PlayerIdentity.loadOrCreate(dir.resolve("creator"));
        var registry = new SessionRegistry(() -> now[0], MasterConfig.defaults());
        SessionRegistry.RoomEntry entry = registry.create(
                new ControlMessage.RoomDescriptor("Big", "s3k", 0, 0, "OPEN", null, 64, false),
                "RELAY", creatorIdentity.fingerprint(), "1.1.1.1", 0, "0.6:cafe");
        manager.createRelayRoom(entry);
        var access = manager.find(entry.roomId()).orElseThrow();

        FakeConnection creator = new FakeConnection();
        manager.attach(creator, entry.roomId(), creatorIdentity.fingerprint(), "HOST");
        ClientHandshake creatorHandshake = new ClientHandshake(creatorIdentity, "HOST", "0.6:cafe");
        access.room().onText(creator, ControlCodec.encode(null, creatorHandshake.hello()));
        access.room().onText(creator, ControlCodec.encode(null,
                creatorHandshake.onWelcome((ControlMessage.Welcome) lastMessage(creator))));
        String token = ((ControlMessage.JoinAccepted) lastMessage(creator)).sessionToken();
        access.room().onText(creator, ControlCodec.encode(token, new ControlMessage.RoundConfigure(
                new ControlMessage.RoundConfig("s3k", 0, 0, 60, "OPEN", null))));
        assertTrue(creator.text.stream().map(t -> ControlCodec.decode(t).message())
                .anyMatch(m -> m instanceof ControlMessage.RoundStart));

        FakeConnection impostor = new FakeConnection();
        manager.attach(impostor, entry.roomId(), creatorIdentity.fingerprint(), "BAD");
        ClientHandshake wrongHandshake = new ClientHandshake(
                PlayerIdentity.loadOrCreate(dir.resolve("wrong-key")), "BAD", "0.6:cafe");
        access.room().onText(impostor, ControlCodec.encode(null, wrongHandshake.hello()));
        access.room().onText(impostor, ControlCodec.encode(null,
                wrongHandshake.onWelcome((ControlMessage.Welcome) lastMessage(impostor))));
        assertInstanceOf(ControlMessage.JoinRejected.class, lastMessage(impostor)); // identity mismatch
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.master.TestRelayRoomManager" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.master;

import com.openggf.net.hub.HubConnection;
import com.openggf.net.hub.RoomHost;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.RoomHostHooks;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Relay rooms: the room's GhostHub runs on the master, one RoomHost per room,
 * each pinned to one event-loop executor (main spec §6.2). Shared verbatim with
 * the player-host path — hosting mode only changes who runs the hub (§6.1).
 */
public final class RelayRoomManager implements RoomBroker.RelayRoomDirectory {
    public record RoomAccess(RoomHost room, Executor loop) {
    }

    @FunctionalInterface
    public interface PlayerCountSink {
        void accept(String roomId, int playerCount);
    }

    public static final int PLAYER_COUNT_INTERVAL_TICKS = 20; // 1 Hz at the 50 ms tick

    private final PlayerIdentity masterIdentity;
    private final TrustLadder ladder;
    private final TrackValidationProfileSource profiles;
    private final List<Executor> roomLoops;
    private final Executor brokerLoop;
    private final LongSupplier clock;
    private final PlayerCountSink playerCounts;
    private final Map<String, RoomAccess> rooms = new ConcurrentHashMap<>();
    private final Map<String, Boolean> newTierByFingerprint = new ConcurrentHashMap<>();
    private final AtomicInteger nextLoop = new AtomicInteger();
    private int tickCounter;

    public RelayRoomManager(PlayerIdentity masterIdentity, TrustLadder ladder,
                            TrackValidationProfileSource profiles, List<Executor> roomLoops,
                            Executor brokerLoop, LongSupplier clock, PlayerCountSink playerCounts) {
        this.masterIdentity = masterIdentity;
        this.ladder = ladder;
        this.profiles = profiles;
        this.roomLoops = roomLoops;
        this.brokerLoop = brokerLoop;
        this.clock = clock;
        this.playerCounts = playerCounts;
    }

    @Override
    public void noteGuestTier(String fingerprint, boolean isNew) {
        newTierByFingerprint.put(fingerprint, isNew); // set on the broker loop before attach
    }

    @Override
    public String createRelayRoom(SessionRegistry.RoomEntry entry) {
        ControlMessage.RoomDescriptor descriptor = entry.descriptor();
        RoomHostConfig config = new RoomHostConfig(descriptor.name(), descriptor.gameId(),
                descriptor.zone(), descriptor.act(), descriptor.characterPolicy(),
                descriptor.lockedCharacter(), descriptor.maxPlayers(), entry.determinismFingerprint());
        RoomHostHooks hooks = new RoomHostHooks(
                descriptor.maxPlayers() > 8, // relevance filtering only at scale (main spec §4.4)
                // Chat gate reads the pre-populated tier map (no cross-thread SQLite): NEW-tier
                // members are read-only for NEW_CHAT_MUTE_MILLIS, everyone else may chat.
                (fingerprint, memberSince) -> !newTierByFingerprint.getOrDefault(fingerprint, false)
                        || clock.getAsLong() - memberSince > TrustLadder.NEW_CHAT_MUTE_MILLIS,
                (fingerprint, clean) -> brokerLoop.execute(() -> {
                    if (clean) {
                        ladder.onCleanRound(fingerprint); // the only trust-accrual source in v1
                    }
                }),
                entry.hostFingerprint(), // room creator, not the master server identity, starts rounds
                fingerprint -> newTierByFingerprint.getOrDefault(fingerprint, false)); // NEW badge feed (§4)
        RoomHost room = new RoomHost(config, masterIdentity, clock, profiles, hooks);
        Executor loop = roomLoops.get(Math.floorMod(nextLoop.getAndIncrement(), roomLoops.size()));
        rooms.put(entry.roomId(), new RoomAccess(room, loop));
        return entry.roomId();
    }

    @Override
    public boolean attach(HubConnection connection, String roomId, String fingerprint,
                          String displayName) {
        RoomAccess access = rooms.get(roomId);
        if (access == null) {
            return false;
        }
        access.loop().execute(() -> {
            access.room().expectFingerprint(connection, fingerprint);
            access.room().onConnected(connection);
        });
        return true;
    }

    @Override
    public void hostLeft(String roomId) {
        rooms.remove(roomId);
    }

    public Optional<RoomAccess> find(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    public int roomCount() {
        return rooms.size();
    }

    public void tickAll() {
        boolean publishCounts = (++tickCounter % PLAYER_COUNT_INTERVAL_TICKS) == 0;
        for (Map.Entry<String, RoomAccess> entry : rooms.entrySet()) {
            String roomId = entry.getKey();
            RoomAccess access = entry.getValue();
            access.loop().execute(() -> {
                access.room().tick();
                if (publishCounts) {
                    // players() is read on the room's own loop; the sink runs broker-side —
                    // keeps relay browser rows fresh (the registry never heartbeat-expires RELAY).
                    int count = access.room().players().size();
                    brokerLoop.execute(() -> playerCounts.accept(roomId, count));
                }
            });
        }
    }
}
```

Note: the ladder's chat gate runs on the ROOM loop but reads SQLite (broker-confined), so it must not call the ladder cross-thread. Instead the manager keeps a `ConcurrentHashMap<String, Boolean> newTierByFingerprint` and the chatGate lambda reads THAT (same semantics as `ladder.canChatYet` — NEW-tier members are read-only for `NEW_CHAT_MUTE_MILLIS`, everyone else may chat — with no SQLite touch). The broker populates it on the broker loop right before handing the connection over, via a new directory method `void noteGuestTier(String fingerprint, boolean isNew)` (added to `RoomBroker.RelayRoomDirectory` in Task 9 and called there just before `attach`). The `attach(HubConnection, String roomId, String fingerprint, String displayName)` signature stays 4-arg and now binds that fingerprint into `RoomHost.expectFingerprint(...)`; add a test case where the relay attach is for fingerprint A but the subsequent room `Hello/AuthProof` signs with identity B and assert `JoinRejected("identity mismatch")`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.master.TestRelayRoomManager" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/RelayRoomManager.java src/main/java/com/openggf/net/master/RoomBroker.java src/test/java/com/openggf/net/master/TestRelayRoomManager.java src/test/java/com/openggf/net/master/TestRoomBroker.java
git commit -m "feat(timeattack): relay room manager pinning RoomHosts to event loops

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 11: Direct→relay fallback tunnel (host ↔ master multiplexing)

**Files:**
- Create: `src/main/java/com/openggf/net/host/HostMasterLink.java`
- Create: `src/main/java/com/openggf/net/master/GuestTunnelRouter.java`
- Test: `src/test/java/com/openggf/net/TestRelayFallbackTunnel.java`

**Interfaces:**
- Main spec §9: "Direct-connect timeout (3 s) → automatic relay fallback for that pair — from phase 3 onward (the relay is the master's)." The host's OUTBOUND master connection traverses its NAT, so guest traffic tunnels over it.
- Produces `HostMasterLink` (net.host — runs on the player host beside `RaceHostServer`):
  - Constructor `HostMasterLink(RaceHostServer server)` + `void onMasterControl(ControlMessage message)` / `void onMasterBinary(byte[] packet)` — fed by the host's master connection pump (Task 13's `MasterClient` exposes the raw stream); `MessageSink` output interface `{ void sendControl(ControlMessage m); void sendBinary(byte[] d); }` for the master direction (constructor param).
  - `RelayGuestOpen(guestId)` → creates a `TunnelHubConnection` (implements `HubConnection`: `sendText(t)` → `sink.sendControl(new RelayGuestText(guestId, t))`; `sendBinary(d)` → `sink.sendBinary(GhostPackets.encodeRelayGuestBinary(guestId, d))`; `close(reason)` → `sink.sendControl(new RelayGuestClose(guestId, reason))`; `remoteHost()` = `"relay:" + guestId`) and calls `server.execute(() -> server.room().onConnected(conn))`.
  - `RelayGuestText(guestId, text)` → `server.execute(() -> room.onText(conn, text))`; wrapped binary (type 0x04) → unwrap → `room.onBinary(conn, payload)`; `RelayGuestClose` → `room.onDisconnected(conn)` + forget.
  - Guest ids are master-assigned; unknown guestId on any frame → ignore (stale after close).
- Produces `GuestTunnelRouter implements RoomBroker.DirectTunnelDirectory` (net.master — broker-loop resident):
  - `void registerHost(String roomId, HubConnection hostConnection)` — called by the broker when a DIRECT room is created. The host connection is the already-authenticated master socket owned by the room creator; server→host tunnel controls are sent over it as normal master control frames (`ControlCodec.encode(null, RelayGuestOpen/Text/Close)`), and 0x04-wrapped binary is sent raw.
  - `OptionalInt openGuest(String roomId, HubConnection guestConnection)` — allocates a guestId (u16 counter), sends `RelayGuestOpen` to the registered host, returns the id; empty means the host is gone/no longer registered, so fallback fails cleanly.
  - Guest→host: the master channel handler (Task 12) calls `void guestText(int guestId, String text)` / `void guestBinary(int guestId, byte[] packet)` → forwarded as `RelayGuestText` / 0x04-wrapped to the host sink. Host→guest: `void onHostControl(ControlMessage m)` (`RelayGuestText` → `guestConnection.sendText(text)`; `RelayGuestClose` → `guestConnection.close(reason)`) and `void onHostBinary(byte[] wrapped)` (unwrap 0x04 → `guestConnection.sendBinary(payload)`).
  - `void guestDisconnected(int guestId)` → `RelayGuestClose` to the host + forget; `void unregisterHost(String roomId)` closes all active tunnels for that room and forgets the host sink; `int activeTunnels()`.
- The tunneled traffic is EXACTLY the phase-2 room protocol: the guest performs Hello→Welcome→AuthProof against the HOST's `RoomHost` through the tunnel and streams ghost frames normally; latency-wise the pair pays guest↔master↔host, which is the §9 trade.

- [ ] **Step 1: Write the failing test** (pure — fake sinks both sides, a real `RaceHostServer`-less `RoomHost` via `Runnable::run` executors is overkill here; use a real `RoomHost` directly)

```java
package com.openggf.net;

import com.openggf.net.client.ClientHandshake;
import com.openggf.net.host.HostMasterLink;
import com.openggf.net.hub.HubConnection;
import com.openggf.net.hub.RoomHost;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.master.GuestTunnelRouter;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestRelayFallbackTunnel {
    /** Queue-backed sink: what one side "sends to the master link" the test pumps to the other. */
    static final class QueueSink implements HostMasterLink.MessageSink {
        final ArrayDeque<ControlMessage> control = new ArrayDeque<>();
        final ArrayDeque<byte[]> binary = new ArrayDeque<>();

        @Override public void sendControl(ControlMessage m) { control.add(m); }
        @Override public void sendBinary(byte[] d) { binary.add(d); }
    }

    static final class FakeGuestConnection implements HubConnection {
        final List<String> text = new ArrayList<>();
        final List<byte[]> binary = new ArrayList<>();
        String closedReason;

        @Override public void sendText(String t) { text.add(t); }
        @Override public void sendBinary(byte[] d) { binary.add(d); }
        @Override public void close(String reason) { closedReason = reason; }
        @Override public String remoteHost() { return "guest"; }
    }

    @Test
    void guestHandshakesAndStreamsThroughTheTunnel(@TempDir Path dir) throws Exception {
        // HOST side: a real RoomHost + HostMasterLink writing into hostOut.
        PlayerIdentity hostIdentity = PlayerIdentity.loadOrCreate(dir.resolve("host"));
        RoomHost room = new RoomHost(new RoomHostConfig("LAN", "s3k", 0, 0, "OPEN", null, 8, "0.6:cafe"),
                hostIdentity, System::currentTimeMillis, TrackValidationProfileSource.none());
        QueueSink hostOut = new QueueSink();
        HostMasterLink link = new HostMasterLink(room::onConnected, room::onText, room::onBinary,
                room::onDisconnected, hostOut);

        // MASTER side: router pairing the fake guest with the host sink.
        FakeGuestConnection guest = new FakeGuestConnection();
        QueueSink masterToHost = new QueueSink();
        GuestTunnelRouter router = new GuestTunnelRouter();
        router.registerHost("room-1", new HubConnection() {
            @Override public void sendText(String t) {
                masterToHost.control.add((ControlMessage) ControlCodec.decode(t).message());
            }
            @Override public void sendBinary(byte[] d) { masterToHost.binary.add(d); }
            @Override public void close(String reason) { }
            @Override public String remoteHost() { return "host"; }
        });
        int guestId = router.openGuest("room-1", guest).orElseThrow();

        // Pump loop: master→host then host→master until both quiet.
        Runnable pump = () -> {
            while (!masterToHost.control.isEmpty()) {
                link.onMasterControl(masterToHost.control.poll());
            }
            while (!masterToHost.binary.isEmpty()) {
                link.onMasterBinary(masterToHost.binary.poll());
            }
            while (!hostOut.control.isEmpty()) {
                router.onHostControl(hostOut.control.poll());
            }
            while (!hostOut.binary.isEmpty()) {
                router.onHostBinary(hostOut.binary.poll());
            }
        };
        pump.run(); // delivers RelayGuestOpen → room.onConnected

        // Guest performs the normal phase-2 room handshake through the tunnel.
        PlayerIdentity guestIdentity = PlayerIdentity.loadOrCreate(dir.resolve("guest"));
        ClientHandshake handshake = new ClientHandshake(guestIdentity, "GUEST", "0.6:cafe");
        router.guestText(guestId, ControlCodec.encode(null, handshake.hello()));
        pump.run();
        ControlMessage.Welcome welcome = (ControlMessage.Welcome) ControlCodec.decode(
                guest.text.get(guest.text.size() - 1)).message();
        router.guestText(guestId, ControlCodec.encode(null, handshake.onWelcome(welcome)));
        pump.run();
        assertTrue(guest.text.stream().map(t -> ControlCodec.decode(t).message())
                .anyMatch(m -> m instanceof ControlMessage.JoinAccepted));

        // Ghost frames flow guest→room and aggregates flow room→guest.
        byte[] frame = new byte[com.openggf.game.ghost.GhostFrameCodec.BYTES];
        router.guestBinary(guestId, GhostPackets.encodeFrames(1, 0, frame));
        pump.run();
        room.tick(); // no other players: no aggregate — but the ingest must not have violated
        pump.run();
        assertNull(guest.closedReason);

        // Teardown propagates both ways.
        router.guestDisconnected(guestId);
        pump.run();
        assertEquals(0, router.activeTunnels());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.TestRelayFallbackTunnel" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

`HostMasterLink` — constructed from four functional handles so it works with a raw `RoomHost` (tests) or `RaceHostServer.execute`-marshalled calls (production; provide a `static HostMasterLink forServer(RaceHostServer server, MessageSink sink)` factory that wraps each handle in `server.execute`):

```java
package com.openggf.net.host;

import com.openggf.net.hub.HubConnection;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;
import com.openggf.net.protocol.ProtocolViolationException;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Player-host side of the relay fallback tunnel (main spec §9): guests the master
 * could not hand off directly ride the host's outbound master connection. Each
 * tunneled guest appears to the RoomHost as a normal HubConnection.
 */
public final class HostMasterLink {
    public interface MessageSink {
        void sendControl(ControlMessage message);

        void sendBinary(byte[] data);
    }

    private final Consumer<HubConnection> onConnected;
    private final BiConsumer<HubConnection, String> onText;
    private final BiConsumer<HubConnection, byte[]> onBinary;
    private final Consumer<HubConnection> onDisconnected;
    private final MessageSink sink;
    private final Map<Integer, HubConnection> guests = new HashMap<>();

    public HostMasterLink(Consumer<HubConnection> onConnected, BiConsumer<HubConnection, String> onText,
                          BiConsumer<HubConnection, byte[]> onBinary,
                          Consumer<HubConnection> onDisconnected, MessageSink sink) {
        this.onConnected = onConnected;
        this.onText = onText;
        this.onBinary = onBinary;
        this.onDisconnected = onDisconnected;
        this.sink = sink;
    }

    public static HostMasterLink forServer(RaceHostServer server, MessageSink sink) {
        return new HostMasterLink(
                conn -> server.execute(() -> server.room().onConnected(conn)),
                (conn, text) -> server.execute(() -> server.room().onText(conn, text)),
                (conn, data) -> server.execute(() -> server.room().onBinary(conn, data)),
                conn -> server.execute(() -> server.room().onDisconnected(conn)),
                sink);
    }

    public void onMasterControl(ControlMessage message) {
        switch (message) {
            case ControlMessage.RelayGuestOpen open -> {
                HubConnection conn = new TunnelConnection(open.guestId());
                guests.put(open.guestId(), conn);
                onConnected.accept(conn);
            }
            case ControlMessage.RelayGuestText text -> {
                HubConnection conn = guests.get(text.guestId());
                if (conn != null) {
                    onText.accept(conn, text.text());
                }
            }
            case ControlMessage.RelayGuestClose close -> {
                HubConnection conn = guests.remove(close.guestId());
                if (conn != null) {
                    onDisconnected.accept(conn);
                }
            }
            default -> { /* not tunnel traffic */ }
        }
    }

    public void onMasterBinary(byte[] packet) {
        try {
            GhostPackets.RelayGuestBinary wrapped = GhostPackets.decodeRelayGuestBinary(packet);
            HubConnection conn = guests.get(wrapped.guestId());
            if (conn != null) {
                onBinary.accept(conn, wrapped.payload());
            }
        } catch (ProtocolViolationException e) {
            // not a tunnel frame; ignore
        }
    }

    private final class TunnelConnection implements HubConnection {
        private final int guestId;

        private TunnelConnection(int guestId) {
            this.guestId = guestId;
        }

        @Override
        public void sendText(String text) {
            sink.sendControl(new ControlMessage.RelayGuestText(guestId, text));
        }

        @Override
        public void sendBinary(byte[] data) {
            sink.sendBinary(GhostPackets.encodeRelayGuestBinary(guestId, data));
        }

        @Override
        public void close(String reason) {
            guests.remove(guestId);
            sink.sendControl(new ControlMessage.RelayGuestClose(guestId, reason));
        }

        @Override
        public String remoteHost() {
            return "relay:" + guestId;
        }
    }
}
```

`GuestTunnelRouter` (net.master):

```java
package com.openggf.net.master;

import com.openggf.net.hub.HubConnection;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.GhostPackets;
import com.openggf.net.protocol.ProtocolViolationException;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/** Master side of the fallback tunnel: pairs a guest connection with a host sink. */
public final class GuestTunnelRouter implements RoomBroker.DirectTunnelDirectory {
    private record Tunnel(String roomId, HubConnection guest, HubConnection host) {
    }

    private final Map<String, HubConnection> hostsByRoom = new HashMap<>();
    private final Map<Integer, Tunnel> tunnels = new HashMap<>();
    private int nextGuestId;

    @Override
    public void registerHost(String roomId, HubConnection hostConnection) {
        hostsByRoom.put(roomId, hostConnection);
    }

    @Override
    public OptionalInt openGuest(String roomId, HubConnection guestConnection) {
        HubConnection host = hostsByRoom.get(roomId);
        if (host == null) {
            return OptionalInt.empty();
        }
        int guestId = (++nextGuestId) & 0xFFFF;
        tunnels.put(guestId, new Tunnel(roomId, guestConnection, host));
        host.sendText(ControlCodec.encode(null, new ControlMessage.RelayGuestOpen(guestId)));
        return OptionalInt.of(guestId);
    }

    public void guestText(int guestId, String text) {
        Tunnel tunnel = tunnels.get(guestId);
        if (tunnel != null) {
            tunnel.host().sendText(ControlCodec.encode(null, new ControlMessage.RelayGuestText(guestId, text)));
        }
    }

    public void guestBinary(int guestId, byte[] packet) {
        Tunnel tunnel = tunnels.get(guestId);
        if (tunnel != null) {
            tunnel.host().sendBinary(GhostPackets.encodeRelayGuestBinary(guestId, packet));
        }
    }

    public void guestDisconnected(int guestId) {
        Tunnel tunnel = tunnels.remove(guestId);
        if (tunnel != null) {
            tunnel.host().sendText(ControlCodec.encode(null,
                    new ControlMessage.RelayGuestClose(guestId, "guest disconnected")));
        }
    }

    public void onHostControl(ControlMessage message) {
        switch (message) {
            case ControlMessage.RelayGuestText text -> {
                Tunnel tunnel = tunnels.get(text.guestId());
                if (tunnel != null) {
                    tunnel.guest().sendText(text.text());
                }
            }
            case ControlMessage.RelayGuestClose close -> {
                Tunnel tunnel = tunnels.remove(close.guestId());
                if (tunnel != null) {
                    tunnel.guest().close(close.reason());
                }
            }
            default -> { /* not tunnel traffic */ }
        }
    }

    public void onHostBinary(byte[] wrapped) {
        try {
            GhostPackets.RelayGuestBinary payload = GhostPackets.decodeRelayGuestBinary(wrapped);
            Tunnel tunnel = tunnels.get(payload.guestId());
            if (tunnel != null) {
                tunnel.guest().sendBinary(payload.payload());
            }
        } catch (ProtocolViolationException e) {
            // not a tunnel frame; ignore
        }
    }

    public int activeTunnels() {
        return tunnels.size();
    }

    @Override
    public void unregisterHost(String roomId) {
        hostsByRoom.remove(roomId);
        tunnels.entrySet().removeIf(entry -> {
            if (entry.getValue().roomId().equals(roomId)) {
                entry.getValue().guest().close("host disconnected");
                return true;
            }
            return false;
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.TestRelayFallbackTunnel" test`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/host/HostMasterLink.java src/main/java/com/openggf/net/master/GuestTunnelRouter.java src/test/java/com/openggf/net/TestRelayFallbackTunnel.java
git commit -m "feat(timeattack): direct-to-relay fallback tunnel over the host master link

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 12: MasterServerMain — Netty wss server, attack-mode gate, admin endpoint

**Files:**
- Create: `src/main/java/com/openggf/net/master/MasterServer.java`
- Create: `src/main/java/com/openggf/net/master/MasterChannelHandler.java`
- Create: `src/main/java/com/openggf/net/master/AdminEndpoint.java`
- Create: `src/main/java/com/openggf/net/master/MasterServerMain.java`
- Test: `src/test/java/com/openggf/net/master/TestMasterServer.java`

**Interfaces:**
- Produces `MasterServer.start(MasterConfig config, Path dataDir)` → running server; `int port()`; `void execute(Runnable task)` (broker loop); `RoomBroker broker()`; `RelayRoomManager relays()`; `void close()`. Wiring: one `NioEventLoopGroup(1)` acceptor/broker loop + one `NioEventLoopGroup(cores)` whose children become the relay `roomLoops`; `SqliteIdentityStore(dataDir.resolve(config.dbPath()))`, `NewIdentityCache`, `TrustLadder(config.thresholds())`, `SessionRegistry`, `GuestTunnelRouter`, `RelayRoomManager(..., (roomId, count) -> registry.heartbeat(roomId, count))` (the player-count sink runs on the broker loop — it keeps RELAY browser rows fresh since the registry's expiry is DIRECT-only), `RoomBroker(masterIdentity, config, registry, store, ladder, cache, clock, relays, guestTunnelRouter)` — ALL broker-loop-confined except the relay rooms; relay ticks scheduled at 50 ms per room loop; registry expiry + hourly identity GC on the broker loop.
- TLS (security spec §7.3): `SslContextBuilder.forServer(new File(config.tlsCertPath()), new File(config.tlsKeyPath())).build()`; the `SslHandler` leads the pipeline. `config.plaintextForTest()` skips it with `LOG.warning("MASTER RUNNING WITHOUT TLS — test mode only")`. Rest of the pipeline = phase-2 hygiene with ONE deliberate cap change: `HttpServerCodec`, `HttpObjectAggregator(8192)`, `WebSocketServerProtocolHandler("/master", null, true, Protocol.MAX_MASTER_FRAME_BYTES)`, `WebSocketFrameAggregator(Protocol.MAX_MASTER_FRAME_BYTES)` — the master cap includes the Task-1 tunnel headroom, because `RelayGuestText` frames nest a full room control envelope and would exceed `MAX_CONTROL_BYTES` — `IdleStateHandler(60, 0, 0)`, `MasterChannelHandler` (per-IP connection cap + per-connection message rate bucket — copy the constants/logic from `RaceHostChannelHandler`; keep them in one place by promoting the phase-2 `ConnectionCounter` + rate-bucket into a small shared `net.host` utility class `ConnectionHygiene` used by both handlers).
- `MasterChannelHandler` routing: connections start in BROKER mode (`broker.onText`, marshalled onto the broker loop); after a successful `RelayAttach` for a RELAY room, the handler flips to ROOM mode and routes text/binary to the attached room via `RelayRoomManager.RoomAccess` (marshalled onto the room's loop); after a successful `RelayAttach` for a DIRECT fallback, the handler flips to TUNNEL_GUEST mode with the allocated guestId and routes text/binary into `GuestTunnelRouter.guestText/guestBinary`. The broker exposes the attach outcome via a `Consumer<AttachResult>` callback parameter (`record AttachResult(Mode mode, String roomId, int guestId)`). Host connections that own DIRECT rooms stay in BROKER mode for browser/heartbeat commands, but inbound `RelayGuest*` messages and 0x04 binary from that host are intercepted and routed to `router.onHostControl/onHostBinary` before ordinary broker dispatch (the interception decode, like every decode on a master connection, uses `ControlCodec.decode(text, Protocol.MAX_MASTER_FRAME_BYTES)` — Task 1). **Disconnects route by mode:** BROKER → `broker.onDisconnected` on the broker loop (existing path); ROOM → the attached room's `onDisconnected` on ITS loop **and** `broker.onDisconnected` on the broker loop — the broker keeps its member record after the mode flip (it stops routing traffic, not tracking ownership) precisely so a relay-room OWNER's death still tears down their rooms via `removeByHostFingerprint` + `relays.hostLeft`; TUNNEL_GUEST → `router.guestDisconnected(guestId)` on the broker loop (which sends `RelayGuestClose` to the host) plus the same broker teardown.
- `AdminEndpoint(MasterConfig config, MasterServer server)` — plain-HTTP `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1:adminPort` (localhost-only by construction; operators tunnel in — security spec §9 v1 operator tooling): `POST /admin/sanction` (JSON `{fingerprint, type: BAN|TIMEOUT, reason, durationHours}` → `TrustLadder.sanction`), `POST /admin/attack-mode` (`{"enabled": true}` → `broker.setAttackMode`), `GET /admin/audit` (returns the audit log tail). Every admin action appends one JSON line to `dataDir/admin-audit.jsonl` — actor (token suffix), action, reason, timestamp (append-only audit log — security spec §5). All requests require header `Authorization: Bearer <config.adminToken()>` → else 401.
- `MasterServerMain` — tools-style main (`com.openggf.net.master.MasterServerMain`, main spec §6.2): args `--config master.yaml --data ./master-data`; loads config, starts server + admin, blocks on shutdown hook. NO engine imports (fence-checked).

- [ ] **Step 1: Write the failing test** (plaintext-for-test loopback + admin HTTP; TLS handshake is covered in Task 13's client test with a self-signed cert)

```java
package com.openggf.net.master;

import com.openggf.net.client.ClientHandshake;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.ControlCodec;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class TestMasterServer {
    private MasterServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** Plaintext test config with tiny PoW. Write the full canonical-constructor call. */
    static MasterConfig testConfig() {
        MasterConfig defaults = MasterConfig.defaults();
        return new MasterConfig(0, null, null, true, "ids.db", 0, "secret-admin-token",
                defaults.establishedAgeHours(), defaults.establishedCleanRounds(),
                defaults.trustedAgeDays(), defaults.trustedCleanRounds(),
                8 /* identityPowBits */, 8 /* attackModePowBits */, false,
                defaults.maxRoomsPerIdentity(), defaults.maxRoomsPerIp(),
                defaults.roomHeartbeatTimeoutSeconds(), defaults.browserPageSize(),
                defaults.identityGcInactiveDays(), defaults.newIdentityCacheSize(),
                defaults.newIdentityCacheTtlMinutes());
    }

    static final class Probe implements WebSocket.Listener {
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

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
    }

    private ControlMessage await(Probe probe, Class<?> type) throws Exception {
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
    void browsesOverRealSocketWithIdentityPow(@TempDir Path dir) throws Exception {
        server = MasterServer.start(testConfig(), dir);
        PlayerIdentity identity = PlayerIdentity.loadOrCreate(dir.resolve("id"));
        ClientHandshake handshake = new ClientHandshake(identity, "A", "0.6:cafe");
        Probe probe = new Probe();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/master"), probe)
                .get(10, TimeUnit.SECONDS);
        ws.sendText(ControlCodec.encode(null, handshake.hello()), true).join();
        ControlMessage.Welcome welcome = (ControlMessage.Welcome) await(probe, ControlMessage.Welcome.class);
        ws.sendText(ControlCodec.encode(null, handshake.onWelcome(welcome)), true).join();
        ControlMessage.PowChallenge challenge =
                (ControlMessage.PowChallenge) await(probe, ControlMessage.PowChallenge.class);
        ws.sendText(ControlCodec.encode(null, new ControlMessage.PowSolution("IDENTITY",
                identity.creationPowNonce(challenge.difficultyBits()))), true).join();
        ControlMessage.JoinAccepted accepted =
                (ControlMessage.JoinAccepted) await(probe, ControlMessage.JoinAccepted.class);

        ws.sendText(ControlCodec.encode(accepted.sessionToken(),
                new ControlMessage.RoomListRequest(null, 0)), true).join();
        ControlMessage.RoomListResult list =
                (ControlMessage.RoomListResult) await(probe, ControlMessage.RoomListResult.class);
        assertTrue(list.rooms().isEmpty());
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void adminEndpointSanctionsAndTogglesAttackMode(@TempDir Path dir) throws Exception {
        server = MasterServer.start(testConfig(), dir);
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> unauthorized = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + server.adminPort() + "/admin/attack-mode"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"enabled\":true}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> ok = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + server.adminPort() + "/admin/sanction"))
                        .header("Authorization", "Bearer secret-admin-token")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"fingerprint\":\"abc\",\"type\":\"BAN\",\"reason\":\"cheating\",\"durationHours\":0}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        assertTrue(java.nio.file.Files.readString(dir.resolve("admin-audit.jsonl")).contains("cheating"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.master.TestMasterServer" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`MasterServer` — bootstrap mirroring `RaceHostServer` with: optional `SslHandler` first (`config.plaintextForTest()` skips + warns), path `/master`, the shared `ConnectionHygiene` (extract from `RaceHostChannelHandler` first: move `ConnectionCounter` + the rate-bucket into `src/main/java/com/openggf/net/host/ConnectionHygiene.java` and delegate from both handlers), broker/relay wiring exactly per Interfaces, `adminPort()` accessor (the admin endpoint binds port 0 in tests — expose the actual). `MasterChannelHandler` — per-connection `Mode { BROKER, ROOM, TUNNEL_GUEST }` field; BROKER routes to `broker` on the broker loop; the broker's attach callback flips modes. `AdminEndpoint` — `com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", config.adminPort()), 0)`; handlers parse with the shared Jackson mapper; sanction expiry = `durationHours <= 0 ? Long.MAX_VALUE : now + hours`; audit line via `Files.writeString(..., APPEND, CREATE)`. `MasterServerMain`:

```java
package com.openggf.net.master;

import java.nio.file.Path;

/** Engine-free master entry point (main spec §6.2). Usage: --config master.yaml --data ./master-data */
public final class MasterServerMain {
    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(argValue(args, "--config", "master.yaml"));
        Path dataDir = Path.of(argValue(args, "--data", "master-data"));
        MasterConfig config = MasterConfig.load(configPath);
        MasterServer server = MasterServer.start(config, dataDir);
        System.getLogger(MasterServerMain.class.getName()).log(System.Logger.Level.INFO,
                "master listening on port " + server.port() + " (admin " + server.adminPort() + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }

    private static String argValue(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.master.TestMasterServer" test`
Expected: PASS (2 tests, real loopback sockets).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/master/MasterServer.java src/main/java/com/openggf/net/master/MasterChannelHandler.java src/main/java/com/openggf/net/master/AdminEndpoint.java src/main/java/com/openggf/net/master/MasterServerMain.java src/main/java/com/openggf/net/host/ConnectionHygiene.java src/main/java/com/openggf/net/host/RaceHostChannelHandler.java src/test/java/com/openggf/net/master/TestMasterServer.java
git commit -m "feat(timeattack): master server main with TLS, attack-mode gate, admin endpoint

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 13: MasterClient — browse/create/join with direct→relay fallback

**Files:**
- Create: `src/main/java/com/openggf/net/client/RaceConnection.java` (interface extracted from `RaceClient`)
- Modify: `src/main/java/com/openggf/net/client/RaceClient.java` (`implements RaceConnection` — no behavior change)
- Create: `src/main/java/com/openggf/net/client/MasterClient.java`
- Test: `src/test/java/com/openggf/net/client/TestMasterClient.java`

**Interfaces:**
- Produces `RaceConnection` — the room-connection abstraction the engine adapter (phase-2 `RaceTransport`) wraps, so a room reached via relay is indistinguishable from a direct one:

```java
public interface RaceConnection {
    List<RaceClient.InboundEvent> drainInbound();

    void sendControl(ControlMessage message);

    void sendBinary(byte[] data);

    int playerSlot();

    String sessionToken();

    ControlMessage.JoinAccepted joinAccepted();

    boolean isOpen();

    void close();
}
```

  (`RaceClient` already has every method — add the `implements` clause. Task 16 updates the engine `RaceTransport` adapter to wrap `RaceConnection` instead of `RaceClient` concretely.)
- Produces `MasterClient`:
  - `static CompletableFuture<MasterClient> connect(URI wssUri, PlayerIdentity identity, String displayName, String determinismFingerprint, SSLContext sslContextOrNull)` — `sslContextOrNull` installs into the `HttpClient` builder (null = JDK default trust; tests pass a trust-all context built by the test — production passes null and relies on real certificates; there is deliberately NO insecure flag in production code). Drives Hello→Welcome→AuthProof and transparently answers `PowChallenge`s: `"IDENTITY"` → `identity.creationPowNonce(bits)` (may take ~1 s once, ever); `"JOIN"` → `ProofOfWork.solve(prefixBytes, bits)` (attack mode). Completes on `JoinAccepted`.
  - Request/reply commands (each returns a `CompletableFuture` completed by the matching reply, `MASTER_REPLY_TIMEOUT_MILLIS = 5000` via `orTimeout`): `CompletableFuture<ControlMessage.RoomListResult> listRooms(String gameFilter, int page)`; `CompletableFuture<ControlMessage.RoomCreated> createRoom(ControlMessage.RoomDescriptor descriptor, String routing, int directPort, String determinismFingerprint)` (rejections complete exceptionally with `RaceClient.JoinRejectedException(reason)`); `CompletableFuture<ControlMessage.RoomJoinResult> requestJoin(String roomId)`; `void heartbeat(String roomId, int playerCount)`; `void leaveRoom(String roomId)`.
  - **Join orchestration** — `CompletableFuture<RaceConnection> joinRoom(String roomId, PlayerIdentity identity, String displayName, String determinismFingerprint)`:
    1. `requestJoin(roomId)`; `RELAY` routing → `attachRelay()` (below).
    2. `DIRECT` → `RaceClient.connect("ws://" + directHost + ":" + directPort + "/race", ...)` with its phase-2 3 s whole-join timeout, THEN verify the host's identity: the phase-2 `ClientHandshake` recorded `serverId` — it must equal `RoomJoinResult.hostServerId` or the connection is closed with `JoinRejectedException("host identity mismatch")` (the master told us who the host IS; a MITM host fails here).
    3. Direct failure (timeout/refused) → **automatic relay fallback for that pair** (main spec §9): `requestJoin` again is NOT needed — send `RelayAttach(roomId)` on the master connection and proceed as relay.
  - `RaceConnection attachRelay(String roomId)` — sends `RelayAttach`, then RE-USES the master socket as the room connection: returns a `RelayRaceConnection` (inner class) that owns the socket from that moment (the `MasterClient` stops interpreting inbound traffic and forwards raw text/binary events to it); the caller then drives the normal phase-2 room handshake through it — provide `static CompletableFuture<RaceConnection> completeRoomHandshake(RaceConnection raw, PlayerIdentity identity, String displayName, String determinismFingerprint)` which performs Hello→Welcome→AuthProof→JoinAccepted over ANY `RaceConnection` (reusing `ClientHandshake`; this is the same sequence `RaceClient.connect` does internally — extract the small state machine so both paths share it).
  - Host-side master link: `void bindHostLink(HostMasterLink link)` — inbound `RelayGuest*` control and 0x04 binary are routed to the link instead of the inbound queue (a hosting player's client uses ONE master connection for announce + heartbeat + tunnel).
  - `List<RaceClient.InboundEvent> drainInbound()` for anything unhandled; `boolean isOpen()`; `void close()`.

- [ ] **Step 1: Write the failing test** (loopback against the real `MasterServer` in plaintext-test mode; relay path end to end; direct fallback via a dead direct port)

```java
package com.openggf.net.client;

import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.master.MasterServer;
import com.openggf.net.master.TestMasterServer;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class TestMasterClient {
    private static final String FP = "0.6:cafe";
    private MasterServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private MasterClient connect(Path idDir, String name) throws Exception {
        return MasterClient.connect(URI.create("ws://127.0.0.1:" + server.port() + "/master"),
                PlayerIdentity.loadOrCreate(idDir), name, FP, null).get(15, TimeUnit.SECONDS);
    }

    /** Fast-forward an identity past NEW directly in the master's ladder (broker loop). */
    private void establish(Path idDir) throws Exception {
        String fingerprint = PlayerIdentity.loadOrCreate(idDir).fingerprint();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        server.execute(() -> {
            server.establishForTest(fingerprint); // test hook: 10 clean rounds + age backdate
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void createListJoinRelayRoundTrip(@TempDir Path dir) throws Exception {
        server = MasterServer.start(TestMasterServer.testConfig(), dir);
        MasterClient host = connect(dir.resolve("host"), "HOST");
        establish(dir.resolve("host"));

        ControlMessage.RoomDescriptor desc =
                new ControlMessage.RoomDescriptor("Big", "s3k", 0, 0, "OPEN", null, 64, false);
        String roomId = host.createRoom(desc, "RELAY", 0, FP).get(10, TimeUnit.SECONDS).roomId();

        MasterClient guest = connect(dir.resolve("guest"), "GUEST");
        ControlMessage.RoomListResult list = guest.listRooms("s3k", 0).get(10, TimeUnit.SECONDS);
        assertEquals(1, list.rooms().size());
        assertEquals("RELAY", list.rooms().get(0).routing());

        RaceConnection room = guest.joinRoom(roomId, PlayerIdentity.loadOrCreate(dir.resolve("guest")),
                "GUEST", FP).get(15, TimeUnit.SECONDS);
        assertTrue(room.isOpen());
        assertTrue(room.playerSlot() >= 0); // full room handshake completed through the relay
        room.close();
        guest.close();
        host.close();
    }

    @Test
    void directJoinFallsBackToRelayWhenHostUnreachable(@TempDir Path dir) throws Exception {
        server = MasterServer.start(TestMasterServer.testConfig(), dir);
        MasterClient host = connect(dir.resolve("host"), "HOST");
        establish(dir.resolve("host"));
        // DIRECT room advertising a dead port — direct connect must fail fast, then fall back.
        ControlMessage.RoomDescriptor desc =
                new ControlMessage.RoomDescriptor("Lan", "s3k", 0, 0, "OPEN", null, 8, false);
        String roomId = host.createRoom(desc, "DIRECT", 1, FP).get(10, TimeUnit.SECONDS).roomId();
        // The fallback tunnel needs the host link listening on the host's master connection.
        var hostServer = com.openggf.net.host.RaceHostServer.start(0,
                new com.openggf.net.hub.RoomHostConfig("Lan", "s3k", 0, 0, "OPEN", null, 8, FP),
                PlayerIdentity.loadOrCreate(dir.resolve("host")),
                com.openggf.net.hub.TrackValidationProfileSource.none());
        host.bindHostLink(com.openggf.net.host.HostMasterLink.forServer(hostServer,
                new com.openggf.net.host.HostMasterLink.MessageSink() {
                    @Override public void sendControl(ControlMessage m) { host.sendControl(m); }
                    @Override public void sendBinary(byte[] d) { host.sendBinary(d); }
                }));

        MasterClient guest = connect(dir.resolve("guest"), "GUEST");
        RaceConnection room = guest.joinRoom(roomId, PlayerIdentity.loadOrCreate(dir.resolve("guest")),
                "GUEST", FP).get(30, TimeUnit.SECONDS);
        assertTrue(room.isOpen()); // reached the host's RoomHost through the tunnel
        assertEquals(0, room.playerSlot());
        room.close();
        hostServer.close();
        guest.close();
        host.close();
    }
}
```

Test support additions this test legitimizes: `MasterServer.establishForTest(String fingerprint)` (test-only hook, package-visible is fine since the test lives in `net.master`... it does not — make it public and clearly named; it writes the store directly — backdated first-seen plus 10 clean rounds — deliberately bypassing the ladder's `ACCRUAL_MIN_INTERVAL_MILLIS` pacing, which would otherwise make the fast-forward impossible), and public `MasterClient.sendControl/sendBinary` passthroughs (needed by the host link binding anyway). `TestMasterServer.testConfig()` becomes public static (already written that way).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.client.TestMasterClient" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`MasterClient` implementation notes (structure mirrors `RaceClient`; ~250 lines):
- One JDK WebSocket + `FrameAssembler` + send-chain, exactly like `RaceClient` — but the assembler/inbound cap AND the inbound decode both use `Protocol.MAX_MASTER_FRAME_BYTES` (`ControlCodec.decode(text, Protocol.MAX_MASTER_FRAME_BYTES)` — Task 1): tunnel-wrapped `RelayGuestText` frames legitimately exceed the room cap, and raising only the transport cap would still fail in the phase-2 decoder. The host's `HostMasterLink` traffic rides this same connection, so the host side inherits both raised caps for free; the inner envelope is re-decoded at the room/guest under the normal tight cap.
- A `pendingReplies` map keyed by reply type (`Class<? extends ControlMessage>` → queue of `CompletableFuture`s); inbound routing: handshake phase first (Hello/Welcome/AuthProof/PowChallenge/JoinAccepted), then reply matching (`RoomListResult`, `RoomCreated`/`RoomCreateRejected`, `RoomJoinResult`/`RoomJoinRejected` complete the head future of their queue), then `RelayGuest*`/0x04 → bound `HostMasterLink` if any, else → inbound queue.
- `RelayRaceConnection` (inner): after `RelayAttach` is sent, an `AtomicReference<RelayRaceConnection> attached` diverts ALL inbound text/binary to the connection's own inbound queue; its `sendControl(m)` encodes with ITS room token (`joinAccepted` captured by `completeRoomHandshake`), `sendBinary` writes raw; `close()` closes the underlying socket (leaving a relay room = leaving the master — re-connect to browse again; documented simplification).
- `completeRoomHandshake(raw, ...)`: drain-loop on a small executor (single virtual thread — `Thread.ofVirtual()`): send Hello (token null), await Welcome → send AuthProof, await JoinAccepted → capture into the connection, complete. `orTimeout(RaceClient.JOIN_TIMEOUT_MILLIS)`.
- `joinRoom` composes: `requestJoin` → direct? `RaceClient.connect(...).thenApply(verify hostServerId)` with `exceptionallyCompose(fallback → attachRelay + completeRoomHandshake)` → relay? straight to attach+handshake.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.client.TestMasterClient" test`
Expected: PASS (2 tests; the fallback test takes ~5–10 s including the 3 s direct timeout).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/client/RaceConnection.java src/main/java/com/openggf/net/client/RaceClient.java src/main/java/com/openggf/net/client/MasterClient.java src/main/java/com/openggf/net/master/MasterServer.java src/test/java/com/openggf/net/client/TestMasterClient.java
git commit -m "feat(timeattack): master client with browse, join, and relay fallback

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 14: Track validation profile export tool + bundled source

**Files:**
- Create: `src/main/java/com/openggf/tools/net/TrackProfileExportTool.java` (ENGINE side — outside the fence, on purpose)
- Create: `src/main/resources/net/track-validation-profiles.json` (generated, checked in)
- Create: `src/main/java/com/openggf/net/hub/BundledProfileSource.java`
- Test: `src/test/java/com/openggf/net/hub/TestBundledProfileSource.java`

**Interfaces:**
- Security spec §7.2: the ROM-free master cannot load levels and must not trust host-supplied limits — an engine-side build tool exports pure numeric metadata; the table is a checked-in resource bundled with the master.
- Produces:
  - `TrackProfileExportTool` (tools-style main, requires ROMs locally): iterates `TimeAttackTrackCatalog.tracksFor(...)` for `s1`/`s2`/`s3k`, headlessly loads each track's level (same bootstrap the trace suite uses — grep `HeadlessTestRunner` + the phase-1 catalog ROM-validation test for the loading idiom), reads the level pixel dimensions, and writes `src/main/resources/net/track-validation-profiles.json`:

```json
{ "profiles": [
  { "key": "s3k:0:0", "levelWidthPx": 10752, "levelHeightPx": 2048,
    "maxSpeedPxPerFrame": 32, "maxFramesPerSecond": 60 } ] }
```

    (speed = `TrackValidationProfile.GLOBAL_SPEED_CEILING_PX_PER_FRAME` — the conservative cross-character ceiling; per-track speed tuning is deliberately NOT attempted, security spec §7.2.) Running the tool requires ROMs; the OUTPUT contains only numbers — no asset bytes. Skip-and-warn for games whose ROM is absent.
  - `BundledProfileSource implements TrackValidationProfileSource` (net.hub, engine-free): loads the classpath resource `/net/track-validation-profiles.json` with Jackson at construction; `profileFor(gameId, zone, act)` looks up `"gameId:zone:act"`; missing key → `Optional.empty()` (the validator's explicit degrade). Used by `RelayRoomManager` on the master (update Task 10's construction in `MasterServer` to pass `new BundledProfileSource()`).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBundledProfileSource {
    @Test
    void loadsBundledTableAndDegradesOnUnknownTracks() {
        BundledProfileSource source = new BundledProfileSource();
        // The checked-in table must cover the phase-1 catalog's s3k tracks (AIZ1 at minimum).
        TrackValidationProfile aiz1 = source.profileFor("s3k", 0, 0).orElseThrow();
        assertTrue(aiz1.levelWidthPx() > 1000);
        assertTrue(aiz1.levelHeightPx() > 200);
        assertEquals(TrackValidationProfile.GLOBAL_SPEED_CEILING_PX_PER_FRAME, aiz1.maxSpeedPxPerFrame());
        assertTrue(source.profileFor("s3k", 99, 0).isEmpty()); // unknown → explicit degrade
        assertTrue(source.profileFor("nope", 0, 0).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestBundledProfileSource" test`
Expected: COMPILATION ERROR / resource missing.

- [ ] **Step 3: Implement**

`BundledProfileSource`:

```java
package com.openggf.net.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Checked-in numeric track table for the ROM-free master (security spec §7.2).
 * Regenerate with com.openggf.tools.net.TrackProfileExportTool (needs ROMs locally;
 * the output is pure numbers — no asset bytes).
 */
public final class BundledProfileSource implements TrackValidationProfileSource {
    private final Map<String, TrackValidationProfile> profiles = new HashMap<>();

    public BundledProfileSource() {
        try (InputStream in = BundledProfileSource.class.getResourceAsStream("/net/track-validation-profiles.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode node : root.get("profiles")) {
                profiles.put(node.get("key").asText(), new TrackValidationProfile(
                        node.get("levelWidthPx").asInt(), node.get("levelHeightPx").asInt(),
                        node.get("maxSpeedPxPerFrame").asInt(), node.get("maxFramesPerSecond").asInt()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("bundled track profile table unreadable", e);
        }
    }

    @Override
    public Optional<TrackValidationProfile> profileFor(String gameId, int zone, int act) {
        return Optional.ofNullable(profiles.get(gameId + ":" + zone + ":" + act));
    }
}
```

`TrackProfileExportTool` — engine side: for each catalog track, boot the headless level-load path (reconcile with the as-built phase-1 `TestTimeAttackTrackCatalogRomValidation`, which already proves each catalog track loads — reuse its loading helper; if it has none, extract one), read the level's pixel width/height (`LevelGeometry` accessors), collect entries, write the JSON with Jackson pretty-printing. Run it once (`mvn compile exec:java "-Dexec.mainClass=com.openggf.tools.net.TrackProfileExportTool"` with ROMs present) and CHECK IN the generated resource. If a ROM is missing locally, the tool warns and emits the tracks it can — the committed table must at minimum cover the s3k catalog (the test asserts AIZ1).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.hub.TestBundledProfileSource" test`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/tools/net/TrackProfileExportTool.java src/main/resources/net/track-validation-profiles.json src/main/java/com/openggf/net/hub/BundledProfileSource.java src/main/java/com/openggf/net/master/MasterServer.java src/test/java/com/openggf/net/hub/TestBundledProfileSource.java
git commit -m "feat(timeattack): engine-side track profile export and bundled master table

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 15: Client roster consumption

**Files:**
- Modify: `src/main/java/com/openggf/net/client/RaceClient.java` (roster packets become a typed event)
- Modify: `src/main/java/com/openggf/net/client/RemoteGhostRegistry.java` (far-player roster state)
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java` (route roster; expose far players in the HUD state)
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerHudState.java` (add `int totalPlayers`, `List<RemoteGhostRegistry.FarPlayer> farPlayers`)
- Test: `src/test/java/com/openggf/net/client/TestRosterConsumption.java`

**Interfaces:**
- Produces:
  - `RaceClient.InboundEvent` gains `record Roster(List<GhostPackets.RosterEntry> entries)` — the network thread decodes 0x03 frames (dispatch on the type byte before `decodeAggregate`; undecodable → protocol-violation close as usual).
  - `RemoteGhostRegistry`: `record FarPlayer(int slot, String displayName, String character, int cellX, int cellY, int status)`; `void onRoster(List<GhostPackets.RosterEntry> entries)` (stores latest coarse state per slot); `List<FarPlayer> farPlayers(int excludeSlot)` — roster slots that currently have NO live near playback (near players render as ghosts; far ones exist only as roster entries — main spec §4.6), names/characters joined from the RoomState roster; `reset()` also clears roster state.
  - Coordinator `pump()`: `case RaceClient.InboundEvent.Roster roster -> registry.onRoster(roster.entries());` (the record is nested in `InboundEvent` like `Control`/`GhostData` — use the qualified name everywhere; do NOT introduce a second top-level `RaceClient.Roster` type); `hudState()` carries `session.players().size()` as `totalPlayers` and `registry.farPlayers(session.localSlot())` — the standings panel's "N racing" line and a future minimap feed (rendering beyond the count line is phase-4 polish).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.client;

import com.openggf.net.protocol.ControlMessage;
import com.openggf.net.protocol.GhostPackets;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestRosterConsumption {
    @Test
    void farPlayersComeFromRosterMinusNearPlaybacks() {
        RemoteGhostRegistry registry = new RemoteGhostRegistry();
        registry.onRoomState(List.of(
                new ControlMessage.PlayerInfo(0, "fp0", "ME", "sonic", false),
                new ControlMessage.PlayerInfo(1, "fp1", "NEAR", "tails", false),
                new ControlMessage.PlayerInfo(2, "fp2", "FAR", "knuckles", false)));

        // slot 1 streams (near: has a playback); slot 2 appears only in the roster
        byte[] frames = new byte[3 * com.openggf.game.ghost.GhostFrameCodec.BYTES];
        for (int i = 0; i < 12; i += 3) {
            registry.onAggregate(new GhostPackets.Aggregate(i, List.of(
                    new GhostPackets.AggregateEntry(1, 1, i, 3, frames.clone()))));
        }
        registry.onRoster(List.of(
                new GhostPackets.RosterEntry(1, 10, 5, GhostPackets.ROSTER_STATUS_RUNNING),
                new GhostPackets.RosterEntry(2, 140, 8, GhostPackets.ROSTER_STATUS_FINISHED)));

        List<RemoteGhostRegistry.FarPlayer> far = registry.farPlayers(0);
        assertEquals(1, far.size());
        assertEquals(2, far.get(0).slot());
        assertEquals("FAR", far.get(0).displayName());
        assertEquals(140, far.get(0).cellX());
        assertEquals(GhostPackets.ROSTER_STATUS_FINISHED, far.get(0).status());
    }

    @Test
    void resetClearsRosterToo() {
        RemoteGhostRegistry registry = new RemoteGhostRegistry();
        registry.onRoomState(List.of(new ControlMessage.PlayerInfo(2, "fp2", "FAR", "knuckles", false)));
        registry.onRoster(List.of(new GhostPackets.RosterEntry(2, 1, 1, 0)));
        assertEquals(1, registry.farPlayers(0).size());
        registry.reset();
        assertTrue(registry.farPlayers(0).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.client.TestRosterConsumption" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`RemoteGhostRegistry` additions:

```java
    public record FarPlayer(int slot, String displayName, String character, int cellX, int cellY,
                            int status) {
    }

    private final Map<Integer, GhostPackets.RosterEntry> rosterState = new LinkedHashMap<>();

    public void onRoster(List<GhostPackets.RosterEntry> entries) {
        for (GhostPackets.RosterEntry entry : entries) {
            rosterState.put(entry.playerSlot(), entry);
        }
    }

    public List<FarPlayer> farPlayers(int excludeSlot) {
        List<FarPlayer> far = new ArrayList<>();
        for (Map.Entry<Integer, GhostPackets.RosterEntry> entry : rosterState.entrySet()) {
            int slot = entry.getKey();
            if (slot == excludeSlot || playbacks.containsKey(slot)) {
                continue; // near players render as ghosts, not roster rows
            }
            Roster info = roster.getOrDefault(slot, new Roster("?", "sonic"));
            far.add(new FarPlayer(slot, info.displayName(), info.character(),
                    entry.getValue().cellX(), entry.getValue().cellY(), entry.getValue().status()));
        }
        return far;
    }
```

(`reset()` gains `rosterState.clear();`.) `RaceClient` binary path: peek `packet[0]`; `TYPE_ROSTER` → `inbound.add(new Roster(GhostPackets.decodeRoster(packet)))`; `TYPE_GHOST_AGGREGATE` → existing `GhostData`; anything else → protocol violation. Coordinator/HUD-state changes per Interfaces (mechanical; reconcile with as-built phase-2 code).

- [ ] **Step 4: Run new + phase-2 client tests**

Run: `mvn "-Dtest=com.openggf.net.client.*" test`
Expected: PASS (all client suites; phase-2 `TestRemoteGhostPlayback` etc. untouched).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/client/RaceClient.java src/main/java/com/openggf/net/client/RemoteGhostRegistry.java src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java src/main/java/com/openggf/game/timeattack/mp/MultiplayerHudState.java src/test/java/com/openggf/net/client/TestRosterConsumption.java
git commit -m "feat(timeattack): roster channel consumption for far players

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 16: Paged standings + server-browser UI

**Files:**
- Modify: `src/main/java/com/openggf/net/hub/HostRoundEngine.java` (broadcast only top-N + paging support)
- Modify: `src/main/java/com/openggf/net/hub/RoomHost.java` (StandingsPageRequest / RankUpdate handling)
- Modify: `src/main/java/com/openggf/game/timeattack/mp/RaceTransport.java` + adapter (wrap `RaceConnection`)
- Modify: `src/main/java/com/openggf/game/timeattack/mp/MultiplayerRaceCoordinator.java` (around-you page auto-request + splice)
- Create: `src/main/java/com/openggf/game/timeattack/mp/ServerBrowserScreen.java`
- Modify: `src/main/java/com/openggf/game/timeattack/TimeAttackMenu.java` (BROWSE entry → server browser)
- Modify: `src/main/java/com/openggf/Engine.java` (master connect/browse/join/host-announce flow)
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java` + `ConfigCatalog.java` + `CONFIGURATION.md` (master address keys)
- Test: `src/test/java/com/openggf/net/hub/TestStandingsPaging.java`

**Interfaces:**
- Main spec §6.3: "the standings panel (top 10 + 5 rows around you + your rank — never the full list pushed to hundreds of clients; full list is paged on demand)."
- Produces:
  - `HostRoundEngine`: `STANDINGS_BROADCAST_CAP = 10`; `StandingsDelta` broadcasts carry only the top `STANDINGS_BROADCAST_CAP` rows. New `List<ControlMessage.StandingsRow> page(int page, int pageSize)` and `int totalPages(int pageSize)` for on-demand paging. On an accepted finish whose rank exceeds the broadcast cap, the room UNICASTS `RankUpdate(rank, timeFrames)` to that finisher (so a 40th-place player still sees their standing) in addition to the capped `StandingsDelta` — expose the finisher's slot + rank from `onAttemptFinish` so `RoomHost` can unicast.
  - `RoomHost`: dispatch `StandingsPageRequest` → `round.page(...)` → unicast `StandingsPage`; forward the round engine's `RankUpdate` to the owning member. Rate-limit page requests (1 / 2 s / member).
  - **Around-you rows (the "5 rows around you" of main spec §6.3):** when a `RankUpdate` (or a capped `StandingsDelta`) leaves the local player's rank outside the broadcast cap, `MultiplayerRaceCoordinator` auto-sends `StandingsPageRequest(localRank / pageSize)` at most once per 2 s (matching the room's rate limit) and splices the returned `StandingsPage` rows into the standings list exposed by `hudState()`: the top-10 broadcast rows, then the ≤ 5 rows around the local rank (deduped by slot). No new HUD-state field — the HUD renders the single list, and the rank discontinuity between row 10 and the spliced block reads as the separator. Without this, a 40th-place player sees only their bare rank number, dropping a spec'd panel element.
  - Config keys (enum + `ConfigCatalog` meta + CONFIGURATION.md row each — `TestConfigCatalog` gates this): `TIME_ATTACK_NET_MASTER_URL` → `timeAttack.net.masterUrl`, STRING, default `""` (e.g. `wss://master.example:27900/master`); `TIME_ATTACK_NET_MASTER_TRUST_INSECURE` → `timeAttack.net.masterTrustInsecure`, BOOL, default `false` (dev-only; when true the client builds a trust-all `SSLContext` — logs a loud warning; production stays false and uses the JDK trust store).
  - `ServerBrowserScreen` (game.timeattack.mp): master-title sub-mode; on entry `MasterClient.connect(configuredMasterUrl, identity, displayName, fingerprint, insecureContextOrNull)`; lists rooms via `listRooms(gameFilter=selectedGame, page)` refreshed every ~2 s and on input; rows show name, game, players/max, routing badge, "unverified" label (main spec §2). Lobby and standings render the `(new)` badge from `PlayerInfo.newPlayer()` (Task 8's hook) and disambiguate duplicate display names with a fingerprint-suffix tag — `name#` + the first 4 hex chars of the fingerprint — security spec §4/§9. Actions: CREATE (→ RoomCreate with the phase-2 host config UI, then for RELAY the coordinator attaches to the returned room via `MasterClient`; for DIRECT it also starts a local `RaceHostServer` + binds `HostMasterLink` for fallback and sends heartbeats), JOIN (→ `masterClient.joinRoom(...)` → wraps the `RaceConnection` in the `RaceTransport` adapter → into the existing phase-2 `MultiplayerRaceCoordinator` + `RaceLobbyScreen`), REFRESH, BACK. Connection failures toast and return to the menu (main spec §9).
  - `RaceTransport` adapter change: wrap `RaceConnection` (Task 13) instead of `RaceClient` concretely — one-line generalization; relay and direct rooms are then identical to the coordinator.
- `TimeAttackMenu` mode row gains `BROWSE` (master server) beside the phase-2 `SOLO`/`HOST LAN`/`JOIN LAN` (those stay for zero-infrastructure LAN play).

- [ ] **Step 1: Write the failing test** (the paging/rank logic is the testable core; the UI is compile-plus-manual as in phase 2)

```java
package com.openggf.net.hub;

import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestStandingsPaging {
    private long now = 1_000_000;
    private final List<ControlMessage> broadcast = new ArrayList<>();
    private HostRoundEngine engine;

    @BeforeEach
    void setUp() {
        engine = new HostRoundEngine(() -> now, broadcast::add);
        engine.startRound(new ControlMessage.RoundConfig("s3k", 0, 0, 600, "OPEN", null));
        now += HostRoundEngine.COUNTDOWN_MILLIS;
        engine.onTick();
    }

    private static ControlMessage.AttemptFinish finish(int frames) {
        return new ControlMessage.AttemptFinish(1, frames, 5, 5 + frames, "ab".repeat(32),
                "cd".repeat(32), null);
    }

    @Test
    void broadcastsOnlyTopCapButPagesFullList() {
        for (int slot = 0; slot < 25; slot++) {
            engine.onAttemptFinish(slot, "P" + slot, "sonic", finish(1000 + slot), false);
        }
        ControlMessage.StandingsDelta lastDelta = broadcast.stream()
                .filter(m -> m instanceof ControlMessage.StandingsDelta)
                .map(m -> (ControlMessage.StandingsDelta) m)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(HostRoundEngine.STANDINGS_BROADCAST_CAP, lastDelta.rows().size());
        assertEquals("P0", lastDelta.rows().get(0).displayName()); // fastest (1000 frames)

        assertEquals(3, engine.totalPages(10)); // 25 rows / 10
        List<ControlMessage.StandingsRow> page2 = engine.page(2, 10);
        assertEquals(5, page2.size());
        assertEquals(21, page2.get(0).rank()); // 1-based ranks continue across pages
    }

    @Test
    void slowFinisherOutsideCapGetsRankUpdate() {
        for (int slot = 0; slot < 15; slot++) {
            engine.onAttemptFinish(slot, "P" + slot, "sonic", finish(1000 + slot), false);
        }
        // slot 20 finishes slowest → rank 16, outside the top-10 broadcast
        HostRoundEngine.FinishOutcome outcome =
                engine.onAttemptFinish(20, "LATE", "sonic", finish(9999), false);
        assertNotNull(outcome);
        assertEquals(16, outcome.rank());
        assertTrue(outcome.outsideBroadcastCap());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.hub.TestStandingsPaging" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`HostRoundEngine`: add `STANDINGS_BROADCAST_CAP = 10`; `onAttemptFinish` now returns `FinishOutcome` (`record FinishOutcome(int slot, int rank, boolean outsideBroadcastCap)`, or null when the finish was ignored — update phase-2 callers, which ignored the void return); the broadcast `StandingsDelta` uses `standings().subList(0, min(cap, size))`; add `page(page, pageSize)` (sublist of full `standings()`) and `totalPages(pageSize)`. `RoomHost.dispatch`: `AttemptFinish` → capture the outcome; if `outcome != null && outcome.outsideBroadcastCap()` unicast `RankUpdate(outcome.rank(), finish.timeFrames())` to that member; add `StandingsPageRequest` handling (rate-limited) → unicast `StandingsPage`. UI + config + Engine flow per Interfaces (mechanical, phase-2 idioms). `RaceTransport` adapter generalized to `RaceConnection`.

- [ ] **Step 4: Run paging + phase-2 round tests + config catalog**

Run: `mvn "-Dtest=com.openggf.net.hub.TestStandingsPaging+com.openggf.net.hub.TestHostRoundEngine+com.openggf.configuration.TestConfigCatalog" test`
Expected: PASS (phase-2 `TestHostRoundEngine` updated for the `FinishOutcome` return).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/hub/HostRoundEngine.java src/main/java/com/openggf/net/hub/RoomHost.java src/main/java/com/openggf/game/timeattack/mp/RaceTransport.java src/main/java/com/openggf/game/timeattack/mp/ServerBrowserScreen.java src/main/java/com/openggf/game/timeattack/TimeAttackMenu.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/ConfigCatalog.java CONFIGURATION.md src/test/java/com/openggf/net/hub/TestStandingsPaging.java
git commit -m "feat(timeattack): paged standings, rank updates, and server browser UI

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a"
```

---

### Task 17: GhostLoadTestTool — scale gate + adversarial modes

**Files:**
- Create: `src/main/java/com/openggf/tools/net/GhostLoadTestTool.java`
- Create: `src/main/java/com/openggf/tools/net/BotClient.java`
- Test: `src/test/java/com/openggf/tools/net/TestGhostLoadTest.java`

**Interfaces:**
- Main spec §10 (Load): "spawns N headless bot clients replaying recorded ghost files against a room; asserts hub tick budget, queue depths, and egress at N = 32 / 128 / 256 — the gate for the scale target, runnable in CI at reduced N and on demand at full N." Security spec §10: adversarial modes.
- Produces:
  - `BotClient` — a headless `MasterClient`+`RaceConnection` driver (or, for pure-hub benchmarks, a direct in-JVM `RoomHost` attachment bypassing sockets — the tool supports both; the in-JVM mode is what CI runs for determinism/speed): joins a room, completes the handshake, and each simulated frame publishes a ghost frame from a synthetic path (a bot needs no ROM — it replays a numeric path or a loaded `.ggfghost` file, positions only). Modes (`enum Behavior`): `NORMAL`, `TELEPORT` (jumps > speed cap — must trip `speed`), `PACING_SLOW` (advances frameIndex below 60/s — must trip `pacing`), `OVERSIZED` (attempts a > cap binary — rejected pre-parse), `FLOOD` (exceeds the message rate — connection closed), `HANDSHAKE_ABANDON` (connects, never completes handshake — admission-timeout closed), `ADVERSARIAL_MIX` (a mixed room: majority `NORMAL` plus one bot of each adversarial mode — the composition the CI test drives).
  - `GhostLoadTestTool` (tools-style main): `run(int n, Behavior mix, Duration)` spins N `BotClient`s against one in-JVM `MasterServer` relay room, drives ~M simulated hub ticks, and returns a `LoadReport(double meanTickMillis, double p99TickMillis, long maxQueuedBytesAnyClient, long healthyClientsFinished, long adversariesSanctioned)`. Asserts (the SCALE GATE): mean hub tick < 50 ms (keeps up with 20 Hz) and p99 < the tick interval at the target N; healthy clients unaffected by adversaries (security spec §10). CLI: `--n 256 --duration 30 --mix normal|adversarial`.
- CI runs `TestGhostLoadTest` at N=32 (fast, deterministic, in-JVM ticks driven synchronously); the full N=128/256 runs are on-demand (documented in the test's Javadoc and AGENTS.md). The test asserts the budget at N=32 and that every adversarial behavior fires its expected violation while healthy bots keep finishing.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.tools.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class TestGhostLoadTest {
    @Test
    void thirtyTwoBotsKeepHubWithinTickBudget(@TempDir Path dir) throws Exception {
        GhostLoadTestTool.LoadReport report = GhostLoadTestTool.run(32,
                GhostLoadTestTool.Behavior.NORMAL, Duration.ofSeconds(5), dir);
        assertTrue(report.meanTickMillis() < 50.0,
                "mean hub tick " + report.meanTickMillis() + "ms must stay under the 20Hz budget");
        assertTrue(report.p99TickMillis() < 50.0, "p99 tick " + report.p99TickMillis() + "ms");
        assertTrue(report.healthyClientsFinished() >= 30, "healthy bots should finish");
    }

    @Test
    void adversariesAreCaughtAndHealthyClientsUnaffected(@TempDir Path dir) throws Exception {
        // Mixed room: some NORMAL, some of each adversarial behavior.
        GhostLoadTestTool.LoadReport report = GhostLoadTestTool.run(16,
                GhostLoadTestTool.Behavior.ADVERSARIAL_MIX, Duration.ofSeconds(5), dir);
        assertTrue(report.adversariesSanctioned() > 0, "adversaries must trip the violation ladder");
        assertTrue(report.healthyClientsFinished() > 0, "healthy clients race on unaffected (security §10)");
        assertTrue(report.meanTickMillis() < 50.0, "adversaries must not blow the tick budget");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.tools.net.TestGhostLoadTest" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`GhostLoadTestTool` — in-JVM harness: start `MasterServer.start(TestMasterServer.testConfig(), dir)` (attack mode OFF; identity PoW at 8 bits so 32 bots don't burn seconds), one established host creates a RELAY room, N `BotClient`s join via `MasterClient` (loopback ws). Drive time by advancing a shared clock supplier the tool controls and calling the relay room's tick through `RelayRoomManager.tickAll()` on `Runnable::run` loops (the tool constructs the `MasterServer` with direct executors in a test-loads mode — expose a `MasterServer.startForLoadTest(config, dir)` that uses `Runnable::run` room loops and a manually-pumped tick so the harness is deterministic and fast). Measure wall time per `tickAll()` call for the tick-budget stats. Each `BotClient` per simulated frame calls its `GhostStreamPublisher.onFrame(syntheticFrame(t))`; adversaries deviate per `Behavior`. `LoadReport` aggregates. `BotClient` reuses `GhostStreamPublisher` + `ClientHandshake` + `MasterClient`; no engine, no ROM (fence-safe — it lives in `tools.net`, which is engine-side but imports only net + JDK; keep it out of the fence assertion by NOT importing engine classes — it is a tool, so a stray engine import would not break the master, but Task 18's rule covers `net.*` only, and this is `tools.net`, deliberately outside).

Note on realism vs CI: the in-JVM synchronous-tick harness measures the hub's per-tick CPU cost (the real bottleneck at scale — aggregation + relevance + roster composition), NOT socket throughput; that is the correct gate for the scale TARGET (§4.4's budget is dominated by per-tick composition). Socket-level egress at full N is an on-demand manual run against a deployed master, documented in the tool's `--help` and AGENTS.md; the tool logs (never silently caps) when it runs reduced-N or in-JVM so results are not mistaken for a full-scale socket test.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.tools.net.TestGhostLoadTest" test`
Expected: PASS (2 tests; ~10–20 s).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/tools/net/GhostLoadTestTool.java src/main/java/com/openggf/tools/net/BotClient.java src/main/java/com/openggf/net/master/MasterServer.java src/test/java/com/openggf/tools/net/TestGhostLoadTest.java
git commit -m "feat(timeattack): ghost load-test tool with scale gate and adversarial modes

Changelog: n/a: phase-3 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 18: Protocol fuzzer + engine-free fence + end-to-end + docs

**Files:**
- Create: `src/test/java/com/openggf/net/protocol/TestProtocolFuzzing.java`
- Modify: `src/test/java/com/openggf/tests/TestNetIsolationRules.java` (add `net.master` to the fenced set)
- Create: `src/test/java/com/openggf/net/TestMasterEndToEnd.java`
- Modify: `CLAUDE.md` + `AGENTS.md` (master server section) + `README.md` (staged at merge time per repo policy)

**Interfaces:**
- Security spec §10 (Protocol fuzzing in CI): a frame-level fuzzer against the decoders asserting no crash/hang/allocation blowup. Deterministic seed (no `Math.random` — the fuzzer uses a fixed-seed `java.util.Random`, allowed in tests; the plan's Date/random restriction is about WORKFLOW scripts, not test code).
- `TestProtocolFuzzing`:
  - Random + mutation-based bytes into `GhostPackets.decodeFrames/decodeAggregate/decodeRoster/decodeRelayGuestBinary` and random strings into `ControlCodec.decode`: every input either returns a value or throws `ProtocolViolationException`/`IOException` — NEVER `OutOfMemoryError`, `NegativeArraySizeException`, `ArrayIndexOutOfBoundsException`, or hangs (each call wrapped with an assertion on the throwable type and a per-call time budget).
  - Mutation pass: take a valid packet of each type, flip every single byte across its range, assert the same safety property.
- `TestNetIsolationRules`: add `"com.openggf.net.master.."` to the fenced package list — the master must import no engine classes (only JDK/Netty/Jackson/sqlite-jdbc/`com.openggf.net..`/the two ghost codec classes). `tools.net` is deliberately NOT fenced (it is an engine-side tool), but `BotClient`/`GhostLoadTestTool` must still avoid engine imports in practice; add a focused assertion that `com.openggf.tools.net..` does not import LWJGL (a lighter guard, not the full fence).
- `TestMasterEndToEnd` (the phase-3 acceptance gate, main spec §10 integration at scale-in-miniature): one JVM, real loopback sockets, plaintext-test master. Establish a host identity, host creates a RELAY room via `MasterClient`, THREE guests browse + join, a round runs (countdown + short window), each guest streams a short attempt and finishes with distinct times; assert: browser lists the room with correct player count; all guests receive relevance-filtered aggregates of each other; roster arrives; standings rank the fastest first and a `RankUpdate`/`StandingsPage` round-trips; a deliberately teleporting fourth guest is sanctioned (kick) without disturbing the other three; host disconnect dissolves the room and the browser no longer lists it.

- [ ] **Step 1: Write the fuzzer**

```java
package com.openggf.net.protocol;

import com.openggf.game.ghost.GhostFrameCodec;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TestProtocolFuzzing {
    private static final long SEED = 0xC0FFEE_1234_5678L; // fixed: reproducible

    private static void assertSafe(Runnable decode) {
        try {
            decode.run();
        } catch (ProtocolViolationException | IllegalArgumentException expected) {
            // acceptable: rejected cleanly
        } catch (Throwable fatal) {
            fail("decoder threw a non-protocol error: " + fatal);
        }
    }

    @Test
    void randomBytesNeverCrashBinaryDecoders() {
        Random random = new Random(SEED);
        for (int i = 0; i < 20_000; i++) {
            byte[] bytes = new byte[random.nextInt(Protocol.MAX_BINARY_BYTES + 16)];
            random.nextBytes(bytes);
            assertSafe(() -> GhostPackets.decodeFrames(bytes));
            assertSafe(() -> GhostPackets.decodeAggregate(bytes));
            assertSafe(() -> GhostPackets.decodeRoster(bytes));
            assertSafe(() -> GhostPackets.decodeRelayGuestBinary(bytes));
        }
    }

    @Test
    void randomStringsNeverCrashControlDecoder() {
        Random random = new Random(SEED);
        for (int i = 0; i < 20_000; i++) {
            byte[] bytes = new byte[random.nextInt(512)];
            random.nextBytes(bytes);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            assertSafe(() -> ControlCodec.decode(text));
        }
    }

    @Test
    void singleByteMutationsOfValidPacketsStaySafe() {
        byte[] frames = GhostPackets.encodeFrames(1, 0, new byte[GhostFrameCodec.BYTES]);
        byte[] roster = GhostPackets.encodeRoster(java.util.List.of(
                new GhostPackets.RosterEntry(0, 1, 2, 0)));
        byte[] wrapped = GhostPackets.encodeRelayGuestBinary(7, frames);
        for (byte[] valid : new byte[][] {frames, roster, wrapped}) {
            for (int index = 0; index < valid.length; index++) {
                for (int value = 0; value < 256; value++) {
                    byte[] mutated = valid.clone();
                    mutated[index] = (byte) value;
                    assertSafe(() -> GhostPackets.decodeFrames(mutated));
                    assertSafe(() -> GhostPackets.decodeAggregate(mutated));
                    assertSafe(() -> GhostPackets.decodeRoster(mutated));
                    assertSafe(() -> GhostPackets.decodeRelayGuestBinary(mutated));
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the fuzzer to verify decoders survive**

Run: `mvn "-Dtest=com.openggf.net.protocol.TestProtocolFuzzing" test`
Expected: PASS. A failure means a decoder allocates from an unvalidated count or indexes past the buffer — fix the DECODER (add the missing bound), never the test.

- [ ] **Step 3: Extend the fence + write the end-to-end test**

`TestNetIsolationRules`: add `"com.openggf.net.master.."` to the `resideInAnyPackage(...)` list and, if `sqlite-jdbc`'s package needs allowing, it is a third-party import (not `com.openggf`), so the existing predicate already permits it; verify the allowlist predicate still only special-cases the two ghost codec classes. Add the lighter `tools.net` LWJGL guard as a second `@ArchTest`.

`TestMasterEndToEnd` — full scenario per Interfaces, structured like phase-2's `TestDirectConnectEndToEnd` (poll-until helpers, `@Timeout(120)`), using `MasterClient` + `RaceConnection`. Drive the relay room's ticks via the master's own scheduler (real timing) or the load-test executor hook; assert every bullet.

- [ ] **Step 4: Run the fence, end-to-end, and full net suite**

Run: `mvn "-Dtest=com.openggf.tests.TestNetIsolationRules+com.openggf.net.TestMasterEndToEnd" test`
Expected: PASS. Then the full sweep below.

- [ ] **Step 5: Docs + full verification**

- `CLAUDE.md` + `AGENTS.md`: add a "Master server (phase 3)" subsection — `com.openggf.net.master` (engine-free, fenced), `MasterServerMain` entry point + `master.yaml` config, SQLite identity store + trust ladder, relay rooms reuse `RoomHost`/`GhostHub` verbatim, `GhostLoadTestTool` scale gate + how to run full-N on demand, TLS requirement, admin endpoint.
- CHANGELOG already updated in Task 1.
- README release-notes line staged at merge time (repo policy — the branch's merge target is the owner's call).

Full sweep:

```bash
mvn test
```

Expected: full suite green — all `com.openggf.net.*` and `com.openggf.net.master.*` suites, `TestProtocolFuzzing`, `TestNetIsolationRules`, `TestGhostLoadTest` (N=32), `TestMasterEndToEnd`, `TestConfigCatalog`, phase-1/2 time-attack suites, and the must-keep-green S3K set (`TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`). Trace suites unaffected (no gameplay-physics changes). Deploying a real `wss` master (certs, DNS, CDN) and a full-N socket load run are the release-gate follow-ups, not part of this task.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/openggf/net/protocol/TestProtocolFuzzing.java src/test/java/com/openggf/tests/TestNetIsolationRules.java src/test/java/com/openggf/net/TestMasterEndToEnd.java CLAUDE.md AGENTS.md
git commit -m "test(timeattack): protocol fuzzer, master fence, and master end-to-end round

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

- Task 1 (protocol) unblocks everything. Tasks 2, 5 are independent leaves.
- Hub scale chain: 2 → 3 → 4 (sequential, all in `net.hub`).
- Security chain: 5 → 6 → 7 (identity/store/ladder), then 8 (config+registry+RoomHost hooks) → 9 (broker) → 10 (relay manager). Task 11 (tunnel) needs 1 + 9 because it implements `RoomBroker.DirectTunnelDirectory`. Task 12 (master server) needs 10 + 11 because it wires both relay rooms and direct-fallback tunnels.
- Task 13 (MasterClient) needs 9–12. Task 14 (profiles) needs 1 + the engine catalog (phase 1). Task 15 (roster client) needs 1 + phase-2 client. Task 16 (browser UI + paging) needs 13. Task 17 (load test) needs 12–13. Task 18 last.
- Engine-touching tasks (14 export tool, 15/16 glue, 17 bot) need the phase-1/2 code on the branch; the pure `net.*` tasks (1–13, 18 fuzzer/fence) do not and can proceed even if phase-1/2 engine glue is still settling.

## Deferred-to-phase-4 / post-v1 checklist (recorded so nothing silently drops)

- `openggf-verifier` worker process, verification job queue, verdict signing, `verified` flag flipping true, verified-room TRUSTED gate, `RecordingRequest` + HTTPS `PUT /recordings/{hash}` upload, casual-room spot-checking (security spec §6, §11 "4+").
- Podium, track vote, spectate pan, minimap rendering over the roster channel (main spec §11 phase 4).
- Community moderation (reports/reviewer queues) beyond the operator admin endpoint (security spec §9, §12).
- Postgres `IdentityStore` implementation (interface seam is in place — security spec §5).
- Federated/multi-master; OAuth/account binding atop keypairs; anti-cheat analytics over stored recordings (security spec §12).
