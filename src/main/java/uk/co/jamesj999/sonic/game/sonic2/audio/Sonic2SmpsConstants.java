package uk.co.jamesj999.sonic.game.sonic2.audio;

public final class Sonic2SmpsConstants {
    public static final int MUSIC_FLAGS_ADDR = 0x0ECF36;
    public static final int MUSIC_FLAGS_ID_BASE = 0x81;
    public static final int MUSIC_PTR_TABLE_ADDR = 0x0EC810;
    public static final int MUSIC_PTR_BANK0 = 0x0F0000;
    public static final int MUSIC_PTR_BANK1 = 0x0F8000;

    public static final int Z80_COMPRESSED_LOAD_ADDR = 0x1380;
    public static final int Z80_UNCOMPRESSED_LOAD_ADDR = 0x1C00;
    public static final int Z80_BANK_BASE = 0x8000;
    public static final int Z80_BANK_MASK = 0x7FFF;

    public static final int SFX_ID_BASE = 0xA0;
    public static final int SFX_ID_MAX = 0xEF;
    public static final int SFX_ID_PARSE_MAX = 0xF0;
    public static final int SFX_POINTER_TABLE_ADDR = 0x0FEE91;
    public static final int SFX_BANK_BASE = 0x0F8000;
    public static final int SFX_BANK_SIZE = 0x8000;
    public static final int SFX_VOICE_TABLE_PADDING = 0x100;

    public static final int PSG_ENVELOPE_TABLE_ADDR = 0x0F2E5C;
    public static final int PSG_ENVELOPE_BANK_BASE = 0x0F0000;

    public static final int PCM_BANK_START = 0x0E0000;
    public static final int PCM_SAMPLE_PTR_TABLE_ADDR = 0x0ECF7C;
    public static final int PCM_SAMPLE_MAP_ADDR = 0x0ECF9C;
    public static final int PCM_SAMPLE_ID_BASE = 0x81;
    public static final int PCM_SAMPLE_COUNT = 7;
    public static final int PCM_MAPPING_COUNT = 17;

    private Sonic2SmpsConstants() {
    }
}
