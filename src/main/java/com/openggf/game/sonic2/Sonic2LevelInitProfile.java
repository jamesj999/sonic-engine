package com.openggf.game.sonic2;

import com.openggf.game.AbstractLevelInitProfile;
import com.openggf.game.InitStep;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.resources.Sonic2RuntimePlcPublisher;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.timing.Sonic2LevelMusicScheduler;
import com.openggf.game.sonic2.timing.Sonic2PreBgmTimingModel;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.SkippedPresentationPlcLifecycle;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.Rom;

import java.util.List;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Sonic 2 level initialization profile.
 * <p>
 * Aligned to the S2 {@code Level:} routine at {@code s2.asm:4753} (57 steps
 * across phases A-J). The teardown steps undo the state set up by that routine.
 * <p>
 * S2-specific characteristics:
 * <ul>
 *   <li>Single PLC queue ({@code LoadPLC}/{@code RunPLC_RAM})</li>
 *   <li>Dual-path collision model ({@code LoadCollisionIndexes} → PRIMARY + SECONDARY)</li>
 *   <li>Zone-specific setup: CPZ pylon ({@code ObjID_CPZPylon}), OOZ oil surface</li>
 *   <li>Player spawn BEFORE game state init (phases G→H)</li>
 *   <li>{@code Level_started_flag} set AFTER title card exit (phase J, step 56)</li>
 * </ul>
 *
 * @see <a href="docs/plans/2026-02-27-rom-driven-init-profiles-design.md">
 *      Design doc: Sonic 2 Level Init Profile (57 steps)</a>
 */
public class Sonic2LevelInitProfile extends AbstractLevelInitProfile {
    private static final Logger LOGGER = Logger.getLogger(
            Sonic2LevelInitProfile.class.getName());
    private static final int LEVEL_ENTRY_FADE_OUT_REQUEST = 0xF9;
    /** Fixed length of the s2.asm:5060-5066 title-card leave loop. */
    private static final int TITLE_CARD_LEAVE_LOOP_FRAMES = 25;

    /**
     * {@code Level:} dispatches {@code RunObjects} once at s2.asm:5006 -- after
     * {@code InitPlayers} (s2.asm:4945) and after the {@code ObjectsManager} /
     * {@code RingsManager} / {@code SpecialCNZBumpers} calls at s2.asm:5003-5005
     * -- before it arms the leave flags at s2.asm:5056-5058 and enters the
     * leave loop. That pass is not preceded by a {@code WaitForVint} of its
     * own: the previous vertical interrupt is the one at s2.asm:4923-4924, back
     * when the players did not yet exist. So the omitted presentation runs
     * {@code 1 + 25} player object passes but only the leave loop's 25
     * V-blanks, and the first of those V-blanks drains the queue built by this
     * leading pass.
     */
    private static final int TITLE_CARD_LEADING_OBJECT_PASSES = 1;

    private final Sonic2LevelEventManager levelEventManager;
    private final Sonic2PlayerArtModeAuthority playerArtModeAuthority;
    private final Sonic2LevelMusicScheduler levelMusicScheduler;
    private boolean priorWaterFlag;

    public Sonic2LevelInitProfile(Sonic2LevelEventManager levelEventManager) {
        this(levelEventManager, () -> Sonic2PlayerArtModeAuthority.onePlayer(
                levelEventManager.getPlayerCharacter()).initialLifePlc(),
                new Sonic2LevelMusicScheduler());
    }

    public Sonic2LevelInitProfile(Sonic2LevelEventManager levelEventManager,
                                  Sonic2PlayerArtModeAuthority playerArtModeAuthority) {
        this(levelEventManager, playerArtModeAuthority,
                new Sonic2LevelMusicScheduler());
    }

    public Sonic2LevelInitProfile(Sonic2LevelEventManager levelEventManager,
                                  Sonic2PlayerArtModeAuthority playerArtModeAuthority,
                                  Sonic2LevelMusicScheduler levelMusicScheduler) {
        this.levelEventManager = levelEventManager;
        this.playerArtModeAuthority = playerArtModeAuthority;
        this.levelMusicScheduler = levelMusicScheduler;
    }

