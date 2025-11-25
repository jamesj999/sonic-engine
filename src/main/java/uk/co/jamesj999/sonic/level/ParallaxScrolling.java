package uk.co.jamesj999.sonic.level;

public class ParallaxScrolling {
    private short relativeScroll;
    private byte line;

    public ParallaxScrolling(short relativeScroll, byte line) {
        this.relativeScroll = relativeScroll;
        this.line = line;
    }

    public short getRelativeScroll() {
        return relativeScroll;
    }

    public byte getLine() {
        return line;
    }
}
