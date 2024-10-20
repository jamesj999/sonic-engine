package uk.co.jamesj999.sonic.debug;

public enum DebugOption {
    A,
    B,
    C,
    D,
    E;

    private static final DebugOption[] vals = values();

    public DebugOption next() {
        return vals[(this.ordinal() + 1) % vals.length];
    }
}
