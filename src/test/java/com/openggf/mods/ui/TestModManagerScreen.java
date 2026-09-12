package com.openggf.mods.ui;

import com.openggf.InputBindingFactory;
import com.openggf.ModManagerScreenHost;
import com.openggf.control.InputActionMasks;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.graphics.PixelFont;
import com.openggf.game.session.PatternWindowState;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.mods.EffectiveModCatalog;
import com.openggf.mods.EffectiveCatalogBuilder;
import com.openggf.mods.InvalidModEntry;
import com.openggf.mods.ModCatalog;
import com.openggf.mods.ModCatalogEntry;
import com.openggf.mods.ModDependency;
import com.openggf.mods.ModDescriptor;
import com.openggf.mods.ModEligibility;
import com.openggf.mods.ModFinding;
import com.openggf.mods.ModFindingSeverity;
import com.openggf.mods.ModManifest;
import com.openggf.mods.ModRuntimeFindingStore;
import com.openggf.mods.ModState;
import com.openggf.mods.ModStateStore;
import com.openggf.mods.ModType;
import com.openggf.mods.PendingModStateEditor;
import com.openggf.mods.RepositoryScanFailure;
import com.openggf.mods.SemanticVersion;
import com.openggf.mods.VersionRange;
import com.openggf.mods.code.ModPatternWindowAllocator;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_B;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_X;

class TestModManagerScreen {
    @TempDir Path temp;

    @Test
    void rowsAndDetailsExposeValidInvalidRepositoryEligibilityAndRuntimeFindings() {
        ModDescriptor core = descriptor("pack-core", "Core Pack", List.of(), List.of(
                finding(ModFindingSeverity.WARNING, "OVERRIDE_CONFLICT", "Later pack wins")));
        ModDescriptor addon = descriptor("pack-addon", "Addon Pack",
                List.of(new ModDependency("pack-core", VersionRange.parse(">=1.0.0 <2.0.0"))), List.of());
        InvalidModEntry invalid = new InvalidModEntry(temp.resolve("broken.jar"), List.of(
                finding(ModFindingSeverity.ERROR, "MANIFEST_PARSE", "Malformed manifest")));
        RepositoryScanFailure repository = new RepositoryScanFailure(temp, List.of(
                finding(ModFindingSeverity.ERROR, "REPOSITORY_READ", "Repository unavailable")));
        Map<String, ModEligibility> eligibility = Map.of(
                "pack-core", new ModEligibility("pack-core", ModEligibility.Status.BLOCKED, List.of(
                        new ModEligibility.Reason("ENGINE_API_INCOMPATIBLE", "Needs API 2", List.of()))),
                "pack-addon", new ModEligibility("pack-addon", ModEligibility.Status.DISABLED, List.of(
                        new ModEligibility.Reason("DISABLED", "Disabled in pending state", List.of()))));
        ModCatalog catalog = new ModCatalog(List.of(core, addon, invalid, repository),
                new EffectiveModCatalog(List.of(core)), eligibility);
        ModRuntimeFindingStore runtime = new ModRuntimeFindingStore();
        runtime.replaceOwner("pack-addon", List.of(
                finding(ModFindingSeverity.ERROR, "AUDIO_DECODE_FAILED", "OGG decode failed")));
        RecordingFont font = new RecordingFont();
        ModManagerScreen screen = screen(catalog, state(true, false), runtime, font, temp.resolve("mods"));

        assertEquals(List.of("pack-core", "pack-addon", "broken.jar"),
                screen.rows().stream().map(ModManagerScreen.RowView::identity).toList());
        assertTrue(screen.rows().get(0).badges().contains("WARN"));
        assertTrue(screen.rows().get(0).badges().contains("BLOCKED"));
        assertTrue(screen.rows().get(1).badges().contains("RUNTIME ERROR"));
        assertTrue(screen.rows().get(2).badges().contains("ERROR"));
        assertTrue(screen.bannerLines().stream().anyMatch(line -> line.contains("Repository unavailable")));

        List<String> coreDetails = screen.detailLines();
        assertTrue(coreDetails.stream().anyMatch(line -> line.contains("pack-core")));
        assertTrue(coreDetails.stream().anyMatch(line -> line.contains("Core Pack")));
        assertTrue(coreDetails.stream().anyMatch(line -> line.contains("1.2.3")));
        assertTrue(coreDetails.stream().anyMatch(line -> line.contains("Alice, Bob")));
        assertTrue(coreDetails.stream().anyMatch(line -> line.contains("A detailed music pack")));
        assertTrue(coreDetails.stream().anyMatch(line -> line.contains("Needs API 2")));
        assertTrue(coreDetails.stream().anyMatch(line -> line.contains("Later pack wins")));

        press(screen, Action.DOWN);
        assertTrue(screen.detailLines().stream().anyMatch(line -> line.contains("pack-core >=1.0.0 <2.0.0")));
        assertTrue(screen.detailLines().stream().anyMatch(line -> line.contains("OGG decode failed")));
        screen.render();
        assertTrue(font.drawn.stream().anyMatch(line -> line.contains("MOD MANAGER")));
        press(screen, Action.RIGHT); // visible actions for the selected addon
        press(screen, Action.ACCEPT); // Details
        screen.render();
        assertTrue(font.drawn.stream().anyMatch(line -> line.contains("OGG decode failed")));
    }

    @Test
    void displaysRetainedPerOwnerAndTotalPatternWindowBudget() {
        ModDescriptor small = descriptorWithWindows("pack-small", "Small", 1);
        ModDescriptor large = descriptorWithWindows("pack-large", "Large", 16);
        ModCatalog catalog = new ModCatalog(List.of(small, large),
                new EffectiveModCatalog(List.of(small, large)), Map.of());
        RecordingFont font = new RecordingFont();
        ModPatternWindowAllocator allocator = new ModPatternWindowAllocator(
                catalog.effective(), 0x108000);
        PendingModStateEditor editor = new PendingModStateEditor(
                state(true, true, "pack-small", "pack-large"), catalog.scanned(),
                new ModStateStore(temp.resolve("budget").toAbsolutePath().normalize()));
        ModManagerScreen screen = new ModManagerScreen(catalog, editor,
                new ModRuntimeFindingStore(), font == null ? null : textSink(font), allocator);

        assertEquals("Current pattern windows: 17/128", screen.patternBudgetLine());
        assertTrue(screen.detailLines().stream().anyMatch(line -> line.contains("Pattern windows: 1")));
        screen.select(1);
        assertTrue(screen.detailLines().stream().anyMatch(line -> line.contains("Pattern windows: 16")));
        screen.render();
        assertTrue(font.drawn.contains("Current pattern windows: 17/128"));
    }

