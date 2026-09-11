package com.openggf.tests.trace.s3k;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.objects.CnzBalloonInstance;
import com.openggf.game.sonic3k.objects.CnzMinibossInstance;
import com.openggf.game.sonic3k.objects.CnzMinibossScrollControlInstance;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSignpostInstance;
import com.openggf.level.Block;
import com.openggf.level.Level;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kCnzTraceReplay extends AbstractTraceReplayTest {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s3k/cnz");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;
    private static final int FRAME_FIRST_CARRY_RIGHT_PULSE = 31;
    private static final int FRAME_DELAYED_RIGHT_REACHES_TAILS = 123;
    private static final int FRAME_FIRST_MAIN_JUMP = 142;
    private static final int FRAME_CNZ_CLAMER_SLOT_PRESSURE = 569;
    private static final int FRAME_CNZ_BLOOMINATOR_PROJECTILE_PRESSURE = 621;
    private static final int FRAME_HORIZONTAL_SPRING_LANDING_HANDOFF = 3649;
    private static final int FRAME_TAILS_RIGHT_WALL_CEILING_SEPARATION = 5236;
    private static final int FRAME_CNZ_MINIBOSS_GO3_HANDOFF = 14712;
    private static final int FRAME_CNZ_MINIBOSS_SECOND_CLOSEGO = 15004;
    private static final int FRAME_CNZ_MINIBOSS_SECOND_BODY_PASS = 15059;
    private static final int FRAME_CNZ_MINIBOSS_TAILS_HURT_CONTACT = 15096;
    private static final int FRAME_CNZ_MINIBOSS_TAILS_HURT_HISTORY_PUSH = 15194;
    private static final int FRAME_CNZ_MINIBOSS_POST_OPEN_STORED_CHANGEDIR = 15409;
    private static final int FRAME_CNZ_MINIBOSS_LOOK_DOWN_CAMERA_PAN = 15569;
    private static final int FRAME_CNZ_MINIBOSS_POST_BOSS_X_CLAMP = 15723;

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_3K;
    }

    @Override
    protected int zone() {
        return 0x03; // CNZ
    }

    @Override
    protected int act() {
        // 0-based: 0 = Act 1. The recorder's metadata.json writes "act": 1
        // (1-based). AbstractTraceReplayTest.validateMetadata does not
        // cross-check the act, so the asymmetry is harmless; documenting
        // here so the next reader doesn't chase the off-by-one.
        return 0x00;
    }

    @Override
    protected Path traceDirectory() {
        return TRACE_DIR;
    }

    /**
     * CNZ starts Sonic on the right edge of the opening platform. ROM frame 0
     * therefore runs the ordinary grounded routine-2 path: it selects Wait
     * before {@code Player_AnglePos} detaches Sonic, then animation dispatch
     * publishes Wait's first {@code $BA} mapping without applying air gravity
     * ({@code sonic3k.asm:24740-24771};
     * {@code General/Sprites/Sonic/Anim - Sonic S3.asm:AniSonic05}).
     */
    @Test
    void freshCnzStartRunsGroundedWalkOffBeforeCarryInit() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            assertEquals(0, replay.replayStart().startingTraceIndex(),
                    "CNZ lifecycle regression requires the fresh frame-0 start");

            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    0);

            AbstractPlayableSprite sonic = replay.fixture().sprite();
            AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
            SidekickCpuController tailsCpu = tails.getCpuController();

            assertTrue(sonic.getAir(), "frame 0 terrain walk-off must mark Sonic airborne");
            assertEquals((short) 0x0000, sonic.getYSpeed(),
                    "frame 0 grounded dispatch must not apply air gravity");
            assertEquals(0x05, sonic.getAnimationId(),
                    "frame 0 grounded idle selection must choose Wait");
            assertEquals(0xBA, sonic.getMappingFrame(),
                    "frame 0 Wait dispatch must publish its first mapping");
            assertEquals(SidekickCpuController.State.CARRY_INIT, tailsCpu.getState(),
                    "frame 0 must publish native Tails CPU carry-init routine $0C");
            assertEquals((short) 0x0038, tails.getYSpeed(),
                    "frame 0 carry-init arm still runs ordinary Tails air gravity");
            assertFalse(tailsCpu.isFlyingCarrying(),
                    "frame 0 routine $0C is armed but has not entered the carry body");

            driveReplayFrame(
                    replay.trace(), replay.fixture(), replay.trace().getFrame(0), 1);

            assertEquals(0x22, sonic.getAnimationId(),
                    "frame 1 carry body must select the carried animation");
            assertEquals(0x91, sonic.getMappingFrame(),
                    "frame 1 carry body must publish the carried mapping");
            assertTrue(sonic.isObjectControlled(),
                    "frame 1 native object_control=$03 must own Sonic");
            assertTrue(sonic.isObjectControlAllowsCpu(),
                    "frame 1 native object_control=$03 must retain bit-1 CPU allowance");
            assertTrue(sonic.isObjectControlSuppressesMovement(),
                    "frame 1 native object_control=$03 must suppress normal movement");
        }
    }

    @Test
    void productionSetupAndFirstOrdinaryFrameMatchRecordedBalloonEpochs() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            assertEquals(List.of(0x11, 0x38, 0x38, 0xA8), liveOpeningBalloonAngles());
            assertEquals(0x14A7ABBBL, GameServices.rng().getSeed());
            int vblankBefore = GameServices.level().getObjectManager().getVblaCounter();

            driveReplayFrame(
                    replay.trace(), replay.fixture(),
                    replay.replayStart().hasSeededTraceState()
                            ? replay.trace().getFrame(replay.replayStart().seededTraceIndex())
                            : null,
                    replay.replayStart().startingTraceIndex());

            List<Integer> recorded = replay.trace().preTraceObjectSnapshots().stream()
                    .filter(snapshot -> snapshot.objectType() == 0x41)
                    .sorted(Comparator.comparingInt(TraceEvent.ObjectStateSnapshot::slot))
                    .map(snapshot -> snapshot.fields().angle() & 0xFF)
                    .toList();
            assertEquals(recorded, liveOpeningBalloonAngles());
            assertEquals(1, GameServices.level().getFrameCounter());
            assertEquals(vblankBefore + 1,
                    GameServices.level().getObjectManager().getVblaCounter());
            assertEquals(0x14A7ABBBL, GameServices.rng().getSeed(),
                    "the ordinary frame advances balloon angles without consuming RNG");
        }
    }

    @ParameterizedTest
    @EnumSource(MetadataVariant.class)
    void productionSetupTokenIsInvariantToTraceMetadata(MetadataVariant variant) throws Exception {
        Path variantDirectory = metadataVariantDirectory(variant);
        TraceData trace = TraceData.load(variantDirectory);

        try (BootstrappedCnzReplay replay =
                     bootstrappedCnzReplay(trace, false, false)) {
            assertFalse(GameServices.level().hasPendingInitialProcessSpritesPass(), variant.name());
            assertFalse(GameServices.level().consumePendingInitialProcessSpritesPass(), variant.name());
        }
    }

    @Test
    void metadataCannotCreateSetupAuthorityWhenFreshLoadTokenWasDiscarded() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);

        try (BootstrappedCnzReplay replay =
                     bootstrappedCnzReplay(trace, true, false)) {
            assertFalse(GameServices.level().hasPendingInitialProcessSpritesPass());
            assertFalse(GameServices.level().consumePendingInitialProcessSpritesPass());
        }
    }

    @Test
    void bootstrapConvergesWithFixedMetadataProductionConsumerState() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        RuntimeState production = fixedMetadataRuntimeState(trace, false);
        TestEnvironment.resetAll();
        RuntimeState bootstrap = fixedMetadataRuntimeState(trace, true);

        assertEquals(production, bootstrap,
                "byte-identical metadata must produce the same concrete state whether "
                        + "production or replay consumes the fresh-load token");
    }

    @Override
    protected String additionalEngineObjectDiagnostics(ObjectManager om) {
        return combineDiagnostics(
                summariseCnzMinibossLayoutDiagnostics(om),
                summariseS3kEndSignDiagnostics(om));
    }

    private static String combineDiagnostics(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " | " + second;
    }

    private String summariseS3kEndSignDiagnostics(ObjectManager om) {
        if (om == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ObjectInstance instance : om.getActiveObjects()) {
            if (!(instance instanceof S3kSignpostInstance || instance instanceof S3kResultsScreenObjectInstance)
                    || !(instance instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            parts.add(String.format("s3k-end s%d %s @%04X,%04X %s",
                    aoi.getSlotIndex(),
                    aoi.getName(),
                    instance.getX() & 0xFFFF,
                    instance.getY() & 0xFFFF,
                    aoi.traceDebugDetails()));
        }
        parts.sort(String::compareTo);
        return parts.isEmpty() ? "s3k-end none" : String.join(" | ", parts);
    }

    private String summariseCnzMinibossLayoutDiagnostics(ObjectManager om) {
        if (!(GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager)) {
            return "cnz-layout unavailable";
        }
        Sonic3kCNZEvents events = manager.getCnzEvents();
        var level = GameServices.level() != null ? GameServices.level().getCurrentLevel() : null;
        int fgLandingCell = -1;
        int fgBelowCell = -1;
        int bgSourceCell = -1;
        if (level != null && level.getMap() != null) {
            fgLandingCell = level.getMap().getValue(0, 0x65, 5) & 0xFF;
            fgBelowCell = level.getMap().getValue(0, 0x65, 6) & 0xFF;
            bgSourceCell = level.getMap().getValue(1, 6, 3) & 0xFF;
        }
        AbstractPlayableSprite sidekick = null;
        SpriteManager spriteManager = GameServices.sprites();
        if (spriteManager != null && !spriteManager.getSidekicks().isEmpty()) {
            sidekick = spriteManager.getSidekicks().getFirst();
        }
        boolean bgCollision = GameServices.gameState() != null
                && GameServices.gameState().isBackgroundCollisionFlag();
        String scrollControl = "none";
        if (om != null) {
            for (ObjectInstance instance : om.getActiveObjects()) {
                if (instance instanceof CnzMinibossScrollControlInstance scroll
                        && instance instanceof AbstractObjectInstance aoi) {
                    scrollControl = String.format("s%d @%04X,%04X %s",
                            aoi.getSlotIndex(),
                            instance.getX() & 0xFFFF,
                            instance.getY() & 0xFFFF,
                            scroll.traceDebugDetails());
                    break;
                }
            }
        }
        return String.format(
                "cnz-layout bgR=%02X fg5=%s bgCol=%s scroll=%04X/%08X sig=%s fg65,5=%02X fg65,6=%02X bg6,3=%02X pend=%s:%s last=%s hist=%s sc=%s",
                events.getBackgroundRoutine() & 0xFF,
                events.isEventsFg5(),
                bgCollision,
                events.getBossScrollOffsetY() & 0xFFFF,
                events.getBossScrollVelocityY(),
                events.isMinibossDefeatSignalForScrollControlPending(),
                fgLandingCell,
                fgBelowCell,
                bgSourceCell,
                events.isArenaChunkDestructionQueued(),
                formatArenaClearDiagnostics(level, events.getPendingArenaChunkX(), events.getPendingArenaChunkY(), sidekick),
                formatArenaClearDiagnostics(level, events.getLastArenaChunkClearX(), events.getLastArenaChunkClearY(), sidekick),
                formatArenaClearHistoryDiagnostics(level, events.getArenaClearHistorySnapshot(), sidekick),
                scrollControl);
    }

    private String formatArenaClearHistoryDiagnostics(Level level, int[] history,
                                                      AbstractPlayableSprite sidekick) {
        if (history.length == 0) {
            return "none";
        }
        List<String> entries = new ArrayList<>();
        for (int i = 0; i + 2 < history.length; i += 3) {
            entries.add(String.format("f%d:%s",
                    history[i],
                    formatArenaClearDiagnostics(level, history[i + 1], history[i + 2], sidekick)));
        }
        return String.join(";", entries);
    }

    private String formatArenaClearDiagnostics(Level level, int snappedWorldX, int snappedWorldY,
                                               AbstractPlayableSprite sidekick) {
        if (level == null || level.getMap() == null || snappedWorldX == 0 || snappedWorldY == 0) {
            return "none";
        }
        int rawWorldX = snappedWorldX - 0x10;
        int rawWorldY = snappedWorldY - 0x10;
        int blockPixelSize = level.getBlockPixelSize();
        int blockX = Math.floorDiv(rawWorldX, blockPixelSize);
        int blockY = Math.floorDiv(rawWorldY, blockPixelSize);
        if (blockX < 0 || blockY < 0
                || blockX >= level.getLayerWidthBlocks(0)
                || blockY >= level.getLayerHeightBlocks(0)) {
            return String.format("snap=%04X,%04X raw=%04X,%04X out",
                    snappedWorldX & 0xFFFF,
                    snappedWorldY & 0xFFFF,
                    rawWorldX & 0xFFFF,
                    rawWorldY & 0xFFFF);
        }
        int blockIndex = level.getMap().getValue(0, blockX, blockY) & 0xFF;
        String desc = "bad";
        int chunkX = -1;
        int chunkY = -1;
        if (blockIndex > 0 && blockIndex < level.getBlockCount()) {
            Block block = level.getBlock(blockIndex);
            chunkX = ((rawWorldX & (blockPixelSize - 1)) / 0x10) & ~1;
            chunkY = ((rawWorldY & (blockPixelSize - 1)) / 0x10) & ~1;
            if (chunkX + 1 < block.getGridSide() && chunkY + 1 < block.getGridSide()) {
                desc = String.format("%04X/%04X/%04X/%04X",
                        block.getChunkDesc(chunkX, chunkY).get() & 0xFFFF,
                        block.getChunkDesc(chunkX + 1, chunkY).get() & 0xFFFF,
                        block.getChunkDesc(chunkX, chunkY + 1).get() & 0xFFFF,
                        block.getChunkDesc(chunkX + 1, chunkY + 1).get() & 0xFFFF);
            }
        }
        return String.format("snap=%04X,%04X raw=%04X,%04X b=%02X[%02X,%02X] c=%d,%d desc=%s tail=%s",
                snappedWorldX & 0xFFFF,
                snappedWorldY & 0xFFFF,
                rawWorldX & 0xFFFF,
                rawWorldY & 0xFFFF,
                blockIndex,
                blockX,
                blockY,
                chunkX,
                chunkY,
                desc,
                arenaClearIncludesSidekickFoot(level, blockX, blockY, chunkX, chunkY, sidekick));
    }

    private boolean arenaClearIncludesSidekickFoot(Level level, int clearBlockX, int clearBlockY,
                                                   int clearChunkX, int clearChunkY,
                                                   AbstractPlayableSprite sidekick) {
        if (sidekick == null || clearChunkX < 0 || clearChunkY < 0) {
            return false;
        }
        int footY = (sidekick.getCentreY() & 0xFFFF) + sidekick.getYRadius();
        int leftFootX = (sidekick.getCentreX() & 0xFFFF) - sidekick.getXRadius();
        int rightFootX = (sidekick.getCentreX() & 0xFFFF) + sidekick.getXRadius();
        return arenaClearIncludesWorldPoint(level, clearBlockX, clearBlockY, clearChunkX, clearChunkY, leftFootX, footY)
                || arenaClearIncludesWorldPoint(level, clearBlockX, clearBlockY, clearChunkX, clearChunkY, rightFootX, footY);
    }

    private boolean arenaClearIncludesWorldPoint(Level level, int clearBlockX, int clearBlockY,
                                                 int clearChunkX, int clearChunkY,
                                                 int worldX, int worldY) {
        int blockPixelSize = level.getBlockPixelSize();
        int blockX = Math.floorDiv(worldX, blockPixelSize);
        int blockY = Math.floorDiv(worldY, blockPixelSize);
        int chunkX = (worldX & (blockPixelSize - 1)) / 0x10;
        int chunkY = (worldY & (blockPixelSize - 1)) / 0x10;
        return blockX == clearBlockX
                && blockY == clearBlockY
                && chunkX >= clearChunkX
                && chunkX <= clearChunkX + 1
                && chunkY >= clearChunkY
                && chunkY <= clearChunkY + 1;
    }

    @Test
    void traceReplayDoesNotPulseCarryRightOnFrame1() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(replay.trace(), replay.fixture(), replay.replayStart(), 1);

            AbstractPlayableSprite tails = GameServices.sprites().getRegisteredSidekicks().getFirst();
            assertEquals(SidekickCpuController.State.CARRYING, tails.getCpuController().getState(),
                    "Frame 1: ROM Tails_CPU_routine has reached $0E carrying");
            assertEquals(traceFrame(replay.trace(), 1).sidekick().xSpeed(),
                    tails.getXSpeed(),
                    "Frame 1: loc_13FFA reads Level_frame_counter low byte=$02, so no RIGHT pulse yet");
        }
    }

    @Test
    void traceReplayAppliesFirstCarryRightPulseOnFrame31() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_FIRST_CARRY_RIGHT_PULSE);

            AbstractPlayableSprite tails = GameServices.sprites().getRegisteredSidekicks().getFirst();
            assertEquals(SidekickCpuController.State.CARRYING, tails.getCpuController().getState(),
                    "Frame 31: ROM Tails_CPU_routine is $0E carrying");
            assertEquals(traceFrame(replay.trace(), FRAME_FIRST_CARRY_RIGHT_PULSE).sidekick().xSpeed(),
                    tails.getXSpeed(),
                    "Frame 31: loc_13FFA should pulse RIGHT when "
                            + "(Level_frame_counter+1)&$1F == 0, then Tails_InputAcceleration_Freespace "
                            + "raises x_vel to $118");
        }
    }

    @Test
    void traceReplayAppliesDelayedRightInputToTailsOnFrame123() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_DELAYED_RIGHT_REACHES_TAILS);

            AbstractPlayableSprite tails = GameServices.sprites().getRegisteredSidekicks().getFirst();
            assertEquals(SidekickCpuController.State.NORMAL, tails.getCpuController().getState(),
                    "Frame 123: ROM Tails_CPU_routine is $06 normal follow");
            assertEquals(traceFrame(replay.trace(), FRAME_DELAYED_RIGHT_REACHES_TAILS).sidekick().xSpeed(),
                    tails.getXSpeed(),
                    "Frame 123: loc_13D4A should replay Sonic's delayed RIGHT input through "
                            + "Tails_InputAcceleration_Freespace");
        }
    }

    @Test
    void traceReplayAppliesFirstMainJumpOnFrame142() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_FIRST_MAIN_JUMP);

            TraceFrame expected = traceFrame(replay.trace(), FRAME_FIRST_MAIN_JUMP);
            AbstractPlayableSprite sonic = replay.fixture().sprite();
            assertEquals(expected.input(), AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                    "Frame 142 trace row should carry RIGHT+jump input");
            assertEquals(expected.x(), sonic.getCentreX() & 0xFFFF,
                    "Sonic_Jump consumes Ctrl_1_pressed_logical before ground SpeedToPos");
            assertEquals(expected.ySpeed(), sonic.getYSpeed(),
                    "Sonic_Jump should apply the level-ground -$680 y_vel on the first pressed frame");
            assertEquals(expected.air(), sonic.getAir(),
                    "Sonic_Jump should set Status_InAir on the first pressed frame");
            assertEquals(expected.rolling(), sonic.getRolling(),
                    "Sonic_Jump should enter roll/jump radii when jumping from standing");
        }
    }

    @Test
    void traceReplayCnzMinibossTailsPushBypassUsesHurtLatchedLeaderInput() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_CNZ_MINIBOSS_TAILS_HURT_HISTORY_PUSH);

            TraceFrame expected = traceFrame(replay.trace(), FRAME_CNZ_MINIBOSS_TAILS_HURT_HISTORY_PUSH);
            AbstractPlayableSprite tails = GameServices.sprites().getRegisteredSidekicks().getFirst();
            assertEquals(expected.sidekick().gSpeed(), tails.getGSpeed(),
                    "Frame 15194: ROM loc_122BE records the previous Ctrl_1_logical during "
                            + "Sonic hurt knockback, so loc_13DD0's current Status_Push bypass "
                            + "must not replay live RIGHT into Tails ground acceleration "
                            + "(docs/skdisasm/sonic3k.asm:22132,24449-24467,26702-26705)");
        }
    }

    @Test
    void traceReplayCnzMinibossTopHurtsTailsOnNativeNextFrameBoundary() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_CNZ_MINIBOSS_TAILS_HURT_CONTACT);

            AbstractPlayableSprite tails = GameServices.sprites().getRegisteredSidekicks().getFirst();
            TraceFrame beforeContact =
                    traceFrame(replay.trace(), FRAME_CNZ_MINIBOSS_TAILS_HURT_CONTACT);
            assertEquals((short) -0x0340, beforeContact.sidekick().xSpeed());
            assertEquals(beforeContact.sidekick().xSpeed(), tails.getXSpeed(),
                    "Frame 15096: Touch_Loop must read the top's live prior-pass X=$32D8; "
                            + "its right edge $32E8 still misses Tails' left edge $32E9");
            assertEquals(beforeContact.sidekick().routine(), TraceCharacterState.routineFromSprite(tails),
                    "Frame 15096 must finish the ordinary Tails routine before contact");

            driveReplayFrame(
                    replay.trace(), replay.fixture(), beforeContact,
                    FRAME_CNZ_MINIBOSS_TAILS_HURT_CONTACT + 1);

            TraceFrame contact =
                    traceFrame(replay.trace(), FRAME_CNZ_MINIBOSS_TAILS_HURT_CONTACT + 1);
            assertEquals((short) 0x0200, contact.sidekick().xSpeed());
            assertEquals(contact.sidekick().xSpeed(), tails.getXSpeed(),
                    "Frame 15097: Tails' left edge reaches the moving top and HurtCharacter "
                            + "must apply the native rightward knockback");
            assertEquals(contact.sidekick().routine(), TraceCharacterState.routineFromSprite(tails),
                    "Frame 15097 must enter native hurt routine 4 "
                            + "(sonic3k.asm:20660-20712,21050-21104)");
        }
    }

    @Test
    void traceReplayCnzMinibossLookDownBiasIsOwnedByFocusedPlayer() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_CNZ_MINIBOSS_LOOK_DOWN_CAMERA_PAN);

            TraceFrame expected = traceFrame(replay.trace(), FRAME_CNZ_MINIBOSS_LOOK_DOWN_CAMERA_PAN);
            AbstractPlayableSprite sonic = replay.fixture().sprite();
            assertEquals(0x78, sonic.getLookDelayCounter() & 0xFFFF,
                    "Frame 15569: S3K Obj01_LookUpDown clamps scroll_delay_counter at $78 "
                            + "before applying the look-down bias "
                            + "(docs/skdisasm/sonic3k.asm:22616-22634)");
            assertEquals(0x005E, GameServices.camera().getYPosBias() & 0xFFFF,
                    "Frame 15569: Sonic's look-down branch subtracts 2 from camera bias; "
                            + "CPU Tails must not run Obj01_ResetScr against the focused camera "
                            + "(docs/skdisasm/sonic3k.asm:22629-22634,25741-25746)");
            assertEquals(expected.cameraY(), GameServices.camera().getY() & 0xFFFF,
                    "Frame 15569: non-default look bias makes MoveCameraY scroll down by two pixels "
                            + "(docs/skdisasm/sonic3k.asm:38435-38586)");
        }
    }

    @Test
    void traceReplayCnzMinibossPostBossKeepsArenaXClampAfterBossFlagClear() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_CNZ_MINIBOSS_POST_BOSS_X_CLAMP);

            TraceFrame expected = traceFrame(replay.trace(), FRAME_CNZ_MINIBOSS_POST_BOSS_X_CLAMP);
            assertEquals(expected.cameraX(), GameServices.camera().getX() & 0xFFFF,
                    "Frame 15723: Obj_CNZMinibossEndGo clears Boss_flag, but CNZ AfterBoss_Cleanup "
                            + "is rts, so the camera must still be clamped by Camera_max_X_pos=$3260 "
                            + "(docs/skdisasm/sonic3k.asm:144996-145001,176489-176557)");
            assertEquals(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X, GameServices.camera().getMaxX() & 0xFFFF,
                    "Frame 15723: the later CNZ1BGE_DoTransition path owns post-boss scroll-control "
                            + "progression; the Boss_flag falling edge must not restore Camera_stored_max_X_pos "
                            + "(docs/skdisasm/sonic3k.asm:107603-107653)");
        }
    }

    @Test
    void traceReplayCnz2PathSwapConsumesSstSlotBeforeBalloonCluster() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            String frame20903Slots = "";
            String frame20936Slots = "";
            String frame21031Slots = "";
            String frame20583Slots = "";
            String frame20584Slots = "";
            String frame20611Slots = "";
            int frame20584TriangleSlot = -1;
            int frame20903SpikeSlot = -1;
            int frame20903PathSwapSlot = -1;
            int frame20903FirstBalloonSlot = -1;
            int frame20936SpikeSlot = -1;
            int frame20936PathSwapSlot = -1;
            int frame20936FirstBalloonSlot = -1;
            int frame21031SpikeSlot = -1;
            int frame21031PathSwapSlot = -1;
            int frame21031FirstBalloonSlot = -1;
            TraceFrame previousDriveFrame = replay.replayStart().hasSeededTraceState()
                    ? replay.trace().getFrame(replay.replayStart().seededTraceIndex())
                    : null;
            for (int traceIndex = replay.replayStart().startingTraceIndex();
                 traceIndex <= 21031;
                 traceIndex++) {
                previousDriveFrame = driveReplayFrame(
                        replay.trace(), replay.fixture(), previousDriveFrame, traceIndex);

                if (traceIndex == 20583) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame20583Slots = slotDump(objectManager);
                } else if (traceIndex == 20584) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame20584Slots = slotDump(objectManager);
                    frame20584TriangleSlot = slotFor(objectManager, "CNZTriangleBumpers", 0x1580, 0x0978);
                } else if (traceIndex == 20611) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame20611Slots = slotDump(objectManager);
                } else if (traceIndex == 20903) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame20903Slots = slotDump(objectManager);
                    frame20903SpikeSlot = slotFor(objectManager, "Spikes", 0x1280, 0x09D0);
                    frame20903PathSwapSlot = slotFor(objectManager, "PathSwap", 0x1180, 0x09A0);
                    frame20903FirstBalloonSlot = slotForSpawn(objectManager, "CNZBalloon", 0x1080, 0x0700);
                } else if (traceIndex == 20936) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame20936Slots = slotDump(objectManager);
                    frame20936SpikeSlot = slotFor(objectManager, "Spikes", 0x1280, 0x09D0);
                    frame20936PathSwapSlot = slotFor(objectManager, "PathSwap", 0x1180, 0x09A0);
                    frame20936FirstBalloonSlot = slotForSpawn(objectManager, "CNZBalloon", 0x1080, 0x0700);
                } else if (traceIndex == 21031) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame21031Slots = slotDump(objectManager);
                    frame21031SpikeSlot = slotFor(objectManager, "Spikes", 0x1280, 0x09D0);
                    frame21031PathSwapSlot = slotFor(objectManager, "PathSwap", 0x1180, 0x09A0);
                    frame21031FirstBalloonSlot = slotForSpawn(objectManager, "CNZBalloon", 0x1080, 0x0700);
                }
            }

            assertEquals(5, frame20584TriangleSlot,
                    "Frame 20584: ROM reloads CNZTriangleBumpers @1580,0978 into s5 before "
                            + "the @14A0 spike arrives "
                            + "(docs/skdisasm/sonic3k.asm:68421-68434,37301-37317). slots="
                            + frame20584Slots + " previous=" + frame20583Slots);
            assertEquals(5, frame20903SpikeSlot,
                    "Frame 20903: ROM loads Spikes @1280,09D0 into s5 before the lower-X PathSwap/balloon "
                            + "because Load_Sprites is scrolling backward and processes the X pass before "
                            + "the later Y-strip pass (docs/skdisasm/sonic3k.asm:37640-37674,37723-37762). "
                            + "f20584=" + frame20584Slots
                            + "f20611=" + frame20611Slots
                            + " "
                            + "pathSwap=" + frame20903PathSwapSlot
                            + " firstBalloon=" + frame20903FirstBalloonSlot
                            + " slots=" + frame20903Slots);
            assertEquals(6, frame20936PathSwapSlot,
                    "Frame 20936: ROM then loads Obj_PathSwap @1180,09A0 in s6 after the spike is already live. "
                            + "spike=" + frame20936SpikeSlot
                            + " firstBalloon=" + frame20936FirstBalloonSlot
                            + " f20583=" + frame20583Slots
                            + " f20584=" + frame20584Slots
                            + " f20611=" + frame20611Slots
                            + " f20903=" + frame20903Slots
                            + " slots=" + frame20936Slots);
            assertEquals(5, frame21031SpikeSlot,
                    "Frame 21031: ROM still has the S3K Spikes @1280,09D0 in s5 until "
                            + "Sprite_OnScreen_Test2 removes it at frame 21333 "
                            + "(docs/skdisasm/sonic3k.asm:49102-49103). f20903=" + frame20903Slots
                            + " f20936=" + frame20936Slots
                            + " f21031=" + frame21031Slots);
            assertEquals(6, frame21031PathSwapSlot,
                    "Frame 21031: with spike s5 alive, Obj_PathSwap @1180,09A0 should load in s6. "
                            + "slots=" + frame21031Slots);
            assertTrue(frame21031PathSwapSlot >= 0,
                    "Frame 21031: S3K Obj_PathSwap must allocate a real SST slot even though "
                            + "ObjectManager.PlaneSwitchers owns the path/priority behavior. "
                            + "The ROM installs loc_1CD8A and ends in Delete_Sprite_If_Not_In_Range "
                            + "(docs/skdisasm/sonic3k.asm:39699-39720,39740-39776). slots="
                            + frame21031Slots);
            assertTrue(frame21031FirstBalloonSlot > frame21031PathSwapSlot,
                    "Frame 21031: the path-switch marker should consume low-slot pressure before "
                            + "the nearby CNZ balloon cluster. slots=" + frame21031Slots);
        }
    }

    @Test
    void traceReplayCnz2PreBalloonSlotPressureMatchesRomSequence() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            String frame20584Slots = "";
            String frame17837Slots = "";
            String frame17843Slots = "";
            String frame17845Slots = "";
            String frame17863Slots = "";
            String frame17868Slots = "";
            String frame17876Slots = "";
            String frame17995Slots = "";
            String frame18930Slots = "";
            String frame18937Slots = "";
            String frame19004Slots = "";
            String frame19064Slots = "";
            String frame19086Slots = "";
            String frame19089Slots = "";
            String frame19162Slots = "";
            String frame20313Slots = "";
            String frame20350Slots = "";
            String frame20358Slots = "";
            String frame19500Slots = "";
            String frame18000Slots = "";
            String frame20200Slots = "";
            String frame20046Slots = "";
            String frame20049Slots = "";
            String frame20131Slots = "";
            String frame20300Slots = "";
            String frame20528Slots = "";
            String frame20549Slots = "";
            String frame20903Slots = "";
            String frame20969Slots = "";
            String frame20936Slots = "";
            String frame21031Slots = "";
            String frame21620Slots = "";
            int frame17837SpikeSlot = -1;
            int frame17824OldPathSwapSlot = -1;
            int frame17995TriangleSlot = -1;
            int frame18937BubblerSlot = -1;
            int frame20903SpikeSlot = -1;
            int frame20969SpikeSlot = -1;
            int frame20936PathSwapSlot = -1;
            int frame21031PathSwapSlot = -1;
            int frame21620MonitorSlot = -1;
            int frame21620FirstBalloonSlot = -1;
            TraceFrame previousDriveFrame = replay.replayStart().hasSeededTraceState()
                    ? replay.trace().getFrame(replay.replayStart().seededTraceIndex())
                    : null;
            for (int traceIndex = replay.replayStart().startingTraceIndex();
                 traceIndex <= 21620;
                 traceIndex++) {
                previousDriveFrame = driveReplayFrame(
                        replay.trace(), replay.fixture(), previousDriveFrame, traceIndex);

                ObjectManager objectManager = GameServices.level().getObjectManager();
                if (traceIndex == 17824) {
                    frame17824OldPathSwapSlot = slotFor(objectManager, "PathSwap", 0x0E80, 0x0A80);
                } else if (traceIndex == 17837) {
                    frame17837Slots = slotDump(objectManager);
                    frame17837SpikeSlot = slotFor(objectManager, "Spikes", 0x1220, 0x0990);
                } else if (traceIndex == 17843) {
                    frame17843Slots = slotDump(objectManager);
                } else if (traceIndex == 17845) {
                    frame17845Slots = slotDump(objectManager);
                } else if (traceIndex == 17863) {
                    frame17863Slots = slotDump(objectManager);
                } else if (traceIndex == 17868) {
                    frame17868Slots = slotDump(objectManager);
                } else if (traceIndex == 17876) {
                    frame17876Slots = slotDump(objectManager);
                } else if (traceIndex == 17995) {
                    frame17995Slots = slotDump(objectManager);
                    frame17995TriangleSlot = slotFor(objectManager, "CNZTriangleBumpers", 0x1580, 0x0978);
                } else if (traceIndex == 18930) {
                    frame18930Slots = slotDump(objectManager);
                } else if (traceIndex == 18937) {
                    frame18937Slots = slotDump(objectManager);
                    frame18937BubblerSlot = firstSlotFor(objectManager, "Bubbler");
                } else if (traceIndex == 19004) {
                    frame19004Slots = slotDump(objectManager);
                } else if (traceIndex == 19064) {
                    frame19064Slots = slotDump(objectManager);
                } else if (traceIndex == 19086) {
                    frame19086Slots = slotDump(objectManager);
                } else if (traceIndex == 19089) {
                    frame19089Slots = slotDump(objectManager);
                } else if (traceIndex == 19162) {
                    frame19162Slots = slotDump(objectManager);
                } else if (traceIndex == 18000) {
                    frame18000Slots = slotDump(objectManager);
                } else if (traceIndex == 19500) {
                    frame19500Slots = slotDump(objectManager);
                } else if (traceIndex == 20046) {
                    frame20046Slots = slotDump(objectManager);
                } else if (traceIndex == 20049) {
                    frame20049Slots = slotDump(objectManager);
                } else if (traceIndex == 20131) {
                    frame20131Slots = slotDump(objectManager);
                } else if (traceIndex == 20200) {
                    frame20200Slots = slotDump(objectManager);
                } else if (traceIndex == 20300) {
                    frame20300Slots = slotDump(objectManager);
                } else if (traceIndex == 20313) {
                    frame20313Slots = slotDump(objectManager);
                } else if (traceIndex == 20350) {
                    frame20350Slots = slotDump(objectManager);
                } else if (traceIndex == 20358) {
                    frame20358Slots = slotDump(objectManager);
                } else if (traceIndex == 20528) {
                    frame20528Slots = slotDump(objectManager);
                } else if (traceIndex == 20549) {
                    frame20549Slots = slotDump(objectManager);
                } else if (traceIndex == 20584) {
                    frame20584Slots = slotDump(objectManager);
                } else if (traceIndex == 20903) {
                    frame20903Slots = slotDump(objectManager);
                    frame20903SpikeSlot = slotFor(objectManager, "Spikes", 0x1280, 0x09D0);
                } else if (traceIndex == 20936) {
                    frame20936Slots = slotDump(objectManager);
                    frame20936PathSwapSlot = slotFor(objectManager, "PathSwap", 0x1180, 0x09A0);
                } else if (traceIndex == 20969) {
                    frame20969Slots = slotDump(objectManager);
                    frame20969SpikeSlot = slotFor(objectManager, "Spikes", 0x1280, 0x09D0);
                } else if (traceIndex == 21031) {
                    frame21031Slots = slotDump(objectManager);
                    frame21031PathSwapSlot = slotFor(objectManager, "PathSwap", 0x1180, 0x09A0);
                } else if (traceIndex == 21620) {
                    frame21620Slots = slotDump(objectManager);
                    frame21620MonitorSlot = slotFor(objectManager, "Monitor", 0x0F5C, 0x0770);
                    frame21620FirstBalloonSlot = slotForSpawn(objectManager, "CNZBalloon", 0x1080, 0x0700);
                }
            }

            assertEquals(5, frame17824OldPathSwapSlot,
                    "Frame 17824: ROM keeps the older vertical Obj_PathSwap @0E80,0A80 in s5 "
                            + "until Delete_Sprite_If_Not_In_Range runs at f17831 "
                            + "(docs/skdisasm/sonic3k.asm:39892-39901).");
            assertEquals(5, frame17837SpikeSlot,
                    "Frame 17837: ROM keeps the newly loaded S3K Spikes @1220,0990 in s5. "
                            + "Obj_Spikes stores loc_2413E/loc_24090 on init and active variants "
                            + "must use Sprite_OnScreen_Test2-style lifecycle, not early placement unload "
                            + "(docs/skdisasm/sonic3k.asm:48925-48960,49011-49103). slots="
                            + frame17837Slots + " f17824PathSwap=" + frame17824OldPathSwapSlot
                            + " f17837=" + frame17837Slots);
            assertEquals(5, frame17995TriangleSlot,
                    "Frame 17995: the accepted spike/water-splash slot pressure should leave "
                            + "CNZTriangleBumpers @1580,0978 in the ROM s5 slot before the later "
                            + "CNZ2 slot-pressure boundary. slots=" + frame17995Slots
                            + " f17843=" + frame17843Slots
                            + " f17845=" + frame17845Slots
                            + " f17863=" + frame17863Slots
                            + " f17868=" + frame17868Slots
                            + " f17876=" + frame17876Slots
                            + " unresolved f20584=" + frame20584Slots
                            + " f20903=" + frame20903Slots
                            + " f20936=" + frame20936Slots
                            + " f20969=" + frame20969Slots
                            + " f21031=" + frame21031Slots
                            + " f21620=" + frame21620Slots);
            assertEquals(4, frame18937BubblerSlot,
                    "Frame 18937: Obj_Bubbler maker children must consume the lowest free SST slot "
                            + "because loc_2FABA calls AllocateObject, not AllocateObjectAfterCurrent "
                            + "(docs/skdisasm/sonic3k.asm:64560-64567). slots=" + frame18937Slots
                            + " f18930=" + frame18930Slots
                            + " f19004=" + frame19004Slots
                            + " f19089=" + frame19089Slots
                            + " f19162=" + frame19162Slots
                            + " f20313=" + frame20313Slots
                            + " f20358=" + frame20358Slots);
        }
    }

    @Test
    void traceReplayCnz2FixedAirCountdownInitialCadencePreservesBubblerSlot() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            String frame17824Slots = "";
            String frame17831Slots = "";
            String frame17834Slots = "";
            String frame18937Slots = "";
            int frame17824AirCountdownSlot = -1;
            int frame17831AirCountdownSlot = -1;
            int frame17834AirCountdownSlot = -1;
            int frame18937BubblerSlot = -1;
            TraceFrame previousDriveFrame = replay.replayStart().hasSeededTraceState()
                    ? replay.trace().getFrame(replay.replayStart().seededTraceIndex())
                    : null;
            for (int traceIndex = replay.replayStart().startingTraceIndex();
                 traceIndex <= 18937;
                 traceIndex++) {
                previousDriveFrame = driveReplayFrame(
                        replay.trace(), replay.fixture(), previousDriveFrame, traceIndex);

                ObjectManager objectManager = GameServices.level().getObjectManager();
                if (traceIndex == 17824) {
                    frame17824Slots = slotDump(objectManager);
                    frame17824AirCountdownSlot = dynamicSlotFor(objectManager, "AirCountdown", 0x0FC0, 0x0A97);
                } else if (traceIndex == 17831) {
                    frame17831Slots = slotDump(objectManager);
                    frame17831AirCountdownSlot = dynamicSlotFor(objectManager, "AirCountdown", 0x0FC0, 0x0A91);
                } else if (traceIndex == 17834) {
                    frame17834Slots = slotDump(objectManager);
                    frame17834AirCountdownSlot = firstSlotFor(objectManager, "AirCountdown");
                } else if (traceIndex == 18937) {
                    frame18937Slots = slotDump(objectManager);
                    frame18937BubblerSlot = firstSlotFor(objectManager, "Bubbler");
                }
            }
            assertEquals(6, frame17824AirCountdownSlot,
                    "Frame 17824: fixed AirCountdown visible child should allocate into dynamic s6 "
                            + "without the fixed controller itself consuming an SST slot. slots=" + frame17824Slots);
            assertEquals(6, frame17831AirCountdownSlot,
                    "Frame 17831: the visible AirCountdown child should still occupy s6 while rising "
                            + "(docs/skdisasm/sonic3k.asm:33314-33370). slots=" + frame17831Slots);
            assertEquals(-1, frame17834AirCountdownSlot,
                    "Frame 17834: the water-surface routine deletes the f17824 visible child. slots=" + frame17834Slots);
            assertEquals(4, frame18937BubblerSlot,
                    "Frame 18937: fixed AirCountdown controllers are sidecars outside dynamic SST, "
                            + "so Bubbler's AllocateObject child must still get s4. slots=" + frame18937Slots);
        }
    }

    @Test
    void traceReplayClamerWaitOffscreenAndMonitorBreakPreserveLowSlotPressureBeforeBalloon() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            String initialSlots = slotDump(GameServices.level().getObjectManager());
            String frame287Slots = "";
            int frame287ClamerSlot = -1;
            int frame287SpringSlot = -1;
            int frame287MonitorContentsSlot = -1;
            int frame287ExplosionSlot = -1;
            String frame457Slots = "";
            int frame457BumperScoreSlot = -1;
            String frame490Slots = "";
            int frame490SpringSlot = -1;
            TraceFrame previousDriveFrame = replay.replayStart().hasSeededTraceState()
                    ? replay.trace().getFrame(replay.replayStart().seededTraceIndex())
                    : null;
            for (int traceIndex = replay.replayStart().startingTraceIndex();
                 traceIndex <= FRAME_CNZ_CLAMER_SLOT_PRESSURE;
                 traceIndex++) {
                previousDriveFrame = driveReplayFrame(
                        replay.trace(), replay.fixture(), previousDriveFrame, traceIndex);

                if (traceIndex == 287) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame287Slots = slotDump(objectManager);
                    frame287ClamerSlot = slotFor(objectManager, "Clamer", 0x0578, 0x0690);
                    frame287SpringSlot = slotFor(objectManager, "ClamerSpringChild", 0x0578, 0x0688);
                    frame287MonitorContentsSlot = slotFor(objectManager, "MonitorContents", 0x0340);
                    frame287ExplosionSlot = slotFor(objectManager, "Explosion", 0x0340, 0x0650);
                }
                if (traceIndex == 457) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame457Slots = slotDump(objectManager);
                    frame457BumperScoreSlot = slotFor(objectManager, "S3KPoints", 0x03E8, 0x0630);
                }
                if (traceIndex == 490) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame490Slots = slotDump(objectManager);
                    frame490SpringSlot = slotFor(objectManager, "Spring", 0x05C8, 0x06B0);
                }
            }

            ObjectManager objectManager = GameServices.level().getObjectManager();
            String frame569Slots = slotDump(objectManager);

            assertEquals(4, frame287ClamerSlot,
                    "Frame 287: Load_Sprites has allocated Clamer's Obj_WaitOffscreen wrapper "
                            + "in s4, but Obj_Clamer loc_88FDC has not created the child yet "
                            + "(docs/skdisasm/sonic3k.asm:180266-180298,185857-185877). "
                            + "initial=" + initialSlots + " f287=" + frame287Slots);
            assertEquals(-1, frame287SpringSlot,
                    "Frame 287: Obj_WaitOffscreen must suppress Clamer's CreateChild1_Normal "
                            + "until the wrapper becomes onscreen.");
            assertEquals(18, frame287MonitorContentsSlot,
                    "Frame 287: broken monitor contents must consume a high slot after "
                            + "the monitor shell, not the first free low slot "
                            + "(docs/skdisasm/sonic3k.asm:40640-40652).");
            assertEquals(19, frame287ExplosionSlot,
                    "Frame 287: monitor explosion is allocated after the monitor contents "
                            + "via AllocateObjectAfterCurrent (docs/skdisasm/sonic3k.asm:40654-40659).");
            assertEquals(5, frame457BumperScoreSlot,
                    "Frame 457: CNZ bumper sub_32F56 must allocate Obj_EnemyScore after HUD_AddToScore, "
                            + "keeping s5 occupied until the placed spring window "
                            + "(docs/skdisasm/sonic3k.asm:68980-68989). f457=" + frame457Slots);
            assertEquals(6, frame490SpringSlot,
                    "Frame 490: ROM deletes Obj_EnemyScore from s5 on the same frame Load_Sprites "
                            + "allocates the placed Spring into s6, because s5 was occupied at allocation time "
                            + "(docs/skdisasm/sonic3k.asm:61375-61389). f490=" + frame490Slots);
            assertEquals(4, slotFor(objectManager, "Clamer", 0x0578, 0x0690),
                    "Frame 569: ROM f540 loaded Obj_Clamer into s4 before its spring child. "
                            + "Load_Sprites allocates with AllocateObject/sub_1BA0C "
                            + "(docs/skdisasm/sonic3k.asm:37640-37656,37784-37898); "
                            + "Obj_Clamer loc_88FDC then calls CreateChild1_Normal "
                            + "(docs/skdisasm/sonic3k.asm:185873-185877,176919-176949).");
            assertEquals(5, slotFor(objectManager, "ClamerSpringChild", 0x0578, 0x0688),
                    "Frame 569: Obj_Clamer loc_88FDC must have run CreateChild1_Normal "
                            + "before the balloon allocation window. slots=" + frame569Slots);
            assertEquals(6, slotFor(objectManager, "Spring", 0x05C8, 0x06B0),
                    "Frame 569: the placed Spring should remain behind Clamer's child in s6. "
                            + "slots=" + frame569Slots);
            assertEquals(7, slotForSpawn(objectManager, "CNZBalloon", 0x06C0, 0x0618),
                    "Frame 569: Clamer and intervening low-slot objects should make the first CNZ balloon "
                            + "allocates into s7, preserving later barber-pole slot pressure.");
        }
    }

    private static int slotFor(ObjectManager objectManager, String name, int x) {
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            if (aoi.getSpawn() == null) {
                continue;
            }
            if (name.equals(aoi.getName())
                    && (aoi.getX() & 0xFFFF) == (x & 0xFFFF)) {
                return aoi.getSlotIndex();
            }
        }
        return -1;
    }

    private static int slotForSpawn(ObjectManager objectManager, String name, int x, int y) {
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            if (aoi.getSpawn() == null) {
                continue;
            }
            if (name.equals(aoi.getName())
                    && (aoi.getSpawn().x() & 0xFFFF) == (x & 0xFFFF)
                    && (aoi.getSpawn().y() & 0xFFFF) == (y & 0xFFFF)) {
                return aoi.getSlotIndex();
            }
        }
        return -1;
    }

    @Test
    void traceReplayClamerAutoCloseProjectileConsumesRomSlot() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            String frame621Slots = "";
            int frame621ProjectileSlot = -1;
            TraceFrame previousDriveFrame = replay.replayStart().hasSeededTraceState()
                    ? replay.trace().getFrame(replay.replayStart().seededTraceIndex())
                    : null;
            for (int traceIndex = replay.replayStart().startingTraceIndex();
                 traceIndex <= FRAME_CNZ_BLOOMINATOR_PROJECTILE_PRESSURE;
                 traceIndex++) {
                previousDriveFrame = driveReplayFrame(
                        replay.trace(), replay.fixture(), previousDriveFrame, traceIndex);

                if (traceIndex == FRAME_CNZ_BLOOMINATOR_PROJECTILE_PRESSURE) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame621Slots = slotDump(objectManager);
                    frame621ProjectileSlot = slotFor(objectManager, "ClamerAutoCloseProjectile", 0x0566, 0x0692);
                }
            }

            assertEquals(8, frame621ProjectileSlot,
                    "Frame 621: ROM Clamer loc_89064 creates ChildObjDat_89150/loc_86D5E projectile in s8 "
                            + "(docs/skdisasm/sonic3k.asm:185930-185940,186020-186027,182257-182265). f621="
                            + frame621Slots);
        }
    }

    @Test
    void traceReplayClamerAutoCloseProjectileHoldsSlotUntilRomDeleteFrame() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            TraceFrame previousDriveFrame = replay.replayStart().hasSeededTraceState()
                    ? replay.trace().getFrame(replay.replayStart().seededTraceIndex())
                    : null;
            String frame665Slots = "";
            String frame666Slots = "";
            int frame665ProjectileSlot = -1;
            int frame665ProjectileX = -1;
            int frame665ProjectileFlags = -1;
            int frame666ProjectileSlot = -1;
            for (int traceIndex = replay.replayStart().startingTraceIndex();
                 traceIndex <= 666;
                 traceIndex++) {
                previousDriveFrame = driveReplayFrame(
                        replay.trace(), replay.fixture(), previousDriveFrame, traceIndex);

                if (traceIndex == 665) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    ObjectInstance projectile = objectByName(objectManager, "ClamerAutoCloseProjectile");
                    frame665Slots = slotDump(objectManager);
                    frame665ProjectileSlot = projectile instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
                    frame665ProjectileX = projectile != null ? projectile.getX() : -1;
                    frame665ProjectileFlags = projectile instanceof TouchResponseProvider provider
                            ? provider.getCollisionFlags()
                            : -1;
                }
                if (traceIndex == 666) {
                    ObjectManager objectManager = GameServices.level().getObjectManager();
                    frame666Slots = slotDump(objectManager);
                    ObjectInstance projectile = objectByName(objectManager, "ClamerAutoCloseProjectile");
                    if (projectile instanceof AbstractObjectInstance aoi) {
                        frame666ProjectileSlot = aoi.getSlotIndex();
                    }
                }
            }

            assertEquals(8, frame665ProjectileSlot,
                    "Frame 665: ROM Sprite_CheckDeleteTouchXY has just branched through Go_Delete_Sprite, "
                            + "so the projectile's SST slot is still occupied until Delete_Current_Sprite "
                            + "runs next frame (docs/skdisasm/sonic3k.asm:179027-179039,179131-179134,36108-36122). "
                            + "slots=" + frame665Slots);
            assertEquals(0x050E, frame665ProjectileX & 0xFFFF,
                    "Frame 665: projectile should reach the ROM delete-marker position before being freed.");
            assertEquals(0, frame665ProjectileFlags,
                    "Frame 665: the Go_Delete_Sprite marker no longer contributes collision flags.");
            assertEquals(-1, frame666ProjectileSlot,
                    "Frame 666: Delete_Current_Sprite has cleared the projectile slot. f666=" + frame666Slots);
        }
    }

    private static ObjectInstance objectByName(ObjectManager objectManager, String name) {
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            if (name.equals(aoi.getName())) {
                return object;
            }
        }
        return null;
    }

    @Test
    void traceReplayHorizontalSpringLandingHandoffMatchesFrame3649() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_HORIZONTAL_SPRING_LANDING_HANDOFF);

            TraceFrame expected = traceFrame(replay.trace(), FRAME_HORIZONTAL_SPRING_LANDING_HANDOFF);
            AbstractPlayableSprite tails = GameServices.sprites().getRegisteredSidekicks().getFirst();
            assertEquals(expected.sidekick().x(), tails.getCentreX() & 0xFFFF,
                    "Frame 3649: ROM skips the horizontal spring side push and reaches "
                            + "sub_2326C's proactive trigger from outside the side box");
            assertEquals(expected.sidekick().xSpeed(), tails.getXSpeed(),
                    "Frame 3649: proactive horizontal spring trigger applies the left spring velocity");
        }
    }

    @Test
    void traceReplayS3kRightWallPathRunsCeilingSeparationOnFrame5236() throws Exception {
        try (BootstrappedCnzReplay replay = bootstrappedCnzReplay()) {
            driveReplayToTraceFrame(
                    replay.trace(),
                    replay.fixture(),
                    replay.replayStart(),
                    FRAME_TAILS_RIGHT_WALL_CEILING_SEPARATION);

            TraceFrame expected = traceFrame(replay.trace(), FRAME_TAILS_RIGHT_WALL_CEILING_SEPARATION);
            AbstractPlayableSprite tails = GameServices.sprites().getRegisteredSidekicks().getFirst();
            assertEquals(expected.sidekick().y(), tails.getCentreY() & 0xFFFF,
                    "Frame 5236: S3K Tails_DoLevelCollision right-wall path must continue into "
                            + "sub_11FEE ceiling separation instead of returning like S1/S2");
            assertEquals(expected.sidekick().ySpeed(), tails.getYSpeed(),
                    "Frame 5236: S3K wall-hit path preserves the post-separation vertical speed");
            assertEquals(expected.sidekick().gSpeed(), tails.getGSpeed(),
                    "Frame 5236: S3K wall-hit path copies y_vel to ground_vel before ceiling separation");
        }
    }

    private static int slotFor(ObjectManager objectManager, String name, int x, int y) {
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            if (aoi.getSpawn() == null) {
                continue;
            }
            if (name.equals(aoi.getName())
                    && (aoi.getX() & 0xFFFF) == (x & 0xFFFF)
                    && (aoi.getY() & 0xFFFF) == (y & 0xFFFF)) {
                return aoi.getSlotIndex();
            }
        }
        return -1;
    }

    private static int dynamicSlotFor(ObjectManager objectManager, String name, int x, int y) {
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            if (aoi.getSpawn() != null) {
                continue;
            }
            if (name.equals(aoi.getName())
                    && (aoi.getX() & 0xFFFF) == (x & 0xFFFF)
                    && (aoi.getY() & 0xFFFF) == (y & 0xFFFF)) {
                return aoi.getSlotIndex();
            }
        }
        return -1;
    }

    private static int firstSlotFor(ObjectManager objectManager, String name) {
        int slot = Integer.MAX_VALUE;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof AbstractObjectInstance aoi && name.equals(aoi.getName())) {
                slot = Math.min(slot, aoi.getSlotIndex());
            }
        }
        return slot == Integer.MAX_VALUE ? -1 : slot;
    }

    private static String slotDump(ObjectManager objectManager) {
        StringBuilder sb = new StringBuilder();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append('s').append(aoi.getSlotIndex())
                    .append(' ').append(aoi.getName());
            if (aoi.getSpawn() == null) {
                sb.append(" @<dynamic>");
            } else {
                sb.append(String.format(" @%04X,%04X", aoi.getX() & 0xFFFF, aoi.getY() & 0xFFFF));
            }
            if ("CNZHoverFan".equals(aoi.getName())
                    || "CNZBalloon".equals(aoi.getName())
                    || "Bubbler".equals(aoi.getName())
                    || "AirCountdown".equals(aoi.getName())
                    || "BreathingBubble".equals(aoi.getName())) {
                sb.append('[').append(aoi.traceDebugDetails()).append(']');
            }
        }
        return sb.toString();
    }

    private static BootstrappedCnzReplay bootstrappedCnzReplay() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        return bootstrappedCnzReplay(trace, false, true);
    }

    private static BootstrappedCnzReplay bootstrappedCnzReplay(
            TraceData trace,
            boolean discardProductionToken,
            boolean assertOpeningState) throws Exception {
        var configSnapshot = TraceReplaySessionBootstrap.snapshotGameplayConfig();
        TraceReplaySessionBootstrap.prepareConfiguration(trace, trace.metadata());

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0x03, 0x00);
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .withRecording(TRACE_DIR.resolve("s3k-cnz-sonic-tails.bk2"))
                .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                .startPosition(trace.metadata().startX(), trace.metadata().startY())
                .startPositionIsCentre()
                .withFreshLevelStartLifecycle()
                .build();

        assertTrue(GameServices.level().hasPendingInitialProcessSpritesPass(),
                "a genuine production load must publish setup authority");
        if (discardProductionToken) {
            GameServices.level().discardPendingInitialProcessSpritesForStateRestoration();
        }
        fixture.gameplayMode().activateRecordedHardwareAdmission();
        int objectFrameBefore = GameServices.level().getObjectManager().getFrameCounter();
        TraceReplaySessionBootstrap.BootstrapResult boot =
                TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);
        assertEquals(objectFrameBefore + (discardProductionToken ? 0 : 1),
                GameServices.level().getObjectManager().getFrameCounter(),
                "bootstrap may execute only authority published by the production load");
        assertFalse(GameServices.level().consumePendingInitialProcessSpritesPass(),
                "bootstrap must already consume the production setup token");
        if (assertOpeningState) {
            assertEquals(List.of(0x11, 0x38, 0x38, 0xA8), liveOpeningBalloonAngles(),
                    "zero-seed production setup must initialize the native balloon epoch");
            assertEquals(trace.metadata().initialRngSeed().longValue(), GameServices.rng().getSeed(),
                    "frame-zero RNG must be applied after native object setup");
        }
        TraceReplayBootstrap.ReplayStartState replayStart = boot.replayStart();
        TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : null;
        TraceReplaySessionBootstrap.alignFrameCountersForReplayStart(
                previousDriveFrame,
                trace.getFrame(replayStart.startingTraceIndex()));
        return new BootstrappedCnzReplay(trace, sharedLevel, fixture, replayStart,
                configSnapshot);
    }

    private Path metadataVariantDirectory(MetadataVariant variant) throws Exception {
        Path directory = tempDir.resolve(variant.name().toLowerCase());
        Files.createDirectories(directory);
        Files.copy(TRACE_DIR.resolve("physics.csv.gz"), directory.resolve("physics.csv.gz"));

        ObjectNode metadata = (ObjectNode) JSON.readTree(TRACE_DIR.resolve("metadata.json").toFile());
        switch (variant) {
            case TRACE_PROFILE -> metadata.put("trace_profile", "metadata-must-not-select-setup");
            case SIDEKICK_CHARACTERS -> {
                ArrayNode characters = metadata.putArray("characters");
                characters.add("sonic");
                metadata.putArray("sidekicks");
            }
            case CHECKPOINT_NAMES -> {
                // The checkpoint is aux data rather than metadata.json, but it is still
                // comparison input and must not become setup authority.
            }
            case RECORDING_FILENAME -> metadata.put(
                    "source_bk2", "renamed-recording-must-not-select-setup.bk2");
            case RNG_SEED -> metadata.put("rng_seed", "0x89ABCDEF");
            case START_POSITION -> {
                metadata.put("start_x", "0x0118");
                metadata.put("start_y", "0x0700");
            }
        }
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("metadata.json").toFile(), metadata);

        if (variant == MetadataVariant.CHECKPOINT_NAMES) {
            rewriteCheckpointNames(
                    TRACE_DIR.resolve("aux_state.jsonl.gz"),
                    directory.resolve("aux_state.jsonl.gz"));
        } else {
            Files.copy(TRACE_DIR.resolve("aux_state.jsonl.gz"),
                    directory.resolve("aux_state.jsonl.gz"));
        }
        return directory;
    }

    private static void rewriteCheckpointNames(Path source, Path destination) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                     new GZIPInputStream(Files.newInputStream(source)), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     new GZIPOutputStream(Files.newOutputStream(destination)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line.contains("\"event\":\"checkpoint\"")
                        ? line.replace("\"name\":\"", "\"name\":\"renamed_")
                        : line);
                writer.newLine();
            }
        }
    }

    private static List<Integer> liveOpeningBalloonAngles() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(CnzBalloonInstance.class::isInstance)
                .map(CnzBalloonInstance.class::cast)
                .filter(balloon -> balloon.getSlotIndex() >= 4 && balloon.getSlotIndex() <= 7)
                .sorted(Comparator.comparingInt(CnzBalloonInstance::getSlotIndex))
                .map(balloon -> Integer.parseInt(
                        balloon.traceDebugDetails().substring(4, 6), 16))
                .toList();
    }

    private static RuntimeState fixedMetadataRuntimeState(TraceData trace, boolean bootstrap)
            throws Exception {
        var configSnapshot = TraceReplaySessionBootstrap.snapshotGameplayConfig();
        TraceReplaySessionBootstrap.prepareConfiguration(trace, trace.metadata());
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0x03, 0x00);
        HeadlessTestFixture fixture = null;
        try {
            fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(TRACE_DIR.resolve("s3k-cnz-sonic-tails.bk2"))
                    .withRecordingStartFrame(
                            TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                    .startPosition(trace.metadata().startX(), trace.metadata().startY())
                    .startPositionIsCentre()
                    .withFreshLevelStartLifecycle()
                    .build();
            if (bootstrap) {
                fixture.gameplayMode().activateRecordedHardwareAdmission();
                TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);
            } else {
                GameServices.level().getObjectManager().initVIntRunCounterPhaseOffset(
                        trace.initialVIntRunCounterPhaseOffset());
                GameServices.level().getObjectManager().initVblaCounter(
                        trace.initialVblankCounter() - 1);
                assertTrue(GameServices.level().consumePendingInitialProcessSpritesPass());
                TraceReplaySessionBootstrap.applyInitialRngSeedForReplay(trace.metadata());
            }
            TraceReplaySessionBootstrap.alignFrameCountersForReplayStart(
                    null, trace.getFrame(0));
            return captureRuntimeState();
        } finally {
            if (fixture != null) {
                fixture.abortHardwareTimingReplayRun();
            }
            sharedLevel.dispose();
            TraceReplaySessionBootstrap.restoreGameplayConfig(configSnapshot);
        }
    }

    private static RuntimeState captureRuntimeState() {
        ObjectManager manager = GameServices.level().getObjectManager();
        List<ObjectRow> objects = manager.getActiveObjects().stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .map(object -> new ObjectRow(object.getSlotIndex(),
                        object.getClass().getName(), object.getX(), object.getY()))
                .sorted(Comparator.comparingInt(ObjectRow::slot)
                        .thenComparing(ObjectRow::className))
                .toList();
        AbstractPlayableSprite player = GameServices.sprites().getMainPlayable();
        List<PlayerState> sidekicks = GameServices.sprites().getSidekicks().stream()
                .map(TestS3kCnzTraceReplay::capturePlayerState)
                .toList();
        return new RuntimeState(objects, objects.size(), manager.getFrameCounter(),
                manager.getVblaCounter(), GameServices.level().getFrameCounter(),
                GameServices.camera().getX(), GameServices.camera().getY(),
                GameServices.rng().getSeed(), capturePlayerState(player), sidekicks);
    }

    private static PlayerState capturePlayerState(AbstractPlayableSprite player) {
        return new PlayerState(player.getCentreX(), player.getCentreY(),
                player.getXSpeed(), player.getYSpeed(), player.getGSpeed(),
                TraceCharacterState.statusByteFromSprite(player),
                player.getAnimationId(), player.getMappingFrame());
    }

    private static TraceFrame traceFrame(TraceData trace, int frame) {
        return trace.getFrame(frame);
    }

    private TraceFrame driveReplayFrame(
            TraceData trace,
            HeadlessTestFixture fixture,
            TraceFrame previousDriveFrame,
            int traceIndex) {
        TraceFrame driveFrame = trace.getFrame(traceIndex);
        fixture.beginTraceRow(traceIndex, driveFrame.frame());
        TraceExecutionPhase phase =
                TraceReplayBootstrap.phaseForReplay(trace, previousDriveFrame, driveFrame);
        TraceReplayBootstrap.markVblankStarvedIterationForReplay(
                previousDriveFrame, driveFrame);
        driveScenarioReplayFrame(trace, fixture, phase);
        return driveFrame;
    }

    private void driveReplayToTraceFrame(TraceData trace,
                                         HeadlessTestFixture fixture,
                                         TraceReplayBootstrap.ReplayStartState replayStart,
                                         int targetTraceFrame) {
        TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : null;
        for (int traceIndex = replayStart.startingTraceIndex();
             traceIndex <= targetTraceFrame;
             traceIndex++) {
            previousDriveFrame = driveReplayFrame(
                    trace, fixture, previousDriveFrame, traceIndex);
        }
    }

    private record BootstrappedCnzReplay(TraceData trace,
                                         SharedLevel sharedLevel,
                                         HeadlessTestFixture fixture,
                                         TraceReplayBootstrap.ReplayStartState replayStart,
                                         TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot)
            implements AutoCloseable {
        @Override
        public void close() {
            fixture.abortHardwareTimingReplayRun();
            sharedLevel.dispose();
            TraceReplaySessionBootstrap.restoreGameplayConfig(configSnapshot);
        }
    }

    private enum MetadataVariant {
        TRACE_PROFILE,
        SIDEKICK_CHARACTERS,
        CHECKPOINT_NAMES,
        RECORDING_FILENAME,
        RNG_SEED,
        START_POSITION
    }

    private record ObjectRow(int slot, String className, int x, int y) {
    }

    private record PlayerState(int centreX, int centreY, int xSpeed, int ySpeed,
                               int groundSpeed, int status, int animation, int mapping) {
    }

    private record RuntimeState(List<ObjectRow> objects, int activeSlotCount,
                                int objectFrame, int vbla, int levelFrame,
                                int cameraX, int cameraY, long rngSeed,
                                PlayerState player, List<PlayerState> sidekicks) {
    }

}
