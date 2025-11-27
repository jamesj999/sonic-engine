package uk.co.jamesj999.sonic.graphics.mapping;

import java.util.HashMap;
import java.util.Map;

public class SpriteMapManager {
    private static SpriteMapManager instance;
    private final Map<String, SpriteMap> spriteMapMap = new HashMap<>();

    private SpriteMapManager() {
    }

    public void addSpriteMap(String code, SpriteMap spriteMap) {
        spriteMapMap.put(code, spriteMap);
    }

    public SpriteMap getSpriteMap(String code) {
        return spriteMapMap.get(code);
    }

    public static synchronized SpriteMapManager getInstance() {
        if (instance == null) {
            instance = new SpriteMapManager();
        }
        return instance;
    }
}
