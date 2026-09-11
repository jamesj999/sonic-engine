package com.openggf.tools.disasm;

import java.nio.file.Path;

public class DisassemblySearchResult {
    private final String label;
    private final String filePath;
    private final CompressionType compressionType;
    private final String asmFilePath;
    private final int asmLineNumber;
    private final String asmLine;

    public DisassemblySearchResult(String label, String filePath, CompressionType compressionType,
                                    String asmFilePath, int asmLineNumber, String asmLine) {
        this.label = label;
        this.filePath = filePath;
        this.compressionType = compressionType;
        this.asmFilePath = asmFilePath;
        this.asmLineNumber = asmLineNumber;
        this.asmLine = asmLine;
    }

    public String getLabel() {
        return label;
    }

    public String getFilePath() {
        return filePath;
    }

    public CompressionType getCompressionType() {
        return compressionType;
    }

    public String getAsmFilePath() {
        return asmFilePath;
    }

    public int getAsmLineNumber() {
        return asmLineNumber;
    }

    public String getAsmLine() {
        return asmLine;
    }

    public String getFileName() {
        return filePath != null ? Path.of(filePath).getFileName().toString() : null;
    }

    /**
     * Whether this result has an associated file path (binclude or include).
     * Label-only results (Offs_*, PLC_*) return false.
     */
    public boolean hasFile() {
        return filePath != null;
    }

    @Override
    public String toString() {
        String fileName = filePath != null ? getFileName() : "(label-only)";
        String compression = compressionType != null ? compressionType.getDisplayName() : "N/A";
        return String.format("DisassemblySearchResult{label='%s', file='%s', compression=%s, asmLine=%d}",
                label, fileName, compression, asmLineNumber);
    }
}
