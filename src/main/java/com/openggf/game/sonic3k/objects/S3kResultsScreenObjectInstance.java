package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.audio.GameMusic;
import com.openggf.data.Rom;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.save.SaveReason;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.objects.AbstractResultsScreen;
import com.openggf.game.sonic3k.Sonic3kObjectArt;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.S3kTransitionWriteSupport;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.data.compression.NemesisReader;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Pattern;
import com.openggf.level.CarriedTitlePublicationTiming;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * S3K results screen -- displays "{CHARACTER} GOT THROUGH ACT {N}" with
 * time bonus and ring bonus tally after the signpost lands.
 *
 * <p>ROM: Obj_LevelResults (sonic3k.asm lines 62499-63003).
 *
 * <p>Key differences from S2:
 * <ul>
 *   <li>No fade-to-black -- signals flags and lets level events handle transitions</li>
 *   <li>360-frame pre-tally delay (S2 uses 180)</li>
 *   <li>90-frame post-tally wait (S2 uses 180)</li>
 *   <li>No perfect bonus</li>
 *   <li>Act 1 exit shows act 2 title card; act 2 exit sets End_of_level_flag</li>
 * </ul>
 */
public class S3kResultsScreenObjectInstance extends AbstractResultsScreen implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(S3kResultsScreenObjectInstance.class.getName());

    // ROM-accurate timing
    private static final int S3K_PRE_TALLY_DELAY = 360;  // 6*60 frames (ROM line 62580)
    private static final int S3K_WAIT_DURATION = 90;      // ROM line 62676
    private static final int MUSIC_TRIGGER_FRAME = 71;    // 360 - 289 = 71 (ROM line 62626)
    private static final int CARRIED_RESULTS_RENDER_RETIRE_DISPATCHES = 3;
    private static final int MUTATED_TITLE_CARD_RESET_DISPATCHES = 38;

    // Time bonus table (ROM lines 62910-62918)
    private static final int[] TIME_BONUSES = {5000, 5000, 1000, 500, 400, 300, 100, 10};
    private static final int SPECIAL_TIME_BONUS = 10000;  // 9:59 override (ROM line 62559)
    private static final int MAX_TIMER_SECONDS = 599;     // 9:59 = 9*60 + 59

    // Slide speeds (ROM lines 62847, 62836)
    private static final int SLIDE_IN_SPEED = 16;   // moveq #$10,d1
    private static final int SLIDE_OUT_SPEED = 32;  // move.w #-$20,d0

    // Pattern caching
    private static final int PATTERN_BASE = PatternAtlasRange.RESULTS_SCREENS.base();

    // Digit rendering constants
    private static final int DIGIT_OFFSET_X = -0x38;  // Digits start 0x38 pixels left of element X
    private static final int DIGIT_SPACING = 8;        // 8px per digit
    private static final int DIGIT_COUNT = 7;           // 7-digit display
    private static final int[] DIVISORS = {1000000, 100000, 10000, 1000, 100, 10, 1};

    // State
    // Un-finaled for rewind: character/act are constructor args not carried by any
    // ObjectSpawn, so recreate uses placeholders and GenericFieldCapturer (which
    // skips final scalars) reapplies the captured values
    // after recreate. Covers this class and its Mgz2 subclass.
    private PlayerCharacter character;
    private int act;  // 0-indexed: 0=Act 1, 1=Act 2
    private int waitDurationAdjustment;
    private int postControlHandoffDelayEntries;
    private int carriedResultsRetireDispatches = CARRIED_RESULTS_RENDER_RETIRE_DISPATCHES;
    private S3kSignpostInstance.ResultsChildTimingAdjustment resultsChildTimingAdjustment =
            S3kSignpostInstance.ResultsChildTimingAdjustment.NONE;
    private boolean usesShortResultsChildRetireTail;
    private boolean controlsReleasedAheadOfHandoff;
    private boolean carriedAcrossSeamlessTransition;
    private boolean titlePublicationOwnedByCarriedObject;
    private boolean carriedTitleTimingExplicit;
    private boolean carriedTitleResetLevelGamestateAtDisplay;
    private int carriedTitleResetAdditionalDispatches;
    private int carriedTitleResetPhaseOneDispatchOverlap;
    private boolean carriedTitleLockPlayerControl;
    private int carriedTitleExitAdditionalDispatches;
    private int carriedTitleExitPhaseOneDispatchOverlap;
    private int carriedPreloadedActCameraReleaseDispatches = -1;

    // Tally values
    private int timeBonus;
    private int ringBonus;
    private int totalBonusCountUp;

    // Art
    private Pattern[] combinedPatterns;
    private List<SpriteMappingFrame> mappingFrames;
    private boolean artLoaded;
    private boolean artCached;
    private boolean resultsArtLoadPending;
    private boolean initialResultsArtLoadDispatchDeferred;
    private boolean resultsArtLoadDispatchDeferred;
    private Sonic3kObjectArt.QueuedResultsArt queuedResultsArt;
    private long resultsGeneralArtOrdinal = -1;
    private long resultsNumberArtOrdinal = -1;
    private long resultsCharacterArtOrdinal = -1;

    // Rendering
    private ObjectSpriteSheet spriteSheet;
    private PatternSpriteRenderer renderer;

    // Music flag
    private boolean musicPlayed;

    // Player reference for control restoration on exit
    private AbstractPlayableSprite playerRef;

    // Elements
    private final ResultsElement[] elements = new ResultsElement[12];
    private int exitQueueCounter;
    private int childrenRemaining;
    private int createGateFrames = -1;
    private boolean actTransitionSignaled;
    private boolean resultsChildrenCreated;
    private int carriedResultsRenderRetireDispatches;
    private boolean exitRetireDispatchesInitialized;
    private boolean exitPublicationComplete;
    private boolean titleInitializationPending;
    private boolean titleCardInitialized;
    private boolean pendingPreloadedTitleHandoff;
    private boolean pendingAizTitleHandoff;
    private boolean pendingRetainedReloadTitleHandoff;
    private boolean postControlHandoffPending;
    private boolean postResultsEventHandoffPending;

    public S3kResultsScreenObjectInstance(PlayerCharacter character, int act) {
        this(character, act, 0, 0);
    }

    @Override
    protected boolean skipsSameFrameUpdateAfterSpawn() {
        // The grounded short-tail owner is published by AllocateObject from an
        // earlier SST and does not run Obj_LevelResultsInit until the next
        // Process_Sprites pass (sonic3k.asm:62512-62531). Ordinary result
        // owners retain their native same-pass dispatch.
        return usesShortResultsChildRetireTail
                && resultsChildTimingAdjustment
                == S3kSignpostInstance.ResultsChildTimingAdjustment.UNSUPPORTED_GROUNDED_COMPENSATION;
    }

    S3kResultsScreenObjectInstance(PlayerCharacter character, int act, int waitDurationAdjustment,
            int postControlHandoffDelayEntries) {
        this(character, act, waitDurationAdjustment, postControlHandoffDelayEntries,
                CARRIED_RESULTS_RENDER_RETIRE_DISPATCHES);
    }

    S3kResultsScreenObjectInstance(PlayerCharacter character, int act, int waitDurationAdjustment,
            int postControlHandoffDelayEntries, int carriedResultsRetireDispatches) {
        this(character, act, waitDurationAdjustment, postControlHandoffDelayEntries,
                carriedResultsRetireDispatches,
                S3kSignpostInstance.ResultsChildTimingAdjustment.NONE, false);
    }

    S3kResultsScreenObjectInstance(PlayerCharacter character, int act, int waitDurationAdjustment,
            int postControlHandoffDelayEntries, int carriedResultsRetireDispatches,
            boolean usesShortResultsChildRetireTail) {
        this(character, act, waitDurationAdjustment, postControlHandoffDelayEntries,
                carriedResultsRetireDispatches,
                S3kSignpostInstance.ResultsChildTimingAdjustment.NONE,
                usesShortResultsChildRetireTail);
    }

    S3kResultsScreenObjectInstance(
            PlayerCharacter character,
            int act,
            int waitDurationAdjustment,
            int postControlHandoffDelayEntries,
            int carriedResultsRetireDispatches,
            S3kSignpostInstance.ResultsChildTimingAdjustment timingAdjustment,
            boolean usesShortResultsChildRetireTail) {
        super("S3kResults");
        this.character = character;
        this.act = act;
        this.waitDurationAdjustment = Math.max(0, waitDurationAdjustment);
        this.postControlHandoffDelayEntries = Math.max(0, postControlHandoffDelayEntries);
        this.carriedResultsRetireDispatches = Math.max(0, carriedResultsRetireDispatches);
        this.resultsChildTimingAdjustment = timingAdjustment;
        this.usesShortResultsChildRetireTail = usesShortResultsChildRetireTail;

        // Calculate bonuses from current game state (ROM lines 62550-62578)
        calculateBonuses();

        // Fade out current music immediately (ROM line 62513)
        fadeOutMusic();

        // Rewind restoration reconstructs a scalar shell, then rebinds the
        // captured hardware ordinals after GenericFieldCapturer restores them.
        if (!ObjectConstructionContext.isRewindActiveRestore()) {
            resultsArtLoadPending = true;
        }

        LOG.fine(() -> String.format("S3K results init: character=%s act=%d timeBonus=%d ringBonus=%d",
                character, act, timeBonus, ringBonus));
    }

    private S3kResultsScreenObjectInstance() {
        this(true);
    }

    protected S3kResultsScreenObjectInstance(boolean restoreOnly) {
        super("S3kResults");
        this.character = PlayerCharacter.SONIC_AND_TAILS;
        this.act = 0;
        this.resultsArtLoadPending = false;
        createElements();
    }

    @Override
    public AbstractResultsScreen recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new S3kResultsScreenObjectInstance(true));
    }

    @Override
    protected void afterGenericRewindStateRestored(RewindCaptureContext context) {
        if (mappingFrames == null) {
            loadDerivedMappingsFromRom();
        }
        if (artLoaded && renderer == null) {
            restoreClaimedArtRenderOwner();
        }
    }

    @Override
    public String traceDebugDetails() {
        return String.format(
                "state=%02X timer=%04X total=%04X act=%d create=%02X sig=%b time=%d ring=%d total=%d music=%b complete=%b",
                state,
                stateTimer & 0xFFFF,
                totalFrames & 0xFFFF,
                act,
                createGateFrames & 0xFFFF,
                actTransitionSignaled,
                timeBonus,
                ringBonus,
                totalBonusCountUp,
                musicPlayed,
                complete);
    }

    // ---- Element data structure ----

    /**
     * A single visual element on the results screen that slides in and out.
     * ROM: ObjArray_LevResults (sonic3k.asm lines 62919-63003).
     */
    private static class ResultsElement {
        enum Type { CHAR_NAME, GENERAL, TIME_BONUS, RING_BONUS, TOTAL }
        enum SlideDirection { FROM_LEFT, FROM_RIGHT }

        final Type type;
        final int targetX;
        final int startX;
        final int y;
        final int mappingFrame;
        final int widthPixels;
        final int exitQueuePriority;
        final SlideDirection slideDirection;
        int currentX;
        boolean exitStarted;
        boolean renderOnScreen = true;
        boolean offScreen;

        ResultsElement(Type type, int targetX, int startX, int y,
                       int mappingFrame, int widthPixels, int exitQueuePriority) {
            this.type = type;
            this.targetX = targetX;
            this.startX = startX;
            this.y = y;
            this.mappingFrame = mappingFrame;
            this.widthPixels = widthPixels;
            this.exitQueuePriority = exitQueuePriority;
            this.slideDirection = startX < 0 ? SlideDirection.FROM_LEFT : SlideDirection.FROM_RIGHT;
            this.currentX = startX;
        }

        /** Move toward targetX at 16px/frame. Returns true when reached. */
        boolean slideIn() {
            if (currentX == targetX) return true;
            if (currentX < targetX) {
                currentX = Math.min(currentX + SLIDE_IN_SPEED, targetX);
            } else {
                currentX = Math.max(currentX - SLIDE_IN_SPEED, targetX);
            }
            return currentX == targetX;
        }

        /** Move at 32px/frame back toward the direction it came from. */
        void slideOut() {
            if (slideDirection == SlideDirection.FROM_LEFT) {
                currentX -= SLIDE_OUT_SPEED;
            } else {
                currentX += SLIDE_OUT_SPEED;
            }
            // Render_Sprites clears and recomputes render_flags.on_screen
            // after the object pass. LevelResults_MoveElement consumes that
            // prior-pass bit on the child's next dispatch.
            renderOnScreen = currentX + widthPixels >= 0
                    && currentX - widthPixels < SCREEN_WIDTH;
        }
    }

    // ---- Element creation ----

    /**
     * Populates the elements array from ROM data (ObjArray_LevResults).
     * ROM: sonic3k.asm lines 62919-63003.
     */
    private void createElements() {
        // ROM ObjArray_LevResults uses VDP coordinates (+128 hardware offset on X and Y).
        // Our engine uses direct screen coordinates, so subtract 128 from all positions.
        // CLAUDE.md: "VDP hardware adds 128 to X/Y. Convert: screen_position = vdp_value - 128"
        final int V = 128; // VDP offset

        int charNameFrame = getCharNameFrame();
        int charNameTargetX = 0xE0 - V;
        int charNameStartX = -0x220 - V;
        int charNameWidth = 0x48;

        if (character == PlayerCharacter.KNUCKLES) {
            charNameTargetX -= 0x30;
            charNameStartX -= 0x30;
            charNameWidth += 0x30;
        }
        if (character == PlayerCharacter.TAILS_ALONE) {
            charNameTargetX += 8;
            charNameStartX += 8;
            charNameWidth -= 8;
        }

        elements[0]  = new ResultsElement(ResultsElement.Type.CHAR_NAME,  charNameTargetX, charNameStartX, 0xB8 - V, charNameFrame, charNameWidth, 1);
        elements[1]  = new ResultsElement(ResultsElement.Type.GENERAL,    0x130 - V, -0x1D0 - V, 0xB8 - V, 0x11, 0x30, 1);
        elements[2]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xE8 - V,   0x468 - V, 0xCC - V, 0x10, 0x70, 3);
        elements[3]  = new ResultsElement(ResultsElement.Type.GENERAL,    0x160 - V,  0x4E0 - V, 0xBC - V, 0x0F, 0x38, 3);
        elements[4]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xC0 - V,   0x4C0 - V, 0xF0 - V, 0x0E, 0x20, 5);
        elements[5]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xE8 - V,   0x4E8 - V, 0xF0 - V, 0x0C, 0x30, 5);
        elements[6]  = new ResultsElement(ResultsElement.Type.TIME_BONUS, 0x178 - V,  0x578 - V, 0xF0 - V, 1,    0x40, 5);
        elements[7]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xC0 - V,   0x500 - V, 0x100 - V, 0x0D, 0x20, 7);
        elements[8]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xE8 - V,   0x528 - V, 0x100 - V, 0x0C, 0x30, 7);
        elements[9]  = new ResultsElement(ResultsElement.Type.RING_BONUS, 0x178 - V,  0x5B8 - V, 0x100 - V, 1,    0x40, 7);
        elements[10] = new ResultsElement(ResultsElement.Type.GENERAL,    0xD4 - V,   0x554 - V, 0x11C - V, 0x0B, 0x30, 9);
        elements[11] = new ResultsElement(ResultsElement.Type.TOTAL,      0x178 - V,  0x5F8 - V, 0x11C - V, 1,    0x40, 9);
        childrenRemaining = 12;
    }

    /**
     * Returns the mapping frame index for the character name.
     * ROM: character-specific name art frames in Map_LevelResults.
     */
    private int getCharNameFrame() {
        return switch (character) {
            case TAILS_ALONE -> 0x13 + 2;
            case KNUCKLES -> 0x13 + 3;
            default -> 0x13;
        };
    }

    // ---- Bonus calculation ----

    private void calculateBonuses() {
        var levelGamestate = services().levelGamestate();
        int elapsedSeconds = (levelGamestate != null) ? levelGamestate.getElapsedSeconds() : 0;

        // Pause the timer (ROM line 62550)
        if (levelGamestate != null) {
            levelGamestate.pauseTimer();
        }

        // Special case: 9:59 -> 10000 (ROM lines 62557-62559)
        if (elapsedSeconds >= MAX_TIMER_SECONDS) {
            timeBonus = SPECIAL_TIME_BONUS;
        } else {
            int index = Math.min(elapsedSeconds / 30, TIME_BONUSES.length - 1);
            timeBonus = TIME_BONUSES[index];
        }

        // Ring bonus: rings x 10 (ROM lines 62576-62578)
        int ringCount = (levelGamestate != null) ? levelGamestate.getRings() : 0;
        ringBonus = ringCount * 10;

        totalBonusCountUp = 0;
    }

    // ---- Timing overrides ----

    @Override
    protected int getSlideDuration() {
        return 0;  // Children handle their own sliding; skip SLIDE_IN entirely
    }

    @Override
    protected int getPreTallyDelay() {
        return S3K_PRE_TALLY_DELAY;
    }

    @Override
    protected int getWaitDuration() {
        return S3K_WAIT_DURATION + waitDurationAdjustment;
    }

    // ---- Update with element sliding ----

    /**
     * Fully overrides base update() because S3K has a slide-out exit phase
     * that the base class doesn't support (base STATE_EXIT immediately sets
     * complete=true, but S3K needs exit queue animation first).
     *
     * ROM flow: Obj_LevelResultsWait2 counts down 90 frames, THEN runs exit
     * queue each frame until all children are off-screen, THEN transitions.
     */
    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        this.playerRef = player;
        this.frameCounter = vIntRunCount;
        if (postControlHandoffPending) {
            if (postControlHandoffDelayEntries > 0) {
                postControlHandoffDelayEntries--;
            }
            if (postControlHandoffDelayEntries <= 0) {
                postControlHandoffPending = false;
                completePostResultsEventHandoff();
                restoreNativeEndSignControlAtPublication();
            }
        }
        if (resultsArtLoadPending) {
            if (shouldDeferInitialResultsArtLoadDispatch()
                    && !initialResultsArtLoadDispatchDeferred) {
                // Keep the capsule's player/control handoff on its native
                // dispatch while Obj_LevelResultsInit crosses the separate
                // result-art service boundary before its three Kosinski jobs.
                initialResultsArtLoadDispatchDeferred = true;
                return;
            }
            if (usesShortResultsChildRetireTail
                    && resultsChildTimingAdjustment
                    == S3kSignpostInstance.ResultsChildTimingAdjustment.UNSUPPORTED_GROUNDED_COMPENSATION
                    && !resultsArtLoadDispatchDeferred) {
                // Obj_EndSignResults publishes the general-allocation results
                // owner on its own pass before Obj_LevelResultsInit submits
                // the three KosM jobs.
                resultsArtLoadDispatchDeferred = true;
                return;
            }
            // Obj_LevelResultsInit runs on the first dispatch after the SST
            // is allocated.  Keep the ROM's one-frame gap between the object
            // publication and its three Kosinski submissions.
            loadArt();
            resultsArtLoadPending = false;
        }
        if (!updateCreateGate()) {
            return;
        }
        stateTimer++;
        totalFrames++;

        switch (state) {
            case STATE_SLIDE_IN -> updateSlideIn();
            case STATE_PRE_TALLY_DELAY -> updatePreTallyDelay();
            case STATE_TALLY -> updateTally();
            case STATE_WAIT -> updateWait();
            case STATE_EXIT -> updateExitQueue();
            // Do NOT set complete=true here — exitQueue handles it
        }

        // Slide elements toward targets during pre-tally and tally
        if (state <= STATE_TALLY) {
            for (ResultsElement elem : elements) {
                if (elem != null && !elem.offScreen) {
                    elem.slideIn();
                }
            }
        }
    }

    /**
     * Override: transition to STATE_EXIT without calling onExitReady().
     * ROM: Obj_LevelResultsWait2 first counts down 90 frames, then the
     * exit queue runs. onExitReady() fires only after ALL children are gone.
     */
    @Override
    protected void updateWait() {
        if (stateTimer >= getWaitDuration()) {
            state = STATE_EXIT;
            stateTimer = 0;
            // Do NOT call onExitReady() here — wait for exit queue to finish
        }
    }

    private boolean updateCreateGate() {
        if (resultsChildrenCreated) {
            return true;
        }
        // ROM Obj_LevelResultsCreate gates on Kos_modules_left alone
        // (docs/skdisasm/sonic3k.asm:62596-62598): the first object dispatch
        // after the queued results art has fully decompressed creates the
        // children and sets Events_fg_5. Module readiness is the exact engine
        // equivalent of that flag, so no additional dispatch countdown applies.
        rebindQueuedResultsArtAfterRestore();
        if (queuedResultsArt == null || !queuedResultsArt.isReady()) {
            return false;
        }
        // ROM Obj_LevelResultsInit queues three Kosinski module loads and
        // advances to Obj_LevelResultsCreate; Create polls Kos_modules_left
        // before allocating child objects and setting Events_fg_5
        // (docs/skdisasm/sonic3k.asm:62512-62584, 62586-62616).
        finishQueuedArt();
        createElements();
        signalActTransitionIfNeeded();
        actTransitionSignaled = true;
        resultsChildrenCreated = true;
        return true;
    }

    /**
     * ROM: Obj_LevelResultsWait2 after 90-frame wait (sonic3k.asm lines 62686-62690).
     * Increments exit queue counter each frame. Children start sliding out when
     * the counter reaches their priority. When all are gone, fire onExitReady().
     */
    private void updateExitQueue() {
        if (childrenRemaining <= 0) {
            if (!exitRetireDispatchesInitialized) {
                carriedResultsRenderRetireDispatches += additionalChildRetireDispatches();
                exitRetireDispatchesInitialized = true;
            }
            if (carriedResultsRenderRetireDispatches > 0) {
                onAdditionalChildRetireDispatch(carriedResultsRenderRetireDispatches);
                carriedResultsRenderRetireDispatches--;
                return;
            }
            onExitReady();
            complete = !titleInitializationPending;
            return;
        }
        exitQueueCounter++;
        for (ResultsElement elem : elements) {
            if (elem == null || elem.offScreen) continue;
            if (!elem.renderOnScreen) {
                elem.offScreen = true;
                childrenRemaining--;
                if (childrenRemaining == 0) {
                    onResultsChildrenRetired();
                }
                continue;
            }
            if (exitQueueCounter >= elem.exitQueuePriority) {
                elem.exitStarted = true;
            }
            if (elem.exitStarted) {
                elem.slideOut();
            }
        }
    }

    /**
     * Reports the retained-owner boundary at which Obj_EndSignControl can
     * restore the players, before Obj_LevelResults publishes the next owner.
     * The result children and the carried SST retirement tail are both gone,
     * but the publication flag is still clear, so the next result dispatch is
     * the one that clears End_of_level_active.
     */
    boolean isEndSignControlRestoreBoundaryReady() {
        if (postControlHandoffDelayEntries > 0 || postControlHandoffPending) {
            return false;
        }
        boolean deferredGeneralOwnerControlBoundary = resultsArtLoadDispatchDeferred
                && state == STATE_EXIT
                && childrenRemaining <= 0
                && !exitPublicationComplete;
        boolean ready = deferredGeneralOwnerControlBoundary || (state == STATE_EXIT
                && childrenRemaining <= 0
                && exitRetireDispatchesInitialized
                && carriedResultsRenderRetireDispatches <= 0
                && !exitPublicationComplete);
        return ready;
    }

    /**
     * Additional owner dispatches while ROM child SSTs finish retiring after
     * the engine's embedded result elements have left the screen.
     */
    protected int additionalChildRetireDispatches() {
        return 0;
    }

    /**
     * Whether this result owner waits one dispatch before submitting its
     * initial ROM-backed art jobs.
     */
    protected boolean shouldDeferInitialResultsArtLoadDispatch() {
        return false;
    }

    /**
     * Hook for an event-owned results parent whose retained child slots have
     * dispatch-visible work before the parent's final exit callback.
     */
    protected void onAdditionalChildRetireDispatch(int dispatchesRemaining) {
        // Default results parents have no retained slot work.
    }

    /**
     * Called when the final embedded results child retires.  Route owners can
     * publish the ROM's child-count handoff before their parent performs its
     * final exit callback on the next object pass.
     */
    protected void onResultsChildrenRetired() {
        // Default results parents have no early release owner.
    }

    @Override
    public void onCarriedAcrossSeamlessTransition(int offsetX, int offsetY) {
        // HCZ/MGZ-style Load_Level paths retain Obj_LevelResults and its ROM
        // child SSTs. The engine carries the parent but renders its twelve
        // children as embedded elements, so preserve the final three child
        // retirement dispatches that occur after the embedded set is gone.
        carriedResultsRenderRetireDispatches = carriedResultsRetireDispatches;
        carriedAcrossSeamlessTransition = true;
    }

    @Override
    public void onCarriedAcrossSeamlessTransition(
            int offsetX,
            int offsetY,
            CarriedTitlePublicationTiming titleTiming) {
        onCarriedAcrossSeamlessTransition(offsetX, offsetY);
        carriedTitleTimingExplicit = titleTiming.explicitTiming();
        titlePublicationOwnedByCarriedObject =
                titleTiming.titlePublicationOwnedByCarriedObject();
        carriedTitleResetLevelGamestateAtDisplay =
                titleTiming.resetLevelGamestateAtDisplay();
        carriedTitleResetAdditionalDispatches = titleTiming.resetAdditionalDispatches();
        carriedTitleResetPhaseOneDispatchOverlap = titleTiming.resetPhaseOneDispatchOverlap();
        carriedTitleLockPlayerControl = titleTiming.lockPlayerControl();
        carriedTitleExitAdditionalDispatches = titleTiming.exitAdditionalDispatches();
        carriedTitleExitPhaseOneDispatchOverlap = titleTiming.exitPhaseOneDispatchOverlap();
        carriedPreloadedActCameraReleaseDispatches =
                titleTiming.preloadedActCameraReleaseDispatches();
        if (titleTiming.carriedResultsRetireDispatches() >= 0) {
            carriedResultsRenderRetireDispatches = titleTiming.carriedResultsRetireDispatches();
        }
    }

    // ---- Pre-tally delay with music trigger ----

    @Override
    protected void updatePreTallyDelay() {
        // Trigger music at frame 71 of the 360-frame countdown
        // ROM: checks counter == 289 (360 - 71) at line 62626
        if (!musicPlayed && stateTimer == MUSIC_TRIGGER_FRAME) {
            musicPlayed = true;
            try {
                services().playMusic(GameMusic.ACT_CLEAR);
            } catch (Exception e) {
                // Ignore audio errors
            }
        }

        super.updatePreTallyDelay();
    }

    // ---- Tally logic ----

    @Override
    protected TallyResult performTallyStep() {
        int totalIncrement = 0;

        // Decrement time bonus by 10 (ROM line 62639)
        int[] timeResult = decrementBonus(timeBonus);
        timeBonus = timeResult[0];
        totalIncrement += timeResult[1];

        // Decrement ring bonus by 10 (ROM line 62645)
        int[] ringResult = decrementBonus(ringBonus);
        ringBonus = ringResult[0];
        totalIncrement += ringResult[1];

        // Track running total (ROM line 62648)
        totalBonusCountUp += totalIncrement;

        boolean anyRemaining = (timeBonus > 0 || ringBonus > 0);
        return tallyResult(anyRemaining, totalIncrement);
    }

    @Override
    protected void updateTally() {
        TallyResult result = performTallyStep();

        if (result.totalIncrement() > 0) {
            services().gameState().addScore(result.totalIncrement());
        }

        // ROM uses global frame counter for tick timing (line 62652-62654)
        // Level_frame_counter & 3 == 0
        // We use the frameCounter field (set by update()) as the global frame source
        if (result.anyRemaining()) {
            if ((this.frameCounter & 3) == 0) {
                playTickSound();
            }
        }

        if (!result.anyRemaining()) {
            playTallyEndSound();
            int zone = services().romZoneId();
            if ((act != 0) || (zone == 0x0A)) {
                services().requestSessionSave(SaveReason.PROGRESSION_SAVE);
            }
            state = STATE_WAIT;
            stateTimer = 0;
        }
    }

    // ---- Audio overrides ----

    @Override
    protected void playTickSound() {
        try {
            services().playSfx(Sonic3kSfx.SWITCH.id);
        } catch (Exception e) {
            // Ignore audio errors
        }
    }

    @Override
    protected void playTallyEndSound() {
        try {
            services().playSfx(Sonic3kSfx.REGISTER.id);
        } catch (Exception e) {
            // Ignore audio errors
        }
    }

    private void fadeOutMusic() {
        try {
            services().fadeOutMusic();
        } catch (Exception e) {
            // Ignore audio errors
        }
    }

    /**
     * ROM: Obj_LevelResultsCreate (sonic3k.asm line 62610-62616).
     * Sets Events_fg_5 for Act 1 zones (except AIZ zone 0 and ICZ zone 5)
     * to trigger the background event handler's seamless act transition.
     *
     * <p>ROM Obj_LevelResultsInit queues the Kosinski module loads first; the
     * create gate above models Obj_LevelResultsCreate polling Kos_modules_left
     * before this flag is written.
     */
    private void signalActTransitionIfNeeded() {
        if (act != 0) return;  // Act 2 — no transition
        try {
            int zone = services().romZoneId();
            if (zone == 0x00) return;  // AIZ — handled by fire transition
            if (zone == 0x05) return;  // ICZ — different transition mechanism
            S3kTransitionWriteSupport.signalActTransition(services());
        } catch (Exception e) {
            LOG.fine("Could not signal act transition: " + e.getMessage());
        }
    }

    // ---- Exit behavior ----

    @Override
    protected void onExitReady() {
        if (exitPublicationComplete) {
            if (titleInitializationPending) {
                // ROM Obj_LevelResultsWait2 mutates this SST into
                // Obj_TitleCard and returns. A retained generic title owner
                // may have submitted Obj_TitleCardInit's art on the
                // publication dispatch; its following owner pass only retires
                // the old results shell.
                if (!titleCardInitialized) {
                    initializePublishedTitleCard();
                    titleCardInitialized = true;
                }
                titleInitializationPending = false;
                complete = true;
                ObjectLifetimeOps.deleteNoRespawn(this);
            }
            return;
        }
        exitPublicationComplete = true;
        int zone = services().romZoneId();
        boolean hasSeamlessTransition = (act == 0) && (zone == 0x01 || zone == 0x02);
        boolean retainedReloadState = act == 0 && carriedAcrossSeamlessTransition;
        boolean lbzAct2PostBossHandoff = zone == 0x06 && act == 1;
        boolean preloadedNextActHandoff = isPreloadedNextActHandoff(act, services().currentAct());
        boolean aizAct1MinibossTitleHandoff = zone == 0x00 && act == 0;

        // Restore camera. AIZ Act 1 is excluded because ROM Obj_LevelResults
        // changes into the in-level Act 2 title card without touching camera
        // bounds; Obj_EndSignControlDoStart waits for that title card to set
        // End_of_level_flag before Change_Act2Sizes creates the gradual
        // level-size objects (sonic3k.asm:62708-62720,62276-62279,
        // 180415-180419,180575-180609).
        // When the AIZ2 cutscene override is active, the
        // Aiz2BossEndSequenceController manages camera bounds for the walk-right
        // sequence. Restoring full level bounds here would snap the camera back
        // to the pre-boss area (ROM: loc_694D4 uses Obj_IncLevEndXGradual).
        boolean iczAct2EndBossHandoff = zone == 0x05 && act == 1;
        var cam = services().camera();
        if (!preloadedNextActHandoff) {
            applyCameraFollowExitState(cam, lbzAct2PostBossHandoff);
        }
        if (!hasSeamlessTransition && !retainedReloadState
                && !preloadedNextActHandoff
                && shouldRestoreCameraBoundsOnExit(zone, act)
                && !Aiz2BossEndSequenceState.isCutsceneOverrideObjectsActive()) {
            var level = services().currentLevel();
            if (level != null) {
                if (!iczAct2EndBossHandoff) {
                    cam.setMinX((short) level.getMinX());
                }
                cam.setMaxX((short) level.getMaxX());
                cam.setMinY((short) level.getMinY());
                cam.setMaxY((short) level.getMaxY());
            }
        }

        // Act 2, Sky Sanctuary ($A), or LRZ boss ($16): set End_of_level_flag
        // ROM lines 62694-62705
        boolean isAct2OrSpecial = (act != 0) || (zone == 0x0A) || (zone == 0x16);

        services().gameState().setEndOfLevelActive(false);
        // Obj_EndSignControlAwaitStart is a separate retained owner that runs
        // later in this object pass once _unkFAA8 clears. Route the handoff
        // through the transition bridge so only an armed native event consumes
        // it; no zone or trace identity is consulted here.
        boolean retainedTransitionFlagOwner = false;
        if (postControlHandoffDelayEntries > 0) {
            // A retained EndSignControl slot that precedes this results owner
            // has already run in the current Process_Sprites walk. Preserve
            // that slot boundary before asking the event-owned replacement to
            // consume the _unkFAA8-clear publication.
            postResultsEventHandoffPending = true;
        } else {
            retainedTransitionFlagOwner =
                    S3kTransitionWriteSupport.completePostResultsHandoff(services());
        }

        if (isAct2OrSpecial || retainedTransitionFlagOwner) {
            // ROM loc_2DCF8 sets End_of_level_flag directly for Act 2/Sky
            // Sanctuary/LRZ boss results (sonic3k.asm:62693-62705).
            // A retained native transition owner can request the same ready
            // flag through the event bridge without this object inferring its
            // transition policy from the current zone.
            services().gameState().setEndOfLevelFlag(true);
        }

        if (!isAct2OrSpecial) {
            // Act 1: transition to act 2 (ROM lines 62708-62720)
            // ROM loc_2DD06 mutates the results object into Obj_TitleCard
            // without setting End_of_level_flag. The in-level title-card wait
            // path sets it after its children are gone (sonic3k.asm:62708-62720,
            // 62244-62279).
            // ROM: move.b #1,(Apparent_act).w — update display act so
            // death/restart title cards show "Act 2" from this point on.
            services().setApparentAct(1);
            // The level data continues seamlessly (S3K acts share the same level).

            // Play act 2 music
            var zoneRegistry = services().gameModule().getZoneRegistry();
            int act2MusicId = zoneRegistry.getMusicId(zone, 1);
            if (act2MusicId >= 0) {
                try { services().playMusic(act2MusicId); } catch (Exception e) { /* ignore */ }
            }

            // Show act 2 title card (except SOZ zone $8, DEZ zone $B, and zones
            // with seamless transitions like HCZ — the seamless transition will
            // show its own title card after the level reload).
            // ROM lines 62713-62720
            boolean skipTitleCard = (zone == 0x08) || (zone == 0x0B);
            if (!skipTitleCard && (!hasSeamlessTransition || retainedReloadState)
                    && (!carriedAcrossSeamlessTransition
                    || titlePublicationOwnedByCarriedObject)) {
                var gameModule = services().gameModule();
                var objectArtProvider = gameModule == null
                        ? null : gameModule.getObjectArtProvider();
                if (objectArtProvider != null) {
                    objectArtProvider.prepareRuntimeArtForInLevelTitleCard();
                }
                titleInitializationPending = true;
                pendingPreloadedTitleHandoff = preloadedNextActHandoff;
                pendingAizTitleHandoff = aizAct1MinibossTitleHandoff;
                pendingRetainedReloadTitleHandoff = retainedReloadState;
            }

            // ROM: Timer and ring count reset on act transition. For zones with
            // seamless transitions (HCZ), the level reload in executeActTransition
            // creates a fresh LevelGamestate. For non-seamless S3K act transitions
            // (where acts share level data), the results screen must reset the
            // gamestate directly since no level reload occurs. AIZ's miniboss
            // handoff carries its Timer/Ring_count reset through the title-card
            // request above, where it becomes visible after the title children
            // reach their display positions (sonic3k.asm:62708-62720,
            // 62214-62235).
            if (!hasSeamlessTransition && !retainedReloadState
                    && !aizAct1MinibossTitleHandoff) {
                resetLevelGamestateForActTransition();
            }
        }

        if (!titleInitializationPending && !controlsReleasedAheadOfHandoff) {
            releasePlayerControlsForExit();
            controlsReleasedAheadOfHandoff = true;
        }
        if (postControlHandoffDelayEntries > 0) {
            // Obj_EndSignControl's lower SST slot restores control on the
            // following Process_Sprites pass. Keep the results publication
            // (and its title-art submissions) on this pass, but defer the
            // player-control writes until that owner pass.
            postControlHandoffPending = true;
        } else {
            restoreNativeEndSignControlAtPublication();
        }
        if (titleInitializationPending && initializeTitleCardOnPublication()) {
            // The native carried title owner submits Obj_TitleCardInit's
            // ROM-backed jobs on the same dispatch that mutates the results
            // parent. Keep the generic retained shell for its following
            // object pass; the short-tail owner retains its existing immediate
            // retirement contract.
            initializePublishedTitleCard();
            titleCardInitialized = true;
            if (usesShortResultsChildRetireTail) {
                titleInitializationPending = false;
                complete = true;
            }
        }
        if (!titleInitializationPending) {
            ObjectLifetimeOps.deleteNoRespawn(this);
        }
        LOG.fine(() -> String.format("S3K results exit: zone=%X act=%d isAct2OrSpecial=%b",
                zone, act, isAct2OrSpecial));
    }

    private boolean initializeTitleCardOnPublication() {
        return carriedAcrossSeamlessTransition
                && titlePublicationOwnedByCarriedObject
                && (usesShortResultsChildRetireTail || carriedTitleTimingExplicit);
    }

    /**
     * ROM {@code Obj_LevelResultsWait2} has already changed this retained SST
     * into {@code Obj_TitleCard}; its next object dispatch performs
     * {@code Obj_TitleCardInit} and queues the four ROM-backed KosM jobs
     * (docs/skdisasm/sonic3k.asm:62108-62166, 62684-62725).
     */
    private void initializePublishedTitleCard() {
        int zone = services().romZoneId();
        var titleCardProvider = services().titleCardProvider();
        titleCardProvider.initializeInLevel(zone, 1);
        if (titleCardProvider instanceof Sonic3kTitleCardManager s3kTitleCard) {
            if (pendingPreloadedTitleHandoff) {
                int releaseDispatches = carriedPreloadedActCameraReleaseDispatches;
                if (releaseDispatches < 0) {
                    releaseDispatches =
                            S3kTransitionWriteSupport
                                    .preloadedActCameraReleaseAdditionalDispatches(services());
                }
                if (releaseDispatches < 0) {
                    s3kTitleCard.requestPreloadedActCameraReleaseOnComplete();
                } else if (releaseDispatches > 0) {
                    s3kTitleCard.requestPreloadedActCameraReleaseOnComplete(
                            releaseDispatches);
                }
            }
            if (pendingAizTitleHandoff) {
                s3kTitleCard.requestLevelGamestateResetAtInLevelDisplay();
            } else if (pendingRetainedReloadTitleHandoff) {
                // This Obj_LevelResults survived an earlier Load_Level and now
                // dispatches as Obj_TitleCard. The title owner resets the
                // counters after its native create dispatches.
                if (carriedTitleTimingExplicit) {
                    if (carriedTitleResetLevelGamestateAtDisplay) {
                        s3kTitleCard.requestLevelGamestateResetAtInLevelDisplay(
                                carriedTitleResetAdditionalDispatches,
                                carriedTitleResetPhaseOneDispatchOverlap);
                    }
                    if (carriedTitleLockPlayerControl) {
                        s3kTitleCard.requestInLevelPlayerControlLock();
                    }
                    s3kTitleCard.requestInLevelExitAdditionalDispatches(
                            carriedTitleExitAdditionalDispatches,
                            carriedTitleExitPhaseOneDispatchOverlap);
                } else {
                    s3kTitleCard.requestLevelGamestateResetAfterCreateDispatches(
                            mutatedTitleCardResetDispatches(
                                    usesShortResultsChildRetireTail,
                                    carriedPreloadedActCameraReleaseDispatches,
                                    initializeTitleCardOnPublication()));
                    if (carriedPreloadedActCameraReleaseDispatches == 0) {
                        s3kTitleCard.requestInLevelExitAdditionalDispatches(1);
                    }
                }
            }
        }
        pendingPreloadedTitleHandoff = false;
        pendingAizTitleHandoff = false;
        pendingRetainedReloadTitleHandoff = false;
    }

    /**
     * Mirrors the later native EndSignControl slot when it follows this
     * results owner in the same object pass. The owner is discovered by its
     * live flow state; no zone or trace identity participates in the handoff.
     */
    private void restoreNativeEndSignControlAtPublication() {
        var objectManager = services().objectManager();
        if (objectManager == null || playerRef == null) {
            return;
        }
        for (S3kBossDefeatSignpostFlow flow :
                objectManager.activeObjectsOfType(S3kBossDefeatSignpostFlow.class)) {
            if (!flow.isDestroyed()) {
                flow.restoreNativeControlAtResultsPublication(playerRef);
                return;
            }
        }
    }

    private void completePostResultsEventHandoff() {
        if (!postResultsEventHandoffPending) {
            return;
        }
        postResultsEventHandoffPending = false;
        if (S3kTransitionWriteSupport.completePostResultsHandoff(services())) {
            services().gameState().setEndOfLevelFlag(true);
        }
    }

    static int mutatedTitleCardResetDispatches(boolean usesShortResultsChildRetireTail) {
        return mutatedTitleCardResetDispatches(usesShortResultsChildRetireTail, -1);
    }

    static int mutatedTitleCardResetDispatches(
            boolean usesShortResultsChildRetireTail,
            int preloadedActCameraReleaseDispatches) {
        return mutatedTitleCardResetDispatches(
                usesShortResultsChildRetireTail,
                preloadedActCameraReleaseDispatches,
                false);
    }

    static int mutatedTitleCardResetDispatches(
            boolean usesShortResultsChildRetireTail,
            int preloadedActCameraReleaseDispatches,
            boolean initializesOnPublication) {
        // A short child-retirement tail hands ownership to the mutated title
        // card one frame earlier, before the native child/create phase has
        // exposed its final two dispatches.
        int dispatches = MUTATED_TITLE_CARD_RESET_DISPATCHES
                + (usesShortResultsChildRetireTail ? 2 : 0);
        if (initializesOnPublication) {
            // Sharing the publication dispatch removes one owner pass from the
            // absolute display-reset schedule.
            dispatches--;
        }
        // When the retained transition explicitly has no preloaded-camera
        // tail, its virtual child retirement also has no synthetic owner pass.
        // The title initializes one replay row earlier; keep the native
        // display-time gamestate reset at the same absolute dispatch.
        return dispatches + (preloadedActCameraReleaseDispatches == 0 ? 1 : 0);
    }

    private void releasePlayerControlsForExit() {
        int zone = services().romZoneId();
        // HCZ/MGZ retain a separate post-transition control owner. CNZ carries
        // this results object through its reload and Restore_PlayerControl still
        // belongs to the results exit itself.
        boolean hasSeamlessTransition = (act == 0) && (zone == 0x01 || zone == 0x02);
        boolean lbzAct2PostBossHandoff = zone == 0x06 && act == 1;
        if (!hasSeamlessTransition && !lbzAct2PostBossHandoff && shouldRestorePlayerControlsOnExit()) {
            for (PlayableEntity candidate : playerQuery()
                    .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
                if (candidate instanceof AbstractPlayableSprite sprite) {
                    sprite.setControlLocked(false);
                    ObjectControlState.none().applyTo(sprite);
                    sprite.setForcedAnimationId(-1);
                    if (shouldPublishWaitAnimationOnControlRestore()) {
                        // Restore_PlayerControl writes anim/prev_anim to Wait.
                        // A retained results owner runs after player animation,
                        // so publish the new animation id while retaining the
                        // current mapping for this object entry.
                        sprite.setAnimationId(Sonic3kAnimationIds.WAIT);
                    }
                }
            }
        }
    }

    static boolean shouldPublishWaitAnimationOnControlRestore(
            int waitDurationAdjustment, boolean carriedAcrossSeamlessTransition) {
        return waitDurationAdjustment > 0 || carriedAcrossSeamlessTransition;
    }

    protected boolean shouldPublishWaitAnimationOnControlRestore() {
        return shouldPublishWaitAnimationOnControlRestore(
                waitDurationAdjustment, carriedAcrossSeamlessTransition);
    }

    static boolean shouldRestoreLevelCameraBoundsOnExit(int zone, int act) {
        boolean actOneInLevelTitleHandoff = act == 0
                && (zone == 0x00 || zone == 0x01 || zone == 0x02);
        boolean lbzActTwoPostBossHandoff = zone == 0x06 && act == 1;
        return !actOneInLevelTitleHandoff && !lbzActTwoPostBossHandoff;
    }

    static boolean isPreloadedNextActHandoff(int resultsAct, int currentAct) {
        // Some mid-act bosses run after the next act's level state has already
        // been loaded while Apparent_act still belongs to the results owner.
        // The in-level title card, not Obj_LevelResults, later releases the
        // retained Scroll_lock and camera bounds.
        return resultsAct == 0 && currentAct > resultsAct;
    }

    protected boolean shouldRestoreCameraBoundsOnExit(int zone, int act) {
        return shouldRestoreLevelCameraBoundsOnExit(zone, act);
    }

    /**
     * Applies the route-specific camera-follow state at the results handoff.
     * Most routes resume normal following; retained boss/cutscene owners can
     * preserve or assert the ROM {@code Scroll_lock} state instead.
     */
    protected void applyCameraFollowExitState(com.openggf.camera.Camera camera,
                                              boolean lbzAct2PostBossHandoff) {
        if (!lbzAct2PostBossHandoff) {
            camera.setFrozen(false);
        }
    }

    protected boolean shouldRestorePlayerControlsOnExit() {
        return true;
    }

    private ObjectPlayerQuery playerQuery() {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> playerRef, query::sidekicks);
    }

    /**
     * Resets the LevelGamestate (timer + rings) for a non-seamless act transition.
     * ROM: Timer and ring count reset to zero when entering a new act.
     * Score carries over.
     */
    private void resetLevelGamestateForActTransition() {
        var levelManager = services().levelManager();
        if (levelManager != null) {
            var gameModule = services().gameModule();
            if (gameModule != null) {
                levelManager.resetLevelGamestate(gameModule.createLevelState());
            }
        }
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        return true;  // Survives screen boundary checks
    }

    // ---- Art loading ----

    private void loadArt() {
        try {
            var rom = services().rom();
            var reader = services().romReader();
            Sonic3kObjectArt objectArt = new Sonic3kObjectArt(null, reader);
            queuedResultsArt = objectArt.queueResultsArt(
                    rom,
                    character,
                    act,
                    services().romZoneId(),
                    S3kRuntimeArtCoordinator.from(services()).moduleQueue());
            List<HardwareWorkHandle> resultsHandles = queuedResultsArt.handles();
            resultsGeneralArtOrdinal = resultsHandles.get(0).ordinal();
            resultsNumberArtOrdinal = resultsHandles.get(1).ordinal();
            resultsCharacterArtOrdinal = resultsHandles.get(2).ordinal();
            loadDerivedMappings(objectArt);
            artLoaded = false;

            // Note: The level results screen uses the existing level palette.
            // Pal_Results in the ROM is for the special stage results screen, not here.
            // (S2 results screen also does not load a palette.)
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Unable to queue results-screen ROM art", e);
        }
    }

    private void loadDerivedMappingsFromRom() {
        try {
            loadDerivedMappings(new Sonic3kObjectArt(null, services().romReader()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Unable to restore results-screen ROM mappings", e);
        }
    }

    private void loadDerivedMappings(Sonic3kObjectArt objectArt) {
        List<SpriteMappingFrame> rawMappings = objectArt.loadResultsMappings();
        mappingFrames = Sonic3kObjectArt.adjustTileIndices(
                rawMappings, -Sonic3kConstants.VRAM_RESULTS_BASE);
        if (act != 0) {
            int charNameTileOffset = Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT2
                    - Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT1;
            adjustCharNameFrameTiles(charNameTileOffset);
        }
    }

    private void finishQueuedArt() {
        combinedPatterns = queuedResultsArt.claim();
        queuedResultsArt = null;
        rebuildArtRenderOwner();
    }

    private void restoreClaimedArtRenderOwner() {
        var timing = services().hardwareTiming();
        combinedPatterns = Sonic3kObjectArt.assembleClaimedResultsArt(
                List.of(
                        timing.claimedPayload(
                                HardwareWorkKind.KOS_MODULE_QUEUE,
                                resultsGeneralArtOrdinal),
                        timing.claimedPayload(
                                HardwareWorkKind.KOS_MODULE_QUEUE,
                                resultsNumberArtOrdinal),
                        timing.claimedPayload(
                                HardwareWorkKind.KOS_MODULE_QUEUE,
                                resultsCharacterArtOrdinal)),
                act);
        rebuildArtRenderOwner();
    }

    private void rebuildArtRenderOwner() {
        Rom rom;
        try {
            rom = services().rom();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Unable to resolve results-screen ROM", e);
        }
        loadHudTextIntoPatterns(rom, combinedPatterns);
        if (mappingFrames == null || mappingFrames.isEmpty()) {
            throw new IllegalStateException(
                    "Results-screen mapping table is empty");
        }
        spriteSheet = new ObjectSpriteSheet(combinedPatterns, mappingFrames, 0, 1);
        renderer = new PatternSpriteRenderer(spriteSheet);
        artLoaded = true;
        artCached = false;
    }

    private void rebindQueuedResultsArtAfterRestore() {
        if (artLoaded || queuedResultsArt != null || resultsGeneralArtOrdinal < 0) {
            return;
        }
        var timing = services().hardwareTiming();
        HardwareWorkHandle general = timing.pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        resultsGeneralArtOrdinal)
                .orElseThrow(() -> missingRestoredResultsJob(resultsGeneralArtOrdinal));
        HardwareWorkHandle numbers = timing.pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        resultsNumberArtOrdinal)
                .orElseThrow(() -> missingRestoredResultsJob(resultsNumberArtOrdinal));
        HardwareWorkHandle characterName = timing.pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        resultsCharacterArtOrdinal)
                .orElseThrow(() -> missingRestoredResultsJob(resultsCharacterArtOrdinal));
        int charDestination = act == 0
                ? Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT1
                : Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT2;
        queuedResultsArt = Sonic3kObjectArt.QueuedResultsArt.restore(
                S3kRuntimeArtCoordinator.from(services()).moduleQueue(),
                List.of(general, numbers, characterName),
                new int[] {
                        0,
                        Sonic3kConstants.VRAM_RESULTS_NUMBERS
                                - Sonic3kConstants.VRAM_RESULTS_BASE,
                        charDestination - Sonic3kConstants.VRAM_RESULTS_BASE
                });
    }

    private static IllegalStateException missingRestoredResultsJob(long ordinal) {
        return new IllegalStateException(
                "restored results owner cannot find KosM ordinal " + ordinal);
    }

    /**
     * Loads HUD text tiles from ArtNem_RingHUDText and places them at the correct
     * VRAM offsets in the combined pattern array.
     *
     * ROM: ArtNem_RingHUDText loads to ArtTile_Ring ($6BC). First 14 tiles are
     * ring art ($6BC-$6C9), tiles 14+ are HUD text starting at $6CA.
     * The results mapping frames reference these tiles for TIME/RING/BONUS labels.
     */
    private void loadHudTextIntoPatterns(Rom rom, Pattern[] patterns) {
        try {
            FileChannel channel = rom.getFileChannel();
            // Rom exposes a shared FileChannel; lock around seek+decode so concurrent
            // readers cannot move the channel position mid-stream.
            byte[] data;
            synchronized (rom) {
                channel.position(Sonic3kConstants.ART_NEM_RING_HUD_TEXT_ADDR);
                data = NemesisReader.decompress(channel);
            }

            int totalTiles = data.length / Pattern.PATTERN_SIZE_IN_ROM;
            // ArtTile_Ring = $6BC. Place ALL tiles starting at array index $6BC - $520 = $19C
            int vramBase = 0x6BC;
            for (int i = 0; i < totalTiles; i++) {
                int arrayIdx = (vramBase + i) - Sonic3kConstants.VRAM_RESULTS_BASE;
                if (arrayIdx >= 0 && arrayIdx < patterns.length) {
                    byte[] tileData = Arrays.copyOfRange(data,
                            i * Pattern.PATTERN_SIZE_IN_ROM,
                            (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                    Pattern pat = new Pattern();
                    pat.fromSegaFormat(tileData);
                    patterns[arrayIdx] = pat;
                }
            }
            LOG.fine("Loaded " + totalTiles + " ring/HUD text tiles from ROM");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Unable to load results-screen HUD text from ROM", e);
        }
    }

    /**
     * Adjusts tile indices for character name mapping frames to account for the
     * act-dependent VRAM destination. Character name frames are $13 (Sonic),
     * $14 (Sonic & Tails label), $15 (Tails), $16 (Knuckles).
     *
     * <p>ROM: Character name child object art_tile = 0 (act 1) or $28 (act 2).
     * Since we use a single combined pattern array without per-element art_tile,
     * we must offset the character name frame tile indices by this amount.
     */
    private void adjustCharNameFrameTiles(int tileOffset) {
        if (mappingFrames == null || !(mappingFrames instanceof ArrayList)) {
            mappingFrames = new ArrayList<>(mappingFrames);
        }
        // Adjust all possible character name frames ($13 through $16)
        for (int frameIdx = 0x13; frameIdx <= 0x16; frameIdx++) {
            if (frameIdx >= mappingFrames.size()) continue;
            SpriteMappingFrame frame = mappingFrames.get(frameIdx);
            if (frame.pieces().isEmpty()) continue;
            List<SpriteMappingPiece> adjusted = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                adjusted.add(new SpriteMappingPiece(
                        piece.xOffset(), piece.yOffset(),
                        piece.widthTiles(), piece.heightTiles(),
                        piece.tileIndex() + tileOffset,
                        piece.hFlip(), piece.vFlip(),
                        piece.paletteIndex(), piece.priority()));
            }
            mappingFrames.set(frameIdx, new SpriteMappingFrame(adjusted));
        }
    }

    // ---- Pattern caching ----

    private void ensureArtCached() {
        if (artCached || !artLoaded || renderer == null) return;
        var gm = services().graphicsManager();
        if (gm == null) return;
        renderer.ensurePatternsCached(gm, PATTERN_BASE);
        artCached = true;
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!artLoaded || renderer == null) return;
        ensureArtCached();
        if (!renderer.isReady()) return;

        var camera = services().camera();
        if (camera == null) return;

        // xOffset() is (viewportWidth - 320) / 2; 0 at native 320 (byte-identical).
        int baseX = camera.getX() + xOffset();
        int baseY = camera.getY();

        for (ResultsElement elem : elements) {
            if (elem == null || elem.offScreen) continue;
            int worldX = baseX + elem.currentX;
            int worldY = baseY + elem.y;

            switch (elem.type) {
                case TIME_BONUS -> renderBonusDigits(worldX, worldY, timeBonus);
                case RING_BONUS -> renderBonusDigits(worldX, worldY, ringBonus);
                case TOTAL -> renderBonusDigits(worldX, worldY, totalBonusCountUp);
                default -> renderMappingFrame(elem.mappingFrame, worldX, worldY);
            }
        }
    }

    /**
     * Renders a mapping frame at the given world position using the PatternSpriteRenderer.
     */
    private void renderMappingFrame(int frameIndex, int worldX, int worldY) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount()) return;
        renderer.drawFrameIndex(frameIndex, worldX, worldY, false, false);
    }

    /**
     * Renders a 7-digit BCD value with leading zero suppression.
     * ROM: LevResults_DisplayScore (sonic3k.asm lines 62789-62815).
     *
     * Each digit uses a mapping frame from Map_Results:
     *   Frame 0 = blank (suppressed leading zero)
     *   Frame 1 = "0", Frame 2 = "1", ... Frame 10 = "9"
     * These frames reference tiles from ArtKosM_ResultsGeneral ($520+),
     * NOT the act number art at $568.
     */
    private void renderBonusDigits(int worldX, int worldY, int value) {
        int x = worldX + DIGIT_OFFSET_X;
        boolean hasNonZero = false;
        int remaining = value;

        for (int i = 0; i < DIGIT_COUNT; i++) {
            int digit = remaining / DIVISORS[i];
            remaining %= DIVISORS[i];
            if (digit != 0) hasNonZero = true;

            // ROM: mapping_frame = (hasNonZero) ? digit + 1 : 0
            // Frame 0 = blank, Frame 1 = "0", Frame 2 = "1", ..., Frame 10 = "9"
            int frameIdx;
            if (hasNonZero || i == DIGIT_COUNT - 1) {
                frameIdx = digit + 1;
            } else {
                frameIdx = 0; // Suppress leading zero
            }

            if (frameIdx > 0) {
                renderMappingFrame(frameIdx, x + i * DIGIT_SPACING, worldY);
            }
        }
    }
}
