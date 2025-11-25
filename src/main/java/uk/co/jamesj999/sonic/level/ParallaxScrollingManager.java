package uk.co.jamesj999.sonic.level;

import java.util.SortedMap;
import java.util.TreeMap;

public class ParallaxScrollingManager {
    private final SortedMap<Byte, ParallaxScrolling> parallaxScroll = new TreeMap<>();

    public void add(ParallaxScrolling parallaxScrolling) {
        parallaxScroll.put(parallaxScrolling.getLine(), parallaxScrolling);
    }

    public ParallaxScrolling get(byte line) {
        if (parallaxScroll.containsKey(line)) {
            return parallaxScroll.get(line);
        }
        SortedMap<Byte, ParallaxScrolling> headMap = parallaxScroll.headMap(line);
        if (headMap.isEmpty()) {
            return null;
        }
        return headMap.get(headMap.lastKey());
    }
}
