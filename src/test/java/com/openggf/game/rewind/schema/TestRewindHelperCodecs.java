package com.openggf.game.rewind.schema;

import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.PlatformBobHelper;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.Palette;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.util.AnimationTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestRewindHelperCodecs {
    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void capturesFinalSubpixelMotionStateInPlace() {
        HelperFixture fixture = new HelperFixture();
        fixture.motion.x = 10;
        fixture.motion.y = 20;
        fixture.motion.xSub = 30;
        fixture.motion.ySub = 40;
        fixture.motion.xVel = 50;
        fixture.motion.yVel = 60;

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.motion.x = -1;
        fixture.motion.y = -2;
        fixture.motion.xSub = -3;
        fixture.motion.ySub = -4;
        fixture.motion.xVel = -5;
        fixture.motion.yVel = -6;
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(10, fixture.motion.x);
        assertEquals(20, fixture.motion.y);
        assertEquals(30, fixture.motion.xSub);
        assertEquals(40, fixture.motion.ySub);
        assertEquals(50, fixture.motion.xVel);
        assertEquals(60, fixture.motion.yVel);
    }

    @Test
    void capturesFinalPlatformBobHelperInPlace() {
        HelperFixture fixture = new HelperFixture();
        fixture.bob.update(true);
        fixture.bob.update(true);
        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);

        for (int i = 0; i < 20; i++) {
            fixture.bob.update(true);
        }
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(8, fixture.bob.getAngle());
    }

    @Test
    void capturesFinalAnimationTimerInPlace() {
        HelperFixture fixture = new HelperFixture();
        fixture.timer.tick();
        fixture.timer.tick();
        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);

        fixture.timer.tick();
        fixture.timer.tick();
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(0, fixture.timer.getFrame());
        fixture.timer.tick();
        assertEquals(1, fixture.timer.getFrame());
    }

    @Test
    void capturesFinalObjectAnimationStateInPlace() {
        HelperFixture fixture = new HelperFixture();
        fixture.animation.update();
        fixture.animation.update();
        ObjectAnimationState expected = fixture.animation.copyForRewind();
        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);

        fixture.animation.setAnimId(1);
        fixture.animation.update();
        fixture.animation.update();
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(expected, fixture.animation);
    }

    @Test
    void capturesFinalPaletteColorArrayInPlace() {
        ArrayFixture fixture = new ArrayFixture();

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.colors[0].r = 9;
        fixture.colors[0].g = 9;
        fixture.colors[0].b = 9;
        fixture.colors[1] = null;
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(1, fixture.colors[0].r);
        assertEquals(2, fixture.colors[0].g);
        assertEquals(3, fixture.colors[0].b);
        assertEquals(4, fixture.colors[1].r);
        assertEquals(5, fixture.colors[1].g);
        assertEquals(6, fixture.colors[1].b);
    }

    @Test
    void capturesFinalPlainStateHolderArrayInPlace() {
        ArrayFixture fixture = new ArrayFixture();

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.segments[0].x = -1;
        fixture.segments[1] = null;
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(10, fixture.segments[0].x);
        assertEquals(20, fixture.segments[1].x);
    }

    private static SpriteAnimationSet animationSet() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        set.addScript(0, new SpriteAnimationScript(2, List.of(7, 8), SpriteAnimationEndAction.LOOP, 0));
        set.addScript(1, new SpriteAnimationScript(1, List.of(3, 4, 5), SpriteAnimationEndAction.LOOP, 0));
        return set;
    }

    private static final class HelperFixture {
        final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
        final PlatformBobHelper bob = new PlatformBobHelper();
        final AnimationTimer timer = new AnimationTimer(3, 2);
        final ObjectAnimationState animation = new ObjectAnimationState(animationSet(), 0, 7);
    }

    private static final class ArrayFixture {
        final Palette.Color[] colors = {
                new Palette.Color((byte) 1, (byte) 2, (byte) 3),
                new Palette.Color((byte) 4, (byte) 5, (byte) 6)
        };
        final Segment[] segments = {
                new Segment(10),
                new Segment(20)
        };
    }

    private static final class Segment {
        int x;

        private Segment() {
        }

        private Segment(int x) {
            this.x = x;
        }
    }
}
