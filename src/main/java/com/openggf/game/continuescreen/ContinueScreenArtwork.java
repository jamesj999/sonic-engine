package com.openggf.game.continuescreen;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPieces;
import com.openggf.util.PatternDecompressor;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** ROM-backed continue-screen VRAM image; mappings remain relative to native tile $500. */
public final class ContinueScreenArtwork {
    private final Pattern[] patterns = new Pattern[0x200];
    private final Palette[] palettes;
    private final PatternSpriteRenderer renderer;
    private final List<SpriteMappingFrame> mappings;
    private boolean cached;
    private int countdown = -1;

    public ContinueScreenArtwork(Rom rom, List<SpriteMappingFrame> mappings,
            int paletteAddress, int paletteLines) throws IOException {
        Arrays.setAll(patterns, i -> new Pattern());
        palettes = new Palette[4];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = new Palette();
            if (i < paletteLines) palettes[i].fromSegaFormat(rom.readBytes(paletteAddress + i * 32, 32));
        }
        palettes[0].colors[0].fromSegaFormat(0);
        this.mappings = mappings;
        renderer = new PatternSpriteRenderer(new ObjectSpriteSheet(patterns, mappings, 0, 1));
    }

    public void setPalette(int line, Palette palette) {
        palettes[line] = palette.deepCopy();
        palettes[0].colors[0].fromSegaFormat(0);
        if (cached) GameServices.graphics().cachePaletteTexture(palettes[line], line);
    }

    public void loadNemesis(Rom rom, int address, int tileOffset) throws IOException {
        Pattern[] source = PatternDecompressor.nemesis(rom, address);
        copyPatterns(source, 0, source.length, tileOffset);
    }

    public void copyPatterns(Pattern[] source, int sourceOffset, int count, int destination) {
        System.arraycopy(source, sourceOffset, patterns, destination, count);
        if (cached) renderer.updatePatternRange(GameServices.graphics(), destination, count);
    }

    public void setCountdown(int value, Pattern[] digits) {
        if (value == countdown) return;
        countdown = value;
        copyPatterns(digits, value / 10 * 2, 2, 0x1FC);
        copyPatterns(digits, value % 10 * 2, 2, 0x1FE);
    }

    public void cache() {
        if (cached) return;
        for (int i = 0; i < palettes.length; i++) GameServices.graphics().cachePaletteTexture(palettes[i], i);
        renderer.ensurePatternsCached(GameServices.graphics(), PatternAtlasRange.CONTINUE_SCREEN.base());
        cached = true;
    }

    public void draw(int frame, int x, int y) {
        cache();
        renderer.drawFrameIndexWithPaletteBase(frame, x, y, false, false, 0);
    }

    /** Build once for mini-character mappings whose native art tile differs from $500. */
    public PatternSpriteRenderer withTileOffset(int offset, int atlasOffset) {
        var adjusted = mappings.stream().map(frame -> new SpriteMappingFrame(frame.pieces().stream()
                .map(piece -> SpriteMappingPieces.withTileWord(piece,
                        SpriteMappingPieces.toTileWord(piece) + offset)).toList())).toList();
        var result = new PatternSpriteRenderer(new ObjectSpriteSheet(patterns, adjusted, 0, 1));
        result.ensurePatternsCached(GameServices.graphics(), PatternAtlasRange.CONTINUE_SCREEN.base() + atlasOffset);
        return result;
    }
    /** Reuses the native playable animation and DPLC helpers without running gameplay movement. */
    public static final class Character {
        private final com.openggf.sprites.playable.AbstractPlayableSprite sprite;
        private final com.openggf.sprites.managers.PlayableSpriteAnimation animation;
        private final com.openggf.sprites.render.PlayerSpriteRenderer renderer;

        public Character(com.openggf.sprites.art.SpriteArtSet art,
                com.openggf.sprites.playable.AbstractPlayableSprite sprite, int atlasOffset, int animationId) {
            this.sprite = sprite;
            sprite.setAnimationSet(art.animationSet());
            sprite.setAnimationProfile(art.animationProfile());
            sprite.setAnimationFrameCount(art.mappingFrames().size());
            sprite.setAnimationId(animationId);
            sprite.setDirection(com.openggf.physics.Direction.RIGHT);
            animation = new com.openggf.sprites.managers.PlayableSpriteAnimation(sprite);
            var relocated = new com.openggf.sprites.art.SpriteArtSet(art.artTiles(), art.mappingFrames(),
                    art.dplcFrames(), art.paletteIndex(), PatternAtlasRange.CONTINUE_SCREEN.base() + atlasOffset,
                    art.frameDelay(), art.bankSize(), art.animationProfile(), art.animationSet());
            renderer = new com.openggf.sprites.render.PlayerSpriteRenderer(relocated);
        }

        public void setAnimation(int id) { sprite.setAnimationId(id); }

        public void update(int vint, int inertia) {
            // Continue objects own anim; bypass movement selection, preserving script-driven switches.
            sprite.setForcedAnimationId(sprite.getAnimationId());
            sprite.setGSpeed((short) inertia);
            animation.update(vint);
        }

        public void draw(int x, int y) {
            renderer.drawFrame(sprite.getMappingFrame(), x, y, sprite.getRenderHFlip(), sprite.getRenderVFlip());
        }
    }
}
