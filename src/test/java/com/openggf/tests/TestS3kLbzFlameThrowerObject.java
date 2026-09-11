package com.openggf.tests;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.LbzFlameThrowerFlameInstance;
import com.openggf.game.sonic3k.objects.LbzFlameThrowerObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kLbzFlameThrowerObject {

    @Test
    void registryCreatesLbzFlameThrowerAndProfileMarksS3klSlotImplemented() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.LBZ_FLAME_THROWER, 0, 0, false, 0));

        assertInstanceOf(LbzFlameThrowerObjectInstance.class, instance);
        assertTrue(new Sonic3kObjectProfile().getImplementedIds().contains(Sonic3kObjectIds.LBZ_FLAME_THROWER));
    }

    @Test
    void parentUsesRomSolidBoundsAndLevelArt() {
        LbzFlameThrowerObjectInstance flameThrower = createParent(new RecordingServices(), 0x00, false);

        SolidObjectParams solid = flameThrower.getSolidParams();

        assertEquals(0x1B, solid.halfWidth());
        assertEquals(0x10, solid.airHalfHeight());
        assertEquals(0x11, solid.groundHalfHeight());
        assertTrue(flameThrower.usesInclusiveRightEdge(),
                "SolidObjectFull's unsigned bhi gate accepts relX == d1*2");
        assertEquals(3, flameThrower.getPriorityBucket());
        assertEquals(Sonic3kObjectArtKeys.LBZ_FLAME_THROWER, flameThrower.getArtKeyForTesting());
    }

    @Test
    void parentSpawnsFlameEveryOneHundredTwentyEightFramesOffsetBySubtype() {
        RecordingServices services = new RecordingServices();
        LbzFlameThrowerObjectInstance flameThrower = createParent(services, 0x05, false);

        flameThrower.update(0x7A, playerAt(0x0200, 0x0100));

        assertEquals(0, services.children.size());

        flameThrower.update(0x7B, playerAt(0x0200, 0x0100));

        assertEquals(1, services.children.size());
        LbzFlameThrowerFlameInstance flame = assertInstanceOf(LbzFlameThrowerFlameInstance.class, services.children.get(0));
        assertEquals(0x0240, flame.getCentreX());
        assertEquals(0x0100, flame.getCentreY());
        assertEquals(0x9D, flame.getCollisionFlags());
        assertEquals(List.of(Sonic3kSfx.FIRE_ATTACK.id), services.playedSfx);
    }

    @Test
    void parentUsesRomVIntPhaseInsteadOfRawObjectUpdateCounter() {
        RecordingServices services = new RecordingServices(6);
        LbzFlameThrowerObjectInstance flameThrower = createParent(services, 0x20, false);

        flameThrower.update(0x5A, playerAt(0x0200, 0x0100));

        assertEquals(1, services.children.size(),
                "Obj16 reads the phased low byte of V_int_run_count");
    }

    @Test
    void xFlippedParentSpawnsFlameToTheLeft() {
        RecordingServices services = new RecordingServices();
        LbzFlameThrowerObjectInstance flameThrower = createParent(services, 0x00, true);

        flameThrower.update(0x80, playerAt(0x0200, 0x0100));

        LbzFlameThrowerFlameInstance flame = assertInstanceOf(LbzFlameThrowerFlameInstance.class, services.children.get(0));
        assertEquals(0x01C0, flame.getCentreX());
        assertEquals(0x0100, flame.getCentreY());
    }

    @Test
    void flameAnimatesRomSequenceAndDeletesAfterTerminator() {
        LbzFlameThrowerFlameInstance flame = new LbzFlameThrowerFlameInstance(
                new ObjectSpawn(0x0240, 0x0100, Sonic3kObjectIds.LBZ_FLAME_THROWER, 0, 0, false, 0));

        int[] expectedFrames = {1, 2, 3, 4, 5, 3, 4, 6, 7, 8};
        for (int frame : expectedFrames) {
            assertEquals(frame, flame.getMappingFrameForTesting());
            flame.update(0, playerAt(0x0200, 0x0100));
            flame.update(1, playerAt(0x0200, 0x0100));
            flame.update(2, playerAt(0x0200, 0x0100));
            flame.update(3, playerAt(0x0200, 0x0100));
        }

        assertTrue(flame.isDestroyed());
    }

    @Test
    void flameUsesFireShieldReactionTouchProfileAndDrawsCurrentFrame() {
        LbzFlameThrowerFlameInstance flame = new LbzFlameThrowerFlameInstance(
                new ObjectSpawn(0x0240, 0x0100, Sonic3kObjectIds.LBZ_FLAME_THROWER, 0, 1, false, 0));
        RecordingServices services = new RecordingServices();
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        services.renderers.add(renderer);
        flame.setServices(services);

        TouchResponseProfile profile = flame.getTouchResponseProfile();

        assertEquals(0x9D, flame.getCollisionFlags());
        assertEquals(1 << 4, flame.getShieldReactionFlags());
        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertEquals(1 << 4, profile.shieldReactionFlags());

        flame.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(1, 0x0240, 0x0100, true, false);
    }

    private static LbzFlameThrowerObjectInstance createParent(RecordingServices services, int subtype, boolean xFlip) {
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0400, 0x0300, 0);
        LbzFlameThrowerObjectInstance flameThrower = new LbzFlameThrowerObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.LBZ_FLAME_THROWER,
                        subtype, xFlip ? 1 : 0, false, 0));
        flameThrower.setServices(services);
        return flameThrower;
    }

    private static TestablePlayableSprite playerAt(int x, int y) {
        return new TestablePlayableSprite("sonic", (short) x, (short) y);
    }

    private static final class RecordingServices extends StubObjectServices {
        private final ObjectManager objectManager;
        private final ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        private final List<AbstractObjectInstance> children = new ArrayList<>();
        private final List<Integer> playedSfx = new ArrayList<>();
        private final List<PatternSpriteRenderer> renderers = new ArrayList<>();
        private final int vIntPhase;

        private RecordingServices() {
            this(0);
        }

        private RecordingServices(int vIntPhase) {
            this.vIntPhase = vIntPhase;
            objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                AbstractObjectInstance child = invocation.getArgument(0);
                child.setServices(this);
                children.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any(AbstractObjectInstance.class));
            doAnswer(invocation -> {
                assertEquals(Sonic3kObjectArtKeys.LBZ_FLAME_THROWER, invocation.getArgument(0));
                return renderers.isEmpty() ? null : renderers.get(0);
            }).when(renderManager).getRenderer(any(String.class));
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }

        @Override
        public int resolveVIntRunCount(int vIntRunCountAtObservation) {
            return vIntRunCountAtObservation + vIntPhase;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }
}
