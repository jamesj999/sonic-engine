# S3K-Donated Data Select Accurate Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.

**Goal:** Replace the placeholder Data Select frontend with a ROM-accurate S3K-donated presentation, while keeping save/session semantics host-owned and routing Data Select only through the `1 PLAYER` title path.

**Architecture:** Split Data Select into explicit startup routing, a donated presentation provider, and a host-game metadata profile. Native S3K and S1/S2 with S3K donation share the same ROM-backed S3K screen, while slot metadata, validation, and clear-restart rules remain owned by the active base game.

**Tech Stack:** Java 21, Maven, LWJGL/OpenGL fixed-function wrapper code, ROM-backed asset loaders, existing `GameServices`/`RuntimeManager` service architecture. Current and new tests must use JUnit 5 / Jupiter only; any JUnit 4 references below are historical migration context.

---

## File Structure

### New Files

- `src/main/java/com/openggf/game/startup/StartupRouteResolver.java`
- `src/main/java/com/openggf/game/startup/TitleActionRoute.java`
- `src/main/java/com/openggf/game/startup/DataSelectPresentationResolution.java`
- `src/main/java/com/openggf/game/dataselect/DataSelectPresentationProvider.java`
- `src/main/java/com/openggf/game/dataselect/DataSelectHostProfile.java`
- `src/main/java/com/openggf/game/dataselect/DataSelectSessionController.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectDataLoader.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectLayout.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectHostProfile.java`
- `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectHostProfile.java`
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectHostProfile.java`
- `src/test/java/com/openggf/game/startup/TestStartupRouteResolver.java`
- `src/test/java/com/openggf/game/dataselect/TestDataSelectSessionController.java`
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectDataLoader.java`
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

### Files To Modify

- `src/main/java/com/openggf/Engine.java`
- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/game/GameModule.java`
- `src/main/java/com/openggf/game/TitleScreenProvider.java`
- `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- `src/main/java/com/openggf/game/dataselect/AbstractDataSelectProvider.java`
- `src/main/java/com/openggf/game/dataselect/DataSelectAction.java`
- `src/main/java/com/openggf/game/dataselect/DataSelectDestination.java`
- `src/main/java/com/openggf/game/dataselect/DataSelectMenuModel.java`
- `src/main/java/com/openggf/game/dataselect/DataSelectGameProfile.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- `src/main/java/com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenManager.java`
- `src/main/java/com/openggf/game/sonic2/titlescreen/TitleScreenManager.java`
- `src/main/java/com/openggf/game/sonic1/titlescreen/Sonic1TitleScreenManager.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectManager.java`
- `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectManager.java`
- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectManager.java`
- `src/test/java/com/openggf/TestGameLoop.java`
- `src/test/java/com/openggf/game/TestCrossGameFeatureProviderRefactor.java`
- `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java`
- `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java`
- `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectProfile.java`
- `docs/S3K_KNOWN_DISCREPANCIES.md`

### Files To Remove From Production Path

- `src/main/java/com/openggf/game/dataselect/SimpleDataSelectManager.java`
- placeholder rendering branches inside `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectManager.java`

### Design Notes

- Keep the existing `SaveManager`, `SaveSessionContext`, and snapshot providers unless a task explicitly replaces their interface.
- Do not create bespoke S1/S2 Data Select frontends. Their participation is limited to host metadata plus S3K-donated presentation.
- Do not route `2 PLAYER`, `OPTIONS`, competition, or sound-test through Data Select.

### Task 1: Add Explicit Startup And Title Routing

**Files:**
- Create: `src/main/java/com/openggf/game/startup/StartupRouteResolver.java`
- Create: `src/main/java/com/openggf/game/startup/TitleActionRoute.java`
- Create: `src/main/java/com/openggf/game/startup/DataSelectPresentationResolution.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/game/TitleScreenProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/titlescreen/TitleScreenManager.java`
- Modify: `src/main/java/com/openggf/game/sonic1/titlescreen/Sonic1TitleScreenManager.java`
- Test: `src/test/java/com/openggf/game/startup/TestStartupRouteResolver.java`
- Test: `src/test/java/com/openggf/TestGameLoop.java`

- [ ] **Step 1: Write the failing routing tests**

