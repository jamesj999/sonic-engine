package com.openggf.game.sonic3k.titlescreen;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.control.InputHandler;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.LoadTimeProfile;
import com.openggf.game.timing.LoadTimeSimulationMode;
import com.openggf.game.timing.RomWorkBudgetScheduler;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ROM title loop decompresses frames 8-B synchronously inside
 * {@code TitleSonic_LoadFrame} (sonic3k.asm:5834) and queues frame 7's art at
 * {@code loc_4040}. Under the FAST load-time manifest those decodes cost the
 * frames the original hardware capture measured; under NONE the cadence is the
 * ROM's uniform four iterations per frame.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kTitleScreenFrameLoadTiming {

    /** ROM cadence: Title_anim_delay reload 3, advance when it reads 1. */
    private static final int ROM_ITERATIONS_PER_FRAME = 4;

    private Rom rom;

    @BeforeEach
    void setUp() throws Exception {
        Path romPath = Path.of(System.getProperty("s3k.rom.path", "s3k.gen"));
        assumeTrue(Files.isRegularFile(romPath), "s3k.gen ROM required");
        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();
        rom = new Rom();
        assertTrue(rom.open(romPath.toAbsolutePath().toString()));
        TestEnvironment.configureRomFixture(rom);
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
        if (rom != null) {
            rom.close();
        }
    }

    @Test
    void fastManifestCarriesTheOriginalHardwareFrameCosts() throws Exception {
        List<String> warnings = new ArrayList<>();
        LoadTimeProfile fast = new Sonic3kGameModule().createLoadTimeProfile(
                LoadTimeSimulationMode.FAST, warnings::add);
        HardwareTimingService timing = new HardwareTimingService(
                RomWorkBudgetScheduler.oneWorkUnitAt(HardwareServiceBoundary.POST_OBJECTS),
                fast);
        S3kKosDecompressionQueue queue = new S3kKosDecompressionQueue(timing);
        // serviceFrames count the PRE_MAIN_LOOP services from the iteration that
        // starts the decode, so the original capture's extra frames (8, 8, 6, 0
        // on frames 8, 9, A, B) are one less than the manifest values.
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("frame 7 ArtKos_S3TitleSonic8 (queued at loc_4040)", -1);
        expected.put("frame 8 ArtKos_S3TitleSonic9", 9);
        expected.put("frame 9 ArtKos_S3TitleSonicA", 9);
        expected.put("frame A ArtKos_S3TitleSonicB", 7);
        expected.put("frame B ArtKos_S3TitleSonicC", 1);
        int[] addresses = {
                Sonic3kConstants.ART_KOS_TITLE_SONIC8_ADDR,
                Sonic3kConstants.ART_KOS_TITLE_SONIC9_ADDR,
                Sonic3kConstants.ART_KOS_TITLE_SONIC_A_ADDR,
                Sonic3kConstants.ART_KOS_TITLE_SONIC_B_ADDR,
                Sonic3kConstants.ART_KOS_TITLE_SONIC_C_ADDR,
        };
        Map<String, Integer> measured = new LinkedHashMap<>();
        int index = 0;
        for (String name : expected.keySet()) {
            HardwareWorkHandle handle = queue.queueStandardKos(
                    rom, addresses[index++], S3kKosRamDestinations.RAM_START);
            // Direct Kosinski work prepares fully on its first PRE_MAIN_LOOP
            // service, so the services needed before readiness are exactly the
            // profile's serviceFrames.
            int services = 0;
            while (!queue.isReady(handle) && services < 64) {
                timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
                queue.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
                services++;
            }
            System.out.println("TITLE-FP " + name + " "
                    + handle.submissionFingerprint() + " -> " + services);
            measured.put(name, services);
            queue.claim(handle);
        }
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            if (entry.getValue() < 0) {
                // Frame 7's art is queued 24 iterations ahead and the ROM never
                // stalls on it (Process_Kos_Queue resumes across V-ints).
                assertTrue(measured.get(entry.getKey()) <= 6 * ROM_ITERATIONS_PER_FRAME,
                        entry.getKey());
                continue;
            }
            assertEquals(entry.getValue(), measured.get(entry.getKey()), entry.getKey());
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void fastStallsFramesEightToAForTheMeasuredCostAndNoneDoesNot() {
        Map<Integer, Integer> fast = animationFrameDurations(LoadTimeSimulationMode.FAST);
        Map<Integer, Integer> none = animationFrameDurations(LoadTimeSimulationMode.NONE);

        for (int frame = 2; frame <= 0xB; frame++) {
            assertEquals(ROM_ITERATIONS_PER_FRAME, none.get(frame), "NONE frame " + frame);
        }
        for (int frame = 2; frame <= 7; frame++) {
            assertEquals(ROM_ITERATIONS_PER_FRAME, fast.get(frame), "FAST frame " + frame);
        }
        assertEquals(ROM_ITERATIONS_PER_FRAME + 8, fast.get(8));
        assertEquals(ROM_ITERATIONS_PER_FRAME + 8, fast.get(9));
        assertEquals(ROM_ITERATIONS_PER_FRAME + 6, fast.get(0xA));
        assertEquals(ROM_ITERATIONS_PER_FRAME, fast.get(0xB));
    }

    private Map<Integer, Integer> animationFrameDurations(LoadTimeSimulationMode mode) {
        GameServices.configuration().setConfigValue(
                SonicConfiguration.LOAD_TIME_SIMULATION, mode.name());
        Sonic3kTitleScreenManager manager = new Sonic3kTitleScreenManager();
        manager.initialize();
        manager.enterSonicAnimationForTest();
        InputHandler input = new InputHandler();
        Map<Integer, Integer> durations = new LinkedHashMap<>();
        int frame = manager.currentAnimFrame();
        int updates = 0;
        while (manager.getState() != com.openggf.game.TitleScreenProvider.State.ACTIVE
                && updates < 600) {
            int before = manager.currentAnimFrame();
            manager.update(input);
            updates++;
            durations.merge(before, 1, Integer::sum);
            if (manager.currentAnimFrame() == 0xD) {
                break;
            }
            frame = manager.currentAnimFrame();
        }
        assertTrue(updates < 600, "animation must finish; last frame " + frame);
        return durations;
    }
}
