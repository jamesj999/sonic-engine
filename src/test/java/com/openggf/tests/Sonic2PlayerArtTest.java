package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.Sonic2PlayerArt;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.level.Pattern;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
public class Sonic2PlayerArtTest {
    private Rom rom;
    private RomByteReader reader;

    @BeforeEach
    public void setUp() throws Exception {
        rom = com.openggf.tests.TestEnvironment.currentRom();
        reader = RomByteReader.fromRom(rom);
    }

    @Test
    public void sonicMappingFramesMatchRev01() throws Exception {
        Sonic2PlayerArt artLoader = new Sonic2PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertEquals(214, sonic.mappingFrames().size());
        assertEquals(sonic.mappingFrames().size(), sonic.dplcFrames().size());
        assertEquals(Sonic2Constants.ART_UNC_SONIC_SIZE / Pattern.PATTERN_SIZE_IN_ROM, sonic.artTiles().length);
        assertFalse(sonic.mappingFrames().isEmpty());
        assertEquals(Sonic2Constants.SONIC_ANIM_SCRIPT_COUNT, sonic.animationSet().getScriptCount());
        assertTrue(((ScriptedVelocityAnimationProfile) sonic.animationProfile())
                .isWalkRunPublishesFrameBeforeTimerAdvance());
        assertTrue(((ScriptedVelocityAnimationProfile) sonic.animationProfile())
                .isPushUsesWalkSpecialHandler());
        assertEquals(Sonic2AnimationIds.HURT2.id(),
                ((ScriptedVelocityAnimationProfile) sonic.animationProfile()).getHurtAnimId());
    }

    @Test
    public void tailsMappingFramesMatchRev01() throws Exception {
        Sonic2PlayerArt artLoader = new Sonic2PlayerArt(reader);
        SpriteArtSet tails = artLoader.loadForCharacter("tails");

        assertEquals(139, tails.mappingFrames().size());
        assertEquals(tails.mappingFrames().size(), tails.dplcFrames().size());
        assertEquals(Sonic2Constants.ART_UNC_TAILS_SIZE / Pattern.PATTERN_SIZE_IN_ROM, tails.artTiles().length);
        assertFalse(tails.mappingFrames().isEmpty());
        assertFalse(((ScriptedVelocityAnimationProfile) tails.animationProfile())
                .isWalkRunPublishesFrameBeforeTimerAdvance());
        ScriptedVelocityAnimationProfile profile =
                (ScriptedVelocityAnimationProfile) tails.animationProfile();
        assertTrue(profile.isPushUsesWalkSpecialHandler());
        assertEquals(0x1F, profile.getHighSpeedWalkRunAnimId());
        assertEquals(0x700, profile.getHighSpeedWalkRunThreshold());
        assertEquals(4, profile.getWalkSlopeFrameStride());
        assertEquals(3, profile.getRunSlopeFrameStride());
        assertEquals(3, profile.getHighSpeedSlopeFrameStride());
        assertEquals(0x75, profile.getTumbleFrameBase());
        assertEquals(Sonic2AnimationIds.HURT2.id(), profile.getHurtAnimId());
        assertTrue(profile.isDoubleWalkRunAnimationSpeedWhenSliding());
        assertEquals(List.of(0x32, 0x33), tails.animationSet().getScript(0x1F).frames());
    }
}