```java
@Test
void onePlayerRoutesToDataSelectOnlyWhenPresentationResolvesToS3k() {
    StartupRouteResolver resolver = new StartupRouteResolver();

    TitleActionRoute route = resolver.resolveTitleAction(
            hostModule(GameId.S2),
            presentationResolution(GameId.S3K, true),
            true,
            false,
            TitleScreenAction.ONE_PLAYER);

    assertEquals(TitleActionRoute.DATA_SELECT, route);
}

@Test
void levelSelectStillOverridesDataSelect() {
    StartupRouteResolver resolver = new StartupRouteResolver();

    TitleActionRoute route = resolver.resolveTitleAction(
            hostModule(GameId.S3K),
            presentationResolution(GameId.S3K, true),
            true,
            true,
            TitleScreenAction.ONE_PLAYER);

    assertEquals(TitleActionRoute.LEVEL_SELECT, route);
}
```

- [ ] **Step 2: Run the routing tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestStartupRouteResolver,TestGameLoop" test`

Expected: FAIL because `StartupRouteResolver`, `TitleActionRoute`, and explicit title action routing do not exist yet.

- [ ] **Step 3: Add startup routing types and title action contract**

```java
public enum TitleScreenAction {
    ONE_PLAYER,
    TWO_PLAYER,
    OPTIONS,
    OTHER
}

public interface TitleScreenProvider {
    default TitleScreenAction consumeExitAction() {
        return TitleScreenAction.ONE_PLAYER;
    }
}
```

```java
public enum TitleActionRoute {
    LEVEL,
    LEVEL_SELECT,
    DATA_SELECT,
    TWO_PLAYER,
    OPTIONS,
    OTHER
}

public record DataSelectPresentationResolution(
        boolean dataSelectEligible,
        GameId presentationGameId) {

    public boolean usesS3kPresentation() {
        return dataSelectEligible && presentationGameId == GameId.S3K;
    }
}
```

```java
public final class StartupRouteResolver {
    public TitleActionRoute resolveTitleAction(
            GameModule hostModule,
            DataSelectPresentationResolution resolution,
            boolean titleScreenEnabled,
            boolean levelSelectEnabled,
            TitleScreenAction action) {
        if (action != TitleScreenAction.ONE_PLAYER) {
            return switch (action) {
                case TWO_PLAYER -> TitleActionRoute.TWO_PLAYER;
                case OPTIONS -> TitleActionRoute.OPTIONS;
                default -> TitleActionRoute.OTHER;
            };
        }
        if (levelSelectEnabled) {
            return TitleActionRoute.LEVEL_SELECT;
        }
        if (resolution.usesS3kPresentation()) {
            return TitleActionRoute.DATA_SELECT;
        }
        return TitleActionRoute.LEVEL;
    }
}
```

- [ ] **Step 4: Wire `Engine` and `GameLoop` to use the resolver instead of provider presence**

```java
private void enterConfiguredStartupMode() {
    boolean titleScreenOnStartup = configService.getBoolean(SonicConfiguration.TITLE_SCREEN_ON_STARTUP);
    boolean levelSelectOnStartup = configService.getBoolean(SonicConfiguration.LEVEL_SELECT_ON_STARTUP);
    if (titleScreenOnStartup) {
        gameLoop.initializeTitleScreenMode();
        return;
    }
    if (levelSelectOnStartup) {
        gameLoop.initializeLevelSelectMode();
        return;
    }
    loadDefaultStartingLevel(true);
}
```

```java
private void exitTitleScreen() {
    TitleScreenAction action = getTitleScreenProviderLazy().consumeExitAction();
    TitleActionRoute route = startupRouteResolver.resolveTitleAction(
            GameServices.module(),
            resolveDataSelectPresentation(),
            true,
            configService.getBoolean(SonicConfiguration.LEVEL_SELECT_ON_STARTUP),
            action);
    switch (route) {
        case DATA_SELECT -> initializeDataSelectMode();
        case LEVEL_SELECT -> initializeLevelSelectMode();
        default -> doExitTitleScreen();
    }
}
```

- [ ] **Step 5: Run the routing tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestStartupRouteResolver,TestGameLoop" test`

