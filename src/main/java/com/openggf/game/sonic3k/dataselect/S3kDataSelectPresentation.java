package com.openggf.game.sonic3k.dataselect;


import com.openggf.game.session.EngineServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.dataselect.AbstractDataSelectProvider;
import com.openggf.game.dataselect.DataSelectAction;
import com.openggf.game.dataselect.DataSelectActionType;
import com.openggf.game.dataselect.DataSelectDestination;
import com.openggf.game.dataselect.DataSelectGameProfile;
import com.openggf.game.dataselect.DataSelectHostProfile;
import com.openggf.game.dataselect.DataSelectMenuModel;
import com.openggf.game.dataselect.DataSelectSessionController;
import com.openggf.game.dataselect.HostSlotPreview;
import com.openggf.game.save.SaveManager;
import com.openggf.game.save.SaveSlotState;
import com.openggf.game.save.SaveSlotSummary;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic1.dataselect.S1DataSelectImageCacheManager;
import com.openggf.game.sonic1.dataselect.S1SelectedSlotPreviewLoader;
import com.openggf.game.sonic2.dataselect.S2DataSelectImageCacheManager;
import com.openggf.game.sonic2.dataselect.S2SelectedSlotPreviewLoader;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

public class S3kDataSelectPresentation extends AbstractDataSelectProvider {
    private static final int ENTRY_FADE_DURATION = 21;
    private static final int[] DELETE_MAIN_FRAMES = {0xD, 0xE, 0xD, 0xE, 0xD, 0xE, 0xD, 0xE, 0xD, 0xE, 0xD, 0xD, 0xD, 0xD};
    private static final int[] S1_HOST_EMERALD_FRAMES = {0x13, 0x11, 0x12, 0x10, 0x15, 0x14};
    private static final int[] S2_HOST_EMERALD_FRAMES = {0x13, 0x16, 0x15, 0x12, 0x11, 0x10, 0x14};

    private final DataSelectHostProfile hostProfile;
    private final SaveManager saveManager;
    private final SonicConfigurationService config;
    private final S3kDataSelectAssetSource assets;
    private final S3kDataSelectRenderer renderer;
    private final IntConsumer musicPlayer;
    private final IntConsumer menuSfxPlayer;
    private final S3kSaveScreenSelectorState selectorState;
    private final boolean requireAssetsForActivation;
    private int frameCounter;
    private DeleteRobotnikState deleteRobotnikState = DeleteRobotnikState.HOME;
    private int deleteWorldX = S3kSaveScreenLayoutObjects.original().deleteIcon().worldX();
    private int deleteTargetRow = -1;
    private int deleteMainAnimFrameIndex;
    private int deleteMainAnimTimer;
    private int deleteSignFrame = 8;
    private int deleteSignAnimTimer;
    private int displayedSelectedRow;
    private int fadeTimer;

    // Cached computation results — avoid per-frame allocations
    private S3kCustomTeamPortraitComposer cachedPortraitComposer;
    private final java.util.Map<SelectedTeam, SpriteMappingFrame> cachedPortraitFrames = new java.util.HashMap<>();
    private List<List<Integer>> cachedEmeraldFrames;
    private List<SaveSlotSummary> cachedEmeraldSummaries;

    private enum DeleteRobotnikState {
        HOME,
        FOLLOWING,
        PROMPT_ANIMATING,
        PROMPT_CHOICE,
        RETURNING
    }

    public S3kDataSelectPresentation(DataSelectSessionController controller) {
        this(controller, Path.of("saves"), EngineServices.current().configuration());
    }

    public S3kDataSelectPresentation(Path saveRoot, SonicConfigurationService config) {
        this(new DataSelectSessionController(new S3kDataSelectProfile()), saveRoot, config);
    }

    public S3kDataSelectPresentation(DataSelectSessionController controller,
                                     Path saveRoot,
                                     SonicConfigurationService config) {
        this(controller,
                new SaveManager(saveRoot),
                config,
                createDefaultAssets(),
                new S3kDataSelectRenderer(),
                S3kDataSelectPresentation::playMusicSafely,
                new S3kSaveScreenSelectorState(S3kDataSelectPresentation::playMovementSafely),
                S3kDataSelectPresentation::playMovementSafely,
                false);
    }

    S3kDataSelectPresentation(DataSelectSessionController controller,
                              SaveManager saveManager,
                              SonicConfigurationService config,
                              S3kDataSelectAssetSource assets,
                              S3kDataSelectRenderer renderer,
                              IntConsumer musicPlayer) {
        this(controller, saveManager, config, assets, renderer, musicPlayer,
                new S3kSaveScreenSelectorState(S3kDataSelectPresentation::playMovementSafely),
                S3kDataSelectPresentation::playMovementSafely,
                true);
    }

    S3kDataSelectPresentation(DataSelectSessionController controller,
                              SaveManager saveManager,
                              SonicConfigurationService config,
                              S3kDataSelectAssetSource assets,
                              S3kDataSelectRenderer renderer,
                              IntConsumer musicPlayer,
                              S3kSaveScreenSelectorState selectorState) {
        this(controller, saveManager, config, assets, renderer, musicPlayer, selectorState,
                S3kDataSelectPresentation::playMovementSafely, true);
    }

    S3kDataSelectPresentation(DataSelectSessionController controller,
                              SaveManager saveManager,
                              SonicConfigurationService config,
                              S3kDataSelectAssetSource assets,
                              S3kDataSelectRenderer renderer,
                              IntConsumer musicPlayer,
                              S3kSaveScreenSelectorState selectorState,
                              IntConsumer menuSfxPlayer) {
        this(controller, saveManager, config, assets, renderer, musicPlayer, selectorState, menuSfxPlayer, true);
    }

    S3kDataSelectPresentation(DataSelectSessionController controller,
                              SaveManager saveManager,
                              SonicConfigurationService config,
                              S3kDataSelectAssetSource assets,
                              S3kDataSelectRenderer renderer,
                              IntConsumer musicPlayer,
                              S3kSaveScreenSelectorState selectorState,
                              IntConsumer menuSfxPlayer,
                              boolean requireAssetsForActivation) {
        this.hostProfile = controller.hostProfile();
        this.saveManager = saveManager;
        this.config = config;
        this.assets = assets;
        this.renderer = renderer;
        this.musicPlayer = musicPlayer;
        this.menuSfxPlayer = menuSfxPlayer;
        this.selectorState = selectorState;
        this.requireAssetsForActivation = requireAssetsForActivation;
        attachSessionController(controller);
    }

