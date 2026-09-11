package com.openggf.game.timing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical SHA-256 identity for independently submitted ROM-backed work. */
public final class HardwareSubmissionFingerprint {
    private HardwareSubmissionFingerprint() {
    }

    public static String compute(HardwareWorkSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        return computeCanonical(
                submission.kind().name(),
                submission.romSourceAddress(),
                submission.compressedLength(),
                submission.destinationAddress(),
                submission.destinationLength(),
                submission.compressionVariant(),
                submission.moduleCount());
    }

    static String computeCanonical(
            String kindName,
            int romSourceAddress,
            int compressedLength,
            int destinationAddress,
            int destinationLength,
            String compressionVariant,
            int moduleCount) {
        Objects.requireNonNull(kindName, "kindName");
        Objects.requireNonNull(compressionVariant, "compressionVariant");
        byte[] canonical = encodeCanonical(
                kindName,
                romSourceAddress,
                compressedLength,
                destinationAddress,
                destinationLength,
                compressionVariant,
                moduleCount);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] encodeCanonical(
            String kindName,
            int romSourceAddress,
            int compressedLength,
            int destinationAddress,
            int destinationLength,
            String compressionVariant,
            int moduleCount) {
        byte[] kindBytes = kindName.getBytes(StandardCharsets.UTF_8);
        byte[] variantBytes = compressionVariant.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                Integer.BYTES * 7 + kindBytes.length + variantBytes.length);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeUtf8(output, kindBytes);
            output.writeInt(romSourceAddress);
            output.writeInt(compressedLength);
            output.writeInt(destinationAddress);
            output.writeInt(destinationLength);
            writeUtf8(output, variantBytes);
            output.writeInt(moduleCount);
        } catch (IOException e) {
            throw new IllegalStateException("failed to encode in-memory hardware fingerprint", e);
        }
        return bytes.toByteArray();
    }

    private static void writeUtf8(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
}