Expected: PASS with new route resolution covering native S3K, S1/S2 donated S3K, and Level Select precedence.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/startup src/main/java/com/openggf/Engine.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/game/TitleScreenProvider.java src/main/java/com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenManager.java src/main/java/com/openggf/game/sonic2/titlescreen/TitleScreenManager.java src/main/java/com/openggf/game/sonic1/titlescreen/Sonic1TitleScreenManager.java src/test/java/com/openggf/game/startup/TestStartupRouteResolver.java src/test/java/com/openggf/TestGameLoop.java
git commit -m "refactor: add explicit startup routing for data select"
```

### Task 2: Split Data Select Presentation From Host Metadata

**Files:**
- Create: `src/main/java/com/openggf/game/dataselect/DataSelectPresentationProvider.java`
- Create: `src/main/java/com/openggf/game/dataselect/DataSelectHostProfile.java`
- Create: `src/main/java/com/openggf/game/dataselect/DataSelectSessionController.java`
- Modify: `src/main/java/com/openggf/game/GameModule.java`
- Modify: `src/main/java/com/openggf/game/dataselect/AbstractDataSelectProvider.java`
- Modify: `src/main/java/com/openggf/game/dataselect/DataSelectAction.java`
- Modify: `src/main/java/com/openggf/game/dataselect/DataSelectDestination.java`
- Modify: `src/main/java/com/openggf/game/dataselect/DataSelectMenuModel.java`
- Modify: `src/main/java/com/openggf/game/dataselect/DataSelectGameProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Test: `src/test/java/com/openggf/game/dataselect/TestDataSelectSessionController.java`
- Test: `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java`
- Test: `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectProfile.java`

- [ ] **Step 1: Write the failing controller/profile tests**

```java
@Test
void clearSaveUsesHostProfileDestinationsInsteadOfRendererLogic() {
    DataSelectSessionController controller = new DataSelectSessionController(hostProfile, saveManager, configService);
    controller.initialize();
    controller.selectSlot(3);

    assertEquals(List.of(new DataSelectDestination(7, 0)), controller.currentClearRestartDestinations());
}

@Test
void extraTeamsComeFromHostProfileParsingRules() {
    List<SelectedTeam> teams = hostProfile.parseExtraTeams("sonic,knuckles;knuckles,tails");
    assertEquals(2, teams.size());
    assertEquals("sonic", teams.get(0).mainCharacter());
}
```

- [ ] **Step 2: Run the controller/profile tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestDataSelectSessionController,TestS1DataSelectProfile,TestS2DataSelectProfile,TestS3kDataSelectProfile" test`

Expected: FAIL because the branch still uses `DataSelectGameProfile` plus renderer-owned menu logic.

- [ ] **Step 3: Introduce the new presentation/profile/controller interfaces**

```java
public interface DataSelectHostProfile {
    String gameCode();
    int slotCount();
    List<SelectedTeam> builtInTeams();
    List<SelectedTeam> parseExtraTeams(String configValue);
    SaveSlotSummary summarizeFreshSlot(int slot);
    SaveSlotSummary validateSlotSummary(int slot, Map<String, Object> payload, boolean hashWarning);
    List<DataSelectDestination> clearRestartDestinations(Map<String, Object> payload);
}
```

```java
public final class DataSelectSessionController {
    private final DataSelectHostProfile hostProfile;
    private final SaveManager saveManager;
    private final SonicConfigurationService config;
    private final DataSelectMenuModel menu = new DataSelectMenuModel();

    public void initialize() { /* load teams, load slot summaries, reset selection */ }
    public void updateSelection(int delta) { /* host-agnostic selection logic only */ }
    public DataSelectAction confirm() { /* no rendering code here */ }
}
```

- [ ] **Step 4: Update game modules to expose presentation provider and host profile separately**

```java
default DataSelectPresentationProvider getDataSelectPresentationProvider() {
    return NoOpDataSelectPresentationProvider.INSTANCE;
}

default DataSelectHostProfile getDataSelectHostProfile() {
    return NoOpDataSelectHostProfile.INSTANCE;
}
```

```java
@Override
public DataSelectHostProfile getDataSelectHostProfile() {
    return new S2DataSelectHostProfile();
}
```

- [ ] **Step 5: Run the controller/profile tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestDataSelectSessionController,TestS1DataSelectProfile,TestS2DataSelectProfile,TestS3kDataSelectProfile" test`

Expected: PASS with host profiles owning validation and restart rules, and the controller owning action selection without drawing.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/dataselect src/main/java/com/openggf/game/GameModule.java src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java src/test/java/com/openggf/game/dataselect/TestDataSelectSessionController.java src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectProfile.java
git commit -m "refactor: split data select presentation from host profile"
```

### Task 3: Implement S3K Data Select ROM Asset Loading

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectDataLoader.java`
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectLayout.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectDataLoader.java`

- [ ] **Step 1: Write the failing ROM-loader tests**

```java
@Test
void loadsS3kDataSelectArtPaletteAndMusicMetadata() throws Exception {
    S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(romReader);

    loader.loadData();

    assertNotNull(loader.getBackgroundPatterns());
    assertNotNull(loader.getPaletteData());
    assertTrue(loader.getCardMappings().size() > 0);
    assertEquals(Sonic3kMusic.DATA_SELECT.id, loader.getMusicId());
}

