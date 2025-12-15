package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.parallax.*;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages parallax scrolling effects, delegating to zone-specific strategies.
 */
public class ParallaxManager {
    private static final Logger LOGGER = Logger.getLogger(ParallaxManager.class.getName());

    public static final int VISIBLE_LINES = 224;

    // Packed as (planeA << 16) | (planeB & 0xFFFF)
    // Plane A is FG, Plane B is BG.
    private final int[] hScroll = new int[VISIBLE_LINES];

    private int minScroll = 0;
    private int maxScroll = 0;

    private boolean loaded = false;

    private final Map<Integer, ParallaxStrategy> strategies = new HashMap<>();
    private final ParallaxStrategy defaultStrategy = new MinimalParallaxStrategy();

    private static ParallaxManager instance;

    public static synchronized ParallaxManager getInstance() {
        if (instance == null) {
            instance = new ParallaxManager();
        }
        return instance;
    }

    private ParallaxManager() {
        // Register Strategies
        // Zone IDs match LevelManager list index
        strategies.put(0, new EhzParallaxStrategy()); // EHZ
        strategies.put(1, new CpzParallaxStrategy()); // CPZ
        strategies.put(9, new WfzParallaxStrategy()); // WFZ

        // Others use default for now, or can be added explicitly
        // strategies.put(2, new ArzParallaxStrategy());
    }

    public void load(Rom rom) {
        if (loaded) return;

        for (ParallaxStrategy strategy : strategies.values()) {
            strategy.load(rom);
        }
        defaultStrategy.load(rom);

        loaded = true;
    }

    public int[] getHScroll() {
        return hScroll;
    }

    public int getMinScroll() { return minScroll; }
    public int getMaxScroll() { return maxScroll; }

    public void update(int zoneId, int actId, Camera cam, int frameCounter, int bgScrollY) {
        // Reset min/max tracking (strategies don't track it anymore, but we can compute it if needed)
        // The old code used updateMinMax to track range for optimization.
        // We will re-calculate it after update if strictly necessary,
        // or assume the renderer handles it.
        // Looking at LevelManager, it uses localMin/localMax from hScroll array.
        // So global minScroll/maxScroll might not be critical, but let's keep it safe.

        ParallaxStrategy strategy = strategies.getOrDefault(zoneId, defaultStrategy);
        strategy.update(cam, frameCounter, bgScrollY, hScroll);

        // Recalculate Min/Max for public accessors
        minScroll = Integer.MAX_VALUE;
        maxScroll = Integer.MIN_VALUE;

        for (int val : hScroll) {
            short bg = (short) (val & 0xFFFF);
            if (bg < minScroll) minScroll = bg;
            if (bg > maxScroll) maxScroll = bg;
        }
    }
}
