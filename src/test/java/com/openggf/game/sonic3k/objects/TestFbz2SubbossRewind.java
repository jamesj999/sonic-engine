package com.openggf.game.sonic3k.objects;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.level.objects.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.audio.AudioManager;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.Sonic3kPlcLoader;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.resources.KosinskiModuleQueue;
import com.openggf.tests.RomTestUtils;

import java.io.File;
import java.util.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.events.FbzObjectEventBridge;
import com.openggf.game.sonic3k.events.Sonic3kFBZEvents;

import static org.junit.jupiter.api.Assertions.*;

class TestFbz2SubbossRewind {
    @BeforeEach void headless(){GraphicsManager.getInstance().initHeadless();}
    @AfterEach void reset(){GraphicsManager.getInstance().resetState();}

    @TestFactory Collection<DynamicTest> initialAllocationFailuresPreserveTheExactFlatPrefix() {
        List<DynamicTest> tests=new ArrayList<>();
        for(int capacity=0;capacity<=8;capacity++){int c=capacity;tests.add(DynamicTest.dynamicTest("capacity "+c,()->assertInitialPrefix(c)));}
        return tests;
    }

    @Test void missingRomPaletteLoadIsLoggedIntoExplicitBossState() {
        Harness h=harness();
        Fbz2SubbossInstance root=h.manager.createDynamicObject(()->new Fbz2SubbossInstance(
                new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));
        h.step(0);
        assertEquals("ROM owner unavailable",root.paletteLoadFailure());
    }

    private void assertInitialPrefix(int capacity){
        Harness h=harness();
        Fbz2SubbossInstance root=h.manager.createDynamicObject(()->new Fbz2SubbossInstance(new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));
        h.manager.reserveAllButNFreeSlots(capacity);
        h.step(0);
        List<ObjectInstance> live=h.manager.getActiveObjects().stream().filter(o->!o.isDestroyed()).toList();
        long machine=live.stream().filter(Fbz2SubbossMachineChild.class::isInstance).count();
        long character=live.stream().filter(Fbz2SubbossCharacterChild.class::isInstance).count();
        long mask=live.stream().filter(Fbz2SubbossSpriteMaskChild.class::isInstance).count();
        long corners=live.stream().filter(Fbz2SubbossCornerChild.class::isInstance).count();
        assertEquals(capacity>=2?1:0,machine);
        assertEquals(capacity>=3?1:0,character);
        assertEquals(capacity>=4?1:0,mask);
        assertEquals(Math.max(0,capacity-4),corners);
        assertSame(root,live.stream().filter(Fbz2SubbossInstance.class::isInstance).findFirst().orElseThrow());
        int[] slots=live.stream().mapToInt(o->((AbstractObjectInstance)o).getSlotIndex()).sorted().toArray();
        List<String> roles=fbzRoles(live);
        forceRecreate(h.manager);
        List<ObjectInstance> restored=h.manager.getActiveObjects().stream().filter(o->!o.isDestroyed()).toList();
        assertArrayEquals(slots,restored.stream().mapToInt(o->((AbstractObjectInstance)o).getSlotIndex()).sorted().toArray());
        assertEquals(roles,fbzRoles(restored));
        Fbz2SubbossInstance restoredRoot=h.manager.activeObjectsOfType(Fbz2SubbossInstance.class).get(0);
        assertEquals(capacity>=5,restoredRoot.upperLeft()!=null);
        assertEquals(capacity>=6,restoredRoot.upperRight()!=null);
        releaseUnused(h.manager);h.step(99);
        assertEquals(roles,fbzRoles(h.manager.getActiveObjects().stream().filter(o->!o.isDestroyed()).toList()),
                "forced restore must not heal a failed initial prefix");
    }

