package com.openggf.game.sonic1.dataselect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.Sonic1ZoneRegistry;
import com.openggf.game.sonic1.scroll.Sonic1ZoneConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import com.openggf.tests.LogCaptureHandler;
import com.openggf.version.AppVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class TestS1DataSelectImageCacheManager {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SonicConfigurationService config = SonicConfigurationService.getInstance();

    @AfterEach
    void resetConfig() {
        config.resetToDefaults();
    }

    @Test
    void manifestContractSerializesExpectedFields() throws Exception {
        S1DataSelectImageManifest manifest = new S1DataSelectImageManifest(
                AppVersion.get(),
                S1DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION,
                "0123456789abcdef",
                "2026-04-14T12:34:56Z",
                Map.of("ghz", "ghz.png"));

        JsonNode json = mapper.valueToTree(manifest);

        assertEquals(AppVersion.get(), json.path("engineVersion").asText());
        assertEquals(S1DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION, json.path("generatorFormatVersion").asInt());
        assertEquals("0123456789abcdef", json.path("romSha256").asText());
        assertEquals("2026-04-14T12:34:56Z", json.path("generatedAt").asText());
        assertTrue(json.path("settleFrames").isMissingNode());
        assertEquals("ghz.png", json.path("zones").path("ghz").asText());
    }

    @Test
    void cacheInvalidWhenOverrideEnabled() throws Exception {
        writeManifest("sha-override", Map.of("ghz", writeZonePng("ghz.png")));
        config.setConfigValue(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE, true);

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-override",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheInvalidWhenManifestIsMissing() {
        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-missing",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheValidWhenManifestMatchesAndAllZonePngsDecode() throws Exception {
        Map<String, String> zones = writeZoneSet();
        writeManifest("sha-valid", zones);

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-valid",
                mapper);

        assertTrue(manager.cacheValid());
    }

    @Test
    void cacheInvalidWhenManifestEngineVersionDoesNotMatchAppVersion() throws Exception {
        writeManifest("sha-version", Map.of("ghz", writeZonePng("ghz.png")), "0.0.0-test");

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-version",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheInvalidWhenGeneratorFormatVersionDoesNotMatch() throws Exception {
        writeManifest("sha-format", writeZoneSet(), AppVersion.get(),
                S1DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION + 1);

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-format",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheInvalidWhenRomShaDoesNotMatchManifest() throws Exception {
        writeManifest("manifest-sha", Map.of("ghz", writeZonePng("ghz.png")));

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "different-sha",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheInvalidWhenManifestZonesAreEmpty() throws Exception {
        writeManifest("sha-empty", Map.of());

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-empty",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheInvalidWhenManifestZonesArePartial() throws Exception {
        Map<String, String> zones = new TreeMap<>();
        zones.put("ghz", writeZonePng("ghz.png"));
        writeManifest("sha-partial", zones);

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-partial",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheInvalidWhenZoneImageIsNotPng() throws Exception {
        Map<String, String> zones = writeZoneSet();
        Files.writeString(tempDir.resolve(zones.get("ghz")), "not a png");
        writeManifest("sha-corrupt", zones);

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-corrupt",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheInvalidWhenZoneImageHasWrongDimensions() throws Exception {
        Map<String, String> zones = writeZoneSet();
        writeZonePng(tempDir.resolve(zones.get("ghz")).getFileName().toString(), 1, 1);
        writeManifest("sha-dimensions", zones);

        S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-dimensions",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void constructorDoesNotEagerlyStartGeneration() throws Exception {
        java.util.concurrent.CompletableFuture<RgbaImage> blocked = new java.util.concurrent.CompletableFuture<>();
        try (var donor = org.mockito.Mockito.mockStatic(CrossGameFeatureProvider.class);
             var graphics = org.mockito.Mockito.mockStatic(GraphicsManager.class)) {
            donor.when(CrossGameFeatureProvider::isS3kDonorActive).thenReturn(true);
            GraphicsManager graphicsManager = org.mockito.Mockito.mock(GraphicsManager.class);
            graphics.when(GraphicsManager::getInstance).thenReturn(graphicsManager);
            org.mockito.Mockito.when(graphicsManager.submitRenderThreadTask(org.mockito.ArgumentMatchers.any()))
                    .thenReturn((CompletableFuture) blocked);

            S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                    tempDir,
                    config,
                    () -> "constructor-sha",
                    mapper);

            assertNull(readInFlight(manager));
            blocked.complete(new RgbaImage(1, 1, new int[] {0xFFFFFFFF}));
            manager.awaitGenerationIfRunning();
        }
    }

    @Test
    void failedAsyncGenerationIsLoggedRetainedAndNonFatal() throws Exception {
        Path cacheRoot = tempDir.resolve("cache-root-is-file");
        Files.writeString(cacheRoot, "not a directory");
        Logger logger = Logger.getLogger(S1DataSelectImageCacheManager.class.getName());
        LogCaptureHandler handler = new LogCaptureHandler();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        Level previousLevel = logger.getLevel();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);

        try (var donor = org.mockito.Mockito.mockStatic(CrossGameFeatureProvider.class)) {
            donor.when(CrossGameFeatureProvider::isS3kDonorActive).thenReturn(true);
            S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                    cacheRoot,
                    config,
                    () -> "async-failure-sha",
                    mapper);

            assertDoesNotThrow(() -> {
                manager.ensureGenerationStarted();
                manager.awaitGenerationIfRunning();
            });

            assertFalse(manager.isGenerationRunning());
            assertFalse(manager.cacheValid());
            assertNotNull(manager.getLastGenerationFailure());
            assertTrue(handler.countAtOrAbove(Level.WARNING) >= 1,
                    "Async generation failure should be visible in diagnostics");
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousUseParentHandlers);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void captureFramebufferUsesResolvedRequest() throws Exception {
        Camera camera = org.mockito.Mockito.mock(Camera.class);
        com.openggf.level.LevelManager levelManager = org.mockito.Mockito.mock(com.openggf.level.LevelManager.class);
        com.openggf.level.objects.ObjectManager objectManager = org.mockito.Mockito.mock(com.openggf.level.objects.ObjectManager.class);
        RgbaImage captured = new RgbaImage(1, 1, new int[] {0xFF112233});

        try (var cameraService = org.mockito.Mockito.mockStatic(GameServices.class);
             var engineServicesStatic = org.mockito.Mockito.mockStatic(EngineServices.class);
             var screenshot = org.mockito.Mockito.mockStatic(ScreenshotCapture.class)) {
            cameraService.when(GameServices::camera).thenReturn(camera);
            cameraService.when(GameServices::level).thenReturn(levelManager);
            org.mockito.Mockito.when(levelManager.getObjectManager()).thenReturn(objectManager);
            org.mockito.Mockito.when(camera.getX()).thenReturn((short) 0x180);
            GraphicsManager graphicsManager = org.mockito.Mockito.mock(GraphicsManager.class);
            EngineContext engineServices = org.mockito.Mockito.mock(EngineContext.class);
            engineServicesStatic.when(EngineServices::current).thenReturn(engineServices);
            org.mockito.Mockito.when(engineServices.graphics()).thenReturn(graphicsManager);
            org.mockito.Mockito.when(graphicsManager.submitRenderThreadTask(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        java.util.concurrent.Callable<RgbaImage> callable = invocation.getArgument(0);
                        return (CompletableFuture) java.util.concurrent.CompletableFuture.completedFuture(callable.call());
                    });
            org.mockito.Mockito.when(graphicsManager.getViewportX()).thenReturn(10);
            org.mockito.Mockito.when(graphicsManager.getViewportY()).thenReturn(20);
            org.mockito.Mockito.when(graphicsManager.getViewportWidth()).thenReturn(640);
            org.mockito.Mockito.when(graphicsManager.getViewportHeight()).thenReturn(448);
            screenshot.when(() -> ScreenshotCapture.captureFramebufferRegion(10, 20, 640, 448)).thenReturn(captured);

            S1DataSelectImageCacheManager manager = new S1DataSelectImageCacheManager(
                    tempDir,
                    config,
                    () -> "capture-sha",
                    mapper);
            var method = S1DataSelectImageCacheManager.class.getDeclaredMethod(
                    "captureFramebuffer",
                    int.class,
                    S1DataSelectImageGenerator.PreviewCaptureTarget.class);
            method.setAccessible(true);

            S1DataSelectImageGenerator.PreviewCaptureTarget point =
                    new S1DataSelectImageGenerator.PreviewCaptureTarget(0x180, 0x100);
            RgbaImage result = (RgbaImage) method.invoke(manager, Sonic1ZoneConstants.ZONE_GHZ, point);

            assertSame(captured, result);
            org.mockito.Mockito.verify(levelManager).loadZoneAndAct(
                    Sonic1ZoneConstants.ZONE_GHZ,
                    0,
                    com.openggf.game.LevelLoadMode.PREVIEW_CAPTURE);
            org.mockito.Mockito.verify(camera).setX((short) 0x180);
            org.mockito.Mockito.verify(camera).setY((short) (0x100 - 96));
            org.mockito.Mockito.verify(levelManager).recomputeParallaxAfterRewindRestore();
            org.mockito.Mockito.verify(objectManager).postCameraPlacementUpdate(0x180);
            org.mockito.Mockito.verify(levelManager).drawWithRenderOptions(
                    null,
                    com.openggf.level.LevelManager.LevelRenderOptions.previewCapture());
            org.mockito.Mockito.verify(graphicsManager).flush();
            screenshot.verify(() -> ScreenshotCapture.captureFramebufferRegion(10, 20, 640, 448));
            org.mockito.Mockito.verify(camera, org.mockito.Mockito.never()).updatePosition(true);
        }
    }

    @Test
    void generateAllWritesSevenPngsAndManifestAfterSuccess() throws Exception {
        Path cacheRoot = tempDir.resolve("saves/image-cache/s1");
        FakeCaptureSource captureSource = FakeCaptureSource.solidColourImages(320, 224, 7);
        S1DataSelectImageGenerator generator = new S1DataSelectImageGenerator(
                cacheRoot,
                captureSource,
                () -> "romsha");

        generator.generateAll();

        assertTrue(Files.isRegularFile(cacheRoot.resolve("manifest.json")));
        try (var stream = Files.list(cacheRoot)) {
            assertEquals(7, stream.filter(path -> path.toString().endsWith(".png")).count());
        }
        assertEquals(List.of(
                Sonic1ZoneConstants.ZONE_GHZ,
                Sonic1ZoneConstants.ZONE_MZ,
                Sonic1ZoneConstants.ZONE_SYZ,
                Sonic1ZoneConstants.ZONE_LZ,
                Sonic1ZoneConstants.ZONE_SLZ,
                Sonic1ZoneConstants.ZONE_SBZ,
                Sonic1ZoneConstants.ZONE_FZ
        ), captureSource.capturedZones());
    }

    @Test
    void captureTargetFallsBackToSpawnLeftEdgeForZonesWithoutOverrides() throws Exception {
        Path cacheRoot = tempDir.resolve("saves/image-cache/s1");
        FakeCaptureSource captureSource = FakeCaptureSource.solidColourImages(320, 224, 7);
        S1DataSelectImageGenerator generator = new S1DataSelectImageGenerator(
                cacheRoot,
                captureSource,
                () -> "romsha");

        generator.generateAll();

        assertFalse(captureSource.capturePoints().isEmpty());
        S1DataSelectImageGenerator.PreviewCaptureTarget point = captureSource.capturePoints().get(1);
        int[] spawn = new Sonic1ZoneRegistry().getStartPosition(Sonic1ZoneConstants.ZONE_MZ, 0);
        assertEquals(spawn[0], point.cameraLeftX());
        assertEquals(spawn[1], point.centreY());
    }

    @Test
    void captureTargetUsesHardcodedOverrideWhenZoneHasOne() {
        S1DataSelectImageGenerator generator = new S1DataSelectImageGenerator(
                tempDir.resolve("saves/image-cache/s1"),
                FakeCaptureSource.solidColourImages(320, 224, 7),
                () -> "romsha");

        assertEquals(
                new S1DataSelectImageGenerator.PreviewCaptureTarget(8232, 798),
                generator.resolveCaptureTarget(Sonic1ZoneConstants.ZONE_GHZ));
        assertEquals(
                new S1DataSelectImageGenerator.PreviewCaptureTarget(1157, 1086),
                generator.resolveCaptureTarget(Sonic1ZoneConstants.ZONE_SBZ));
        assertEquals(
                new S1DataSelectImageGenerator.PreviewCaptureTarget(9312, 1392),
                generator.resolveCaptureTarget(Sonic1ZoneConstants.ZONE_FZ));
    }

    @Test
    void failedZoneLeavesNoManifestBehind() throws Exception {
        Path cacheRoot = tempDir.resolve("saves/image-cache/s1");
        FakeCaptureSource captureSource = FakeCaptureSource.failOnZone(Sonic1ZoneConstants.ZONE_SYZ);
        S1DataSelectImageGenerator generator = new S1DataSelectImageGenerator(
                cacheRoot,
                captureSource,
                () -> "romsha");

        assertThrows(IOException.class, generator::generateAll);
        assertFalse(Files.exists(cacheRoot.resolve("manifest.json")));
    }

    private CompletableFuture<Void> readInFlight(S1DataSelectImageCacheManager manager) throws Exception {
        var field = S1DataSelectImageCacheManager.class.getDeclaredField("inFlight");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void> future = (CompletableFuture<Void>) field.get(manager);
        return future;
    }

    private void writeManifest(String romSha256, Map<String, String> zones) throws IOException {
        writeManifest(romSha256, zones, AppVersion.get(), S1DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION);
    }

    private void writeManifest(String romSha256, Map<String, String> zones, String engineVersion) throws IOException {
        writeManifest(romSha256, zones, engineVersion, S1DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION);
    }

    private void writeManifest(String romSha256, Map<String, String> zones, String engineVersion,
            int generatorFormatVersion) throws IOException {
        S1DataSelectImageManifest manifest = new S1DataSelectImageManifest(
                engineVersion,
                generatorFormatVersion,
                romSha256,
                "2026-04-14T12:34:56Z",
                zones);
        mapper.writerWithDefaultPrettyPrinter().writeValue(tempDir.resolve("manifest.json").toFile(), manifest);
    }

    private String writeZonePng(String fileName) throws IOException {
        return writeZonePng(fileName, S1DataSelectImageGenerator.PREVIEW_WIDTH, S1DataSelectImageGenerator.PREVIEW_HEIGHT);
    }

    private String writeZonePng(String fileName, int width, int height) throws IOException {
        Path file = tempDir.resolve(fileName);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", file.toFile());
        return file.getFileName().toString();
    }

    private Map<String, String> writeZoneSet() throws IOException {
        Map<String, String> zones = new TreeMap<>();
        zones.put("ghz", writeZonePng("ghz.png"));
        zones.put("mz", writeZonePng("mz.png"));
        zones.put("syz", writeZonePng("syz.png"));
        zones.put("lz", writeZonePng("lz.png"));
        zones.put("slz", writeZonePng("slz.png"));
        zones.put("sbz", writeZonePng("sbz.png"));
        zones.put("fz", writeZonePng("fz.png"));
        return zones;
    }

    private static final class FakeCaptureSource implements S1DataSelectImageGenerator.CaptureSource {
        private final Map<Integer, RgbaImage> images;
        private final int failZone;
        private final List<Integer> capturedZones = new ArrayList<>();
        private final List<S1DataSelectImageGenerator.PreviewCaptureTarget> capturePoints = new ArrayList<>();

        private FakeCaptureSource(Map<Integer, RgbaImage> images, int failZone) {
            this.images = images;
            this.failZone = failZone;
        }

        static FakeCaptureSource solidColourImages(int width, int height, int count) {
            Map<Integer, RgbaImage> images = new TreeMap<>();
            for (int zone = 0; zone < count; zone++) {
                images.put(zone, solidImage(width, height, 0xFF000000 | (zone * 0x00202020)));
            }
            return new FakeCaptureSource(images, -1);
        }

        static FakeCaptureSource failOnZone(int zoneId) {
            return new FakeCaptureSource(Map.of(), zoneId);
        }

        List<Integer> capturedZones() {
            return capturedZones;
        }

        List<S1DataSelectImageGenerator.PreviewCaptureTarget> capturePoints() {
            return capturePoints;
        }

        @Override
        public RgbaImage capture(int zoneId, S1DataSelectImageGenerator.PreviewCaptureTarget capturePoint)
                throws IOException {
            capturedZones.add(zoneId);
            capturePoints.add(capturePoint);
            if (zoneId == failZone) {
                throw new IOException("boom");
            }
            RgbaImage image = images.get(zoneId);
            if (image == null) {
                throw new IOException("Missing fake capture for zone " + zoneId);
            }
            return image.copy();
        }

        private static RgbaImage solidImage(int width, int height, int argb) {
            int[] pixels = new int[width * height];
            java.util.Arrays.fill(pixels, argb);
            return new RgbaImage(width, height, pixels);
        }
    }
}