@Test
void layoutMatchesOriginalSaveScreenCoordinates() throws Exception {
    S3kDataSelectLayout layout = S3kDataSelectLayout.original();

    assertEquals(0x110, layout.slotWorldX(1));
    assertEquals(0x448, layout.deleteWorldX());
}
```

- [ ] **Step 2: Run the loader tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectDataLoader" test`

Expected: FAIL because no Data Select ROM loader or layout class exists yet.

- [ ] **Step 3: Add the S3K Data Select constants and loader**

```java
public final class S3kDataSelectDataLoader {
    private final RomByteReader reader;
    private Pattern[] backgroundPatterns;
    private Pattern[] spritePatterns;
    private byte[] paletteData;
    private List<SpriteMappingFrame> cardMappings;

    public void loadData() throws IOException {
        backgroundPatterns = Nemesis.decompress(reader.slice(Sonic3kConstants.ARTNEM_SAVE_SELECT_BG_ADDR));
        spritePatterns = Nemesis.decompress(reader.slice(Sonic3kConstants.ARTNEM_SAVE_SELECT_SPRITES_ADDR));
        paletteData = reader.slice(Sonic3kConstants.PAL_SAVE_SELECT_ADDR, 128);
        cardMappings = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_SAVE_SELECT_ADDR);
    }
}
```

```java
public record S3kDataSelectLayout(
        int noSaveWorldX,
        int deleteWorldX,
        int slotWorldXStart,
        int slotWorldXStep,
        int slotWorldY) {

    public static S3kDataSelectLayout original() {
        return new S3kDataSelectLayout(0xB0, 0x448, 0x110, 0x68, 0x108);
    }
}
```

- [ ] **Step 4: Run the loader tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectDataLoader" test`

Expected: PASS with real art, palette, mapping, and music metadata loaded from the S3K ROM.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectDataLoader.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectLayout.java src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectDataLoader.java
git commit -m "feat: load s3k data select assets from rom"
```

### Task 4: Build The ROM-Accurate S3K Data Select Presentation

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectManager.java`
- Modify: `src/main/java/com/openggf/game/dataselect/AbstractDataSelectProvider.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`
- Test: `src/test/java/com/openggf/TestGameLoop.java`

- [ ] **Step 1: Write the failing presentation tests**

```java
@Test
void presentationInitializesMusicAndRomAssetsOnEnter() {
    S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(loader, renderer, controller);

    presentation.initialize();

    assertTrue(presentation.isActive());
    assertEquals(Sonic3kMusic.DATA_SELECT.id, fakeAudio.lastMusicId());
}

@Test
void drawPathDoesNotUsePixelFontFallbackCards() {
    S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(loader, renderer, controller);

    presentation.draw();

    assertFalse(renderer.usedDebugTextFallback());
}
```

- [ ] **Step 2: Run the presentation tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation,TestGameLoop" test`

Expected: FAIL because the branch still renders with `PixelFontTextRenderer` and custom `RECTI` cards.

- [ ] **Step 3: Implement the S3K presentation and renderer**

```java
public final class S3kDataSelectPresentation extends AbstractDataSelectProvider
        implements DataSelectPresentationProvider {

    private final DataSelectSessionController controller;
    private final S3kDataSelectDataLoader dataLoader;
    private final S3kDataSelectRenderer renderer;

    @Override
    public void initialize() {
        controller.initialize();
        dataLoader.loadData();
        GameServices.audio().playMusic(Sonic3kMusic.DATA_SELECT.id);
        state = State.FADE_IN;
    }

    @Override
    public void draw() {
        renderer.draw(controller.snapshot(), dataLoader, S3kDataSelectLayout.original());
    }
}
```

```java
public final class S3kDataSelectRenderer {
    public void draw(DataSelectViewState viewState,
                     S3kDataSelectDataLoader loader,
                     S3kDataSelectLayout layout) {
        drawBackground(loader);
        drawSlotStrip(viewState, loader, layout);
        drawTeamSprites(viewState, loader);
        drawDetailPanels(viewState, loader);
        drawFade(viewState);
    }
}
```

