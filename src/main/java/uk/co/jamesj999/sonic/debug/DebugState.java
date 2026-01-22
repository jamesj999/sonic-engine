package uk.co.jamesj999.sonic.debug;

public enum DebugState {
    NONE,
    PATTERNS_VIEW,
    BLOCKS_VIEW;

    private static final DebugState[] vals = values();

    public DebugState next() {
        return vals[(this.ordinal() + 1) % vals.length];
    }
}
