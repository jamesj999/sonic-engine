package com.openggf.game.sonic3k.titlecard;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.sonic3k.events.S3kTransitionWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.titlecard.TitleCardMappings;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.graphics.TitleCardSpriteRenderer;
import com.openggf.level.Pattern;
import com.openggf.level.objects.FreshLevelTitleOwnerReplacement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Manages the Sonic 3&K title card display.
 *
 * <p>Key differences from S2 title card:
 * <ul>
 *   <li>Art uses KosinskiM compression (not Nemesis)</li>
 *   <li>4 art blocks loaded to VRAM offsets $500, $510, $53D, $54D</li>
 *   <li>Staggered exit via priority values (not cascading states)</li>
 *   <li>Supports in-level mode (no background) for AIZ intro</li>
 * </ul>
 *
 * <p>Elements from ObjArray_TtlCard (disasm):
 * <table>
 *   <tr><th>Element</th><th>Start</th><th>Target</th><th>Direction</th><th>Exit Priority</th></tr>
 *   <tr><td>Red Banner</td><td>(96,-112)</td><td>(96,64)</td><td>Vertical</td><td>1</td></tr>
 *   <tr><td>Zone Name</td><td>(480,96)</td><td>(160,96)</td><td>Horizontal</td><td>3</td></tr>
 *   <tr><td>"ZONE"</td><td>(636,128)</td><td>(252,128)</td><td>Horizontal</td><td>5</td></tr>
 *   <tr><td>Act Number</td><td>(708,160)</td><td>(260,160)</td><td>Horizontal</td><td>7</td></tr>
 * </table>
 */
