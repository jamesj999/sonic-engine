package uk.co.jamesj999.sonic.tools.disasm;

/**
 * Result of verifying a calculated ROM offset against actual ROM data.
 */
public class VerificationResult {

    public enum Status {
        /** Calculated offset matches ROM data */
        VERIFIED,
        /** Calculated offset doesn't match, but data found at different offset */
        MISMATCH,
        /** Could not find the data in ROM */
        NOT_FOUND,
        /** Error during verification process */
        ERROR
    }

    private final String label;
    private final long calculatedOffset;
    private final long verifiedOffset;
    private final Status status;
    private final String message;
    private final CompressionType compressionType;
    private final int fileSize;

    private VerificationResult(String label, long calculatedOffset, long verifiedOffset,
                                Status status, String message,
                                CompressionType compressionType, int fileSize) {
        this.label = label;
        this.calculatedOffset = calculatedOffset;
        this.verifiedOffset = verifiedOffset;
        this.status = status;
        this.message = message;
        this.compressionType = compressionType;
        this.fileSize = fileSize;
    }

    /**
     * Create a verified result - offset matches ROM data.
     */
    public static VerificationResult verified(String label, long offset,
                                               CompressionType type, int fileSize) {
        return new VerificationResult(label, offset, offset, Status.VERIFIED, null, type, fileSize);
    }

    /**
     * Create a mismatch result - calculated offset wrong, found at different location.
     */
    public static VerificationResult mismatch(String label, long calculatedOffset,
                                               long actualOffset, CompressionType type, int fileSize) {
        return new VerificationResult(label, calculatedOffset, actualOffset, Status.MISMATCH,
                String.format("Expected at 0x%X but found at 0x%X", calculatedOffset, actualOffset),
                type, fileSize);
    }

    /**
     * Create a not-found result - could not locate data in ROM.
     */
    public static VerificationResult notFound(String label, long calculatedOffset, String message) {
        return new VerificationResult(label, calculatedOffset, -1, Status.NOT_FOUND, message, null, -1);
    }

    /**
     * Create an error result - something went wrong during verification.
     */
    public static VerificationResult error(String label, String message) {
        return new VerificationResult(label, -1, -1, Status.ERROR, message, null, -1);
    }

    public String getLabel() {
        return label;
    }

    public long getCalculatedOffset() {
        return calculatedOffset;
    }

    public long getVerifiedOffset() {
        return verifiedOffset;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public CompressionType getCompressionType() {
        return compressionType;
    }

    public int getFileSize() {
        return fileSize;
    }

    public boolean isVerified() {
        return status == Status.VERIFIED;
    }

    @Override
    public String toString() {
        return switch (status) {
            case VERIFIED -> String.format("[OK] %s at 0x%X (%s, %d bytes)",
                    label, calculatedOffset, compressionType.getDisplayName(), fileSize);
            case MISMATCH -> String.format("[!!] %s calc=0x%X actual=0x%X",
                    label, calculatedOffset, verifiedOffset);
            case NOT_FOUND -> String.format("[??] %s at 0x%X - %s",
                    label, calculatedOffset, message);
            case ERROR -> String.format("[ER] %s - %s", label, message);
        };
    }
}
