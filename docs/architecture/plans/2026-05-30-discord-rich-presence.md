# Discord Rich Presence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in Discord Rich Presence for OpenGGF that reports menus and gameplay context such as game, character/team, zone/act, and timer.

**Architecture:** Add a small `com.openggf.integration.presence` layer that snapshots existing engine state, formats Discord activity text, throttles updates, and publishes through a hand-rolled Discord IPC client. Gameplay/object code remains read-only from the presence system; failures disable presence for the run without affecting gameplay.

**Tech Stack:** Java 21, JUnit 5, Jackson, Discord local IPC protocol over platform pipes/sockets.

---

### Task 1: Configuration And Constants

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/resources/config.json`
- Modify: `CONFIGURATION.md`
- Create: `src/main/java/com/openggf/integration/presence/discord/DiscordPresenceConstants.java`
- Test: `src/test/java/com/openggf/configuration/TestSonicConfigurationFileBootstrap.java`

- [ ] Add opt-in configuration keys: `DISCORD_RICH_PRESENCE_ENABLED`, `DISCORD_RICH_PRESENCE_SHOW_TIMER`, and `DISCORD_RICH_PRESENCE_SHOW_ZONE`.
- [ ] Keep Discord application id fixed in code as `1510395080652099754`; do not expose it in `config.json`.
- [ ] Verify sparse config bootstrapping persists the new defaults.

### Task 2: Presence Snapshot Formatting

**Files:**
- Create: `src/main/java/com/openggf/integration/presence/PresenceMode.java`
- Create: `src/main/java/com/openggf/integration/presence/PresenceSnapshot.java`
- Create: `src/main/java/com/openggf/integration/presence/PresencePayload.java`
- Create: `src/main/java/com/openggf/integration/presence/PresenceFormatter.java`
- Test: `src/test/java/com/openggf/integration/presence/TestPresenceFormatter.java`

- [ ] Test gameplay formatting includes `OpenGGF` in details and puts zone/act/team/timer in state.
- [ ] Test menu formatting returns `OpenGGF - In Menus`.
- [ ] Test timer and zone visibility flags suppress only their respective text.

### Task 3: Presence Manager

**Files:**
- Create: `src/main/java/com/openggf/integration/presence/PresenceClient.java`
- Create: `src/main/java/com/openggf/integration/presence/NoOpPresenceClient.java`
- Create: `src/main/java/com/openggf/integration/presence/PresenceSnapshotProvider.java`
- Create: `src/main/java/com/openggf/integration/presence/PresenceManager.java`
- Test: `src/test/java/com/openggf/integration/presence/TestPresenceManager.java`

- [ ] Test disabled config never connects or updates.
- [ ] Test first enabled tick connects and publishes menu presence.
- [ ] Test meaningful snapshot changes publish immediately.
- [ ] Test timer-only changes are throttled to 15 seconds.
- [ ] Test client failures close the client and disable presence for the run.

### Task 4: Runtime Snapshot Provider

**Files:**
- Create: `src/main/java/com/openggf/integration/presence/RuntimePresenceSnapshotProvider.java`
- Test: `src/test/java/com/openggf/integration/presence/TestRuntimePresenceSnapshotProvider.java`

- [ ] Read game mode from `GameLoop`.
- [ ] For menus, return a menu snapshot including master title screen.
- [ ] For gameplay, read game id from `WorldSession`, zone/act from `WorldSession`, zone name from `ZoneRegistry`, team from `ActiveGameplayTeamResolver`, and timer from `LevelState`.
- [ ] Treat missing runtime state as menu/unavailable rather than throwing.

### Task 5: Discord IPC Transport And Client

**Files:**
- Create: `src/main/java/com/openggf/integration/presence/discord/DiscordIpcTransport.java`
- Create: `src/main/java/com/openggf/integration/presence/discord/DiscordIpcTransportFactory.java`
- Create: `src/main/java/com/openggf/integration/presence/discord/DiscordIpcPresenceClient.java`
- Test: `src/test/java/com/openggf/integration/presence/discord/TestDiscordIpcPresenceClient.java`

- [ ] Test handshake sends opcode `0` with version `1` and application id `1510395080652099754`.
- [ ] Test activity update sends `SET_ACTIVITY` with a nonce and formatted activity.
- [ ] Test clear sends `SET_ACTIVITY` with null activity.
- [ ] Test close closes the transport.

### Task 6: Engine Wiring

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Test: `src/test/java/com/openggf/integration/presence/TestPresenceLifecycle.java`

- [ ] Construct presence manager with config, runtime snapshot provider, formatter, and Discord IPC client factory.
- [ ] Tick presence once per game-loop step after runtime bindings refresh.
- [ ] Close presence during engine cleanup.
- [ ] Ensure disabled config path is a no-op.

### Task 7: Verification

**Commands:**
- `mvn "-Dtest=TestPresenceFormatter,TestPresenceManager,TestDiscordIpcPresenceClient" test`
- `mvn "-Dtest=TestSonicConfigurationFileBootstrap" test`
- `mvn test`

- [ ] Confirm all focused tests pass.
- [ ] Confirm the full default suite passes.
- [ ] Manual smoke test with Discord running and `DISCORD_RICH_PRESENCE_ENABLED=true`.
