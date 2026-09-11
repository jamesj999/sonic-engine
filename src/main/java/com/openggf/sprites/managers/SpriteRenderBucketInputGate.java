package com.openggf.sprites.managers;

import com.openggf.graphics.RenderPriority;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Map;

/**
 * Allocation-free comparison of live sprite bucket inputs against a retained snapshot.
 *
 * <p>Precondition: the owning manager marks its buckets dirty when membership
 * identities change. With membership stable, this gate compares the live size
 * and each retained object's current bucket key; it intentionally does not
 * iterate or compare map identities.
 */
final class SpriteRenderBucketInputGate {

    private SpriteRenderBucketInputGate() {
    }

    static boolean inputsChanged(Map<String, Sprite> liveSprites,
                                 Sprite[] retainedSprites,
                                 int[] retainedKeys,
                                 int retainedCount) {
        if (liveSprites.size() != retainedCount) {
            return true;
        }
        for (int position = 0; position < retainedCount; position++) {
            Sprite sprite = retainedSprites[position];
            if (sprite == null || retainedKeys[position] != inputKey(sprite)) {
                return true;
            }
        }
        return false;
    }

    // Packed-key layout: bit 0 = highPriority, bit 1 = cpuControlled, bits 2+
    // = bucket index. -1 remains the non-playable sentinel.
    static int inputKey(Sprite sprite) {
        if (!(sprite instanceof AbstractPlayableSprite playable)) {
            return -1;
        }
        int bucket = RenderPriority.clamp(playable.getPriorityBucket()) - RenderPriority.MIN;
        return (bucket << 2)
                | (playable.isHighPriority() ? 1 : 0)
                | (playable.isCpuControlled() ? 2 : 0);
    }
}
