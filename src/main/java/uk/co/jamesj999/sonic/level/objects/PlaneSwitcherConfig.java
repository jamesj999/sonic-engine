package uk.co.jamesj999.sonic.level.objects;

public final class PlaneSwitcherConfig {
    private final byte path0TopSolidBit;
    private final byte path0LrbSolidBit;
    private final byte path1TopSolidBit;
    private final byte path1LrbSolidBit;

    public PlaneSwitcherConfig(byte path0TopSolidBit,
                               byte path0LrbSolidBit,
                               byte path1TopSolidBit,
                               byte path1LrbSolidBit) {
        this.path0TopSolidBit = path0TopSolidBit;
        this.path0LrbSolidBit = path0LrbSolidBit;
        this.path1TopSolidBit = path1TopSolidBit;
        this.path1LrbSolidBit = path1LrbSolidBit;
    }

    public byte getPath0TopSolidBit() {
        return path0TopSolidBit;
    }

    public byte getPath0LrbSolidBit() {
        return path0LrbSolidBit;
    }

    public byte getPath1TopSolidBit() {
        return path1TopSolidBit;
    }

    public byte getPath1LrbSolidBit() {
        return path1LrbSolidBit;
    }
}
