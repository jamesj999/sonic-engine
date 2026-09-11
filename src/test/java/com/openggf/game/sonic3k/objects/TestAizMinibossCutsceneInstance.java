package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizMinibossCutsceneInstance {
    @Test
    void cutsceneStaysPersistentSoSpecialExplosionControllerExhaustsRomDrawCount() throws Exception {
        AizMinibossCutsceneInstance cutscene = buildCutscene(new TestObjectServices());

        assertTrue(cutscene.isPersistent(),
                "Obj_AIZMinibossCutscene has no normal out-of-range deletion in its active/exit flow "
                        + "(sonic3k.asm:136734-136896)");

        S3kBossExplosionController controller = new S3kBossExplosionController(0, 0, 2);
        int spawnCount = 0;
        for (int frame = 0; frame < 200 && !controller.isFinished(); frame++) {
            controller.tick();
            spawnCount += controller.drainPendingExplosions().size();
        }
        assertEquals(39, spawnCount,
                "Obj_BossExplosionSpecial subtype 2 should consume one Random_Number draw for each of "
                        + "$27..$01 before deleting at zero (sonic3k.asm:176746-176751,176780-176799)");
    }

    /**
     * The AIZ1 cutscene miniboss (object 0x90) is a one-shot scripted scene object whose
     * fly-off (EXIT_TIME_AIZ1 = 0x120 frames) is still running when the AIZ1->AIZ2 fire
     * transition snapshots persistent objects (~80-150 frames after Events_fg_5). Because it
     * (and its flame-barrel children, persistent via AbstractBossChild) is persistent, it gets
     * carried into AIZ2 with its world position un-offset, stranding its still-firing flame
     * children partway through AIZ2 where they keep hurting the player with no coherent parent.
     *
     * <p>ROM Obj_AIZMinibossCutscene's object RAM slot does not survive the AIZ2 reload, so the
     * engine must not carry the cutscene object or its children across the seamless act
     * transition.
     */
    @Test
    void aiz1CutsceneAndChildrenDoNotSurviveSeamlessActReload() throws Exception {
        TestObjectServices services = new TestObjectServices();
        AizMinibossCutsceneInstance cutscene = buildCutscene(services);

        // Simulate a live tracked flame-barrel child present mid fly-off, as during the scene.
        AizMinibossFlameBarrelChild barrel = buildBarrel(cutscene, services);
        cutscene.getChildComponents().add(barrel);

        // AIZ1 -> AIZ2 seamless transition world delta (LevelManager.offsetCarriedObjectsForTransition).
        cutscene.onCarriedAcrossSeamlessTransition(-0x2F00, -0x80);

        assertTrue(cutscene.isDestroyed(),
                "AIZ1 cutscene miniboss (0x90) must not survive the AIZ1->AIZ2 seamless reload");
        assertTrue(barrel.isDestroyed(),
                "AIZ1 cutscene miniboss flame-barrel children must not survive the reload");
    }

    private static AizMinibossFlameBarrelChild buildBarrel(
            AizMinibossCutsceneInstance parent, ObjectServices services) throws Exception {
        ThreadLocal<ObjectServices> context = constructionContext();
        context.set(services);
        try {
            AizMinibossFlameBarrelChild barrel = new AizMinibossFlameBarrelChild(parent, 0, true);
            barrel.setServices(services);
            return barrel;
        } finally {
            context.remove();
        }
    }

    /**
     * The AIZ miniboss drop plays {@code mus_Miniboss}, {@code $2E}
     * (sonic3k.asm:136807-136812, {@code AIZMiniboss_StartDropMusic}), which
     * also writes it to {@code Current_music+1}.
     *
     * <p>The engine used {@code mus_MinibossK}, {@code $18}. Both resolve to the
     * same arrangement through the S&amp;K driver table, so the wrong constant
     * is inaudible today and becomes audible the moment the S3 table is
     * selected. That is exactly the kind of divergence a listening test cannot
     * catch, so it is pinned by id here.
     */
    @Test
    void minibossDropRequestsTheRomsOwnMinibossTrack() throws Exception {
        RecordingMusicServices services = new RecordingMusicServices();
        AizMinibossCutsceneInstance cutscene = buildCutscene(services);

        java.lang.reflect.Method start =
                AizMinibossCutsceneInstance.class.getDeclaredMethod("onInitialDelayComplete");
        start.setAccessible(true);
        start.invoke(cutscene);

        assertEquals(List.of(0x2E), services.musicRequests,
                "AIZMiniboss_StartDropMusic plays mus_Miniboss ($2E), not mus_MinibossK ($18)");
    }

    /** Records the music ids an object requests. */
    private static final class RecordingMusicServices extends TestObjectServices {
        private final List<Integer> musicRequests = new ArrayList<>();

        @Override
        public void playMusic(int musicId) {
            musicRequests.add(musicId);
        }
    }

    /**
     * The AIZ miniboss cutscene really does fade, and at the S3K driver's rate.
     *
     * <p>Both of its fades are {@code cmd_FadeOut}: the entry fade before the
     * miniboss theme (ROM {@code loc_68556}, sonic3k.asm:136839-136846) and the
     * escape fade before the level music is restored ({@code loc_68646},
     * :136929-136946). {@code zFadeOutMusic} loads {@code zFadeOutTimeout} with
     * 28h and {@code zFadeDelay} with 6 (Sound/Z80 Sound Driver.asm:2306-2311),
     * so silence arrives after 240 frames rather than the 120 of S1 and S2.
     *
     * <p>This exists because an earlier source-level reading of these two sites
     * and a later execution run disagreed about whether any fade was issued at
     * all. It is issued; the run that saw none never drove the cutscene's own
     * entry, only the fire-curtain restore that follows it.
     */
    @Test
    void minibossCutsceneFadesAtTheS3kDriversRate() throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new AudioTestFixtures.RecordingAudioBackend());
        audio.setAudioProfile(new Sonic3kAudioProfile());
        audio.beginCommandTimelineFrame(0);

        ForwardingFadeServices services = new ForwardingFadeServices(audio);
        // The trigger only fires once the camera has reached the arena
        // (ROM loc_68556's Camera_min/max_X lock, sonic3k.asm:136839-136846).
        services.camera().setX((short) 0x3000);
        AizMinibossCutsceneInstance cutscene = buildCutscene(services);
        java.lang.reflect.Method trigger =
                AizMinibossCutsceneInstance.class.getDeclaredMethod("updateWaitTrigger");
        trigger.setAccessible(true);
        trigger.invoke(cutscene);

        List<AudioCommand.FadeOutMusic> fades = audio.commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .filter(AudioCommand.FadeOutMusic.class::isInstance)
                .map(AudioCommand.FadeOutMusic.class::cast)
                .toList();
        assertEquals(1, fades.size(),
                "the cutscene's trigger issues cmd_FadeOut (sonic3k.asm:136844-136845)");
        assertEquals(new AudioCommand.FadeOutMusic(0x28, 6), fades.getFirst(),
                "an S3K fade must carry the S3K driver's own 28h/6, not the S1/S2 28h/3");
    }

    /**
     * Object services whose fade reaches a real AudioManager, with a camera the
     * trigger's arena lock can write to.
     */
    private static final class ForwardingFadeServices extends TestObjectServices {
        private final AudioManager audio;
        private final com.openggf.camera.Camera camera = new com.openggf.camera.Camera();
        private final com.openggf.game.GameStateManager gameState =
                new com.openggf.game.GameStateManager();

        ForwardingFadeServices(AudioManager audio) {
            this.audio = audio;
        }

        @Override
        public com.openggf.camera.Camera camera() {
            return camera;
        }

        @Override
        public com.openggf.game.GameStateManager gameState() {
            return gameState;
        }

        @Override
        public void fadeOutMusic() {
            audio.fadeOutMusic();
        }
    }

    private static AizMinibossCutsceneInstance buildCutscene(ObjectServices services) throws Exception {
        ThreadLocal<ObjectServices> context = constructionContext();
        context.set(services);
        try {
            AizMinibossCutsceneInstance cutscene = new AizMinibossCutsceneInstance(
                    new ObjectSpawn(0x2FB0, 0x0350, 0x90, 0, 0, false, 0));
            cutscene.setServices(services);
            return cutscene;
        } finally {
            context.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<ObjectServices> constructionContext() throws Exception {
        Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
        field.setAccessible(true);
        return (ThreadLocal<ObjectServices>) field.get(null);
    }
}
