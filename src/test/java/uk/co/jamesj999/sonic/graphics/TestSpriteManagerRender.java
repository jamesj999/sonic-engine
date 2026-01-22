package uk.co.jamesj999.sonic.graphics;

import org.junit.Test;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TestSpriteManagerRender {
    @Test
    public void testBucketOrderingAndNonPlayablePlacement() {
        List<String> drawOrder = new ArrayList<>();
        SpriteManager spriteManager = SpriteManager.getInstance();
        SpriteManager renderManager = SpriteManager.getInstance();

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
        public uk.co.jamesj999.sonic.physics.Direction getDirection() {
            return uk.co.jamesj999.sonic.physics.Direction.RIGHT;
        }

        @Override
        public void setDirection(uk.co.jamesj999.sonic.physics.Direction direction) {
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
