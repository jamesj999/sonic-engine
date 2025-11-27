package uk.co.jamesj999.sonic.graphics.mapping;

public class SpriteMapping {
    private final short x;
    private final short y;
    private final byte height;
    private final byte width;
    private final boolean hFlip;
    private final boolean vFlip;
    private final byte palette;
    private final short patternIndex;

    public SpriteMapping(short x, short y, byte height, byte width, boolean hFlip, boolean vFlip, byte palette, short patternIndex) {
        this.x = x;
        this.y = y;
        this.height = height;
        this.width = width;
        this.hFlip = hFlip;
        this.vFlip = vFlip;
        this.palette = palette;
        this.patternIndex = patternIndex;
    }

    public short getX() {
        return x;
    }

    public short getY() {
        return y;
    }

    public byte getHeight() {
        return height;
    }

    public byte getWidth() {
        return width;
    }

    public boolean isHFlip() {
        return hFlip;
    }

    public boolean isVFlip() {
        return vFlip;
    }

    public byte getPalette() {
        return palette;
    }

    public short getPatternIndex() {
        return patternIndex;
    }
}