- [ ] **Step 4: Remove placeholder rendering from the production path**

```java
@Override
public DataSelectProvider getDataSelectProvider() {
    return new S3kDataSelectPresentation(loader(), renderer(), controller());
}
```

```java
// Delete or dead-end the old custom renderer path:
// - no PixelFontTextRenderer-based cards
// - no GLCommand.RECTI slot backgrounds as primary UI
```

- [ ] **Step 5: Run the presentation tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation,TestGameLoop" test`

Expected: PASS with title-to-data-select flow still working and the draw path owned by the S3K ROM-backed presentation.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectManager.java src/main/java/com/openggf/game/dataselect/AbstractDataSelectProvider.java src/main/java/com/openggf/Engine.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java src/test/java/com/openggf/TestGameLoop.java
git commit -m "feat: replace placeholder data select with s3k presentation"
```

### Task 5: Wire S3K Donation For S1 And S2 Without Creating New Frontends

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectHostProfile.java`
- Create: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectHostProfile.java`
- Create: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectHostProfile.java`
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectManager.java`
- Modify: `src/test/java/com/openggf/game/TestCrossGameFeatureProviderRefactor.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`

- [ ] **Step 1: Write the failing donation/host-profile tests**

```java
@Test
void s1UsesS3kDataSelectOnlyWhenDonationResolvesToS3k() {
    GameModule module = new Sonic1GameModule();

    assertTrue(module.getDataSelectPresentationProvider(resolution(GameId.S3K)).isPresent());
    assertFalse(module.getDataSelectPresentationProvider(resolution(GameId.S2)).isPresent());
}

@Test
void donatedPresentationStillUsesHostOwnedRestartRules() {
    DataSelectHostProfile profile = new S2DataSelectHostProfile();
    List<DataSelectDestination> destinations = profile.clearRestartDestinations(clearPayload());
    assertEquals(List.of(new DataSelectDestination(5, 0)), destinations);
}
```

- [ ] **Step 2: Run the donation tests to verify they fail**

Run: `mvn -Dmse=off "-Dtest=TestCrossGameFeatureProviderRefactor,TestGameLoop,TestS1DataSelectProfile,TestS2DataSelectProfile,TestS3kDataSelectProfile" test`

Expected: FAIL because S1/S2 still expose their own manager classes instead of resolving S3K-donated presentation plus host-owned semantics.

- [ ] **Step 3: Teach donation resolution to answer “presentation provider = S3K”**

```java
public DataSelectPresentationResolution resolveDataSelectPresentation(GameModule hostModule) {
    if (hostModule.getGameId() == GameId.S3K) {
        return new DataSelectPresentationResolution(true, GameId.S3K);
    }
    if (isActive() && donorGameId == GameId.S3K) {
        return new DataSelectPresentationResolution(true, GameId.S3K);
    }
    return new DataSelectPresentationResolution(false, hostModule.getGameId());
}
```

- [ ] **Step 4: Replace S1/S2 Data Select managers with host profiles plus donated S3K presentation**

```java
@Override
public DataSelectHostProfile getDataSelectHostProfile() {
    return new S1DataSelectHostProfile();
}

@Override
public DataSelectPresentationProvider getDataSelectPresentationProvider() {
    return GameServices.crossGameFeatures().resolveDataSelectPresentation(this).usesS3kPresentation()
            ? new S3kDataSelectPresentation(...)
            : NoOpDataSelectPresentationProvider.INSTANCE;
}
```

- [ ] **Step 5: Run the donation tests to verify they pass**

Run: `mvn -Dmse=off "-Dtest=TestCrossGameFeatureProviderRefactor,TestGameLoop,TestS1DataSelectProfile,TestS2DataSelectProfile,TestS3kDataSelectProfile" test`

Expected: PASS with S1/S2 entering Data Select only through donated S3K presentation and with host-specific restart tables preserved.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/CrossGameFeatureProvider.java src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectHostProfile.java src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectHostProfile.java src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectHostProfile.java src/test/java/com/openggf/game/TestCrossGameFeatureProviderRefactor.java src/test/java/com/openggf/TestGameLoop.java
git commit -m "feat: donate s3k data select presentation across games"
```

### Task 6: Finish Verification, Remove Placeholder Paths, And Update Docs

**Files:**
- Modify: `src/main/java/com/openggf/game/dataselect/SimpleDataSelectManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectManager.java`
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write the final regression tests**

