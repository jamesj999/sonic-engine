package uk.co.jamesj999.sonic.graphics.mapping;

import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SpriteMap {
    private final String code;
    private final List<SpriteMapping> mappings = new ArrayList<>();

    public SpriteMap(String code) {
        this.code = code;
    }

    public void load(Rom rom, int offset) throws IOException {
        byte numberOfMappings = rom.readByte(offset);
        for (int i = 0; i < numberOfMappings; i++) {
            int mappingOffset = offset + 1 + (i * 6);
            short y = rom.readByte(mappingOffset);
            byte height = (byte) (rom.readByte(mappingOffset + 1) >> 2);
            byte width = (byte) (rom.readByte(mappingOffset + 1) & 0x03);
            int patternData = rom.read16BitAddr(mappingOffset + 2);
            short x = rom.readByte(mappingOffset + 4);
            boolean hFlip = (patternData & 0x800) > 0;
            boolean vFlip = (patternData & 0x1000) > 0;
            byte palette = (byte) ((patternData >> 13) & 0x03);
            short patternIndex = (short) (patternData & 0x7FF);
            mappings.add(new SpriteMapping(x, y, height, width, hFlip, vFlip, palette, patternIndex));
        }
    }

    public List<SpriteMapping> getMappings() {
        return mappings;
    }
}