    @TestFactory Collection<DynamicTest> sideSolidTableStopsAtFirstFailureAndNeverRetries(){
        List<DynamicTest> tests=new ArrayList<>();
        for(int capacity=0;capacity<=2;capacity++){int c=capacity;tests.add(DynamicTest.dynamicTest("side capacity "+c,()->{
            Harness h=harness();h.manager.createDynamicObject(()->new Fbz2SubbossInstance(new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));h.step(0);
            h.manager.reserveAllButNFreeSlots(c);when(h.player.getCentreX()).thenReturn((short)0x2B40);h.step(1);
            assertEquals(c,h.manager.activeObjectsOfType(Fbz2SubbossSolidSideChild.class).size());
            int[] slots=h.manager.activeObjectsOfType(Fbz2SubbossSolidSideChild.class).stream()
                    .mapToInt(AbstractObjectInstance::getSlotIndex).sorted().toArray();
            forceRecreate(h.manager);
            assertArrayEquals(slots,h.manager.activeObjectsOfType(Fbz2SubbossSolidSideChild.class).stream()
                    .mapToInt(AbstractObjectInstance::getSlotIndex).sorted().toArray());
            for(Fbz2SubbossSolidSideChild side:h.manager.activeObjectsOfType(Fbz2SubbossSolidSideChild.class)){
                assertNotNull(side.root());assertNotNull(side.corner());
            }
            releaseUnused(h.manager);for(int f=2;f<10;f++)h.step(f);
            assertEquals(c,h.manager.activeObjectsOfType(Fbz2SubbossSolidSideChild.class).size(),"latched table must not heal");
        }));}return tests;
    }

    @Test void failedLaserAllocationLeavesTheNativeActiveStallAndDoesNotRetry(){
        Harness h=harness();Fbz2SubbossInstance root=h.manager.createDynamicObject(()->new Fbz2SubbossInstance(new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));h.step(0);
        h.manager.activeObjectsOfType(SongFadeTransitionInstance.class).forEach(h.manager::removeDynamicObject);
        h.manager.reserveAllButNFreeSlots(0);when(h.player.getCentreX()).thenReturn((short)0x2B40);h.step(1);
        for(int f=2;f<=121;f++)h.step(f);
        assertEquals("ACTIVE",root.phaseName());assertTrue(h.manager.activeObjectsOfType(Fbz2SubbossLaserChild.class).isEmpty());
        releaseUnused(h.manager);for(int f=122;f<140;f++)h.step(f);
        assertTrue(h.manager.activeObjectsOfType(Fbz2SubbossLaserChild.class).isEmpty());
    }

    @Test void nativeMusicTransitionsUseMinibossThenFbz2IdsEvenWhenOtherAllocationsFail() {
        Harness h=harness();
        Fbz2SubbossInstance root=h.manager.createDynamicObject(()->new Fbz2SubbossInstance(
                new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));
        h.manager.reserveAllButNFreeSlots(1);
        h.step(0);
        SongFadeTransitionInstance intro=h.manager.activeObjectsOfType(SongFadeTransitionInstance.class)
                .stream().findFirst().orElseThrow();
        assertEquals(Sonic3kMusic.MINIBOSS_S3.id,intro.getMusicIdForTest());

        releaseUnused(h.manager);
        h.manager.removeDynamicObject(intro);
        for(int i=0;i<7;i++)root.completeLaserCycleForTest();
        for(int frame=1;frame<=96;frame++)h.step(frame);
        SongFadeTransitionInstance restore=h.manager.activeObjectsOfType(SongFadeTransitionInstance.class)
                .stream().findFirst().orElseThrow();
        assertEquals(Sonic3kMusic.FBZ2.id,restore.getMusicIdForTest());
    }

    @Test void defeatArtQueueReportsNativePrefixUnderCapacityPressure() throws Exception {
        File romFile=RomTestUtils.ensureSonic3kRomAvailable();
        org.junit.jupiter.api.Assumptions.assumeTrue(romFile != null);
        try(Rom rom=new Rom()){
            assertTrue(rom.open(romFile.getAbsolutePath()));
            for(int prefilled:new int[]{3,4}){
                Harness h=harness();h.romHolder[0]=rom;
                for(int i=0;i<prefilled;i++)
                    assertTrue(h.queue.enqueue(rom,0x0D6A62,0x1000+i*0x800));
                Fbz2SubbossInstance root=h.manager.createDynamicObject(()->new Fbz2SubbossInstance(
                        new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));
                h.step(0);for(int cycle=0;cycle<7;cycle++)root.completeLaserCycleForTest();
                int expectedQueued=4-prefilled;
                assertEquals(expectedQueued,root.defeatArtQueuedCount());
                assertNotNull(root.defeatArtQueueFailure());
                assertTrue(root.defeatArtQueueFailure().contains("after "+expectedQueued));
                assertEquals(4,h.queue.queuedArchiveCount());
                assertEquals(prefilled==3?Sonic3kPlcLoader.fbz2SubbossDefeatKosmEntries().getFirst().sourceAddress()
                                :0x0D6A62,
                        h.queue.queuedArchives().getLast().archiveAddress(),
                        "Queue_Kos_Module retains the accepted prefix and never rolls it back");
            }
        }
    }