    @Override
    public void initialize() {
        sessionController.reset();
        sessionController.loadAvailableTeams(config.getString(SonicConfiguration.DATA_SELECT_EXTRA_PLAYER_COMBOS));
        reloadSlotSummaries();
        selectorState.setCurrentEntry(menuModel().getSelectedRow());
        displayedSelectedRow = menuModel().getSelectedRow();
        boolean assetsLoaded = loadAssets();
        if (!assetsLoaded && requireAssetsForActivation) {
            state = State.INACTIVE;
            return;
        }
        if (assetsLoaded) {
            musicPlayer.accept(assets.getMusicId());
        }
        frameCounter = 0;
        fadeTimer = 0;
        resetDeleteRobotnikState();
        state = State.FADE_IN;
    }

    @Override
    public void update(InputHandler input) {
        if (state == State.FADE_IN) {
            fadeTimer++;
            if (fadeTimer >= ENTRY_FADE_DURATION) {
                state = State.ACTIVE;
            }
        }
        if (state != State.ACTIVE && state != State.FADE_IN) {
            return;
        }
        frameCounter++;
        selectorState.advanceFrame();
        if (!selectorState.isMoving()) {
            displayedSelectedRow = menuModel().getSelectedRow();
        }
        advanceDeleteRobotnikState();

        int upKey = config.getInt(SonicConfiguration.UP);
        int downKey = config.getInt(SonicConfiguration.DOWN);
        int leftKey = config.getInt(SonicConfiguration.LEFT);
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        int jumpKey = config.getInt(SonicConfiguration.JUMP);
        boolean upPressed = input.isKeyPressed(upKey) || input.logical().menuUp();
        boolean downPressed = input.isKeyPressed(downKey) || input.logical().menuDown();
        boolean leftPressed = input.isKeyPressed(leftKey) || input.logical().menuLeft();
        boolean rightPressed = input.isKeyPressed(rightKey) || input.logical().menuRight();
        boolean backPressed = input.isKeyPressed(GLFW_KEY_ESCAPE) || input.logical().menuBack();
        boolean confirmPressed = input.isKeyPressed(jumpKey) || input.menuAcceptExcludingBackAction();

        if (deleteRobotnikState == DeleteRobotnikState.PROMPT_CHOICE) {
            if (leftPressed) {
                commitDeletePrompt();
                return;
            }
            if (rightPressed || backPressed) {
                startDeleteRetreat();
                return;
            }
            return;
        }

        if (deleteRobotnikState == DeleteRobotnikState.PROMPT_ANIMATING) {
            if (backPressed) {
                startDeleteRetreat();
            }
            return;
        }

        if (upPressed) {
            handleVerticalAdjustment(1);
            return;
        }
        if (downPressed) {
            handleVerticalAdjustment(-1);
            return;
        }
        if (leftPressed) {
            if (!selectorState.isMoving() && selectorState.moveLeft(menuModel().isDeleteMode())) {
                displayedSelectedRow = menuModel().getSelectedRow();
                sessionController.moveSelection(-1);
                syncDeleteRobotnikToCursorIfNeeded();
            }
            return;
        }
        if (rightPressed) {
            if (!selectorState.isMoving() && selectorState.moveRight(menuModel().isDeleteMode())) {
                displayedSelectedRow = menuModel().getSelectedRow();
                sessionController.moveSelection(1);
                syncDeleteRobotnikToCursorIfNeeded();
            }
            return;
        }
        if (backPressed) {
            if (menuModel().isDeleteMode()) {
                startDeleteRetreat();
            } else {
                sessionController.dismissDeleteMode();
            }
            return;
        }
        if (confirmPressed) {
            if (handleDeleteRobotnikAction()) {
                return;
            }
            handleControllerAction(sessionController.confirmSelection());
        }
    }

