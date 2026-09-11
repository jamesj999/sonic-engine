package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.DataSelectProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.dataselect.AbstractDataSelectProvider;
import com.openggf.game.dataselect.DataSelectActionType;
import com.openggf.game.dataselect.DataSelectPresentationProvider;
import com.openggf.game.dataselect.DataSelectSessionController;
import com.openggf.game.sonic1.dataselect.S1DataSelectImageCacheManager;
import com.openggf.game.sonic1.dataselect.S1DataSelectProfile;
import com.openggf.game.sonic2.dataselect.S2DataSelectProfile;
import com.openggf.game.sonic2.dataselect.S2DataSelectImageCacheManager;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.save.SaveManager;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.Palette;
import com.openggf.level.PatternDesc;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.dataselect.S3kDataSelectManager;
import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.glfwInit;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kDataSelectPresentation {
    @TempDir
    Path root;

    @BeforeAll
    static void configureEngineServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void s3kGameModule_routesToNativeS3kPresentationDelegate() {
        Sonic3kGameModule module = new Sonic3kGameModule();

        DataSelectPresentationProvider provider = module.getDataSelectPresentationProvider();
        DataSelectProvider delegate = provider.delegate();

        assertNotNull(delegate);
        assertTrue(delegate instanceof S3kDataSelectManager,
                "S3K production data select should resolve to the native S3K manager");
        assertSame(provider.controller(),
                ((AbstractDataSelectProvider) delegate).getSessionController(),
                "Presentation delegate should keep using the shared session controller");
    }

    @Test
    void initialize_loadsAssetsAndStartsDataSelectMusic() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        List<Integer> playedMusic = new ArrayList<>();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                playedMusic::add);

        presentation.initialize();

        assertEquals(1, assets.loadCalls);
        assertEquals(List.of(0x2A), playedMusic);
        assertEquals(DataSelectProvider.State.FADE_IN, presentation.getState());
        assertEquals(8, presentation.slotSummaries().size());
    }

    @Test
    void draw_carriesLaunchErrorMessageIntoRenderedObjectState() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();
        presentation.showLaunchError("Unable to load selected save.");
        presentation.draw();

        assertNotNull(renderer.lastObjectState);
        assertEquals("Unable to load selected save.", renderer.lastObjectState.launchErrorMessage(),
                "stored launch error should be included in the object state passed to the renderer");
    }

    @Test
    void update_keepsDataSelectInFadeInForMultipleFramesBeforeActive() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        assertEquals(DataSelectProvider.State.FADE_IN, presentation.getState());
        assertEquals(1.0f, presentation.currentFadeAlpha(), 0.0001f);

        presentation.update(new InputHandler());

        assertEquals(DataSelectProvider.State.FADE_IN, presentation.getState(),
                "Data Select fade-in should last multiple frames");
        assertTrue(presentation.currentFadeAlpha() < 1.0f,
                "Fade alpha should decrease gradually during Data Select fade-in");

        for (int i = 0; i < 19; i++) {
            presentation.update(new InputHandler());
        }

        assertEquals(DataSelectProvider.State.FADE_IN, presentation.getState(),
                "Data Select should still be fading on the final pre-complete frame");

        presentation.update(new InputHandler());

        assertEquals(DataSelectProvider.State.ACTIVE, presentation.getState());
        assertEquals(0.0f, presentation.currentFadeAlpha(), 0.0001f);
    }

    @Test
    void draw_buildsVisualStateFromSlotSummariesAndDeleteMode() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 2,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));
        saveManager.writeSlot("s3k", 2, java.util.Map.of(
                "zone", 3,
                "act", 1,
                "mainCharacter", "knuckles",
                "sidekicks", List.of(),
                "lives", 7,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4),
                "superEmeralds", List.of(),
                "clear", true,
                "progressCode", 11,
                "clearState", 2
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();
        presentation.getSessionController().menuModel().setSelectedTeamIndex(2);
        presentation.getSessionController().menuModel().setDeleteMode(true);
        presentation.draw();

        assertEquals(1, renderer.drawCalls);
        assertNotNull(renderer.lastObjectState);
        assertNotNull(renderer.lastObjectState.layoutObjects());
        assertNotNull(renderer.lastObjectState.selectorState());
        assertNotNull(renderer.lastObjectState.visualState());
        assertEquals(4, renderer.lastObjectState.visualState().noSaveMappingFrame());
        assertEquals(0xE, renderer.lastObjectState.visualState().deleteMappingFrame());
        assertEquals(8, renderer.lastObjectState.visualState().slotStates().size());
        assertEquals(S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED,
                renderer.lastObjectState.visualState().slotStates().get(0).kind());
        assertEquals(S3kSaveScreenObjectState.SlotVisualKind.CLEAR,
                renderer.lastObjectState.visualState().slotStates().get(1).kind());
        assertEquals(S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                renderer.lastObjectState.visualState().slotStates().get(2).kind());
        assertEquals(S3kSaveScreenObjectState.SlotLabelKind.ZONE,
                renderer.lastObjectState.visualState().slotStates().get(0).labelKind());
        assertEquals(3, renderer.lastObjectState.visualState().slotStates().get(0).zoneDisplayNumber());
        assertEquals(1, renderer.lastObjectState.visualState().slotStates().get(0).headerStyleIndex());
        assertEquals(5, renderer.lastObjectState.visualState().slotStates().get(0).lives());
        assertEquals(0, renderer.lastObjectState.visualState().slotStates().get(0).continuesCount());
        assertEquals(S3kSaveScreenObjectState.SlotLabelKind.CLEAR,
                renderer.lastObjectState.visualState().slotStates().get(1).labelKind());
        assertEquals(3, renderer.lastObjectState.visualState().slotStates().get(1).headerStyleIndex());
        assertEquals(4, renderer.lastObjectState.visualState().slotStates().get(0).objectMappingFrame());
        assertEquals(List.of(0x10, 0x11, 0x12),
                renderer.lastObjectState.visualState().slotStates().get(0).emeraldMappingFrames());
        assertEquals(7, renderer.lastObjectState.visualState().slotStates().get(1).objectMappingFrame());
        assertEquals(List.of(0x10, 0x11, 0x12, 0x13, 0x14),
                renderer.lastObjectState.visualState().slotStates().get(1).emeraldMappingFrames());
        assertEquals(-1, renderer.lastObjectState.visualState().noSaveChildMappingFrame());
        assertEquals(8, renderer.lastObjectState.visualState().deleteChildMappingFrame());
    }

    @Test
    void draw_selectedClearSlot_defaultsToTerminalClearGraphic() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 3,
                "act", 1,
                "mainCharacter", "knuckles",
                "sidekicks", List.of(),
                "lives", 7,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4),
                "superEmeralds", List.of(),
                "clear", true,
                "progressCode", 11,
                "clearState", 2
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        settleHorizontalMove(presentation);

        presentation.draw();

        assertEquals(-1, renderer.lastObjectState.visualState().slotStates().get(0).sub2MappingFrame());
        assertEquals(S3kSaveScreenObjectState.SlotLabelKind.CLEAR,
                renderer.lastObjectState.visualState().slotStates().get(0).labelKind());
        assertEquals(12, renderer.lastObjectState.visualState().slotStates().get(0).zoneDisplayNumber());
        assertNotNull(renderer.lastObjectState.selectedSlotIcon());
        assertEquals(0x23, renderer.lastObjectState.selectedSlotIcon().mappingFrame());
        assertTrue(renderer.lastObjectState.selectedSlotIcon().finishCard());
        assertEquals(11, renderer.lastObjectState.selectedSlotIcon().iconIndex());
    }

    @Test
    void draw_selectorVisibilityAlternatesEveryFourFrames() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();
        presentation.draw();
        boolean firstVisible = renderer.lastObjectState.selectorState().visible();

        for (int i = 0; i < 4; i++) {
            presentation.update(new InputHandler());
        }

        presentation.draw();
        boolean secondVisible = renderer.lastObjectState.selectorState().visible();

        assertNotEquals(firstVisible, secondVisible,
                "selector border should blink on and off");
    }

    @Test
    void draw_selectedOccupiedSlotKeepsZoneImageVisibleWhileHighlighted() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 2,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);

        presentation.draw();
        boolean firstVisible = renderer.lastObjectState.selectedSlotIcon() != null;

        assertFalse(firstVisible,
                "highlighted occupied slot should wait for the red-box tween before swapping from static to the zone image");

        settleHorizontalMove(presentation);
        presentation.draw();
        boolean secondVisible = renderer.lastObjectState.selectedSlotIcon() != null;

        assertTrue(secondVisible,
                "highlighted occupied slot should keep showing the zone image instead of returning to static");
    }

    @Test
    void update_doesNotCommitSelectedRowUntilSelectorMovementFinishes() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 2,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);

        presentation.draw();
        assertEquals(1, controller.menuModel().getSelectedRow(),
                "logical selection can update immediately");
        assertEquals(S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED,
                renderer.lastObjectState.visualState().slotStates().get(0).kind(),
                "rendered selection should remain on the old row until the selector tween finishes");
        assertNull(renderer.lastObjectState.selectedSlotIcon(),
                "highlighted slot image should not jump ahead of the red-box tween");

        settleHorizontalMove(presentation);
        presentation.draw();
        assertNotNull(renderer.lastObjectState.selectedSlotIcon(),
                "highlighted slot image should appear once the selector settles on the new slot");
    }

    @Test
    void update_logicalDirectionsMoveSelection() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());
        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });
        presentation.initialize();

        InputHandler input = new InputHandler();
        input.setLogicalOverride(logicalPress(AbstractPlayableSprite.INPUT_RIGHT, 0, false));
        presentation.update(input);
        assertEquals(1, controller.menuModel().getSelectedRow());

        settleHorizontalMove(presentation);
        input.setLogicalOverride(logicalPress(AbstractPlayableSprite.INPUT_LEFT, 0, false));
        presentation.update(input);
        assertEquals(0, controller.menuModel().getSelectedRow());
    }

    @Test
    void update_logicalBackCancelsDeleteModeWithoutConfirming() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());
        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });
        presentation.initialize();
        controller.menuModel().setSelectedRow(controller.deleteRowIndex());
        controller.menuModel().setDeleteMode(true);

        InputHandler input = new InputHandler();
        input.setLogicalOverride(logicalPress(0, InputActionMasks.ACTION_C, false));
        presentation.update(input);

        assertFalse(controller.menuModel().isDeleteMode(),
                "logical C/east should back out of delete mode");
        assertEquals(controller.deleteRowIndex(), controller.menuModel().getSelectedRow(),
                "logical C/east must not also confirm the delete row");
    }

    @Test
    void update_logicalABOrStartConfirmSelection() {
        assertLogicalConfirmStartsNoSave(InputActionMasks.ACTION_A, false);
        assertLogicalConfirmStartsNoSave(InputActionMasks.ACTION_B, false);
        assertLogicalConfirmStartsNoSave(0, true);
    }

    private void assertLogicalConfirmStartsNoSave(int actionMask, boolean startPressed) {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());
        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });
        presentation.initialize();

        InputHandler input = new InputHandler();
        input.setLogicalOverride(logicalPress(0, actionMask, startPressed));
        presentation.update(input);
        assertEquals(DataSelectActionType.NO_SAVE_START, controller.consumePendingAction().type());
    }

    @Test
    void donatedS2Host_draw_settledOccupiedSlotShowsHostSelectedIcon() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s2", 1, java.util.Map.of(
                "zone", 0,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S2DataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        settleHorizontalMove(presentation);
        presentation.draw();

        assertNotNull(renderer.lastObjectState.selectedSlotIcon(),
                "donated S2 saves should still show a selected zone preview image");
        assertEquals(0, renderer.lastObjectState.selectedSlotIcon().iconIndex());
        assertFalse(renderer.lastObjectState.selectedSlotIcon().finishCard());
    }

    @Test
    void donatedS1Host_draw_usesIndividualEmeraldFramesFromChaosList() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s1", 1, java.util.Map.of(
                "zone", 0,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of(),
                "lives", 5,
                "chaosEmeralds", List.of(0, 2, 5),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S1DataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();
        presentation.draw();

        assertEquals(List.of(0x13, 0x12, 0x14),
                renderer.lastObjectState.visualState().slotStates().get(0).emeraldMappingFrames());
    }

    @Test
    void donatedS1Host_draw_buildsSelectedSlotIconFromHostZone() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s1", 1, java.util.Map.of(
                "zone", 0,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of(),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S1DataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        settleHorizontalMove(presentation);
        presentation.draw();

        assertNotNull(renderer.lastObjectState.selectedSlotIcon(),
                "donated S1 saves should advertise a host-owned selected zone preview image");
        assertEquals(0, renderer.lastObjectState.selectedSlotIcon().iconIndex());
        assertFalse(renderer.lastObjectState.selectedSlotIcon().finishCard());
    }

    @Test
    void donorAssets_loadHostPreviewAssets_usesRuntimeS2PreviewCache() throws Exception {
        File s3kRomFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(s3kRomFile != null && s3kRomFile.exists(), "Sonic 3K ROM not available");

        try (Rom s3kRom = new Rom()) {
            assumeTrue(s3kRom.open(s3kRomFile.getPath()), "Failed to open Sonic 3K ROM");

            S2DataSelectImageCacheManager cacheManager = org.mockito.Mockito.mock(S2DataSelectImageCacheManager.class);
            org.mockito.Mockito.when(cacheManager.loadCachedPreviews())
                    .thenReturn(Map.of(0, solidPreviewImage(0xFF3366CC)));

            com.openggf.game.GameModule module = org.mockito.Mockito.mock(com.openggf.game.GameModule.class);
            org.mockito.Mockito.when(module.getGameService(S2DataSelectImageCacheManager.class)).thenReturn(cacheManager);

            try (var services = org.mockito.Mockito.mockStatic(GameServices.class)) {
                services.when(GameServices::module).thenReturn(module);
                services.when(GameServices::rom).thenReturn(null);

                S3kDataSelectAssetSource assets = newLoaderBackedAssets(s3kRom, "s2");
                assets.loadData();

                var selected = new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17);
                SpriteMappingFrame frame = assets.getSelectedSlotIconFrame(selected);

                assertNotNull(frame);
                assertEquals(70, frame.pieces().size(),
                        "runtime S2 preview should use the 80x56 PNG-backed tile grid instead of the 12-tile ROM icon");
                assertTrue(frame.pieces().stream().allMatch(piece -> piece.paletteIndex() == 2),
                        "runtime generated host previews should use palette line 2 so S2 purple can keep line 3");
                assertTrue(assets.useScaledSelectedSlotIconFrame(selected));
                assertEquals(70, assets.getSlotIconPatterns(0).length);
            }
        }
    }

    @Test
    void donorAssets_emptyRuntimeS2PreviewCacheFallsBackToNativeS3kCard() throws Exception {
        File s3kRomFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(s3kRomFile != null && s3kRomFile.exists(), "Sonic 3K ROM not available");

        try (Rom s3kRom = new Rom()) {
            assumeTrue(s3kRom.open(s3kRomFile.getPath()), "Failed to open Sonic 3K ROM");

            S2DataSelectImageCacheManager cacheManager = org.mockito.Mockito.mock(S2DataSelectImageCacheManager.class);
            org.mockito.Mockito.when(cacheManager.loadCachedPreviews()).thenReturn(Map.of());

            com.openggf.game.GameModule module = org.mockito.Mockito.mock(com.openggf.game.GameModule.class);
            org.mockito.Mockito.when(module.getGameService(S2DataSelectImageCacheManager.class)).thenReturn(cacheManager);

            try (var services = org.mockito.Mockito.mockStatic(GameServices.class)) {
                services.when(GameServices::module).thenReturn(module);
                services.when(GameServices::rom).thenReturn(null);

                S3kDataSelectAssetSource assets = newLoaderBackedAssets(s3kRom, "s2");
                assets.loadData();

                var selected = new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17);

                assertFalse(assets.useScaledSelectedSlotIconFrame(selected));
                assertNull(assets.getSelectedSlotIconFrame(selected));
                assertNull(assets.getSelectedSlotIconPalette(selected));
                assertTrue(assets.getSlotIconPatterns(0).length > 0,
                        "empty runtime preview cache should fall back to the native S3K selected card art");
            }
        }
    }

    @Test
    void donorAssets_emptyRuntimeS1PreviewCacheFallsBackToNativeS3kCard() throws Exception {
        File s3kRomFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(s3kRomFile != null && s3kRomFile.exists(), "Sonic 3K ROM not available");

        try (Rom s3kRom = new Rom()) {
            assumeTrue(s3kRom.open(s3kRomFile.getPath()), "Failed to open Sonic 3K ROM");

            S1DataSelectImageCacheManager cacheManager = org.mockito.Mockito.mock(S1DataSelectImageCacheManager.class);
            org.mockito.Mockito.when(cacheManager.loadCachedPreviews()).thenReturn(Map.of());

            com.openggf.game.GameModule module = org.mockito.Mockito.mock(com.openggf.game.GameModule.class);
            org.mockito.Mockito.when(module.getGameService(S1DataSelectImageCacheManager.class)).thenReturn(cacheManager);

            try (var services = org.mockito.Mockito.mockStatic(GameServices.class)) {
                services.when(GameServices::module).thenReturn(module);
                services.when(GameServices::rom).thenReturn(null);

                S3kDataSelectAssetSource assets = newLoaderBackedAssets(s3kRom, "s1");
                assets.loadData();

                var selected = new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17);

                assertFalse(assets.useScaledSelectedSlotIconFrame(selected));
                assertNull(assets.getSelectedSlotIconFrame(selected));
                assertNull(assets.getSelectedSlotIconPalette(selected));
                assertTrue(assets.getSlotIconPatterns(0).length > 0,
                        "empty runtime preview cache should fall back to the native S3K selected card art");
            }
        }
    }

    @Test
    void donorAssets_loadHostPreviewAssets_consumesEmeraldPresentationResult() throws Exception {
        File s1RomFile = RomTestUtils.ensureSonic1RomAvailable();
        File s3kRomFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(s1RomFile != null && s1RomFile.exists(), "Sonic 1 ROM not available");
        assumeTrue(s3kRomFile != null && s3kRomFile.exists(), "Sonic 3K ROM not available");

        try (Rom hostRom = new Rom(); Rom s3kRom = new Rom()) {
            assumeTrue(hostRom.open(s1RomFile.getPath()), "Failed to open Sonic 1 ROM");
            assumeTrue(s3kRom.open(s3kRomFile.getPath()), "Failed to open Sonic 3K ROM");

            TestEnvironment.configureRomFixture(hostRom);

            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(s3kRom));
            loader.loadData();
            HostEmeraldPresentation.Result expected = HostEmeraldPresentation.forHost(
                    "s1",
                    hostRom,
                    loader.getEmeraldPaletteBytes());
            S3kDataSelectAssetSource assets = newLoaderBackedAssets(s3kRom, "s1");

            assets.loadData();

            assertArrayEquals(expected.paletteBytes(), assets.getEmeraldPaletteBytes());
            assertEquals(expected.layout(), readPrivateField(assets, "hostEmeraldLayoutProfile"));
        }
    }

    @Test
    void donorAssets_loadHostPreviewAssets_rebindsS2PurpleEmeraldToPaletteLineThree() throws Exception {
        File s2RomFile = RomTestUtils.ensureSonic2RomAvailable();
        File s3kRomFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(s2RomFile != null && s2RomFile.exists(), "Sonic 2 ROM not available");
        assumeTrue(s3kRomFile != null && s3kRomFile.exists(), "Sonic 3K ROM not available");

        try (Rom hostRom = new Rom(); Rom s3kRom = new Rom()) {
            assumeTrue(hostRom.open(s2RomFile.getPath()), "Failed to open Sonic 2 ROM");
            assumeTrue(s3kRom.open(s3kRomFile.getPath()), "Failed to open Sonic 3K ROM");

            TestEnvironment.configureRomFixture(hostRom);

            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(s3kRom));
            loader.loadData();

            S3kDataSelectAssetSource assets = newLoaderBackedAssets(s3kRom, "s2");
            assets.loadData();

            HostEmeraldPresentation.Result expected = HostEmeraldPresentation.forHost(
                    "s2",
                    hostRom,
                    loader.getEmeraldPaletteBytes());
            SpriteMappingFrame originalPurple = loader.getSaveScreenMappings().get(0x16);
            SpriteMappingFrame reboundPurple = assets.getSaveScreenMappings().get(0x16);

            assertArrayEquals(expected.paletteBytes(), assets.getEmeraldPaletteBytes());
            assertEquals(originalPurple.pieces().size(), reboundPurple.pieces().size());
            for (int i = 0; i < originalPurple.pieces().size(); i++) {
                SpriteMappingPiece originalPiece = originalPurple.pieces().get(i);
                SpriteMappingPiece reboundPiece = reboundPurple.pieces().get(i);
                assertEquals(originalPiece.xOffset(), reboundPiece.xOffset());
                assertEquals(originalPiece.yOffset(), reboundPiece.yOffset());
                assertEquals(originalPiece.widthTiles(), reboundPiece.widthTiles());
                assertEquals(originalPiece.heightTiles(), reboundPiece.heightTiles());
                assertEquals(originalPiece.tileIndex(), reboundPiece.tileIndex());
                assertEquals(originalPiece.hFlip(), reboundPiece.hFlip());
                assertEquals(originalPiece.vFlip(), reboundPiece.vFlip());
                assertEquals(originalPiece.priority(), reboundPiece.priority());
                assertEquals(3, reboundPiece.paletteIndex(),
                        "Dedicated S2 purple emerald should render on palette line 3");
            }
        }
    }

    @Test
    void donatedRenderer_placesS1EmeraldsInReferenceAlignedHexagon() {
        RecordingGraphics graphics = new RecordingGraphics();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        EmeraldOrbitAssets assets = new EmeraldOrbitAssets(HostEmeraldLayoutProfile.s1SixRing());

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 4, -1,
                                        0x13, 0x11, 0x12, 0x10, 0x15, 0x14),
                                slotState(1, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(2, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(3, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(4, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(5, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(6, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(7, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1))));

        for (HostEmeraldLayoutProfile.Point offset : assets.referenceAlignedS1HexagonPositions()) {
            assertTrue(graphics.containsRenderPosition(
                            assets.slotWorldX + offset.x() - 128,
                            assets.slotWorldY + offset.y() - 128),
                    "expected emerald render at reference-aligned S1 orbit position");
        }
    }

    @Test
    void render_selectedHostPreviewDoesNotClobberDedicatedS2PurpleEmeraldPalette() {
        RecordingGraphics graphics = new RecordingGraphics();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S2PurpleEmeraldWithSelectedPreviewAssets assets = new S2PurpleEmeraldWithSelectedPreviewAssets();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 4, -1, 0x16),
                                slotState(1, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(2, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(3, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(4, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(5, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(6, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(7, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1)),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17)));

        Palette palette = graphics.cachedPalette(3);
        assertNotNull(palette);
        assertTrue((palette.getColor(9).r & 0xFF) > 0
                        || (palette.getColor(9).g & 0xFF) > 0
                        || (palette.getColor(9).b & 0xFF) > 0,
                "line 3 should end the frame with the dedicated S2 purple emerald palette, not the selected preview palette");
    }

    @Test
    void render_hostSelectedPreviewDoesNotLeaveEmeraldPaletteLineTwoDirtyForNextFrame() {
        RecordingGraphics graphics = new RecordingGraphics();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S2PurpleEmeraldWithSelectedPreviewAssets assets = new S2PurpleEmeraldWithSelectedPreviewAssets();
        S3kSaveScreenLayoutObjects layout = assets.getSaveScreenLayoutObjects();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 4, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 4, -1, 0x16),
                                slotState(1, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(2, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(3, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(4, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(5, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(6, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(7, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1)),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17)));

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 4, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 4, -1, 0x16),
                                slotState(1, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(2, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(3, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(4, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(5, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(6, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(7, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1))));

        Palette palette = graphics.cachedPalette(2);
        assertNotNull(palette);
        assertTrue((palette.getColor(1).r & 0xFF) > 0
                        || (palette.getColor(1).g & 0xFF) > 0
                        || (palette.getColor(1).b & 0xFF) > 0,
                "line 2 emerald palette should be rebuilt on the next frame after a host preview used line 2");
    }

    @Test
    void render_flushesEachLayerSoLaterPaletteUploadsCannotRewriteEarlierLayers() {
        RecordingGraphics graphics = new RecordingGraphics();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S2PurpleEmeraldWithSelectedPreviewAssets assets = new S2PurpleEmeraldWithSelectedPreviewAssets();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 4, -1, 0x16),
                                slotState(1, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(2, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(3, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(4, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(5, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(6, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(7, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1)),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17)));

        assertTrue(graphics.flushCalls() > 0,
                "renderer should flush each layer immediately so later palette uploads cannot rewrite earlier queued draws");
        assertEquals(graphics.flushBatchCalls(), graphics.flushCalls(),
                "every layer batch flush should execute immediately");
    }

    @Test
    void donatedS2Host_draw_usesIndividualEmeraldFramesFromChaosList() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s2", 1, java.util.Map.of(
                "zone", 0,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(1, 4, 6),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S2DataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();
        presentation.draw();

        assertEquals(List.of(0x16, 0x11, 0x14),
                renderer.lastObjectState.visualState().slotStates().get(0).emeraldMappingFrames());
    }

    @Test
    void update_ignoresAdditionalHorizontalMovesUntilSelectorTweenFinishes() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);

        InputHandler secondMove = new InputHandler();
        secondMove.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(secondMove);

        assertEquals(1, controller.menuModel().getSelectedRow(),
                "selection should not advance again while the red-box tween is still in progress");
    }

    @Test
    void draw_mixedChaosAndSuperEmeraldStateUsesPerEmeraldFrames() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 6,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(1, 4),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();
        presentation.draw();

        assertEquals(List.of(0x10, 0x1D, 0x12, 0x13, 0x20, 0x15, 0x16),
                renderer.lastObjectState.visualState().slotStates().get(0).emeraldMappingFrames(),
                "Each emerald identity should render as chaos or super independently");
    }

    @Test
    void draw_selectedClearSlotAfterCyclingRestart_keepsClearLabelAndAdvancesZoneCard() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 3,
                "act", 1,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 7,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "clear", true,
                "progressCode", 14,
                "clearState", 2
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        int upKey = EngineServices.current().configuration().getInt(SonicConfiguration.UP);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        settleHorizontalMove(presentation);
        pressAndRelease(input, upKey, presentation);
        pressAndRelease(input, upKey, presentation);

        presentation.draw();

        assertEquals(S3kSaveScreenObjectState.SlotLabelKind.CLEAR,
                renderer.lastObjectState.visualState().slotStates().get(0).labelKind());
        assertNotNull(renderer.lastObjectState.selectedSlotIcon());
        assertFalse(renderer.lastObjectState.selectedSlotIcon().finishCard());
        assertEquals(1, renderer.lastObjectState.selectedSlotIcon().iconIndex());
        assertEquals(1, renderer.lastObjectState.selectedSlotIcon().paletteIndex(),
                "clear-slot stage cards should use the selected stage palette, not a finish-card palette");
    }

    @Test
    void draw_customSavedTeam_buildsComposedPortraitFrame() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 2,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails", "knuckles", "tails", "sonic", "knuckles"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));

        RecordingAssets assets = new SlotObjectFrameAssets();
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        presentation.draw();

        S3kSaveScreenObjectState.SlotVisualState slotState =
                renderer.lastObjectState.visualState().slotStates().get(0);
        assertNotNull(slotState.customObjectFrame());
        assertEquals(6, slotState.customObjectFrame().pieces().size());
    }

    @Test
    void draw_customNoSaveTeam_buildsComposedPortraitFrame() {
        RecordingAssets assets = new SlotObjectFrameAssets();
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();
        controller.loadAvailableTeams("sonic,knuckles");
        controller.cycleTeam(4);
        presentation.draw();

        assertNotNull(renderer.lastObjectState.visualState().noSaveCustomFrame());
        assertEquals(2, renderer.lastObjectState.visualState().noSaveCustomFrame().pieces().size());
    }

    @Test
    void draw_customNewSlotTeam_buildsComposedPortraitFrame() {
        RecordingAssets assets = new SlotObjectFrameAssets();
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();
        controller.loadAvailableTeams("sonic,knuckles");
        controller.moveSelection(1);
        controller.cycleTeam(4);
        presentation.draw();

        assertNotNull(renderer.lastObjectState.visualState().slotStates().get(0).customObjectFrame());
        assertEquals(2, renderer.lastObjectState.visualState().slotStates().get(0).customObjectFrame().pieces().size());
    }

    @Test
    void draw_deleteRobotnikFollowsCursorAndSecondDeletePressStartsRetreat() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        int jumpKey = EngineServices.current().configuration().getInt(SonicConfiguration.JUMP);
        for (int i = 0; i < 9; i++) {
            pressAndRelease(input, rightKey, presentation);
        }

        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);
        int homeDeleteX = renderer.lastObjectState == null
                ? assets.getSaveScreenLayoutObjects().deleteIcon().worldX()
                : renderer.lastObjectState.layoutObjects().deleteIcon().worldX();
        int leftKey = EngineServices.current().configuration().getInt(SonicConfiguration.LEFT);
        pressAndRelease(input, leftKey, presentation);
        presentation.draw();

        int activatedDeleteX = renderer.lastObjectState.deleteWorldX();
        assertTrue(activatedDeleteX < homeDeleteX,
                "active Robotnik should leave the home delete slot and follow the cursor");

        input.update();
        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);
        for (int i = 0; i < 4; i++) {
            presentation.update(new InputHandler());
        }
        presentation.draw();

        assertTrue(renderer.lastObjectState.deleteWorldX() > activatedDeleteX,
                "pressing Delete again while Robotnik is active should make him retreat rightwards");
    }

    @Test
    void draw_deletePromptFreezesYesNoFrameAndLeftDeletesSlot() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 2,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        int leftKey = EngineServices.current().configuration().getInt(SonicConfiguration.LEFT);
        int jumpKey = EngineServices.current().configuration().getInt(SonicConfiguration.JUMP);

        for (int i = 0; i < 9; i++) {
            pressAndRelease(input, rightKey, presentation);
        }
        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);

        for (int i = 0; i < 8; i++) {
            pressAndRelease(input, leftKey, presentation);
        }

        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);
        for (int i = 0; i < 24; i++) {
            presentation.update(new InputHandler());
        }
        presentation.draw();

        assertEquals(0xC, renderer.lastObjectState.visualState().deleteChildMappingFrame(),
                "occupied-slot delete prompt should freeze the sign on the YES/NO frame");

        input.update();
        input.handleKeyEvent(leftKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(leftKey, GLFW_RELEASE);

        assertEquals(com.openggf.game.save.SaveSlotState.EMPTY, presentation.slotSummaries().get(0).state(),
                "Left on the YES/NO prompt should delete the selected save");
    }

    @Test
    void draw_deletePromptRightAbortsAndKeepsSaveIntact() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 2,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        int leftKey = EngineServices.current().configuration().getInt(SonicConfiguration.LEFT);
        int jumpKey = EngineServices.current().configuration().getInt(SonicConfiguration.JUMP);

        for (int i = 0; i < 9; i++) {
            pressAndRelease(input, rightKey, presentation);
        }
        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);

        for (int i = 0; i < 8; i++) {
            pressAndRelease(input, leftKey, presentation);
        }

        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);
        for (int i = 0; i < 24; i++) {
            presentation.update(new InputHandler());
        }

        input.update();
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);

        assertEquals(com.openggf.game.save.SaveSlotState.VALID, presentation.slotSummaries().get(0).state(),
                "Right on the YES/NO prompt should abort deletion");
    }

    @Test
    void draw_activeDeleteOnNewSlotRetreatsInsteadOfOpeningPrompt() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        int leftKey = EngineServices.current().configuration().getInt(SonicConfiguration.LEFT);
        int jumpKey = EngineServices.current().configuration().getInt(SonicConfiguration.JUMP);

        for (int i = 0; i < 9; i++) {
            pressAndRelease(input, rightKey, presentation);
        }
        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);

        for (int i = 0; i < 7; i++) {
            pressAndRelease(input, leftKey, presentation);
        }
        presentation.draw();
        int robotnikAtNewSlotX = renderer.lastObjectState.deleteWorldX();

        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);
        for (int i = 0; i < 4; i++) {
            presentation.update(new InputHandler());
        }
        presentation.draw();

        assertNotEquals(0xC, renderer.lastObjectState.visualState().deleteChildMappingFrame(),
                "NEW-slot interaction should not open the YES/NO delete prompt");
        assertTrue(renderer.lastObjectState.deleteWorldX() > robotnikAtNewSlotX,
                "interacting with a NEW slot while Robotnik is active should send him retreating rightwards");
    }

    @Test
    void draw_selectedClearSlotAtTerminalDestination_usesFinishCardMappingForClearState() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 3,
                "act", 1,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 7,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "clear", true,
                "progressCode", 11,
                "clearState", 2
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        settleHorizontalMove(presentation);

        presentation.getSessionController().menuModel().setClearRestartIndex(13);
        presentation.draw();

        assertNotNull(renderer.lastObjectState.selectedSlotIcon());
        assertTrue(renderer.lastObjectState.selectedSlotIcon().finishCard());
        assertEquals(13, renderer.lastObjectState.selectedSlotIcon().iconIndex());
        assertEquals(0x19, renderer.lastObjectState.selectedSlotIcon().mappingFrame());
    }

    @Test
    void draw_selectedClearSlotAfterBlinkWindow_showsClearChildFrame() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, java.util.Map.of(
                "zone", 3,
                "act", 1,
                "mainCharacter", "knuckles",
                "sidekicks", List.of(),
                "lives", 7,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4),
                "superEmeralds", List.of(),
                "clear", true,
                "progressCode", 11,
                "clearState", 2
        ));

        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        for (int i = 0; i < 16; i++) {
            presentation.update(new InputHandler());
        }

        presentation.draw();

        assertEquals(0x1A, renderer.lastObjectState.visualState().slotStates().get(0).sub2MappingFrame());
    }

    @Test
    void draw_selectedEmptySlot_usesBlinkingChildFrame0FLikeOriginal() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        RecordingRenderer renderer = new RecordingRenderer();
        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                new DataSelectSessionController(new S3kDataSelectProfile()),
                saveManager,
                EngineServices.current().configuration(),
                new RecordingAssets(0x2A),
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        for (int i = 0; i < 16; i++) {
            presentation.update(new InputHandler());
        }

        presentation.draw();

        assertEquals(0xF, renderer.lastObjectState.visualState().slotStates().get(0).sub2MappingFrame());
    }

    @Test
    void draw_selectedEmptySlot_doesNotShowLivesOrContinues() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        RecordingRenderer renderer = new RecordingRenderer();
        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                new DataSelectSessionController(new S3kDataSelectProfile()),
                saveManager,
                EngineServices.current().configuration(),
                new RecordingAssets(0x2A),
                renderer,
                ignored -> {
                });

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);

        presentation.draw();

        S3kSaveScreenObjectState.SlotVisualState slotState =
                renderer.lastObjectState.visualState().slotStates().get(0);
        assertEquals(0, slotState.headerStyleIndex(),
                "NEW saves should not show the lives/continues header");
        assertEquals(0, slotState.lives(), "NEW saves should not show lives");
        assertEquals(0, slotState.continuesCount(), "NEW saves should not show continues");
    }

    @Test
    void render_realAssets_emptySlotBodyUsesExpectedS3kBodyTiles() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null, "S3K ROM not available");

        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romFile.getPath()), "Failed to open S3K ROM");

            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(rom));
            loader.loadData();

            RecordingGraphics graphics = new RecordingGraphics();
            S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
            S3kSaveScreenSelectorState selectorState = new S3kSaveScreenSelectorState(ignored -> {});
            selectorState.setCurrentEntry(0);

            renderer.draw(graphics, loader,
                    new S3kSaveScreenObjectState(
                            loader.getSaveScreenLayoutObjects(),
                            selectorState,
                            visualState(loader.getSaveScreenLayoutObjects(), 4, 0xD,
                                    S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                    S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                    S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                    S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                    S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                    S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                    S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                    S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

            int patternBase = 0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC;
            assertEquals(patternBase + 0x10, graphics.patternIdAt(104, 80));
            assertEquals(patternBase + 0x11, graphics.patternIdAt(112, 80));
            assertEquals(patternBase + 0x12, graphics.patternIdAt(120, 80));
            assertEquals(0, graphics.renderCallAt(104, 80).desc().getPaletteIndex());
            assertEquals(0, graphics.renderCallAt(112, 80).desc().getPaletteIndex());
            assertEquals(0, graphics.renderCallAt(120, 80).desc().getPaletteIndex());
        }
    }

    @Test
    void nativeSelectorRendering_usesAuthoredMappingFramesInsteadOfRectiOverlay() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null, "S3K ROM not available");

        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romFile.getPath()), "Failed to open S3K ROM");

            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(rom));
            loader.loadData();

            RecordingGraphics graphics = new RecordingGraphics();
            S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
            S3kSaveScreenSelectorState selectorState = new S3kSaveScreenSelectorState(ignored -> {});
            selectorState.setCurrentEntry(1);
            S3kSaveScreenObjectState objectState = new S3kSaveScreenObjectState(
                    loader.getSaveScreenLayoutObjects(),
                    selectorState,
                    visualState(loader.getSaveScreenLayoutObjects(), 4, 0xD,
                            S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                            S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                            S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                            S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                            S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                            S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                            S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                            S3kSaveScreenObjectState.SlotVisualKind.EMPTY));

            renderer.draw(graphics, loader, objectState);

            assertEquals(0, graphics.rectiCommands(), "native selector rendering must not use RECTI");
            assertTrue(graphics.renderPatternCalls() > 0, "selector rendering should submit mapping-frame pieces");
        }
    }

    @Test
    void nativeSelectorRendering_usesCurrentEntryToShiftSelectorPosition() {
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        RecordingGraphics noSaveGraphics = new RecordingGraphics();
        RecordingGraphics deleteGraphics = new RecordingGraphics();
        SelectorOnlyAssets assets = new SelectorOnlyAssets();

        S3kSaveScreenSelectorState noSaveSelector = new S3kSaveScreenSelectorState(ignored -> {});
        noSaveSelector.setCurrentEntry(0);
        renderer.draw(noSaveGraphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        noSaveSelector,
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        S3kSaveScreenSelectorState deleteSelector = new S3kSaveScreenSelectorState(ignored -> {});
        deleteSelector.setCurrentEntry(1);
        assertTrue(deleteSelector.selectorBiasedX() > noSaveSelector.selectorBiasedX());
        renderer.draw(deleteGraphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        deleteSelector,
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        assertTrue(noSaveGraphics.renderPatternCalls() > 0);
        assertTrue(deleteGraphics.renderPatternCalls() > 0);
    }

    @Test
    void nativeObjectRendering_appliesSaveScreenBasePriorityBit() {
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        RecordingGraphics graphics = new RecordingGraphics();
        SelectorOnlyAssets assets = new SelectorOnlyAssets();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        assertTrue(graphics.renderCalls().stream()
                        .filter(call -> call.patternId() >= 0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC)
                        .anyMatch(call -> call.desc().getPriority()),
                "save-screen object pieces should inherit the base art_tile priority bit");
    }

    @Test
    void draw_usesAuthoredLayoutObjectsForCardsWhileBackgroundStaysAtOrigin() {
        RecordingGraphics graphics = new RecordingGraphics();
        CustomPlacementAssets assets = new CustomPlacementAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S3kSaveScreenLayoutObjects layout = new S3kSaveScreenLayoutObjects(
                new S3kSaveScreenLayoutObjects.SceneObject(0x120, 0x14C, 3),
                new S3kSaveScreenLayoutObjects.SceneObject(0x120, 0x0E2, 1),
                new S3kSaveScreenLayoutObjects.SceneObject(0x500, 0x1A0, 1),
                new S3kSaveScreenLayoutObjects.SceneObject(0x260, 0x1A0, 0),
                List.of(
                        new S3kSaveScreenLayoutObjects.SaveSlotObject(0x300, 0x1A0, 0, 0),
                        new S3kSaveScreenLayoutObjects.SaveSlotObject(0x320, 0x1A0, 0, 1),
                        new S3kSaveScreenLayoutObjects.SaveSlotObject(0x340, 0x1A0, 0, 2),
                        new S3kSaveScreenLayoutObjects.SaveSlotObject(0x360, 0x1A0, 0, 3),
                        new S3kSaveScreenLayoutObjects.SaveSlotObject(0x380, 0x1A0, 0, 4),
                        new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3A0, 0x1A0, 0, 5),
                        new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3C0, 0x1A0, 0, 6),
                        new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3E0, 0x1A0, 0, 7)));

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        assertTrue(graphics.renderPositions().size() >= 12);
        assertTrue(graphics.containsRenderPosition(0, 0));
        assertTrue(graphics.containsRenderPosition(0x120 - 128, 0x14C - 128));
    }

    @Test
    void render_usesAnimatedStaticLayoutFrameForAllActiveSlots() {
        RecordingGraphics graphics = new RecordingGraphics();
        VisualStateAssets assets = new VisualStateAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S3kSaveScreenLayoutObjects layout = S3kSaveScreenLayoutObjects.original();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 5, 0xE, 1,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED,
                                S3kSaveScreenObjectState.SlotVisualKind.CLEAR,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        assertTrue(graphics.containsPatternId(0x50001));
        assertTrue(graphics.containsPatternId(0x50011));
        assertFalse(graphics.containsPatternId(0x50010));
    }

    @Test
    void render_usesVisualStateFramesForNoSaveAndDeleteObjects() {
        RecordingGraphics graphics = new RecordingGraphics();
        NoSaveDeleteFrameAssets assets = new NoSaveDeleteFrameAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S3kSaveScreenLayoutObjects layout = S3kSaveScreenLayoutObjects.original();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 5, 0xE,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        assertTrue(graphics.containsPatternId(0x502A4));
        assertTrue(graphics.containsPatternId(0x502AD));
    }

    @Test
    void render_drawsStaticNoSavePlaneTextAtAuthoredOffsets() {
        RecordingGraphics graphics = new RecordingGraphics();
        RecordingAssets assets = new RecordingAssets(0x2A);
        assets.loadData();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S3kSaveScreenLayoutObjects layout = S3kSaveScreenLayoutObjects.original();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        assertTrue(graphics.containsRenderPosition(24, 96));
        assertTrue(graphics.containsRenderPosition(32, 96));
        assertTrue(graphics.containsRenderPosition(48, 96));
        assertTrue(graphics.containsRenderPosition(72, 96));
    }

    @Test
    void render_drawsLaunchErrorMessageText() {
        RecordingGraphics graphics = new RecordingGraphics();
        RecordingAssets assets = new RecordingAssets(0x2A);
        assets.loadData();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S3kSaveScreenLayoutObjects layout = S3kSaveScreenLayoutObjects.original();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY),
                        null,
                        layout.deleteIcon().worldX(),
                        "Unable to load selected save."));

        assertTrue(graphics.containsPatternId(saveTextWordPatternId('U')),
                "launch error should render the stored message text");
        assertTrue(graphics.containsPatternId(saveTextWordPatternId('.')),
                "launch error should render punctuation from the stored message");
    }

    @Test
    void render_usesNoSaveDeleteSlotAndEmeraldChildFrames() {
        RecordingGraphics graphics = new RecordingGraphics();
        SlotObjectFrameAssets assets = new SlotObjectFrameAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S3kSaveScreenLayoutObjects layout = S3kSaveScreenLayoutObjects.original();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 5, 0xF, 0xE, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 5, -1, 0x10, 0x11),
                                slotState(1, S3kSaveScreenObjectState.SlotVisualKind.CLEAR, 6, 0x1A, 0x1C, 0x1D),
                                slotState(2, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(3, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(4, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(5, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(6, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1),
                                slotState(7, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1))));

        assertTrue(graphics.containsPatternId(0x502A5));
        assertTrue(graphics.containsPatternId(0x502AF));
        assertTrue(graphics.containsPatternId(0x502A7));
        assertTrue(graphics.containsPatternId(0x502B0));
        assertTrue(graphics.containsPatternId(0x502BB));
        assertTrue(graphics.containsPatternId(0x502B9));
    }

    @Test
    void render_usesSlotMetadataTextAndNumberTiles() {
        RecordingGraphics graphics = new RecordingGraphics();
        SlotObjectFrameAssets assets = new SlotObjectFrameAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S3kSaveScreenLayoutObjects layout = S3kSaveScreenLayoutObjects.original();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 5, 0xF, 0xE, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 5, -1,
                                        S3kSaveScreenObjectState.SlotLabelKind.ZONE, 3, 1, 5, 0, 0x10, 0x11),
                                slotState(1, S3kSaveScreenObjectState.SlotVisualKind.CLEAR, 6, 0x1A,
                                        S3kSaveScreenObjectState.SlotLabelKind.CLEAR, 0, 3, 7, 0, 0x1C, 0x1D),
                                slotState(2, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1,
                                        S3kSaveScreenObjectState.SlotLabelKind.BLANK, 0, 0, 0, 0),
                                slotState(3, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1,
                                        S3kSaveScreenObjectState.SlotLabelKind.BLANK, 0, 0, 0, 0),
                                slotState(4, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1,
                                        S3kSaveScreenObjectState.SlotLabelKind.BLANK, 0, 0, 0, 0),
                                slotState(5, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1,
                                        S3kSaveScreenObjectState.SlotLabelKind.BLANK, 0, 0, 0, 0),
                                slotState(6, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1,
                                        S3kSaveScreenObjectState.SlotLabelKind.BLANK, 0, 0, 0, 0),
                                slotState(7, S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1,
                                        S3kSaveScreenObjectState.SlotLabelKind.BLANK, 0, 0, 0, 0))));

        assertTrue(graphics.containsPatternId(saveTextWordPatternId('Z')));
        assertTrue(graphics.containsPatternId(saveTextDigitPatternId(3)));
        assertTrue(graphics.containsPatternId(saveExtraPatternId(0x50)));
        assertTrue(graphics.containsPatternId(saveTextWordPatternId('C')));
        assertTrue(graphics.containsPatternId(saveExtraPatternId(0x76)));
    }

    @Test
    void rightInput_movesSelectorAndPlaysSlotMachineSfx() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        RecordingSfxRecorder movementSfx = new RecordingSfxRecorder();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                },
                new S3kSaveScreenSelectorState(movementSfx::accept));

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        input.handleKeyEvent(rightKey, GLFW_PRESS);

        presentation.update(input);

        assertEquals(1, presentation.getSessionController().menuModel().getSelectedRow());
        assertEquals(List.of(0xB7), movementSfx.sfxIds());
    }

    @Test
    void rightInputInDeleteMode_playsSmallBumpersSfx() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        RecordingSfxRecorder movementSfx = new RecordingSfxRecorder();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                },
                new S3kSaveScreenSelectorState(movementSfx::accept));

        presentation.initialize();

        InputHandler input = new InputHandler();
        int jumpKey = EngineServices.current().configuration().getInt(SonicConfiguration.JUMP);
        int leftKey = EngineServices.current().configuration().getInt(SonicConfiguration.LEFT);
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);

        for (int i = 0; i < 9; i++) {
            pressAndRelease(input, rightKey, presentation);
        }

        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        presentation.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);
        input.update();

        pressAndRelease(input, leftKey, presentation);
        input.handleKeyEvent(rightKey, GLFW_PRESS);

        presentation.update(input);

        assertEquals(9, presentation.getSessionController().menuModel().getSelectedRow());
        assertEquals(0x7B, movementSfx.sfxIds().getLast());
    }

    @Test
    void upInputOnNoSave_playsSwitchSfxWhileCyclingTeam() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        RecordingSfxRecorder movementSfx = new RecordingSfxRecorder();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                },
                new S3kSaveScreenSelectorState(movementSfx::accept),
                movementSfx::accept);

        presentation.initialize();

        InputHandler input = new InputHandler();
        int upKey = EngineServices.current().configuration().getInt(SonicConfiguration.UP);
        input.handleKeyEvent(upKey, GLFW_PRESS);

        presentation.update(input);

        assertEquals(1, presentation.getSessionController().menuModel().getSelectedTeamIndex());
        assertEquals(List.of(Sonic3kSfx.SWITCH.id), movementSfx.sfxIds());
    }

    @Test
    void upInputOnNewSave_playsSwitchSfxWhileCyclingTeam() {
        RecordingAssets assets = new RecordingAssets(0x2A);
        RecordingRenderer renderer = new RecordingRenderer();
        RecordingSfxRecorder movementSfx = new RecordingSfxRecorder();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                ignored -> {
                },
                new S3kSaveScreenSelectorState(movementSfx::accept),
                movementSfx::accept);

        presentation.initialize();

        InputHandler input = new InputHandler();
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        int upKey = EngineServices.current().configuration().getInt(SonicConfiguration.UP);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        presentation.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        input.handleKeyEvent(upKey, GLFW_PRESS);

        presentation.update(input);

        assertEquals(1, presentation.getSessionController().menuModel().getSelectedRow());
        assertEquals(1, presentation.getSessionController().menuModel().getSelectedTeamIndex());
        assertEquals(List.of(Sonic3kSfx.SLOT_MACHINE.id, Sonic3kSfx.SWITCH.id), movementSfx.sfxIds());
    }

    @Test
    void emeraldPalette_isShiftedByOneColorLikeOriginalSaveScreen() {
        RecordingGraphics graphics = new RecordingGraphics();
        EmeraldPaletteAssets assets = new EmeraldPaletteAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        Palette emeraldPalette = graphics.cachedPalette(2);
        assertNotNull(emeraldPalette);
        assertTrue((emeraldPalette.getColor(0).r & 0xFF) > 0
                        || (emeraldPalette.getColor(0).g & 0xFF) > 0
                        || (emeraldPalette.getColor(0).b & 0xFF) > 0,
                "palette line 2 color 0 should preserve the second character palette line");
        assertTrue((emeraldPalette.getColor(1).r & 0xFF) > 0
                        || (emeraldPalette.getColor(1).g & 0xFF) > 0
                        || (emeraldPalette.getColor(1).b & 0xFF) > 0,
                "first emerald color should start at palette index 1, not 0");
    }

    @Test
    void render_foregroundPlaneScrollsWhileBackgroundStaysFixed() {
        RecordingGraphics noSaveGraphics = new RecordingGraphics();
        RecordingGraphics movedGraphics = new RecordingGraphics();
        ForegroundScrollAssets assets = new ForegroundScrollAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        S3kSaveScreenSelectorState noSaveSelector = new S3kSaveScreenSelectorState(ignored -> {});
        noSaveSelector.setCurrentEntry(0);
        renderer.draw(noSaveGraphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        noSaveSelector,
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        S3kSaveScreenSelectorState movedSelector = new S3kSaveScreenSelectorState(ignored -> {});
        movedSelector.setCurrentEntry(2);
        renderer.draw(movedGraphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        movedSelector,
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        int noPatternId = 0x50000 + (Sonic3kConstants.ARTTILE_SAVE_TEXT - 0x10 + S3kSaveTextCodec.encode('N'));
        int backgroundPatternId = 0x50007;
        int noSaveX = lastRenderXForPattern(noSaveGraphics, noPatternId);
        int movedNoSaveX = lastRenderXForPattern(movedGraphics, noPatternId);
        int backgroundX = lastRenderXForPattern(noSaveGraphics, backgroundPatternId);
        int movedBackgroundX = lastRenderXForPattern(movedGraphics, backgroundPatternId);

        assertTrue(movedNoSaveX < noSaveX,
                "Plane A NO SAVE text should move with the save field when cameraX changes");
        assertEquals(backgroundX, movedBackgroundX,
                "menu background should stay fixed while the save field scrolls");
    }

    @Test
    void render_dataSelectTitleStaysStaticWhenCameraMoves() {
        RecordingGraphics noSaveGraphics = new RecordingGraphics();
        RecordingGraphics movedGraphics = new RecordingGraphics();
        StaticTitleAssets assets = new StaticTitleAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        S3kSaveScreenSelectorState noSaveSelector = new S3kSaveScreenSelectorState(ignored -> {});
        noSaveSelector.setCurrentEntry(0);
        renderer.draw(noSaveGraphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        noSaveSelector,
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        S3kSaveScreenSelectorState movedSelector = new S3kSaveScreenSelectorState(ignored -> {});
        movedSelector.setCurrentEntry(2);
        renderer.draw(movedGraphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        movedSelector,
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        int titlePatternId = 0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 9;
        assertEquals(lastRenderXForPattern(noSaveGraphics, titlePatternId),
                lastRenderXForPattern(movedGraphics, titlePatternId),
                "DATA SELECT title should remain fixed while the save field scrolls");
    }

    @Test
    void render_highPriorityPlaneAOverlaysSelectedSlotIcon() {
        RecordingGraphics graphics = new RecordingGraphics();
        PriorityOverlayAssets assets = new PriorityOverlayAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY),
                        null));

        assertEquals(0x50001, graphics.patternIdAt(0, 0),
                "high-priority Plane A border tile should render after the selected preview sprite");
    }

    @Test
    void render_usesSeparatePatternBatchesPerLayerToPreserveSceneOrdering() {
        RecordingGraphics graphics = new RecordingGraphics();
        RecordingAssets assets = new RecordingAssets(0x2A);
        assets.loadData();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        assertTrue(graphics.beginBatchCalls() >= 10,
                "Data Select must flush multiple ordered pattern layers instead of collapsing the whole screen into one batch");
        assertEquals(graphics.beginBatchCalls(), graphics.flushBatchCalls(),
                "every started pattern batch should be flushed");
    }

    @Test
    void render_highPriorityEmptySlotOverlayStillDrawsNewCard() {
        RecordingGraphics graphics = new RecordingGraphics();
        HighPriorityNewCardAssets assets = new HighPriorityNewCardAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY)));

        assertEquals(0x50001, graphics.patternIdAt(104, 16),
                "high-priority NEW card tiles should still render in the slot Plane A overlay");
    }

    @Test
    void render_blankOverlayCellsDoNotHideUnderlyingPlaneShell() {
        RecordingGraphics graphics = new RecordingGraphics();
        TransparentBlankOverlayAssets assets = new TransparentBlankOverlayAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY),
                        null));

        assertEquals(0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 1, graphics.patternIdAt(104, 16),
                "blank overlay cells should stay transparent and leave the authored shell tile visible");
        assertFalse(graphics.containsPatternId(0x50000),
                "blank tile index 0 should not be submitted as an opaque overlay tile");
    }

    @Test
    void render_blankPriorityCellsDoNotRenderOpaqueTileZero() {
        RecordingGraphics graphics = new RecordingGraphics();
        LowPriorityBodyBorderAssets assets = new LowPriorityBodyBorderAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY),
                        null));

        assertNotNull(graphics.patternIdAt(0, 0),
                "the authored Plane A map should still render a shell tile at the top-left corner");
        assertNotEquals(0x50000, graphics.patternIdAt(0, 0),
                "blank priority cells should not overwrite the authored shell with tile index 0");
        assertFalse(graphics.containsPatternId(0x50000),
                "blank priority cells should remain transparent instead of rendering tile index 0 over the shell");
        assertTrue(graphics.cachedPatternIdsInRange(0x50000, 0x50001) >= 1,
                "renderer should cache an explicit blank tile at the Data Select pattern base");
    }

    @Test
    void render_selectedSlotIconCachesOriginalDmaWordSpan() {
        RecordingGraphics graphics = new RecordingGraphics();
        SelectedSlotIconAssets assets = new SelectedSlotIconAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17)));

        int iconBase = 0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 0x31B;
        assertEquals(70, graphics.cachedPatternIdsInRange(iconBase, iconBase + 0x100),
                "Load_Icon_Art DMA size is $460 words, which is 70 tiles");
    }

    @Test
    void render_selectedOccupiedSlotUsesSelectedIconPatternBank() {
        RecordingGraphics graphics = new RecordingGraphics();
        SelectedSlotIconAssets assets = new SelectedSlotIconAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17)));

        int iconBase = 0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 0x31B;
        assertEquals(iconBase, graphics.patternIdAt(144, 136),
                "highlighted save previews must render using the original frame $17 tile indices in the selected icon DMA bank");
    }

    @Test
    void render_selectedOccupiedSlotUsesCustomHostSelectedIconFrame() {
        RecordingGraphics graphics = new RecordingGraphics();
        HostSelectedSlotIconAssets assets = new HostSelectedSlotIconAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17)));

        assertEquals(12, graphics.scaledRenderCalls().size(),
                "host-selected previews should render as a scaled 4x3 tile grid");
        RecordingGraphics.ScaledRenderCall firstTile = graphics.scaledRenderCalls().getFirst();
        RecordingGraphics.ScaledRenderCall lastTile = graphics.scaledRenderCalls().getLast();
        assertEquals(0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 0x31B, firstTile.patternId());
        assertEquals(104f, firstTile.x(), 0.01f);
        assertEquals(16f, firstTile.y(), 0.01f);
        assertEquals(20f, firstTile.width(), 0.01f);
        assertEquals(56f / 3f, firstTile.height(), 0.01f);
        assertEquals(0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 0x326, lastTile.patternId());
        assertEquals(164f, lastTile.x(), 0.01f);
        assertEquals(16f + ((56f / 3f) * 2f), lastTile.y(), 0.02f);
        assertNotNull(graphics.cachedPalette(2),
                "host-selected previews should cache their host palette into line 2 to avoid the S2 purple emerald on line 3");
    }

    @Test
    void render_selectedOccupiedSlotZoneImageRendersAboveStaticCardOverlay() {
        RecordingGraphics graphics = new RecordingGraphics();
        SelectedIconOverStaticOverlayAssets assets = new SelectedIconOverStaticOverlayAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 4, -1)),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 0, false, 0, 0x17)));

        int iconBase = 0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 0x31B;
        assertEquals(iconBase, graphics.patternIdAt(104, 16),
                "highlighted save previews must render above the slot static instead of behind it");
    }

    @Test
    void render_selectedClearSlotArrowsRenderAboveStageGraphic() {
        RecordingGraphics graphics = new RecordingGraphics();
        ClearArrowsOverSelectedIconAssets assets = new ClearArrowsOverSelectedIconAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.CLEAR, 7, 0x1A)),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 1, false, 1, 0x17)));

        assertEquals(0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 2, graphics.patternIdAt(104, 0),
                "clear-slot stage-cycling arrows must render above the selected stage graphic");
    }

    @Test
    void render_emeraldsRenderAboveHighPriorityShellTiles() {
        RecordingGraphics graphics = new RecordingGraphics();
        EmeraldOverShellAssets assets = new EmeraldOverShellAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();
        S3kSaveScreenLayoutObjects layout = S3kSaveScreenLayoutObjects.original();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        layout,
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(layout, 5, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 5, -1, 0x10))));

        assertEquals(0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 0x10, graphics.patternIdAt(104, 80),
                "save-screen emeralds should render above the yellow shell/shadow graphics");
    }

    @Test
    void render_selectedS3ZoneCard8UsesDedicatedPalette() {
        RecordingGraphics graphics = new RecordingGraphics();
        SelectedS3ZoneCard8PaletteAssets assets = new SelectedS3ZoneCard8PaletteAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xD,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY,
                                S3kSaveScreenObjectState.SlotVisualKind.EMPTY),
                        new S3kSaveScreenObjectState.SelectedSlotIcon(0, 0x110, 0x108, 7, false, 7, 0x17)));

        Palette palette = graphics.cachedPalette(3);
        assertNotNull(palette);
        assertTrue((palette.getColor(0).r & 0xFF) > 0
                        || (palette.getColor(0).g & 0xFF) > 0
                        || (palette.getColor(0).b & 0xFF) > 0,
                "selected S3 zone card 8 should use its dedicated palette instead of the generic zone-card palette");
    }

    @Test
    void render_mappingFrameDrawsEarlierPiecesOnTop() {
        RecordingGraphics graphics = new RecordingGraphics();
        OverlappingPieceOrderAssets assets = new OverlappingPieceOrderAssets();
        S3kDataSelectRenderer renderer = new S3kDataSelectRenderer();

        renderer.draw(graphics,
                assets,
                new S3kSaveScreenObjectState(
                        assets.getSaveScreenLayoutObjects(),
                        new S3kSaveScreenSelectorState(ignored -> {}),
                        visualState(assets.getSaveScreenLayoutObjects(), 4, 0xF, 0xD, 8,
                                slotState(0, S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED, 4, -1, 0, 0))));

        assertEquals(0x50000 + Sonic3kConstants.ARTTILE_SAVE_MISC + 1, graphics.patternIdAt(144, 136),
                "earlier mapping pieces should render on top for the Sonic and Tails card");
    }

    @Test
    void initialize_doesNotEnterActiveFlowWhenAssetsFailToLoad() {
        FailingAssets assets = new FailingAssets();
        RecordingRenderer renderer = new RecordingRenderer();
        List<Integer> playedMusic = new ArrayList<>();
        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());

        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                new SaveManager(root),
                EngineServices.current().configuration(),
                assets,
                renderer,
                playedMusic::add);

        presentation.initialize();

        assertEquals(1, assets.loadCalls);
        assertTrue(playedMusic.isEmpty(), "music should not start when ROM-backed assets failed to load");
        assertEquals(DataSelectProvider.State.INACTIVE, presentation.getState(),
                "provider should stay out of active flow when assets are unavailable");
        assertFalse(presentation.isActive());
    }

    @Test
    void visualCapture_selectedSaveSlotShowsRightBodyRail() throws Exception {
        File rom = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(rom != null && rom.exists(), "S3K ROM required for visual regression capture");
        assumeTrue(glfwAvailable(), "GLFW unavailable for visual regression capture");

        System.setProperty("s3k.rom.path", rom.getAbsolutePath());
        S3kDataSelectVisualCapture.main(new String[0]);

        RgbaImage image = ScreenshotCapture.loadPNG(
                TestSessionOutputPaths.diagnostics("s3k-dataselect-visual")
                        .resolve("native_s3k_dataselect_with_saves.png"));

        assertNotEquals(0xFFFFFFFF, image.argb(113, 89),
                "selected slot left body rail should render over the background");
        assertNotEquals(0xFFFFFFFF, image.argb(173, 89),
                "selected slot right body rail should render over the background");
    }

    private static boolean glfwAvailable() {
        return glfwInit();
    }

    private static class RecordingAssets implements S3kDataSelectAssetSource {
        private final int musicId;
        private int loadCalls;
        private boolean loaded;

        RecordingAssets(int musicId) {
            this.musicId = musicId;
        }

        @Override
        public void loadData() {
            loadCalls++;
            loaded = true;
        }

        @Override
        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public int getMusicId() {
            return musicId;
        }

        @Override
        public int[] getLayoutWords() {
            return new int[0];
        }

        @Override
        public int[] getNewLayoutWords() {
            return new int[0];
        }

        @Override
        public int[][] getStaticLayouts() {
            return new int[0][];
        }

        @Override
        public int[] getMenuBackgroundLayoutWords() {
            return new int[0];
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getSkZonePatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getPortraitPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getS3ZonePatterns() {
            return new Pattern[0];
        }

        @Override
        public byte[] getMenuBackgroundPaletteBytes() {
            return new byte[0];
        }

        @Override
        public byte[] getCharacterPaletteBytes() {
            return new byte[0];
        }

        @Override
        public byte[] getEmeraldPaletteBytes() {
            return new byte[0];
        }

        @Override
        public byte[][] getFinishCardPalettes() {
            return new byte[0][];
        }

        @Override
        public byte[][] getZoneCardPalettes() {
            return new byte[0][];
        }

        @Override
        public byte[] getS3ZoneCard8PaletteBytes() {
            return new byte[0];
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return List.of();
        }

        @Override
        public S3kSaveScreenLayoutObjects getSaveScreenLayoutObjects() {
            return S3kSaveScreenLayoutObjects.original();
        }
    }

    private static final class CustomPlacementAssets extends RecordingAssets {
        private final List<SpriteMappingFrame> selectorMappings = List.of(
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false))),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 1, false, false, 0, false))),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 2, false, false, 0, false))),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 3, false, false, 0, false))));

        private CustomPlacementAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public int[] getLayoutWords() {
            return new int[]{0x0001};
        }

        @Override
        public int[] getNewLayoutWords() {
            return new int[]{0x0001};
        }

        @Override
        public int[][] getStaticLayouts() {
            return new int[][]{
                    new int[]{0x0001},
                    new int[]{0x0001},
                    new int[]{0x0001},
                    new int[]{0x0001}
            };
        }

        @Override
        public int[] getMenuBackgroundLayoutWords() {
            return new int[]{0x0001};
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return selectorMappings;
        }

        @Override
        public S3kSaveScreenLayoutObjects getSaveScreenLayoutObjects() {
            return new S3kSaveScreenLayoutObjects(
                    new S3kSaveScreenLayoutObjects.SceneObject(0x120, 0x14C, 3),
                    new S3kSaveScreenLayoutObjects.SceneObject(0x120, 0x0E2, 1),
                    new S3kSaveScreenLayoutObjects.SceneObject(0x500, 0x1A0, 1),
                    new S3kSaveScreenLayoutObjects.SceneObject(0x260, 0x1A0, 0),
                    List.of(
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x300, 0x1A0, 0, 0),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x320, 0x1A0, 0, 1),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x340, 0x1A0, 0, 2),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x360, 0x1A0, 0, 3),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x380, 0x1A0, 0, 4),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3A0, 0x1A0, 0, 5),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3C0, 0x1A0, 0, 6),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3E0, 0x1A0, 0, 7)));
        }
    }

    private static final class EmeraldOrbitAssets extends RecordingAssets {
        private final HostEmeraldLayoutProfile layout;
        private final int slotWorldX = 0x180;
        private final int slotWorldY = 0x120;
        private final List<SpriteMappingFrame> mappings;
        private static final List<HostEmeraldLayoutProfile.Point> REFERENCE_ALIGNED_S1_HEXAGON_POSITIONS = List.of(
                new HostEmeraldLayoutProfile.Point(-4, -50),
                new HostEmeraldLayoutProfile.Point(-26, -38),
                new HostEmeraldLayoutProfile.Point(18, -38),
                new HostEmeraldLayoutProfile.Point(-26, -12),
                new HostEmeraldLayoutProfile.Point(18, -12),
                new HostEmeraldLayoutProfile.Point(-4, 0));

        private EmeraldOrbitAssets(HostEmeraldLayoutProfile layout) {
            super(0x2A);
            this.layout = layout;
            this.mappings = buildMappings();
            loadData();
        }

        private List<HostEmeraldLayoutProfile.Point> referenceAlignedS1HexagonPositions() {
            return REFERENCE_ALIGNED_S1_HEXAGON_POSITIONS;
        }

        @Override
        public Pattern[] getMiscPatterns() {
            Pattern[] patterns = new Pattern[0x50];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
            return patterns;
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }

        @Override
        public S3kSaveScreenLayoutObjects getSaveScreenLayoutObjects() {
            return new S3kSaveScreenLayoutObjects(
                    new S3kSaveScreenLayoutObjects.SceneObject(0x120, 0x14C, 3),
                    new S3kSaveScreenLayoutObjects.SceneObject(0x120, 0x0E2, 1),
                    new S3kSaveScreenLayoutObjects.SceneObject(0x500, 0x1A0, 1),
                    new S3kSaveScreenLayoutObjects.SceneObject(0x260, 0x1A0, 0),
                    List.of(
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(slotWorldX, slotWorldY, 0, 0),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x320, 0x1A0, 0, 1),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x340, 0x1A0, 0, 2),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x360, 0x1A0, 0, 3),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x380, 0x1A0, 0, 4),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3A0, 0x1A0, 0, 5),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3C0, 0x1A0, 0, 6),
                            new S3kSaveScreenLayoutObjects.SaveSlotObject(0x3E0, 0x1A0, 0, 7)));
        }

        @Override
        public HostEmeraldLayoutProfile getHostEmeraldLayoutProfile() {
            return layout;
        }

        private List<SpriteMappingFrame> buildMappings() {
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i < 0x20; i++) {
                int x = 0;
                int y = 0;
                if (i >= 0x10 && i <= 0x16) {
                    HostEmeraldLayoutProfile.Point point = switch (i) {
                        case 0x10 -> new HostEmeraldLayoutProfile.Point(-4, -50);
                        case 0x11 -> new HostEmeraldLayoutProfile.Point(-24, -40);
                        case 0x12 -> new HostEmeraldLayoutProfile.Point(16, -40);
                        case 0x13 -> new HostEmeraldLayoutProfile.Point(-29, -18);
                        case 0x14 -> new HostEmeraldLayoutProfile.Point(21, -18);
                        case 0x15 -> new HostEmeraldLayoutProfile.Point(-15, -1);
                        case 0x16 -> new HostEmeraldLayoutProfile.Point(7, -1);
                        default -> new HostEmeraldLayoutProfile.Point(0, 0);
                    };
                    x = point.x();
                    y = point.y();
                }
                frames.add(new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(x, y, 1, 1, i, false, false, 0, false))));
            }
            return List.copyOf(frames);
        }
    }

    private static final class ForegroundScrollAssets extends RecordingAssets {
        private ForegroundScrollAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public int[] getMenuBackgroundLayoutWords() {
            return new int[]{0x0007};
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            Pattern[] patterns = new Pattern[8];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
            return patterns;
        }
    }

    private static final class FailingAssets implements S3kDataSelectAssetSource {
        private int loadCalls;

        @Override
        public void loadData() throws java.io.IOException {
            loadCalls++;
            throw new java.io.IOException("expected load failure");
        }

        @Override
        public boolean isLoaded() {
            return false;
        }

        @Override
        public int getMusicId() {
            return 0x2A;
        }

        @Override
        public int[] getLayoutWords() {
            return new int[0];
        }

        @Override
        public int[] getNewLayoutWords() {
            return new int[0];
        }

        @Override
        public int[][] getStaticLayouts() {
            return new int[0][];
        }

        @Override
        public int[] getMenuBackgroundLayoutWords() {
            return new int[0];
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getSkZonePatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getPortraitPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getS3ZonePatterns() {
            return new Pattern[0];
        }

        @Override
        public byte[] getMenuBackgroundPaletteBytes() {
            return new byte[0];
        }

        @Override
        public byte[] getCharacterPaletteBytes() {
            return new byte[0];
        }

        @Override
        public byte[] getEmeraldPaletteBytes() {
            return new byte[0];
        }

        @Override
        public byte[][] getFinishCardPalettes() {
            return new byte[0][];
        }

        @Override
        public byte[][] getZoneCardPalettes() {
            return new byte[0][];
        }

        @Override
        public byte[] getS3ZoneCard8PaletteBytes() {
            return new byte[0];
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return List.of();
        }

        @Override
        public S3kSaveScreenLayoutObjects getSaveScreenLayoutObjects() {
            return S3kSaveScreenLayoutObjects.original();
        }
    }

    private static final class SelectorOnlyAssets extends RecordingAssets {
        private final List<SpriteMappingFrame> selectorMappings = List.of(
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false))),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false))),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false))));

        private SelectorOnlyAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return selectorMappings;
        }
    }

    private static final class RecordingRenderer extends S3kDataSelectRenderer {
        private int drawCalls;
        private S3kSaveScreenObjectState lastObjectState;

        @Override
        public void draw(S3kDataSelectAssetSource assets, S3kSaveScreenObjectState objectState) {
            drawCalls++;
            lastObjectState = objectState;
        }
    }

    private static final class RecordingGraphics extends GraphicsManager {
        private int rectiCommands;
        private int renderPatternCalls;
        private int beginBatchCalls;
        private int flushBatchCalls;
        private int flushCalls;
        private final List<RenderPosition> renderPositions = new ArrayList<>();
        private final List<RenderCall> renderCalls = new ArrayList<>();
        private final List<ScaledRenderCall> scaledRenderCalls = new ArrayList<>();
        private final List<Integer> cachedPatternIds = new ArrayList<>();
        private final java.util.Map<Integer, Palette> cachedPalettes = new java.util.HashMap<>();

        @Override
        public void registerCommand(com.openggf.graphics.GLCommandable command) {
            if (command instanceof GLCommand glCommand && glCommand.getCommandType() == GLCommand.CommandType.RECTI) {
                rectiCommands++;
            }
        }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            renderPatternCalls++;
            renderPositions.add(new RenderPosition(x, y));
            renderCalls.add(new RenderCall(patternId, new PatternDesc(desc.get()), x, y));
        }

        @Override
        public void renderPatternWithIdScaled(int patternId, PatternDesc desc, float x, float y, float width, float height) {
            scaledRenderCalls.add(new ScaledRenderCall(patternId, new PatternDesc(desc.get()), x, y, width, height));
        }

        @Override
        public void cachePatternTexture(Pattern pattern, int patternId) {
            cachedPatternIds.add(patternId);
        }

        @Override
        public void cachePaletteTexture(Palette palette, int paletteId) {
            cachedPalettes.put(paletteId, palette.deepCopy());
        }

        @Override
        public void beginPatternBatch() {
            beginBatchCalls++;
        }

        @Override
        public void flushPatternBatch() {
            flushBatchCalls++;
        }

        @Override
        public void flush() {
            flushCalls++;
        }

        int rectiCommands() {
            return rectiCommands;
        }

        int renderPatternCalls() {
            return renderPatternCalls;
        }

        int firstRenderX() {
            return renderPositions.isEmpty() ? Integer.MIN_VALUE : renderPositions.get(0).x();
        }

        int lastRenderX() {
            return renderPositions.isEmpty() ? Integer.MIN_VALUE : renderPositions.get(renderPositions.size() - 1).x();
        }

        int maxRenderX() {
            return renderPositions.stream().mapToInt(RenderPosition::x).max().orElse(Integer.MIN_VALUE);
        }

        boolean containsRenderPosition(int x, int y) {
            return renderPositions.stream().anyMatch(pos -> pos.x() == x && pos.y() == y);
        }

        List<RenderPosition> renderPositions() {
            return List.copyOf(renderPositions);
        }

        Integer patternIdAt(int x, int y) {
            return renderCalls.stream()
                    .filter(call -> call.x() == x && call.y() == y)
                    .reduce((ignored, latest) -> latest)
                    .map(RenderCall::patternId)
                    .orElse(null);
        }

        RenderCall renderCallAt(int x, int y) {
            return renderCalls.stream()
                    .filter(call -> call.x() == x && call.y() == y)
                    .reduce((ignored, latest) -> latest)
                    .orElse(null);
        }

        boolean containsPatternId(int patternId) {
            return renderCalls.stream().anyMatch(call -> call.patternId() == patternId);
        }

        List<RenderCall> renderCalls() {
            return List.copyOf(renderCalls);
        }

        List<ScaledRenderCall> scaledRenderCalls() {
            return List.copyOf(scaledRenderCalls);
        }

        int cachedPatternIdsInRange(int startInclusive, int endExclusive) {
            return (int) cachedPatternIds.stream()
                    .filter(id -> id >= startInclusive && id < endExclusive)
                    .count();
        }

        Palette cachedPalette(int paletteId) {
            return cachedPalettes.get(paletteId);
        }

        int beginBatchCalls() {
            return beginBatchCalls;
        }

        int flushBatchCalls() {
            return flushBatchCalls;
        }

        int flushCalls() {
            return flushCalls;
        }

        private record ScaledRenderCall(int patternId, PatternDesc desc, float x, float y, float width, float height) {
        }
    }

    private record RenderPosition(int x, int y) {
    }

    private record RenderCall(int patternId, PatternDesc desc, int x, int y) {
    }

    private static final class VisualStateAssets extends RecordingAssets {
        private VisualStateAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public int[] getNewLayoutWords() {
            return new int[]{0x0001};
        }

        @Override
        public int[][] getStaticLayouts() {
            return new int[][]{
                    new int[]{0x0010},
                    new int[]{0x0011},
                    new int[]{0x0002},
                    new int[]{0x0003}
            };
        }
    }

    private static final class SelectedSlotIconAssets extends RecordingAssets {
        private final Pattern[] slotIconPatterns = new Pattern[70];
        private final List<SpriteMappingFrame> mappings;

        private SelectedSlotIconAssets() {
            super(0x2A);
            for (int i = 0; i < slotIconPatterns.length; i++) {
                slotIconPatterns[i] = new Pattern();
            }
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i < 0x18; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(0x17, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(0, 0, 1, 1, 0x31B, false, false, 3, false))));
            mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getSlotIconPatterns(int iconIndex) {
            return slotIconPatterns;
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class SelectedIconOverStaticOverlayAssets extends RecordingAssets {
        private final Pattern[] slotIconPatterns = new Pattern[70];
        private final List<SpriteMappingFrame> mappings;

        private SelectedIconOverStaticOverlayAssets() {
            super(0x2A);
            for (int i = 0; i < slotIconPatterns.length; i++) {
                slotIconPatterns[i] = new Pattern();
            }
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i < 0x18; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(0x17, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(-40, -120, 1, 1, 0x31B, false, false, 3, false))));
            mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            Pattern[] patterns = new Pattern[2];
            patterns[0] = new Pattern();
            patterns[1] = new Pattern();
            return patterns;
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getSlotIconPatterns(int iconIndex) {
            return slotIconPatterns;
        }

        @Override
        public int[][] getStaticLayouts() {
            return new int[][]{
                    new int[]{0x8001},
                    new int[]{0x8001},
                    new int[]{0x8001},
                    new int[]{0x8001}
            };
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class S2PurpleEmeraldWithSelectedPreviewAssets extends RecordingAssets {
        private final Pattern[] slotIconPatterns = new Pattern[12];
        private final List<SpriteMappingFrame> mappings;
        private final SpriteMappingFrame customFrame = new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(-40, -120, 4, 3, 0x31B, false, false, 2, false)));

        private S2PurpleEmeraldWithSelectedPreviewAssets() {
            super(0x2A);
            for (int i = 0; i < slotIconPatterns.length; i++) {
                slotIconPatterns[i] = new Pattern();
            }
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i <= 0x17; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(0x16, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(-40, -56, 1, 1, 0x10, false, false, 3, false))));
            mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            Pattern[] patterns = new Pattern[2];
            patterns[0] = new Pattern();
            patterns[1] = new Pattern();
            return patterns;
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getSlotIconPatterns(int iconIndex) {
            return slotIconPatterns;
        }

        @Override
        public byte[] getCustomEmeraldPaletteBytes() {
            byte[] bytes = new byte[30];
            bytes[16] = 0x0E;
            bytes[17] = (byte) 0xEE;
            return bytes;
        }

        @Override
        public byte[] getCharacterPaletteBytes() {
            byte[] bytes = new byte[64];
            bytes[32] = 0x0E;
            bytes[33] = 0x00;
            return bytes;
        }

        @Override
        public byte[] getEmeraldPaletteBytes() {
            byte[] bytes = new byte[30];
            bytes[0] = 0x00;
            bytes[1] = 0x0E;
            return bytes;
        }

        @Override
        public Palette getSelectedSlotIconPalette(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            Palette palette = new Palette();
            palette.getColor(1).r = 0;
            palette.getColor(1).g = 0;
            palette.getColor(1).b = 0;
            return palette;
        }

        @Override
        public SpriteMappingFrame getSelectedSlotIconFrame(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            return customFrame;
        }

        @Override
        public boolean useScaledSelectedSlotIconFrame(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            return true;
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class HostSelectedSlotIconAssets extends RecordingAssets {
        private final Pattern[] slotIconPatterns = new Pattern[12];
        private final SpriteMappingFrame customFrame = new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(-40, -120, 4, 3, 0x31B, false, false, 2, false)));

        private HostSelectedSlotIconAssets() {
            super(0x2A);
            for (int i = 0; i < slotIconPatterns.length; i++) {
                slotIconPatterns[i] = new Pattern();
            }
            loadData();
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getSlotIconPatterns(int iconIndex) {
            return slotIconPatterns;
        }

        @Override
        public Palette getSelectedSlotIconPalette(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            return new Palette();
        }

        @Override
        public SpriteMappingFrame getSelectedSlotIconFrame(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            return customFrame;
        }

        @Override
        public boolean useScaledSelectedSlotIconFrame(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            return true;
        }
    }

    private static final class ClearArrowsOverSelectedIconAssets extends RecordingAssets {
        private final Pattern[] slotIconPatterns = new Pattern[70];
        private final List<SpriteMappingFrame> mappings;

        private ClearArrowsOverSelectedIconAssets() {
            super(0x2A);
            for (int i = 0; i < slotIconPatterns.length; i++) {
                slotIconPatterns[i] = new Pattern();
            }
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i <= 0x1A; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(0x17, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(-40, -120, 1, 1, 0x31B, false, false, 3, false))));
            frames.set(0x1A, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(-40, -128, 1, 1, 2, false, false, 0, false))));
            mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            Pattern[] patterns = new Pattern[3];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
            return patterns;
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getSlotIconPatterns(int iconIndex) {
            return slotIconPatterns;
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class SelectedS3ZoneCard8PaletteAssets extends RecordingAssets {
        private final Pattern[] slotIconPatterns = new Pattern[70];
        private final List<SpriteMappingFrame> mappings;

        private SelectedS3ZoneCard8PaletteAssets() {
            super(0x2A);
            for (int i = 0; i < slotIconPatterns.length; i++) {
                slotIconPatterns[i] = new Pattern();
            }
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i < 0x18; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(0x17, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(0, 0, 1, 1, 0x31B, false, false, 3, false))));
            mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getSlotIconPatterns(int iconIndex) {
            return slotIconPatterns;
        }

        @Override
        public byte[][] getZoneCardPalettes() {
            byte[][] palettes = new byte[15][];
            for (int i = 0; i < palettes.length; i++) {
                palettes[i] = new byte[0x20];
            }
            palettes[7][0] = 0x00;
            palettes[7][1] = 0x0E;
            return palettes;
        }

        @Override
        public byte[] getS3ZoneCard8PaletteBytes() {
            byte[] bytes = new byte[0x20];
            bytes[0] = 0x0E;
            bytes[1] = 0x00;
            return bytes;
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class StaticTitleAssets extends RecordingAssets {
        private final List<SpriteMappingFrame> mappings;

        private StaticTitleAssets() {
            super(0x2A);
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(0, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(0, 0, 1, 1, 9, false, false, 0, false))));
            mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            Pattern[] patterns = new Pattern[10];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
            return patterns;
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class OverlappingPieceOrderAssets extends RecordingAssets {
        private final List<SpriteMappingFrame> mappings;

        private OverlappingPieceOrderAssets() {
            super(0x2A);
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(4, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(0, 0, 1, 1, 1, false, false, 0, false),
                    new SpriteMappingPiece(0, 0, 1, 1, 2, false, false, 0, false)
            )));
            mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public Pattern[] getMiscPatterns() {
            Pattern[] patterns = new Pattern[4];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
            return patterns;
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class PriorityOverlayAssets extends RecordingAssets {
        private final List<SpriteMappingFrame> mappings;

        private PriorityOverlayAssets() {
            super(0x2A);
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i < 0x18; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(0x17, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false),
                    new SpriteMappingPiece(8, 0, 1, 1, 0, false, false, 0, false),
                    new SpriteMappingPiece(16, 0, 1, 1, 0, false, false, 0, false))));
            this.mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public int[] getPlaneALayoutWords() {
            int[] words = new int[128 * 32];
            words[0] = 0x8001;
            return words;
        }

        @Override
        public Pattern[] getMenuBackgroundPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getMiscPatterns() {
            return new Pattern[]{new Pattern(), new Pattern()};
        }

        @Override
        public Pattern[] getExtraPatterns() {
            return new Pattern[0];
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class HighPriorityNewCardAssets extends RecordingAssets {
        private HighPriorityNewCardAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public int[] getNewLayoutWords() {
            return new int[]{0x8001};
        }

        @Override
        public Pattern[] getMiscPatterns() {
            return new Pattern[]{new Pattern(), new Pattern()};
        }
    }

    private static final class LowPriorityBodyBorderAssets extends RecordingAssets {
        private final List<SpriteMappingFrame> mappings;

        private LowPriorityBodyBorderAssets() {
            super(0x2A);
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i < 0x18; i++) {
                frames.add(new SpriteMappingFrame(List.of()));
            }
            frames.set(0x17, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false))));
            this.mappings = List.copyOf(frames);
            loadData();
        }

        @Override
        public int[] getPlaneALayoutWords() {
            int[] words = new int[128 * 32];
            words[0] = Sonic3kConstants.ARTTILE_SAVE_MISC + 0xA1;
            words[1] = Sonic3kConstants.ARTTILE_SAVE_MISC + 0xB5;
            words[2] = Sonic3kConstants.ARTTILE_SAVE_MISC + 0xC6;
            return words;
        }

        @Override
        public Pattern[] getMiscPatterns() {
            Pattern[] patterns = new Pattern[0xC7];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
            return patterns;
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            return mappings;
        }
    }

    private static final class TransparentBlankOverlayAssets extends RecordingAssets {
        private TransparentBlankOverlayAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public int[] getPlaneALayoutWords() {
            int[] words = new int[128 * 32];
            words[0x021A / 2] = 0x8000 | (Sonic3kConstants.ARTTILE_SAVE_MISC + 1);
            return words;
        }

        @Override
        public int[] getNewLayoutWords() {
            return new int[]{0x8000};
        }

        @Override
        public Pattern[] getMiscPatterns() {
            Pattern[] patterns = new Pattern[4];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
            return patterns;
        }
    }

    private static final class EmeraldPaletteAssets extends RecordingAssets {
        private EmeraldPaletteAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public byte[] getCharacterPaletteBytes() {
            byte[] bytes = new byte[64];
            bytes[32] = 0x0E;
            bytes[33] = 0x00;
            return bytes;
        }

        @Override
        public byte[] getEmeraldPaletteBytes() {
            return new byte[]{
                    0x00, 0x0E,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00
            };
        }
    }

    private static final class NoSaveDeleteFrameAssets extends RecordingAssets {
        private NoSaveDeleteFrameAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i <= 0xE; i++) {
                frames.add(new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, i, false, false, 0, false))));
            }
            return frames;
        }
    }

    private static class SlotObjectFrameAssets extends RecordingAssets {
        private SlotObjectFrameAssets() {
            super(0x2A);
            loadData();
        }

        @Override
        public int[] getNewLayoutWords() {
            return new int[]{0x0001};
        }

        @Override
        public int[][] getStaticLayouts() {
            return new int[][]{
                    new int[]{0x0001},
                    new int[]{0x0001},
                    new int[]{0x0001},
                    new int[]{0x0001}
            };
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            List<SpriteMappingFrame> frames = new ArrayList<>();
            for (int i = 0; i <= 0x23; i++) {
                frames.add(new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, i, false, false, 0, false))));
            }
            return frames;
        }
    }

    private static final class EmeraldOverShellAssets extends SlotObjectFrameAssets {
        @Override
        public int[] getLayoutWords() {
            int[] words = new int[128 * 32];
            words[(10 * 128) + 13] = 0x8001;
            return words;
        }

        @Override
        public List<SpriteMappingFrame> getSaveScreenMappings() {
            List<SpriteMappingFrame> frames = new ArrayList<>(super.getSaveScreenMappings());
            frames.set(0x10, new SpriteMappingFrame(List.of(
                    new SpriteMappingPiece(-40, -56, 1, 1, 0x10, false, false, 0, false)
            )));
            return frames;
        }
    }

    private static S3kSaveScreenObjectState.VisualState visualState(S3kSaveScreenLayoutObjects layoutObjects,
                                                                    int noSaveMappingFrame,
                                                                    int deleteMappingFrame,
                                                                    S3kSaveScreenObjectState.SlotVisualKind... kinds) {
        return visualState(layoutObjects, noSaveMappingFrame, deleteMappingFrame, 0, kinds);
    }

    private static S3kSaveScreenObjectState.VisualState visualState(S3kSaveScreenLayoutObjects layoutObjects,
                                                                    int noSaveMappingFrame,
                                                                    int deleteMappingFrame,
                                                                    int activeHeaderAnimationFrame,
                                                                    S3kSaveScreenObjectState.SlotVisualKind... kinds) {
        List<S3kSaveScreenObjectState.SlotVisualState> slotStates = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < layoutObjects.slots().size(); slotIndex++) {
            S3kSaveScreenObjectState.SlotVisualKind kind = slotIndex < kinds.length
                    ? kinds[slotIndex]
                    : S3kSaveScreenObjectState.SlotVisualKind.EMPTY;
            slotStates.add(slotState(slotIndex, kind, 4, -1));
        }
        return new S3kSaveScreenObjectState.VisualState(
                noSaveMappingFrame, 0xF, deleteMappingFrame, 8, activeHeaderAnimationFrame, slotStates);
    }

    private static S3kSaveScreenObjectState.VisualState visualState(S3kSaveScreenLayoutObjects layoutObjects,
                                                                    int noSaveMappingFrame,
                                                                    int noSaveChildMappingFrame,
                                                                    int deleteMappingFrame,
                                                                    int deleteChildMappingFrame,
                                                                    S3kSaveScreenObjectState.SlotVisualState... slotStates) {
        List<S3kSaveScreenObjectState.SlotVisualState> states = new ArrayList<>(List.of(slotStates));
        while (states.size() < layoutObjects.slots().size()) {
            states.add(slotState(states.size(), S3kSaveScreenObjectState.SlotVisualKind.EMPTY, 4, -1));
        }
        return new S3kSaveScreenObjectState.VisualState(
                noSaveMappingFrame,
                noSaveChildMappingFrame,
                deleteMappingFrame,
                deleteChildMappingFrame,
                0,
                states);
    }

    private static S3kSaveScreenObjectState.SlotVisualState slotState(int slotIndex,
                                                                      S3kSaveScreenObjectState.SlotVisualKind kind,
                                                                      int objectMappingFrame,
                                                                      int sub2MappingFrame,
                                                                      Integer... emeraldFrames) {
        return slotState(slotIndex, kind, objectMappingFrame, sub2MappingFrame,
                S3kSaveScreenObjectState.SlotLabelKind.BLANK, 0, 0, 0, 0, emeraldFrames);
    }

    private static S3kSaveScreenObjectState.SlotVisualState slotState(int slotIndex,
                                                                      S3kSaveScreenObjectState.SlotVisualKind kind,
                                                                      int objectMappingFrame,
                                                                      int sub2MappingFrame,
                                                                      S3kSaveScreenObjectState.SlotLabelKind labelKind,
                                                                      int zoneDisplayNumber,
                                                                      int headerStyleIndex,
                                                                      int lives,
                                                                      int continuesCount,
                                                                      Integer... emeraldFrames) {
        return new S3kSaveScreenObjectState.SlotVisualState(
                slotIndex,
                kind,
                objectMappingFrame,
                sub2MappingFrame,
                labelKind,
                zoneDisplayNumber,
                headerStyleIndex,
                lives,
                continuesCount,
                java.util.Arrays.stream(emeraldFrames)
                        .filter(java.util.Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .boxed()
                        .toList());
    }

    private static final class RecordingSfxRecorder {
        private final List<Integer> sfxIds = new ArrayList<>();

        void accept(int sfxId) {
            sfxIds.add(sfxId);
        }

        List<Integer> sfxIds() {
            return List.copyOf(sfxIds);
        }
    }

    private static int saveTextWordPatternId(char c) {
        return 0x50000 + Sonic3kConstants.ARTTILE_SAVE_TEXT - 0x10 + S3kSaveTextCodec.encode(c);
    }

    private static int saveTextDigitPatternId(int digit) {
        return 0x50000 + Sonic3kConstants.ARTTILE_SAVE_TEXT + digit;
    }

    private static int saveExtraPatternId(int tileOffset) {
        return 0x50000 + Sonic3kConstants.ARTTILE_SAVE_EXTRA + tileOffset;
    }

    private static RgbaImage solidPreviewImage(int argb) {
        RgbaImage image = new RgbaImage(80, 56, new int[80 * 56]);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                image.setArgb(x, y, argb);
            }
        }
        return image;
    }

    private static int lastRenderXForPattern(RecordingGraphics graphics, int patternId) {
        return graphics.renderCalls().stream()
                .filter(call -> call.patternId() == patternId)
                .reduce((ignored, latest) -> latest)
                .map(RenderCall::x)
                .orElse(Integer.MIN_VALUE);
    }

    private static void settleHorizontalMove(S3kDataSelectPresentation presentation) {
        for (int i = 0; i < 12; i++) {
            presentation.update(new InputHandler());
        }
    }

    private static void pressAndRelease(InputHandler input, int key, S3kDataSelectPresentation presentation) {
        input.handleKeyEvent(key, GLFW_PRESS);
        presentation.update(input);
        input.update();
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
        int leftKey = EngineServices.current().configuration().getInt(SonicConfiguration.LEFT);
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        if (key == leftKey || key == rightKey) {
            settleHorizontalMove(presentation);
        }
    }

    private static LogicalInputSnapshot logicalPress(int directionMask, int actionMask, boolean startPressed) {
        return LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(directionMask, directionMask, actionMask, actionMask, false, startPressed),
                PlayerInputState.neutral());
    }

    private static S3kDataSelectAssetSource newLoaderBackedAssets(Rom frontendRom,
                                                                  String hostGameCode) throws Exception {
        Class<?> romSourceType = Class.forName(
                "com.openggf.game.sonic3k.dataselect.S3kDataSelectPresentation$RomSource");
        Class<?> loaderType = Class.forName(
                "com.openggf.game.sonic3k.dataselect.S3kDataSelectPresentation$LoaderBackedAssets");
        Object romSource = java.lang.reflect.Proxy.newProxyInstance(
                romSourceType.getClassLoader(),
                new Class<?>[]{romSourceType},
                (proxy, method, args) -> frontendRom);
        Constructor<?> constructor = loaderType.getDeclaredConstructor(romSourceType, String.class);
        constructor.setAccessible(true);
        return (S3kDataSelectAssetSource) constructor.newInstance(romSource, hostGameCode);
    }

    private static Object readPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

}
