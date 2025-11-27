package uk.co.jamesj999.sonic.graphics.art;

import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;

public class SpriteArt {
    private final String code;
    private byte[] art;

    public SpriteArt(String code) {
        this.code = code;
    }

    public void load(Rom rom, int offset, int size) throws IOException {
        this.art = rom.readBytes(offset, size);
    }

    public byte[] getArt() {
        return art;
    }

    public String getCode() {
        return code;
    }
}
