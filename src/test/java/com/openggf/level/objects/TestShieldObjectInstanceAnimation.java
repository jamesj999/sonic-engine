package com.openggf.level.objects;

import com.openggf.game.ObjectArtProvider;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.snapshot.GenericObjectSnapshot;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.animation.SpriteAnimationSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestShieldObjectInstanceAnimation {

    @Test
    void sonic1ShieldUsesRomFrameSequenceAndItsOwnAnimateSpriteCountdown() {
        ShieldObjectInstance shield = newShield(4);

        int[] expectedFrames = {1, 1, 0, 0, 2, 2, 0, 0, 3, 3, 0, 0, 1};
        int[] unrelatedVIntValues = {0, 7, 20, 21, 42, 99, 100, 5, 6, 31, 88, 89, 90};
        for (int i = 0; i < expectedFrames.length; i++) {
            shield.update(unrelatedVIntValues[i], null);
            assertEquals(expectedFrames[i], shield.getCurrentFrame(),
                    "S1 Ani_Shield should hold each ROM frame for two object updates at step " + i);
        }
    }

    @Test
    void sonic2ShieldKeepsItsEveryUpdateRomSequence() {
        ShieldObjectInstance shield = newShield(6);

        int[] expectedFrames = {5, 0, 5, 1, 5, 2, 5, 3, 5, 4, 5};
        int[] unrelatedVIntValues = {8, 41, 2, 3, 70, 17, 18, 99, 4, 55, 56};
        for (int i = 0; i < expectedFrames.length; i++) {
            shield.update(unrelatedVIntValues[i], null);
            assertEquals(expectedFrames[i], shield.getCurrentFrame(),
                    "S2 Ani_obj38 delay 0 should advance on every object update at step " + i);
        }
    }

    @Test
    void rewindRestoresShieldAnimationPhaseAndCountdown() {
        ShieldObjectInstance source = newShield(4);
        source.update(12, null);
        source.update(99, null);
        source.update(4, null);
        GenericObjectSnapshot snapshot = GenericFieldCapturer.captureObjectSubclassScalars(source);

        ShieldObjectInstance restored = newShield(4);
        for (int i = 0; i < 6; i++) {
            restored.update(i, null);
        }
        GenericFieldCapturer.restore(restored, snapshot);

        assertEquals(source.getCurrentFrame(), restored.getCurrentFrame());
        source.update(1, null);
        restored.update(200, null);
        assertEquals(source.getCurrentFrame(), restored.getCurrentFrame(),
                "Rewind must restore the local countdown as well as the visible frame");
    }

    private static ShieldObjectInstance newShield(int frameCount) {
        ObjectSpriteSheet shieldSheet = new ObjectSpriteSheet(
                new Pattern[0],
                java.util.stream.IntStream.range(0, frameCount)
                        .mapToObj(ignored -> emptyFrame())
                        .toList(),
                0,
                1);
        ObjectRenderManager renderManager = new ObjectRenderManager(new ShieldArtProvider(shieldSheet));
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };
        return ObjectConstructionContext.construct(services, () -> new ShieldObjectInstance(null));
    }

    private static SpriteMappingFrame emptyFrame() {
        return new SpriteMappingFrame(List.of());
    }

    private record ShieldArtProvider(ObjectSpriteSheet shieldSheet) implements ObjectArtProvider {
        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            return null;
        }

        @Override
        public ObjectSpriteSheet getSheet(String key) {
            return ObjectArtKeys.SHIELD.equals(key) ? shieldSheet : null;
        }

        @Override
        public SpriteAnimationSet getAnimations(String key) {
            return null;
        }

        @Override
        public int getZoneData(String key, int zoneIndex) {
            return -1;
        }

        @Override
        public Pattern[] getHudDigitPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesNumbers() {
            return new Pattern[0];
        }

        @Override
        public List<String> getRendererKeys() {
            return List.of();
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }
}
