package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestOOZLauncherObjectInstance {

    @BeforeEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void queriedEngineSidekicksEachUseIndependentLauncherParticipation() {
        OOZLauncherObjectInstance launcher = newLauncher();
        TestablePlayableSprite main = player("sonic");
        TestablePlayableSprite nativeP2 = player("tails");
        TestablePlayableSprite extraSidekick = player("knuckles");
        RecordingObjectManager objectManager = new RecordingObjectManager();
        main.setAnimationId(Sonic2AnimationIds.WAIT);
        nativeP2.setAnimationId(Sonic2AnimationIds.ROLL);
        nativeP2.setYSpeed((short) -0x0120);
        extraSidekick.setAnimationId(Sonic2AnimationIds.ROLL);
        launcher.setServices(new QueryOnlyPlayerServices(main, List.of(nativeP2, extraSidekick), objectManager));

        launcher.update(0, main);
        launcher.onSolidContact(nativeP2, new SolidContact(true, false, false, true, false), 0);

        assertTrue(nativeP2.getAir(),
                "Obj3D has only native P1/P2 state slots, so queried native P2 must use the Tails slot");
        assertTrue(nativeP2.getRolling());
        assertFalse(launcher.isSolidFor(main), "Rolling native P2 should break the launcher block");

        spawnedInvisibleLauncher(objectManager);

        assertTrue(extraSidekick.isObjectControlled(),
                "OOZ launcher has per-player launch state and should explicitly include engine sidekicks");
        assertTrue(extraSidekick.getAir());
    }

    @Test
    void solidLandingKeepsRollingRadiusForBreakFrame() {
        OOZLauncherObjectInstance launcher = newLauncher();
        TestablePlayableSprite player = player("sonic");
        player.setRolling(true);

        assertTrue(launcher.landingPreservesRolling(player),
                "Obj3D loc_24EB8 sets roll radii after SolidObject_Landed and never runs Sonic_ResetOnFloor");
        assertEquals(player.getStandYRadius() - player.getYRadius(),
                launcher.getTopLandingSnapAdjustment(player, player.getStandYRadius()),
                "Obj3D's break-frame landing uses the live roll radius, not the standing-radius overlap surface");
    }

    @Test
    void horizontalLauncherCapturesPlayerInWindowOnFirstScan() {
        OOZLauncherObjectInstance launcher = newLauncherAt(0x1140, 0x0270, 1);
        TestablePlayableSprite player = player("sonic");
        RecordingObjectManager objectManager = new RecordingObjectManager();
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setRolling(true);
        player.setYSpeed((short) 0x0418);
        launcher.setServices(new QueryOnlyPlayerServices(player, List.of(), objectManager));
        player.setCentreX((short) 0x114C);
        player.setCentreY((short) 0x0263);

        launcher.update(0, player);
        launcher.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);

        assertTrue(player.isObjectControlled());
        assertTrue(player.isOnObject());
        assertEquals(0x1140, player.getCentreX() & 0xFFFF);
        assertEquals((short) -0x0800, player.getYSpeed());
    }

    @Test
    void breakSpawnsInvisibleLauncherAfterCurrentAndParentStopsOwningLaunchState() {
        OOZLauncherObjectInstance launcher = newLauncherAt(0x1140, 0x0270, 1);
        TestablePlayableSprite player = player("sonic");
        RecordingObjectManager objectManager = new RecordingObjectManager();
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setRolling(true);
        player.setYSpeed((short) 0x0418);
        launcher.setServices(new QueryOnlyPlayerServices(player, List.of(), objectManager));
        player.setCentreX((short) 0x114C);
        player.setCentreY((short) 0x0263);

        launcher.update(0, player);
        launcher.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);

        assertFalse(launcher.isPersistent(),
                "Obj3D loc_24F04 keeps the broken parent as fragments, not the persistent routine-6 launcher");
        assertEquals(1, objectManager.afterCurrent.size(),
                "Obj3D loc_24F04 must allocate one invisible launcher via AllocateObjectAfterCurrent");
        OOZLauncherObjectInstance child = assertInstanceOf(OOZLauncherObjectInstance.class,
                objectManager.afterCurrent.getFirst());
        assertTrue(child.isPersistent(),
                "The allocated routine-6 child owns the post-break proximity/MoveCharacter state");
    }

    @Test
    void brokenParentImmediatelyRunsFirstFragmentInOriginalSlot() {
        OOZLauncherObjectInstance launcher = newLauncherAt(0x1140, 0x0270, 1);
        TestablePlayableSprite player = player("sonic");
        RecordingObjectManager objectManager = new RecordingObjectManager();
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setRolling(true);
        player.setYSpeed((short) 0x0418);
        launcher.setServices(new QueryOnlyPlayerServices(player, List.of(), objectManager));
        player.setCentreX((short) 0x114C);
        player.setCentreY((short) 0x0263);

        launcher.update(0, player);
        launcher.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);

        assertEquals(0x113C, launcher.getX(),
                "Obj3D loc_24F28/BreakObjectToPieces uses the current object as fragment 0");
        assertEquals(0x026C, launcher.getY(),
                "Obj3D_Fragment falls through on the break frame and moves before returning");
        launcher.update(1, player);
        assertEquals(0x1138, launcher.getX(),
                "The broken placement object must keep running routine 4 instead of occupying a stale slot");
    }

    @Test
    void horizontalLauncherDeletesIfNeitherPlayerEntersWindow() {
        OOZLauncherObjectInstance launcher = newLauncherAt(0x1140, 0x0270, 1);
        TestablePlayableSprite player = player("sonic");
        RecordingObjectManager objectManager = new RecordingObjectManager();
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setRolling(true);
        player.setYSpeed((short) 0x0418);
        launcher.setServices(new QueryOnlyPlayerServices(player, List.of(), objectManager));

        launcher.update(0, player);
        launcher.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        OOZLauncherObjectInstance invisibleLauncher = spawnedInvisibleLauncher(objectManager);
        invisibleLauncher.update(1, player);

        assertFalse(launcher.isPersistent());
        assertFalse(invisibleLauncher.isPersistent());
        assertFalse(player.isObjectControlled());
    }

    private static OOZLauncherObjectInstance newLauncher() {
        return newLauncherAt(0x0100, 0x0100, 0);
    }

    private static OOZLauncherObjectInstance newLauncherAt(int x, int y, int subtype) {
        return new OOZLauncherObjectInstance(
                new ObjectSpawn(x, y, Sonic2ObjectIds.OOZ_LAUNCHER, subtype, 0, false, 0),
                "OOZLauncher");
    }

    private static TestablePlayableSprite player(String code) {
        TestablePlayableSprite player = new TestablePlayableSprite(code, (short) 0x0100, (short) 0x0100);
        player.setCentreX((short) 0x0100);
        player.setCentreY((short) 0x0100);
        player.setAir(false);
        return player;
    }

    private static OOZLauncherObjectInstance spawnedInvisibleLauncher(RecordingObjectManager objectManager) {
        assertEquals(1, objectManager.afterCurrent.size());
        return assertInstanceOf(OOZLauncherObjectInstance.class, objectManager.afterCurrent.getFirst());
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;
        private final ObjectManager objectManager;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this(main, queriedSidekicks, null);
        }

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks,
                ObjectManager objectManager) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
            this.objectManager = objectManager;
            if (objectManager instanceof RecordingObjectManager recording) {
                recording.services = this;
            }
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }

    private static final class RecordingObjectManager extends ObjectManager {
        private final List<ObjectInstance> afterCurrent = new ArrayList<>();
        private TestObjectServices services;

        private RecordingObjectManager() {
            super(List.of(), null, 0, null, null, null, null, new TestObjectServices());
        }

        @Override
        public void addDynamicObjectAfterCurrent(ObjectInstance object) {
            if (object instanceof OOZLauncherObjectInstance launcher) {
                launcher.setServices(services);
            }
            afterCurrent.add(object);
        }
    }
}
