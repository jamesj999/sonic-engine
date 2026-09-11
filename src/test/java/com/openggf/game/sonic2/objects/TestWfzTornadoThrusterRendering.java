package com.openggf.game.sonic2.objects;

import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.Sonic2ObjectArtProvider;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.events.Sonic2ZoneEvents;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteFrame;
import com.openggf.level.render.SpriteFramePiece;
import com.openggf.physics.Sensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@RequiresRom(SonicGame.SONIC_2)
@ExtendWith(SingletonResetExtension.class)
class TestWfzTornadoThrusterRendering {
    private static final int ROM_PARENT_X = 0x3192;
    private static final int ROM_PARENT_Y = 0x0472;
    private static final int SUBTYPE_WFZ_END = 0x54;
    private static final int SUBTYPE_THRUSTER = 0x5C;

    private EngineContext previousEngineContext;
    private GameModule previousModule;
    private RecordingGraphicsManager graphics;
    private Sonic2ObjectArtProvider provider;
    private ObjectRenderManager renderManager;
    private LevelManager levelManager;
    private LiveObjectServices objectServices;
    private ObjectManager objectManager;

    @BeforeEach
    void setUp() throws Exception {
        previousEngineContext = EngineServices.current();
        previousModule = GameModuleRegistry.getCurrent();
        graphics = new RecordingGraphicsManager();
        EngineServices.configure(withGraphics(previousEngineContext, graphics));

        Sonic2GameModule module = new Sonic2GameModule();
        GameModuleRegistry.setCurrent(module);
        SessionManager.clear();
        module.createGame(TestEnvironment.currentRom());
        GameplayModeContext gameplay = SessionManager.openGameplaySession(module);
        TestEnvironment.activeGameplayMode();

        provider = (Sonic2ObjectArtProvider) module.getObjectArtProvider();
        provider.loadArtForZone(Sonic2ZoneConstants.ROM_ZONE_WFZ);
        renderManager = new ObjectRenderManager(provider);
        levelManager = gameplay.getLevelManager();
        setField(levelManager, "objectRenderManager", renderManager);
        setCurrentLevel(levelManager, gameplay, mock(Level.class));
        levelManager.refreshObjectArtPatterns();

        objectServices = new LiveObjectServices(renderManager);
        objectServices.withCamera(GameServices.camera());
        objectServices.withParallaxManager(GameServices.parallax());
        objectServices.withSpriteManager(GameServices.sprites());
        objectServices.withGraphicsManager(graphics);
        objectServices.withLevelManager(levelManager);
        objectServices.withConfiguration(SonicConfigurationService.getInstance());
        objectManager = new ObjectManager(
                List.of(),
                module.createObjectRegistry(),
                module.getPlaneSwitcherObjectId(),
                module.getPlaneSwitcherConfig(),
                null,
                graphics,
                GameServices.camera(),
                objectServices);
        objectServices.setObjectManager(objectManager);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.setCurrent(previousModule);
        EngineServices.configure(previousEngineContext);
    }

    @Test
    void liveWfzThrusterChildRendersRocketBodyAndAlternatingLeftFacingFlames() throws Exception {
        assertNull(renderManager.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER),
                "WFZ level art must not pre-load the runtime Tornado thruster sheet");

        new PlcRequestEvents().request(Sonic2Constants.PLC_TORNADO);

