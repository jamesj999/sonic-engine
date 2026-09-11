package com.openggf.game.sonic1.credits;

import com.openggf.game.GameServices;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenDataLoader;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenManager;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.graphics.FadeManager;
import com.openggf.game.resources.NativeFadeLifecycle;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Orchestrates the Sonic 1 ending credits sequence.
 * <p>
 * State machine with two alternating phases:
 * <ol>
 *   <li>TEXT phase: credit text displayed on black screen for 120 frames</li>
 *   <li>DEMO phase: pre-recorded demo playback on a zone (540/510 frames)</li>
 * </ol>
 * <p>
 * Credits 0-7 have both text and demo. Credit 8 ("PRESENTED BY SEGA")
 * is text-only, after which the sequence is finished.
 * <p>
 * Reference: docs/s1disasm/sonic.asm lines 4090-4267 (GM_Credits / EndingDemoLoad)
 */
public class Sonic1CreditsManager {
    private static final Logger LOGGER = Logger.getLogger(Sonic1CreditsManager.class.getName());

    /** Credits state machine phases. */
    public enum State {
        TEXT_FADE_IN,
        TEXT_DISPLAY,
        TEXT_FADE_OUT,
        DEMO_LOADING,
        DEMO_LOAD_DELAY,
        DEMO_FADE_IN,
        DEMO_PLAYING,
        DEMO_FADING_OUT,
        FINISHED
    }

    private State state;
    private int creditsNum;
    private int timer;
    private int demoLoadDelay;
    private boolean scrollFrozen;

    // Demo input playback
    private DemoInputPlayer demoInputPlayer;

    // Credit text rendering
    private final Sonic1CreditsTextRenderer textRenderer = new Sonic1CreditsTextRenderer();

    // Transition flags read by GameLoop
    private boolean requestDemoLoad;
    private boolean requestTextReturn;
    private boolean requestFinished;
    private final NativeFadeLifecycle nativeFadeLifecycle;
    private final com.openggf.graphics.FadeManager injectedFadeManager;

    public Sonic1CreditsManager() {
        this(com.openggf.game.resources.NoOpNativeFadeLifecycle.INSTANCE, null);
    }

    public Sonic1CreditsManager(NativeFadeLifecycle nativeFadeLifecycle) {
        this(nativeFadeLifecycle, null);
    }

    Sonic1CreditsManager(
            NativeFadeLifecycle nativeFadeLifecycle,
            com.openggf.graphics.FadeManager fadeManager) {
        this.nativeFadeLifecycle = java.util.Objects.requireNonNull(
                nativeFadeLifecycle, "nativeFadeLifecycle");
        this.injectedFadeManager = fadeManager;
    }

    private Runnable nativeCompletion(Runnable completion) {
        return nativeFadeLifecycle.beginNativeBlockingFade().wrapCompletion(completion);
    }

    /**
     * Initializes the credits sequence. Plays credits music and starts
     * the first credit text fade-in.
     */
    public void initialize() {
        creditsNum = 0;
        scrollFrozen = false;
        demoLoadDelay = 0;
        demoInputPlayer = null;
        requestDemoLoad = false;
        requestTextReturn = false;
        requestFinished = false;
        queueNextDemoPlcs();

        // Initialize text renderer from title screen data.
        // Ensure data is loaded (it normally is from the title screen, but be safe).
        Sonic1TitleScreenDataLoader dataLoader = resolveTitleScreenDataLoader();
        if (dataLoader != null && !dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }
        textRenderer.initialize(dataLoader);

        // Play credits music (ROM: move.w #MusID_Credits,d0; jsr PlaySound)
        GameServices.audio().playMusic(Sonic1Music.CREDITS.id);

        // Start with fade from black to reveal first credit text
        state = State.TEXT_FADE_IN;
        fadeManager().startFadeFromBlack(nativeCompletion(() -> {
            state = State.TEXT_DISPLAY;
            timer = Sonic1CreditsDemoData.TEXT_DISPLAY_FRAMES;
        }));

        LOGGER.info("Credits sequence initialized, starting credit 0");
    }

