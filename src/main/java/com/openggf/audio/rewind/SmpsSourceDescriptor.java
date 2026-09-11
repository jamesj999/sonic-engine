package com.openggf.audio.rewind;

import com.openggf.audio.smps.AbstractSmpsData;

import java.util.Arrays;
import java.util.Objects;

public record SmpsSourceDescriptor(
        Kind kind,
        int id,
        String name,
        String donorGameId,
        int z80StartAddress,
        int dataLength,
        int dataHash,
        boolean palSpeedupDisabled,
        long dependencyGeneration) {

    public enum Kind {
        UNKNOWN,
        BASE_MUSIC,
        BASE_SFX_ID,
        BASE_SFX_NAME,
        DONOR_MUSIC,
        DONOR_SFX_ID
    }

    public SmpsSourceDescriptor {
        Objects.requireNonNull(kind, "kind");
        if (dependencyGeneration < 0) {
            throw new IllegalArgumentException(
                    "dependencyGeneration must be non-negative");
        }
    }

    public static SmpsSourceDescriptor from(AbstractSmpsData data) {
        return describe(Kind.UNKNOWN, null, null, 0, data);
    }

    public static SmpsSourceDescriptor from(
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.UNKNOWN, null, null,
                dependencyGeneration, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor baseMusic(AbstractSmpsData data) {
        return describe(Kind.BASE_MUSIC, null, null, 0, data);
    }

    public static SmpsSourceDescriptor baseMusic(
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.BASE_MUSIC, null, null,
                dependencyGeneration, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor baseMusic(
            int assetId,
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.BASE_MUSIC, null, null,
                dependencyGeneration, assetId, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor baseSfx(AbstractSmpsData data) {
        return describe(Kind.BASE_SFX_ID, null, null, 0, data);
    }

    public static SmpsSourceDescriptor baseSfx(
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.BASE_SFX_ID, null, null,
                dependencyGeneration, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor baseSfx(
            int assetId,
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.BASE_SFX_ID, null, null,
                dependencyGeneration, assetId, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor baseNamedSfx(String name, AbstractSmpsData data) {
        return describe(Kind.BASE_SFX_NAME,
                Objects.requireNonNull(name, "name"), null, 0, data);
    }

    public static SmpsSourceDescriptor baseNamedSfx(
            String name,
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.BASE_SFX_NAME,
                Objects.requireNonNull(name, "name"), null,
                dependencyGeneration, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor donorMusic(String donorGameId, AbstractSmpsData data) {
        return describe(Kind.DONOR_MUSIC, null,
                Objects.requireNonNull(donorGameId, "donorGameId"), 0,
                data);
    }

    public static SmpsSourceDescriptor donorMusic(
            String donorGameId,
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.DONOR_MUSIC, null,
                Objects.requireNonNull(donorGameId, "donorGameId"),
                dependencyGeneration, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor donorMusic(
            String donorGameId,
            int assetId,
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.DONOR_MUSIC, null,
                Objects.requireNonNull(donorGameId, "donorGameId"),
                dependencyGeneration, assetId, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor donorSfx(String donorGameId, AbstractSmpsData data) {
        return describe(Kind.DONOR_SFX_ID, null,
                Objects.requireNonNull(donorGameId, "donorGameId"), 0,
                data);
    }

    public static SmpsSourceDescriptor donorSfx(
            String donorGameId,
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.DONOR_SFX_ID, null,
                Objects.requireNonNull(donorGameId, "donorGameId"),
                dependencyGeneration, data, dataLength, dataHash);
    }

    public static SmpsSourceDescriptor donorSfx(
            String donorGameId,
            int assetId,
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        return describe(Kind.DONOR_SFX_ID, null,
                Objects.requireNonNull(donorGameId, "donorGameId"),
                dependencyGeneration, assetId, data, dataLength, dataHash);
    }

    private static SmpsSourceDescriptor describe(
            Kind kind,
            String name,
            String donorGameId,
            long dependencyGeneration,
            AbstractSmpsData data) {
        Objects.requireNonNull(data, "data");
        byte[] bytes = data.getData();
        return describe(kind, name, donorGameId, dependencyGeneration,
                data, bytes != null ? bytes.length : 0,
                Arrays.hashCode(bytes));
    }

    private static SmpsSourceDescriptor describe(
            Kind kind,
            String name,
            String donorGameId,
            long dependencyGeneration,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        Objects.requireNonNull(data, "data");
        return describe(kind, name, donorGameId, dependencyGeneration,
                data.getId(), data, dataLength, dataHash);
    }

    private static SmpsSourceDescriptor describe(
            Kind kind,
            String name,
            String donorGameId,
            long dependencyGeneration,
            int assetId,
            AbstractSmpsData data,
            int dataLength,
            int dataHash) {
        Objects.requireNonNull(data, "data");
        return new SmpsSourceDescriptor(
                kind,
                assetId,
                name,
                donorGameId,
                data.getZ80StartAddress(),
                dataLength,
                dataHash,
                data.isPalSpeedupDisabled(),
                dependencyGeneration);
    }

    public boolean matchesData(AbstractSmpsData data) {
        return matches(from(data));
    }

    public boolean matches(SmpsSourceDescriptor other) {
        Objects.requireNonNull(other, "other");
        return id == other.id
                && z80StartAddress == other.z80StartAddress
                && dataLength == other.dataLength
                && dataHash == other.dataHash
                && palSpeedupDisabled == other.palSpeedupDisabled;
    }
}