    @Test
    void moreThanEighteenRowsScrollAndEveryLogicalActionIsEdgeTriggered() {
        List<ModDescriptor> descriptors = new ArrayList<>();
        List<ModState.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            String id = "pack-mod" + index;
            descriptors.add(descriptor(id, "Mod " + index, List.of(), List.of()));
            entries.add(new ModState.Entry(id, false, index));
        }
        ModManagerScreen screen = screen(catalog(descriptors),
                new ModState(ModState.CURRENT_FORMAT_VERSION, entries),
                new ModRuntimeFindingStore(), null, temp.resolve("mods"));

        LogicalInputSnapshot down = logical(Action.DOWN);
        for (int index = 0; index < 20; index++) {
            screen.update(menu(down));
            screen.update(ModManagerScreen.MenuInput.NEUTRAL);
        }
        assertEquals(20, screen.selectedIndex());
        assertTrue(screen.scrollOffset() > 0);
        assertEquals(ModManagerScreen.MAX_VISIBLE_ROWS, screen.visibleRows().size());

        screen.update(menu(down));
        screen.update(menu(down));
        assertEquals(21, screen.selectedIndex(), "a held logical action must not double-trigger");
    }

    @Test
    void keyboardAndGamepadLogicalActionsHaveIdenticalToggleAndNavigationResults() {
        ModDescriptor one = descriptor("pack-one", "One", List.of(), List.of());
        ModDescriptor two = descriptor("pack-two", "Two", List.of(), List.of());
        ModCatalog catalog = catalog(List.of(one, two));
        ModState startup = state(false, false, "pack-one", "pack-two");
        ModManagerScreen keyboard = screen(catalog, startup, new ModRuntimeFindingStore(), null,
                temp.resolve("keyboard"));
        ModManagerScreen gamepad = screen(catalog, startup, new ModRuntimeFindingStore(), null,
                temp.resolve("gamepad"));

        // The keyboard mapper and gamepad mapper both feed these same logical edges.
        press(keyboard, Action.DOWN); press(keyboard, Action.ACCEPT);
        press(gamepad, Action.DOWN); press(gamepad, Action.ACCEPT);

        assertEquals(keyboard.selectedIndex(), gamepad.selectedIndex());
        assertEquals(keyboard.pendingState(), gamepad.pendingState());
        assertTrue(keyboard.restartRequired());
        assertTrue(gamepad.restartRequired());
    }

    @Test
    void liveKeyboardAndGamepadMappingsMatchForNavigationToggleReorderAndBack() {
        ModDescriptor one = descriptor("pack-one", "One", List.of(), List.of());
        ModDescriptor two = descriptor("pack-two", "Two", List.of(), List.of());
        ModCatalog catalog = catalog(List.of(one, two));
        ModState startup = state(false, false, "pack-one", "pack-two");
        ModManagerScreen keyboardScreen = screen(catalog, startup, new ModRuntimeFindingStore(), null,
                temp.resolve("keyboard-live"));
        ModManagerScreen gamepadScreen = screen(catalog, startup, new ModRuntimeFindingStore(), null,
                temp.resolve("gamepad-live"));

        SonicConfigurationService keyboardConfig = SonicConfigurationService.createStandalone(
                temp.resolve("keyboard-config"));
        InputHandler keyboard = new InputHandler(InputBindingFactory.supplier(keyboardConfig));
        liveKeyboardPress(keyboardScreen, keyboard, keyboardConfig.getInt(SonicConfiguration.DOWN));
        liveKeyboardPress(keyboardScreen, keyboard, keyboardConfig.getInt(SonicConfiguration.JUMP));
        liveKeyboardPress(keyboardScreen, keyboard, keyboardConfig.getInt(SonicConfiguration.LEFT));
        liveKeyboardPress(keyboardScreen, keyboard, GLFW_KEY_ESCAPE);

        SonicConfigurationService gamepadConfig = SonicConfigurationService.createStandalone(
                temp.resolve("gamepad-config"));
        gamepadConfig.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        gamepadConfig.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        gamepadConfig.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler gamepad = new InputHandler(InputBindingFactory.supplier(gamepadConfig), source);
        liveGamepadPress(gamepadScreen, gamepad, source, GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        liveGamepadPress(gamepadScreen, gamepad, source, GLFW_GAMEPAD_BUTTON_X);
        liveGamepadPress(gamepadScreen, gamepad, source, GLFW_GAMEPAD_BUTTON_DPAD_LEFT);
        liveGamepadPress(gamepadScreen, gamepad, source, GLFW_GAMEPAD_BUTTON_B);

        assertEquals(keyboardScreen.selectedIndex(), gamepadScreen.selectedIndex());
        assertEquals(keyboardScreen.pendingState(), gamepadScreen.pendingState());
        assertEquals(keyboardScreen.closeRequested(), gamepadScreen.closeRequested());
    }

    @Test
    void dependencyCascadeArmsCancelsAndCommitsWithoutChangingEffectiveSnapshot() {
        ModDescriptor core = descriptor("pack-core", "Core", List.of(), List.of());
        ModDescriptor addon = descriptor("pack-addon", "Addon",
                List.of(new ModDependency("pack-core", VersionRange.parse("*"))), List.of());
        EffectiveModCatalog effective = new EffectiveModCatalog(List.of(core));
        ModCatalog catalog = new ModCatalog(List.of(core, addon), effective, Map.of(
                "pack-core", new ModEligibility("pack-core", ModEligibility.Status.EFFECTIVE, List.of()),
                "pack-addon", new ModEligibility("pack-addon", ModEligibility.Status.DISABLED, List.of(
                        new ModEligibility.Reason("DISABLED", "Disabled", List.of())))));
        ModManagerScreen screen = screen(catalog, state(false, false, "pack-core", "pack-addon"),
                new ModRuntimeFindingStore(), null, temp.resolve("mods"));

        press(screen, Action.DOWN);
        press(screen, Action.ACCEPT);
        assertTrue(screen.cascadeArmed());
        assertFalse(enabled(screen, "pack-addon"));
        press(screen, Action.BACK);
        assertFalse(screen.cascadeArmed());
        assertFalse(screen.closeRequested());

        press(screen, Action.ACCEPT);
        press(screen, Action.ACCEPT);
        assertTrue(enabled(screen, "pack-core"));
        assertTrue(enabled(screen, "pack-addon"));
        assertSame(effective, catalog.effective());
        assertEquals(List.of(core), effective.orderedEnabled());

        screen.select(0);
        press(screen, Action.ACCEPT);
        assertTrue(screen.cascadeArmed());
        press(screen, Action.ACCEPT);
        assertFalse(enabled(screen, "pack-core"));
        assertFalse(enabled(screen, "pack-addon"));
    }

    @Test
    void errorsAndBlockedEligibilityRefuseEnableAndNewModsStartDisabled() {
        ModDescriptor error = descriptor("pack-error", "Error", List.of(), List.of(
                finding(ModFindingSeverity.ERROR, "BAD_AUDIO", "Invalid audio")));
        ModDescriptor blocked = descriptor("pack-blocked", "Blocked", List.of(), List.of());
        ModCatalog catalog = new ModCatalog(List.of(error, blocked), EffectiveModCatalog.EMPTY, Map.of(
                "pack-error", new ModEligibility("pack-error", ModEligibility.Status.BLOCKED, List.of(
                        new ModEligibility.Reason("CATALOG_ERROR", "Has errors", List.of()))),
                "pack-blocked", new ModEligibility("pack-blocked", ModEligibility.Status.BLOCKED, List.of(
                        new ModEligibility.Reason("MISSING_DEPENDENCY", "Missing pack-core", List.of("pack-core"))))));
        ModManagerScreen screen = screen(catalog, ModState.EMPTY, new ModRuntimeFindingStore(), null,
                temp.resolve("mods"));

        assertFalse(enabled(screen, "pack-error"));
        assertFalse(enabled(screen, "pack-blocked"));
        press(screen, Action.ACCEPT);
        assertFalse(enabled(screen, "pack-error"));
        assertTrue(screen.statusMessage().contains("cannot be enabled"));
        press(screen, Action.DOWN); press(screen, Action.ACCEPT);
        assertFalse(enabled(screen, "pack-blocked"));
        assertTrue(screen.statusMessage().contains("Missing pack-core"));
    }

    @Test
    void codeTrustRequiresTwoAcceptsAndOnlyChangesPendingState() {
        ModDescriptor code = codeDescriptor("code-mod", "Code Mod", "c".repeat(64));
        ModCatalog catalog = new ModCatalog(List.of(code), EffectiveModCatalog.EMPTY, Map.of(
                "code-mod", new ModEligibility("code-mod", ModEligibility.Status.BLOCKED, List.of(
                        new ModEligibility.Reason("CODE_TRUST_REQUIRED",
                                "contains code — trust required (press accept twice)", List.of())))));
        ModManagerScreen screen = screen(catalog, state(false, "code-mod"),
                new ModRuntimeFindingStore(), null, temp.resolve("trust"));

        assertTrue(screen.rows().getFirst().badges().contains("TRUST REQUIRED"));
        press(screen, Action.ACCEPT);
        assertFalse(enabled(screen, "code-mod"));
        assertFalse(screen.pendingState().entries().getFirst().trusted());
        assertTrue(screen.statusMessage().contains(
                "This mod contains code and runs with full permissions"));

        press(screen, Action.ACCEPT);
        ModState.Entry pending = screen.pendingState().entries().getFirst();
        assertTrue(pending.enabled());
        assertTrue(pending.trusted());
        assertEquals(code.sha256(), pending.trustedJarSha256());
        assertTrue(screen.restartRequired());
        assertTrue(catalog.effective().orderedEnabled().isEmpty());

        press(screen, Action.BACK);
        assertTrue(screen.closeRequested());
        ModState persisted = new ModStateStore(temp.resolve("trust").toAbsolutePath().normalize())
                .load().state();
        assertTrue(persisted.entries().getFirst().trustsSha256(code.sha256()));
    }

    @Test
    void trustArmCancelsOnNavigationAndCascadeNeverTrustsACodeDependency() {
        ModDescriptor code = codeDescriptor("code-core", "Code Core", "d".repeat(64));
        ModDescriptor addon = descriptor("data-addon", "Data Addon",
                List.of(new ModDependency("code-core", VersionRange.parse("*"))), List.of());
        ModCatalog catalog = new ModCatalog(List.of(code, addon), EffectiveModCatalog.EMPTY, Map.of(
                "code-core", new ModEligibility("code-core", ModEligibility.Status.BLOCKED, List.of(
                        new ModEligibility.Reason("CODE_TRUST_REQUIRED",
                                "contains code — trust required (press accept twice)", List.of()))),
                "data-addon", new ModEligibility("data-addon", ModEligibility.Status.DISABLED, List.of(
                        new ModEligibility.Reason("DISABLED", "Disabled", List.of())))));
        ModManagerScreen screen = screen(catalog,
                state(false, false, "code-core", "data-addon"),
                new ModRuntimeFindingStore(), null, temp.resolve("cascade-trust"));

        press(screen, Action.ACCEPT);
        assertTrue(screen.trustArmed());
        press(screen, Action.BACK);
        assertFalse(screen.trustArmed());
        assertFalse(screen.closeRequested());
        assertFalse(screen.pendingState().entries().getFirst().trusted());

        press(screen, Action.ACCEPT);
        assertTrue(screen.trustArmed());
        press(screen, Action.DOWN);
        assertFalse(screen.trustArmed());
        assertFalse(screen.pendingState().entries().getFirst().trusted());

        press(screen, Action.ACCEPT);
        assertFalse(enabled(screen, "data-addon"));
        assertFalse(screen.pendingState().entries().getFirst().trusted());
        assertTrue(screen.statusMessage().contains("trust required"));
    }

    @Test
    void codeModWithDataDependencyTrustsAndEnablesTheWholeCascadeOnSecondAccept() {
        ModDescriptor data = descriptor("data-core", "Data Core", List.of(), List.of());
        ModDescriptor code = codeDescriptor("code-addon", "Code Addon", "e".repeat(64),
                List.of(new ModDependency("data-core", VersionRange.parse("*"))));
        ModCatalog catalog = new ModCatalog(List.of(data, code), EffectiveModCatalog.EMPTY, Map.of(
                "data-core", new ModEligibility("data-core", ModEligibility.Status.DISABLED, List.of(
                        new ModEligibility.Reason("DISABLED", "Disabled", List.of()))),
                "code-addon", new ModEligibility("code-addon", ModEligibility.Status.BLOCKED, List.of(
                        new ModEligibility.Reason("CODE_TRUST_REQUIRED",
                                "contains code — trust required (press accept twice)", List.of())))));
        ModManagerScreen screen = screen(catalog,
                state(false, false, "data-core", "code-addon"),
                new ModRuntimeFindingStore(), null, temp.resolve("combined-trust"));
        screen.select(1);

        press(screen, Action.ACCEPT);
        assertTrue(screen.trustArmed());
        assertFalse(enabled(screen, "data-core"));
        assertFalse(enabled(screen, "code-addon"));

        press(screen, Action.ACCEPT);
        assertFalse(screen.trustArmed());
        assertTrue(enabled(screen, "data-core"));
        assertTrue(enabled(screen, "code-addon"));
        assertTrue(screen.pendingState().entries().stream()
                .filter(entry -> entry.id().equals("code-addon"))
                .findFirst().orElseThrow().trustsSha256(code.sha256()));
    }

    @Test
    void dataOnlyModTogglesOnceWithoutAcquiringTrust() {
        ModDescriptor data = descriptor("data-only", "Data Only", List.of(), List.of());
        ModManagerScreen screen = screen(catalog(List.of(data)), state(false, "data-only"),
                new ModRuntimeFindingStore(), null, temp.resolve("data-only"));

        press(screen, Action.ACCEPT);

        ModState.Entry pending = screen.pendingState().entries().getFirst();
        assertTrue(pending.enabled());
        assertFalse(pending.trusted());
        assertFalse(screen.trustArmed());
    }

    @Test
    void pendingTrustReevaluatesDependencyBlockersWithoutASecondRestart() {
        ModDescriptor code = codeDescriptor("code-core", "Code Core", "f".repeat(64));
        ModDescriptor addon = descriptor("data-addon", "Data Addon",
                List.of(new ModDependency("code-core", VersionRange.parse("*"))), List.of());
        ModState startup = state(false, false, "code-core", "data-addon");
        ModCatalog frozen = new com.openggf.mods.EffectiveCatalogBuilder()
                .build(List.of(code, addon), startup);
        assertTrue(frozen.eligibility().get("data-addon").reasons().stream()
                .anyMatch(reason -> reason.code().equals("DEPENDENCY_BLOCKED")));
        ModManagerScreen screen = screen(frozen, startup, new ModRuntimeFindingStore(), null,
                temp.resolve("pending-eligibility"));

        press(screen, Action.ACCEPT);
        press(screen, Action.ACCEPT);
        assertTrue(enabled(screen, "code-core"));
        press(screen, Action.DOWN);
        press(screen, Action.ACCEPT);

        assertTrue(enabled(screen, "data-addon"));
        assertTrue(screen.restartRequired());
        assertTrue(frozen.effective().orderedEnabled().isEmpty());
    }

    @Test
    void trustedDisabledCodeDependencyParticipatesInProposedEnableCascade() {
        ModDescriptor code = codeDescriptor("code-core", "Code Core", "1".repeat(64));
        ModDescriptor addon = descriptor("data-addon", "Data Addon",
                List.of(new ModDependency("code-core", VersionRange.parse("*"))), List.of());
        ModState startup = new ModState(1, List.of(
                new ModState.Entry("code-core", false, 0, true, code.sha256()),
                new ModState.Entry("data-addon", false, 1)));
        ModCatalog frozen = new com.openggf.mods.EffectiveCatalogBuilder()
                .build(List.of(code, addon), startup);
        ModManagerScreen screen = screen(frozen, startup, new ModRuntimeFindingStore(), null,
                temp.resolve("trusted-cascade"));
        screen.select(1);

        press(screen, Action.ACCEPT);
        assertTrue(screen.cascadeArmed());
        press(screen, Action.ACCEPT);

        assertTrue(enabled(screen, "code-core"));
        assertTrue(enabled(screen, "data-addon"));
        assertTrue(screen.pendingState().entries().getFirst().trustsSha256(code.sha256()));
    }

    @Test
    void trustPromptDoesNotMaskStructuralBlockerBehindTrustGate() {
        ModManifest manifest = new ModManifest(1, "invalid-code", "Invalid Code",
                SemanticVersion.parse("1.2.3"), List.of("Alice"), "Compiled mod",
                VersionRange.parse(">=0.7.0 <0.8.0"), ModType.PATCH, "s2", "example.Code",
                List.of(), Map.of(), Map.of(), "not-a-stock-anchor", OptionalInt.empty());
        ModDescriptor code = new ModDescriptor(Path.of("invalid-code.jar"), manifest,
                "2".repeat(64), true, List.of());
        ModState startup = state(false, "invalid-code");
        ModCatalog frozen = new com.openggf.mods.EffectiveCatalogBuilder()
                .build(List.of(code), startup);
        assertTrue(frozen.eligibility().get("invalid-code").reasons().stream()
                .anyMatch(reason -> reason.code().equals("CODE_TRUST_REQUIRED")));
        ModManagerScreen screen = screen(frozen, startup, new ModRuntimeFindingStore(), null,
                temp.resolve("masked-blocker"));

        press(screen, Action.ACCEPT);

        assertFalse(screen.trustArmed());
        assertFalse(enabled(screen, "invalid-code"));
        assertFalse(screen.pendingState().entries().getFirst().trusted());
        assertTrue(screen.statusMessage().contains("cannot be enabled"));
        assertTrue(screen.statusMessage().contains("insertAfter"));
    }

    @Test
    void reorderHonorsDependencyTopologyButAllowsIndependentMoves() {
        ModDescriptor core = descriptor("pack-core", "Core", List.of(), List.of());
        ModDescriptor addon = descriptor("pack-addon", "Addon",
                List.of(new ModDependency("pack-core", VersionRange.parse("*"))), List.of());
        ModDescriptor other = descriptor("pack-other", "Other", List.of(), List.of());
        ModManagerScreen screen = screen(catalog(List.of(core, addon, other)),
                state(false, false, false, "pack-core", "pack-addon", "pack-other"),
                new ModRuntimeFindingStore(), null, temp.resolve("mods"));

        press(screen, Action.DOWN);
        press(screen, Action.LEFT);
        assertEquals(List.of("pack-core", "pack-addon", "pack-other"), pendingIds(screen));
        assertTrue(screen.statusMessage().contains("dependency order"));

        press(screen, Action.DOWN);
        press(screen, Action.LEFT);
        assertEquals(List.of("pack-core", "pack-other", "pack-addon"), pendingIds(screen));
        assertTrue(screen.restartRequired());
    }

    @Test
    void backSavesAndClosesOnSuccessButFailureBannerKeepsScreenOpen() throws Exception {
        ModDescriptor mod = descriptor("pack-one", "One", List.of(), List.of());
        ModCatalog catalog = catalog(List.of(mod));
        ModManagerScreen success = screen(catalog, state(false, "pack-one"),
                new ModRuntimeFindingStore(), null, temp.resolve("success"));
        press(success, Action.ACCEPT);
        press(success, Action.BACK);
        assertTrue(success.closeRequested());
        assertTrue(Files.exists(temp.resolve("success/modstate.json")));

        Path invalidRoot = temp.resolve("not-a-directory");
        Files.writeString(invalidRoot, "occupied");
        ModManagerScreen failure = screen(catalog, state(false, "pack-one"),
                new ModRuntimeFindingStore(), null, invalidRoot);
        press(failure, Action.ACCEPT);
        press(failure, Action.BACK);
        assertFalse(failure.closeRequested());
        assertTrue(failure.bannerLines().stream().anyMatch(line -> line.startsWith("Save failed:")));
    }

    @Test
    void orphanRuntimeFindingsAppearGloballyWithoutDuplicatingCatalogOwnerRows() {
        ModDescriptor catalogMod = descriptor("pack-one", "One", List.of(), List.of());
        ModRuntimeFindingStore runtime = new ModRuntimeFindingStore();
        runtime.upsertOwnerFinding("pack-one", finding(
                ModFindingSeverity.WARNING, "ROW_WARNING", "shown on row"));
        runtime.upsertOwnerFinding("disabled-owner", finding(
                ModFindingSeverity.WARNING, "S2_MOD_ZONE_MISSING", "saved zone missing"));
        runtime.upsertOwnerFinding("s2-save", finding(
                ModFindingSeverity.WARNING, "S2_LEGACY_ZONE_OUT_OF_RANGE", "legacy fallback"));
        ModManagerScreen screen = screen(catalog(List.of(catalogMod)), state(false, "pack-one"),
                runtime, null, temp.resolve("orphan-findings"));

        assertTrue(screen.bannerLines().stream().anyMatch(line -> line.contains("[disabled-owner]")
                && line.contains("S2_MOD_ZONE_MISSING")));
        assertTrue(screen.bannerLines().stream().anyMatch(line -> line.contains("[s2-save]")
                && line.contains("S2_LEGACY_ZONE_OUT_OF_RANGE")));
        assertFalse(screen.bannerLines().stream().anyMatch(line -> line.contains("ROW_WARNING")),
                "catalog owners retain their row badge/detail path and must not duplicate globally");
    }

    @Test
    void renderedDetailsAreBoundedAndStartDirectionScrollsAllFindings() {
        List<ModFinding> findings = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            findings.add(finding(ModFindingSeverity.WARNING, "WARNING_" + index,
                    "Finding number " + index));
        }
        RecordingFont font = new RecordingFont();
        ModManagerScreen screen = screen(catalog(List.of(
                        descriptor("pack-many", "Many", List.of(), findings))),
                state(false, "pack-many"), new ModRuntimeFindingStore(), font, temp.resolve("mods"));

        assertEquals(ModManagerScreen.MAX_VISIBLE_DETAILS, screen.visibleDetailLines().size());
        screen.render();
        assertTrue(font.drawn.size() <= ModManagerScreen.MAX_VISIBLE_DETAILS
                + ModManagerScreen.MAX_VISIBLE_ROWS + 8);
        screen.update(menu(logicalStartDirection(Action.DOWN)));
        screen.update(ModManagerScreen.MenuInput.NEUTRAL);
        assertTrue(screen.detailScrollOffset() > 0);
        assertTrue(screen.visibleDetailLines().stream().anyMatch(line -> line.contains("Finding number")));
    }

    @Test
    void conventionalEscapeBackSavesOnceThroughLiveInputHandler() {
        ModDescriptor mod = descriptor("pack-one", "One", List.of(), List.of());
        ModManagerScreen screen = screen(catalog(List.of(mod)), state(false, "pack-one"),
                new ModRuntimeFindingStore(), null, temp.resolve("escape"));
        com.openggf.control.InputHandler input = new com.openggf.control.InputHandler();
        ModManagerScreenHost host = new ModManagerScreenHost(screen);
        input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        input.refreshLogicalSnapshot();

        host.update(input);
        host.update(input);

        assertTrue(screen.closeRequested());
        assertTrue(Files.exists(temp.resolve("escape/modstate.json")));
    }

    @Test
    void duplicateDescriptorIdsRemainDistinctFilenameQualifiedRows() {
        ModDescriptor first = descriptorAt("pack-duplicate", "Duplicate", temp.resolve("first.jar"), List.of(),
                List.of(finding(ModFindingSeverity.ERROR, "DUPLICATE_MOD_ID", "Duplicate id")));
        ModDescriptor second = descriptorAt("pack-duplicate", "Duplicate", temp.resolve("second.jar"), List.of(),
                List.of(finding(ModFindingSeverity.ERROR, "DUPLICATE_MOD_ID", "Duplicate id")));
        ModCatalog catalog = new ModCatalog(List.of(first, second), EffectiveModCatalog.EMPTY, Map.of(
                "pack-duplicate", new ModEligibility("pack-duplicate", ModEligibility.Status.BLOCKED, List.of(
                        new ModEligibility.Reason("DUPLICATE_MOD_ID", "Duplicate id", List.of())))));
        RecordingFont font = new RecordingFont();
        ModManagerScreen screen = screen(catalog, state(false, "pack-duplicate"),
                new ModRuntimeFindingStore(), font, temp.resolve("mods"));

        assertEquals(2, screen.rows().size());
        assertEquals(List.of("pack-duplicate (first.jar)", "pack-duplicate (second.jar)"),
                screen.rows().stream().map(ModManagerScreen.RowView::identity).toList());
        assertTrue(screen.detailLines().stream().anyMatch(line -> line.contains("first.jar")));
        screen.select(1);
        assertTrue(screen.detailLines().stream().anyMatch(line -> line.contains("second.jar")));
        screen.render();
        assertTrue(font.drawn.stream().anyMatch(line -> line.contains("first.jar")));
        assertTrue(font.drawn.stream().anyMatch(line -> line.contains("second.jar")));

        screen.select(0);
        press(screen, Action.RIGHT); // actions
        press(screen, Action.RIGHT); // Order
        press(screen, Action.ACCEPT);
        press(screen, Action.DOWN);
        assertEquals(2, screen.rows().size());
        assertTrue(screen.statusMessage().contains("Duplicate"));
        screen.select(1);
        press(screen, Action.LEFT);
        assertEquals(2, screen.rows().size());
        assertTrue(screen.statusMessage().contains("Duplicate"));
    }

    @Test
    void openingInputIsSuppressedUntilNeutral() {
        ModDescriptor mod = descriptor("pack-one", "One", List.of(), List.of());
        ModManagerScreen screen = screen(catalog(List.of(mod)), state(false, "pack-one"),
                new ModRuntimeFindingStore(), null, temp.resolve("mods"));
        screen.suppressInputUntilNeutral();
        LogicalInputSnapshot heldAccept = logical(Action.ACCEPT);

        screen.update(menu(heldAccept));
        screen.update(menu(heldAccept));
        assertFalse(enabled(screen, "pack-one"));
        screen.update(ModManagerScreen.MenuInput.NEUTRAL);
        press(screen, Action.ACCEPT);
        assertTrue(enabled(screen, "pack-one"));
    }

    @Test
    void openingManagerRepairsPersistedDependencyOrderBeforeCascadeSave() {
        ModDescriptor core = descriptor("pack-core", "Core", List.of(), List.of());
        ModDescriptor addon = descriptor("pack-addon", "Addon",
                List.of(new ModDependency("pack-core", VersionRange.parse("*"))), List.of());
        ModManagerScreen screen = screen(catalog(List.of(addon, core)),
                state(false, false, "pack-addon", "pack-core"),
                new ModRuntimeFindingStore(), null, temp.resolve("mods"));

        assertEquals(List.of("pack-core", "pack-addon"), pendingIds(screen));
        screen.select(1);
        press(screen, Action.ACCEPT);
        press(screen, Action.ACCEPT);
        assertTrue(enabled(screen, "pack-core"));
        assertTrue(enabled(screen, "pack-addon"));
        assertEquals(List.of("pack-core", "pack-addon"), pendingIds(screen));
    }

    @Test
    void topologyRepairRetainsUnknownPersistedIdsWithoutTryingToEditThem() {
        ModDescriptor core = descriptor("pack-core", "Core", List.of(), List.of());
        ModDescriptor addon = descriptor("pack-addon", "Addon",
                List.of(new ModDependency("pack-core", VersionRange.parse("*"))), List.of());
        ModState startup = new ModState(ModState.CURRENT_FORMAT_VERSION, List.of(
                new ModState.Entry("pack-addon", false, 0),
                new ModState.Entry("temporarily-missing", true, 1),
                new ModState.Entry("pack-core", false, 2)));

        ModManagerScreen screen = screen(catalog(List.of(addon, core)), startup,
                new ModRuntimeFindingStore(), null, temp.resolve("mods"));

        List<String> ids = pendingIds(screen);
        assertTrue(ids.contains("temporarily-missing"));
        assertTrue(ids.indexOf("pack-core") < ids.indexOf("pack-addon"));
    }

    private ModManagerScreen screen(ModCatalog catalog, ModState startup,
                                    ModRuntimeFindingStore runtime, PixelFont font, Path root) {
        PendingModStateEditor editor = new PendingModStateEditor(startup, catalog.scanned(),
                new ModStateStore(root.toAbsolutePath().normalize()));
        return new ModManagerScreen(catalog, editor, runtime, font == null ? null : textSink(font));
    }

    private static ModCatalog catalog(List<ModDescriptor> descriptors) {
        Map<String, ModEligibility> eligibility = new HashMap<>();
        for (ModDescriptor descriptor : descriptors) {
            eligibility.put(descriptor.manifest().id(), new ModEligibility(descriptor.manifest().id(),
                    ModEligibility.Status.DISABLED,
                    List.of(new ModEligibility.Reason("DISABLED", "Disabled", List.of()))));
        }
        return new ModCatalog(List.copyOf(descriptors), EffectiveModCatalog.EMPTY, eligibility);
    }

    static ModDescriptor descriptor(String id, String name, List<ModDependency> dependencies,
                                    List<ModFinding> findings) {
        return descriptorAt(id, name, Path.of(id + ".jar"), dependencies, findings);
    }

    private static ModDescriptor descriptorWithWindows(String id, String name, int windows) {
        ModManifest manifest = new ModManifest(1, id, name, SemanticVersion.parse("1.2.3"),
                List.of("Alice"), "Pattern owner", VersionRange.parse(">=0.7.0 <0.8.0"),
                ModType.PATCH, "s2", null, List.of(), Map.of(), Map.of(), null,
                OptionalInt.of(windows));
        return new ModDescriptor(Path.of(id + ".jar"), manifest, "a".repeat(64), false, List.of());
    }

    private static ModDescriptor descriptorAt(String id, String name, Path path,
                                              List<ModDependency> dependencies,
                                              List<ModFinding> findings) {
        ModManifest manifest = new ModManifest(1, id, name, SemanticVersion.parse("1.2.3"),
                List.of("Alice", "Bob"), "A detailed music pack", VersionRange.parse(">=0.7.0 <0.8.0"),
                ModType.PATCH, "s2", null, dependencies, Map.of(), Map.of(), null, OptionalInt.empty());
        return new ModDescriptor(path, manifest, "a".repeat(64), false, findings);
    }

    static ModDescriptor codeDescriptor(String id, String name, String hash) {
        return codeDescriptor(id, name, hash, List.of());
    }

    static ModDescriptor codeDescriptor(String id, String name, String hash,
                                        List<ModDependency> dependencies) {
        ModManifest manifest = new ModManifest(1, id, name, SemanticVersion.parse("1.2.3"),
                List.of("Alice"), "Compiled mod", VersionRange.parse(">=0.7.0 <0.8.0"),
                ModType.PATCH, "s2", "example.Code", dependencies, Map.of(), Map.of(), null,
                OptionalInt.empty());
        return new ModDescriptor(Path.of(id + ".jar"), manifest, hash, true, List.of());
    }

    private static ModFinding finding(ModFindingSeverity severity, String code, String message) {
        return new ModFinding(severity, code, message, null);
    }

    private static ModState state(boolean... enabled) {
        String[] ids = new String[enabled.length];
        for (int index = 0; index < ids.length; index++) ids[index] = "pack-mod" + index;
        return state(enabled, ids);
    }

    private static ModState state(boolean enabled, String id) {
        return state(new boolean[]{enabled}, id);
    }

    private static ModState state(boolean first, boolean second, String firstId, String secondId) {
        return state(new boolean[]{first, second}, firstId, secondId);
    }

    private static ModState state(boolean first, boolean second) {
        return state(new boolean[]{first, second}, "pack-core", "pack-addon");
    }

    private static ModState state(boolean first, boolean second, boolean third,
                                  String firstId, String secondId, String thirdId) {
        return state(new boolean[]{first, second, third}, firstId, secondId, thirdId);
    }

    static ModState state(boolean[] enabled, String... ids) {
        List<ModState.Entry> entries = new ArrayList<>();
        for (int index = 0; index < ids.length; index++) {
            entries.add(new ModState.Entry(ids[index], enabled[index], index));
        }
        return new ModState(ModState.CURRENT_FORMAT_VERSION, entries);
    }

    static boolean enabled(ModManagerScreen screen, String id) {
        return screen.pendingState().entries().stream()
                .filter(entry -> entry.id().equals(id)).findFirst().orElseThrow().enabled();
    }

    private static List<String> pendingIds(ModManagerScreen screen) {
        return screen.pendingState().entries().stream().map(ModState.Entry::id).toList();
    }

    private static void press(ModManagerScreen screen, Action action) {
        screen.update(menu(logical(action)));
        screen.update(ModManagerScreen.MenuInput.NEUTRAL);
    }

    static void accept(ModManagerScreen screen) {
        press(screen, Action.ACCEPT);
    }

    static void select(ModManagerScreen screen, String id) {
        List<ModManagerScreen.RowView> rows = screen.rows();
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).identity().equals(id)) {
                screen.select(index);
                return;
            }
        }
        throw new AssertionError("Missing mod manager row: " + id);
    }

    static ModManagerScreen nativeGuardScreen(Path root, List<ModDescriptor> descriptors,
                                               ModState startup, boolean compiledModsSupported) {
        ModCatalog catalog = new EffectiveCatalogBuilder().build(descriptors, startup);
        PendingModStateEditor editor = new PendingModStateEditor(startup, catalog.scanned(),
                new ModStateStore(root.toAbsolutePath().normalize()));
        return new ModManagerScreen(catalog, editor, new ModRuntimeFindingStore(), null,
                PatternWindowState.EMPTY, compiledModsSupported);
    }

    private static LogicalInputSnapshot logical(Action action) {
        int direction = switch (action) {
            case UP -> AbstractPlayableSprite.INPUT_UP;
            case DOWN -> AbstractPlayableSprite.INPUT_DOWN;
            case LEFT -> AbstractPlayableSprite.INPUT_LEFT;
            case RIGHT -> AbstractPlayableSprite.INPUT_RIGHT;
            default -> 0;
        };
        int actionMask = action == Action.ACCEPT ? InputActionMasks.ACTION_A
                : action == Action.BACK ? InputActionMasks.ACTION_C : 0;
        PlayerInputState player = PlayerInputState.of(direction, direction, actionMask, actionMask,
                false, false);
        return LogicalInputSnapshot.ofPlayers(player, PlayerInputState.neutral());
    }

    private static LogicalInputSnapshot logicalStartDirection(Action action) {
        int direction = action == Action.DOWN ? AbstractPlayableSprite.INPUT_DOWN
                : AbstractPlayableSprite.INPUT_UP;
        PlayerInputState player = PlayerInputState.of(direction, direction, 0, 0, true, false);
        return LogicalInputSnapshot.ofPlayers(player, PlayerInputState.neutral());
    }

    private static void liveKeyboardPress(ModManagerScreen screen, InputHandler input, int key) {
        ModManagerScreenHost host = new ModManagerScreenHost(screen);
        input.handleKeyEvent(key, GLFW_PRESS);
        input.refreshLogicalSnapshot();
        host.update(input);
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
        input.refreshLogicalSnapshot();
        host.update(input);
    }

    private static void liveGamepadPress(ModManagerScreen screen, InputHandler input,
                                         FakeGamepadStateSource source, int button) {
        ModManagerScreenHost host = new ModManagerScreenHost(screen);
        source.setButtons(button);
        input.refreshLogicalSnapshot();
        host.update(input);
        source.setButtons();
        input.update();
        input.refreshLogicalSnapshot();
        host.update(input);
    }

    @Test
    void visibleDetailsAndOrderActionsPreserveTheSelectedModAndSaveFlow() {
        List<ModDescriptor> mods = List.of(descriptor("pack-a", "A", List.of(), List.of()),
                descriptor("pack-b", "B", List.of(), List.of()),
                descriptor("pack-c", "C", List.of(), List.of()));
        RecordingFont font = new RecordingFont();
        ModManagerScreen screen = screen(catalog(mods), state(false, false, false, "pack-a", "pack-b", "pack-c"),
                new ModRuntimeFindingStore(), font, temp.resolve("visible-actions"));
        press(screen, Action.DOWN);
        press(screen, Action.RIGHT);
        press(screen, Action.ACCEPT);
        screen.render();
        assertTrue(font.drawn.contains("MOD DETAILS"));
        assertTrue(font.drawn.stream().anyMatch(line -> line.contains("Id: pack-b")));
        assertEquals(1, screen.selectedIndex());
        press(screen, Action.BACK);
        assertFalse(screen.closeRequested());
        press(screen, Action.RIGHT);
        press(screen, Action.ACCEPT);
        press(screen, Action.DOWN);
        assertEquals(List.of("pack-a", "pack-c", "pack-b"), pendingIds(screen));
        press(screen, Action.BACK);
        press(screen, Action.RIGHT);
        press(screen, Action.RIGHT);
        press(screen, Action.ACCEPT);
        assertTrue(screen.closeRequested());
        assertTrue(Files.exists(temp.resolve("visible-actions/modstate.json")));
    }

    @Test
    void longDetailsAndManyNoticesStayOnNativeGridAndRemainReadableByPaging() {
        List<ModFinding> findings = new ArrayList<>();
        for (int i = 0; i < 35; i++) findings.add(finding(ModFindingSeverity.WARNING, "LONG_" + i,
                "A long explanation with exact useful information ".repeat(3) + "END_MARKER_" + i));
        ModDescriptor mod = descriptor("pack-verbose", "A very long mod name ".repeat(8), List.of(), findings);
        RecordingFont font = new RecordingFont();
        ModManagerScreen screen = screen(catalog(List.of(mod)), state(false, "pack-verbose"),
                new ModRuntimeFindingStore(), font, temp.resolve("verbose"));
        screen.render();
        press(screen, Action.RIGHT);
        press(screen, Action.ACCEPT);
        for (int i = 0; i < 300; i++) press(screen, Action.DOWN);
        screen.render();
        assertTrue(font.drawn.stream().anyMatch(line -> line.contains("END_MARKER_34")),
                "Wrapping must retain the final text, not permanently truncate it");
        for (RenderedLine line : font.lines) {
            int advance = line.scale() < 1 ? 6 : 9;
            int height = line.scale() < 1 ? 8 : 10;
            assertTrue(line.x() >= 0 && line.x() + line.value().length() * advance <= 320,
                    () -> "Horizontal overflow: " + line);
            assertTrue(line.y() >= 0 && line.y() + height <= 224,
                    () -> "Vertical overflow: " + line);
            assertTrue(line.scale() == 1f || line.scale() == 2f / 3f,
                    "Only native menu font sizes are used");
        }
    }

    @Test
    void adaptiveLabelsAndDetailsBackDoNotSaveOrToggle() {
        RecordingFont font = new RecordingFont();
        ModManagerScreen screen = screen(catalog(List.of(descriptor("pack-one", "One", List.of(), List.of()))),
                state(false, "pack-one"), new ModRuntimeFindingStore(), font, temp.resolve("labels"));
        screen.update(new ModManagerScreen.MenuInput() {
            @Override public String confirmLabel() { return "A"; }
            @Override public String backLabel() { return "B"; }
            @Override public String directionLabel() { return "D-Pad"; }
        });
        screen.render();
        assertTrue(font.drawn.stream().anyMatch(line -> line.startsWith("A Toggle")));
        assertTrue(font.drawn.stream().anyMatch(line -> line.startsWith("B Save+Back")));
        press(screen, Action.RIGHT);
        press(screen, Action.ACCEPT);
        press(screen, Action.BACK);
        assertFalse(screen.closeRequested());
        assertFalse(enabled(screen, "pack-one"));
        assertFalse(Files.exists(temp.resolve("labels/modstate.json")));
    }

    private static ModManagerScreen.MenuInput menu(LogicalInputSnapshot input) {
        return menu(input, false);
    }

    private static ModManagerScreen.MenuInput menu(LogicalInputSnapshot input, boolean escape) {
        return new ModManagerScreen.MenuInput() {
            @Override public boolean menuUp() { return input.menuUp(); }
            @Override public boolean menuDown() { return input.menuDown(); }
            @Override public boolean menuLeft() { return input.menuLeft(); }
            @Override public boolean menuRight() { return input.menuRight(); }
            @Override public boolean menuAccept() { return input.menuAccept(); }
            @Override public boolean menuBack() { return input.menuBack(); }
            @Override public boolean startHeld() { return input.player1().startHeld(); }
            @Override public boolean escape() { return escape; }
        };
    }

    private static ModManagerScreen.TextSink textSink(PixelFont font) {
        return new ModManagerScreen.TextSink() {
            @Override public void begin() { font.beginMegaBatch(); }
            @Override public void draw(String text, int x, int y, float scale,
                                       float r, float g, float b, float a) {
                font.drawText(text, x, y, scale, r, g, b, a);
            }
            @Override public void end() { font.endMegaBatch(); }
        };
    }

    private enum Action { UP, DOWN, LEFT, RIGHT, ACCEPT, BACK }

    private record RenderedLine(String value, int x, int y, float scale) { }

    private static final class RecordingFont extends PixelFont {
        private final List<String> drawn = new ArrayList<>();
        private final List<RenderedLine> lines = new ArrayList<>();

        @Override
        public void beginMegaBatch() { }

        @Override
        public void endMegaBatch() { }

        @Override
        public void drawText(String text, int x, int y, float scale,
                             float r, float g, float b, float a) {
            drawn.add(text);
            lines.add(new RenderedLine(text, x, y, scale));
        }
    }

    private static final class FakeGamepadStateSource implements GamepadStateSource {
        private final List<DeviceState> devices = new ArrayList<>();

        void setButtons(int... pressedButtons) {
            boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
            for (int button : pressedButtons) buttons[button] = true;
            devices.clear();
            devices.add(DeviceState.connected(0, "pad", buttons, 0f, 0f));
        }

        @Override
        public List<DeviceState> pollDevices() {
            return List.copyOf(devices);
        }
    }
}