    @Override
    public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
        List<InitStep> steps = buildCoreSteps(ctx);
        if (!isPreviewCapture(ctx)) {
            steps.remove(1);
            steps.add(1, ioStep("ConfigureAudio",
                    "Configure the S2 request service before Level entry commands",
                    () -> GameServices.level().configureAudio()));
            steps.add(2, new InitStep("QueueLevelEntryFadeOut",
                    "S2 Level loc_3EC4: PlaySound(MusID_FadeOut) before ClearPLC",
                    () -> GameServices.level().beginLevelEntry()));
            steps.add(3, new InitStep("ScheduleLevelMusic",
                    "S2 Level load: arm the source-derived Level_PlayBgm boundary",
                    () -> scheduleLevelMusic(ctx)));
        }
        int initialPlcIndex = isPreviewCapture(ctx) ? 2 : 5;
        steps.add(initialPlcIndex, new InitStep("QueueInitialPlcs",
                "S2 Level: ClearPLC, level-header primary LoadPLC, LoadPLC Std2",
                () -> queueInitialPlcs(ctx)));
        // Level_ClrRam wipes MiscLevelVariables after the level's LoadPLC calls
        // (s2disasm/s2.asm:4806-4809), and RNG_seed lives inside that block
        // (s2disasm/s2.constants.asm:1412-1421,1467). Every act load therefore
        // starts from a zero seed, which RandomNumber's zero-sanity check turns
        // into $2A6D365A on the act's first draw (s2disasm/s2.asm:3975-3979).
        // Without this the seed carried across acts and every S2 RandomNumber
        // consumer downstream -- Obj28_InitRandom animal choice, the Egg Prison
        // release offsets, boss explosion offsets -- read a stream shifted by
        // the previous acts' draw count. Keep this S2-owned: S1 has the same
        // rule in its own profile and S3K clears a different range.
        steps.add(initialPlcIndex + 1, new InitStep("ResetRng",
                "S2 Level_ClrRam: clear RNG_seed with MiscLevelVariables",
                () -> {
                    var rng = GameServices.rngOrNull();
                    if (rng != null) {
                        rng.setSeed(0L);
                    }
                }));
        if (ctx.isIncludePostLoadAssembly()) {
            steps.addAll(postLoadAssemblySteps(ctx));
        }
        return List.copyOf(steps);
    }

    @Override
    public void beginLevelEntry() {
        levelMusicScheduler.cancel();
        var level = GameServices.levelOrNull();
        var water = GameServices.waterOrNull();
        priorWaterFlag = level != null && level.getCurrentLevel() != null
                && water != null
                && water.isLiveWaterFlagSet(
                        level.getRomZoneId(), level.getRomActId());
        // Level (docs/s2disasm/s2.asm:4753-4765) writes MusID_FadeOut through
        // PlaySound/Sound_Queue.SFX0 before ClearPLC and Pal_FadeToBlack. The
        // negative Demo_mode_flag branch skips this only for credits demos;
        // Sonic 2's credits have no demo-level path in OpenGGF.
        GameServices.audio().playSfx(LEVEL_ENTRY_FADE_OUT_REQUEST);
    }

    private void scheduleLevelMusic(LevelLoadContext ctx) {
        var level = GameServices.levelOrNull();
        if (level == null) {
            levelMusicScheduler.cancel();
            return;
        }
        try {
            var music = level.prepareCurrentLevelMusic();
            if (music.isEmpty()) {
                levelMusicScheduler.cancel();
                return;
            }
            var region = Sonic2PreBgmTimingModel.Region.fromConfiguration(
                    GameServices.configuration().getString(SonicConfiguration.REGION));
            // The model costs ROM routines that key off Current_Zone /
            // Current_Act, so it needs the ROM ids, not the zone registry's
            // progression indices. This step runs before LoadLevelData, so the
            // loaded Level cannot supply them yet; read the destination pair
            // from the ROM's own LevelOrder table (s2disasm/s2.asm:28041,
            // read at s2.asm:27986) the way Sonic2.getZoneAct does.
            Rom rom = GameServices.rom().getRom();
            int levelOrderEntry = Sonic2Constants.LEVEL_SELECT_ADDR
                    + ctx.getLevelIndex() * 2;
            int romZoneId = Byte.toUnsignedInt(rom.readByte(levelOrderEntry));
            int romActId = Byte.toUnsignedInt(rom.readByte(levelOrderEntry + 1));
            var resolution = Sonic2PreBgmTimingModel.resolve(
                    rom, romZoneId, romActId,
                    playerArtModeAuthority.initialLifePlc(),
                    region, priorWaterFlag);
            if (resolution instanceof Sonic2PreBgmTimingModel.Resolved resolved) {
                // The engine reaches this seam where the old eager InitAudio
                // request ran.  The model costs the interrupt-masked body from
                // ClearScreen through Level_PlayBgm (s2.asm:4767-4911).
                levelMusicScheduler.arm(music.getAsInt(),
                        resolved.evidence().terminalRowBucket());
                return;
            }
            var unresolved = (Sonic2PreBgmTimingModel.Unresolved) resolution;
            levelMusicScheduler.cancel();
            LOGGER.warning(() -> "Refusing to arm S2 Level_PlayBgm: "
                    + unresolved.reason());
        } catch (IOException | RuntimeException failure) {
            levelMusicScheduler.cancel();
            LOGGER.warning(() -> "Refusing to arm S2 Level_PlayBgm: "
                    + failure.getMessage());
        }
    }

    @Override
    public void serviceLevelLoadVBlank() {
        levelMusicScheduler.serviceVBlank().ifPresent(musicId -> {
            if (GameServices.levelOrNull() != null) {
                GameServices.levelOrNull().publishPreparedLevelMusic(musicId);
            }
        });
    }

    @Override
    public void cancelPendingLevelLoadWork() {
        levelMusicScheduler.cancel();
    }

    @Override
    public boolean isLevelMusicPublicationPending() {
        return levelMusicScheduler.pending();
    }

    private void queueInitialPlcs(LevelLoadContext ctx) {
        if (ctx.getLevel() == null) {
            return;
        }
        Sonic2PlcService plcService = GameServices.module().getGameService(Sonic2PlcService.class);
        if (plcService == null) {
            return;
        }
        try {
            int zone = ctx.getLevel().getZoneIndex();
            int offset = Sonic2Constants.LEVEL_DATA_DIR + zone * Sonic2Constants.LEVEL_DATA_DIR_ENTRY_SIZE;
            int primary = GameServices.rom().getRom().readByte(offset) & 0xFF;
            // Retail 1P bypasses this load unless Player_mode == 2. OpenGGF
            // currently has no separate two-player graphics flag owner, so the
            // represented Tails-alone path selects the native Tails life cue.
            java.util.OptionalInt lifePlc = playerArtModeAuthority.initialLifePlc();
            java.util.List<Sonic2PlcService.Operation> operations = new java.util.ArrayList<>();
            operations.add(Sonic2PlcService.clearOperation());
            if (primary != 0) operations.add(Sonic2PlcService.appendOperation(primary));
            operations.add(Sonic2PlcService.appendOperation(Sonic2Constants.PLC_STD2));
            lifePlc.ifPresent(id -> operations.add(Sonic2PlcService.appendOperation(id)));
            Sonic2PlcService.Operation[] transaction = operations.toArray(Sonic2PlcService.Operation[]::new);
            if (GameServices.module().getObjectArtProvider() instanceof Sonic2ObjectArtProvider artProvider
                    && GameServices.levelOrNull() != null
                    && GameServices.levelOrNull().getObjectRenderManager() != null) {
                Sonic2RuntimePlcPublisher.transact(artProvider, plcService,
                        GameServices.levelOrNull()::refreshObjectArtPatterns, transaction);
            } else {
                plcService.transact(transaction);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to queue S2 initial PLCs", failure);
        }
    }

    @Override
    public void completeInitialPresentationPlcs() {
        Sonic2PlcService plcService =
                GameServices.module().getGameService(Sonic2PlcService.class);
        if (plcService != null
                && GameServices.levelOrNull() != null
                && GameServices.levelOrNull().getCurrentLevel() != null) {
            try {
                completeInitialPresentationPlcs(
                        GameServices.rom().getRom(),
                        plcService,
                        GameServices.levelOrNull().getCurrentLevel().getZoneIndex());
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Failed to access S2 ROM for initial presentation PLCs",
                        failure);
            }
        }
    }

    static void completeInitialPresentationPlcs(
            Rom rom, Sonic2PlcService plcService, int zoneIndex) {
        SkippedPresentationPlcLifecycle.drain(
                plcService, PlcLifecyclePhase.LEVEL_TITLE_CARD,
                plcService::isBusy);
        try {
            int secondary = Sonic2PlcLoader.getZonePlcIds(rom, zoneIndex)[1];
            if (secondary != 0) {
                plcService.append(secondary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Failed to append S2 post-title secondary PLC", failure);
        }
        // The secondary PLC enters the queue from loadZoneBlockMaps
        // (docs/s2disasm/s2.asm:20103-20110), which Level: calls at s2.asm:4939 --
        // still inside the title-card sequence. The title-card leave loop
        // (s2.asm:5060-5066) then runs VintID_TitleCard + RunPLC_RAM every frame
        // until TitleCard_Background unloads, before Level_StartGame /
        // Level_MainLoop (s2.asm:5082-5087). That loop is exactly 25 frames long,
        // fixed by the Obj34 leave routines and their RAM slot order
        // (s2.asm:27368-27374): TitleCard_Left runs Obj34_LeftPartOut for 5 frames
        // from titlecard_location $A (s2.asm:5058, 27518-27540: $A -> 6 -> 2 -> 0
        // -> -4 -> delete) and hands TitleCard_Bottom routine $10; Bottom runs
        // Obj34_BottomPartOut for frames 6..16, stepping titlecard_location by 4
        // until it reaches $28 (s2.asm:27542-27551); Background sits earlier in
        // slot order so it first sees routine $12 on frame 17 and runs
        // Obj34_BackgroundOutInit/Out for 9 frames, $F0 stepping by -$20 to -$30
        // (s2.asm:27587-27604), deleting itself on frame 25. A headless load omits
        // that presentation, so replay its PLC service here.
        SkippedPresentationPlcLifecycle.runIterations(
                plcService, PlcLifecyclePhase.LEVEL_TITLE_CARD,
                TITLE_CARD_LEAVE_LOOP_FRAMES);
    }

    @Override
    public int preLevelFadeOutFrames() {
        // Level: runs ClearPLC then Pal_FadeToBlack (s2.asm:4764-4765) before
        // it clears the screen, decompresses the title-card art and creates
        // Obj34 at s2.asm:4912. Pal_FadeToBlack is an unconditional
        // "move.w #$15,d4" dbf loop with one "bsr.w WaitForVint" per pass
        // (s2.asm:3370-3383), so it is 22 counted V-blank rows during which
        // the title card does not yet exist. Same shape as S1's PaletteFadeOut
        // (Sonic1LevelInitProfile.preLevelFadeOutFrames) -- the returning
        // level's card and its art therefore start 22 rows after the
        // results-screen handoff, not at it.
        return 22;
    }

    /**
     * The 25-frame title-card leave loop (s2.asm:5060-5066) runs after
     * InitPlayers (s2.asm:4945), so it is exactly the omitted presentation
     * window in which the player objects animate and load their DPLCs -- and
     * the leading s2.asm:5006 {@code RunObjects} pass, which runs with the
     * players already created, belongs to that window too.
     */
    @Override
    public int skippedPresentationPlayableFrames() {
        return TITLE_CARD_LEADING_OBJECT_PASSES + TITLE_CARD_LEAVE_LOOP_FRAMES;
    }

    /** {@inheritDoc} See {@link #TITLE_CARD_LEADING_OBJECT_PASSES}. */
    @Override
    public int skippedPresentationPlayableFramesBeforeFirstVBlank() {
        return TITLE_CARD_LEADING_OBJECT_PASSES;
    }

    @Override
    protected InitStep levelEventTeardownStep() {
        return new InitStep("ResetS2LevelEvents",
            "Undoes S2 zone event handlers (HTZ earthquake, boss arenas, CPZ/ARZ/CNZ events)",
            levelEventManager::resetState);
    }

    @Override
    protected InitStep perTestLeadStep() {
        return new InitStep("ResetS2LevelEvents",
            "Undoes S2 zone event handlers and object-level static state for test isolation",
            () -> {
                levelEventManager.resetState();
                ButtonVineTriggerManager.reset();
            });
    }
}
