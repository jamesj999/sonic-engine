package com.openggf.game.dataselect;

/**
 * Preview metadata for a save slot on the donated S3K data select screen.
 * Host profiles (S1/S2) provide this so the S3K presentation can render
 * host-appropriate zone labels and preview art instead of S3K zone data.
 *
 * @param type the preview rendering mode
 * @param zoneLabelText short label for the zone (e.g. "GHZ"), max ~6 chars;
 *                       must only use characters supported by {@code S3kSaveTextCodec}
 * @param zoneDisplayNumber numeric zone label to render using the native "ZONE" card digits
 */
public record HostSlotPreview(HostSlotPreviewType type, String zoneLabelText, Integer zoneDisplayNumber) {

    public HostSlotPreview(HostSlotPreviewType type, String zoneLabelText) {
        this(type, zoneLabelText, null);
    }

    public static HostSlotPreview textOnly(String zoneLabelText) {
        return new HostSlotPreview(HostSlotPreviewType.TEXT_ONLY, zoneLabelText, null);
    }

    public static HostSlotPreview numberedZone(int zoneDisplayNumber) {
        return new HostSlotPreview(HostSlotPreviewType.NUMBERED_ZONE, null, zoneDisplayNumber);
    }

    public static HostSlotPreview image(String zoneLabelText) {
        return new HostSlotPreview(HostSlotPreviewType.IMAGE, zoneLabelText, null);
    }

    public enum HostSlotPreviewType {
        /** Render zone name as text only (no zone card image). */
        TEXT_ONLY,
        /** Render the native S3K numeric "ZONE" label with a host-supplied number. */
        NUMBERED_ZONE,
        /** Render a scaled host-game image (reserved for future use). */
        IMAGE
    }
}
