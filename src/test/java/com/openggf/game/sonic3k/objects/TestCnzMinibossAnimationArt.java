package com.openggf.game.sonic3k.objects;

import com.openggf.audio.AudioManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CNZ miniboss sprite-frame coverage against the S&K-side raw animation
 * labels at sonic3k.asm:145705-145711.
 */
class TestCnzMinibossAnimationArt {

    @Test
    void bossOpeningRawAnimationSelectsIntermediateMapFramesBeforeOpenFrame() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());

        boss.forceRoutineForTest(8);
        boss.update(0, null);
        boss.appendRenderCommands(new ArrayList<>());

        verify(harness.renderer()).drawFrameIndex(1, 0x3240, 0x02B8, false, false);

        for (int i = 1; i <= 4; i++) {
            clearInvocations(harness.renderer(), harness.renderManager());
            boss.update(i, null);
            boss.appendRenderCommands(new ArrayList<>());
        }

        verify(harness.renderer()).drawFrameIndex(2, 0x3240, 0x02B8, false, false);
    }

    @Test
    void bossClosingRawAnimationWalksBackFromOpenFrame() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());

        boss.forceOpenForTest();
        boss.simulateHitForTest();
        boss.update(0, null);
        boss.update(1, null);
        boss.appendRenderCommands(new ArrayList<>());

        verify(harness.renderer()).drawFrameIndex(6, 0x3240, 0x02B8, false, false);
    }

    @Test
    void bossClosingRawAnimationUsesExactMultiDelayDuration() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());

        boss.forceRoutineForTest(0x0C);
        for (int frame = 0; frame < 25; frame++) {
            boss.update(frame, null);
        }
        assertEquals(0x0C, boss.getCurrentRoutine(),
                "Closing must remain active through the six delay-3 frame holds");

        boss.update(25, null);
        assertEquals(0x06, boss.getCurrentRoutine(),
                "AniRaw_CNZMinibossClosing's $F4 terminator invokes CloseGo in the same dispatch");
    }

    @Test
    void topWait2RawGetFasterSelectsFrame8BeforeLaunchingToMain() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(harness.services());

        top.update(0, null);
        top.update(1, null);
        top.update(2, null);
        top.appendRenderCommands(new ArrayList<>());

        verify(harness.renderer()).drawFrameIndex(8, 0x3240, 0x0300, false, false);
    }

    @Test
    void openCoilSparkChildAnimatesElectricityFrames() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());
        boss.forceOpenForTest();
        CnzMinibossSparkInstance spark = new CnzMinibossSparkInstance(
                new ObjectSpawn(0x323C, 0x02E0, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        spark.setServices(harness.services());
        spark.attachBossForTest(boss);

        spark.update(0, null);
        spark.appendRenderCommands(new ArrayList<>());

        verify(harness.renderer()).drawFrameIndex(0x0A, 0x323C, 0x02E0, false, false);

        for (int i = 1; i <= 9; i++) {
            clearInvocations(harness.renderer(), harness.renderManager());
            spark.update(i, null);
            spark.appendRenderCommands(new ArrayList<>());
        }

        verify(harness.renderer()).drawFrameIndex(0x0B, 0x323C, 0x02E0, false, false);
    }

    @Test
    void openCoilSparkRendersInFrontOfMinibossBase() {
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        CnzMinibossSparkInstance spark = new CnzMinibossSparkInstance(
                new ObjectSpawn(0x323C, 0x02E0, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));

        assertTrue(boss.getPriorityBucket() > spark.getPriorityBucket(),
                "ObjDat_CNZMiniboss priority $0280 must draw behind ObjDat3_CNZMinibossSpark priority $0200");
        assertTrue(boss.isHighPriority(),
                "ObjDat_CNZMiniboss uses make_art_tile(ArtTile_CNZMiniboss,1,1)");
        assertTrue(spark.isHighPriority(),
                "ObjDat3_CNZMinibossSpark uses the high-priority CNZ miniboss art tile");
    }

    @Test
    void nonFinalTopHitRunsBossDamageFlashAndStun() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());

        boss.forceOpenForTest();
        boss.simulateHitForTest();
        boss.update(0, null);

        assertTrue(harness.playedSfx().contains(Sonic3kSfx.BOSS_HIT.id),
                "CNZMiniboss_CheckTopHit plays sfx_BossHit on non-final top hits");
        assertColorEquals(colorFromSega(0x0888), harness.paletteLine1().getColor(2),
                "CNZMiniboss_BossFlash must write the first bright flash color to line 2 color 2");
        assertEquals(0, boss.getCollisionFlags(),
                "Boss collision must stay suppressed during the $20-frame hit stun flash");

        for (int i = 1; i <= 0x21; i++) {
            boss.update(i, null);
        }

        assertFalse(boss.getState().invulnerable,
                "The non-final hit flash must expire after the ROM $20-frame stun timer");
    }

    @Test
    void finalTopHitSpawnsDefeatExplosionAnimation() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());

        for (int i = 0; i < 4; i++) {
            boss.simulateHitForTest();
        }

        assertTrue(harness.spawnedChildren().stream().anyMatch(S3kBossExplosionChild.class::isInstance),
                "CNZMiniboss_BossDefeated creates the visible boss explosion animation child");
    }

    @Test
    void finalTopHitSpawnsNineBreakApartDebrisPieces() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());

        for (int i = 0; i < 4; i++) {
            boss.simulateHitForTest();
        }

        long debrisCount = harness.spawnedChildren().stream()
                .filter(CnzMinibossDebrisChild.class::isInstance)
                .count();

        assertEquals(9, debrisCount,
                "Obj_CNZMinibossEnd must create Child6_CNZMinibossMakeDebris's nine break-apart pieces");
    }

    @Test
    void finalTopHitStopsDrawingIntactMinibossBase() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());

        for (int i = 0; i < 4; i++) {
            boss.simulateHitForTest();
        }

        clearInvocations(harness.renderer(), harness.renderManager());
        boss.appendRenderCommands(new ArrayList<>());

        verify(harness.renderer(), never())
                .drawFrameIndex(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void finalTopHitQueuesFadeBackToAct1Music() {
        RenderHarness harness = new RenderHarness();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(harness.services());

        for (int i = 0; i < 4; i++) {
            boss.simulateHitForTest();
        }

        SongFadeTransitionInstance transition = harness.spawnedChildren().stream()
                .filter(SongFadeTransitionInstance.class::isInstance)
                .map(SongFadeTransitionInstance.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "CNZ miniboss defeat must allocate Obj_Song_Fade_ToLevelMusic"));

        transition.update(0, null);

        // cmd_FadeOut carries no parameters of its own; the S3K profile owns
        // 28h/6 (Sound/Z80 Sound Driver.asm:2306-2311) and
        // TestGameFadeOutParameters pins that the no-argument fade resolves to
        // them for S3K and to the S1/S2 values for the other two games.
        verify(harness.audioManager()).fadeOutMusic();
        for (int frame = 1; frame < 120; frame++) {
            transition.update(frame, null);
        }
        assertTrue(harness.playedMusic().contains(Sonic3kMusic.CNZ1.id),
                "Obj_Song_Fade_ToLevelMusic must restore Carnival Night Act 1 music after the fade");
    }

    private static Palette.Color colorFromSega(int value) {
        byte[] bytes = {(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        Palette.Color color = new Palette.Color();
        color.fromSegaFormat(bytes, 0);
        return color;
    }

    private static void assertColorEquals(Palette.Color expected, Palette.Color actual, String message) {
        assertEquals(expected.r, actual.r, message + " (red)");
        assertEquals(expected.g, actual.g, message + " (green)");
        assertEquals(expected.b, actual.b, message + " (blue)");
    }

    private static final class RenderHarness {
        private final LevelManager levelManager = mock(LevelManager.class);
        private final ObjectManager objectManager = mock(ObjectManager.class);
        private final ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        private final PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        private final AudioManager audioManager = mock(AudioManager.class);
        private final Level level = mock(Level.class);
        private final Palette paletteLine1 = new Palette();
        private final List<Integer> playedSfx = new ArrayList<>();
        private final List<Integer> playedMusic = new ArrayList<>();
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();

        private RenderHarness() {
            when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
            when(levelManager.getObjectManager()).thenReturn(objectManager);
            when(levelManager.getCurrentLevel()).thenReturn(level);
            when(levelManager.getCurrentLevelMusicId()).thenReturn(Sonic3kMusic.CNZ1.id);
            when(level.getPaletteCount()).thenReturn(4);
            when(level.getPalette(1)).thenReturn(paletteLine1);
            when(renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_MINIBOSS)).thenReturn(renderer);
            when(renderer.isReady()).thenReturn(true);
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                if (child instanceof com.openggf.level.objects.AbstractObjectInstance instance) {
                    instance.setServices(services());
                }
                spawnedChildren.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
        }

        private TestObjectServices services() {
            TestObjectServices services = new TestObjectServices() {
                @Override
                public void playSfx(int soundId) {
                    playedSfx.add(soundId);
                }

                @Override
                public void playMusic(int musicId) {
                    playedMusic.add(musicId);
                }
            };
            services.withLevelManager(levelManager).withAudioManager(audioManager);
            return services;
        }

        private ObjectRenderManager renderManager() {
            return renderManager;
        }

        private PatternSpriteRenderer renderer() {
            return renderer;
        }

        private AudioManager audioManager() {
            return audioManager;
        }

        private Palette paletteLine1() {
            return paletteLine1;
        }

        private List<Integer> playedSfx() {
            return playedSfx;
        }

        private List<Integer> playedMusic() {
            return playedMusic;
        }

        private List<ObjectInstance> spawnedChildren() {
            return spawnedChildren;
        }
    }
}
