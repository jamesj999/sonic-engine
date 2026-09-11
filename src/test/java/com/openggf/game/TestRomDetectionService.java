package com.openggf.game;

import com.openggf.data.Rom;
import com.openggf.tests.TestTempFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRomDetectionService {

    @Test
    void lowerPriorityDetectorWinsWhenMultipleDetectorsMatch() throws Exception {
        GameModule lowerPriorityModule = module("lower-priority");
        GameModule higherPriorityModule = module("higher-priority");
        RomDetectionService service = new RomDetectionService(List.of(
                detector(100, true, higherPriorityModule),
                detector(10, true, lowerPriorityModule)));

        try (Rom rom = openRom()) {
            assertSame(lowerPriorityModule, service.detectAndCreateModule(rom).orElseThrow());
        }
    }

    @Test
    void equalPriorityDetectorsRetainRegistrationOrder() throws Exception {
        GameModule firstRegisteredModule = module("first-registered");
        GameModule secondRegisteredModule = module("second-registered");
        RomDetectionService service = new RomDetectionService(List.of(
                detector(20, true, firstRegisteredModule),
                detector(20, true, secondRegisteredModule)));

        try (Rom rom = openRom()) {
            assertSame(firstRegisteredModule, service.detectAndCreateModule(rom).orElseThrow());
        }
    }

    @Test
    void firstMatchingDetectorWinsOverLaterMatches() throws Exception {
        GameModule firstMatchingModule = module("first-match");
        GameModule laterMatchingModule = module("later-match");
        RomDetectionService service = new RomDetectionService(List.of(
                detector(10, false, module("non-match")),
                detector(20, true, firstMatchingModule),
                detector(30, true, laterMatchingModule)));

        try (Rom rom = openRom()) {
            assertSame(firstMatchingModule, service.detectAndCreateModule(rom).orElseThrow());
        }
    }

    @Test
    void throwingDetectorIsSkippedForTheNextMatchingDetector() throws Exception {
        GameModule matchingModule = module("post-throwing-match");
        RomDetectionService service = new RomDetectionService(List.of(
                throwingDetector(10),
                detector(20, true, matchingModule)));

        try (Rom rom = openRom()) {
            assertSame(matchingModule, service.detectAndCreateModule(rom).orElseThrow());
        }
    }

    @Test
    void unregisteringADetectorRemovesItFromDetection() throws Exception {
        GameModule matchingModule = module("unregistered-match");
        RomDetector detector = detector(10, true, matchingModule);
        RomDetectionService service = new RomDetectionService(List.of(detector));
        service.unregisterDetector(detector);

        try (Rom rom = openRom()) {
            assertTrue(service.detectAndCreateModule(rom).isEmpty());
        }
    }

    @Test
    void registeredDetectorSnapshotCannotBeMutated() {
        RomDetectionService service = new RomDetectionService(List.of(
                detector(10, true, module("registered"))));

        List<RomDetector> snapshot = service.getRegisteredDetectors();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(detector(20, true, module("added"))));
        assertEquals(1, service.getRegisteredDetectors().size());
    }

    @Test
    void nullAndClosedRomsReturnNoModule() {
        RomDetectionService service = new RomDetectionService(List.of(
                detector(10, true, module("null-and-closed"))));

        assertTrue(service.detectAndCreateModule(null).isEmpty());
        assertTrue(service.detectAndCreateModule(new Rom()).isEmpty());
    }

    private static RomDetector detector(int priority, boolean matches, GameModule module) {
        return new TestRomDetector(priority, matches, module, false);
    }

    private static RomDetector throwingDetector(int priority) {
        return new TestRomDetector(priority, false, module("throwing"), true);
    }

    private static GameModule module(String identifier) {
        return new TestGameModule(identifier);
    }

    private static Rom openRom() throws IOException {
        Path path = TestTempFiles.createTempFile("rom-detection-service", ".gen");
        Files.write(path, new byte[] {0});

        Rom rom = new Rom();
        assertTrue(rom.open(path.toString()));
        return rom;
    }

    private record TestRomDetector(int priority, boolean matches, GameModule module,
                                   boolean throwsOnDetection) implements RomDetector {
        @Override
        public boolean canHandle(Rom rom) {
            if (throwsOnDetection) {
                throw new IllegalStateException("detector failure");
            }
            return matches;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public GameModule createModule() {
            return module;
        }

        @Override
        public String getGameName() {
            return "test-" + priority;
        }
    }

    private record TestGameModule(String identifier) implements GameModule {
        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public com.openggf.data.Game createGame(Rom rom) {
            return null;
        }

        @Override
        public com.openggf.level.objects.ObjectRegistry createObjectRegistry() {
            return null;
        }

        @Override
        public com.openggf.audio.GameAudioProfile getAudioProfile() {
            return null;
        }

        @Override
        public com.openggf.level.objects.TouchResponseTable createTouchResponseTable(
                com.openggf.data.RomByteReader romReader) {
            return null;
        }

        @Override
        public int getPlaneSwitcherObjectId() {
            return 0;
        }

        @Override
        public com.openggf.level.objects.PlaneSwitcherConfig getPlaneSwitcherConfig() {
            return null;
        }

        @Override
        public LevelEventProvider getLevelEventProvider() {
            return null;
        }

        @Override
        public RespawnState createRespawnState() {
            return null;
        }

        @Override
        public LevelState createLevelState() {
            return null;
        }

        @Override
        public ZoneRegistry getZoneRegistry() {
            return null;
        }

        @Override
        public ScrollHandlerProvider getScrollHandlerProvider() {
            return null;
        }

        @Override
        public ZoneFeatureProvider getZoneFeatureProvider() {
            return null;
        }

        @Override
        public DebugOverlayProvider getDebugOverlayProvider() {
            return null;
        }

        @Override
        public ObjectArtProvider getObjectArtProvider() {
            return null;
        }

        @Override
        public PhysicsProvider getPhysicsProvider() {
            return null;
        }

        @Override
        public GameId getGameId() {
            return GameId.S1;
        }
    }
}