        PatternSpriteRenderer renderer =
                renderManager.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER);
        assertNotNull(renderer, "PLCID_Tornado must publish the ObjB2 $5C renderer");
        assertTrue(renderer.isReady(), "the runtime renderer must be cache-ready in the request frame");
        int stablePatternBase = renderer.getPatternBase();
        assertTrue(stablePatternBase >= 0);

        TornadoObjectInstance parent = objectManager.createDynamicObject(() ->
                new TornadoObjectInstance(new ObjectSpawn(
                        ROM_PARENT_X, ROM_PARENT_Y, Sonic2ObjectIds.TORNADO,
                        SUBTYPE_WFZ_END, 0, false, 0)));
        assertNotNull(parent);
        // ObjectManager faithfully gives a dynamic spawn its routine-0 frame.
        // The fixture jumps directly to the final wait frame, so consume that
        // init pass before priming the ROM's leader wait counter.
        parent.consumePendingInitRoutine();
        TestPlayableSprite player = new TestPlayableSprite(
                (short) ROM_PARENT_X, (short) 0x05EC);
        GameServices.camera().setFocusedSprite(player);
        GameServices.camera().setX((short) 0x3100);

        // Select the final ROM wait frame so the real ObjB2 routine performs its
        // LoadChildObject sequence. The child is then discovered in the live manager.
        setField(parent, "leaderWaitCounter", 0x3F);
        objectManager.update(GameServices.camera().getX(), player, List.of(), 0, false);

        TornadoObjectInstance child = findLiveTornadoSubtype(SUBTYPE_THRUSTER);
        assertNotNull(child, "the real WFZ ending parent must spawn its $5C child");

        // Continue from the ROM's active-thruster state at the capture position.
        // TornadoObjectInstance currentX/currentY are its native x_pos/y_pos fields;
        // inspect them directly instead of treating generic getX/getY as sprite bounds.
        setRomPosition(parent, ROM_PARENT_X, ROM_PARENT_Y);
        setField(parent, "routineSecondary", 2);
        setField(child, "routineSecondary", 2);
        setField(child, "animFrameIndex", 0);

        objectManager.update(GameServices.camera().getX(), player, List.of(), 1, false);
        assertRomFollowerPosition(parent, child);
        graphics.clearDraws();
        child.appendRenderCommands(new ArrayList<>());
        List<Draw> longFlameDraws = graphics.copyDraws();
        assertCompositeDraws(longFlameDraws, stablePatternBase, true);

        objectManager.update(GameServices.camera().getX(), player, List.of(), 2, false);
        assertRomFollowerPosition(parent, child);
        graphics.clearDraws();
        child.appendRenderCommands(new ArrayList<>());
        List<Draw> shortFlameDraws = graphics.copyDraws();
        assertCompositeDraws(shortFlameDraws, stablePatternBase, false);

        assertEquals(22, longFlameDraws.size(), "frame 1 is 14 body tiles plus 8 flame tiles");
        assertEquals(18, shortFlameDraws.size(), "frame 2 is 14 body tiles plus 4 flame tiles");
        assertFalse(longFlameDraws.equals(shortFlameDraws),
                "consecutive ObjB2 animation frames must alternate the flame mapping");
        assertSame(renderer, renderManager.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));
        assertEquals(stablePatternBase, renderer.getPatternBase(),
                "rendering the live child must not relocate its runtime PLC allocation");
    }

    private TornadoObjectInstance findLiveTornadoSubtype(int subtype) {
        return objectManager.getActiveObjects().stream()
                .filter(TornadoObjectInstance.class::isInstance)
                .map(TornadoObjectInstance.class::cast)
                .filter(object -> object.getSpawn().subtype() == subtype)
                .findFirst()
                .orElse(null);
    }

    private static void assertRomFollowerPosition(
            TornadoObjectInstance parent, TornadoObjectInstance child) throws Exception {
        int parentXPos = (int) getField(parent, "currentX");
        int parentYPos = (int) getField(parent, "currentY");
        int childXPos = (int) getField(child, "currentX");
        int childYPos = (int) getField(child, "currentY");
        assertEquals(parentXPos - 0x0C, childXPos,
                "ObjB2 $5C x_pos must follow the parent x_pos-$0C");
        assertEquals(parentYPos + 0x28, childYPos,
                "ObjB2 $5C y_pos must follow the parent y_pos+$28");
        assertEquals(childXPos, child.getX(),
                "this ObjB2 implementation exposes native x_pos directly");
        assertEquals(childYPos, child.getY(),
                "this ObjB2 implementation exposes native y_pos directly");
    }

    private void assertCompositeDraws(List<Draw> draws, int base, boolean longFlame) {
        int flameFirst = longFlame ? base + 0x0E : base + 0x16;
        int flameLast = longFlame ? base + 0x15 : base + 0x19;
        int expectedFlameTiles = longFlame ? 8 : 4;
        int expectedLeftX = longFlame ? ROM_PARENT_X - 0x0C - 0x3C
                : ROM_PARENT_X - 0x0C - 0x2C;

        List<Draw> body = draws.stream()
                .filter(draw -> draw.patternId() >= base && draw.patternId() < base + 0x0E)
                .toList();
        List<Draw> flame = draws.stream()
                .filter(draw -> draw.patternId() >= flameFirst && draw.patternId() <= flameLast)
                .toList();
        assertEquals(14, body.size(), "both gray rocket-body mapping pieces must render");
        assertEquals(expectedFlameTiles, flame.size(),
                "the selected left-facing flame mapping piece must render every tile");
        assertEquals(expectedLeftX, flame.stream().mapToInt(Draw::x).min().orElseThrow(),
                "the flame must extend left from the rocket body");
        assertEquals(ROM_PARENT_Y + 0x28 - 8,
                flame.stream().mapToInt(Draw::y).min().orElseThrow());
        assertTrue(body.stream().allMatch(draw -> draw.paletteIndex() == 1),
                "the two gray rocket pieces use mapping palette line 1");
        assertTrue(flame.stream().allMatch(draw -> draw.paletteIndex() == 2),
                "the flame piece uses mapping palette line 2");

        SpriteFrame<? extends SpriteFramePiece> mapping =
                provider.getSheet(Sonic2ObjectArtKeys.TORNADO_THRUSTER)
                        .getFrame(longFlame ? 1 : 2);
        assertEquals(3, mapping.pieces().size(),
                "each animated composite frame contains two body pieces and one flame piece");
        assertEquals(longFlame ? 4 : 2, mapping.pieces().get(2).widthTiles());
        assertEquals(longFlame ? -0x3C : -0x2C, mapping.pieces().get(2).xOffset());
    }

    private static void setRomPosition(TornadoObjectInstance object, int x, int y) throws Exception {
        setField(object, "currentX", x);
        setField(object, "currentY", y);
        setField(object, "xPosFixed8", x << 8);
        setField(object, "yPosFixed8", y << 8);
    }

    private static EngineContext withGraphics(EngineContext source, GraphicsManager graphics) {
        return new EngineContext(
                source.configuration(), graphics, source.audio(), source.roms(), source.profiler(),
                source.debugOverlay(), source.playbackDebug(), source.romDetection(),
                source.crossGameFeatures());
    }

    private static void setCurrentLevel(
            LevelManager manager, GameplayModeContext gameplay, Level level) throws Exception {
        setField(manager, "level", level);
        gameplay.getWorldSession().setCurrentLevel(level);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class PlcRequestEvents extends Sonic2ZoneEvents {
        @Override
        public void update(int act, int frameCounter) {
        }

        private void request(int plcId) {
            requestSonic2Plc(plcId);
        }
    }

    private static final class LiveObjectServices extends TestObjectServices {
        private final ObjectRenderManager renderManager;
        private ObjectManager objectManager;

        private LiveObjectServices(ObjectRenderManager renderManager) {
            this.renderManager = renderManager;
        }

        private void setObjectManager(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }
    }

    private static final class RecordingGraphicsManager extends GraphicsManager {
        private final List<Draw> draws = new ArrayList<>();

        @Override
        public void cachePatternTexture(Pattern pattern, int patternId) {
            // Preserve production allocation/caching calls without requiring OpenGL.
        }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            draws.add(new Draw(patternId, desc.get(), desc.getPaletteIndex(), x, y));
        }

        private void clearDraws() {
            draws.clear();
        }

        private List<Draw> copyDraws() {
            return List.copyOf(draws);
        }
    }

    private record Draw(int patternId, int descriptor, int paletteIndex, int x, int y) {
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite(short x, short y) {
            super("main", x, y);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 28;
            runHeight = 38;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void draw() {
        }
    }
}
