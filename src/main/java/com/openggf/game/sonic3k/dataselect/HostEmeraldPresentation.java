package com.openggf.game.sonic3k.dataselect;

import com.openggf.data.Rom;

import java.io.IOException;

/**
 * Adapts host-game emerald presentation into the palette and layout contract expected by the
 * donated S3K Data Select frontend.
 *
 * <p>The selected-slot screenshots are host-owned, but the emerald layer still renders with S3K
 * save-screen mappings. This class therefore decides which native S3K palette bytes can stay
 * untouched, which host-specific palettes need to be supplied separately, and which layout profile
 * the renderer should use for the current host game.</p>
 */
public final class HostEmeraldPresentation {
    private HostEmeraldPresentation() {
    }

    /**
     * Resolves the emerald presentation contract for the active host game.
     */
    public static Result forHost(String hostGameCode, Rom hostRom) {
        return forHost(hostGameCode, hostRom, null);
    }

    /**
     * Resolves the emerald presentation contract for the active host game, optionally seeding the
     * mapping from the real S3K donor emerald palette bytes.
     */
    public static Result forHost(String hostGameCode, Rom hostRom, byte[] frontendEmeraldPaletteBytes) {
        if (hostRom == null || hostGameCode == null || hostGameCode.isBlank()) {
            return Result.fallback();
        }
        try {
            return switch (hostGameCode) {
                case "s1" -> buildS1(frontendEmeraldPaletteBytes);
                case "s2" -> buildS2(frontendEmeraldPaletteBytes);
                default -> Result.fallback();
            };
        } catch (IOException | IllegalArgumentException e) {
            return Result.fallback();
        }
    }

    private static Result buildS1(byte[] frontendEmeraldPaletteBytes) throws IOException {
        var nativeRamp = HostEmeraldPaletteBuilder.nativeRampFromS3kPaletteBytes(frontendEmeraldPaletteBytes);
        return new Result(
                HostEmeraldPaletteBuilder.composeNativePaletteBytes(nativeRamp),
                new byte[0],
                HostEmeraldLayoutProfile.s1SixRing(),
                6,
                false);
    }

    private static Result buildS2(byte[] frontendEmeraldPaletteBytes) throws IOException {
        var nativeRamp = HostEmeraldPaletteBuilder.nativeRampFromS3kPaletteBytes(frontendEmeraldPaletteBytes);
        return new Result(
                HostEmeraldPaletteBuilder.composeNativePaletteBytes(nativeRamp),
                HostEmeraldPaletteBuilder.composeS2PurplePaletteBytes(nativeRamp),
                HostEmeraldLayoutProfile.defaultSeven(),
                7,
                false);
    }

    /**
     * Immutable renderer-facing emerald presentation bundle.
     */
    public record Result(byte[] paletteBytes,
                         byte[] customPurplePaletteBytes,
                         HostEmeraldLayoutProfile layout,
                         int activeEmeraldCount,
                         boolean usesRetintedRamp) {
        public Result {
            paletteBytes = paletteBytes != null ? paletteBytes.clone() : new byte[0];
            customPurplePaletteBytes = customPurplePaletteBytes != null ? customPurplePaletteBytes.clone() : new byte[0];
            layout = layout != null ? layout : HostEmeraldLayoutProfile.defaultSeven();
        }

        static Result fallback() {
            return new Result(new byte[0], new byte[0], HostEmeraldLayoutProfile.defaultSeven(), 7, false);
        }
    }
}
