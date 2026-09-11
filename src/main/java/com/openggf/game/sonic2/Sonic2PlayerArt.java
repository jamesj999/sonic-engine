package com.openggf.game.sonic2;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;

import com.openggf.data.RomByteReader;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.util.PatternDecompressor;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.art.SpriteArtSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads Sonic/Tails sprite art, mappings, and DPLCs for Sonic 2 (REV01).
 */
public class Sonic2PlayerArt {
    private final RomByteReader reader;
    private SpriteArtSet cachedSonic;
    private SpriteArtSet cachedTails;

    public Sonic2PlayerArt(RomByteReader reader) {
        this.reader = reader;
    }

    public SpriteArtSet loadForCharacter(String characterCode) throws IOException {
        if (characterCode == null) {
            return null;
        }
        String normalized = characterCode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tails" -> loadTails();
            case "sonic" -> loadSonic();
            default -> null;
        };
    }

    public SpriteArtSet loadSonic() throws IOException {
        if (cachedSonic != null) {
            return cachedSonic;
        }
        cachedSonic = loadCharacter(
                Sonic2Constants.ART_UNC_SONIC_ADDR,
                Sonic2Constants.ART_UNC_SONIC_SIZE,
                Sonic2Constants.MAP_UNC_SONIC_ADDR,
                Sonic2Constants.MAP_R_UNC_SONIC_ADDR,
                Sonic2Constants.ART_TILE_SONIC);
        return cachedSonic;
    }

    public SpriteArtSet loadTails() throws IOException {
        if (cachedTails != null) {
            return cachedTails;
        }
        cachedTails = loadCharacter(
                Sonic2Constants.ART_UNC_TAILS_ADDR,
                Sonic2Constants.ART_UNC_TAILS_SIZE,
                Sonic2Constants.MAP_UNC_TAILS_ADDR,
                Sonic2Constants.MAP_R_UNC_TAILS_ADDR,
                Sonic2Constants.ART_TILE_TAILS);
        return cachedTails;
    }

    private SpriteArtSet loadCharacter(
            int artAddr,
            int artSize,
            int mappingAddr,
            int dplcAddr,
            int basePatternIndex) throws IOException {
        Pattern[] artTiles = loadArtTiles(artAddr, artSize);
        List<SpriteMappingFrame> mappingFrames = loadMappingFrames(mappingAddr);
        List<SpriteDplcFrame> dplcFrames = parseDplcFrames(reader, dplcAddr);

        int bankSize = resolveBankSize(dplcFrames, mappingFrames);
        int frameDelay = 1;
        int paletteIndex = 0;
        SpriteAnimationSet animationSet;
        if (basePatternIndex == Sonic2Constants.ART_TILE_SONIC) {
            animationSet = loadSonicAnimations();
        } else if (basePatternIndex == Sonic2Constants.ART_TILE_TAILS) {
            animationSet = loadTailsAnimations();
        } else {
            animationSet = null;
        }
        // Hurt_Sidekick writes animation $1A for both Sonic and Tails
        // (s2.asm:85497-85519). Tails' $19 entry is death-like frame $5D,
        // while $1A is the distinct hurt frame $5C.
        int hurtAnimId = Sonic2AnimationIds.HURT2.id();

        boolean isSonic = basePatternIndex != Sonic2Constants.ART_TILE_TAILS;
        SpriteAnimationProfile animationProfile = new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(Sonic2AnimationIds.WAIT)
                .setWalkAnimId(Sonic2AnimationIds.WALK)
                .setRunAnimId(Sonic2AnimationIds.RUN)
                .setRunFramesUseWalkAnimationId(true)
                .setRollAnimId(Sonic2AnimationIds.ROLL)
                .setRoll2AnimId(Sonic2AnimationIds.ROLL2)
                .setPushAnimId(Sonic2AnimationIds.PUSH)
                .setPushUsesWalkSpecialHandler(true)
                .setDuckAnimId(Sonic2AnimationIds.DUCK)
                .setLookUpAnimId(Sonic2AnimationIds.LOOK_UP)
                .setSpindashAnimId(Sonic2AnimationIds.SPINDASH)
                .setSpringAnimId(Sonic2AnimationIds.SPRING)
                .setDeathAnimId(Sonic2AnimationIds.DEATH)
                .setDrownAnimId(Sonic2AnimationIds.DROWN)
                .setHurtAnimId(hurtAnimId)
                .setSkidAnimId(Sonic2AnimationIds.SKID)
                .setSlideAnimId(Sonic2AnimationIds.SLIDE)
                .setAirAnimId(Sonic2AnimationIds.WALK)
                .setBalanceAnimId(Sonic2AnimationIds.BALANCE)
                .setBalance2AnimId(Sonic2AnimationIds.BALANCE2)
                .setBalance3AnimId(Sonic2AnimationIds.BALANCE3)
                .setBalance4AnimId(Sonic2AnimationIds.BALANCE4)
                .setWalkSpeedThreshold(0x40)
                .setRunSpeedThreshold(0x600)
                .setFallbackFrame(0)
                .setAnglePreAdjust(true)
                .setDoubleWalkRunAnimationSpeedWhenSliding(true);
        if (isSonic) {
            // ROM Obj01_MdNormal_Checks (s2.asm:36444-36468) is Sonic-only:
            // Obj02 (Tails) has no impatient-wait blink/get-up interrupt.
            ((ScriptedVelocityAnimationProfile) animationProfile)
                    .setBlinkAnimId(Sonic2AnimationIds.BLINK)
                    .setGetUpAnimId(Sonic2AnimationIds.GET_UP)
                    .setCompactSuperRunSlope(true)
                    // SAnim_SuperWalk's alternate bright frames, s2.asm:38534-38539.
                    .setSuperAlternateFrameStep(3, 0x20, 0xB5)
                    .setWalkRunPublishesFrameBeforeTimerAdvance(true);
        } else {
            // TAnim_WalkRunZoom keeps raw anim=Walk while selecting its private
            // TailsAni_HaulAss pointer at or above $700. Its angle-bank strides are
            // walk*4, run*3, haul-ass*3 (s2.asm:41366-41401).
            ((ScriptedVelocityAnimationProfile) animationProfile)
                    .setHighSpeedWalkRunAnimId(0x1F)
                    .setHighSpeedWalkRunThreshold(0x700)
                    .setWalkSlopeFrameStride(4)
                    .setRunSlopeFrameStride(3)
                    .setHighSpeedSlopeFrameStride(3)
                    .setTumbleFrameBase(0x75); // TAnim_Tumble, s2.asm:41405-41435
        }

        return new SpriteArtSet(
                artTiles,
                mappingFrames,
                dplcFrames,
                paletteIndex,
                basePatternIndex,
                frameDelay,
                bankSize,
                animationProfile,
                animationSet);
    }

    private Pattern[] loadArtTiles(int artAddr, int artSize) throws IOException {
        return PatternDecompressor.uncompressed(reader, artAddr, artSize);
    }

    private List<SpriteMappingFrame> loadMappingFrames(int mappingAddr) {
        int offsetTableSize = reader.readU16BE(mappingAddr);
        int frameCount = offsetTableSize / 2;
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = mappingAddr + reader.readU16BE(mappingAddr + i * 2);
            int pieceCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<SpriteMappingPiece> pieces = new ArrayList<>(pieceCount);
            for (int p = 0; p < pieceCount; p++) {
                int yOffset = (byte) reader.readU8(frameAddr);
                frameAddr += 1;
                int size = reader.readU8(frameAddr);
                frameAddr += 1;
                int tileWord = reader.readU16BE(frameAddr);
                frameAddr += 2;
                frameAddr += 2; // 2P tile word, unused in 1P.
                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = tileWord & 0x7FF;
                boolean hFlip = (tileWord & 0x800) != 0;
                boolean vFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;

                pieces.add(new SpriteMappingPiece(
                        xOffset, yOffset, widthTiles, heightTiles, tileIndex, hFlip, vFlip, paletteIndex));
            }
            frames.add(new SpriteMappingFrame(pieces));
        }
        return frames;
    }

    /**
     * Parses DPLC frames from ROM at the given address.
     * Exposed as package-private static so the ending cutscene renderer can reuse it.
     */
    public static List<SpriteDplcFrame> parseDplcFrames(RomByteReader reader, int dplcAddr) {
        int offsetTableSize = reader.readU16BE(dplcAddr);
        int frameCount = offsetTableSize / 2;
        List<SpriteDplcFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = dplcAddr + reader.readU16BE(dplcAddr + i * 2);
            int requestCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<TileLoadRequest> requests = new ArrayList<>(requestCount);
            for (int r = 0; r < requestCount; r++) {
                int entry = reader.readU16BE(frameAddr);
                frameAddr += 2;
                int count = ((entry >> 12) & 0xF) + 1;
                int startTile = entry & 0x0FFF;
                requests.add(new TileLoadRequest(startTile, count));
            }
            frames.add(new SpriteDplcFrame(requests));
        }
        return frames;
    }

    private SpriteAnimationSet loadSonicAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        int base = Sonic2Constants.SONIC_ANIM_DATA_ADDR;
        int count = Sonic2Constants.SONIC_ANIM_SCRIPT_COUNT;

        for (int i = 0; i < count; i++) {
            int scriptAddr = base + reader.readU16BE(base + i * 2);
            int delay = reader.readU8(scriptAddr);
            scriptAddr += 1;

            List<Integer> frames = new ArrayList<>();
            SpriteAnimationEndAction endAction = SpriteAnimationEndAction.LOOP;
            int endParam = 0;

            while (true) {
                int value = reader.readU8(scriptAddr);
                scriptAddr += 1;
                if (value >= 0xF0) {
                    if (value == 0xFF) {
                        endAction = SpriteAnimationEndAction.LOOP;
                        break;
                    }
                    if (value == 0xFE) {
                        endAction = SpriteAnimationEndAction.LOOP_BACK;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    if (value == 0xFD) {
                        endAction = SpriteAnimationEndAction.SWITCH;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    endAction = SpriteAnimationEndAction.HOLD;
                    break;
                }
                frames.add(value);
            }

            set.addScript(i, new SpriteAnimationScript(delay, frames, endAction, endParam));
        }
        return set;
    }

    /**
     * Loads Super Sonic animation scripts from ROM.
     * Returns null if the ROM address is not yet known (SUPER_SONIC_ANIM_DATA_ADDR == 0).
     */
    /**
     * Loads the Super Sonic animation set, merged with normal Sonic animations.
     *
     * <p>SuperSonicAniData has entries that reference unique SupSon scripts (walk, run,
     * wait, balance, look up) and entries that back-reference SonicAniData scripts
     * (roll, duck, spring, etc.) via negative offsets. Back-references are detected
     * by their large unsigned offset (>= 0x8000) and skipped - the normal Sonic
     * script is used instead.
     *
     * @return merged animation set, or null if ROM address is unknown
     */
    public SpriteAnimationSet loadSuperSonicAnimationSet() {
        int base = Sonic2Constants.SUPER_SONIC_ANIM_DATA_ADDR;
        int count = Sonic2Constants.SUPER_SONIC_ANIM_SCRIPT_COUNT;
        if (base == 0) {
            return null;
        }

        // Start with a copy of normal Sonic animations as fallback
        SpriteAnimationSet normalSet = loadSonicAnimations();
        SpriteAnimationSet set = new SpriteAnimationSet();
        for (var entry : normalSet.getAllScripts().entrySet()) {
            set.addScript(entry.getKey(), entry.getValue());
        }

        // Overlay unique Super Sonic scripts (skip back-references to SonicAniData)
        int ptrTableSize = count * 2;
        for (int i = 0; i < count; i++) {
            int offset = reader.readU16BE(base + i * 2);
            // Back-references to SonicAniData are negative offsets stored as unsigned,
            // so they appear as values >= 0x8000. Also skip offsets smaller than the
            // pointer table size (would overlap the table itself).
            if (offset >= 0x8000 || offset < ptrTableSize) {
                continue;
            }
            int scriptAddr = base + offset;
            if (scriptAddr + 2 > reader.size()) {
                continue;
            }

            int delay = reader.readU8(scriptAddr);
            scriptAddr += 1;

            List<Integer> frames = new ArrayList<>();
            SpriteAnimationEndAction endAction = SpriteAnimationEndAction.LOOP;
            int endParam = 0;

            while (true) {
                int value = reader.readU8(scriptAddr);
                scriptAddr += 1;
                if (value >= 0xF0) {
                    if (value == 0xFF) {
                        endAction = SpriteAnimationEndAction.LOOP;
                        break;
                    }
                    if (value == 0xFE) {
                        endAction = SpriteAnimationEndAction.LOOP_BACK;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    if (value == 0xFD) {
                        endAction = SpriteAnimationEndAction.SWITCH;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    endAction = SpriteAnimationEndAction.HOLD;
                    break;
                }
                frames.add(value);
            }

            set.addScript(i, new SpriteAnimationScript(delay, frames, endAction, endParam));
        }
        return set;
    }

    private SpriteAnimationSet loadTailsAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        int base = Sonic2Constants.TAILS_ANIM_DATA_ADDR;
        int count = Sonic2Constants.TAILS_ANIM_SCRIPT_COUNT;

        for (int i = 0; i < count; i++) {
            int scriptAddr = base + reader.readU16BE(base + i * 2);
            int delay = reader.readU8(scriptAddr);
            scriptAddr += 1;

            List<Integer> frames = new ArrayList<>();
            SpriteAnimationEndAction endAction = SpriteAnimationEndAction.LOOP;
            int endParam = 0;

            while (true) {
                int value = reader.readU8(scriptAddr);
                scriptAddr += 1;
                if (value >= 0xF0) {
                    if (value == 0xFF) {
                        endAction = SpriteAnimationEndAction.LOOP;
                        break;
                    }
                    if (value == 0xFE) {
                        endAction = SpriteAnimationEndAction.LOOP_BACK;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    if (value == 0xFD) {
                        endAction = SpriteAnimationEndAction.SWITCH;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    endAction = SpriteAnimationEndAction.HOLD;
                    break;
                }
                frames.add(value);
            }

            set.addScript(i, new SpriteAnimationScript(delay, frames, endAction, endParam));
        }
        return set;
    }

    public static int resolveBankSize(List<SpriteDplcFrame> dplcFrames, List<SpriteMappingFrame> mappingFrames) {
        int maxTiles = 0;
        for (SpriteDplcFrame frame : dplcFrames) {
            int total = 0;
            for (TileLoadRequest request : frame.requests()) {
                total += Math.max(0, request.count());
            }
            maxTiles = Math.max(maxTiles, total);
        }

        if (maxTiles > 0) {
            return maxTiles;
        }

        int maxIndex = 0;
        for (SpriteMappingFrame frame : mappingFrames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                int tileCount = piece.widthTiles() * piece.heightTiles();
                maxIndex = Math.max(maxIndex, piece.tileIndex() + tileCount);
            }
        }
        return Math.max(0, maxIndex);
    }

}
