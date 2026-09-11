package com.openggf.graphics;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.EngineServices;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.level.LevelManager;
import com.openggf.physics.Direction;
import com.openggf.physics.Sensor;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSpriteManagerRender {

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameModuleRegistry.reset();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    public void testBucketOrderingAndNonPlayablePlacement() {
        List<String> drawOrder = new ArrayList<>();
        SpriteManager spriteManager = GameServices.sprites();
        SpriteManager renderManager = GameServices.sprites();

        TestPlayableSprite highBucket = new TestPlayableSprite("high", drawOrder);
        highBucket.setPriorityBucket(3);
        highBucket.setHighPriority(false);

        TestPlayableSprite lowBucket = new TestPlayableSprite("low", drawOrder);
        lowBucket.setPriorityBucket(1);
        lowBucket.setHighPriority(false);

        TestSprite npc = new TestSprite("npc", drawOrder);

        spriteManager.addSprite(highBucket);
        spriteManager.addSprite(lowBucket);
        spriteManager.addSprite(npc);

        try {
            renderManager.drawLowPriority();
            assertEquals(List.of("high", "low"), drawOrder);

            drawOrder.clear();
            highBucket.setHighPriority(true);
            lowBucket.setHighPriority(true);
            renderManager.drawLowPriority(); // bucketSprites is called here
            renderManager.drawHighPriority();
            assertEquals(List.of("high", "low", "npc"), drawOrder);
        } finally {
            spriteManager.removeSprite(highBucket.getCode());
            spriteManager.removeSprite(lowBucket.getCode());
            spriteManager.removeSprite(npc.getCode());
        }
    }

    @Test
    public void testSidekickDrawnBeforeMainPlayer() {
        List<String> drawOrder = new ArrayList<>();
        SpriteManager spriteManager = GameServices.sprites();

        // Add main first, then sidekick â€” same bucket and priority
        TestPlayableSprite main = new TestPlayableSprite("main", drawOrder);
        main.setPriorityBucket(2);
        main.setHighPriority(false);

        TestPlayableSprite sidekick = new TestPlayableSprite("sidekick", drawOrder);
        sidekick.setCpuControlled(true);
        sidekick.setPriorityBucket(2);
        sidekick.setHighPriority(false);

        spriteManager.addSprite(main);
        spriteManager.addSprite(sidekick);

        try {
            spriteManager.drawLowPriority();
            // VDP draws lower-indexed sprites on top; painter's algorithm means
            // the sprite drawn LAST appears in front. Sidekick must draw first.
            assertEquals(List.of("sidekick", "main"), drawOrder);
        } finally {
            spriteManager.removeSprite(main.getCode());
            spriteManager.removeSprite(sidekick.getCode());
        }
    }

    @Test
    public void testMultipleSidekicksKeepChainRenderOrderBeforeMainPlayer() {
        List<String> drawOrder = new ArrayList<>();
        SpriteManager spriteManager = GameServices.sprites();

        TestPlayableSprite main = new TestPlayableSprite("main", drawOrder);
        main.setPriorityBucket(2);
        main.setHighPriority(false);

        TestPlayableSprite firstSidekick = new TestPlayableSprite("sidekick0", drawOrder);
        firstSidekick.setCpuControlled(true);
        firstSidekick.setPriorityBucket(2);
        firstSidekick.setHighPriority(false);

        TestPlayableSprite secondSidekick = new TestPlayableSprite("sidekick1", drawOrder);
        secondSidekick.setCpuControlled(true);
        secondSidekick.setPriorityBucket(2);
        secondSidekick.setHighPriority(false);

        spriteManager.addSprite(main);
        spriteManager.addSprite(firstSidekick);
        spriteManager.addSprite(secondSidekick);

        try {
            spriteManager.drawLowPriority();
            assertEquals(List.of("sidekick0", "sidekick1", "main"), drawOrder);
        } finally {
            spriteManager.removeSprite(main.getCode());
            spriteManager.removeSprite(firstSidekick.getCode());
            spriteManager.removeSprite(secondSidekick.getCode());
        }
    }

    @Test
    public void testSonic2SidekickSuppressionZones() throws Exception {
        List<String> drawOrder = new ArrayList<>();
        SpriteManager spriteManager = GameServices.sprites();
        LevelManager levelManager = GameServices.level();

        int originalZone = levelManager.getCurrentZone();

        TestPlayableSprite main = new TestPlayableSprite("main", drawOrder);
        main.setPriorityBucket(2);
        main.setHighPriority(false);

        TestPlayableSprite sidekick = new TestPlayableSprite("sidekick", drawOrder);
        sidekick.setCpuControlled(true);
        sidekick.setPriorityBucket(2);
        sidekick.setHighPriority(false);

        spriteManager.addSprite(main);
        spriteManager.addSprite(sidekick);

        try {
            assertSuppressedZone(levelManager, spriteManager, drawOrder, Sonic2ZoneConstants.ZONE_SCZ);
            assertSuppressedZone(levelManager, spriteManager, drawOrder, Sonic2ZoneConstants.ZONE_WFZ);
            assertSuppressedZone(levelManager, spriteManager, drawOrder, Sonic2ZoneConstants.ZONE_DEZ);
        } finally {
            drawOrder.clear();
            setCurrentZone(levelManager, originalZone);
            spriteManager.removeSprite(main.getCode());
            spriteManager.removeSprite(sidekick.getCode());
        }
    }

    private static void assertSuppressedZone(LevelManager levelManager, SpriteManager spriteManager,
                                             List<String> drawOrder, int zone) throws Exception {
        drawOrder.clear();
        setCurrentZone(levelManager, zone);
        assertTrue(spriteManager.getSidekicks().isEmpty(), "Zone should suppress CPU sidekick gameplay instance");
        spriteManager.draw();
        assertEquals(List.of("main"), drawOrder, "Suppressed zone should render only the main character sprite");
    }

    private static void setCurrentZone(LevelManager levelManager, int zone) throws Exception {
        Field currentZone = LevelManager.class.getDeclaredField("currentZone");
        currentZone.setAccessible(true);
        currentZone.setInt(levelManager, zone);
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private final List<String> drawOrder;

        private TestPlayableSprite(String code, List<String> drawOrder) {
            super(code, (short) 0, (short) 0);
            this.drawOrder = drawOrder;
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
            rollHeight = 0;
            runHeight = 0;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void draw() {
            drawOrder.add(getCode());
        }
    }

    private static final class TestSprite implements Sprite {
        private final String code;
        private final List<String> drawOrder;

        private TestSprite(String code, List<String> drawOrder) {
            this.code = code;
            this.drawOrder = drawOrder;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public void setCode(String code) {
        }

        @Override
        public void draw() {
            drawOrder.add(code);
        }

        @Override
        public short getCentreX() {
            return 0;
        }

        @Override
        public short getCentreY() {
            return 0;
        }

        @Override
        public void setCentreX(short x) {
        }

        @Override
        public void setCentreY(short y) {
        }

        @Override
        public short getX() {
            return 0;
        }

        @Override
        public void setX(short x) {
        }

        @Override
        public short getY() {
            return 0;
        }

        @Override
        public void setY(short y) {
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public void setHeight(int height) {
        }

        @Override
        public int getWidth() {
            return 0;
        }

        @Override
        public void setWidth(int width) {
        }

        @Override
        public short getBottomY() {
            return 0;
        }

        @Override
        public short getTopY() {
            return 0;
        }

        @Override
        public short getLeftX() {
            return 0;
        }

        @Override
        public short getRightX() {
            return 0;
        }

        @Override
        public void move(short xSpeed, short ySpeed) {
        }

        @Override
        public Direction getDirection() {
            return Direction.RIGHT;
        }

        @Override
        public void setDirection(Direction direction) {
        }

        @Override
        public void setLayer(byte layer) {
        }

        @Override
        public byte getLayer() {
            return 0;
        }
    }
}
