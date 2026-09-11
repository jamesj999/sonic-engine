package com.openggf.level.objects.art;

import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.sprites.animation.SpriteAnimationSet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Game-agnostic object art data exposed by object art providers.
 */
public record ObjectArtBundle(
        Map<String, ObjectSpriteSheet> sheets,
        Map<String, SpriteAnimationSet> animations,
        Map<String, Integer> zoneData,
        Pattern[] hudDigitPatterns,
        Pattern[] hudTextPatterns,
        Pattern[] hudLivesPatterns,
        Pattern[] hudLivesNumbers,
        Pattern[] hudHexDigitPatterns) {

    public ObjectArtBundle {
        sheets = Map.copyOf(sheets);
        animations = Map.copyOf(animations);
        zoneData = Map.copyOf(zoneData);
    }

    public static ObjectArtBundle empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public ObjectSpriteSheet sheet(String key) {
        return sheets.get(key);
    }

    public SpriteAnimationSet animation(String key) {
        return animations.get(key);
    }

    public int zoneData(String key) {
        return zoneData.getOrDefault(key, -1);
    }

    public static final class Builder {
        private final Map<String, ObjectSpriteSheet> sheets = new LinkedHashMap<>();
        private final Map<String, SpriteAnimationSet> animations = new LinkedHashMap<>();
        private final Map<String, Integer> zoneData = new LinkedHashMap<>();
        private Pattern[] hudDigitPatterns;
        private Pattern[] hudTextPatterns;
        private Pattern[] hudLivesPatterns;
        private Pattern[] hudLivesNumbers;
        private Pattern[] hudHexDigitPatterns;

        public Builder sheets(Map<String, ObjectSpriteSheet> sheets) {
            this.sheets.putAll(sheets);
            return this;
        }

        public Builder animations(Map<String, SpriteAnimationSet> animations) {
            this.animations.putAll(animations);
            return this;
        }

        public Builder zoneData(String key, int value) {
            this.zoneData.put(key, value);
            return this;
        }

        public Builder hudDigitPatterns(Pattern[] patterns) {
            this.hudDigitPatterns = patterns;
            return this;
        }

        public Builder hudTextPatterns(Pattern[] patterns) {
            this.hudTextPatterns = patterns;
            return this;
        }

        public Builder hudLivesPatterns(Pattern[] patterns) {
            this.hudLivesPatterns = patterns;
            return this;
        }

        public Builder hudLivesNumbers(Pattern[] patterns) {
            this.hudLivesNumbers = patterns;
            return this;
        }

        public Builder hudHexDigitPatterns(Pattern[] patterns) {
            this.hudHexDigitPatterns = patterns;
            return this;
        }

        public ObjectArtBundle build() {
            return new ObjectArtBundle(
                    sheets,
                    animations,
                    zoneData,
                    hudDigitPatterns,
                    hudTextPatterns,
                    hudLivesPatterns,
                    hudLivesNumbers,
                    hudHexDigitPatterns);
        }
    }
}
