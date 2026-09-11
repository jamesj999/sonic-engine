# Session-Aware Character Resolution — Fix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all code paths that read `MAIN_CHARACTER_CODE` / `SIDEKICK_CHARACTER_CODE` from config directly instead of checking the Data Select session's `SelectedTeam` first, causing wrong character behavior when Knuckles (or Tails) is selected via the data select screen.

**Architecture:** Add a `resolvePlayerCharacter()` method to the existing `ActiveGameplayTeamResolver` so there is one authoritative session-aware path for both the `String` character code and the `PlayerCharacter` enum. Then update all broken call sites to use the resolver instead of reading config directly.

**Tech Stack:** Java 21, JUnit 5

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `src/main/java/com/openggf/game/session/ActiveGameplayTeamResolver.java` | Modify | Add `resolvePlayerCharacter()` and `resolveSidekicks()` |
| `src/test/java/com/openggf/game/session/TestActiveGameplayTeamResolver.java` | Create | Unit tests for the resolver |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java` | Modify | Use resolver in `getPlayerCharacter()` |
| `src/main/java/com/openggf/game/sonic3k/runtime/S3kRuntimeStates.java` | Modify | Use resolver in fallback path |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java` | Modify | Use resolver in `resolveLifeIconAddr()` |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java` | Modify | Use resolver in `shouldSuppressInitialTitleCard()` |
| `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java` | Modify | Use resolver in `getPlayerCharacter()` |
| `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java` | Modify | Use resolver in `setupPlayers()` |
| `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java` | Modify | Use resolver in `enter()` |
| `src/main/java/com/openggf/game/sonic3k/objects/AizRideVineObjectInstance.java` | Modify | Use resolver in `resolveMainPlayer()` |
| `src/main/java/com/openggf/game/sonic3k/objects/AizGiantRideVineObjectInstance.java` | Modify | Use resolver in `resolveMainPlayer()` |
| `src/main/java/com/openggf/level/LevelDebugRenderer.java` | Modify | Use resolver (2 call sites) |
| `src/main/java/com/openggf/debug/DebugRenderer.java` | Modify | Use resolver in `getMainCharacterCode()` |

---

### Task 1: Add `resolvePlayerCharacter()` and `resolveSidekicks()` to `ActiveGameplayTeamResolver`

**Files:**
- Modify: `src/main/java/com/openggf/game/session/ActiveGameplayTeamResolver.java`
- Create: `src/test/java/com/openggf/game/session/TestActiveGameplayTeamResolver.java`

- [ ] **Step 1: Write failing tests for `resolvePlayerCharacter()`**

Create `src/test/java/com/openggf/game/session/TestActiveGameplayTeamResolver.java`:

```java
package com.openggf.game.session;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameModule;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TestActiveGameplayTeamResolver {

    private SonicConfigurationService config;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.getInstance();
        config.resetState();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        config.resetState();
    }

    // --- resolveMainCharacterCode ---

    @Test
    void resolveMainCharacterCode_noSession_returnsConfig() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        assertEquals("knuckles", ActiveGameplayTeamResolver.resolveMainCharacterCode(config));
    }

    @Test
    void resolveMainCharacterCode_withSession_prefersSessionOverConfig() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        SessionManager.openGameplaySession(mock(GameModule.class),
                SaveSessionContext.noSave("s3k", new SelectedTeam("knuckles", List.of()), 0, 0));
        assertEquals("knuckles", ActiveGameplayTeamResolver.resolveMainCharacterCode(config));
    }

    @Test
    void resolveMainCharacterCode_nullConfig_returnsSonic() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "");
        assertEquals("sonic", ActiveGameplayTeamResolver.resolveMainCharacterCode(config));
    }

    // --- resolvePlayerCharacter ---

    @Test
    void resolvePlayerCharacter_noSession_sonicWithTails_returnsSonicAndTails() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        assertEquals(PlayerCharacter.SONIC_AND_TAILS,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_noSession_sonicAlone_returnsSonicAlone() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        assertEquals(PlayerCharacter.SONIC_ALONE,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_noSession_knuckles_returnsKnuckles() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        assertEquals(PlayerCharacter.KNUCKLES,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_noSession_tails_returnsTailsAlone() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");
        assertEquals(PlayerCharacter.TAILS_ALONE,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_sessionKnuckles_configSonic_returnsKnuckles() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        SessionManager.openGameplaySession(mock(GameModule.class),
                SaveSessionContext.noSave("s3k", new SelectedTeam("knuckles", List.of()), 0, 0));
        assertEquals(PlayerCharacter.KNUCKLES,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_sessionSonicWithTails_configKnuckles_returnsSonicAndTails() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        SessionManager.openGameplaySession(mock(GameModule.class),
                SaveSessionContext.noSave("s3k",
                        new SelectedTeam("sonic", List.of("tails")), 0, 0));
        assertEquals(PlayerCharacter.SONIC_AND_TAILS,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_sessionTails_configSonic_returnsTailsAlone() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        SessionManager.openGameplaySession(mock(GameModule.class),
                SaveSessionContext.noSave("s3k",
                        new SelectedTeam("tails", List.of()), 0, 0));
        assertEquals(PlayerCharacter.TAILS_ALONE,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_sessionSonicAlone_configSonicAndTails_returnsSonicAlone() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        SessionManager.openGameplaySession(mock(GameModule.class),
                SaveSessionContext.noSave("s3k",
                        new SelectedTeam("sonic", List.of()), 0, 0));
        assertEquals(PlayerCharacter.SONIC_ALONE,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

Run: `mvn test -pl . -Dtest=TestActiveGameplayTeamResolver -Dexec.classpathScope=test`
Expected: Compilation fails — `resolvePlayerCharacter` method does not exist.

- [ ] **Step 3: Implement `resolvePlayerCharacter()` and `resolveSidekicks()`**

In `src/main/java/com/openggf/game/session/ActiveGameplayTeamResolver.java`, add two new methods after the existing `resolveMainCharacterCode()`:

```java
    /**
     * Returns the {@link PlayerCharacter} for the current gameplay session.
     * Checks session-owned team first (Data Select), falls back to config.
     */
    public static PlayerCharacter resolvePlayerCharacter(SonicConfigurationService configService) {
        String mainCode = resolveMainCharacterCode(configService);
        if ("knuckles".equalsIgnoreCase(mainCode)) {
            return PlayerCharacter.KNUCKLES;
        }
        if ("tails".equalsIgnoreCase(mainCode)) {
            return PlayerCharacter.TAILS_ALONE;
        }
        // Sonic — check sidekick to distinguish SONIC_ALONE vs SONIC_AND_TAILS
        List<String> sidekicks = resolveSidekicks(configService);
        if (sidekicks.isEmpty()) {
            return PlayerCharacter.SONIC_ALONE;
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }

    /**
     * Returns the sidekick list for the current gameplay session.
     * Checks session-owned team first (Data Select), falls back to config.
     */
    public static List<String> resolveSidekicks(SonicConfigurationService configService) {
        WorldSession worldSession = SessionManager.getCurrentWorldSession();
        if (worldSession != null
                && worldSession.getSaveSessionContext() != null
                && worldSession.getSaveSessionContext().selectedTeam() != null) {
            return worldSession.getSaveSessionContext().selectedTeam().sidekicks();
        }
        String sidekickCode = configService.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        if (sidekickCode == null || sidekickCode.isBlank()) {
            return List.of();
        }
        return List.of(sidekickCode);
    }
```

Add the necessary imports at the top of `ActiveGameplayTeamResolver.java`:

```java
import com.openggf.game.PlayerCharacter;
import java.util.List;
```

- [ ] **Step 4: Run tests — expect all pass**

Run: `mvn test -Dtest=TestActiveGameplayTeamResolver`
Expected: All 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/session/ActiveGameplayTeamResolver.java \
        src/test/java/com/openggf/game/session/TestActiveGameplayTeamResolver.java
git commit -m "feat: add resolvePlayerCharacter() and resolveSidekicks() to ActiveGameplayTeamResolver

Single authoritative session-aware resolution for PlayerCharacter enum
and sidekick list. Checks SaveSessionContext.selectedTeam() first, falls
back to MAIN_CHARACTER_CODE / SIDEKICK_CHARACTER_CODE config."
```

---

### Task 2: Fix `Sonic3kLevelEventManager.getPlayerCharacter()` — the cascade root

This is the most impactful fix. This method feeds the character into ALL zone runtime states and is called by `LevelManager` for water loading.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java:94-110`

- [ ] **Step 1: Run existing S3K tests to establish baseline**

Run: `mvn test -Dtest=TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestS3kRuntimeStateReadGuard`
Expected: All pass.

- [ ] **Step 2: Replace config-only resolution with resolver**

In `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`, replace `getPlayerCharacter()` (lines 93-110):

Old:
```java
    @Override
    public PlayerCharacter getPlayerCharacter() {
        // Resolve from config — matches ROM's Player_mode variable
        String mainChar = GameServices.configuration()
                .getString(com.openggf.configuration.SonicConfiguration.MAIN_CHARACTER_CODE);
        if ("knuckles".equalsIgnoreCase(mainChar)) {
            return PlayerCharacter.KNUCKLES;
        } else if ("tails".equalsIgnoreCase(mainChar)) {
            return PlayerCharacter.TAILS_ALONE;
        }
        // Check for sidekick config to distinguish SONIC_ALONE vs SONIC_AND_TAILS
        String sidekick = GameServices.configuration()
                .getString(com.openggf.configuration.SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        if (sidekick == null || sidekick.isBlank()) {
            return PlayerCharacter.SONIC_ALONE;
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }
```

New:
```java
    @Override
    public PlayerCharacter getPlayerCharacter() {
        return ActiveGameplayTeamResolver.resolvePlayerCharacter(GameServices.configuration());
    }
```

Add import at top of file:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 3: Run tests to confirm nothing breaks**

Run: `mvn test -Dtest=TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestS3kRuntimeStateReadGuard,TestActiveGameplayTeamResolver`
Expected: All pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java
git commit -m "fix: Sonic3kLevelEventManager uses session-aware character resolution

getPlayerCharacter() now delegates to ActiveGameplayTeamResolver, which
checks the Data Select session's SelectedTeam before falling back to
config. Fixes wrong PlayerCharacter in zone runtime states (AIZ/HCZ/CNZ),
water palette, breakable walls, and boss positioning when Knuckles is
selected via Data Select."
```

---

### Task 3: Fix `S3kRuntimeStates.resolvePlayerCharacter()` fallback

The fallback path (when no zone runtime state exists) also reads config directly. This affects zones without runtime adapters.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/runtime/S3kRuntimeStates.java:26-48`

- [ ] **Step 1: Replace config-only fallback with resolver**

In `src/main/java/com/openggf/game/sonic3k/runtime/S3kRuntimeStates.java`, replace `resolvePlayerCharacter()` (lines 26-48):

Old:
```java
    public static PlayerCharacter resolvePlayerCharacter(ZoneRuntimeRegistry registry,
                                                         SonicConfigurationService config) {
        if (registry != null) {
            var current = registry.current();
            if (current instanceof S3kZoneRuntimeState s3kState) {
                return s3kState.playerCharacter();
            }
        }

        String mainChar = config.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if ("knuckles".equalsIgnoreCase(mainChar)) {
            return PlayerCharacter.KNUCKLES;
        }
        if ("tails".equalsIgnoreCase(mainChar)) {
            return PlayerCharacter.TAILS_ALONE;
        }

        String sidekick = config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        if (sidekick == null || sidekick.isBlank()) {
            return PlayerCharacter.SONIC_ALONE;
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }
```

New:
```java
    public static PlayerCharacter resolvePlayerCharacter(ZoneRuntimeRegistry registry,
                                                         SonicConfigurationService config) {
        if (registry != null) {
            var current = registry.current();
            if (current instanceof S3kZoneRuntimeState s3kState) {
                return s3kState.playerCharacter();
            }
        }

        return ActiveGameplayTeamResolver.resolvePlayerCharacter(config);
    }
```

Add import at top of file:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

Remove the now-unused import:
```java
// Remove: import com.openggf.configuration.SonicConfiguration;
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=TestS3kRuntimeStateReadGuard,TestActiveGameplayTeamResolver`
Expected: All pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/runtime/S3kRuntimeStates.java
git commit -m "fix: S3kRuntimeStates fallback uses session-aware resolution

When no zone runtime state exists, resolvePlayerCharacter() now delegates
to ActiveGameplayTeamResolver instead of reading MAIN_CHARACTER_CODE
directly. Fixes character detection in zones without runtime adapters."
```

---

### Task 4: Fix `Sonic3kObjectArtProvider.resolveLifeIconAddr()` — lives icon bug

This is the direct cause of bug (a): wrong lives icon in the HUD.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java:226-235`

- [ ] **Step 1: Replace config read with resolver**

In `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`, replace `resolveLifeIconAddr()` (lines 226-235):

Old:
```java
    private int resolveLifeIconAddr() {
        String mainChar = GameServices.configuration()
                .getString(com.openggf.configuration.SonicConfiguration.MAIN_CHARACTER_CODE);
        if ("knuckles".equalsIgnoreCase(mainChar)) {
            return Sonic3kConstants.ART_NEM_KNUCKLES_LIFE_ICON_ADDR;
        } else if ("tails".equalsIgnoreCase(mainChar)) {
            return Sonic3kConstants.ART_NEM_TAILS_LIFE_ICON_ADDR;
        }
        return Sonic3kConstants.ART_NEM_SONIC_LIFE_ICON_ADDR;
    }
```

New:
```java
    private int resolveLifeIconAddr() {
        String mainChar = ActiveGameplayTeamResolver.resolveMainCharacterCode(
                GameServices.configuration());
        if ("knuckles".equalsIgnoreCase(mainChar)) {
            return Sonic3kConstants.ART_NEM_KNUCKLES_LIFE_ICON_ADDR;
        } else if ("tails".equalsIgnoreCase(mainChar)) {
            return Sonic3kConstants.ART_NEM_TAILS_LIFE_ICON_ADDR;
        }
        return Sonic3kConstants.ART_NEM_SONIC_LIFE_ICON_ADDR;
    }
```

Add import at top of file:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java
git commit -m "fix: S3K lives icon uses session-aware character resolution

resolveLifeIconAddr() now checks the Data Select session before config,
so the correct character's lives icon art is loaded when Knuckles or
Tails is selected via Data Select."
```

---

### Task 5: Fix `Sonic3kZoneFeatureProvider.shouldSuppressInitialTitleCard()`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java:348-358`

- [ ] **Step 1: Replace config read with resolver**

In `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java`, replace the title card method body (lines 348-358):

Old:
```java
    @Override
    public boolean shouldSuppressInitialTitleCard(int zoneIndex, int actIndex) {
        if (zoneIndex != 0 || actIndex != 0) {
            return false;
        }
        SonicConfigurationService configService = GameServices.configuration();
        if (configService.getBoolean(SonicConfiguration.S3K_SKIP_INTROS)) {
            return false;
        }
        String mainCharacter = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        return mainCharacter != null && "sonic".equalsIgnoreCase(mainCharacter.trim());
    }
```

New:
```java
    @Override
    public boolean shouldSuppressInitialTitleCard(int zoneIndex, int actIndex) {
        if (zoneIndex != 0 || actIndex != 0) {
            return false;
        }
        SonicConfigurationService configService = GameServices.configuration();
        if (configService.getBoolean(SonicConfiguration.S3K_SKIP_INTROS)) {
            return false;
        }
        String mainCharacter = ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
        return "sonic".equalsIgnoreCase(mainCharacter);
    }
```

Add import at top of file:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java
git commit -m "fix: S3K title card suppression uses session-aware character resolution"
```

---

### Task 6: Fix `Sonic2LevelEventManager.getPlayerCharacter()`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java:99-121`

- [ ] **Step 1: Replace config-only resolution with resolver**

In `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java`, replace `getPlayerCharacter()` and `resolvePlayerCharacterFromConfig()` (lines 99-121):

Old:
```java
    @Override
    public PlayerCharacter getPlayerCharacter() {
        if (resolvedPlayerCharacter == null) {
            resolvedPlayerCharacter = resolvePlayerCharacterFromConfig();
        }
        return resolvedPlayerCharacter;
    }

    private static PlayerCharacter resolvePlayerCharacterFromConfig() {
        SonicConfigurationService config = GameServices.configuration();
        if (config == null) {
            return PlayerCharacter.SONIC_AND_TAILS;
        }
        String mainCode = config.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if ("tails".equalsIgnoreCase(mainCode)) {
            return PlayerCharacter.TAILS_ALONE;
        }
        String sidekickCode = config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        if (sidekickCode == null || sidekickCode.isEmpty()) {
            return PlayerCharacter.SONIC_ALONE;
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }
```

New:
```java
    @Override
    public PlayerCharacter getPlayerCharacter() {
        if (resolvedPlayerCharacter == null) {
            SonicConfigurationService config = GameServices.configuration();
            resolvedPlayerCharacter = config != null
                    ? ActiveGameplayTeamResolver.resolvePlayerCharacter(config)
                    : PlayerCharacter.SONIC_AND_TAILS;
        }
        return resolvedPlayerCharacter;
    }
```

Add import at top of file:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=TestActiveGameplayTeamResolver`
Expected: All pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java
git commit -m "fix: S2 level events use session-aware character resolution"
```

---

### Task 7: Fix remaining gameplay call sites

Three more direct config reads: `Sonic2SpecialStageManager`, `S3kSlotBonusStageRuntime`, and the two vine objects.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java:922`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java:80`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizRideVineObjectInstance.java:326-331`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizGiantRideVineObjectInstance.java:199-204`

- [ ] **Step 1: Fix `Sonic2SpecialStageManager.setupPlayers()`**

In `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`, replace line 922:

Old:
```java
        String characterCode = configuration().getString(SonicConfiguration.MAIN_CHARACTER_CODE);
```

New:
```java
        String characterCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(configuration());
```

Add import:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 2: Fix `S3kSlotBonusStageRuntime.enter()`**

In `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`, replace line 80:

Old:
```java
        String mainCode = GameServices.configuration().getString(SonicConfiguration.MAIN_CHARACTER_CODE);
```

New:
```java
        String mainCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(GameServices.configuration());
```

Add import:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 3: Fix `AizRideVineObjectInstance.resolveMainPlayer()`**

In `src/main/java/com/openggf/game/sonic3k/objects/AizRideVineObjectInstance.java`, replace `resolveMainPlayer()` (lines 326-331):

Old:
```java
    private AbstractPlayableSprite resolveMainPlayer() {
        var sprite = services().spriteManager().getSprite(
                config()
                        .getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        return sprite instanceof AbstractPlayableSprite playable ? playable : null;
    }
```

New:
```java
    private AbstractPlayableSprite resolveMainPlayer() {
        var sprite = services().spriteManager().getSprite(
                ActiveGameplayTeamResolver.resolveMainCharacterCode(config()));
        return sprite instanceof AbstractPlayableSprite playable ? playable : null;
    }
```

Add import:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 4: Fix `AizGiantRideVineObjectInstance.resolveMainPlayer()`**

In `src/main/java/com/openggf/game/sonic3k/objects/AizGiantRideVineObjectInstance.java`, replace `resolveMainPlayer()` (lines 199-204):

Old:
```java
    private AbstractPlayableSprite resolveMainPlayer() {
        var sprite = services().spriteManager().getSprite(
                config()
                        .getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        return sprite instanceof AbstractPlayableSprite playable ? playable : null;
    }
```

New:
```java
    private AbstractPlayableSprite resolveMainPlayer() {
        var sprite = services().spriteManager().getSprite(
                ActiveGameplayTeamResolver.resolveMainCharacterCode(config()));
        return sprite instanceof AbstractPlayableSprite playable ? playable : null;
    }
```

Add import:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java \
        src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java \
        src/main/java/com/openggf/game/sonic3k/objects/AizRideVineObjectInstance.java \
        src/main/java/com/openggf/game/sonic3k/objects/AizGiantRideVineObjectInstance.java
git commit -m "fix: remaining gameplay call sites use session-aware character resolution

Special stage, bonus stage, and vine objects now check Data Select
session before config for character identity."
```

---

### Task 8: Fix debug renderer call sites

Lower priority but should be consistent. These affect debug overlays.

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelDebugRenderer.java:722,874`
- Modify: `src/main/java/com/openggf/debug/DebugRenderer.java:1021-1023`

- [ ] **Step 1: Fix `LevelDebugRenderer` (two call sites)**

In `src/main/java/com/openggf/level/LevelDebugRenderer.java`, replace line 722:

Old:
```java
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
```

New:
```java
            Sprite player = spriteManager.getSprite(ActiveGameplayTeamResolver.resolveMainCharacterCode(configService));
```

Replace line 874 the same way:

Old:
```java
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
```

New:
```java
            Sprite player = spriteManager.getSprite(ActiveGameplayTeamResolver.resolveMainCharacterCode(configService));
```

Add import:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 2: Fix `DebugRenderer.getMainCharacterCode()`**

In `src/main/java/com/openggf/debug/DebugRenderer.java`, replace `getMainCharacterCode()` (line 1021-1023):

Old:
```java
        private String getMainCharacterCode() {
                return configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        }
```

New:
```java
        private String getMainCharacterCode() {
                return ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
        }
```

Add import:
```java
import com.openggf.game.session.ActiveGameplayTeamResolver;
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/LevelDebugRenderer.java \
        src/main/java/com/openggf/debug/DebugRenderer.java
git commit -m "fix: debug renderers use session-aware character resolution"
```

---

### Task 9: Full test suite verification

- [ ] **Step 1: Run all tests**

Run: `mvn test`
Expected: All existing tests pass. No regressions.

- [ ] **Step 2: Run S3K-specific tests**

Run: `mvn test -Dtest=TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestS3kRuntimeStateReadGuard,TestActiveGameplayTeamResolver`
Expected: All pass.

- [ ] **Step 3: Verify no remaining direct config reads in gameplay code**

Search for remaining `MAIN_CHARACTER_CODE` reads that don't go through the resolver. The following are expected/acceptable:
- `ActiveGameplayTeamResolver.java` — the resolver itself (fallback path)
- `SonicConfigurationService.java` — default value registration
- `SonicConfiguration.java` — enum constant definition
- `Engine.java:573-581` — `resolveLaunchMainCharacter()` already checks session first (same pattern as resolver)
- `Engine.java:584-592` — `resolveLaunchSidekicks()` already checks session first
- `Sonic3kBootstrapResolver.java:50-58` — already checks session first
- `Sonic3k.java:716-724` — already checks session first
- `CONFIGURATION.md`, `config.json` — documentation/config files

Any other `MAIN_CHARACTER_CODE` in `src/main` gameplay code is a bug to fix.

- [ ] **Step 4: Final commit if any stragglers found**

---