    @Override
    public void draw() {
        renderer.draw(assets, buildObjectState());
        float fadeAlpha = currentFadeAlpha();
        if (fadeAlpha > 0.0f) {
            GraphicsManager graphics = GameServices.graphics();
            if (graphics != null && !graphics.isHeadlessMode()) {
                // Use projectionWidth for the fade overlay so it covers the full viewport.
                int vpWidth = graphics.getProjectionWidth();
                graphics.registerCommand(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        -1,
                        GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                        0.0f, 0.0f, 0.0f, fadeAlpha,
                        0, 0, vpWidth, 224
                ));
            }
        }
    }

    @Override
    public void setViewportWidth(int width) {
        renderer.setViewportWidth(width);
    }

    @Override
    public void setClearColor() {
        renderer.setClearColor(assets);
    }

    @Override
    public void reset() {
        state = State.INACTIVE;
        launchErrorMessage = null;
        renderer.reset();
        if (sessionController != null) {
            sessionController.reset();
        }
        selectorState.setCurrentEntry(0);
        displayedSelectedRow = 0;
        frameCounter = 0;
        fadeTimer = 0;
        resetDeleteRobotnikState();
    }

    @Override
    public void showLaunchError(String message) {
        super.showLaunchError(message);
        reloadSlotSummaries();
        selectorState.setCurrentEntry(menuModel().getSelectedRow());
        displayedSelectedRow = menuModel().getSelectedRow();
        fadeTimer = 0;
        resetDeleteRobotnikState();
    }

    float currentFadeAlpha() {
        if (state != State.FADE_IN) {
            return 0.0f;
        }
        float progress = Math.min(1.0f, (float) fadeTimer / ENTRY_FADE_DURATION);
        return 1.0f - progress;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public boolean isExiting() {
        return state == State.EXITING;
    }

    @Override
    public boolean isActive() {
        return state != State.INACTIVE;
    }

    public List<SaveSlotSummary> slotSummaries() {
        return Collections.unmodifiableList(sessionController.slotSummaries());
    }

    protected SelectedTeam currentTeam() {
        return sessionController.currentTeam();
    }

    protected DataSelectHostProfile hostProfile() {
        return hostProfile;
    }

    protected void reloadSlotSummaries() {
        // Invalidate cached per-slot data since summaries are changing
        cachedObjectState = null;
        cachedAnimBits = -1;
        cachedEmeraldFrames = null;
        cachedEmeraldSummaries = null;
        cachedPortraitFrames.clear();
        List<SaveSlotSummary> summaries = new ArrayList<>();
        for (int slot = 1; slot <= hostProfile.slotCount(); slot++) {
            try {
                DataSelectGameProfile legacyProfile =
                        hostProfile instanceof DataSelectGameProfile gameProfile ? gameProfile : null;
                summaries.add(saveManager.readSlotSummary(hostProfile.gameCode(), slot, legacyProfile));
            } catch (IOException e) {
                summaries.add(hostProfile.summarizeFreshSlot(slot));
            }
        }
        sessionController.loadSlotSummaries(summaries);
    }

    // Cached object state — only rebuilt when animation-relevant inputs change
    private S3kSaveScreenObjectState cachedObjectState;
    private int cachedAnimBits = -1;
    private int cachedSelectedRow = -1;
    private int cachedDeleteWorldX = Integer.MIN_VALUE;
    private DeleteRobotnikState cachedDeleteState;
    private int cachedDeleteAnimFrame = -1;
    private int cachedDeleteSignFrame = -1;
    private int cachedClearRestartIndex = -1;
    private String cachedLaunchErrorMessage;

    private S3kSaveScreenObjectState buildObjectState() {
        selectorState.setVisible(isSelectorVisible());

        // Compute a key from all inputs that affect the object state
        int animBits = (frameCounter >> 2) & 0x7; // captures blink (bit 4) + header anim (bits 2-3)
        int clearIdx = sessionController.currentClearRestartIndex();

        if (cachedObjectState != null
                && animBits == cachedAnimBits
                && displayedSelectedRow == cachedSelectedRow
                && deleteWorldX == cachedDeleteWorldX
                && deleteRobotnikState == cachedDeleteState
                && deleteMainAnimFrameIndex == cachedDeleteAnimFrame
                && deleteSignFrame == cachedDeleteSignFrame
                && clearIdx == cachedClearRestartIndex
                && Objects.equals(launchErrorMessage, cachedLaunchErrorMessage)) {
            // Update selector state in-place (position animates smoothly)
            return cachedObjectState;
        }

        cachedAnimBits = animBits;
        cachedSelectedRow = displayedSelectedRow;
        cachedDeleteWorldX = deleteWorldX;
        cachedDeleteState = deleteRobotnikState;
        cachedDeleteAnimFrame = deleteMainAnimFrameIndex;
        cachedDeleteSignFrame = deleteSignFrame;
        cachedClearRestartIndex = clearIdx;
        cachedLaunchErrorMessage = launchErrorMessage;

        cachedObjectState = new S3kSaveScreenObjectState(
                assets.getSaveScreenLayoutObjects(),
                selectorState,
                buildVisualState(),
                buildSelectedSlotIcon(),
                deleteWorldX,
                launchErrorMessage);
        return cachedObjectState;
    }

    private S3kSaveScreenObjectState.VisualState buildVisualState() {
        List<SaveSlotSummary> summaries = sessionController.slotSummaries();
        int selectedRow = displayedSelectedRow;
        int slotCount = hostProfile.slotCount();
        List<S3kSaveScreenObjectState.SlotVisualState> slotStates = new ArrayList<>(slotCount);
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            SaveSlotSummary summary = slotIndex < summaries.size() ? summaries.get(slotIndex) : null;
            boolean selected = selectedRow == slotIndex + 1;
            slotStates.add(buildSlotVisualState(slotIndex, summary, selected));
        }
        boolean blinkVisible = (frameCounter & 0x10) != 0;
        SelectedTeam noSaveTeam = sessionController.noSaveTeam();
        return new S3kSaveScreenObjectState.VisualState(
                resolveNoSaveMappingFrame(noSaveTeam),
                resolveCustomPortraitFrame(noSaveTeam),
                selectedRow == 0 && blinkVisible && !menuModel().isDeleteMode() ? 0xF : -1,
                resolveDeleteMappingFrame(),
                resolveDeleteChildMappingFrame(),
                resolveActiveHeaderAnimationFrame(),
                slotStates);
    }

    private int resolveActiveHeaderAnimationFrame() {
        return (frameCounter >> 2) & 0x3;
    }

    private boolean isSelectorVisible() {
        return (frameCounter & 0x4) == 0;
    }

    private int resolveNoSaveMappingFrame(SelectedTeam team) {
        return 4 + playerOptionIndexFor(team);
    }

    private int resolveDeleteMappingFrame() {
        if (deleteRobotnikState == DeleteRobotnikState.HOME && menuModel().isDeleteMode()) {
            return 0xE;
        }
        return switch (deleteRobotnikState) {
            case FOLLOWING, PROMPT_ANIMATING, PROMPT_CHOICE -> DELETE_MAIN_FRAMES[deleteMainAnimFrameIndex];
            case HOME, RETURNING -> 0xD;
        };
    }

    private int resolveDeleteChildMappingFrame() {
        return deleteSignFrame;
    }

    private S3kSaveScreenObjectState.SlotVisualKind classifySlot(SaveSlotSummary summary) {
        if (summary == null || summary.state() == SaveSlotState.EMPTY) {
            return S3kSaveScreenObjectState.SlotVisualKind.EMPTY;
        }
        if (Boolean.TRUE.equals(summary.payload().get("clear"))) {
            return S3kSaveScreenObjectState.SlotVisualKind.CLEAR;
        }
        return S3kSaveScreenObjectState.SlotVisualKind.OCCUPIED;
    }

    private void handleControllerAction(DataSelectAction action) {
        if (action.type() == DataSelectActionType.DELETE_SLOT) {
            saveManager.deleteSlot(hostProfile.gameCode(), action.slot());
            reloadSlotSummaries();
            menuModel().setClearRestartIndex(0);
            return;
        }
        if (action.type() != DataSelectActionType.NONE) {
            sessionController.queuePendingAction(action);
            state = State.EXITING;
        }
    }

    private boolean handleDeleteRobotnikAction() {
        int selectedRow = menuModel().getSelectedRow();
        if (deleteRobotnikState == DeleteRobotnikState.HOME) {
            if (selectedRow == sessionController.deleteRowIndex()) {
                menuSfxPlayer.accept(Sonic3kSfx.STARPOST.id);
                deleteRobotnikState = DeleteRobotnikState.FOLLOWING;
                menuModel().setDeleteMode(true);
                syncDeleteRobotnikToCursorIfNeeded();
                resetDeleteAnimationsForFollow();
                return true;
            }
            return false;
        }
        if (deleteRobotnikState != DeleteRobotnikState.FOLLOWING) {
            return false;
        }
        if (selectedRow == sessionController.deleteRowIndex() || isFreshSlotSelection(selectedRow)) {
            startDeleteRetreat();
            return true;
        }
        if (selectedRow >= 1 && selectedRow <= hostProfile.slotCount()) {
            SaveSlotSummary summary = sessionController.slotSummaries().get(selectedRow - 1);
            if (summary.hasRecoverablePayload()) {
                menuSfxPlayer.accept(Sonic3kSfx.STARPOST.id);
                deleteTargetRow = selectedRow;
                deleteRobotnikState = DeleteRobotnikState.PROMPT_ANIMATING;
                resetDeleteAnimationsForPrompt();
                return true;
            }
        }
        return false;
    }

    private void commitDeletePrompt() {
        if (deleteTargetRow >= 1 && deleteTargetRow <= hostProfile.slotCount()) {
            menuSfxPlayer.accept(Sonic3kSfx.PERFECT.id);
            saveManager.deleteSlot(hostProfile.gameCode(), deleteTargetRow);
            reloadSlotSummaries();
        }
        startDeleteRetreat();
    }

    private void startDeleteRetreat() {
        deleteRobotnikState = DeleteRobotnikState.RETURNING;
        deleteTargetRow = -1;
        menuModel().setDeleteMode(false);
        deleteMainAnimFrameIndex = 0;
        deleteMainAnimTimer = 0;
        deleteSignFrame = 8;
        deleteSignAnimTimer = 0;
    }

    private void advanceDeleteRobotnikState() {
        switch (deleteRobotnikState) {
            case HOME -> {
                deleteWorldX = deleteHomeWorldX();
                menuModel().setDeleteMode(false);
            }
            case FOLLOWING -> {
                syncDeleteRobotnikToCursorIfNeeded();
                advanceDeleteMainAnimation();
                advanceDeleteSignAnimation();
            }
            case PROMPT_ANIMATING -> {
                advanceDeleteMainAnimation();
                if (advanceDeleteSignAnimation() && deleteSignFrame == 0xB) {
                    deleteSignFrame = 0xC;
                    deleteRobotnikState = DeleteRobotnikState.PROMPT_CHOICE;
                }
            }
            case PROMPT_CHOICE -> {
                advanceDeleteMainAnimation();
                deleteSignFrame = 0xC;
            }
            case RETURNING -> {
                deleteWorldX = Math.min(deleteHomeWorldX(), deleteWorldX + 8);
                if (deleteWorldX >= deleteHomeWorldX()) {
                    resetDeleteRobotnikState();
                }
            }
        }
    }

    private void syncDeleteRobotnikToCursorIfNeeded() {
        if (deleteRobotnikState == DeleteRobotnikState.FOLLOWING) {
            deleteWorldX = selectorState.selectorBiasedX() + selectorState.cameraX();
        }
    }

    private boolean advanceDeleteSignAnimation() {
        deleteSignAnimTimer--;
        if (deleteSignAnimTimer >= 0) {
            return false;
        }
        deleteSignAnimTimer = 3;
        int cycleIndex = ((deleteSignFrame - 8) + 1) & 0x3;
        deleteSignFrame = 8 + cycleIndex;
        return true;
    }

    private void advanceDeleteMainAnimation() {
        deleteMainAnimTimer--;
        if (deleteMainAnimTimer >= 0) {
            return;
        }
        deleteMainAnimTimer = 5;
        deleteMainAnimFrameIndex = (deleteMainAnimFrameIndex + 1) % DELETE_MAIN_FRAMES.length;
    }

    private void resetDeleteAnimationsForFollow() {
        deleteMainAnimFrameIndex = 0;
        deleteMainAnimTimer = 0;
        deleteSignFrame = 8;
        deleteSignAnimTimer = 0;
    }

    private void resetDeleteAnimationsForPrompt() {
        deleteMainAnimFrameIndex = 0;
        deleteMainAnimTimer = 0;
        deleteSignFrame = 8;
        deleteSignAnimTimer = 0;
    }

    private void resetDeleteRobotnikState() {
        deleteRobotnikState = DeleteRobotnikState.HOME;
        deleteWorldX = deleteHomeWorldX();
        deleteTargetRow = -1;
        menuModel().setDeleteMode(false);
        deleteMainAnimFrameIndex = 0;
        deleteMainAnimTimer = 0;
        deleteSignFrame = 8;
        deleteSignAnimTimer = 0;
    }

    private int deleteHomeWorldX() {
        if (assets != null && assets.isLoaded()) {
            return assets.getSaveScreenLayoutObjects().deleteIcon().worldX();
        }
        return S3kSaveScreenLayoutObjects.original().deleteIcon().worldX();
    }

    private boolean isFreshSlotSelection(int selectedRow) {
        if (selectedRow < 1 || selectedRow > hostProfile.slotCount()) {
            return false;
        }
        List<SaveSlotSummary> summaries = sessionController.slotSummaries();
        if (selectedRow > summaries.size()) {
            return false;
        }
        SaveSlotSummary summary = summaries.get(selectedRow - 1);
        return summary == null || summary.state() == SaveSlotState.EMPTY;
    }

    private void handleVerticalAdjustment(int delta) {
        if (shouldCycleTeamForCurrentSelection()) {
            int before = menuModel().getSelectedTeamIndex();
            sessionController.cycleTeam(delta);
            if (before != menuModel().getSelectedTeamIndex()) {
                menuSfxPlayer.accept(Sonic3kSfx.SWITCH.id);
            }
            return;
        }
        if (sessionController.shouldCycleClearRestart()) {
            int before = menuModel().getClearRestartIndex();
            sessionController.cycleClearRestart(delta);
            if (before != menuModel().getClearRestartIndex()) {
                menuSfxPlayer.accept(Sonic3kSfx.SWITCH.id);
            }
        }
    }

    private boolean shouldCycleTeamForCurrentSelection() {
        if (menuModel().isDeleteMode()) {
            return false;
        }
        int selectedRow = menuModel().getSelectedRow();
        if (selectedRow == 0) {
            return true;
        }
        if (selectedRow < 1 || selectedRow > hostProfile.slotCount()) {
            return false;
        }
        List<SaveSlotSummary> summaries = sessionController.slotSummaries();
        if (selectedRow > summaries.size()) {
            return false;
        }
        SaveSlotSummary summary = summaries.get(selectedRow - 1);
        return summary == null || summary.state() == SaveSlotState.EMPTY;
    }

    private boolean loadAssets() {
        try {
            assets.loadData();
            return assets.isLoaded();
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private S3kSaveScreenObjectState.SelectedSlotIcon buildSelectedSlotIcon() {
        int selectedRow = displayedSelectedRow;
        if (selectedRow <= 0 || selectedRow > hostProfile.slotCount()) {
            return null;
        }
        int slotIndex = selectedRow - 1;
        List<SaveSlotSummary> summaries = sessionController.slotSummaries();
        if (slotIndex >= summaries.size()) {
            return null;
        }
        SaveSlotSummary summary = summaries.get(slotIndex);
        if (summary == null || !summary.hasRecoverablePayload()) {
            return null;
        }
        // Host preview active → suppress the S3K zone card icon (no host zone art available)
        S3kSaveScreenLayoutObjects.SaveSlotObject slotObject = assets.getSaveScreenLayoutObjects().slots().get(slotIndex);
        int hostIconIndex = resolveHostSelectedSlotIconIndex(summary);
        if (hostIconIndex >= 0) {
            return new S3kSaveScreenObjectState.SelectedSlotIcon(
                    slotIndex,
                    slotObject.worldX(),
                    slotObject.worldY(),
                    hostIconIndex,
                    false,
                    hostIconIndex,
                    0x17);
        }
        int progressCode = Math.max(1, S3kSaveProgressions.progressCodeForPayload(summary.payload()));
        if (Boolean.TRUE.equals(summary.payload().get("clear"))) {
            int iconIndex = Math.max(0, Math.min(14, sessionController.currentClearRestartIndex()));
            int terminalIndex = Math.max(0, Math.min(14, S3kSaveProgressions.terminalClearMarkerIndex(summary.payload())));
            boolean finishCard = iconIndex == terminalIndex;
            return new S3kSaveScreenObjectState.SelectedSlotIcon(
                    slotIndex,
                    slotObject.worldX(),
                    slotObject.worldY(),
                    iconIndex,
                    finishCard,
                    finishCard ? resolveFinishPaletteIndex(summary.payload()) : iconIndex,
                    finishCard ? resolveFinishCardMappingFrame(summary.payload()) : 0x17);
        }
        int iconIndex = Math.min(14, progressCode - 1);
        return new S3kSaveScreenObjectState.SelectedSlotIcon(
                slotIndex,
                slotObject.worldX(),
                slotObject.worldY(),
                iconIndex,
                false,
                iconIndex,
                0x17);
    }

    private int resolveHostSelectedSlotIconIndex(SaveSlotSummary summary) {
        if (summary == null || !summary.hasRecoverablePayload()) {
            return -1;
        }
        DataSelectDestination clearDestination = Boolean.TRUE.equals(summary.payload().get("clear"))
                ? sessionController.currentClearRestartDestination()
                : null;
        return hostProfile.resolveSelectedSlotIconIndex(summary.payload(), clearDestination);
    }

    private S3kSaveScreenObjectState.SlotVisualState buildSlotVisualState(int slotIndex,
                                                                          SaveSlotSummary summary,
                                                                          boolean selected) {
        S3kSaveScreenObjectState.SlotVisualKind kind = classifySlot(summary);
        SelectedTeam team = resolveTeamForSlot(slotIndex, summary);
        int objectMappingFrame = resolveSlotObjectMappingFrame(team);
        var customObjectFrame = resolveCustomPortraitFrame(team);
        int sub2MappingFrame = resolveSlotSub2MappingFrame(kind, selected);
        S3kSaveScreenObjectState.SlotLabelKind labelKind = resolveSlotLabelKind(kind, summary, selected);
        int zoneDisplayNumber = resolveZoneDisplayNumber(summary, kind, selected, labelKind);
        int headerStyleIndex = resolveHeaderStyleIndex(summary, selected);
        int lives = resolveSlotStat(summary, "lives");
        int continuesCount = resolveSlotStat(summary, "continues");
        HostSlotPreview preview = resolveHostSlotPreview(summary, kind);
        return new S3kSaveScreenObjectState.SlotVisualState(
                slotIndex,
                kind,
                objectMappingFrame,
                customObjectFrame,
                sub2MappingFrame,
                labelKind,
                zoneDisplayNumber,
                headerStyleIndex,
                lives,
                continuesCount,
                getCachedEmeraldFrames(slotIndex, summary),
                preview);
    }

    private List<Integer> getCachedEmeraldFrames(int slotIndex, SaveSlotSummary summary) {
        List<SaveSlotSummary> currentSummaries = sessionController.slotSummaries();
        if (cachedEmeraldFrames == null || cachedEmeraldSummaries != currentSummaries) {
            cachedEmeraldSummaries = currentSummaries;
            cachedEmeraldFrames = new ArrayList<>(Collections.nCopies(hostProfile.slotCount(), null));
        }
        List<Integer> cached = slotIndex < cachedEmeraldFrames.size() ? cachedEmeraldFrames.get(slotIndex) : null;
        if (cached == null) {
            cached = resolveEmeraldMappingFrames(summary);
            if (slotIndex < cachedEmeraldFrames.size()) {
                cachedEmeraldFrames.set(slotIndex, cached);
            }
        }
        return cached;
    }

    private HostSlotPreview resolveHostSlotPreview(SaveSlotSummary summary,
                                                    S3kSaveScreenObjectState.SlotVisualKind kind) {
        if (kind == S3kSaveScreenObjectState.SlotVisualKind.EMPTY) {
            return null;
        }
        if (summary == null || summary.payload() == null) {
            return null;
        }
        return hostProfile.resolveSlotPreview(summary.payload());
    }

    private SelectedTeam resolveTeamForSlot(int slotIndex, SaveSlotSummary summary) {
        if (summary == null || summary.state() == SaveSlotState.EMPTY) {
            return sessionController.teamForRow(slotIndex + 1);
        }
        return teamFromPayload(summary.payload());
    }

    private int resolveSlotObjectMappingFrame(SelectedTeam team) {
        if (isCustomPortraitTeam(team)) {
            return resolveTeamMappingFrame(new SelectedTeam("sonic", List.of()));
        }
        return resolveTeamMappingFrame(team);
    }

    private int resolveSlotSub2MappingFrame(S3kSaveScreenObjectState.SlotVisualKind kind, boolean selected) {
        if (!selected || menuModel().isDeleteMode()) {
            return -1;
        }
        boolean blinkVisible = (frameCounter & 0x10) != 0;
        return switch (kind) {
            case EMPTY -> blinkVisible ? 0xF : -1;
            case CLEAR -> blinkVisible ? 0x1A : -1;
            case OCCUPIED -> -1;
        };
    }

    private List<Integer> resolveEmeraldMappingFrames(SaveSlotSummary summary) {
        if (summary == null || !summary.hasRecoverablePayload()) {
            return List.of();
        }
        Map<String, Object> payload = summary.payload();
        List<Integer> chaosEmeralds = S3kSaveProgressions.chaosEmeraldsForPayload(payload);
        if (chaosEmeralds.isEmpty()) {
            return List.of();
        }
        List<Integer> superEmeralds = S3kSaveProgressions.superEmeraldsForPayload(payload);
        List<Integer> frames = new ArrayList<>(chaosEmeralds.size());
        String hostGameCode = hostProfile.gameCode();
        for (int emeraldIndex : chaosEmeralds) {
            boolean upgraded = superEmeralds.contains(emeraldIndex);
            if (upgraded) {
                frames.add(0x1C + emeraldIndex);
                continue;
            }
            if ("s1".equals(hostGameCode) && emeraldIndex >= 0 && emeraldIndex < S1_HOST_EMERALD_FRAMES.length) {
                frames.add(S1_HOST_EMERALD_FRAMES[emeraldIndex]);
                continue;
            }
            if ("s2".equals(hostGameCode) && emeraldIndex >= 0 && emeraldIndex < S2_HOST_EMERALD_FRAMES.length) {
                frames.add(S2_HOST_EMERALD_FRAMES[emeraldIndex]);
                continue;
            }
            frames.add(0x10 + emeraldIndex);
        }
        return List.copyOf(frames);
    }

    private S3kSaveScreenObjectState.SlotLabelKind resolveSlotLabelKind(S3kSaveScreenObjectState.SlotVisualKind kind,
                                                                        SaveSlotSummary summary,
                                                                        boolean selected) {
        return switch (kind) {
            case EMPTY -> S3kSaveScreenObjectState.SlotLabelKind.BLANK;
            case OCCUPIED -> S3kSaveScreenObjectState.SlotLabelKind.ZONE;
            case CLEAR -> S3kSaveScreenObjectState.SlotLabelKind.CLEAR;
        };
    }

    private int resolveZoneDisplayNumber(SaveSlotSummary summary,
                                         S3kSaveScreenObjectState.SlotVisualKind kind,
                                         boolean selected,
                                         S3kSaveScreenObjectState.SlotLabelKind labelKind) {
        if (summary == null || !summary.hasRecoverablePayload()) {
            return 0;
        }
        if (kind == S3kSaveScreenObjectState.SlotVisualKind.CLEAR) {
            return S3kSaveProgressions.terminalClearMarkerIndex(summary.payload()) + 1;
        }
        return Math.max(0, S3kSaveProgressions.progressCodeForPayload(summary.payload()));
    }

    private int resolveHeaderStyleIndex(SaveSlotSummary summary, boolean selected) {
        if (summary == null || summary.state() == SaveSlotState.EMPTY) {
            return 0;
        }
        SelectedTeam team;
        team = teamFromPayload(summary.payload());
        return team == null ? 0 : headerStyleIndexFor(team);
    }

    private int resolveFinishPaletteIndex(Map<String, Object> payload) {
        int clearState = Math.max(0, S3kSaveProgressions.clearStateForPayload(payload));
        return Math.max(0, Math.min(2, clearState == 0 ? 0 : clearState - 1));
    }

    private int resolveFinishCardMappingFrame(Map<String, Object> payload) {
        SelectedTeam team = teamFromPayload(payload);
        if (headerStyleIndexFor(team) > 1) {
            return 0x23;
        }
        return switch (Math.max(0, S3kSaveProgressions.clearStateForPayload(payload))) {
            case 1 -> 0x18;
            case 2 -> 0x19;
            case 3 -> 0x1B;
            default -> 0x17;
        };
    }

    private int resolveTeamMappingFrame(SelectedTeam team) {
        return 4 + playerOptionIndexFor(team);
    }

    private boolean isCustomPortraitTeam(SelectedTeam team) {
        return portraitComposer().requiresCustomComposition(team);
    }

    private S3kCustomTeamPortraitComposer portraitComposer() {
        if (cachedPortraitComposer == null) {
            cachedPortraitComposer = new S3kCustomTeamPortraitComposer(assets.getSaveScreenMappings());
        }
        return cachedPortraitComposer;
    }

    private SpriteMappingFrame resolveCustomPortraitFrame(SelectedTeam team) {
        if (!isCustomPortraitTeam(team)) {
            return null;
        }
        return cachedPortraitFrames.computeIfAbsent(team, t -> portraitComposer().compose(t));
    }

    private int headerStyleIndexFor(SelectedTeam team) {
        if (team == null) {
            return 0;
        }
        return switch (playerOptionIndexFor(team)) {
            case 2 -> 2;
            case 3 -> 3;
            default -> 1;
        };
    }

    private int playerOptionIndexFor(SelectedTeam team) {
        if (team == null) {
            return 1;
        }
        if ("knuckles".equalsIgnoreCase(team.mainCharacter())) {
            return 3;
        }
        if ("tails".equalsIgnoreCase(team.mainCharacter()) && team.sidekicks().isEmpty()) {
            return 2;
        }
        if ("sonic".equalsIgnoreCase(team.mainCharacter())
                && team.sidekicks().size() == 1
                && "tails".equalsIgnoreCase(team.sidekicks().getFirst())) {
            return 0;
        }
        return 1;
    }

    private int resolveSlotStat(SaveSlotSummary summary, String key) {
        if (summary == null || !summary.hasRecoverablePayload()) {
            return 0;
        }
        return readInt(summary.payload(), key, 0);
    }

    private static SelectedTeam teamFromPayload(Map<String, Object> payload) {
        String main = String.valueOf(payload.getOrDefault("mainCharacter", "sonic"));
        Object sidekicksRaw = payload.get("sidekicks");
        List<String> sidekicks = sidekicksRaw instanceof List<?>
                ? ((List<?>) sidekicksRaw).stream().map(String::valueOf).toList()
                : List.of();
        return new SelectedTeam(main, sidekicks);
    }

    private static int readInt(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    static S3kDataSelectAssetSource createDefaultAssets() {
        return new LoaderBackedAssets(S3kDataSelectPresentation::resolvePrimaryRom);
    }

    static S3kDataSelectAssetSource createDonorAssets(DataSelectHostProfile hostProfile) {
        return new LoaderBackedAssets(() -> GameServices.rom().getSecondaryRom("s3k"),
                hostProfile != null ? hostProfile.gameCode() : null);
    }

    private static Rom resolvePrimaryRom() throws IOException {
        return GameServices.rom() != null ? GameServices.rom().getRom() : null;
    }

    static void playMusicSafely(int musicId) {
        try {
            if (GameServices.audio() != null) {
                GameServices.audio().playMusic(musicId);
            }
        } catch (Exception ignored) {
        }
    }

    static void playMovementSafely(int sfxId) {
        try {
            if (GameServices.audio() != null) {
                GameServices.audio().playSfx(sfxId);
            }
        } catch (Exception ignored) {
        }
    }

    @FunctionalInterface
    interface RomSource {
        Rom resolve() throws IOException;
    }

    private static final class LoaderBackedAssets implements S3kDataSelectAssetSource {
        private final RomSource romSource;
        private final String hostGameCode;
        private S3kDataSelectDataLoader loader;
        private Map<Integer, S1SelectedSlotPreviewLoader.LoadedPreview> s1SelectedIconPreviews = Map.of();
        private Map<Integer, S1SelectedSlotPreviewLoader.LoadedPreview> s2SelectedIconPreviews = Map.of();
        private boolean s1HostPreviewMode;
        private boolean s2HostPreviewMode;
        private byte[] hostEmeraldPaletteBytes = new byte[0];
        private byte[] customEmeraldPaletteBytes = new byte[0];
        private HostEmeraldLayoutProfile hostEmeraldLayoutProfile = HostEmeraldLayoutProfile.defaultSeven();
        private List<SpriteMappingFrame> saveScreenMappings = List.of();
        private boolean loaded;

        LoaderBackedAssets(RomSource romSource) {
            this(romSource, null);
        }

        LoaderBackedAssets(RomSource romSource, String hostGameCode) {
            this.romSource = romSource;
            this.hostGameCode = hostGameCode;
        }

        @Override
        public void loadData() throws IOException {
            if (loaded) {
                return;
            }
            loader = requireLoader();
            loader.loadData();
            saveScreenMappings = loader.getSaveScreenMappings();
            loadHostPreviewAssets();
            loaded = true;
        }

        @Override
        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public int getMusicId() {
            return loader != null ? loader.getMusicId() : com.openggf.game.sonic3k.audio.Sonic3kMusic.DATA_SELECT.id;
        }

        @Override
        public int[] getLayoutWords() {
            return loader != null ? loader.getLayoutWords() : new int[0];
        }

        @Override
        public int[] getPlaneALayoutWords() {
            return loader != null ? loader.getPlaneALayoutWords() : new int[0];
        }

        @Override
        public int[] getNewLayoutWords() {
            return loader != null ? loader.getNewLayoutWords() : new int[0];
        }

        @Override
        public int[][] getStaticLayouts() {
            return loader != null ? loader.getStaticLayouts() : new int[0][];
        }

        @Override
        public int[] getMenuBackgroundLayoutWords() {
            return loader != null ? loader.getMenuBackgroundLayoutWords() : new int[0];
        }

        @Override
        public com.openggf.level.Pattern[] getMenuBackgroundPatterns() {
            return loader != null ? loader.getMenuBackgroundPatterns() : new com.openggf.level.Pattern[0];
        }

        @Override
        public com.openggf.level.Pattern[] getMiscPatterns() {
            return loader != null ? loader.getMiscPatterns() : new com.openggf.level.Pattern[0];
        }

        @Override
        public com.openggf.level.Pattern[] getExtraPatterns() {
            return loader != null ? loader.getExtraPatterns() : new com.openggf.level.Pattern[0];
        }

        @Override
        public com.openggf.level.Pattern[] getTextPatterns() {
            return loader != null ? loader.getTextPatterns() : new com.openggf.level.Pattern[0];
        }

        @Override
        public com.openggf.level.Pattern[] getSlotIconPatterns(int iconIndex) {
            if (s2HostPreviewMode) {
                S1SelectedSlotPreviewLoader.LoadedPreview preview = s2SelectedIconPreviews.get(iconIndex);
                if (preview != null) {
                    return preview.patterns();
                }
            }
            if (s1HostPreviewMode) {
                S1SelectedSlotPreviewLoader.LoadedPreview preview = s1SelectedIconPreviews.get(iconIndex);
                if (preview != null) {
                    return preview.patterns();
                }
            }
            return loader != null ? loader.getSlotIconPatterns(iconIndex) : new com.openggf.level.Pattern[0];
        }

        @Override
        public com.openggf.level.Palette getSelectedSlotIconPalette(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            if (selectedSlotIcon == null) {
                return null;
            }
            if (s2HostPreviewMode) {
                S1SelectedSlotPreviewLoader.LoadedPreview preview = s2SelectedIconPreviews.get(selectedSlotIcon.iconIndex());
                if (preview != null) {
                    return preview.palette();
                }
                return null;
            }
            if (s1HostPreviewMode) {
                S1SelectedSlotPreviewLoader.LoadedPreview preview = s1SelectedIconPreviews.get(selectedSlotIcon.iconIndex());
                if (preview != null) {
                    return preview.palette();
                }
            }
            return null;
        }

        @Override
        public com.openggf.level.render.SpriteMappingFrame getSelectedSlotIconFrame(
                S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            if (selectedSlotIcon == null) {
                return null;
            }
            int iconIndex = selectedSlotIcon.iconIndex();
            if (s2HostPreviewMode) {
                S1SelectedSlotPreviewLoader.LoadedPreview preview = s2SelectedIconPreviews.get(iconIndex);
                if (preview != null) {
                    return preview.frame();
                }
                return null;
            }
            if (s1HostPreviewMode) {
                S1SelectedSlotPreviewLoader.LoadedPreview preview = s1SelectedIconPreviews.get(iconIndex);
                if (preview != null) {
                    return preview.frame();
                }
            }
            return null;
        }

        @Override
        public boolean useScaledSelectedSlotIconFrame(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
            if (selectedSlotIcon == null) {
                return false;
            }
            if (s2HostPreviewMode) {
                return s2SelectedIconPreviews.containsKey(selectedSlotIcon.iconIndex());
            }
            if (s1HostPreviewMode) {
                return s1SelectedIconPreviews.containsKey(selectedSlotIcon.iconIndex());
            }
            return false;
        }

        @Override
        public com.openggf.level.Pattern[] getSkZonePatterns() {
            return loader != null ? loader.getSkZonePatterns() : new com.openggf.level.Pattern[0];
        }

        @Override
        public com.openggf.level.Pattern[] getPortraitPatterns() {
            return loader != null ? loader.getPortraitPatterns() : new com.openggf.level.Pattern[0];
        }

        @Override
        public com.openggf.level.Pattern[] getS3ZonePatterns() {
            return loader != null ? loader.getS3ZonePatterns() : new com.openggf.level.Pattern[0];
        }

        @Override
        public byte[] getMenuBackgroundPaletteBytes() {
            return loader != null ? loader.getMenuBackgroundPaletteBytes() : new byte[0];
        }

        @Override
        public byte[] getCharacterPaletteBytes() {
            return loader != null ? loader.getCharacterPaletteBytes() : new byte[0];
        }

        @Override
        public byte[] getEmeraldPaletteBytes() {
            if (hostEmeraldPaletteBytes.length > 0) {
                return hostEmeraldPaletteBytes;
            }
            return loader != null ? loader.getEmeraldPaletteBytes() : new byte[0];
        }

        @Override
        public byte[][] getFinishCardPalettes() {
            return loader != null ? loader.getFinishCardPalettes() : new byte[0][];
        }

        @Override
        public byte[][] getZoneCardPalettes() {
            return loader != null ? loader.getZoneCardPalettes() : new byte[0][];
        }

        @Override
        public byte[] getS3ZoneCard8PaletteBytes() {
            return loader != null ? loader.getS3ZoneCard8PaletteBytes() : new byte[0];
        }

        @Override
        public List<com.openggf.level.render.SpriteMappingFrame> getSaveScreenMappings() {
            if (!saveScreenMappings.isEmpty()) {
                return saveScreenMappings;
            }
            return loader != null ? loader.getSaveScreenMappings() : List.of();
        }

        @Override
        public S3kSaveScreenLayoutObjects getSaveScreenLayoutObjects() {
            return loader != null ? loader.getSaveScreenLayoutObjects() : S3kSaveScreenLayoutObjects.original();
        }

        @Override
        public HostEmeraldLayoutProfile getHostEmeraldLayoutProfile() {
            return hostEmeraldLayoutProfile;
        }

        @Override
        public byte[] getCustomEmeraldPaletteBytes() {
            return customEmeraldPaletteBytes;
        }

        /**
         * Loads host-owned preview data on top of the donated S3K frontend.
         *
         * <p>Selected-slot zone imagery may come from the host game, but emerald rendering
         * stays on the native S3K save-card geometry. Host emerald colors are therefore
         * adapted into the S3K save-card palette contract rather than drawn through raw host
         * emerald tiles on the save card.</p>
         */
        private void loadHostPreviewAssets() {
            Rom hostRom = null;
            if (GameServices.rom() != null) {
                try {
                    hostRom = GameServices.rom().getRom();
                } catch (IOException ignored) {
                    hostRom = null;
                }
            }
            HostEmeraldPresentation.Result emeraldPresentation =
                    HostEmeraldPresentation.forHost(hostGameCode, hostRom,
                            loader != null ? loader.getEmeraldPaletteBytes() : null);
            hostEmeraldPaletteBytes = emeraldPresentation.paletteBytes();
            customEmeraldPaletteBytes = emeraldPresentation.customPurplePaletteBytes();
            hostEmeraldLayoutProfile = emeraldPresentation.layout();
            if ("s2".equals(hostGameCode) && customEmeraldPaletteBytes.length > 0) {
                saveScreenMappings = buildS2PurpleEmeraldMappings(saveScreenMappings);
            }
            if ("s1".equals(hostGameCode)) {
                S1DataSelectImageCacheManager cacheManager = GameServices.module()
                        .getGameService(S1DataSelectImageCacheManager.class);
                if (cacheManager == null) {
                    return;
                }
                cacheManager.awaitGenerationIfRunning();
                s1SelectedIconPreviews = new S1SelectedSlotPreviewLoader()
                        .loadAll(cacheManager.loadCachedPreviews());
                s1HostPreviewMode = !s1SelectedIconPreviews.isEmpty();
                return;
            }
            if (!"s2".equals(hostGameCode)) {
                return;
            }
            S2DataSelectImageCacheManager cacheManager = GameServices.module()
                    .getGameService(S2DataSelectImageCacheManager.class);
            if (cacheManager == null) {
                return;
            }
            cacheManager.awaitGenerationIfRunning();
            s2SelectedIconPreviews = new S2SelectedSlotPreviewLoader()
                    .loadAll(cacheManager.loadCachedPreviews());
            s2HostPreviewMode = !s2SelectedIconPreviews.isEmpty();
        }

        private List<SpriteMappingFrame> buildS2PurpleEmeraldMappings(List<SpriteMappingFrame> baseMappings) {
            if (baseMappings == null || baseMappings.size() <= 0x16) {
                return baseMappings != null ? baseMappings : List.of();
            }
            List<SpriteMappingFrame> mappings = new ArrayList<>(baseMappings);
            SpriteMappingFrame purpleFrame = mappings.get(0x16);
            if (purpleFrame == null) {
                return List.copyOf(mappings);
            }
            List<SpriteMappingPiece> reboundPieces = new ArrayList<>(purpleFrame.pieces().size());
            for (SpriteMappingPiece piece : purpleFrame.pieces()) {
                reboundPieces.add(new SpriteMappingPiece(
                        piece.xOffset(),
                        piece.yOffset(),
                        piece.widthTiles(),
                        piece.heightTiles(),
                        piece.tileIndex(),
                        piece.hFlip(),
                        piece.vFlip(),
                        3,
                        piece.priority()));
            }
            mappings.set(0x16, new SpriteMappingFrame(reboundPieces));
            return List.copyOf(mappings);
        }

        private S3kDataSelectDataLoader requireLoader() {
            Rom rom;
            try {
                rom = romSource.resolve();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to access S3K ROM", e);
            }
            if (rom == null) {
                throw new IllegalStateException("S3K data select requires an active ROM");
            }
            try {
                return new S3kDataSelectDataLoader(RomByteReader.fromRom(rom));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to build S3K ROM reader", e);
            }
        }
    }

    private DataSelectMenuModel menuModel() {
        return sessionController.menuModel();
    }
}
