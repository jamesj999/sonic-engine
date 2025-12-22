package uk.co.jamesj999.sonic.sprites.animation;

import java.util.List;

public record SpriteAnimationScript(
        int delay,
        List<Integer> frames,
        SpriteAnimationEndAction endAction,
        int nextAnimationId
) {
}
