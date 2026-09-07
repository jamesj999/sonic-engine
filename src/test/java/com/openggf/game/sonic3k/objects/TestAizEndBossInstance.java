package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.GameRng;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizEndBossInstance {
    private Camera camera;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        camera = GameServices.camera();
        camera.resetState();
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(false);
        constructionContext().remove();
        SessionManager.clear();
    }

    @Test
    void initLocksCameraAndLoadsPaletteIntoOwnershipRegistryLineOne() throws Exception {
        camera.setX((short) 0x48A0);

        byte[] paletteLine = new byte[32];
        paletteLine[0] = 0x0E;
        paletteLine[1] = (byte) 0xEE;
        paletteLine[2] = 0x08;
        paletteLine[3] = (byte) 0x88;

        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());
        services.withRom(new FixedReadRom(paletteLine));
        services.registry.beginFrame();

        AizEndBossInstance boss = buildBoss(services);
        boss.update(0, null);
        services.registry.resolveInto(services.level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.AIZ_END_BOSS, services.registry.ownerAt(PaletteSurface.NORMAL, 1, 0));
        assertEquals(S3kPaletteOwners.AIZ_END_BOSS, services.registry.ownerAt(PaletteSurface.NORMAL, 1, 1));
        assertColorWord(services.level.getPalette(1), 0, 0x0EEE);
        assertColorWord(services.level.getPalette(1), 1, 0x0888);
        assertEquals(0x4880, camera.getMinX() & 0xFFFF);
        assertEquals(0x4880, camera.getMaxX() & 0xFFFF);
    }

    @Test
    void retreatSubmergeAnimationRunsRepositionCallback() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);
        invokeNoArg(boss, "beginReSubmerge");

        for (int frame = 0; frame < 45; frame++) {
            boss.update(frame, null);
        }

        assertEquals(12, boss.getState().routine);
    }

    @Test
    void retreatSubmergeCancelsHitFlashWithoutRestoringCollisionBehindWaterfall() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);
        invokeNoArg(boss, "onEmergeComplete");
        boss.update(0, null);
        boss.onPlayerAttack(null, null);

        assertTrue(boss.getState().invulnerable);
        assertEquals(0, boss.getCollisionFlags());

        invokeNoArg(boss, "beginReSubmerge");

        assertFalse(boss.getState().invulnerable,
                "ROM sub_69C94 clears the hit-flash timer/status when Robotnik submerges");
        assertEquals(0, boss.getState().invulnerabilityTimer);
        for (int frame = 0; frame < 0x20; frame++) {
            boss.update(frame, null);
            assertEquals(0, boss.getCollisionFlags(),
                    "Robotnik must remain non-interactive throughout the waterfall phase");
        }
    }

    @Test
    void fireSignalTriggersBurnBridgeVariant() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);

        AizCollapsingLogBridgeObjectInstance bridge = new AizCollapsingLogBridgeObjectInstance(
                new ObjectSpawn(0, 0, 0x2C, 0x80, 0, false, 0));
        bridge.setServices(new TestObjectServices().withCamera(camera));

        invokeNoArg(boss, "onFireTimerExpired");
        bridge.update(0, null);

        assertTrue(readBoolean(bridge, "segmentsSpawned"));
        assertFalse(bridge.isHighPriority(),
                "the fire bridge is not above every terrain palette");
        assertEquals(0b0111, bridge.getTileOcclusionPaletteMask(),
                "the fire bridge draws above palette 3 water but below palettes 0-2 terrain");
    }

    @Test
    void retreatDoesNotClearBridgeBurnLatchBeforeBridgeConsumesIt() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);

        AizCollapsingLogBridgeObjectInstance bridge = new AizCollapsingLogBridgeObjectInstance(
                new ObjectSpawn(0, 0, 0x2C, 0x80, 0, false, 0));
        bridge.setServices(new TestObjectServices().withCamera(camera));

        invokeNoArg(boss, "onFireTimerExpired");
        invokeNoArg(boss, "beginRetreat");
        bridge.update(0, null);

        assertTrue(readBoolean(bridge, "segmentsSpawned"));
    }

    @Test
    void cameraScrollPhaseAdvancesArenaBoundsWithRomParity() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);
        camera.setX((short) 0x4880);
        camera.setMinX((short) 0x4880);
        camera.setMaxX((short) 0x4880);

        invokeNoArg(boss, "updateCameraScroll");

        assertEquals(0x4882, camera.getMinX() & 0xFFFF);
        assertEquals(0x4882, camera.getMaxX() & 0xFFFF);

        camera.setMinX((short) 0x48E0);
        camera.setMaxX((short) 0x48E0);
        invokeNoArg(boss, "updateCameraScroll");

        assertEquals(0x48E0, camera.getMinX() & 0xFFFF);
        assertEquals(0x48E2, camera.getMaxX() & 0xFFFF);
    }

    @Test
    void revealedRawAnimationUsesRomCallbackTimingBeforeHover() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);
        invokeNoArg(boss, "onEmergeComplete");

        for (int frame = 0; frame < 19; frame++) {
            invokeNoArg(boss, "updateRevealed");
        }
        assertEquals(4, boss.getState().routine,
                "byte_69DB3 should not hit its $F4 callback before the 20th revealed update");
        assertEquals(0, boss.getMappingFrame(),
                "byte_69DB3's final visible entry is mapping frame 0 for one update");

        invokeNoArg(boss, "updateRevealed");

        assertEquals(6, boss.getState().routine,
                "byte_69DB3 $F4 callback should enter loc_6933A hover on the 20th update");
    }

    @Test
    void cameraScrollPhaseLetsCameraFollowIntoExpandedRightBound() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x49A8);
        player.setCentreY((short) 0x0100);
        camera.setFocusedSprite(player);
        camera.setX((short) 0x48E0);
        camera.setY((short) 0x00A0);
        camera.setMinX((short) 0x48E0);
        camera.setMaxX((short) 0x48E0);
        camera.setMinY((short) 0);
        camera.setMaxY((short) 0x1000);

        invokeNoArg(boss, "updateCameraScroll");
        camera.updatePosition();

        assertEquals(0x48E2, camera.getX() & 0xFFFF);
    }

    @Test
    void hoverSetupDoesNotChangeBossRenderFacing() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);

        invokeNoArg(boss, "doMainInit");
        invokeNoArg(boss, "beginHover");

        assertTrue(boss.isFacingRight());
    }

    @Test
    void repositionSelectorUsesRomRawMaskNotLowTwoBits() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 0xA1AFBE1BL);
        services.withRng(rng);

        AizEndBossInstance boss = buildBoss(services);
        boss.getState().x = 0x48A0;
        boss.getState().y = 0x01BD;
        boss.getState().xFixed = 0x48A00000;
        boss.getState().yFixed = 0x01BD0000;

        invokeNoArg(boss, "selectRandomPosition");

        assertEquals(0x08, boss.getAngle(),
                "Obj_AIZEndBoss loc_69A66 masks Random_Number with #$C; "
                        + "raw A1AF5778 should select target index $8, not low-bit index 0 "
                        + "(sonic3k.asm:138748-138756)");
        assertEquals(((0x4A40 - 0x48A0) << 8) / 0x80, boss.getState().xVel,
                "Target index $8 should travel to _unkFA84+$160 = $4A40 over $80 frames");
    }

    @Test
    void repositionVelocityUsesFullLongwordPositionSubpixels() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 0xA1AFBE1BL);
        services.withRng(rng);

        AizEndBossInstance boss = buildBoss(services);
        boss.getState().x = 0x48A0;
        boss.getState().y = 0x01BD;
        boss.getState().xFixed = 0x48A00000;
        boss.getState().yFixed = 0x01BD8000;

        invokeNoArg(boss, "selectRandomPosition");

        assertEquals(-0x003B, boss.getState().yVel,
                "loc_69A66 subtracts the full longword y_pos before doubling the delta; "
                        + "$01BD.8000 to target $01A0 should produce y_vel=-$3B "
                        + "(sonic3k.asm:138764-138771)");
    }

    @Test
    void defeatCapsuleHandoffWaitUsesObjWaitPredecrementSemantics() throws Exception {
        RecordingServices services = new RecordingServices();
        services.withCamera(camera);
        services.withGameState(new GameStateManager());

        AizEndBossInstance boss = buildBoss(services);
        boss.getState().hitCount = 1;

        boss.onPlayerAttack(null, null);

        for (int frame = 0; frame < 0x38; frame++) {
            boss.update(frame, null);
            assertTrue(!readBoolean(boss, "eggCapsuleSignal"),
                    "The ship/explosion handoff remains in Wait_Draw before the independent Obj_Wait begins");
        }

        for (int frame = 0; frame < 0x7F; frame++) {
            boss.update(frame, null);
            assertTrue(!readBoolean(boss, "eggCapsuleSignal"),
                    "Obj_Wait branches only after --$2E is negative, so $2E=$7F "
                            + "must not spawn the capsule while it is still non-negative "
                            + "(sonic3k.asm:177944-177952)");
        }

        boss.update(0x7F, null);

        assertTrue(readBoolean(boss, "eggCapsuleSignal"),
                "The AIZ capsule callback should run on the negative transition "
                        + "after 128 Obj_Wait entries from $2E=$7F");
    }

    private static AizEndBossInstance buildBoss(ObjectServices services) throws Exception {
        if (services instanceof TestObjectServices testServices && testServices.configuration() == null) {
            testServices.withConfiguration(SonicConfigurationService.getInstance());
        }
        ThreadLocal<ObjectServices> context = constructionContext();
        context.set(services);
        try {
            AizEndBossInstance boss = new AizEndBossInstance(
                    new ObjectSpawn(0x48E0, 0x015A, 0x92, 0, 0, false, 0));
            boss.setServices(services);
            return boss;
        } finally {
            context.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<ObjectServices> constructionContext() throws Exception {
        Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
        field.setAccessible(true);
        return (ThreadLocal<ObjectServices>) field.get(null);
    }

    private static void invokeNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static boolean readBoolean(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF);
    }

    private static final class RecordingServices extends TestObjectServices {
        private final StubLevel level = new StubLevel();
        private final PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();

        @Override
        public Level currentLevel() {
            return level;
        }

        @Override
        public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
            return registry;
        }
    }

    private static final class FixedReadRom extends Rom {
        private final byte[] bytes;

        private FixedReadRom(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public byte[] readBytes(long offset, int count) {
            return bytes;
        }
    }

    private static final class StubLevel implements Level {
        private final Palette[] palettes = new Palette[] {
                new Palette(), new Palette(), new Palette(), new Palette()
        };

        Palette[] palettes() {
            return palettes;
        }

        @Override public int getPaletteCount() { return palettes.length; }
        @Override public Palette getPalette(int index) { return palettes[index]; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { throw new UnsupportedOperationException(); }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { throw new UnsupportedOperationException(); }
        @Override public int getBlockCount() { return 0; }
        @Override public Block getBlock(int index) { throw new UnsupportedOperationException(); }
        @Override public SolidTile getSolidTile(int index) { throw new UnsupportedOperationException(); }
        @Override public Map getMap() { return null; }
        @Override public List<ObjectSpawn> getObjects() { return List.of(); }
        @Override public List<RingSpawn> getRings() { return List.of(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 0; }
    }
}
