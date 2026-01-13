package uk.co.jamesj999.sonic.tools.disasm;

public enum CompressionType {
    NEMESIS(".nem", "Nemesis"),
    KOSINSKI(".kos", "Kosinski"),
    KOSINSKI_MODULED(".kosm", "Kosinski Moduled"),
    ENIGMA(".eni", "Enigma"),
    SAXMAN(".sax", "Saxman"),
    UNCOMPRESSED(".bin", "Uncompressed"),
    UNKNOWN("", "Unknown");

    private final String extension;
    private final String displayName;

    CompressionType(String extension, String displayName) {
        this.extension = extension;
        this.displayName = displayName;
    }

    public String getExtension() {
        return extension;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CompressionType fromExtension(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".nem")) return NEMESIS;
        if (lower.endsWith(".kosm")) return KOSINSKI_MODULED;
        if (lower.endsWith(".kos")) return KOSINSKI;
        if (lower.endsWith(".eni")) return ENIGMA;
        if (lower.endsWith(".sax")) return SAXMAN;
        if (lower.endsWith(".bin")) return UNCOMPRESSED;
        return UNKNOWN;
    }
}
