package com.openggf.game.sonic2.dataselect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.sonic2.Sonic2ZoneRegistry;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.RgbaImage;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2DataSelectImageCacheManager {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SonicConfigurationService config = SonicConfigurationService.getInstance();

    @AfterEach
    void resetConfig() {
        config.resetToDefaults();
    }

    @Test
    void manifestContractSerializesExpectedFields() {
        S2DataSelectImageManifest manifest = new S2DataSelectImageManifest(
                AppVersion.get(),
                S2DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION,
                "0123456789abcdef",
                "2026-04-15T12:34:56Z",
                Map.of("ehz", "ehz.png"));

        JsonNode json = mapper.valueToTree(manifest);

        assertEquals(AppVersion.get(), json.path("engineVersion").asText());
        assertEquals(S2DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION, json.path("generatorFormatVersion").asInt());
        assertEquals("0123456789abcdef", json.path("romSha256").asText());
        assertEquals("2026-04-15T12:34:56Z", json.path("generatedAt").asText());
        assertTrue(json.path("settleFrames").isMissingNode());
        assertEquals("ehz.png", json.path("zones").path("ehz").asText());
    }

    @Test
    void cacheInvalidWhenOverrideEnabled() throws Exception {
        writeManifest("sha-override", Map.of("ehz", writeZonePng("ehz.png")));
        config.setConfigValue(SonicConfiguration.CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE, true);

        S2DataSelectImageCacheManager manager = new S2DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-override",
                mapper);

        assertFalse(manager.cacheValid());
    }

    @Test
    void cacheValidWhenManifestMatchesAndAllZonePngsDecode() throws Exception {
        Map<String, String> zones = writeZoneSet();
        writeManifest("sha-valid", zones);

        S2DataSelectImageCacheManager manager = new S2DataSelectImageCacheManager(
                tempDir,
                config,
                () -> "sha-valid",
                mapper);

        assertTrue(manager.cacheValid());
    }

    @Test
    void failedAsyncGenerationIsLoggedRetainedAndNonFatal() throws Exception {
        Path cacheRoot = tempDir.resolve("cache-root-is-file");
        Files.writeString(cacheRoot, "not a directory");
        Logger logger = Logger.getLogger(S2DataSelectImageCacheManager.class.getName());
        LogCaptureHandler handler = new LogCaptureHandler();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        Level previousLevel = logger.getLevel();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);

        try (var donor = org.mockito.Mockito.mockStatic(CrossGameFeatureProvider.class)) {
            donor.when(CrossGameFeatureProvider::isS3kDonorActive).thenReturn(true);
            S2DataSelectImageCacheManager manager = new S2DataSelectImageCacheManager(
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
    void captureTargetFallsBackToSpawnLeftEdgeForZonesWithoutOverrides() throws Exception {
        Path cacheRoot = tempDir.resolve("saves/image-cache/s2");
        FakeCaptureSource captureSource = FakeCaptureSource.solidColourImages(320, 224, 11);
        S2DataSelectImageGenerator generator = new S2DataSelectImageGenerator(
                cacheRoot,
                captureSource,
                () -> "romsha");

        generator.generateAll();

        S2DataSelectImageGenerator.PreviewCaptureTarget point = captureSource.capturePoints().get(6);
        int[] spawn = new Sonic2ZoneRegistry().getStartPosition(Sonic2ZoneConstants.ZONE_OOZ, 0);
        assertEquals(spawn[0], point.cameraLeftX());
        assertEquals(spawn[1], point.centreY());
    }

    @Test
    void captureTargetUsesConfiguredOverrideWhenPresent() {
        Path cacheRoot = tempDir.resolve("saves/image-cache/s2");
        S2DataSelectImageGenerator generator = new S2DataSelectImageGenerator(
                cacheRoot,
                FakeCaptureSource.solidColourImages(320, 224, 11),
                () -> "romsha");

        S2DataSelectImageGenerator.PreviewCaptureTarget point =
                generator.resolveCaptureTarget(Sonic2ZoneConstants.ZONE_EHZ);

        assertEquals(6150, point.cameraLeftX());
        assertEquals(654, point.centreY());
    }

    @Test
    void failedZoneLeavesNoManifestBehind() {
        Path cacheRoot = tempDir.resolve("saves/image-cache/s2");
        FakeCaptureSource captureSource = FakeCaptureSource.failOnZone(Sonic2ZoneConstants.ZONE_ARZ);
        S2DataSelectImageGenerator generator = new S2DataSelectImageGenerator(
                cacheRoot,
                captureSource,
                () -> "romsha");

        assertThrows(IOException.class, generator::generateAll);
        assertFalse(Files.exists(cacheRoot.resolve("manifest.json")));
    }

    private void writeManifest(String romSha256, Map<String, String> zones) throws IOException {
        S2DataSelectImageManifest manifest = new S2DataSelectImageManifest(
                AppVersion.get(),
                S2DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION,
                romSha256,
                "2026-04-15T12:34:56Z",
                zones);
        mapper.writerWithDefaultPrettyPrinter().writeValue(tempDir.resolve("manifest.json").toFile(), manifest);
    }

    private String writeZonePng(String fileName) throws IOException {
        Path file = tempDir.resolve(fileName);
        BufferedImage image = new BufferedImage(
                S2DataSelectImageGenerator.PREVIEW_WIDTH,
                S2DataSelectImageGenerator.PREVIEW_HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", file.toFile());
        return file.getFileName().toString();
    }

    private Map<String, String> writeZoneSet() throws IOException {
        Map<String, String> zones = new TreeMap<>();
        zones.put("ehz", writeZonePng("ehz.png"));
        zones.put("cpz", writeZonePng("cpz.png"));
        zones.put("arz", writeZonePng("arz.png"));
        zones.put("cnz", writeZonePng("cnz.png"));
        zones.put("htz", writeZonePng("htz.png"));
        zones.put("mcz", writeZonePng("mcz.png"));
        zones.put("ooz", writeZonePng("ooz.png"));
        zones.put("mtz", writeZonePng("mtz.png"));
        zones.put("scz", writeZonePng("scz.png"));
        zones.put("wfz", writeZonePng("wfz.png"));
        zones.put("dez", writeZonePng("dez.png"));
        return zones;
    }

    private static final class FakeCaptureSource implements S2DataSelectImageGenerator.CaptureSource {
        private final Map<Integer, RgbaImage> images;
        private final int failZone;
        private final List<S2DataSelectImageGenerator.PreviewCaptureTarget> capturePoints = new ArrayList<>();

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

        List<S2DataSelectImageGenerator.PreviewCaptureTarget> capturePoints() {
            return capturePoints;
        }

        @Override
        public RgbaImage capture(int zoneId, S2DataSelectImageGenerator.PreviewCaptureTarget capturePoint)
                throws IOException {
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
