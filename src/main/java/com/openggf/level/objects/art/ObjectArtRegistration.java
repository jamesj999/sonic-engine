package com.openggf.level.objects.art;

/**
 * Provider-owned metadata for registering object art without expanding
 * game-agnostic art data classes with game or zone fields.
 */
public record ObjectArtRegistration(
        String key,
        SourceKind sourceKind,
        int artAddress,
        int mappingAddress,
        int patternBase,
        int paletteLine,
        int priority,
        boolean plcRequired) {

    public static final int UNSPECIFIED = -1;

    public enum SourceKind {
        ROM_COMPRESSED,
        ROM_UNCOMPRESSED,
        LEVEL_TILE,
        COMPOSITE,
        DERIVED
    }

    public static ObjectArtRegistration sheet(String key) {
        return new ObjectArtRegistration(
                key,
                SourceKind.COMPOSITE,
                UNSPECIFIED,
                UNSPECIFIED,
                UNSPECIFIED,
                UNSPECIFIED,
                UNSPECIFIED,
                false);
    }

    public ObjectArtRegistration withRomSource(int artAddress, int mappingAddress, int paletteLine, int priority) {
        return new ObjectArtRegistration(
                key,
                SourceKind.ROM_COMPRESSED,
                artAddress,
                mappingAddress,
                patternBase,
                paletteLine,
                priority,
                plcRequired);
    }

    public ObjectArtRegistration requiringPlc() {
        return new ObjectArtRegistration(
                key,
                sourceKind,
                artAddress,
                mappingAddress,
                patternBase,
                paletteLine,
                priority,
                true);
    }
}
