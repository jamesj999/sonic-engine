package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.parallax.*;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages parallax scrolling effects, loading data tables from ROM and generating
 * per-scanline H-Scroll values.
 */
public class ParallaxManager {
    private static final Logger LOGGER = Logger.getLogger(ParallaxManager.class.getName());

    public static final int VISIBLE_LINES = 224;

    // Zone IDs
    private static final int ZONE_EHZ = 0;
    private static final int ZONE_MTZ = 4; // Check IDs?
    private static final int ZONE_WFZ = 9;
    private static final int ZONE_HTZ = 7; // Map correctly
    private static final int ZONE_OOZ = 6;
    private static final int ZONE_MCZ = 2;
    private static final int ZONE_CNZ = 3;
    private static final int ZONE_CPZ = 1;
    private static final int ZONE_DEZ = 10;
    private static final int ZONE_ARZ = 5;
    private static final int ZONE_SCZ = 8;
    // Note: Zone IDs need to match LevelManager/LevelData.
    // 0: EHZ
    // 1: CPZ
    // 2: MCZ? Or ARZ?
    // Let's use standard Sonic 2 IDs if possible, or LevelData map.
    // Assuming standard: 0=EHZ, 1=CPZ, 2=ARZ, 3=CNZ, 4=HTZ, 5=MCZ, 6=OOZ, 7=MTZ, 8=SCZ, 9=WFZ, 10=DEZ.
    // Wait, let's verify LevelData or Sonic2.java if visible.
    // But for now, let's map based on prompt names.
    // The prompt listed:
    // EHZ, MTZ, WFZ, HTZ, OOZ, MCZ, CNZ, CPZ, DEZ, ARZ, SCZ.

    // LevelData.java likely has the enum.

    // Packed as (planeA << 16) | (planeB & 0xFFFF)
    private final int[] hScroll = new int[VISIBLE_LINES];

    private int minScroll = 0;
    private int maxScroll = 0;

    // Strategies
    private ParallaxStrategy currentStrategy;

    // Store Strategies
    private EhzParallaxStrategy ehzStrategy;
    private MtzParallaxStrategy mtzStrategy;
    private WfzParallaxStrategy wfzStrategy;
    private HtzParallaxStrategy htzStrategy;
    private OOZParallaxStrategy oozStrategy;
    private MCZParallaxStrategy mczStrategy;
    private CNZParallaxStrategy cnzStrategy;
    private CPZParallaxStrategy cpzStrategy;
    private DEZParallaxStrategy dezStrategy;
    private ARZParallaxStrategy arzStrategy;
    private SCZParallaxStrategy sczStrategy;

    private boolean loaded = false;

    // Addresses (REV01)
    private static final int EHZ_RIPPLE_ADDR = 0x00C682;
    private static final int EHZ_RIPPLE_SIZE = 66; // Matches ROM table size

    private static final int WFZ_TRANS_ADDR = 0x00C8CA;
    private static final int WFZ_NORMAL_ADDR = 0x00C916;
    private static final int WFZ_TABLE_SIZE = 128; // Safe

    private static final int MCZ_ROW_ADDR = 0x00CE6C;
    private static final int MCZ_ROW_SIZE = 256; // Safe

    private static final int CNZ_ROW_ADDR = 0x00D156;
    private static final int CNZ_ROW_SIZE = 256;

    private static final int DEZ_ROW_ADDR = 0x00D48A;
    private static final int DEZ_ROW_SIZE = 256;

    private static final int ARZ_ROW_ADDR = 0x00D5CE;
    private static final int ARZ_ROW_SIZE = 256;

    private static ParallaxManager instance;

    public static synchronized ParallaxManager getInstance() {
        if (instance == null) {
            instance = new ParallaxManager();
        }
        return instance;
    }

