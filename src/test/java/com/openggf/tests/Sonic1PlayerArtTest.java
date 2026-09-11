package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.Sonic1PlayerArt;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Sonic 1 player sprite art loading.
 * Uses {@link RequiresRom} annotation Ã¢â‚¬â€ TestEnvironment.resetAll() in
 * The shared ROM fixture prevents S1 module/loader state from leaking to subsequent tests.
 */
@RequiresRom(SonicGame.SONIC_1)
public class Sonic1PlayerArtTest {
    private RomByteReader reader;

    @BeforeEach
    public void setUp() throws Exception {
        reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
    }

    @Test
    public void sonicArtLoadsCorrectTileCount() throws Exception {
        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic, "SpriteArtSet should not be null");
        int expectedTiles = Sonic1Constants.ART_UNC_SONIC_SIZE / Pattern.PATTERN_SIZE_IN_ROM;
        assertEquals(expectedTiles, sonic.artTiles().length, "Tile count should match ROM art size");
        assertTrue(((ScriptedVelocityAnimationProfile) sonic.animationProfile())
                .isPushUsesWalkSpecialHandler());
    }

    @Test
    public void sonicMappingFrameCount() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic);
        assertEquals(Sonic1Constants.SONIC_MAPPING_FRAME_COUNT, sonic.mappingFrames().size(), "Should have 88 mapping frames");
    }

    @Test
    public void sonicDplcFrameCountMatchesMappings() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic);
        assertEquals(sonic.mappingFrames().size(), sonic.dplcFrames().size(), "DPLC and mapping frame counts should match");
    }

    @Test
    public void sonicAnimationScriptCount() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic);
        assertNotNull(sonic.animationSet(), "Animation set should not be null");
        assertEquals(Sonic1Constants.SONIC_ANIM_SCRIPT_COUNT, sonic.animationSet().getScriptCount(), "Should have 31 animation scripts");
    }

    @Test
    public void standingFrameHasFourPieces() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic);
        // Frame 1 = MS_Stand (from s1disasm _maps/Sonic.asm)
        SpriteMappingFrame standFrame = sonic.mappingFrames().get(1);
        assertEquals(4, standFrame.pieces().size(), "Standing frame should have 4 pieces");

        // First piece of standing frame: y=-0x14, w=3, h=1, tile=0, x=-0x10
        var firstPiece = standFrame.pieces().get(0);
        assertEquals(-0x14, firstPiece.yOffset());
        assertEquals(3, firstPiece.widthTiles());
        assertEquals(1, firstPiece.heightTiles());
        assertEquals(0, firstPiece.tileIndex());
        assertEquals(-0x10, firstPiece.xOffset());
    }

    @Test
    public void walkAnimationHasSixFrames() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic);
        // Walk animation (id 0) has 6 frames: Walk13,14,15,16,11,12
        var walkScript = sonic.animationSet().getScript(0);
        assertNotNull(walkScript, "Walk animation script should exist");
        assertEquals(6, walkScript.frames().size(), "Walk should have 6 frames");
        // fr_Walk13=8, fr_Walk14=9, fr_Walk15=0xA, fr_Walk16=0xB, fr_Walk11=6, fr_Walk12=7
        assertEquals(8, (int) walkScript.frames().get(0));
        assertEquals(9, (int) walkScript.frames().get(1));
        assertEquals(0x0A, (int) walkScript.frames().get(2));
        assertEquals(0x0B, (int) walkScript.frames().get(3));
        assertEquals(6, (int) walkScript.frames().get(4));
        assertEquals(7, (int) walkScript.frames().get(5));
    }

    @Test
    public void nullFrameHasZeroPieces() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic);
        // Frame 0 = MS_Null (empty frame)
        SpriteMappingFrame nullFrame = sonic.mappingFrames().get(0);
        assertEquals(0, nullFrame.pieces().size(), "Null frame should have 0 pieces");
    }

    @Test
    public void unknownCharacterReturnsNull() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);

        assertNull(artLoader.loadForCharacter("tails"), "Tails should return null for S1");
        assertNull(artLoader.loadForCharacter("knuckles"), "Unknown character should return null");
    }

    @Test
    public void bankSizeIsPositive() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic);
        assertTrue(sonic.bankSize() > 0, "Bank size should be positive");
    }

    @Test
    public void animationProfileIsSet() throws Exception {

        Sonic1PlayerArt artLoader = new Sonic1PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertNotNull(sonic);
        assertNotNull(sonic.animationProfile(), "Animation profile should be set");
    }
}

