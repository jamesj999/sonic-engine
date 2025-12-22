package uk.co.jamesj999.sonic.sprites.animation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SpriteAnimationSet {
    private final Map<Integer, SpriteAnimationScript> scripts = new HashMap<>();

    public void addScript(int id, SpriteAnimationScript script) {
        if (script == null) {
            return;
        }
        scripts.put(id, script);
    }

    public SpriteAnimationScript getScript(int id) {
        return scripts.get(id);
    }

    public int getScriptCount() {
        return scripts.size();
    }

    public Map<Integer, SpriteAnimationScript> getAllScripts() {
        return Collections.unmodifiableMap(scripts);
    }
}