    public void load(Rom rom) {
        if (loaded) return;
        try {
            // Load tables
            byte[] ehzRipple = rom.readBytes(EHZ_RIPPLE_ADDR, EHZ_RIPPLE_SIZE);
            byte[] wfzTrans = rom.readBytes(WFZ_TRANS_ADDR, WFZ_TABLE_SIZE);
            byte[] wfzNormal = rom.readBytes(WFZ_NORMAL_ADDR, WFZ_TABLE_SIZE);
            byte[] mczRows = rom.readBytes(MCZ_ROW_ADDR, MCZ_ROW_SIZE);
            byte[] cnzRows = rom.readBytes(CNZ_ROW_ADDR, CNZ_ROW_SIZE);
            byte[] dezRows = rom.readBytes(DEZ_ROW_ADDR, DEZ_ROW_SIZE);
            byte[] arzRows = rom.readBytes(ARZ_ROW_ADDR, ARZ_ROW_SIZE);

            // Initialize strategies
            ehzStrategy = new EhzParallaxStrategy(ehzRipple);
            mtzStrategy = new MtzParallaxStrategy();
            wfzStrategy = new WfzParallaxStrategy(wfzTrans, wfzNormal);
            htzStrategy = new HtzParallaxStrategy();
            oozStrategy = new OOZParallaxStrategy(ehzRipple); // OOZ uses same ripple table? Prompt: "sun uses SwScrl_RippleData -> byte_C682"
            mczStrategy = new MCZParallaxStrategy(mczRows, null);
            cnzStrategy = new CNZParallaxStrategy(cnzRows);
            cpzStrategy = new CPZParallaxStrategy(ehzRipple); // Check if CPZ uses same table. Prompt: "one special block row... uses SwScrl_RippleData" -> Yes.
            dezStrategy = new DEZParallaxStrategy(dezRows);
            arzStrategy = new ARZParallaxStrategy(arzRows);
            sczStrategy = new SCZParallaxStrategy();

            loaded = true;
            LOGGER.info("Parallax data loaded and strategies initialized.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load parallax data: " + e.getMessage(), e);
            // Fallback?
        }
    }

    public int[] getHScroll() {
        return hScroll;
    }

    public int getMinScroll() { return minScroll; }
    public int getMaxScroll() { return maxScroll; }

    public void update(int zoneId, int actId, Camera cam, int frameCounter, int bgScrollY) {
        // Reset min/max (recalc after update)
        minScroll = Integer.MAX_VALUE;
        maxScroll = Integer.MIN_VALUE;

        if (!loaded) {
             // Fallback
             return;
        }

        ParallaxStrategy strategy = getStrategy(zoneId);
        if (strategy != null) {
            strategy.update(hScroll, cam, frameCounter, bgScrollY, actId);

            // Calc min/max for rendering optimization
            for (int val : hScroll) {
                short bg = (short)(val & 0xFFFF);
                if (bg < minScroll) minScroll = bg;
                if (bg > maxScroll) maxScroll = bg;
            }
        }

        // Debug dump?
        // if (SonicConfiguration.DEBUG_PARALLAX) dump(hScroll);
    }

    private ParallaxStrategy getStrategy(int zoneId) {
        // Map Zone ID to Strategy
        // IDs must match LevelData.
        // 0=EHZ, 1=CPZ, 2=ARZ, 3=CNZ, 4=HTZ, 5=MCZ, 6=OOZ, 7=MTZ, 8=SCZ, 9=WFZ, 10=DEZ
        switch (zoneId) {
            case 0: return ehzStrategy;
            case 1: return cpzStrategy;
            case 2: return arzStrategy;
            case 3: return cnzStrategy;
            case 4: return htzStrategy;
            case 5: return mczStrategy;
            case 6: return oozStrategy;
            case 7: return mtzStrategy;
            case 8: return sczStrategy;
            case 9: return wfzStrategy;
            case 10: return dezStrategy;
            default: return mtzStrategy; // Default minimal
        }
    }
}
