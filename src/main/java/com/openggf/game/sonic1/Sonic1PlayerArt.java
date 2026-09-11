package com.openggf.game.sonic1;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.art.SpriteArtSet;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Loads Sonic 1 player sprite art, mappings, DPLCs, and animations from ROM.
 *
 * <p>Sonic 1 uses different data formats from Sonic 2:
 * <ul>
 *   <li>Mappings: SonicMappingsVer=1 — byte piece count, 5 bytes per piece
 *       (y:byte, size:byte, tileHi:byte, tileLo:byte, x:byte)</li>
 *   <li>DPLCs: SonicDplcVer=1 — byte entry count, same 2-byte entry format as S2
 *       (bits 12-15: count-1, bits 0-11: start tile)</li>
 *   <li>Art: Uncompressed (same as S2 Sonic art)</li>
 *   <li>Animations: Same script format as S2 (delay byte + frame IDs + end flags)</li>
 * </ul>
 */
public class Sonic1PlayerArt {
    private static final Logger LOG = Logger.getLogger(Sonic1PlayerArt.class.getName());

    private final RomByteReader reader;
    private SpriteArtSet cachedSonic;

    public Sonic1PlayerArt(RomByteReader reader) {
        this.reader = reader;
    }

    public SpriteArtSet loadForCharacter(String characterCode) throws IOException {
        if (characterCode == null) {
            return null;
        }
        String normalized = characterCode.trim().toLowerCase(Locale.ROOT);
        // Sonic 1 only has Sonic as a playable character
        if (!"sonic".equals(normalized)) {
            return null;
        }
        return loadSonic();
    }

    public SpriteArtSet loadSonic() throws IOException {
        if (cachedSonic != null) {
            return cachedSonic;
        }

        Pattern[] artTiles = loadArtTiles(
                Sonic1Constants.ART_UNC_SONIC_ADDR,
                Sonic1Constants.ART_UNC_SONIC_SIZE);

        List<SpriteMappingFrame> mappingFrames = loadS1MappingFrames(
                Sonic1Constants.MAP_SONIC_ADDR,
                Sonic1Constants.SONIC_MAPPING_FRAME_COUNT);

        List<SpriteDplcFrame> dplcFrames = loadS1DplcFrames(
                Sonic1Constants.DPLC_SONIC_ADDR,
                Sonic1Constants.SONIC_MAPPING_FRAME_COUNT);

        int bankSize = resolveBankSize(dplcFrames, mappingFrames);
        int paletteIndex = 0;
        int frameDelay = 1;

        SpriteAnimationSet animationSet = loadSonicAnimations();
        SpriteAnimationProfile animationProfile = new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(Sonic1AnimationIds.WAIT)
                .setWalkAnimId(Sonic1AnimationIds.WALK)
                .setRunAnimId(Sonic1AnimationIds.RUN)
                .setRunFramesUseWalkAnimationId(true)
                .setRollAnimId(Sonic1AnimationIds.ROLL)
                .setRoll2AnimId(Sonic1AnimationIds.ROLL2)
                .setPushAnimId(Sonic1AnimationIds.PUSH)
                .setPushUsesWalkSpecialHandler(true)
                .setDuckAnimId(Sonic1AnimationIds.DUCK)
                .setLookUpAnimId(Sonic1AnimationIds.LOOK_UP)
                .setSpringAnimId(Sonic1AnimationIds.SPRING)
                .setDeathAnimId(Sonic1AnimationIds.DEATH)
                .setDrownAnimId(Sonic1AnimationIds.DROWN)
                .setHurtAnimId(Sonic1AnimationIds.HURT)
                .setSkidAnimId(Sonic1AnimationIds.STOP)
                .setSlideAnimId(Sonic1AnimationIds.WATER_SLIDE)
                .setAirAnimId(Sonic1AnimationIds.WALK)
                .setBalanceAnimId(Sonic1AnimationIds.BALANCE)
                .setWalkSpeedThreshold(0x40)
                .setRunSpeedThreshold(0x600)
                .setFallbackFrame(0);

        cachedSonic = new SpriteArtSet(
                artTiles,
                mappingFrames,
                dplcFrames,
                paletteIndex,
                Sonic1Constants.ART_TILE_SONIC,
                frameDelay,
                bankSize,
                animationProfile,
                animationSet);

        LOG.info("Sonic 1 player art loaded: " + artTiles.length + " tiles, "
                + mappingFrames.size() + " mapping frames, "
                + dplcFrames.size() + " DPLC frames, "
                + animationSet.getScriptCount() + " animations");
        return cachedSonic;
    }

    private Pattern[] loadArtTiles(int artAddr, int artSize) throws IOException {
        return S1SpriteDataLoader.loadArtTiles(reader, artAddr, artSize);
    }

    /**
     * Loads S1-format sprite mappings.
     * Delegates to {@link S1SpriteDataLoader#loadMappingFrames(RomByteReader, int, int)}.
     */
    private List<SpriteMappingFrame> loadS1MappingFrames(int tableAddr, int frameCount) {
        return S1SpriteDataLoader.loadMappingFrames(reader, tableAddr, frameCount);
    }

    /**
     * Loads S1-format DPLCs.
     * Delegates to {@link S1SpriteDataLoader#loadDplcFrames(RomByteReader, int, int)}.
     */
    private List<SpriteDplcFrame> loadS1DplcFrames(int tableAddr, int frameCount) {
        return S1SpriteDataLoader.loadDplcFrames(reader, tableAddr, frameCount);
    }

    /**
     * Loads Sonic 1 animation scripts from ROM.
     * Delegates to {@link S1SpriteDataLoader#loadAnimationSet(RomByteReader, int, int)}.
     */
    private SpriteAnimationSet loadSonicAnimations() {
        return S1SpriteDataLoader.loadAnimationSet(reader,
                Sonic1Constants.SONIC_ANIM_DATA_ADDR,
                Sonic1Constants.SONIC_ANIM_SCRIPT_COUNT);
    }

    private int resolveBankSize(List<SpriteDplcFrame> dplcFrames, List<SpriteMappingFrame> mappingFrames) {
        return S1SpriteDataLoader.resolveBankSize(dplcFrames, mappingFrames);
    }
}