```java
@Test
void productionDataSelectPathNeverInstantiatesSimpleDataSelectManager() {
    Engine engine = bootWithS3kPresentation();

    engine.getGameLoop().initializeTitleScreenMode();
    exitThroughOnePlayer(engine);

    assertFalse(engine.getGameLoop().getDataSelectProvider() instanceof SimpleDataSelectManager);
}

@Test
void twoPlayerAndOptionsStillBypassDataSelect() {
    assertEquals(GameMode.LEVEL, routeForAction(TitleScreenAction.TWO_PLAYER));
    assertEquals(GameMode.LEVEL, routeForAction(TitleScreenAction.OPTIONS));
}
```

- [ ] **Step 2: Run the final targeted suite to verify any remaining placeholder path fails**

Run: `mvn -Dmse=off "-Dtest=TestStartupRouteResolver,TestDataSelectSessionController,TestS3kDataSelectDataLoader,TestS3kDataSelectPresentation,TestCrossGameFeatureProviderRefactor,TestGameLoop,TestS1DataSelectProfile,TestS2DataSelectProfile,TestS3kDataSelectProfile,TestSaveManager,TestSessionManager" test`

Expected: PASS on all Data Select/save/session tests.

- [ ] **Step 3: Remove or quarantine the old placeholder manager from production**

```java
@Deprecated(forRemoval = true)
public final class SimpleDataSelectManager extends AbstractDataSelectProvider {
    public SimpleDataSelectManager(DataSelectGameProfile profile) {
        throw new UnsupportedOperationException("SimpleDataSelectManager is not a production Data Select path");
    }
}
```

```java
## S3K Data Select

- Data Select presentation is now donated from the S3K ROM whenever the active presentation provider resolves to `S3K`.
- S1 and S2 use that same screen only when S3K cross-game donation is active.
- Slot metadata, validation, and clear-restart rules remain host-owned.
- The previous placeholder rectangle/text frontend is no longer used in production.
```

- [ ] **Step 4: Run the full repository suite and package build**

Run: `mvn -Dmse=off test`

Expected: `BUILD SUCCESS`

Run: `mvn -Dmse=off package`

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Perform manual parity passes**

Run:

```bash
run.cmd
```

Manual checklist:

- Native `S3K`: title screen `1 PLAYER` enters real Data Select, `2 PLAYER` does not.
- Native `S3K`: Level Select enabled bypasses Data Select and behaves as `No Save`.
- Native `S3K`: slot cards, clear-save routing, team sprites, and music match the original design reference closely enough to capture screenshots for review.
- `S2` + S3K donation: title screen `1 PLAYER` enters donated S3K Data Select; slot metadata and clear destinations are S2-owned.
- `S1` + S3K donation: title screen `1 PLAYER` enters donated S3K Data Select; slot metadata and clear destinations are S1-owned.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/dataselect/SimpleDataSelectManager.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectManager.java docs/S3K_KNOWN_DISCREPANCIES.md src/test/java/com/openggf/TestGameLoop.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git commit -m "docs: finalize accurate s3k data select rollout"
```

## Self-Review

### Spec Coverage

- Startup and `1 PLAYER`-only routing: covered by Task 1 and Task 6.
- Level Select precedence: covered by Task 1 and Task 6.
- Presentation provider vs host profile split: covered by Task 2 and Task 5.
- ROM-accurate S3K presentation: covered by Task 3 and Task 4.
- S1/S2 participation only through S3K donation: covered by Task 5.
- Rejection of placeholder frontend: covered by Task 4 and Task 6.
- Documentation update: covered by Task 6.

### Placeholder Scan

- No `TODO`, `TBD`, or “implement later” placeholders remain.
- Each task names concrete files, tests, commands, and commit points.
- Each code-writing step includes concrete class or method content rather than generic instructions.

### Type Consistency

- Routing types use `StartupRouteResolver`, `TitleActionRoute`, and `DataSelectPresentationResolution` consistently.
- Data Select split uses `DataSelectPresentationProvider`, `DataSelectHostProfile`, and `DataSelectSessionController` consistently.
- S3K frontend types use `S3kDataSelectDataLoader`, `S3kDataSelectLayout`, `S3kDataSelectPresentation`, and `S3kDataSelectRenderer` consistently.

Plan complete and saved to `docs/architecture/plans/2026-04-13-s3k-donated-data-select-implementation-plan.md`. Two execution options:

1. Subagent-Driven (recommended) - I dispatch a fresh subagent per task, review between tasks, fast iteration

2. Inline Execution - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
