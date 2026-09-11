package com.openggf.sprites.managers;

import com.openggf.physics.Direction;
import com.openggf.sprites.Sprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSpriteManagerRemoval {

    @Test
    public void removeSpriteByCodeRemovesMatchingSpriteInstance() {
        SpriteManager spriteManager = new SpriteManager();
        Sprite sprite = new TestSprite("slot-player");
        spriteManager.addSprite(sprite);

        assertTrue(spriteManager.removeSprite("slot-player"));
        assertNull(spriteManager.getSprite("slot-player"));
        assertFalse(spriteManager.getAllSprites().contains(sprite));
    }

    private static final class TestSprite implements Sprite {
        private String code;

        private TestSprite(String code) {
            this.code = code;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public void setCode(String code) {
            this.code = code;
        }

        @Override
        public void draw() {
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


