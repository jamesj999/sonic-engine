package com.openggf.audio;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageCollisionQueue;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageGrid;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageManager;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStagePlayer;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.CELL_BLUE;
import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.COLLISION_QUEUE_SIZE;
import static org.junit.jupiter.api.Assertions.*;

/** Retail blue-sphere request and FM5 release boundaries; no instruction-timing oracle. */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kBlueSphereAudioRom {
    private Rom rom;
    private AudioManager audio;
    private final List<String> writes = new ArrayList<>();
    private final List<String> physicalWrites = new ArrayList<>();
    private final int[] physicalAddress = new int[2];
    private final ChipWriteObserver observer = new ChipWriteObserver() {
        @Override public void onYm2612Write(int port, int register, int value) {
            writes.add("ym%d[%02X]=%02X".formatted(port, register, value));
        }
        @Override public void onPsgWrite(int value) { }
        @Override public boolean observesPhysicalWrites() { return true; }
        @Override public void onYm2612BusWrite(long cycle, int busPort, int value,
                PhysicalWriteOrigin origin) {
            int port = busPort >>> 1;
            if ((busPort & 1) == 0) {
                physicalAddress[port] = value;
            } else if (origin == PhysicalWriteOrigin.EXTERNAL_BUS) {
                physicalWrites.add("ym%d[%02X]=%02X".formatted(port, physicalAddress[port], value));
            }
        }
    };

    @BeforeEach
    void install() {
        rom = new Rom();
        assertTrue(rom.open(RomTestUtils.ensureSonic3kRomAvailable().getAbsolutePath()));
        audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setRom(rom);
        Sonic3kAudioProfile profile = new Sonic3kAudioProfile();
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
    }

    @AfterEach
    void close() {
        audio.setRequestObserver(null);
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        rom.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blueContactRequestsSoundEvenWhenTheAnimationQueueIsFull(boolean full) throws Exception {
        Sonic3kSpecialStageManager stage = new Sonic3kSpecialStageManager();
        field(stage, "player", Sonic3kSpecialStagePlayer.class).initialize(0, 0, 0, false);
        field(stage, "grid", Sonic3kSpecialStageGrid.class).setCellByIndex(0, CELL_BLUE);
        Sonic3kSpecialStageCollisionQueue queue = field(
                stage, "collisionQueue", Sonic3kSpecialStageCollisionQueue.class);
        if (full) {
            for (int slot = 0; slot < COLLISION_QUEUE_SIZE; slot++) {
                assertTrue(queue.addBlueSphere(slot + 1));
            }
            assertFalse(queue.addBlueSphere(0), "the contacted sphere has no animation slot");
        }
        List<Integer> requests = new ArrayList<>();
        audio.setRequestObserver((kind, id) -> requests.add(id));

        collide(stage);

        // loc_97AA branches to loc_97BE on allocation failure; Play_SFX is
        // outside the successful-slot writes (sonic3k.asm:12131-12142).
        assertEquals(List.of(Sonic3kSfx.BLUE_SPHERE.id), requests);
        assertFalse(queue.addBlueSphere(0), "no duplicate slot, including after failed admission");
    }

    @Test
    void emptyCellDoesNotRequestTheBlueSphereSound() throws Exception {
        Sonic3kSpecialStageManager stage = new Sonic3kSpecialStageManager();
        field(stage, "player", Sonic3kSpecialStagePlayer.class).initialize(0, 0, 0, false);
        List<Integer> requests = new ArrayList<>();
        audio.setRequestObserver((kind, id) -> requests.add(id));
        collide(stage);
        assertEquals(List.of(), requests);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realBlueSphereStopPreservesTheCoveredMusicRestBit(boolean initiallyResting) {
        Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
        try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                "s3k-blue-sphere", 0, new SmpsPhysicalDevice.Settings(44_100, false),
                Sonic3kSmpsPhysicalPolicy.INSTANCE, ChipWriteObserver.NONE)) {
            SmpsDriver driver = stream.logicalDriver();
            SmpsSequencer music = new SmpsSequencer(loader.loadMusic(Sonic3kMusic.SPECIAL_STAGE.id),
                    loader.loadDacData(), driver, () -> { }, Sonic3kSmpsSequencerConfig.CONFIG);
            driver.addSequencer(music, false);
            SmpsSequencer.Track fm5 = music.getTracks().stream()
                    .filter(t -> t.type == SmpsSequencer.TrackType.FM && t.channelId == 4)
                    .findFirst().orElseThrow();
            // Explicit release-unit precondition, not trace hydration. Only the
            // SFX is walked below, isolating cfStopTrack from the next music walk.
            fm5.resting = initiallyResting;
            SmpsSequencer sfx = new SmpsSequencer(loader.loadSfx(Sonic3kSfx.BLUE_SPHERE.id),
                    loader.loadDacData(), driver, () -> { }, Sonic3kSmpsSequencerConfig.CONFIG);
            driver.addSequencer(sfx, true);
            assertTrue(fm5.overridden);
            stream.setChipWriteObserver(observer);
            int walks = 0;
            while (sfx.getTracks().getFirst().active && walks++ < 40) {
                writes.clear();
                sfx.serviceOuterFrame();
            }

            assertFalse(sfx.getTracks().getFirst().active, "real $65 must execute F2");
            assertFalse(fm5.overridden, "cfStopTrack clears music override bit 2");
            // Retail cfStopTrack and its voice/TL upload callees never write
            // PlaybackControl bit 4 (Z80 Sound Driver.asm:3443-3518, 3178-3215).
            assertEquals(initiallyResting, fm5.resting, "S3K must preserve music rest bit 4");
            assertTrue(writes.contains("ym0[28]=05"), "the SFX's own key-off stands");
            assertTrue(writes.stream().anyMatch(w -> w.startsWith("ym1[B1]")),
                    "handoff restores the music voice");
            assertFalse(writes.contains("ym0[28]=F5"), "handoff must not key music on");
            assertFalse(writes.stream().anyMatch(w -> w.startsWith("ym1[A1]")
                    || w.startsWith("ym1[A5]")), "handoff must not resend music pitch");
        }
    }

    @Test
    void publicPlaybackRunsBothRomLegsAndRetriggerRestartsTheHeader() {
        audio.setChipWriteObserver(observer);
        assertTrue(audio.playSfx(Sonic3kSfx.BLUE_SPHERE.id));
        service(); // zUpdateSFXTracks precedes queue fill: admission only.
        assertFalse(writes.contains("ym0[28]=F5"));
        assertEquals(5, sphere().volumeOffset());
        service(); // First walk: voice, positive modulation, first note.
        SmpsTrackSnapshot first = sphere();
        assertEquals(8, first.duration());
        assertEquals(-12, first.keyOffset());
        assertEquals(0x41, first.modCurrentDelta());
        assertEquals(0x41, first.modAccumulator(),
                "zUpdateFMorPSGTrack also calls zDoModulation on the attack walk (:782)");
        assertTrue(writes.contains("ym0[28]=F5"));
        assertTrue(writes.contains("ym1[45]=05"), "first-leg carrier attenuation comes from header");
        List<String> firstAttack = fm5Writes(writes);
        assertEquals(firstAttack, fm5Writes(physicalWrites), "actual chip bus receives the logical first attack");

        // Both $08 notes attack: there is no smpsNoAttack between them.
        // zDoModulation sign-extends $41 and $D0; zero steps underflows,
        // rather than disabling either leg (driver:1274-1321).
        for (int tick = 1; tick <= 8; tick++) {
            service();
            assertEquals(tick == 8, writes.contains("ym0[28]=F5"),
                    "only the second note boundary retriggers the FM attack");
            if (tick < 8) {
                assertEquals(0x41 * (tick + 1), sphere().modAccumulator());
            }
        }
        SmpsTrackSnapshot second = sphere();
        assertEquals(first.note(), second.note(), "both legs use nEb5, not a collection pitch ladder");
        assertEquals(8, second.duration());
        assertEquals(10, second.volumeOffset(), "E6 adds $05 attenuation, once");
        assertEquals(-48, second.modCurrentDelta());
        assertEquals(-48, second.modAccumulator());
        assertTrue(writes.contains("ym1[45]=0A"));
        assertEquals(fm5Writes(writes), fm5Writes(physicalWrites));
        service();
        assertEquals(-96, sphere().modAccumulator());

        assertTrue(audio.playSfx(Sonic3kSfx.BLUE_SPHERE.id));
        service();
        assertEquals(1, audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                .filter(SmpsDriverSnapshot.SequencerEntry::sfx).count(), "retrigger replaces the old instance");
        assertEquals(5, sphere().volumeOffset(), "a new request reloads header attenuation");
        assertTrue(writes.contains("ym0[28]=05"), "admission keys off the outgoing sound");
        service();
        assertEquals(firstAttack, fm5Writes(writes), "retrigger restarts the same ROM attack");
        assertEquals(fm5Writes(writes), fm5Writes(physicalWrites));
    }

    @Test
    void publicSnapshotRoundTripKeepsTheRunningSphereAndItsMusicOwnership() {
        audio.playMusic(Sonic3kMusic.SPECIAL_STAGE.id);
        service();
        audio.playSfx(Sonic3kSfx.BLUE_SPHERE.id);
        service();
        service();
        service();
        SmpsTrackSnapshot before = sphere();
        SmpsTrackSnapshot covered = musicFm5();
        assertTrue(covered.overridden());

        audio.restoreLogicalSnapshot(audio.captureLogicalSnapshot());

        assertEquals(before.duration(), sphere().duration());
        assertEquals(before.modAccumulator(), sphere().modAccumulator());
        assertEquals(before.volumeOffset(), sphere().volumeOffset());
        assertEquals(covered.resting(), musicFm5().resting());
        assertTrue(musicFm5().overridden());
        int remaining = 40;
        while (musicFm5().overridden() && remaining-- > 0) {
            service();
        }
        assertFalse(musicFm5().overridden(), "real F2 still hands the channel back after restore");
        assertEquals(0, audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                .filter(SmpsDriverSnapshot.SequencerEntry::sfx).count());
    }

    @Test
    void existingS1AndS2ReleasePoliciesStillMarkMusicAtRest() {
        Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
        for (SmpsSequencerConfig config : List.of(
                Sonic1SmpsSequencerConfig.CONFIG, Sonic2SmpsSequencerConfig.CONFIG)) {
            assertEquals(SmpsSequencerConfig.FmSfxReleaseMode.ROM_VOICE_RESTORE,
                    config.getFmSfxReleaseMode());
            // Data supplies a voice; this isolated policy test executes no
            // foreign bytecode and does not assert S1/S2 instruction behavior.
            SmpsSequencer music = new SmpsSequencer(loader.loadMusic(Sonic3kMusic.SPECIAL_STAGE.id),
                    loader.loadDacData(), () -> { }, config);
            SmpsSequencer.Track fm5 = music.getTracks().stream()
                    .filter(t -> t.type == SmpsSequencer.TrackType.FM && t.channelId == 4)
                    .findFirst().orElseThrow();
            fm5.resting = false;
            music.setChannelOverridden(SmpsSequencer.TrackType.FM, 4, true);
            music.setChannelOverridden(SmpsSequencer.TrackType.FM, 4, false);
            assertTrue(fm5.resting, "the pre-existing non-S3K policy is unchanged");
        }
    }

    private void service() {
        writes.clear();
        physicalWrites.clear();
        audio.presentFrame(PresentationMode.FORWARD);
    }

    private SmpsTrackSnapshot sphere() {
        return fm5(true);
    }

    private SmpsTrackSnapshot musicFm5() {
        return fm5(false);
    }

    private SmpsTrackSnapshot fm5(boolean sfx) {
        return audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                .filter(entry -> entry.sfx() == sfx).flatMap(entry -> entry.snapshot().tracks().stream())
                .filter(t -> t.type() == SmpsSequencer.TrackType.FM && t.channelId() == 4)
                .findFirst().orElseThrow();
    }

    private static List<String> fm5Writes(List<String> source) {
        return source.stream().filter(w -> w.startsWith("ym1[")
                || w.equals("ym0[28]=05") || w.equals("ym0[28]=F5")).toList();
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static void collide(Sonic3kSpecialStageManager stage) throws Exception {
        Method collision = Sonic3kSpecialStageManager.class.getDeclaredMethod("processCollision");
        collision.setAccessible(true);
        collision.invoke(stage);
    }
}
