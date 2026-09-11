package com.openggf.game.sonic3k.events;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Mgz2LevelCollapseSolidInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.LevelGeometry;
import com.openggf.level.LevelTilemapManager;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the MGZ Act 2 collapse event flow.
 */
class TestSonic3kMgz2CollapseEvents {

    private static final int COLLAPSE_STARTUP_EVENT_CALLS = 0x17;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
        GameServices.level().setLevel(new SyntheticMgzCollapseLevel());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void collapseTrigger_runsTimedShakeBeforeOpeningClear() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.requestLevelCollapse();

        events.update(1, 0);
        assertFalse(events.isCollapseActive(),
                "The boss SST's Events_fg_4 write becomes visible on the following screen-event dispatch");
        events.update(1, 1);

        SyntheticMgzCollapseLevel level = (SyntheticMgzCollapseLevel) GameServices.level().getCurrentLevel();
        assertTrue(events.isCollapseActive());
        assertEquals(0, events.getCollapseMutationCount());
        assertEquals(4, events.getScreenEventRoutine());
        assertEquals(0, events.getCollapseSolidCountForTest());
        assertFilled(level.getMap(), 121, 14, 3, 3, 1);
    }

    @Test
    void collapseStartupShakeClearsOpeningAndCreatesCollapseSolids() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.requestLevelCollapse();

        for (int frame = 0; frame < COLLAPSE_STARTUP_EVENT_CALLS; frame++) {
            events.update(1, frame);
        }

        SyntheticMgzCollapseLevel level = (SyntheticMgzCollapseLevel) GameServices.level().getCurrentLevel();
        assertTrue(events.isCollapseActive());
        assertEquals(1, events.getCollapseMutationCount());
        assertEquals(20, events.getCollapseSolidCountForTest());
        assertCleared(level.getMap(), 121, 14, 3, 3);
    }

    @Test
    void collapseStartupClearDoesNotInvalidateForegroundTilemap() throws Exception {
        SyntheticMgzCollapseLevel level = (SyntheticMgzCollapseLevel) GameServices.level().getCurrentLevel();
        LevelTilemapManager tilemaps = installTilemapManager(level);
        tilemaps.setForegroundTilemapDirty(false);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.requestLevelCollapse();

        for (int frame = 0; frame < COLLAPSE_STARTUP_EVENT_CALLS; frame++) {
            events.update(1, frame);
        }

        assertCleared(level.getMap(), 121, 14, 3, 3);
        assertFalse(tilemaps.isForegroundTilemapDirty(),
                "MGZ2 collapse startup must clear layout RAM without redrawing the visible foreground plane");
    }

    @Test
    void collapseStartupClearSnapshotsForegroundTilemapIfItWasDirty() throws Exception {
        SyntheticMgzCollapseLevel level = (SyntheticMgzCollapseLevel) GameServices.level().getCurrentLevel();
        LevelTilemapManager tilemaps = installTilemapManager(level);
        tilemaps.setForegroundTilemapDirty(true);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.requestLevelCollapse();

        for (int frame = 0; frame < COLLAPSE_STARTUP_EVENT_CALLS; frame++) {
            events.update(1, frame);
        }

        assertCleared(level.getMap(), 121, 14, 3, 3);
        assertFalse(tilemaps.isForegroundTilemapDirty(),
                "If the FG tilemap is dirty at impact, the engine must snapshot the visible plane before clearing layout RAM");
        assertVisibleTilemapSnapshotAt(tilemaps, level, 121, 14);
    }

    @Test
    void repeatedCollapseRequestsDoNotRecreateSolidsOrResetTransition() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.requestLevelCollapse();

        for (int frame = 0; frame < COLLAPSE_STARTUP_EVENT_CALLS; frame++) {
            events.update(1, frame);
        }
        assertEquals(1, events.getCollapseMutationCount());
        assertEquals(20, events.getCollapseSolidCountForTest());

        events.requestLevelCollapse();
        for (int frame = 0; frame < 0x14; frame++) {
            events.update(1, COLLAPSE_STARTUP_EVENT_CALLS + frame);
        }

        assertTrue(events.isCollapseActive());
        assertEquals(1, events.getCollapseMutationCount(),
                "Repeated floor-impact handoff while the collapse is active must not restart the startup clear");
        assertEquals(20, events.getCollapseSolidCountForTest(),
                "Repeated floor-impact handoff must reuse the original invisible collapse solids");
    }

    @Test
    void collapseProgression_finishesAndClearsLowerRegion() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        GameServices.camera().setX((short) 0x3C80);
        events.requestLevelCollapse();

        for (int frame = 0; frame < 512 && !events.isCollapseFinished(); frame++) {
            events.update(1, frame);
        }

        SyntheticMgzCollapseLevel level = (SyntheticMgzCollapseLevel) GameServices.level().getCurrentLevel();
        assertTrue(events.isCollapseFinished());
        assertFalse(events.isCollapseActive());
        assertEquals(8, events.getScreenEventRoutine());
        assertEquals(2, events.getCollapseMutationCount());
        assertEquals(0, events.getBossBgScrollVelocityForTest());
        assertEquals(0x3C80, events.getBossBgScrollOffsetForTest(),
                "ROM loc_51484 seeds Events_bg+$0C from Camera_X_pos_copy when collapse finishes");
        assertCleared(level.getMap(), 121, 14, 3, 3);
        assertCleared(level.getMap(), 121, 11, 3, 3);
    }

    @Test
    void collapseSolidStopsBeingSolidAsSoonAsDeleteStateStarts() {
        boolean[] delete = { false };
        Mgz2LevelCollapseSolidInstance solid = new Mgz2LevelCollapseSolidInstance(
                0x3C90, 0x05C0, () -> 0, () -> delete[0]);

        assertTrue(solid.isSolidFor(null));
        assertTrue(solid.bypassesOffscreenSolidGate(),
                "Obj_MGZ2LevelCollapseSolid calls SolidObjectFull2 even though its sprite is always invisible");
        assertTrue(solid.usesInclusiveRightEdge(),
                "SolidObjectFull2 accepts the exact d1*2 right edge");
        assertTrue(solid.airborneStaleStandingBitReturnsNoContact(null),
                "A jumping rider must take SolidObjectFull2_1P's stale-standing-bit return, not its upward lift");
        assertTrue(solid.usesInstanceSolidStateLatchKey(),
                "The native standing bit must survive this carrier's per-frame dynamic-spawn Y rewrite");
        assertEquals(0x0005, solid.romObjectCodePointerHighWord(),
                "Tails_CPU_interact stores Obj_MGZ2LevelCollapseSolid's $0005180A pointer high word");

        delete[0] = true;

        assertFalse(solid.isSolidFor(null),
                "Invisible MGZ2 collapse solids must stop accepting contacts before their next update removes them");
    }

    @Test
    void collapseSolidsRestoreThroughGenericRecreateWithoutExplicitCodec() throws Exception {
        ObjectManager objectManager = installObjectManager();
        Sonic3kMGZEvents events = activeMgzEvents();
        events.requestLevelCollapse();

        for (int frame = 0; frame < COLLAPSE_STARTUP_EVENT_CALLS; frame++) {
            events.update(1, frame);
        }
        ArrayList<Mgz2LevelCollapseSolidInstance> capturedSolids = liveCollapseSolids(objectManager);
        assertEquals(20, capturedSolids.size(),
                "precondition: collapse startup should create exactly the 20 invisible solid carriers");
        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId firstCapturedId = captureTable.idFor(capturedSolids.getFirst());
        assertNotNull(firstCapturedId,
                "ObjectManager capture identity table must register dynamic MGZ2 collapse solids");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        var snapshot = registry.capture();

        for (Mgz2LevelCollapseSolidInstance solid : capturedSolids) {
            objectManager.removeDynamicObject(solid);
        }
        objectManager.createDynamicObject(() -> new Mgz2LevelCollapseSolidInstance(
                0x4000, 0x0600, () -> 0x40, () -> false));
        assertEquals(1, liveCollapseSolids(objectManager).size(),
                "diverge step should leave one unrelated live collapse solid before restore");

        registry.restore(snapshot);

        ArrayList<Mgz2LevelCollapseSolidInstance> restoredSolids = liveCollapseSolids(objectManager);
        assertEquals(20, restoredSolids.size(),
                "restore must recreate the captured collapse solids exactly once, not drop or double them");
        assertTrue(restoredSolids.stream().allMatch(solid -> solid.isSolidFor(null)),
                "restored collapse solids should remain solid while the MGZ collapse delete state is false");
        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertTrue(restoredSolids.stream().anyMatch(solid -> firstCapturedId.equals(restoredTable.idFor(solid))),
                "restored collapse solids should retain their captured dynamic rewind identities");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Mgz2LevelCollapseSolidInstance.class.getName()),
                "Mgz2LevelCollapseSolidInstance must restore through RewindRecreatable genericRecreate, "
                        + "not a handwritten S3K dynamic codec");
    }

    @Test
    void collapseFinishImmediatelyRestoresNormalVScrollAndRedrawsFinalClear() throws Exception {
        SyntheticMgzCollapseLevel level = (SyntheticMgzCollapseLevel) GameServices.level().getCurrentLevel();
        LevelTilemapManager tilemaps = installTilemapManager(level);
        tilemaps.setForegroundTilemapDirty(false);

        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        GameServices.camera().setX((short) 0x3C80);
        events.requestLevelCollapse();

        int frame = 0;
        for (; frame < 512 && !events.isCollapseFinished(); frame++) {
            events.update(1, frame);
        }

        assertNull(events.buildCollapseForegroundVScrollOverride(0x3C80),
                "MGZ2SE_MoveBG must restore normal foreground VScroll immediately on the terminal clear frame");
        assertTrue(tilemaps.isForegroundTilemapDirty(),
                "The terminal 3x3 layout clear must rebuild Plane A before normal VScroll resumes");
        assertCleared(level.getMap(), 121, 11, 3, 3);
    }

    @Test
    void moveBgScrollAcceleratesAfterCollapseCompletes() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        GameServices.camera().setX((short) 0x3C80);
        events.requestLevelCollapse();

        int frame = 0;
        for (; frame < 512 && !events.isCollapseFinished(); frame++) {
            events.update(1, frame);
        }
        assertEquals(8, events.getScreenEventRoutine());
        assertEquals(0, events.getBossBgScrollVelocityForTest());
        assertEquals(0x3C80, events.getBossBgScrollOffsetForTest());

        for (int i = 0; i < 40; i++) {
            events.update(1, frame + i);
        }

        assertEquals(0x14000, events.getBossBgScrollVelocityForTest(),
                "MGZ2SE_MoveBG should add $800 to Events_bg+$08 each frame until $50000");
        assertTrue(events.getBossBgScrollOffsetForTest() > 0x3C80,
                "MGZ2SE_MoveBG swaps the velocity longword and adds its high word into Events_bg+$0C");
    }

    @Test
    void collapseProgressionUsesRom16Dot16ScrollAcceleration() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.requestLevelCollapse();

        for (int frame = 0; frame < COLLAPSE_STARTUP_EVENT_CALLS; frame++) {
            events.update(1, frame);
        }
        events.update(1, COLLAPSE_STARTUP_EVENT_CALLS); // first active tick: column 6 has zero delay

        assertNull(events.buildCollapseForegroundVScrollOverride(0x3C80),
                "ROM adds $500 to a 16:16 velocity accumulator; the first active tick has not moved a full pixel");

        for (int frame = 0; frame < 15; frame++) {
            events.update(1, COLLAPSE_STARTUP_EVENT_CALLS + 1 + frame);
        }
        short[] override = events.buildCollapseForegroundVScrollOverride(0x3C80);

        assertEquals(20, override.length);
        assertEquals(0, override[0],
                "the tilemap shader expects per-column VScroll deltas, so delayed columns need no extra offset");
        assertEquals(-events.getCollapseScrollPositionCopy()[6], override[12],
                "ROM writes Camera_Y_pos_copy minus displacement; the tilemap shader adds this delta to worldY, "
                        + "so the negative value makes the preserved terrain appear to fall down");
        assertEquals(override[12], override[13],
                "each 32px collapse block uses two 16px VScroll columns");
    }

    @Test
    void collapseSolidReadsRawAccumulatorPastVisualScrollCap() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.setScreenEventRoutine(4);
        events.setCollapseInitialized(true);
        events.setCollapseFrameCounter(0x20);

        int[] velocity = new int[10];
        int[] fixedPosition = new int[10];
        int[] visualPosition = new int[10];
        velocity[0] = 0x60000;
        fixedPosition[0] = 0x2DF0000;
        visualPosition[0] = 0x2DF;
        events.setCollapseScrollVelocity(velocity);
        events.setCollapseScrollFixedPosition(fixedPosition);
        events.setCollapseScrollPosition(visualPosition);

        assertEquals(0x2E5, events.getCollapseSolidObjectPassScrollForTest(0),
                "Obj_MGZ2LevelCollapseSolid reads the raw high word after the pending $500 acceleration");

        events.update(1, 0);

        assertEquals(0x2E0, events.getCollapseScrollPositionCopy()[0],
                "loc_51436 caps only the draw displacement and completion comparison");
        assertEquals(0x2E5, events.getCollapseScrollFixedPositionCopy()[0] >>> 16,
                "the HScroll-table 16:16 accumulator must retain its terminal overshoot");
    }

    @Test
    void cappedColumnKeepsAcceleratingUntilEveryColumnFinishes() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.setScreenEventRoutine(4);
        events.setCollapseInitialized(true);
        events.setCollapseFrameCounter(0x20);

        int[] velocity = new int[10];
        int[] fixedPosition = new int[10];
        int[] visualPosition = new int[10];
        velocity[0] = 0x60500;
        fixedPosition[0] = 0x2E50500;
        visualPosition[0] = 0x2E0;
        events.setCollapseScrollVelocity(velocity);
        events.setCollapseScrollFixedPosition(fixedPosition);
        events.setCollapseScrollPosition(visualPosition);

        assertEquals(0x2EB, events.getCollapseSolidObjectPassScrollForTest(0),
                "an early capped column still projects its next raw carrier position");

        events.update(1, 0);

        assertEquals(0x2E0, events.getCollapseScrollPositionCopy()[0]);
        assertEquals(0x2EB, events.getCollapseScrollFixedPositionCopy()[0] >>> 16,
                "loc_5142A advances all raw accumulators until d1 reports every column complete");
        assertTrue(events.isCollapseActive(),
                "later delayed columns must keep the collapse routine active");
    }

    @Test
    void carrierDeletesWhenPendingStepCompletesLastDelayedColumn() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.setScreenEventRoutine(4);
        events.setCollapseInitialized(true);
        events.setCollapseFrameCounter(0x120);

        int[] velocity = new int[10];
        int[] fixedPosition = new int[10];
        int[] visualPosition = new int[10];
        java.util.Arrays.fill(velocity, 0x60000);
        java.util.Arrays.fill(fixedPosition, 0x2E00000);
        java.util.Arrays.fill(visualPosition, 0x2E0);
        fixedPosition[8] = 0x2DF0000;
        visualPosition[8] = 0x2DF;
        events.setCollapseScrollVelocity(velocity);
        events.setCollapseScrollFixedPosition(fixedPosition);
        events.setCollapseScrollPosition(visualPosition);

        assertTrue(events.isCollapseSolidDeleteStateForTest(),
                "the carrier pass must observe that the pending event step completes all ten columns");
    }

    @Test
    void collapseVScrollLeavesNonCollapseScreenColumnsUnshifted() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        events.requestLevelCollapse();

        for (int frame = 0; frame < COLLAPSE_STARTUP_EVENT_CALLS; frame++) {
            events.update(1, frame);
        }
        for (int frame = 0; frame < 16; frame++) {
            events.update(1, COLLAPSE_STARTUP_EVENT_CALLS + frame);
        }

        short[] override = events.buildCollapseForegroundVScrollOverride(0x3C70);

        assertEquals(0, override[0],
                "per-column VScroll overrides are deltas; columns outside the collapsing floor must not inherit absolute camera scroll");
    }

    private static Sonic3kMGZEvents activeMgzEvents() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        Sonic3kMGZEvents events = manager.getMgzEvents();
        assertNotNull(events, "MGZ events should be installed for MGZ Act 2");
        return events;
    }

    private static ObjectManager installObjectManager() throws NoSuchFieldException, IllegalAccessException {
        ObjectManager objectManager = new ObjectManager(
                java.util.List.of(),
                new Sonic3kObjectRegistry(),
                0,
                null,
                null,
                GameServices.graphics(),
                GameServices.camera(),
                TestEnvironment.objectServices());
        objectManager.reset(GameServices.camera().getX());

        Field field = GameServices.level().getClass().getDeclaredField("objectManager");
        field.setAccessible(true);
        field.set(GameServices.level(), objectManager);
        return objectManager;
    }

    private static ArrayList<Mgz2LevelCollapseSolidInstance> liveCollapseSolids(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == Mgz2LevelCollapseSolidInstance.class && !object.isDestroyed())
                .map(Mgz2LevelCollapseSolidInstance.class::cast)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static void assertCleared(Map map, int startX, int startY, int width, int height) {
        assertFilled(map, startX, startY, width, height, 0);
    }

    private static void assertFilled(Map map, int startX, int startY, int width, int height, int expected) {
        for (int y = startY; y < startY + height; y++) {
            for (int x = startX; x < startX + width; x++) {
                assertEquals(expected, map.getValue(0, x, y),
                        "unexpected foreground block at (" + x + "," + y + ")");
            }
        }
    }

    private static void assertVisibleTilemapSnapshotAt(LevelTilemapManager tilemaps,
                                                       SyntheticMgzCollapseLevel level,
                                                       int blockX,
                                                       int blockY) {
        byte[] data = tilemaps.getForegroundTilemapData();
        assertNotNull(data, "dirty FG tilemap should have been built before the layout RAM clear");
        int tileX = blockX * level.getBlockPixelSize() / Pattern.PATTERN_WIDTH;
        int tileY = blockY * level.getBlockPixelSize() / Pattern.PATTERN_HEIGHT;
        int offset = (tileY * tilemaps.getForegroundTilemapWidthTiles() + tileX) * 4;

        assertEquals((byte) 0xFF, data[offset + 3],
                "The visible FG tilemap should still contain the pre-clear block instead of rebuilding from the cleared map");
    }

    private static LevelTilemapManager installTilemapManager(SyntheticMgzCollapseLevel level)
            throws NoSuchFieldException, IllegalAccessException {
        LevelGeometry geometry = new LevelGeometry(
                level,
                level.getMap().getWidth() * 128,
                level.getMap().getHeight() * 128,
                level.getMap().getWidth() * 128,
                level.getMap().getWidth() * 128,
                level.getMap().getHeight() * 128,
                level.getBlockPixelSize(),
                level.getChunksPerBlockSide());
        LevelTilemapManager tilemaps = new LevelTilemapManager(
                geometry,
                GameServices.graphics(),
                GameServices.gameState());
        Field field = GameServices.level().getClass().getDeclaredField("tilemapManager");
        field.setAccessible(true);
        field.set(GameServices.level(), tilemaps);
        return tilemaps;
    }

    private static final class SyntheticMgzCollapseLevel extends AbstractLevel {

        private SyntheticMgzCollapseLevel() {
            super(0);
            palettes = new Palette[] { new Palette(), new Palette(), new Palette(), new Palette() };
            patterns = new Pattern[] { new Pattern(), new Pattern(), new Pattern(), new Pattern() };
            patternCount = patterns.length;
            chunks = new Chunk[] { buildChunk() };
            chunkCount = chunks.length;
            blocks = buildBlocks();
            blockCount = blocks.length;
            solidTiles = new SolidTile[] {
                    new SolidTile(0, new byte[SolidTile.TILE_SIZE_IN_ROM],
                            new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0)
            };
            solidTileCount = solidTiles.length;
            map = new Map(2, 128, 32);
            fillMap((byte) 1);
            objects = new ArrayList<ObjectSpawn>();
            rings = new ArrayList<RingSpawn>();
            ringSpriteSheet = null;
            minX = 0;
            maxX = 0x8000;
            minY = 0;
            maxY = 0x2000;
        }

        private void fillMap(byte value) {
            for (int y = 0; y < map.getHeight(); y++) {
                for (int x = 0; x < map.getWidth(); x++) {
                    map.setValue(0, x, y, value);
                    map.setValue(1, x, y, value);
                }
            }
        }

        private static Chunk buildChunk() {
            Chunk chunk = new Chunk();
            chunk.restoreState(new int[] { 0, 1, 2, 3, 0, 0 });
            return chunk;
        }

        private static Block[] buildBlocks() {
            Block[] blocks = new Block[80];
            for (int blockIndex = 0; blockIndex < blocks.length; blockIndex++) {
                Block block = new Block();
                int[] state = new int[64];
                for (int i = 0; i < state.length; i++) {
                    int chunkIndex = blockIndex == 0 ? 0x03FF : 0;
                    state[i] = new ChunkDesc(chunkIndex).get();
                }
                block.restoreState(state);
                blocks[blockIndex] = block;
            }
            return blocks;
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }
    }
}