    @TestFactory Collection<DynamicTest> songFadeCallersUseInclusiveNativeSignedWaitWords() {
        List<DynamicTest> tests=new ArrayList<>();
        for(int waitWord:new int[]{2,30,90,120}){
            tests.add(DynamicTest.dynamicTest("native wait word "+waitWord+" completes on update "+(waitWord+1),()->{
                Harness h=harness();
                SongFadeTransitionInstance transition=h.manager.createDynamicObject(
                        ()->new SongFadeTransitionInstance(waitWord,Sonic3kMusic.MINIBOSS_S3.id));
                assertEquals(waitWord,transition.nativeWaitWordForTest());
                for(int frame=0;frame<waitWord;frame++)transition.update(frame,h.player);
                verify(h.audio).fadeOutMusic();
                verify(h.audio,never()).playMusic(Sonic3kMusic.MINIBOSS_S3.id);
                assertFalse(transition.isDestroyed(),"native wait word has not underflowed through update "+waitWord);
                transition.update(waitWord,h.player);
                verify(h.audio).playMusic(Sonic3kMusic.MINIBOSS_S3.id);
                assertTrue(transition.isDestroyed(),"helper deletes on the same update that starts target music");
            }));
        }
        return tests;
    }

    @Test void songFadeFactoriesKeepLockedOnTransitionAndLevelRestoreSemanticsDistinct() {
        assertEquals(90,SongFadeTransitionInstance.transitionTo(Sonic3kMusic.KNUCKLES.id)
                .nativeWaitWordForTest());
        assertEquals(120,SongFadeTransitionInstance.toLevelMusic(Sonic3kMusic.FBZ2.id)
                .nativeWaitWordForTest());
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    void rumbleControllerDiesWhenTheGlobalShakeFlagClearsAndFinalShakePersists() {
        Harness h=harness();
        Fbz2SubbossInstance root=h.manager.createDynamicObject(()->new Fbz2SubbossInstance(
                new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));
        h.step(0);
        Fbz2SubbossRumbleController nonfinal=h.manager.createDynamicObject(
                Fbz2SubbossRumbleController::new);
        h.step(1);
        assertTrue(h.bridge.shakeActive);
        h.bridge.shakeActive=false;
        h.step(2);
        assertFalse(h.manager.getActiveObjects().contains(nonfinal),
                "loc_86438 observes Screen_shake_flag rather than root lifetime");

        Fbz2SubbossRumbleController finalRumble=h.manager.createDynamicObject(
                Fbz2SubbossRumbleController::new);
        h.step(3);
        for(int i=0;i<7;i++)root.completeLaserCycleForTest();
        h.step(4);
        assertFalse(finalRumble.isDestroyed());
        assertTrue(h.bridge.shakeActive, "final cycle leaves the global shake asserted into Task 15");
    }

    @Test void forcedReconstructionRestoresExactRolesSlotsAndLinksWithoutDuplicates(){
        Harness h=harness();h.manager.createDynamicObject(()->new Fbz2SubbossInstance(new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));h.step(0);
        List<ObjectInstance> before=h.manager.getActiveObjects().stream().filter(o->!o.isDestroyed()).toList();int[] slots=before.stream().mapToInt(o->((AbstractObjectInstance)o).getSlotIndex()).sorted().toArray();
        RewindRegistry registry=new RewindRegistry();registry.register(h.manager.rewindSnapshottable());CompositeSnapshot snapshot=registry.capture();
        h.manager.setRewindInPlaceRestoreEnabledForTest(false);new ArrayList<>(h.manager.getActiveObjects()).forEach(h.manager::removeDynamicObject);registry.restore(snapshot);
        List<ObjectInstance> after=h.manager.getActiveObjects().stream().filter(o->!o.isDestroyed()).toList();assertEquals(before.size(),after.size());assertArrayEquals(slots,after.stream().mapToInt(o->((AbstractObjectInstance)o).getSlotIndex()).sorted().toArray());
        Fbz2SubbossInstance root=after.stream().filter(Fbz2SubbossInstance.class::isInstance).map(Fbz2SubbossInstance.class::cast).findFirst().orElseThrow();assertNotNull(root.upperLeft());assertNotNull(root.upperRight());
        for(Fbz2SubbossSolidSideChild side:h.manager.activeObjectsOfType(Fbz2SubbossSolidSideChild.class)){assertSame(root,side.root());assertNotNull(side.corner());}
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    void forcedReconstructionAtRawBeamCallbackPreservesOneShotRumbleAndExplosionAllocation(){
        Harness h=harness();
        Fbz2SubbossInstance root=h.manager.createDynamicObject(()->new Fbz2SubbossInstance(
                new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));
        h.step(0);
        Fbz2SubbossLaserChild laser=h.manager.createDynamicObject(()->new Fbz2SubbossLaserChild(root));
        for(int call=1;call<=302;call++)laser.update(call,h.player);
        assertTrue(h.manager.activeObjectsOfType(Fbz2SubbossRumbleController.class).isEmpty());
        assertTrue(h.manager.activeObjectsOfType(Fbz2SubbossExplosionController.class).isEmpty());

        RewindRegistry registry=new RewindRegistry();
        registry.register(h.manager.rewindSnapshottable());
        CompositeSnapshot beforeImpact=registry.capture();
        h.manager.setRewindInPlaceRestoreEnabledForTest(false);
        new ArrayList<>(h.manager.getActiveObjects()).forEach(h.manager::removeDynamicObject);
        registry.restore(beforeImpact);

        Fbz2SubbossLaserChild restoredLaser=h.manager.activeObjectsOfType(Fbz2SubbossLaserChild.class)
                .stream().findFirst().orElseThrow();
        restoredLaser.update(303,h.player);
        assertEquals(0xAC,restoredLaser.getCollisionFlags());
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossRumbleController.class).size());
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossExplosionController.class).size());

        CompositeSnapshot afterImpact=registry.capture();
        new ArrayList<>(h.manager.getActiveObjects()).forEach(h.manager::removeDynamicObject);
        registry.restore(afterImpact);
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossRumbleController.class).size());
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossExplosionController.class).size());
        h.step(304);
        assertTrue(h.bridge.shakeActive);
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossRumbleController.class).size(),
                "the restored one-shot callback must not allocate a duplicate controller");
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossExplosionController.class).size(),
                "the restored one-shot callback must not allocate a duplicate explosion owner");
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    void forcedReconstructionPreservesDetachedRolesDefeatWaitAndPilotEscapeCleanup(){
        Harness h=harness();
        Fbz2SubbossInstance root=h.manager.createDynamicObject(()->new Fbz2SubbossInstance(
                new ObjectSpawn(0x2B40,0x5F0,0xAB,0,0,true,417)));
        h.step(0);
        for(int cycle=0;cycle<7;cycle++)root.completeLaserCycleForTest();
        h.step(1);
        assertEquals("DEFEAT_QUEUE_WAIT",root.phaseName());
        int waitAfterDetach=root.waitWordForTest();
        int[] detachedSlots=h.manager.getActiveObjects().stream()
                .filter(o->o instanceof Fbz2SubbossMachineChild||o instanceof Fbz2SubbossCornerChild
                        ||o instanceof Fbz2SubbossCharacterChild)
                .mapToInt(o->((AbstractObjectInstance)o).getSlotIndex()).sorted().toArray();
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossExplosionController.class).size(),
                "detaching the machine attempts its explosion controller exactly once");

        forceRecreate(h.manager);
        root=h.manager.activeObjectsOfType(Fbz2SubbossInstance.class).get(0);
        assertEquals("DEFEAT_QUEUE_WAIT",root.phaseName());
        assertEquals(waitAfterDetach,root.waitWordForTest());
        assertArrayEquals(detachedSlots,h.manager.getActiveObjects().stream()
                .filter(o->o instanceof Fbz2SubbossMachineChild||o instanceof Fbz2SubbossCornerChild
                        ||o instanceof Fbz2SubbossCharacterChild)
                .mapToInt(o->((AbstractObjectInstance)o).getSlotIndex()).sorted().toArray());
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossExplosionController.class).size());
        h.step(2);
        assertEquals(1,h.manager.activeObjectsOfType(Fbz2SubbossExplosionController.class).size(),
                "restored detached machine must not replay its callback");

        for(int frame=3;frame<=96;frame++)h.step(frame);
        Fbz2SubbossCharacterChild pilot=h.manager.activeObjectsOfType(Fbz2SubbossCharacterChild.class)
                .stream().findFirst().orElseThrow();
        assertEquals("DEFEAT_RESTORE_WAIT",h.manager.activeObjectsOfType(Fbz2SubbossInstance.class).get(0).phaseName());
        assertTrue(pilot.runningForTest());
        int pilotSlot=pilot.getSlotIndex(),escapeY=pilot.getY();
        forceRecreate(h.manager);
        pilot=h.manager.activeObjectsOfType(Fbz2SubbossCharacterChild.class).stream().findFirst().orElseThrow();
        assertEquals(pilotSlot,pilot.getSlotIndex());
        assertTrue(pilot.runningForTest());
        assertEquals(escapeY,pilot.getY(),"forced restore must not repeat the one-shot y -= 4 escape offset");
        File romFile=RomTestUtils.ensureSonic3kRomAvailable();
        org.junit.jupiter.api.Assumptions.assumeTrue(romFile != null,"S3K ROM required for raw PLC handoff");
        try(Rom rom=new Rom()){
            assertTrue(rom.open(romFile.getAbsolutePath()));
            h.romHolder[0]=rom;
            h.levelHolder[0]=mock(Sonic3kLevel.class);
            h.levelManagerHolder[0]=mock(LevelManager.class);
            KosinskiModuleQueue.Snapshot beforeHandoff=h.queue.capture();
            h.step(97);
            assertTrue(h.manager.activeObjectsOfType(Fbz2SubbossCharacterChild.class).isEmpty(),
                    "the restored running pilot performs its native full-extents offscreen cleanup");
            KosinskiModuleQueue.Snapshot afterHandoff=h.queue.capture();
            for(var entry:Sonic3kPlcLoader.monitorSpikesSpringsPlcEntries()){
                int address=entry.tileIndex()*32;
                assertTrue(afterHandoff.appliedWrites().stream().anyMatch(write->address>=write.destinationVramBytes()
                                &&address<write.destinationVramBytes()+write.data().length),
                        "raw handoff image must contain destination $"+Integer.toHexString(address));
            }
            h.queue.restore(beforeHandoff);
            assertTrue(h.queue.capture().appliedWrites().isEmpty());
            h.queue.restore(afterHandoff);
            assertEquals(afterHandoff.appliedWrites().size(),h.queue.capture().appliedWrites().size(),
                    "raw pilot cleanup writes must survive queue rewind without duplication");
        }
    }

    private static void releaseUnused(ObjectManager manager){Set<Integer> occupied=manager.getActiveObjects().stream().filter(o->!o.isDestroyed()).map(o->((AbstractObjectInstance)o).getSlotIndex()).collect(java.util.stream.Collectors.toSet());for(int s=4;s<=92;s++)if(!occupied.contains(s))manager.releaseDynamicSlot(s);}

    private static void forceRecreate(ObjectManager manager){
        RewindRegistry registry=new RewindRegistry();registry.register(manager.rewindSnapshottable());
        CompositeSnapshot snapshot=registry.capture();manager.setRewindInPlaceRestoreEnabledForTest(false);
        new ArrayList<>(manager.getActiveObjects()).forEach(manager::removeDynamicObject);registry.restore(snapshot);
    }

    private static List<String> fbzRoles(List<ObjectInstance> objects){
        return objects.stream().filter(o->o instanceof Fbz2SubbossInstance||o instanceof AbstractFbz2SubbossChild
                        ||o instanceof SongFadeTransitionInstance)
                .map(o->o instanceof Fbz2SubbossCornerChild c?o.getClass().getSimpleName()+":"+c.nativeSubtype()
                        :o.getClass().getSimpleName())
                .sorted().toList();
    }


    private Harness harness(){
        PlayableEntity p1=mock(AbstractPlayableSprite.class);ObjectManager[] holder=new ObjectManager[1];
        Sonic3kLevel[] levelHolder=new Sonic3kLevel[1];Rom[] romHolder=new Rom[1];
        LevelManager[] levelManagerHolder=new LevelManager[1];KosinskiModuleQueue queue=new KosinskiModuleQueue();
        Camera camera=new Camera(){@Override public short getX(){return (short)0x2A00;}@Override public short getY(){return 0x560;}@Override public short getWidth(){return 320;}@Override public short getHeight(){return 224;}@Override public boolean isVerticalWrapEnabled(){return false;}};
        AudioManager audio=mock(AudioManager.class);
        RecordingBridge bridge=new RecordingBridge();
        ObjectServices services=new StubObjectServices(){@Override public ObjectManager objectManager(){return holder[0];}@Override public Camera camera(){return camera;}@Override public GraphicsManager graphicsManager(){return GraphicsManager.getInstance();}@Override public AudioManager audioManager(){return audio;}@Override public void playMusic(int musicId){audio.playMusic(musicId);}@Override public ObjectPlayerQuery playerQuery(){return new ObjectPlayerQuery(()->p1,List::of);}@Override public LevelEventProvider levelEventProvider(){return bridge;}@Override public Sonic3kLevel currentLevel(){return levelHolder[0];}@Override public Rom rom(){return romHolder[0];}@Override public KosinskiModuleQueue kosinskiModuleQueue(){return queue;}@Override public LevelManager levelManager(){return levelManagerHolder[0];}};
        ObjectManager manager=new ObjectManager(List.of(),new Sonic3kObjectRegistry(),0,null,null,GraphicsManager.getInstance(),camera,services);holder[0]=manager;manager.reset(0);return new Harness(manager,p1,bridge,audio,levelHolder,romHolder,levelManagerHolder,queue);
    }
    private record Harness(ObjectManager manager,PlayableEntity player,RecordingBridge bridge,AudioManager audio,
                           Sonic3kLevel[] levelHolder,Rom[] romHolder,LevelManager[] levelManagerHolder,
                           KosinskiModuleQueue queue){void step(int frame){manager.update(0x2A00,player,List.of(),frame,false);}}
    private static final class RecordingBridge implements FbzObjectEventBridge,LevelEventProvider{
        boolean shakeActive;
        @Override public void initLevel(int z,int a){} @Override public void update(){}
        @Override public void setMagneticState(Sonic3kFBZEvents.MagneticPolarity p,int t){}
        @Override public void setCloudRewindId(int i,ObjectRefId id){} @Override public void setCloudCleanupTerminal(boolean v){}
        @Override public void setBossLoadPositionAdjustmentPending(boolean v){} @Override public void setBossBackgroundOffsets(int x,int y){}
        @Override public void setPlaneAssignmentMode(Sonic3kFBZEvents.PlaneAssignmentMode p){}
        @Override public void setCollisionMode(Sonic3kFBZEvents.CollisionMode c,int x,int y){}
        @Override public void setScreenShakeState(boolean active,int offset,int phase){shakeActive=active;}
        @Override public boolean isScreenShakeActive(){return shakeActive;}
    }
    @Test void adverseRoleOrderRelinksOnlyExistingPrefixMembers() {
        Fbz2SubbossInstance root = new Fbz2SubbossInstance(
                new ObjectSpawn(0x2B40, 0x5F0, 0xAB, 0, 0, true, 417));
        root.setSlotIndex(80);
        Fbz2SubbossCornerChild upperLeft = Fbz2SubbossCornerChild.forTest(root, 0);
        Fbz2SubbossSolidSideChild left = Fbz2SubbossSolidSideChild.forTest(root, 0);
        Fbz2SubbossRewindLinks.settleForTest(root, new Object[] {left, upperLeft});
        assertSame(upperLeft, root.upperLeft());
        assertNull(root.upperRight());
        assertSame(root, left.root());
        assertSame(upperLeft, left.corner());
    }

    @Test void everyChildCanRecreateWithoutALiveRoot() {
        Fbz2SubbossInstance root = new Fbz2SubbossInstance(
                new ObjectSpawn(0x2B40, 0x5F0, 0xAB, 0, 0, true, 417));
        root.setSlotIndex(80);
        for (AbstractObjectInstance child : Fbz2SubbossRewindLinks.childrenForTest(root)) {
            RewindRecreatable recreatable = assertInstanceOf(RewindRecreatable.class, child);
            assertNotNull(recreatable.recreateForRewind(new RewindRecreateContext(
                    child.getSpawn(), null, new StubObjectServices(), null, null)));
        }
    }
}
