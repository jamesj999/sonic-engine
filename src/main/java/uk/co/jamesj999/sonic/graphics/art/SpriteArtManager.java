package uk.co.jamesj999.sonic.graphics.art;

import java.util.HashMap;
import java.util.Map;

public class SpriteArtManager {
    private static SpriteArtManager instance;
    private final Map<String, SpriteArt> spriteArtMap = new HashMap<>();

    private SpriteArtManager() {
    }

    public void addSpriteArt(String code, SpriteArt spriteArt) {
        spriteArtMap.put(code, spriteArt);
    }

    public SpriteArt getSpriteArt(String code) {
        return spriteArtMap.get(code);
    }

    public static synchronized SpriteArtManager getInstance() {
        if (instance == null) {
            instance = new SpriteArtManager();
        }
        return instance;
    }
}
