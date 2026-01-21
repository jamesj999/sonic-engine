package uk.co.jamesj999.sonic.graphics;

import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SpriteRenderManager {
    private static SpriteRenderManager spriteRenderManager;

    private final SpriteManager spriteManager = SpriteManager.getInstance();

    private static final int BUCKET_COUNT = RenderPriority.MAX - RenderPriority.MIN + 1;
    @SuppressWarnings("unchecked")
    private final List<Sprite>[] lowPriorityBuckets = new ArrayList[BUCKET_COUNT];
    @SuppressWarnings("unchecked")
    private final List<Sprite>[] highPriorityBuckets = new ArrayList[BUCKET_COUNT];
    private final List<Sprite> nonPlayableSprites = new ArrayList<>();

    public SpriteRenderManager() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lowPriorityBuckets[i] = new ArrayList<>();
            highPriorityBuckets[i] = new ArrayList<>();
        }
    }

    public void draw() {
        Collection<Sprite> sprites = spriteManager.getAllSprites();
        for (Sprite sprite : sprites) {
            sprite.draw();
        }
    }

    public void drawLowPriority() {
        bucketSprites();
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            int idx = bucket - RenderPriority.MIN;
            for (Sprite sprite : lowPriorityBuckets[idx]) {
                sprite.draw();
            }
        }
    }

    public void drawHighPriority() {
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            int idx = bucket - RenderPriority.MIN;
            for (Sprite sprite : highPriorityBuckets[idx]) {
                sprite.draw();
            }
            if (bucket == RenderPriority.MIN) {
                for (Sprite sprite : nonPlayableSprites) {
                    sprite.draw();
                }
            }
        }
    }

    private void bucketSprites() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lowPriorityBuckets[i].clear();
            highPriorityBuckets[i].clear();
        }
        nonPlayableSprites.clear();

        Collection<Sprite> sprites = spriteManager.getAllSprites();
        for (Sprite sprite : sprites) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                int bucket = RenderPriority.clamp(playable.getPriorityBucket());
                int idx = bucket - RenderPriority.MIN;
                if (playable.isHighPriority()) {
                    highPriorityBuckets[idx].add(sprite);
                } else {
                    lowPriorityBuckets[idx].add(sprite);
                }
            } else {
                nonPlayableSprites.add(sprite);
            }
        }
    }

    public void drawPriorityBucket(int bucket, boolean highPriority) {
        Collection<Sprite> sprites = spriteManager.getAllSprites();
        int targetBucket = RenderPriority.clamp(bucket);
        for (Sprite sprite : sprites) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                int spriteBucket = RenderPriority.clamp(playable.getPriorityBucket());
                if (playable.isHighPriority() == highPriority && spriteBucket == targetBucket) {
                    sprite.draw();
                }
                continue;
            }
            if (highPriority && targetBucket == RenderPriority.MIN) {
                sprite.draw();
            }
        }
    }

    public static synchronized SpriteRenderManager getInstance() {
        if (spriteRenderManager == null) {
            spriteRenderManager = new SpriteRenderManager();
        }
        return spriteRenderManager;
    }
}