    /**
     * Updates the credits state machine. Called once per frame.
     */
    public void update() {
        switch (state) {
            case TEXT_FADE_IN -> {
                // Waiting for FadeManager callback to advance to TEXT_DISPLAY
            }
            case TEXT_DISPLAY -> updateTextDisplay();
            case TEXT_FADE_OUT -> {
                // Waiting for FadeManager callback
            }
            case DEMO_LOADING -> {
                // Waiting for GameLoop to load the demo zone
            }
            case DEMO_LOAD_DELAY -> updateDemoLoadDelay();
            case DEMO_FADE_IN -> {
                // ROM does not enter Level_MainLoop until after the fade-in completes.
                // Demo input and gameplay remain idle during this phase.
            }
            case DEMO_PLAYING -> updateDemoPlaying();
            case DEMO_FADING_OUT -> {
                // ROM Level_FDLoop continues MoveSonicInDemo during the 60-frame
                // slow fadeout (objects and physics also continue running)
                if (demoInputPlayer != null) {
                    demoInputPlayer.advanceFrame();
                }
            }
            case FINISHED -> { }
        }
    }

    /**
     * TEXT_DISPLAY: Hold credit text on screen for the configured duration.
     */
    private void updateTextDisplay() {
        timer--;
        if (timer > 0) {
            return;
        }

        // Cred_WaitLoop (s1disasm/sonic.asm:3880-3889) holds the page up until
        // BOTH gates clear: `tst.w (v_generictimer).w` (the 120 frames counted
        // above, decremented by VBlank_Title, sonic.asm:784) and
        // `tst.l (v_plc_buffer).w` -- the next demo's level PLC plus plcid_Main2,
        // queued by queueNextDemoPlcs, still decompressing at the nine tiles per
        // frame ProcessPLC_9Tiles gives VBlank routine 4 (sonic.asm:781).
        if (plcBufferOccupied()) {
            return;
        }

        if (creditsNum >= Sonic1CreditsDemoData.DEMO_CREDITS) {
            // Credit 8 ("PRESENTED BY SEGA"): no demo, go to finished
            state = State.TEXT_FADE_OUT;
            fadeManager().startFadeToBlack(nativeCompletion(() -> {
                state = State.FINISHED;
                requestFinished = true;
            }));
        } else {
            // Credits 0-7: fade to black, then load demo zone
            state = State.TEXT_FADE_OUT;
            fadeManager().startFadeToBlack(nativeCompletion(() -> {
                state = State.DEMO_LOADING;
                requestDemoLoad = true;
            }));
        }
    }

    /**
     * DEMO_LOAD_DELAY: ROM-equivalent hidden delay between level load and fade-in.
     */
    private void updateDemoLoadDelay() {
        demoLoadDelay--;
        if (demoLoadDelay > 0) {
            return;
        }

        state = State.DEMO_FADE_IN;
        fadeManager().startFadeFromBlack(
                nativeCompletion(() -> state = State.DEMO_PLAYING));
    }

    /**
     * DEMO_PLAYING: Run demo input, count down timer.
     */
    private void updateDemoPlaying() {
        if (demoInputPlayer != null) {
            demoInputPlayer.advanceFrame();
        }

        timer--;
        if (timer <= 0) {
            // Demo time expired — start slow fadeout.
            // ROM Level_FadeDemo (sonic.asm:3097) uses a 60-frame fade where
            // FadeOut_ToBlack is called every 3rd frame (v_palchgspeed pattern).
            // Objects and demo input continue running during the fade.
            scrollFrozen = true;
            state = State.DEMO_FADING_OUT;
            fadeManager().startFadeToBlack(() -> {
                scrollFrozen = false;
                creditsNum++;
                if (creditsNum >= Sonic1CreditsDemoData.TOTAL_CREDITS) {
                    state = State.FINISHED;
                    requestFinished = true;
                } else {
                    // Return to text phase for next credit
                    requestTextReturn = true;
                }
            }, 0, Sonic1CreditsDemoData.DEMO_FADEOUT_FRAMES);
        }
    }

