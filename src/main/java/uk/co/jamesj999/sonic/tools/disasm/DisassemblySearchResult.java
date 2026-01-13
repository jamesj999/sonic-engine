package uk.co.jamesj999.sonic.tools.disasm;

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
        return Path.of(filePath).getFileName().toString();
    }

    @Override
    public String toString() {
        return String.format("DisassemblySearchResult{label='%s', file='%s', compression=%s, asmLine=%d}",
                label, getFileName(), compressionType.getDisplayName(), asmLineNumber);
    }
}