public class Sonic3kTitleCardManager
        implements TitleCardProvider,
        RewindSnapshottable<Sonic3kTitleCardManager.Snapshot> {
    private static final Logger LOG = Logger.getLogger(Sonic3kTitleCardManager.class.getName());
    public static final String REWIND_KEY = "s3k-title-card";

    // Animation speeds (pixels per frame, matching disasm $10 / $20)
    private static final int SLIDE_SPEED_IN = 16;
    private static final int SLIDE_SPEED_OUT = 32;

    // Display hold duration (frames). ROM: Level routine overwrites $2E to $16 (22)
    // at lines 7897-7900, synchronizing the hold with Palette_fade_timer.
    private static final int DISPLAY_HOLD_FRAMES = 90;
    private static final int FRESH_LEVEL_TRANSITION_HOLD_FRAMES = 22;

    // ROM palette fade duration: 22 frames (sonic3k.asm line 7877, Palette_fade_timer = $16).
    // In the ROM, the title card is already visible for many frames during level loading
    // before the Level routine overwrites the hold timer to 22. Our engine loads levels
    // synchronously, so we use the full 90-frame hold but run the fade in the last 22.
    private static final int BONUS_BG_FADE_FRAMES = 22;

    /** Native game width (320-pixel frame everything is authored for). */
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    /**
     * Returns the configured viewport width in game pixels.
     * At native 320 equals SCREEN_WIDTH exactly (xOffset == 0 — byte-identical).
     */
    private int viewportWidth() {
        try {
            int w = GameServices.graphics().getProjectionWidth();
            return w > 0 ? w : SCREEN_WIDTH;
        } catch (Exception ignored) {
            return SCREEN_WIDTH;
        }
    }

    /**
     * Horizontal offset to centre the 320-wide title-card composition in the
     * configured viewport.  Zero at native 320 — byte-identical.
     */
    private int xOffset() {
        return (viewportWidth() - SCREEN_WIDTH) / 2;
    }

    // Pattern base ID for GPU caching (high to avoid conflicts)
    private static final int PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base();

    // VRAM base tile for title card art
    private static final int VRAM_BASE = Sonic3kConstants.VRAM_TITLE_CARD_BASE;

    // Max VRAM extent (zone art can extend to ~$57D)
    private static final int VRAM_ARRAY_SIZE = 0x100;  // 256 tiles covers $500-$5FF

    // ---- Element definitions ----
    // Each element: frameIndex, startX, startY, targetX, targetY, isVertical, exitPriority

    private static final int ELEM_BANNER = 0;
    private static final int ELEM_ZONE_NAME = 1;
    private static final int ELEM_ZONE_TEXT = 2;
    private static final int ELEM_ACT_NUM = 3;
    private static final int ELEMENT_COUNT = 4;

    // Element positions (screen coordinates, derived from disasm ObjArray_TtlCard)
    private static final int[] START_X = {96, 480, 636, 708};
    private static final int[] START_Y = {-112, 96, 128, 160};
    private static final int[] TARGET_X = {96, 160, 252, 260};
    private static final int[] TARGET_Y = {64, 96, 128, 160};
    private static final boolean[] IS_VERTICAL = {true, false, false, false};
    private static final int[] EXIT_PRIORITY = {1, 3, 5, 7};
    // Render_Sprites bounds consumed by the following object pass. The red
    // banner overwrites height_pixels with $70; the remaining values are the
    // width_pixels bytes in ObjArray_TtlCard.
    private static final int BANNER_RENDER_HEIGHT = 0x70;
    private static final int PRELOADED_ACT_CAMERA_RELEASE_DISPATCHES = 11;
    private static final int[] ELEMENT_RENDER_WIDTH = {0, 0x80, 0x24, 0x1C};

    // ---- State ----
    private Sonic3kTitleCardState state = Sonic3kTitleCardState.COMPLETE;
    private int stateTimer;
    private int phaseCounter;  // Exit phase counter for staggered exit
    private boolean exitChildrenGone;
    private boolean inLevelMode;  // No black background, control released immediately
    private boolean resetLevelGamestateOnInLevelDisplay;
    private int resetLevelGamestateCountdown;
    private boolean heldLevelCounterDispatchOwned;
    private boolean retainedResultsHeldLevelCounterOwned;
    private boolean inLevelPlayerControlLockOwned;
    private int inLevelExitDelayFrames;
    private boolean retainedControlPollFollowsTitleCompletion;
    // Obj_TitleCardWait2 observes the drained child counter once, then reaches
    // LoadEnemyArt on its following owner dispatch. This poll is independent
    // of any retained camera-release tail that keeps the title owner alive.
    private boolean inLevelArtAdmissionPollObserved;
    private boolean releasePreloadedActCameraOnComplete;
    private boolean preloadedActCompletionPrepared;
    private boolean bonusMode;  // 2-element "BONUS STAGE" layout
    private float bonusFadeProgress; // 0.0→1.0 over BONUS_DISPLAY_HOLD_FRAMES during DISPLAY

    // Bonus mode element definitions (ObjArray_TtlCardBonus, sonic3k.asm line 62482)
    // VDP coords converted to screen coords (subtract 128)
    private static final int BONUS_ELEMENT_COUNT = 2;
    private static final int BONUS_ELEM_BONUS = 0;
    private static final int BONUS_ELEM_STAGE = 1;
    private static final int[] BONUS_START_X = {264, 360};
    private static final int[] BONUS_TARGET_X = {72, 168};
    private static final int BONUS_Y = 104;
    private static final int[] BONUS_EXIT_PRIORITY = {1, 1};

    private int currentZone;
    private int currentAct;

    // Per-element animation state
    private final int[] elemX = new int[ELEMENT_COUNT];
    private final int[] elemY = new int[ELEMENT_COUNT];
    private final int[] elemFrame = new int[ELEMENT_COUNT];
    private final boolean[] elemAtTarget = new boolean[ELEMENT_COUNT];
    private final boolean[] elemExiting = new boolean[ELEMENT_COUNT];
    private final boolean[] elemOutsideViewport = new boolean[ELEMENT_COUNT];
    private final boolean[] elemExited = new boolean[ELEMENT_COUNT];
    private boolean actNumberVisible;  // False for single-act zones

    // Art data
    private Pattern[] combinedPatterns;
    private boolean artLoaded;
    private boolean artCached;
    private boolean omittedFreshLevelOwnerActive;
    private final List<HardwareWorkHandle> omittedOwnerHandles = new ArrayList<>();
    private S3kKosModuleQueue omittedOwnerQueue;
    private int lastLoadedZone = -1;
    private int lastLoadedAct = -1;
    private S3kKosModuleQueue artQueue;
    private final List<HardwareWorkHandle> artHandles = new ArrayList<>();
    private final List<Integer> artDestinations = new ArrayList<>();
    private boolean artLoading;
    private long runtimeArtAdmissionLeaseId = -1;
    private boolean runtimeArtAdmissionConsumed;
    private int freshLevelRuntimeArtHandoffLevelIndex = -1;
    private int displayHoldFrames = DISPLAY_HOLD_FRAMES;
    private boolean freshLevelTransitionMode;
    private boolean freshLevelTitleOwnerReplacedAtAssembly;

    /**
     * Immutable live-title snapshot. Array accessors clone their payload so a
     * captured rewind frame cannot be mutated by the continuing live manager.
     */
    public record Snapshot(
            Sonic3kTitleCardState state,
            int stateTimer,
            int phaseCounter,
            boolean exitChildrenGone,
            boolean inLevelMode,
            int displayHoldFrames,
            boolean freshLevelTransitionMode,
            boolean freshLevelTitleOwnerReplacedAtAssembly,
            boolean resetLevelGamestateOnInLevelDisplay,
            int resetLevelGamestateCountdown,
            boolean heldLevelCounterDispatchOwned,
            boolean retainedResultsHeldLevelCounterOwned,
            boolean inLevelPlayerControlLockOwned,
            int inLevelExitDelayFrames,
            boolean retainedControlPollFollowsTitleCompletion,
            boolean inLevelArtAdmissionPollObserved,
            boolean releasePreloadedActCameraOnComplete,
            boolean preloadedActCompletionPrepared,
            boolean bonusMode,
            float bonusFadeProgress,
            int currentZone,
            int currentAct,
            int[] elemX,
            int[] elemY,
            int[] elemFrame,
            boolean[] elemAtTarget,
            boolean[] elemExiting,
            boolean[] elemOutsideViewport,
            boolean[] elemExited,
            boolean actNumberVisible,
            Pattern[] combinedPatterns,
            boolean artLoaded,
            boolean artCached,
            int lastLoadedZone,
            int lastLoadedAct,
            List<HardwareWorkHandle> artHandles,
            List<Integer> artDestinations,
            boolean artLoading,
            long runtimeArtAdmissionLeaseId,
            boolean runtimeArtAdmissionConsumed,
            int freshLevelRuntimeArtHandoffLevelIndex) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            elemX = elemX.clone();
            elemY = elemY.clone();
            elemFrame = elemFrame.clone();
            elemAtTarget = elemAtTarget.clone();
            elemExiting = elemExiting.clone();
            elemOutsideViewport = elemOutsideViewport.clone();
            elemExited = elemExited.clone();
            combinedPatterns = combinedPatterns == null
                    ? null : combinedPatterns.clone();
            artHandles = List.copyOf(artHandles);
            artDestinations = List.copyOf(artDestinations);
        }

        @Override public int[] elemX() { return elemX.clone(); }
        @Override public int[] elemY() { return elemY.clone(); }
        @Override public int[] elemFrame() { return elemFrame.clone(); }
        @Override public boolean[] elemAtTarget() { return elemAtTarget.clone(); }
        @Override public boolean[] elemExiting() { return elemExiting.clone(); }
        @Override public boolean[] elemOutsideViewport() { return elemOutsideViewport.clone(); }
        @Override public boolean[] elemExited() { return elemExited.clone(); }
        @Override public Pattern[] combinedPatterns() {
            return combinedPatterns == null ? null : combinedPatterns.clone();
        }
    }

    public Sonic3kTitleCardManager() {}

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public Snapshot capture() {
        return new Snapshot(
                state, stateTimer, phaseCounter, exitChildrenGone, inLevelMode,
                displayHoldFrames,
                freshLevelTransitionMode,
                freshLevelTitleOwnerReplacedAtAssembly,
                resetLevelGamestateOnInLevelDisplay, resetLevelGamestateCountdown,
                heldLevelCounterDispatchOwned, retainedResultsHeldLevelCounterOwned,
                inLevelPlayerControlLockOwned, inLevelExitDelayFrames,
                retainedControlPollFollowsTitleCompletion,
                inLevelArtAdmissionPollObserved,
                releasePreloadedActCameraOnComplete, preloadedActCompletionPrepared,
                bonusMode, bonusFadeProgress, currentZone, currentAct,
                elemX, elemY, elemFrame, elemAtTarget, elemExiting,
                elemOutsideViewport, elemExited, actNumberVisible,
                combinedPatterns, artLoaded, artCached, lastLoadedZone,
                lastLoadedAct, artHandles, artDestinations, artLoading,
                runtimeArtAdmissionLeaseId, runtimeArtAdmissionConsumed,
                freshLevelRuntimeArtHandoffLevelIndex);
    }

    @Override
    public void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireElementArrayLength(snapshot.elemX().length);
        requireElementArrayLength(snapshot.elemY().length);
        requireElementArrayLength(snapshot.elemFrame().length);
        requireElementArrayLength(snapshot.elemAtTarget().length);
        requireElementArrayLength(snapshot.elemExiting().length);
        requireElementArrayLength(snapshot.elemOutsideViewport().length);
        requireElementArrayLength(snapshot.elemExited().length);
        if (snapshot.artHandles().size() != snapshot.artDestinations().size()) {
            throw new IllegalStateException(
                    "title-card rewind handle/destination count mismatch");
        }

        state = snapshot.state();
        stateTimer = snapshot.stateTimer();
        phaseCounter = snapshot.phaseCounter();
        exitChildrenGone = snapshot.exitChildrenGone();
        inLevelMode = snapshot.inLevelMode();
        displayHoldFrames = snapshot.displayHoldFrames();
        freshLevelTransitionMode = snapshot.freshLevelTransitionMode();
        freshLevelTitleOwnerReplacedAtAssembly =
                snapshot.freshLevelTitleOwnerReplacedAtAssembly();
        resetLevelGamestateOnInLevelDisplay =
                snapshot.resetLevelGamestateOnInLevelDisplay();
        resetLevelGamestateCountdown = snapshot.resetLevelGamestateCountdown();
        heldLevelCounterDispatchOwned = snapshot.heldLevelCounterDispatchOwned();
        retainedResultsHeldLevelCounterOwned =
                snapshot.retainedResultsHeldLevelCounterOwned();
        inLevelPlayerControlLockOwned = snapshot.inLevelPlayerControlLockOwned();
        inLevelExitDelayFrames = snapshot.inLevelExitDelayFrames();
        retainedControlPollFollowsTitleCompletion =
                snapshot.retainedControlPollFollowsTitleCompletion();
        inLevelArtAdmissionPollObserved = snapshot.inLevelArtAdmissionPollObserved();
        releasePreloadedActCameraOnComplete =
                snapshot.releasePreloadedActCameraOnComplete();
        preloadedActCompletionPrepared = snapshot.preloadedActCompletionPrepared();
        bonusMode = snapshot.bonusMode();
        bonusFadeProgress = snapshot.bonusFadeProgress();
        currentZone = snapshot.currentZone();
        currentAct = snapshot.currentAct();
        copy(snapshot.elemX(), elemX);
        copy(snapshot.elemY(), elemY);
        copy(snapshot.elemFrame(), elemFrame);
        copy(snapshot.elemAtTarget(), elemAtTarget);
        copy(snapshot.elemExiting(), elemExiting);
        copy(snapshot.elemOutsideViewport(), elemOutsideViewport);
        copy(snapshot.elemExited(), elemExited);
        actNumberVisible = snapshot.actNumberVisible();
        combinedPatterns = snapshot.combinedPatterns();
        artLoaded = snapshot.artLoaded();
        artCached = snapshot.artCached();
        lastLoadedZone = snapshot.lastLoadedZone();
        lastLoadedAct = snapshot.lastLoadedAct();
        artHandles.clear();
        artDestinations.clear();
        artLoading = snapshot.artLoading();
        runtimeArtAdmissionLeaseId = snapshot.runtimeArtAdmissionLeaseId();
        runtimeArtAdmissionConsumed = snapshot.runtimeArtAdmissionConsumed();
        freshLevelRuntimeArtHandoffLevelIndex =
                snapshot.freshLevelRuntimeArtHandoffLevelIndex();
        if (!snapshot.artHandles().isEmpty()) {
            var timing = GameServices.hardwareTiming();
            for (HardwareWorkHandle captured : snapshot.artHandles()) {
                HardwareWorkHandle rebound = timing.pendingHandle(
                                HardwareWorkKind.KOS_MODULE_QUEUE,
                                captured.ordinal())
                        .orElseThrow(() -> missingRestoredTitleJob(captured));
                if (!captured.equals(rebound)) {
                    throw new IllegalStateException(
                            "restored title job identity mismatch: expected "
                                    + captured + ", actual " + rebound);
                }
                artHandles.add(rebound);
            }
            artDestinations.addAll(snapshot.artDestinations());
            artQueue = S3kRuntimeArtCoordinator.current().moduleQueue();
        } else {
            artQueue = null;
        }
    }

    @Override
    public void resetForMissingSnapshot() {
        reset();
    }

    private static IllegalStateException missingRestoredTitleJob(
            HardwareWorkHandle captured) {
        return new IllegalStateException(
                "restored title owner cannot find " + captured.kind()
                        + " ordinal " + captured.ordinal());
    }

    private static void requireElementArrayLength(int length) {
        if (length != ELEMENT_COUNT) {
            throw new IllegalStateException(
                    "title-card rewind element count mismatch: " + length);
        }
    }

    private static void copy(int[] source, int[] destination) {
        System.arraycopy(source, 0, destination, 0, destination.length);
    }

    private static void copy(boolean[] source, boolean[] destination) {
        System.arraycopy(source, 0, destination, 0, destination.length);
    }

    // ---- TitleCardProvider interface ----

    @Override
    public void initialize(int zoneIndex, int actIndex) {
        initInternal(zoneIndex, actIndex, false, DISPLAY_HOLD_FRAMES);
    }

    @Override
    public void initializeFreshLevelTransition(int zoneIndex, int actIndex) {
        // Obj_TitleCardInit stores 90, then Level overwrites the same owner
        // with #$16 just before LevelLoop (sonic3k.asm:62187, 7897-7900).
        initInternal(zoneIndex, actIndex, false, FRESH_LEVEL_TRANSITION_HOLD_FRAMES);
        freshLevelTransitionMode = true;
        var objectManager = GameServices.level().getObjectManager();
        freshLevelTitleOwnerReplacedAtAssembly = objectManager != null
                && objectManager.getActiveObjects().stream()
                .anyMatch(FreshLevelTitleOwnerReplacement.class::isInstance);
    }

    @Override
    public void requestFreshLevelRuntimeArtHandoff(int levelIndex) {
        freshLevelRuntimeArtHandoffLevelIndex = levelIndex;
    }

    @Override
    public void completeOmittedPresentationFreshLevelRuntimeArtHandoff() {
        publishFreshLevelRuntimeArtHandoffIfNeeded();
    }

    @Override
    public void completeFreshLevelRuntimeArtHandoff() {
        if (!freshLevelTransitionMode) {
            return;
        }
        // The recording driver normally calls this after the title children
        // retire. A startup controller that replaces the carried title slot
        // reaches the same LoadEnemyArt handoff when the overwritten #$16
        // wait expires (sonic3k.asm:62249-62323, 7849-7909).
        publishFreshLevelRuntimeArtHandoffIfNeeded();
        freshLevelTransitionMode = false;
        freshLevelTitleOwnerReplacedAtAssembly = false;
    }

    /**
     * Initializes for in-level mode (no black background, control released immediately).
     * Used during AIZ intro when the level is already visible.
     */
    public void initializeInLevel(int zoneIndex, int actIndex) {
        initInternal(zoneIndex, actIndex, true, DISPLAY_HOLD_FRAMES);
    }

    /**
     * Carries the Act 1 results HUD reset into the in-level title card. The
     * native Obj_TitleCardWait reset becomes visible when the engine's title
     * card children finish sliding to their display positions.
     */
    @Override
    public void requestLevelGamestateResetAtInLevelDisplay() {
        if (inLevelMode) {
            resetLevelGamestateOnInLevelDisplay = true;
            heldLevelCounterDispatchOwned = true;
            int modulePhase = GameServices.level().getObjectManager().getVblaCounter() & 3;
            // The slotless manager reaches its predicted display point after
            // 24 updates. At module phases 1 and 2 the native children are not
            // live until the next phase-0 handoff, followed by their
            // object/render visibility pass; preserve those six additional
            // updates. Phase 3 has already crossed that handoff and needs no
            // compensation.
            boolean needsChildVisibilityCompensation = modulePhase == 1 || modulePhase == 2;
            resetLevelGamestateCountdown = 24 + (needsChildVisibilityCompensation ? 6 : 0);
            // The same child-visibility handoff reaches the later Wait2 poll
            // five updates after the slotless manager would otherwise predict
            // completion when initialization precedes phase 0.
            // At phase 2 the final child retirement is visible only after the
            // retained title owner has crossed one more Process_Sprites poll.
            // Phase 1 additionally waits for the phase-0 create/render handoff.
            inLevelExitDelayFrames = modulePhase == 1 ? 5 : modulePhase == 2 ? 1 : 0;
            retainedControlPollFollowsTitleCompletion = modulePhase == 2;
        }
    }

    /**
     * Whether the retained {@code Obj_EndSignControl} slot has already run in
     * the object pass that publishes the in-level title completion flag.
     */
    public boolean retainedControlPollFollowsTitleCompletion() {
        return retainedControlPollFollowsTitleCompletion;
    }

    @Override
    public void requestLevelGamestateResetAtInLevelDisplay(int additionalDispatches) {
        requestLevelGamestateResetAtInLevelDisplay(additionalDispatches, 0);
    }

    @Override
    public void requestLevelGamestateResetAtInLevelDisplay(
            int additionalDispatches, int phaseOneDispatchOverlap) {
        int modulePhase = GameServices.level().getObjectManager().getVblaCounter() & 3;
        requestLevelGamestateResetAtInLevelDisplay();
        if (resetLevelGamestateOnInLevelDisplay) {
            int overlap = modulePhase == 1 ? Math.max(0, phaseOneDispatchOverlap) : 0;
            resetLevelGamestateCountdown += Math.max(0, additionalDispatches - overlap);
        }
    }

    /**
     * Arms the native in-level {@code Obj_TitleCardWait} state reset after a
     * known number of title-owner dispatches. This is used when a retained
     * results SST mutates directly into {@code Obj_TitleCard}; unlike a fresh
     * level title, its queue/create phase is already the only remaining gate.
     */
    public void requestLevelGamestateResetAfterCreateDispatches(int dispatches) {
        if (inLevelMode) {
            resetLevelGamestateOnInLevelDisplay = true;
            heldLevelCounterDispatchOwned = true;
            retainedResultsHeldLevelCounterOwned = true;
            resetLevelGamestateCountdown = Math.max(1, dispatches);
        }
    }

    @Override
    public void requestInLevelPlayerControlLock() {
        if (inLevelMode) {
            inLevelPlayerControlLockOwned = true;
        }
    }

    /**
     * Models the owner {@code Level:} installs when the presentation is omitted.
     *
     * <p>{@code Obj_TitleCardInit} (docs/skdisasm/sonic3k.asm:62121-62164)
     * queues four archives — RedAct {@code $500}, Zone {@code $510}, act number
     * {@code $53D} and the zone graphic {@code $54D} — on its first dispatch,
     * before anything is drawn. {@code Level:} installs the owner at 7735 and
     * enters the locked loop regardless of what the host displays, so those
     * {@code Queue_Kos_Module} calls belong to the object's creation rather
     * than to its presentation — the same reason
     * {@link #onTitleCardPresentationSkipped} already keeps the owner's later
     * {@code Obj_TitleCardWait2} {@code LoadEnemyArt} handoff.
     *
     * <p>The caller reaches this only for a load that owns the destination's
     * fresh runtime art, which is the same ownership the level's own
     * {@code LoadLevelLoadBlock} art already carries. Nothing here establishes
     * an overlay: no element is created and no state machine is started.
     */
    @Override
    public void beginOmittedFreshLevelOwner(int zoneIndex, int actIndex) {
        if (!GameServices.rom().isRomAvailable()) {
            return;
        }
        int actArtAddr = (actIndex == 0)
                ? Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM1_ADDR
                : Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM2_ADDR;
        int artIndex = (zoneIndex == 22) ? 13 : zoneIndex;
        try {
            Rom rom = GameServices.rom().getRom();
            S3kKosModuleQueue queue =
                    S3kRuntimeArtCoordinator.current().moduleQueue();
            omittedOwnerHandles.clear();
            omittedOwnerHandles.add(queue.queue(rom,
                    Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR,
                    VRAM_BASE));
            omittedOwnerHandles.add(queue.queue(rom,
                    Sonic3kConstants.ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR,
                    Sonic3kConstants.VRAM_TITLE_CARD_ZONE_TEXT));
            omittedOwnerHandles.add(queue.queue(rom, actArtAddr,
                    Sonic3kConstants.VRAM_TITLE_CARD_ACT_NUM));
            if (artIndex >= 0
                    && artIndex < Sonic3kConstants.TITLE_CARD_ZONE_ART_ADDRS.length) {
                omittedOwnerHandles.add(queue.queue(rom,
                        Sonic3kConstants.TITLE_CARD_ZONE_ART_ADDRS[artIndex],
                        Sonic3kConstants.VRAM_TITLE_CARD_ZONE_ART));
            }
            omittedOwnerQueue = queue;
            omittedFreshLevelOwnerActive = true;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to queue omitted S3K title-card owner art", e);
        }
    }

    @Override
    public boolean ownsOmittedFreshLevelPresentation() {
        return omittedFreshLevelOwnerActive;
    }

    /**
     * One dispatch of the omitted owner: the locked loop's iteration retires
     * whatever the module queue has made ready, which is what
     * {@code Obj_TitleCardCreate}'s {@code tst.b (Kos_modules_left).w} gate
     * (docs/skdisasm/sonic3k.asm:62169-62171) waits on before the owner
     * advances. Renders nothing.
     */
    @Override
    public void updateOmittedFreshLevelOwner() {
        if (!omittedFreshLevelOwnerActive) {
            return;
        }
        for (HardwareWorkHandle handle : omittedOwnerHandles) {
            if (!omittedOwnerQueue.isReady(handle)) {
                return;
            }
        }
        try {
            for (HardwareWorkHandle handle : omittedOwnerHandles) {
                omittedOwnerQueue.claim(handle);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to claim omitted S3K title-card owner art", e);
        }
        omittedOwnerHandles.clear();
        omittedOwnerQueue = null;
        omittedFreshLevelOwnerActive = false;
        // Retiring here is this engine's form of Kos_modules_left reaching
        // zero, which is the gate Obj_TitleCardCreate holds on
        // (docs/skdisasm/sonic3k.asm:62169-62171). Once it clears the ROM
        // builds the card's pieces (:62212), Obj_TitleCardWait clears
        // objoff_48 (:62244), loc_62CC exits and Level: runs
        // LoadLevelLoadBlock (:7761) -- so the destination's terrain art is
        // queued a few dispatches later, still inside the transition window.
        // Publishing it from the destination's own frames instead put it past
        // the segment seam, where the parents could never meet the completions
        // recorded against the window and stayed pending for the rest of the
        // run, blocking every later module behind them.
        //
        // Known-incomplete: loc_62CC also holds while Nem_decomp_queue is
        // non-empty (:7747-7748), and S3K has no per-frame Nemesis drain
        // (NemesisPlcServiceQueue has only S1/S2 consumers), so the engine
        // leaves the loop earlier within the window than the ROM does. The
        // parents simply wait pending until their recorded completions arrive,
        // so the retirement schedule is unaffected; modelling that drain is
        // what would make the submission row itself accurate.
        publishFreshLevelRuntimeArtHandoffIfNeeded();
    }

    @Override
    public boolean advancesOnHeldLevelCounter() {
        return inLevelMode && heldLevelCounterDispatchOwned && isOverlayActive();
    }

    @Override
    public boolean ownsHeldLevelCounter() {
        return inLevelMode && heldLevelCounterDispatchOwned;
    }

    @Override
    public boolean ownsRetainedResultsHeldLevelCounter() {
        return inLevelMode && retainedResultsHeldLevelCounterOwned;
    }

    @Override
    public boolean projectsRetainedResultsSpriteCadence() {
        return inLevelMode
                && retainedResultsHeldLevelCounterOwned;
    }

    @Override
    public boolean ownsInLevelPlayerControlLock() {
        return inLevelPlayerControlLockOwned;
    }

    @Override
    public boolean shouldLockPlayerControlForInLevelOverlay() {
        return inLevelPlayerControlLockOwned && inLevelMode;
    }

    @Override
    public void releaseInLevelPlayerControlLockOwnership() {
        inLevelPlayerControlLockOwned = false;
    }

    public void requestPreloadedActCameraReleaseOnComplete() {
        requestPreloadedActCameraReleaseOnComplete(PRELOADED_ACT_CAMERA_RELEASE_DISPATCHES);
    }

    public void requestPreloadedActCameraReleaseOnComplete(int dispatches) {
        if (inLevelMode) {
            releasePreloadedActCameraOnComplete = true;
            // A results SST that mutates into Obj_TitleCard retains the parent
            // Wait2/child retirement entries which the slotless overlay folds
            // away. Keep Scroll_lock through those remaining dispatches.
            inLevelExitDelayFrames += Math.max(0, dispatches);
        }
    }

    @Override
    public void requestInLevelExitAdditionalDispatches(int dispatches) {
        requestInLevelExitAdditionalDispatches(dispatches, 0);
    }

    @Override
    public void requestInLevelExitAdditionalDispatches(
            int dispatches, int phaseOneDispatchOverlap) {
        if (inLevelMode) {
            int modulePhase = GameServices.level().getObjectManager().getVblaCounter() & 3;
            int overlap = modulePhase == 1 ? Math.max(0, phaseOneDispatchOverlap) : 0;
            inLevelExitDelayFrames += Math.max(0, dispatches - overlap);
        }
    }

    /**
     * Initializes for bonus stage mode — shows "BONUS STAGE" text.
     * Uses 2 horizontal elements (frames 19/20) instead of the normal 4-element layout.
     * Both elements have exit priority 1 (exit simultaneously).
     *
     * <p>ROM reference: ObjArray_TtlCardBonus (sonic3k.asm line 62482).
     */
    @Override
    public void initializeBonus() {
        this.bonusMode = true;
        this.bonusFadeProgress = 0f;
        this.inLevelMode = false;
        this.freshLevelTransitionMode = false;
        this.freshLevelTitleOwnerReplacedAtAssembly = false;
        this.displayHoldFrames = DISPLAY_HOLD_FRAMES;
        this.state = Sonic3kTitleCardState.SLIDE_IN;
        this.stateTimer = 0;
        this.phaseCounter = 0;
        this.exitChildrenGone = false;

        // Set up 2 bonus elements — reuse the first 2 slots of the 4-element arrays
        elemFrame[0] = Sonic3kTitleCardMappings.FRAME_BONUS;
        elemFrame[1] = Sonic3kTitleCardMappings.FRAME_STAGE;

        for (int i = 0; i < BONUS_ELEMENT_COUNT; i++) {
            elemX[i] = BONUS_START_X[i];
            elemY[i] = BONUS_Y;
            elemAtTarget[i] = false;
            elemExiting[i] = false;
            elemOutsideViewport[i] = false;
            elemExited[i] = false;
        }

        // Load bonus art (no zone/act needed — bonus art is always the same)
        if (!artLoaded || lastLoadedZone != -2) {
            loadBonusArt();
        }

        artCached = false;
        LOG.info("S3K bonus title card initialized");
    }

    private void initInternal(
            int zoneIndex, int actIndex, boolean inLevel, int displayHoldFrames) {
        this.currentZone = zoneIndex;
        this.currentAct = actIndex;
        this.bonusMode = false;
        this.inLevelMode = inLevel;
        this.displayHoldFrames = displayHoldFrames;
        this.resetLevelGamestateOnInLevelDisplay = false;
        this.resetLevelGamestateCountdown = 0;
        this.heldLevelCounterDispatchOwned = false;
        this.retainedResultsHeldLevelCounterOwned = false;
        this.inLevelPlayerControlLockOwned = false;
        this.inLevelExitDelayFrames = 0;
        this.retainedControlPollFollowsTitleCompletion = false;
        this.inLevelArtAdmissionPollObserved = false;
        this.releasePreloadedActCameraOnComplete = false;
        this.preloadedActCompletionPrepared = false;
        this.runtimeArtAdmissionConsumed = false;
        this.freshLevelTransitionMode = false;
        this.freshLevelTitleOwnerReplacedAtAssembly = false;
        this.state = Sonic3kTitleCardState.SLIDE_IN;
        this.stateTimer = 0;
        this.phaseCounter = 0;
        this.exitChildrenGone = false;
        RuntimeArtAdmissionLease admissionLease = GameServices.module()
                .getObjectArtProvider()
                .bindPendingRuntimeArtAdmission(
                        RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
        this.runtimeArtAdmissionLeaseId = admissionLease.id();

        // Load art if needed
        if (!artLoaded || lastLoadedZone != zoneIndex || lastLoadedAct != actIndex) {
            loadAllArt(zoneIndex, actIndex);
        }

        // Set up elements
        actNumberVisible = !Sonic3kTitleCardMappings.isSingleActZone(zoneIndex);
        int zoneFrame = Sonic3kTitleCardMappings.getZoneFrame(zoneIndex);

        elemFrame[ELEM_BANNER] = Sonic3kTitleCardMappings.FRAME_BANNER;
        elemFrame[ELEM_ZONE_NAME] = zoneFrame;
        elemFrame[ELEM_ZONE_TEXT] = Sonic3kTitleCardMappings.FRAME_ZONE;
        elemFrame[ELEM_ACT_NUM] = Sonic3kTitleCardMappings.FRAME_ACT;

        for (int i = 0; i < ELEMENT_COUNT; i++) {
            elemX[i] = START_X[i];
            elemY[i] = START_Y[i];
            elemAtTarget[i] = false;
            elemExiting[i] = false;
            elemOutsideViewport[i] = false;
            elemExited[i] = false;
        }

        artCached = false;
        LOG.info("S3K title card initialized: zone=" + zoneIndex + " act=" + actIndex
                + " inLevel=" + inLevel);
    }

    @Override
    public void update() {
        if (artLoading) {
            if (!finishQueuedArtIfReady()) {
                if (retainedResultsHeldLevelCounterOwned
                        && resetLevelGamestateOnInLevelDisplay
                        && resetLevelGamestateCountdown > 0
                        && --resetLevelGamestateCountdown == 0) {
                    consumeLevelGamestateResetRequest();
                }
                return;
            }
            if (!freshLevelTransitionMode) {
                publishFreshLevelRuntimeArtHandoffIfNeeded();
            }
        } else if (artLoaded) {
            // A repeated same-zone load reuses the already-ready title sheet.
            // Publish at the same readiness boundary as the queued-art path
            // instead of waiting for EXIT, where another load could replace
            // the armed level identity.
            if (!freshLevelTransitionMode) {
                publishFreshLevelRuntimeArtHandoffIfNeeded();
            }
        }
        if (resetLevelGamestateOnInLevelDisplay && resetLevelGamestateCountdown > 0
                && --resetLevelGamestateCountdown == 0) {
            consumeLevelGamestateResetRequest();
        }
        switch (state) {
            case SLIDE_IN -> updateSlideIn();
            case DISPLAY -> updateDisplay();
            case EXIT -> updateExit();
            case COMPLETE -> {}
        }
    }

    @Override
    public boolean shouldReleaseControl() {
        if (inLevelMode) {
            return true;  // Player already has control during in-level mode
        }
        return state == Sonic3kTitleCardState.EXIT
                || state == Sonic3kTitleCardState.COMPLETE;
    }

    @Override
    public boolean shouldCompleteFreshLevelTransitionBoundary() {
        if (!freshLevelTransitionMode) {
            return shouldReleaseControl();
        }
        // The loaded player slots remain in the native transition owner until
        // Obj_TitleCardWait2 reaches its post-child LoadEnemyArt dispatch.
        return state == Sonic3kTitleCardState.COMPLETE;
    }

    @Override
    public boolean isOverlayActive() {
        if (inLevelMode) {
            return state != Sonic3kTitleCardState.COMPLETE;
        }
        return state == Sonic3kTitleCardState.EXIT;
    }

    @Override
    public boolean isComplete() {
        return state == Sonic3kTitleCardState.COMPLETE;
    }

    public boolean isInLevelExitPhaseFor(int zoneIndex, int actIndex) {
        return inLevelMode
                && currentZone == zoneIndex
                && currentAct == actIndex
                && state == Sonic3kTitleCardState.EXIT;
    }

    public boolean willSetInLevelEndOfLevelFlagThisUpdate() {
        if (!inLevelMode || state != Sonic3kTitleCardState.EXIT) {
            return false;
        }
        // Engine ordering: Sonic3kTitleCardManager advances before object
        // updates, while the ROM title-card wait object and end-sign
        // controller run in the same object pass. Once every child consumed
        // its preceding off-screen render flag, predict the parent wait's next
        // dispatch so AIZ's level-size proxy starts on the native frame.
        if (!exitChildrenGone || inLevelExitDelayFrames > 1) {
            return false;
        }
        int count = bonusMode ? BONUS_ELEMENT_COUNT : ELEMENT_COUNT;
        for (int i = 0; i < count; i++) {
            if (!bonusMode && !actNumberVisible && i == ELEM_ACT_NUM) {
                continue;
            }
            if (!elemExited[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports the in-level title owner's native post-child handoff. The
     * {@code LoadEnemyArt} admission is the first published boundary at which
     * retained AIZ end-sign owners may begin their act-size transition; the
     * later title completion still owns {@code End_of_level_flag}.
     */
    public boolean hasPublishedInLevelRuntimeArtAdmission() {
        return inLevelMode
                && state == Sonic3kTitleCardState.EXIT
                && runtimeArtAdmissionConsumed;
    }

    public int getExitPhaseCounter() {
        return phaseCounter;
    }

    /**
     * S3K ROM: the pre-level title card completes its blocking setup work before
     * normal gameplay begins, so the player does not keep advancing physics during
     * the locked title-card phase. This matters for airborne starts like HCZ1 and
     * LRZ1, where Sonic must remain frozen until the title card releases control.
     */
    @Override
    public boolean shouldRunPlayerPhysics() {
        return false;
    }

    @Override
    public boolean shouldRunLevelObjectsDuringLockedPhase() {
        return false;
    }

    @Override
    public boolean shouldAdvanceVblankClockDuringLockedPhase() {
        return true;
    }

    @Override
    public void draw() {
        ensureArtCached();

        GraphicsManager gm = GameServices.graphics();
        if (gm == null) return;

        // Black background during SLIDE_IN and DISPLAY (not in-level mode).
        // Normal mode: opaque black rect throughout.
        // Bonus mode: fully opaque during SLIDE_IN, then per-channel fade during DISPLAY
        // (B→G→R subtractive, 22 frames, synchronized with hold timer).
        // ROM: Pal_FadeFromBlack runs simultaneously with the title card wait.
        if (!inLevelMode &&
                (state == Sonic3kTitleCardState.SLIDE_IN || state == Sonic3kTitleCardState.DISPLAY)) {
            // Span the full viewport so no level bleeds through on wider screens.
            // viewportWidth()==SCREEN_WIDTH at native 320 — byte-identical.
            int vw = viewportWidth();
            if (bonusMode && state == Sonic3kTitleCardState.DISPLAY && bonusFadeProgress > 0f) {
                // Per-channel subtractive fade matching FadeManager.updateFadeFromBlack()
                // B fades first (0→1/3), then G (1/3→2/3), then R (2/3→1)
                float p = bonusFadeProgress;
                float third = 1f / 3f;
                float darkB = Math.max(0f, 1f - Math.min(1f, p / third));
                float darkG = Math.max(0f, 1f - Math.min(1f, (p - third) / third));
                float darkR = Math.max(0f, 1f - Math.min(1f, (p - 2f * third) / third));
                if (darkR > 0f || darkG > 0f || darkB > 0f) {
                    gm.registerCommand(new GLCommand(
                            GLCommand.CommandType.RECTI, -1, GLCommand.BlendType.SUBTRACTIVE,
                            darkR, darkG, darkB, 1.0f,
                            0, 0, vw, SCREEN_HEIGHT
                    ));
                }
            } else {
                gm.registerCommand(new GLCommand(
                        GLCommand.CommandType.RECTI, -1,
                        0.0f, 0.0f, 0.0f,
                        0, 0, vw, SCREEN_HEIGHT
                ));
            }
        }

        gm.beginPatternBatch();

        if (bonusMode) {
            renderElement(gm, BONUS_ELEM_BONUS);
            renderElement(gm, BONUS_ELEM_STAGE);
        } else {
            renderElement(gm, ELEM_BANNER);
            renderElement(gm, ELEM_ZONE_NAME);
            renderElement(gm, ELEM_ZONE_TEXT);
            if (actNumberVisible) {
                renderElement(gm, ELEM_ACT_NUM);
            }
        }

        gm.flushPatternBatch();
    }

    @Override
    public void reset() {
        state = Sonic3kTitleCardState.COMPLETE;
        stateTimer = 0;
        phaseCounter = 0;
        exitChildrenGone = false;
        inLevelMode = false;
        displayHoldFrames = DISPLAY_HOLD_FRAMES;
        resetLevelGamestateOnInLevelDisplay = false;
        resetLevelGamestateCountdown = 0;
        heldLevelCounterDispatchOwned = false;
        retainedResultsHeldLevelCounterOwned = false;
        inLevelExitDelayFrames = 0;
        retainedControlPollFollowsTitleCompletion = false;
        inLevelArtAdmissionPollObserved = false;
        releasePreloadedActCameraOnComplete = false;
        preloadedActCompletionPrepared = false;
        inLevelPlayerControlLockOwned = false;
        bonusMode = false;
        bonusFadeProgress = 0f;
        currentZone = 0;
        currentAct = 0;
        actNumberVisible = false;
        artLoaded = false;
        artCached = false;
        omittedFreshLevelOwnerActive = false;
        lastLoadedZone = -1;
        lastLoadedAct = -1;
        combinedPatterns = null;
        artQueue = null;
        artHandles.clear();
        artDestinations.clear();
        artLoading = false;
        runtimeArtAdmissionLeaseId = -1;
        runtimeArtAdmissionConsumed = false;
        freshLevelRuntimeArtHandoffLevelIndex = -1;
        freshLevelTransitionMode = false;
        freshLevelTitleOwnerReplacedAtAssembly = false;
        Arrays.fill(elemX, 0);
        Arrays.fill(elemY, 0);
        Arrays.fill(elemFrame, 0);
        Arrays.fill(elemAtTarget, false);
        Arrays.fill(elemExiting, false);
        Arrays.fill(elemOutsideViewport, false);
        Arrays.fill(elemExited, false);
    }

    @Override
    public int getCurrentZone() {
        return currentZone;
    }

    @Override
    public int getCurrentAct() {
        return currentAct;
    }

    // ---- State machine ----

    private void updateSlideIn() {
        int count = bonusMode ? BONUS_ELEMENT_COUNT : ELEMENT_COUNT;
        boolean allAtTarget = true;
        for (int i = 0; i < count; i++) {
            if (!bonusMode && !actNumberVisible && i == ELEM_ACT_NUM) continue;
            if (!elemAtTarget[i]) {
                slideElement(i, true);
                if (!elemAtTarget[i]) allAtTarget = false;
            }
        }
        if (allAtTarget) {
            state = Sonic3kTitleCardState.DISPLAY;
            stateTimer = 0;
            LOG.fine("S3K title card: DISPLAY");
        }
    }

    private void consumeLevelGamestateResetRequest() {
        if (!resetLevelGamestateOnInLevelDisplay) {
            return;
        }
        resetLevelGamestateOnInLevelDisplay = false;
        var levelManager = GameServices.levelOrNull();
        if (levelManager != null) {
            levelManager.resetLevelGamestate(GameServices.module().createLevelState());
        }
        // Obj_TitleCardWait's in-level branch resets both native player slots'
        // air_left bytes alongside rings and timers (sonic3k.asm:62214-62235).
        // The fixed countdown objects survive HCZ's in-place Load_Level, so this
        // must update their existing DrowningController owners rather than
        // recreating the countdown state.
        var player = GameServices.camera().getFocusedSprite();
        if (player != null && player.getDrowningController() != null) {
            player.getDrowningController().replenishAir();
        }
        var nativeP2 = GameServices.sprites().getRegisteredSidekicks().stream()
                .findFirst()
                .orElse(null);
        if (nativeP2 != null && nativeP2 != player
                && nativeP2.getDrowningController() != null) {
            nativeP2.getDrowningController().replenishAir();
        }
    }

    private void updateDisplay() {
        stateTimer++;
        // In bonus mode, run the per-channel fade during the last 22 frames of the hold.
        // ROM: Palette_fade_timer runs after level loading completes, but the title card
        // has already been visible for many frames. Our level loads synchronously, so we
        // use the full 90-frame hold and fade at the end.
        if (bonusMode) {
            int fadeStart = displayHoldFrames - BONUS_BG_FADE_FRAMES;
            if (stateTimer > fadeStart) {
                bonusFadeProgress = Math.min(1f,
                        (float) (stateTimer - fadeStart) / BONUS_BG_FADE_FRAMES);
            }
        }

        if (stateTimer >= displayHoldFrames) {
            state = Sonic3kTitleCardState.EXIT;
            if (freshLevelTransitionMode
                    && freshLevelTitleOwnerReplacedAtAssembly) {
                completeFreshLevelRuntimeArtHandoff();
            }
            phaseCounter = 0;
            LOG.fine("S3K title card: EXIT");
        }
    }

    private void updateExit() {
        phaseCounter++;
        int count = bonusMode ? BONUS_ELEMENT_COUNT : ELEMENT_COUNT;
        int[] priorities = bonusMode ? BONUS_EXIT_PRIORITY : EXIT_PRIORITY;

        boolean allExited = true;
        for (int i = 0; i < count; i++) {
            if (!bonusMode && !actNumberVisible && i == ELEM_ACT_NUM) continue;
            if (elemExited[i]) continue;

            // Obj_TitleCardElement consumes render bit 7 from the preceding
            // Render_Sprites pass. Keep the child alive for that one stale
            // render-flag tick after it first moves wholly off-screen.
            allExited = false;
            if (elemOutsideViewport[i]) {
                elemExited[i] = true;
                continue;
            }

            // Start exiting when phase counter reaches element's priority
            if (phaseCounter >= priorities[i]) {
                elemExiting[i] = true;
            }

            if (elemExiting[i]) {
                slideElement(i, false);
                elemOutsideViewport[i] = isOutsideNativeViewport(i);
            }
        }

        if (allExited) {
            // Obj_TitleCardWait2 (sonic3k.asm:62249-62262) spins only while
            // $30(a0) -- the count of card children still on screen -- is
            // non-zero. Each child clears itself out of that count from a
            // higher SST slot (Obj_TitleCardCreate uses AllocateObjectAfterCurrent,
            // sonic3k.asm:62172; Obj_TitleCardRedBanner decrements $30(a1) at
            // :62311), so the owner cannot see the drained counter until its
            // following dispatch. On that dispatch loc_2D86E (:62263-62302)
            // falls straight through to LoadEnemyArt and
            // Delete_Current_Sprite with no further wait, so exactly one
            // dispatch separates the last child leaving from retirement --
            // there is no additional post-exit owner delay to model.
            if (!exitChildrenGone) {
                exitChildrenGone = true;
                return;
            }
            if (inLevelMode && inLevelExitDelayFrames > 0) {
                // The first owner poll observes the drained child counter;
                // the following poll reaches LoadEnemyArt. Do not derive this
                // from inLevelExitDelayFrames: a retained preloaded-act title
                // can keep its camera-release tail alive for longer than the
                // native title-owner handoff.
                if (inLevelArtAdmissionPollObserved) {
                    consumeRuntimeArtAdmissionIfNeeded();
                } else {
                    inLevelArtAdmissionPollObserved = true;
                }
                inLevelExitDelayFrames--;
                if (inLevelExitDelayFrames == 0
                        && releasePreloadedActCameraOnComplete
                        && !preloadedActCompletionPrepared) {
                    // The retained EndSignControl slot runs one object pass
                    // before Obj_TitleCard publishes its completion flag. It
                    // installs Change_Act2Sizes workers while Scroll_lock is
                    // still held for this frame.
                    preloadedActCompletionPrepared = true;
                    S3kTransitionWriteSupport.preparePreloadedActTitleCardCompletion(
                            GameServices.module().getLevelEventProvider());
                }
                return;
            }
            // Title KosM payload readiness only makes the card renderable.
            // Obj_TitleCardWait2 reaches LoadEnemyArt after the title owner has
            // observed every child retire, which is this sole EXIT -> COMPLETE
            // transition.
            if (!freshLevelTransitionMode) {
                consumeRuntimeArtAdmissionIfNeeded();
            }
            state = Sonic3kTitleCardState.COMPLETE;
            if (!freshLevelTransitionMode) {
                publishFreshLevelRuntimeArtHandoffIfNeeded();
            }
            if (inLevelMode) {
                // ROM Obj_TitleCardWait2 sets End_of_level_flag only after the
                // in-level title-card timer has elapsed and its child objects
                // have disappeared (sonic3k.asm:62244-62279).
                GameServices.gameState().setEndOfLevelFlag(true);
                releasePreloadedActCamera();
            }
            LOG.fine("S3K title card: COMPLETE");
        }
    }

    private void consumeRuntimeArtAdmissionIfNeeded() {
        if (bonusMode || runtimeArtAdmissionConsumed) {
            return;
        }
        var provider = GameServices.module().getObjectArtProvider();
        if (provider == null || runtimeArtAdmissionLeaseId < 0) {
            throw new IllegalStateException(
                    "title owner is missing its runtime-art admission lease");
        }
        RuntimeArtAdmissionLease lease = provider.rebindRuntimeArtAdmission(
                runtimeArtAdmissionLeaseId,
                RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
        if (inLevelMode && !heldLevelCounterDispatchOwned) {
            provider.onInLevelTitleCardCompleted(lease);
        } else {
            provider.consumeRuntimeArtAdmission(
                    lease, RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
        }
        runtimeArtAdmissionConsumed = true;
        if (inLevelMode
                && releasePreloadedActCameraOnComplete) {
            // The LBZ retained EndSignControl owner reaches Change_Act2Sizes
            // at this publication boundary; other transition providers keep
            // their own completion-owned handoff timing.
            S3kTransitionWriteSupport.preparePreloadedActTitleCardRuntimeArtAdmission(
                    GameServices.module().getLevelEventProvider());
        }
    }

    private void publishFreshLevelRuntimeArtHandoffIfNeeded() {
        int levelIndex = freshLevelRuntimeArtHandoffLevelIndex;
        if (levelIndex < 0) {
            return;
        }
        freshLevelRuntimeArtHandoffLevelIndex = -1;
        try {
            // This owner runs on the final locked title-card iteration. Its
            // LoadEnemyArt parents become visible in this row's module tail,
            // but their first direct child belongs to the following loop.
            if (freshLevelTitleOwnerReplacedAtAssembly) {
                GameServices.runtimeArtCoordinator()
                        .deferProductionFirstChildForLateProducer();
            }
            GameServices.level().getGame().queueFreshLevelRuntimeArt(levelIndex);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(
                    "Failed to publish fresh S3K runtime art for level " + levelIndex,
                    exception);
        }
    }

    private void releasePreloadedActCamera() {
        if (!releasePreloadedActCameraOnComplete) {
            return;
        }
        releasePreloadedActCameraOnComplete = false;
        var camera = GameServices.camera();
        // Scroll_lock does not clear H_scroll_frame_offset, so the horizontal
        // history accumulated before the boss remains parked until this release.
        camera.setScrollLocked(false);
    }

    private boolean isOutsideNativeViewport(int idx) {
        if (!bonusMode && IS_VERTICAL[idx]) {
            return elemY[idx] + BANNER_RENDER_HEIGHT < 0;
        }
        int renderWidth = bonusMode ? 0x80 : ELEMENT_RENDER_WIDTH[idx];
        return elemX[idx] - renderWidth >= SCREEN_WIDTH;
    }

    /** Slides an element toward its target or outward until its render bounds leave the screen. */
    private void slideElement(int idx, boolean slideIn) {
        int speed = slideIn ? SLIDE_SPEED_IN : SLIDE_SPEED_OUT;

        if (bonusMode) {
            // Bonus mode: all elements are horizontal
            if (!slideIn) {
                elemX[idx] += speed;
                return;
            }
            int goalX = BONUS_TARGET_X[idx];
            int dir = Integer.compare(goalX, elemX[idx]);
            if (dir == 0) {
                elemAtTarget[idx] = true;
                return;
            }
            elemX[idx] += dir * speed;
            if ((dir > 0 && elemX[idx] >= goalX) || (dir < 0 && elemX[idx] <= goalX)) {
                elemX[idx] = goalX;
                elemAtTarget[idx] = true;
            }
            return;
        }

        // Normal mode
        if (!slideIn) {
            if (IS_VERTICAL[idx]) {
                elemY[idx] -= speed;
            } else {
                elemX[idx] += speed;
            }
            return;
        }
        int goalX = TARGET_X[idx];
        int goalY = TARGET_Y[idx];

        if (IS_VERTICAL[idx]) {
            int dir = Integer.compare(goalY, elemY[idx]);
            if (dir == 0) {
                elemAtTarget[idx] = true;
                return;
            }
            elemY[idx] += dir * speed;
            if ((dir > 0 && elemY[idx] >= goalY) || (dir < 0 && elemY[idx] <= goalY)) {
                elemY[idx] = goalY;
                elemAtTarget[idx] = true;
            }
        } else {
            int dir = Integer.compare(goalX, elemX[idx]);
            if (dir == 0) {
                elemAtTarget[idx] = true;
                return;
            }
            elemX[idx] += dir * speed;
            if ((dir > 0 && elemX[idx] >= goalX) || (dir < 0 && elemX[idx] <= goalX)) {
                elemX[idx] = goalX;
                elemAtTarget[idx] = true;
            }
        }
    }

    // ---- Art loading ----

    private void loadAllArt(int zoneIndex, int actIndex) {
        try {
            if (!GameServices.rom().isRomAvailable()) {
                LOG.warning("ROM not available for title card art");
                return;
            }
            Rom rom = GameServices.rom().getRom();

            combinedPatterns = new Pattern[VRAM_ARRAY_SIZE];
            Pattern empty = new Pattern();
            Arrays.fill(combinedPatterns, empty);
            beginArtQueue();

            // 1. Load RedAct art → VRAM $500 (index 0)
            queueKosmArt(rom, Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR, 0);

            // 2. Load S3KZone text → VRAM $510 (index $10), overwrites part of RedAct
            queueKosmArt(rom, Sonic3kConstants.ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR,
                    Sonic3kConstants.VRAM_TITLE_CARD_ZONE_TEXT - VRAM_BASE);

            // 3. Load act number art → VRAM $53D (index $3D)
            int actArtAddr = (actIndex == 0)
                    ? Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM1_ADDR
                    : Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM2_ADDR;
            queueKosmArt(rom, actArtAddr,
                    Sonic3kConstants.VRAM_TITLE_CARD_ACT_NUM - VRAM_BASE);

            // 4. Load zone-specific art → VRAM $54D (index $4D)
            // Zone 22 (HPZ) maps to art array index 13
            int artIndex = (zoneIndex == 22) ? 13 : zoneIndex;
            if (artIndex >= 0 && artIndex < Sonic3kConstants.TITLE_CARD_ZONE_ART_ADDRS.length) {
                queueKosmArt(rom, Sonic3kConstants.TITLE_CARD_ZONE_ART_ADDRS[artIndex],
                        Sonic3kConstants.VRAM_TITLE_CARD_ZONE_ART - VRAM_BASE);
            }

            artLoading = true;
            artLoaded = false;
            artCached = false;
            lastLoadedZone = zoneIndex;
            lastLoadedAct = actIndex;
            LOG.info("S3K title card art loaded for zone " + zoneIndex);
        } catch (Exception e) {
            LOG.warning("Failed to load S3K title card art: " + e.getMessage());
            artLoaded = false;
        }
    }

    /**
     * Loads bonus stage title card art.
     * Same shared blocks (RedAct, S3KZone text) plus
     * ArtKosM_BonusTitleCard at VRAM $54D instead of zone-specific art.
     */
    private void loadBonusArt() {
        try {
            if (!GameServices.rom().isRomAvailable()) {
                LOG.warning("ROM not available for bonus title card art");
                return;
            }
            Rom rom = GameServices.rom().getRom();

            combinedPatterns = new Pattern[VRAM_ARRAY_SIZE];
            Pattern empty = new Pattern();
            Arrays.fill(combinedPatterns, empty);
            beginArtQueue();

            // 1. Load RedAct art -> VRAM $500 (index 0)
            queueKosmArt(rom, Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR, 0);

            // 2. Load S3KZone text -> VRAM $510 (index $10)
            queueKosmArt(rom, Sonic3kConstants.ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR,
                    Sonic3kConstants.VRAM_TITLE_CARD_ZONE_TEXT - VRAM_BASE);

            // 3. Load bonus-specific letter art -> VRAM $54D (index $4D)
            queueKosmArt(rom, Sonic3kConstants.ART_KOSM_BONUS_TITLE_CARD_ADDR,
                    Sonic3kConstants.VRAM_TITLE_CARD_ZONE_ART - VRAM_BASE);

            artLoading = true;
            artLoaded = false;
            artCached = false;
            lastLoadedZone = -2;  // Sentinel for "bonus art loaded"
            lastLoadedAct = -1;
            LOG.info("S3K bonus title card art loaded");
        } catch (Exception e) {
            LOG.warning("Failed to load S3K bonus title card art: " + e.getMessage());
            artLoaded = false;
        }
    }

    private void beginArtQueue() {
        artQueue = S3kRuntimeArtCoordinator.current().moduleQueue();
        artHandles.clear();
        artDestinations.clear();
    }

    private void queueKosmArt(Rom rom, int romAddr, int destIndex) throws Exception {
        artHandles.add(artQueue.queue(rom, romAddr, VRAM_BASE + destIndex));
        artDestinations.add(destIndex);
    }

    private boolean finishQueuedArtIfReady() {
        for (HardwareWorkHandle handle : artHandles) {
            if (!artQueue.isReady(handle)) {
                return false;
            }
        }
        for (int job = 0; job < artHandles.size(); job++) {
            placeArt(artQueue.claim(artHandles.get(job)), artDestinations.get(job));
        }
        artHandles.clear();
        artDestinations.clear();
        artLoading = false;
        artLoaded = true;
        artCached = false;
        return true;
    }

    private void placeArt(byte[] decompressed, int destIndex) {
        int tileCount = decompressed.length / Pattern.PATTERN_SIZE_IN_ROM;
        for (int i = 0; i < tileCount; i++) {
            int idx = destIndex + i;
            if (idx >= 0 && idx < combinedPatterns.length) {
                byte[] tileData = Arrays.copyOfRange(decompressed,
                        i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                Pattern pat = new Pattern();
                pat.fromSegaFormat(tileData);
                combinedPatterns[idx] = pat;
            }
        }
    }

    private void ensureArtCached() {
        if (artCached || !artLoaded || combinedPatterns == null) return;

        GraphicsManager gm = GameServices.graphics();
        if (gm == null) return;

        for (int i = 0; i < combinedPatterns.length; i++) {
            if (combinedPatterns[i] != null) {
                gm.cachePatternTexture(combinedPatterns[i], PATTERN_BASE + i);
            }
        }
        artCached = true;
    }

    // ---- Rendering ----

    private void renderElement(GraphicsManager gm, int elemIdx) {
        if (!artLoaded || combinedPatterns == null) return;
        if (elemExited[elemIdx]) return;

        int frameIndex = elemFrame[elemIdx];
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(frameIndex);
        // xOffset() centres the 320-wide composition in the viewport.
        // At native 320 xOffset()==0 — byte-identical.
        int centerX = elemX[elemIdx] + xOffset();
        int centerY = elemY[elemIdx];

        // Render back-to-front: VDP sprites earlier in the mapping have higher
        // priority (appear in front). Drawing in reverse ensures the highest-priority
        // pieces (e.g. game name text on the banner) end up on top of lower-priority
        // pieces (e.g. the red fill blocks).
        for (int i = pieces.length - 1; i >= 0; i--) {
            TitleCardSpriteRenderer.renderSpritePiece(
                    gm, pieces[i], centerX, centerY,
                    VRAM_BASE, PATTERN_BASE, VRAM_ARRAY_SIZE);
        }
    }
}
