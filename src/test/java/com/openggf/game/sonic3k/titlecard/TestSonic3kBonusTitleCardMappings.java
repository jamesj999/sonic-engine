package com.openggf.game.sonic3k.titlecard;

import com.openggf.game.titlecard.TitleCardMappings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies bonus title card mapping frames match Map - Title Card.asm lines 204-215.
 */
public class TestSonic3kBonusTitleCardMappings {

    @Test
    public void bonusFrameHasFivePieces() {
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_BONUS);
        assertEquals(5, pieces.length);
    }

    @Test
    public void stageFrameHasFivePieces() {
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_STAGE);
        assertEquals(5, pieces.length);
    }

    @Test
    public void competitionFrameSharesBonusData() {
        TitleCardMappings.SpritePiece[] competition = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_COMPETITION);
        TitleCardMappings.SpritePiece[] bonus = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_BONUS);
        assertSame(competition, bonus, "Competition and Bonus should share the same array");
    }

    @Test
    public void bonusFrameTileIndicesMatchDisasm() {
        // Map - Title Card.asm line 204-209: tiles $53, $28, $5F, $71, $65
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_BONUS);
        assertEquals(0x553, pieces[0].tileIndex());
        assertEquals(0x528, pieces[1].tileIndex());
        assertEquals(0x55F, pieces[2].tileIndex());
        assertEquals(0x571, pieces[3].tileIndex());
        assertEquals(0x565, pieces[4].tileIndex());
    }

    @Test
    public void stageFrameTileIndicesMatchDisasm() {
        // Map - Title Card.asm line 210-215: tiles $65, $6B, $4D, $59, $1C
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_STAGE);
        assertEquals(0x565, pieces[0].tileIndex());
        assertEquals(0x56B, pieces[1].tileIndex());
        assertEquals(0x54D, pieces[2].tileIndex());
        assertEquals(0x559, pieces[3].tileIndex());
        assertEquals(0x51C, pieces[4].tileIndex());
    }

    @Test
    public void existingZoneFramesUnchanged() {
        // Regression: AIZ frame still has 11 pieces
        assertEquals(11, Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_AIZ).length);
        // ZONE text still has 4 pieces
        assertEquals(4, Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_ZONE).length);
    }
}


