package uk.co.jamesj999.sonic.tools.disasm;

public class CompressionTestResult {
    private final boolean success;
    private final CompressionType compressionType;
    private final long romOffset;
    private final int compressedSize;
    private final int decompressedSize;
    private final String errorMessage;
    private final byte[] decompressedData;

    private CompressionTestResult(boolean success, CompressionType compressionType, long romOffset,
                                   int compressedSize, int decompressedSize, String errorMessage,
                                   byte[] decompressedData) {
        this.success = success;
        this.compressionType = compressionType;
        this.romOffset = romOffset;
        this.compressedSize = compressedSize;
        this.decompressedSize = decompressedSize;
        this.errorMessage = errorMessage;
        this.decompressedData = decompressedData;
    }

    public static CompressionTestResult success(CompressionType type, long offset,
                                                  int compressedSize, int decompressedSize,
                                                  byte[] decompressedData) {
        return new CompressionTestResult(true, type, offset, compressedSize, decompressedSize,
                null, decompressedData);
    }

    public static CompressionTestResult failure(CompressionType type, long offset, String errorMessage) {
        return new CompressionTestResult(false, type, offset, -1, -1, errorMessage, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public CompressionType getCompressionType() {
        return compressionType;
    }

    public long getRomOffset() {
        return romOffset;
    }

    public int getCompressedSize() {
        return compressedSize;
    }

    public int getDecompressedSize() {
        return decompressedSize;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public byte[] getDecompressedData() {
        return decompressedData;
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("CompressionTestResult{SUCCESS, type=%s, offset=0x%X, compressed=%d, decompressed=%d}",
                    compressionType.getDisplayName(), romOffset, compressedSize, decompressedSize);
        } else {
            return String.format("CompressionTestResult{FAILED, type=%s, offset=0x%X, error='%s'}",
                    compressionType.getDisplayName(), romOffset, errorMessage);
        }
    }
}
