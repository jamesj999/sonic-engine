package com.openggf.game.sonic2.dataselect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.sonic2.Sonic2ZoneRegistry;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import com.openggf.version.AppVersion;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Generates Sonic 2 runtime-selected-slot preview PNGs for donated S3K Data Select.
 *
 * <p>The generator mirrors the Sonic 1 cache model but targets the 11 Sonic 2 restart
 * destinations. Capture currently defaults to spawn-left-edge framing with a reserved override map
 * for future tuning.</p>
 */
public final class S2DataSelectImageGenerator {
    static final int PREVIEW_WIDTH = 80;
    static final int PREVIEW_HEIGHT = 56;

    private static final Sonic2ZoneRegistry CAPTURE_ZONE_REGISTRY = new Sonic2ZoneRegistry();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<ZoneCaptureSpec> ZONES = List.of(
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_EHZ, "ehz", "ehz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_CPZ, "cpz", "cpz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_ARZ, "arz", "arz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_CNZ, "cnz", "cnz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_HTZ, "htz", "htz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_MCZ, "mcz", "mcz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_OOZ, "ooz", "ooz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_MTZ, "mtz", "mtz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_SCZ, "scz", "scz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_WFZ, "wfz", "wfz.png"),
            new ZoneCaptureSpec(Sonic2ZoneConstants.ZONE_DEZ, "dez", "dez.png")
    );
    private static final Map<Integer, PreviewCapturePoint> CAPTURE_OVERRIDES = buildCaptureOverrides();

    private final Path cacheRoot;
    private final CaptureSource captureSource;
    private final Supplier<String> romSha256Supplier;
    private final Sonic2ZoneRegistry zoneRegistry = CAPTURE_ZONE_REGISTRY;

    public S2DataSelectImageGenerator(Path cacheRoot,
                                      CaptureSource captureSource,
                                      Supplier<String> romSha256Supplier) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.captureSource = Objects.requireNonNull(captureSource, "captureSource");
        this.romSha256Supplier = Objects.requireNonNull(romSha256Supplier, "romSha256Supplier");
    }

    public void generateAll() throws IOException {
        Files.createDirectories(cacheRoot);

        Map<String, String> zoneFiles = new LinkedHashMap<>();
        for (ZoneCaptureSpec spec : ZONES) {
            PreviewCaptureTarget captureTarget = resolveCaptureTarget(spec.zoneId());
            RgbaImage capture = captureSource.capture(spec.zoneId(), captureTarget);
            RgbaImage preview = scaleToPreview(capture);

            Path tempPng = Files.createTempFile(cacheRoot, spec.fileStem() + "-", ".tmp");
            Path finalPng = cacheRoot.resolve(spec.fileName());
            try {
                ScreenshotCapture.savePNG(preview, tempPng);
                moveAtomically(tempPng, finalPng);
            } finally {
                Files.deleteIfExists(tempPng);
            }
            zoneFiles.put(spec.zoneKey(), spec.fileName());
        }

        writeManifest(zoneFiles);
    }

    public PreviewCaptureTarget resolveCaptureTarget(int zoneId) {
        PreviewCapturePoint override = CAPTURE_OVERRIDES.get(zoneId);
        if (override != null) {
            return new PreviewCaptureTarget(override.cameraLeftX(), override.centreY());
        }
        int[] spawn = zoneRegistry.getStartPosition(zoneId, 0);
        return new PreviewCaptureTarget(spawn[0], spawn[1]);
    }

    static List<Integer> supportedZoneIds() {
        return ZONES.stream().map(ZoneCaptureSpec::zoneId).toList();
    }

    static String zoneKeyForZoneId(int zoneId) {
        for (ZoneCaptureSpec spec : ZONES) {
            if (spec.zoneId() == zoneId) {
                return spec.zoneKey();
            }
        }
        return null;
    }

    private void writeManifest(Map<String, String> zoneFiles) throws IOException {
        Path tempManifest = Files.createTempFile(cacheRoot, "manifest-", ".tmp");
        Path finalManifest = cacheRoot.resolve("manifest.json");
        try {
            S2DataSelectImageManifest manifest = new S2DataSelectImageManifest(
                    AppVersion.get(),
                    S2DataSelectImageCacheManager.GENERATOR_FORMAT_VERSION,
                    romSha256Supplier.get(),
                    Instant.now().toString(),
                    zoneFiles);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tempManifest.toFile(), manifest);
            moveAtomically(tempManifest, finalManifest);
        } finally {
            Files.deleteIfExists(tempManifest);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static RgbaImage scaleToPreview(RgbaImage source) {
        if (source.width() == PREVIEW_WIDTH && source.height() == PREVIEW_HEIGHT) {
            return source.copy();
        }
        int[] pixels = new int[PREVIEW_WIDTH * PREVIEW_HEIGHT];
        for (int y = 0; y < PREVIEW_HEIGHT; y++) {
            int sourceY = Math.min(source.height() - 1, y * source.height() / PREVIEW_HEIGHT);
            for (int x = 0; x < PREVIEW_WIDTH; x++) {
                int sourceX = Math.min(source.width() - 1, x * source.width() / PREVIEW_WIDTH);
                pixels[y * PREVIEW_WIDTH + x] = source.argb(sourceX, sourceY);
            }
        }
        return new RgbaImage(PREVIEW_WIDTH, PREVIEW_HEIGHT, pixels);
    }

    /**
     * Render-thread capture contract used by the cache manager to keep OpenGL work on the active
     * graphics context while leaving file I/O and manifest orchestration outside the renderer.
     */
    public interface CaptureSource {
        RgbaImage capture(int zoneId, PreviewCaptureTarget captureTarget) throws IOException;
    }

    /**
     * Code-owned override point for a zone preview, expressed as camera-left X plus centre Y.
     */
    public record PreviewCapturePoint(int cameraLeftX, int centreY) {
    }

    /**
     * Concrete render target passed to the capture path, expressed as camera-left X plus centre Y.
     */
    public record PreviewCaptureTarget(int cameraLeftX, int centreY) {
    }

    private record ZoneCaptureSpec(int zoneId, String zoneKey, String fileName) {
        private String fileStem() {
            int dot = fileName.indexOf('.');
            return dot == -1 ? fileName : fileName.substring(0, dot);
        }
    }

    private static Map<Integer, PreviewCapturePoint> buildCaptureOverrides() {
        return Map.of(
                Sonic2ZoneConstants.ZONE_EHZ, new PreviewCapturePoint(6150, 654),
                Sonic2ZoneConstants.ZONE_CPZ, new PreviewCapturePoint(955, 540),
                Sonic2ZoneConstants.ZONE_ARZ, new PreviewCapturePoint(7499, 973),
                Sonic2ZoneConstants.ZONE_CNZ, new PreviewCapturePoint(1766, 1678),
                Sonic2ZoneConstants.ZONE_HTZ, new PreviewCapturePoint(355, 914),
                Sonic2ZoneConstants.ZONE_MCZ, new PreviewCapturePoint(686, 1567),
                Sonic2ZoneConstants.ZONE_MTZ, new PreviewCapturePoint(4932, 715),
                Sonic2ZoneConstants.ZONE_WFZ, new PreviewCapturePoint(1501, 961)
        );
    }
}