    /**
     * Called by GameLoop after the demo zone has been loaded.
     * Initializes demo input, positions the player, and starts fade-in.
     */
    public void onDemoZoneLoaded() {
        // Load demo input data from ROM (bulk read for atomicity)
        int addr = Sonic1CreditsDemoData.DEMO_DATA_ADDR[creditsNum];
        int size = Sonic1CreditsDemoData.DEMO_DATA_SIZE[creditsNum];
        byte[] demoData;
        try {
            demoData = GameServices.rom().getRom().readBytes(addr, size);
        } catch (IOException e) {
            LOGGER.warning("Failed to read demo data for credit " + creditsNum + ": " + e.getMessage());
            demoData = new byte[] { 0, 0 }; // Terminator
        }
        demoInputPlayer = new DemoInputPlayer(demoData);

        // Set demo timer
        timer = Sonic1CreditsDemoData.DEMO_TIMER[creditsNum];
        demoLoadDelay = Sonic1CreditsDemoData.DEMO_LOAD_DELAY_FRAMES;
        state = State.DEMO_LOAD_DELAY;

        // Diagnostic: log credit index, ROM address, and first bytes for verification
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(8, demoData.length); i++) {
            hex.append(String.format("%02X ", demoData[i] & 0xFF));
        }
        LOGGER.info("Demo zone loaded for credit " + creditsNum +
                " addr=0x" + Integer.toHexString(addr) +
                " size=" + size +
                " timer=" + timer +
                " firstBytes=[" + hex.toString().trim() + "]");
    }

    /**
     * Called by GameLoop when returning from demo to text phase.
     * Starts the next credit text with fade-in.
     */
    public void onReturnToText() {
        demoInputPlayer = null;
        demoLoadDelay = 0;
        queueNextDemoPlcs();

        // Zone loading during demo phase overwrites GPU patterns/palette
        textRenderer.markGpuDirty();

        // Start fade from black to reveal credit text
        state = State.TEXT_FADE_IN;
        fadeManager().startFadeFromBlack(nativeCompletion(() -> {
            state = State.TEXT_DISPLAY;
            timer = Sonic1CreditsDemoData.TEXT_DISPLAY_FRAMES;
        }));

        LOGGER.info("Showing credit text " + creditsNum);
    }

    /** Mirrors EndingDemoLoad's pre-text ClearPLC/AddPLC/AddPLC sequence. */
    private void queueNextDemoPlcs() {
        try {
            Sonic1PlcService plcService = GameServices.module().getGameService(Sonic1PlcService.class);
            if (plcService == null) {
                return;
            }
            int zone = creditsNum < Sonic1CreditsDemoData.DEMO_CREDITS
                    ? new int[] {0, 2, 4, 1, 3, 5, 5, 0}[creditsNum]
                    // EndDemo_Levels[8] overreads the following EndDemo_LampVar bytes: $0101 (LZ1).
                    : 1;
            int header = Sonic1Constants.LEVEL_HEADERS_ADDR + zone * 16;
            int primary = GameServices.rom().getRom().readByte(header) & 0xFF;
            plcService.transact(primary == 0
                    ? new Sonic1PlcService.Operation[] {Sonic1PlcService.clear(), Sonic1PlcService.appendOperation(1)}
                    : new Sonic1PlcService.Operation[] {Sonic1PlcService.clear(), Sonic1PlcService.appendOperation(primary), Sonic1PlcService.appendOperation(1)});
        } catch (Exception e) {
            LOGGER.fine("Credits PLC queue unavailable: " + e.getMessage());
        }
    }

    void beginDemoPlayingForLifecycleTest(int framesUntilFade) {
        state = State.DEMO_PLAYING;
        timer = framesUntilFade;
        demoInputPlayer = null;
    }

    boolean hasTextReturnRequestForLifecycleTest() {
        return requestTextReturn;
    }

    private com.openggf.graphics.FadeManager fadeManager() {
        return injectedFadeManager != null ? injectedFadeManager : GameServices.fade();
    }

    /**
     * Draws the credit text for the current credit number.
     * Called by Engine during CREDITS_TEXT mode.
     */
    public void drawCreditText() {
        textRenderer.draw(creditsNum);
    }

    // ========================================================================
    // Transition flags (consumed by GameLoop)
    // ========================================================================

    public boolean consumeDemoLoadRequest() {
        boolean r = requestDemoLoad;
        requestDemoLoad = false;
        return r;
    }

    public boolean consumeTextReturnRequest() {
        boolean r = requestTextReturn;
        requestTextReturn = false;
        return r;
    }

    public boolean consumeFinishedRequest() {
        boolean r = requestFinished;
        requestFinished = false;
        return r;
    }

    // ========================================================================
    // Accessors for GameLoop
    // ========================================================================

    public State getState() {
        return state;
    }

    public int getCreditsNum() {
        return creditsNum;
    }

    /**
     * Returns the current demo input mask for the player.
     * GameLoop should apply this to the player during CREDITS_DEMO mode.
     */
    public int getDemoInputMask() {
        if (demoInputPlayer != null && shouldRunDemoGameplay()) {
            return demoInputPlayer.getInputMask();
        }
        return 0;
    }

    /**
     * Returns whether the current demo state should run input playback/physics.
     * ROM: gameplay begins only after the post-load fade-in completes.
     */
    public boolean shouldRunDemoGameplay() {
        return state == State.DEMO_PLAYING || state == State.DEMO_FADING_OUT;
    }

    /**
     * During the initial demo fade-in, Sonic/object sprites should remain visible
     * while the level tiles are still under the black fade overlay.
     */
    public boolean shouldRenderDemoSpritesOverFade() {
        return state == State.DEMO_FADE_IN;
    }

    /**
     * Only the hidden post-load delay should advance non-player demo scene
     * state. The visible fade-in should stay static.
     */
    public boolean shouldAdvanceFrozenDemoScene() {
        return state == State.DEMO_LOAD_DELAY;
    }

    /**
     * True during demo fadeout: camera/scroll should freeze but physics continues.
     */
    public boolean isScrollFrozen() {
        return scrollFrozen;
    }

    /**
     * Returns the zone index for the current credit's demo.
     */
    public int getDemoZone() {
        if (creditsNum < Sonic1CreditsDemoData.DEMO_CREDITS) {
            return Sonic1CreditsDemoData.DEMO_ZONE[creditsNum];
        }
        return 0;
    }

    /**
     * Returns the act index for the current credit's demo.
     */
    public int getDemoAct() {
        if (creditsNum < Sonic1CreditsDemoData.DEMO_CREDITS) {
            return Sonic1CreditsDemoData.DEMO_ACT[creditsNum];
        }
        return 0;
    }

    /**
     * Returns the player start X for the current credit's demo.
     */
    public int getDemoStartX() {
        if (creditsNum < Sonic1CreditsDemoData.DEMO_CREDITS) {
            return Sonic1CreditsDemoData.START_X[creditsNum];
        }
        return 0;
    }

    /**
     * Returns the player start Y for the current credit's demo.
     */
    public int getDemoStartY() {
        if (creditsNum < Sonic1CreditsDemoData.DEMO_CREDITS) {
            return Sonic1CreditsDemoData.START_Y[creditsNum];
        }
        return 0;
    }

    /**
     * Returns true if the current credit demo is the LZ demo (credit 3),
     * which requires special lamppost state setup.
     * ROM: sonic.asm:4153 checks v_creditsnum==4 (already incremented),
     * which corresponds to original credit 3 (LZ Act 3).
     */
    public boolean isLzDemo() {
        return creditsNum == 3;
    }

    /**
     * ROM {@code tst.l (v_plc_buffer).w} (s1disasm/sonic.asm:3888): whether any
     * PLC descriptor or in-flight Nemesis decode remains in the logical FIFO.
     */
    private boolean plcBufferOccupied() {
        Sonic1PlcService plcService = GameServices.module().getGameService(Sonic1PlcService.class);
        return plcService != null && plcService.isBusy();
    }

    private Sonic1TitleScreenDataLoader resolveTitleScreenDataLoader() {
        TitleScreenProvider provider = GameServices.module().getTitleScreenProvider();
        if (provider instanceof Sonic1TitleScreenManager manager) {
            return manager.getDataLoader();
        }
        Sonic1TitleScreenDataLoader fallback = new Sonic1TitleScreenDataLoader();
        fallback.loadData();
        return fallback;
    }
}
