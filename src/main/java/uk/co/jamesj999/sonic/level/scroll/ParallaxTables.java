package uk.co.jamesj999.sonic.level.scroll;

import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Loads parallax data tables from ROM at exact offsets.
 * All offsets are for Sonic 2 World Rev 01.
 */
public class ParallaxTables {
    private static final Logger LOGGER = Logger.getLogger(ParallaxTables.class.getName());

    // ROM offsets (Rev 01)
    public static final int SWSCRL_RIPPLE_DATA_ADDR = 0xC682;
    public static final int SWSCRL_RIPPLE_DATA_SIZE = 256;

    public static final int SWSCRL_WFZ_TRANS_ADDR = 0xC8CA;
    public static final int SWSCRL_WFZ_TRANS_SIZE = 76; // Until normal array

    public static final int SWSCRL_WFZ_NORMAL_ADDR = 0xC916;
    public static final int SWSCRL_WFZ_NORMAL_SIZE = 78;

    public static final int SWSCRL_MCZ_ROW_HEIGHTS_ADDR = 0xCE6C;
    public static final int SWSCRL_MCZ_ROW_HEIGHTS_SIZE = 24;

    public static final int SWSCRL_MCZ_2P_ROW_HEIGHTS_ADDR = 0xCF90;
    public static final int SWSCRL_MCZ_2P_ROW_HEIGHTS_SIZE = 26;

    public static final int SWSCRL_CNZ_ROW_HEIGHTS_ADDR = 0xD156;
    public static final int SWSCRL_CNZ_ROW_HEIGHTS_SIZE = 64;

    public static final int SWSCRL_DEZ_ROW_HEIGHTS_ADDR = 0xD48A;
    public static final int SWSCRL_DEZ_ROW_HEIGHTS_SIZE = 36;

    public static final int SWSCRL_ARZ_ROW_HEIGHTS_ADDR = 0xD5CE;
    public static final int SWSCRL_ARZ_ROW_HEIGHTS_SIZE = 16;

    // Loaded tables
    private byte[] rippleData;
    private byte[] wfzTransArray;
    private byte[] wfzNormalArray;
    private byte[] mczRowHeights;
    private byte[] mcz2PRowHeights;
    private byte[] cnzRowHeights;
    private byte[] dezRowHeights;
    private byte[] arzRowHeights;

    public ParallaxTables(Rom rom) throws IOException {
        loadTables(rom);
    }

    private void loadTables(Rom rom) throws IOException {
        rippleData = rom.readBytes(SWSCRL_RIPPLE_DATA_ADDR, SWSCRL_RIPPLE_DATA_SIZE);
        LOGGER.fine("Loaded ripple data: " + rippleData.length + " bytes from 0x" +
                Integer.toHexString(SWSCRL_RIPPLE_DATA_ADDR));

        wfzTransArray = rom.readBytes(SWSCRL_WFZ_TRANS_ADDR, SWSCRL_WFZ_TRANS_SIZE);
        wfzNormalArray = rom.readBytes(SWSCRL_WFZ_NORMAL_ADDR, SWSCRL_WFZ_NORMAL_SIZE);

        mczRowHeights = rom.readBytes(SWSCRL_MCZ_ROW_HEIGHTS_ADDR, SWSCRL_MCZ_ROW_HEIGHTS_SIZE);
        mcz2PRowHeights = rom.readBytes(SWSCRL_MCZ_2P_ROW_HEIGHTS_ADDR, SWSCRL_MCZ_2P_ROW_HEIGHTS_SIZE);

        cnzRowHeights = rom.readBytes(SWSCRL_CNZ_ROW_HEIGHTS_ADDR, SWSCRL_CNZ_ROW_HEIGHTS_SIZE);
        dezRowHeights = rom.readBytes(SWSCRL_DEZ_ROW_HEIGHTS_ADDR, SWSCRL_DEZ_ROW_HEIGHTS_SIZE);
        arzRowHeights = rom.readBytes(SWSCRL_ARZ_ROW_HEIGHTS_ADDR, SWSCRL_ARZ_ROW_HEIGHTS_SIZE);

        LOGGER.info("All parallax tables loaded successfully.");
    }

    /**
     * Get ripple data byte at index.
     * Used by EHZ water surface, OOZ sun haze, CPZ ripple effect.
     */
    public byte getRippleByte(int index) {
        if (rippleData == null || index < 0)
            return 0;
        return rippleData[index % rippleData.length];
    }

    /**
     * Get signed ripple value (sign-extended byte).
     */
    public int getRippleSigned(int index) {
        return getRippleByte(index); // Java bytes are already signed
    }

    public int getRippleDataLength() {
        return rippleData != null ? rippleData.length : 0;
    }

    public byte[] getRippleData() {
        return rippleData;
    }

    public byte[] getWfzTransArray() {
        return wfzTransArray;
    }

    public byte[] getWfzNormalArray() {
        return wfzNormalArray;
    }

    public byte[] getMczRowHeights() {
        return mczRowHeights;
    }

    public byte[] getMcz2PRowHeights() {
        return mcz2PRowHeights;
    }

    public byte[] getCnzRowHeights() {
        return cnzRowHeights;
    }

    public byte[] getDezRowHeights() {
        return dezRowHeights;
    }

    public byte[] getArzRowHeights() {
        return arzRowHeights;
    }
}
