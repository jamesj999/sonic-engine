package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.data.RomManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.RomDetectionService;
import com.openggf.game.dataselect.DataSelectAction;
import com.openggf.game.dataselect.DataSelectActionType;
import com.openggf.game.dataselect.DataSelectSessionController;
import com.openggf.game.save.SaveManager;
import com.openggf.game.save.SaveSlotState;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic2.dataselect.S2DataSelectProfile;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestS3kDataSelectManager {

    @TempDir
    Path root;

    private SonicConfigurationService config;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        config.setConfigValue(SonicConfiguration.DATA_SELECT_EXTRA_PLAYER_COMBOS, "");
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void confirmNoSave_emitsNoSaveActionWithDefaultTeam() {
        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        input.handleKeyEvent(jumpKey, GLFW_PRESS);

        manager.update(input);

        DataSelectAction action = manager.consumePendingAction();
        assertEquals(DataSelectActionType.NO_SAVE_START, action.type());
        assertEquals(-1, action.slot());
        assertEquals(0, action.zone());
        assertEquals(0, action.act());
        assertEquals(new SelectedTeam("sonic", List.of("tails")), action.team());
        assertTrue(manager.isExiting());
    }

    @Test
    void emptySlotStart_emitsNewSlotActionForSelectedSlot() {
        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        input.handleKeyEvent(jumpKey, GLFW_PRESS);

        manager.update(input);

        DataSelectAction action = manager.consumePendingAction();
        assertEquals(DataSelectActionType.NEW_SLOT_START, action.type());
        assertEquals(1, action.slot());
        assertEquals(0, action.zone());
        assertEquals(0, action.act());
        assertEquals(new SelectedTeam("sonic", List.of("tails")), action.team());
        assertTrue(manager.isExiting());
    }

    @Test
    void existingSlot_emitsLoadActionUsingSavedZoneActAndTeam() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, Map.of(
                "zone", 3,
                "act", 1,
                "mainCharacter", "knuckles",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 3, 5),
                "clear", false
        ));

        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        input.handleKeyEvent(jumpKey, GLFW_PRESS);

        manager.update(input);

        DataSelectAction action = manager.consumePendingAction();
        assertEquals(DataSelectActionType.LOAD_SLOT, action.type());
        assertEquals(1, action.slot());
        assertEquals(3, action.zone());
        assertEquals(1, action.act());
        assertEquals(new SelectedTeam("knuckles", List.of("tails")), action.team());
        assertEquals(SaveSlotState.VALID, manager.slotSummaries().get(0).state());
    }

    @Test
    void clearSlot_confirmOnDefaultTerminalClearGraphicDoesNothing() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, Map.of(
                "zone", 0x0C,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 7,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "clear", true,
                "progressCode", 14,
                "clearState", 2
        ));

        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        input.handleKeyEvent(jumpKey, GLFW_PRESS);

        manager.update(input);

        DataSelectAction action = manager.consumePendingAction();
        assertEquals(DataSelectActionType.NONE, action.type());
    }

    @Test
    void clearSlot_rightCyclesRestartDestinationInsteadOfCurrentTeam() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, Map.of(
                "zone", 0x0C,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 7,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "clear", true,
                "progressCode", 14,
                "clearState", 2
        ));

        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        int upKey = config.getInt(SonicConfiguration.UP);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);

        input.handleKeyEvent(rightKey, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        input.update();

        input.handleKeyEvent(upKey, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(upKey, GLFW_RELEASE);
        input.update();

        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        manager.update(input);

        DataSelectAction action = manager.consumePendingAction();
        assertEquals(DataSelectActionType.CLEAR_RESTART, action.type());
        assertEquals(1, action.slot());
        assertEquals(0x00, action.zone());
        assertEquals(0, action.act());
        assertEquals(new SelectedTeam("sonic", List.of("tails")), action.team());
    }

    @Test
    void extraComboConfig_isIncludedAfterBuiltInTeamCycling() {
        config.setConfigValue(SonicConfiguration.DATA_SELECT_EXTRA_PLAYER_COMBOS, "sonic,knuckles");
        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int upKey = config.getInt(SonicConfiguration.UP);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        for (int i = 0; i < 4; i++) {
            input.handleKeyEvent(upKey, GLFW_PRESS);
            manager.update(input);
            input.update();
            input.handleKeyEvent(upKey, GLFW_RELEASE);
            input.update();
        }
        input.handleKeyEvent(jumpKey, GLFW_PRESS);

        manager.update(input);

        DataSelectAction action = manager.consumePendingAction();
        assertEquals(DataSelectActionType.NO_SAVE_START, action.type());
        assertEquals(new SelectedTeam("sonic", List.of("knuckles")), action.team());
    }

    @Test
    void donatedHostRoutesMenuMusicThroughS3kDonorAudio() {
        AudioManager audio = mock(AudioManager.class);
        EngineServices.configure(new EngineContext(
                config,
                GraphicsManager.getInstance(),
                audio,
                RomManager.getInstance(),
                PerformanceProfiler.getInstance(),
                DebugOverlayManager.getInstance(),
                PlaybackDebugManager.getInstance(),
                RomDetectionService.getInstance(),
                CrossGameFeatureProvider.getInstance()));

        DataSelectSessionController controller = new DataSelectSessionController(new S2DataSelectProfile());

        S3kDataSelectManager.resolveMusicPlayer(controller).accept(0x2A);

        verify(audio).playDonorMusic("s3k", 0x2A);
        verify(audio, never()).playMusic(0x2A);
    }

    @Test
    void donatedHostRoutesMenuSfxThroughS3kDonorAudio() {
        AudioManager audio = mock(AudioManager.class);
        EngineServices.configure(new EngineContext(
                config,
                GraphicsManager.getInstance(),
                audio,
                RomManager.getInstance(),
                PerformanceProfiler.getInstance(),
                DebugOverlayManager.getInstance(),
                PlaybackDebugManager.getInstance(),
                RomDetectionService.getInstance(),
                CrossGameFeatureProvider.getInstance()));

        DataSelectSessionController controller = new DataSelectSessionController(new S2DataSelectProfile());

        S3kDataSelectManager.resolveMenuSfxPlayer(controller).accept(0x7C);

        verify(audio).playDonorSfx("s3k", 0x7C);
        verify(audio, never()).playSfx(0x7C);
    }

    @Test
    void rightFromNoSave_selectsSlotOneInsteadOfCyclingTeam() {
        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(rightKey, GLFW_RELEASE);
        input.handleKeyEvent(jumpKey, GLFW_PRESS);

        manager.update(input);

        DataSelectAction action = manager.consumePendingAction();
        assertEquals(DataSelectActionType.NEW_SLOT_START, action.type());
        assertEquals(1, action.slot());
    }

    @Test
    void upFromNoSave_cyclesToNextBuiltInTeam() {
        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int upKey = config.getInt(SonicConfiguration.UP);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        input.handleKeyEvent(upKey, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(upKey, GLFW_RELEASE);
        input.handleKeyEvent(jumpKey, GLFW_PRESS);

        manager.update(input);

        DataSelectAction action = manager.consumePendingAction();
        assertEquals(DataSelectActionType.NO_SAVE_START, action.type());
        assertEquals(new SelectedTeam("sonic", List.of()), action.team());
    }

    @Test
    void newSaveAndNoSaveTeamsPersistPerEntry() {
        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        int leftKey = config.getInt(SonicConfiguration.LEFT);
        int upKey = config.getInt(SonicConfiguration.UP);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);

        pressAndRelease(input, rightKey, manager);
        pressAndRelease(input, rightKey, manager);
        pressAndRelease(input, upKey, manager);
        pressAndRelease(input, upKey, manager);

        pressAndRelease(input, leftKey, manager);
        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        manager.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);

        DataSelectAction slotOneAction = manager.consumePendingAction();
        assertEquals(DataSelectActionType.NEW_SLOT_START, slotOneAction.type());
        assertEquals(1, slotOneAction.slot());
        assertEquals(new SelectedTeam("sonic", List.of("tails")), slotOneAction.team(),
                "slot 1 should retain its own team instead of inheriting slot 2's last selection");

        manager.reset();
        manager.initialize();

        pressAndRelease(input, upKey, manager);
        pressAndRelease(input, upKey, manager);
        pressAndRelease(input, upKey, manager);
        pressAndRelease(input, rightKey, manager);
        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        manager.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);

        DataSelectAction slotOneAfterNoSaveChange = manager.consumePendingAction();
        assertEquals(DataSelectActionType.NEW_SLOT_START, slotOneAfterNoSaveChange.type());
        assertEquals(1, slotOneAfterNoSaveChange.slot());
        assertEquals(new SelectedTeam("sonic", List.of("tails")), slotOneAfterNoSaveChange.team(),
                "changing NO SAVE should not overwrite a fresh slot's default team");
    }

    @Test
    void deleteMode_confirmOnSlotErasesSaveWithoutStartingGameplay() throws Exception {
        SaveManager saveManager = new SaveManager(root);
        saveManager.writeSlot("s3k", 1, Map.of(
                "zone", 2,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));

        S3kDataSelectManager manager = createManager();
        manager.initialize();

        InputHandler input = new InputHandler();
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        int leftKey = config.getInt(SonicConfiguration.LEFT);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);

        for (int i = 0; i < 9; i++) {
            pressAndRelease(input, rightKey, manager);
        }

        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);
        input.update();

        for (int i = 0; i < 8; i++) {
            pressAndRelease(input, leftKey, manager);
        }

        input.handleKeyEvent(jumpKey, GLFW_PRESS);
        manager.update(input);
        input.handleKeyEvent(jumpKey, GLFW_RELEASE);
        for (int i = 0; i < 24; i++) {
            manager.update(new InputHandler());
        }
        input.update();
        input.handleKeyEvent(leftKey, GLFW_PRESS);
        manager.update(input);

        assertEquals(SaveSlotState.EMPTY, manager.slotSummaries().get(0).state());
        assertEquals(DataSelectActionType.NONE, manager.consumePendingAction().type());
        assertFalse(manager.isExiting());
    }

    private static void pressAndRelease(InputHandler input, int key, S3kDataSelectManager manager) {
        input.handleKeyEvent(key, GLFW_PRESS);
        manager.update(input);
        input.update();
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
        int leftKey = EngineServices.current().configuration().getInt(SonicConfiguration.LEFT);
        int rightKey = EngineServices.current().configuration().getInt(SonicConfiguration.RIGHT);
        if (key == leftKey || key == rightKey) {
            for (int i = 0; i < 12; i++) {
                manager.update(new InputHandler());
            }
        }
    }

    private S3kDataSelectManager createManager() {
        return new S3kDataSelectManager(
                new DataSelectSessionController(new S3kDataSelectProfile()),
                root,
                config,
                new SuccessfulAssets(),
                new S3kDataSelectRenderer(),
                ignored -> {
                });
    }

    private static final class SuccessfulAssets implements S3kDataSelectAssetSource {
        private boolean loaded;

        @Override
        public void loadData() {
            loaded = true;
        }

        @Override
        public boolean isLoaded() {
            return loaded;
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
}
