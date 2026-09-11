package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.save.SelectedTeam;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kCustomTeamPortraitComposer {

    @Test
    void layoutLayers_singleSidekick_placesMainLeftAndSidekickRight() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        List<S3kCustomTeamPortraitComposer.PortraitLayer> layers =
                composer.layoutLayers(new SelectedTeam("knuckles", List.of("tails")));

        assertEquals(List.of(
                new S3kCustomTeamPortraitComposer.PortraitLayer("knuckles", -14, -8, true),
                new S3kCustomTeamPortraitComposer.PortraitLayer("tails", 8, -8, true)
        ), layers);
    }

    @Test
    void layoutLayers_singleSidekick_usesBoundsAwareSpacingForKnucklesAndTails() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(realisticSingleSidekickFrames());

        List<S3kCustomTeamPortraitComposer.PortraitLayer> layers =
                composer.layoutLayers(new SelectedTeam("knuckles", List.of("tails")));

        assertEquals(new S3kCustomTeamPortraitComposer.PortraitLayer("knuckles", -14, 0, true), layers.get(0));
        assertEquals(new S3kCustomTeamPortraitComposer.PortraitLayer("tails", 20, 0, true), layers.get(1));
    }

    @Test
    void layoutLayers_twoSidekicks_offsetsMainDownAndBothSidekicksUp() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        List<S3kCustomTeamPortraitComposer.PortraitLayer> layers =
                composer.layoutLayers(new SelectedTeam("sonic", List.of("knuckles", "tails")));

        assertEquals(List.of(
                new S3kCustomTeamPortraitComposer.PortraitLayer("sonic", 0, -3, false),
                new S3kCustomTeamPortraitComposer.PortraitLayer("knuckles", -14, -13, true),
                new S3kCustomTeamPortraitComposer.PortraitLayer("tails", 14, -13, true)
        ), layers);
    }

    @Test
    void layoutLayers_overflowSidekicks_deduplicatesRemainderAndPlacesOverflowHigher() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        List<S3kCustomTeamPortraitComposer.PortraitLayer> layers =
                composer.layoutLayers(new SelectedTeam(
                        "sonic",
                        List.of("tails", "knuckles", "tails", "sonic", "knuckles", "tails", "tails")));

        assertEquals(List.of(
                new S3kCustomTeamPortraitComposer.PortraitLayer("sonic", 0, -3, false),
                new S3kCustomTeamPortraitComposer.PortraitLayer("tails", -14, -13, true),
                new S3kCustomTeamPortraitComposer.PortraitLayer("knuckles", 14, -13, false),
                new S3kCustomTeamPortraitComposer.PortraitLayer("sonic", 0, -18, false),
                new S3kCustomTeamPortraitComposer.PortraitLayer("tails", 0, -18, false),
                new S3kCustomTeamPortraitComposer.PortraitLayer("knuckles", 0, -18, false)
        ), layers);
    }

    @Test
    void compose_translatesAndFlipsBaseCharacterPiecesInFrontToBackOrder() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        SpriteMappingFrame frame = composer.compose(new SelectedTeam("sonic", List.of("knuckles", "tails")));

        assertEquals(List.of(
                new SpriteMappingPiece(0, -3, 1, 1, 100, false, false, 1, false),
                new SpriteMappingPiece(-14, -13, 1, 1, 300, true, false, 1, false),
                new SpriteMappingPiece(14, -13, 1, 1, 200, true, false, 1, false)
        ), frame.pieces());
    }

    @Test
    void layoutLayers_soloCustomCompositionWouldNotMirrorCenteredCharacter() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        List<S3kCustomTeamPortraitComposer.PortraitLayer> layers =
                composer.layoutLayers(new SelectedTeam("sonic", List.of()));

        assertEquals(List.of(
                new S3kCustomTeamPortraitComposer.PortraitLayer("sonic", -12, -8, false)
        ), layers);
    }

    @Test
    void compose_flipsCharactersBasedOnLeftRightRules() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        SpriteMappingFrame frame = composer.compose(new SelectedTeam("tails", List.of("sonic")));

        assertEquals(2, frame.pieces().size());
        assertTrue(frame.pieces().get(0).hFlip(), "Tails on the left should mirror");
        assertTrue(frame.pieces().get(1).hFlip(), "Sonic on the right should mirror");
    }

    @Test
    void compose_wholeFrameMirroringRepositionsPiecesInsteadOfTearingThemApart() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(multiPieceFrames());

        SpriteMappingFrame frame = composer.compose(new SelectedTeam("tails", List.of("sonic")));

        assertEquals(List.of(
                new SpriteMappingPiece(-6, -8, 1, 1, 210, true, false, 1, false),
                new SpriteMappingPiece(-14, -8, 1, 1, 211, true, false, 1, false),
                new SpriteMappingPiece(16, -8, 1, 1, 110, true, false, 1, false),
                new SpriteMappingPiece(8, -8, 1, 1, 111, true, false, 1, false)
        ), frame.pieces());
    }

    @Test
    void compose_bottomAlignsCharactersUsingTheirOwnFrameBounds() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(variableHeightFrames());

        SpriteMappingFrame frame = composer.compose(new SelectedTeam("sonic", List.of("tails", "knuckles")));

        assertEquals(5, maxBottom(frame.pieces().subList(0, 1)));
        assertEquals(-5, maxBottom(frame.pieces().subList(1, 2)));
        assertEquals(-5, maxBottom(frame.pieces().subList(2, 3)));
    }

    @Test
    void layoutLayers_fourSidekicks_keepsFirstThreeOnDefinedRowsAndMovesOverflowHigher() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        List<S3kCustomTeamPortraitComposer.PortraitLayer> layers =
                composer.layoutLayers(new SelectedTeam("sonic", List.of("tails", "knuckles", "sonic", "tails")));

        assertEquals(-3, layers.get(0).yOffset());
        assertEquals(-13, layers.get(1).yOffset());
        assertEquals(-13, layers.get(2).yOffset());
        assertEquals(-18, layers.get(3).yOffset());
        assertEquals(-18, layers.get(4).yOffset());
        assertFalse(layers.get(3).hFlip());
        assertFalse(layers.get(4).hFlip());
    }

    @Test
    void compose_singleSidekickRemainsVisuallyCentered() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(multiPieceFrames());

        SpriteMappingFrame frame = composer.compose(new SelectedTeam("tails", List.of("sonic")));

        assertTrue(Math.abs(horizontalMidpoint(frame)) <= 5,
                "single-sidekick compositions should remain centered as a pair");
    }

    @Test
    void compose_twoSidekicksRemainsVisuallyCentered() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        SpriteMappingFrame frame = composer.compose(new SelectedTeam("sonic", List.of("knuckles", "tails")));

        assertTrue(Math.abs(horizontalMidpoint(frame)) <= 5,
                "two-sidekick compositions should remain centered overall");
    }

    @Test
    void compose_overflowSidekicksRemainsVisuallyCentered() {
        S3kCustomTeamPortraitComposer composer = new S3kCustomTeamPortraitComposer(stubFrames());

        SpriteMappingFrame frame = composer.compose(new SelectedTeam(
                "sonic",
                List.of("tails", "knuckles", "tails", "sonic", "knuckles", "tails")));

        assertTrue(Math.abs(horizontalMidpoint(frame)) <= 5,
                "overflow sidekick compositions should remain centered overall");
    }

    private static List<SpriteMappingFrame> stubFrames() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            frames.add(new SpriteMappingFrame(List.of()));
        }
        frames.set(5, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 100, false, false, 1, false)
        )));
        frames.set(6, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 200, false, false, 1, false)
        )));
        frames.set(7, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 300, false, false, 1, false)
        )));
        return frames;
    }

    private static List<SpriteMappingFrame> multiPieceFrames() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            frames.add(new SpriteMappingFrame(List.of()));
        }
        frames.set(5, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 110, false, false, 1, false),
                new SpriteMappingPiece(8, 0, 1, 1, 111, false, false, 1, false)
        )));
        frames.set(6, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 210, false, false, 1, false),
                new SpriteMappingPiece(8, 0, 1, 1, 211, false, false, 1, false)
        )));
        frames.set(7, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 310, false, false, 1, false)
        )));
        return frames;
    }

    private static List<SpriteMappingFrame> variableHeightFrames() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            frames.add(new SpriteMappingFrame(List.of()));
        }
        frames.set(5, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 0, 1, 1, 100, false, false, 1, false)
        )));
        frames.set(6, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, -4, 1, 2, 200, false, false, 1, false)
        )));
        frames.set(7, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(0, 2, 1, 1, 300, false, false, 1, false)
        )));
        return frames;
    }

    private static List<SpriteMappingFrame> realisticSingleSidekickFrames() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            frames.add(new SpriteMappingFrame(List.of()));
        }
        frames.set(6, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(-25, -40, 5, 5, 200, false, false, 1, false)
        )));
        frames.set(7, new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(-13, -40, 4, 5, 300, false, false, 1, false)
        )));
        return frames;
    }

    private static int maxBottom(List<SpriteMappingPiece> pieces) {
        return pieces.stream()
                .mapToInt(piece -> piece.yOffset() + (piece.heightTiles() * 8))
                .max()
                .orElse(Integer.MIN_VALUE);
    }

    private static int horizontalMidpoint(SpriteMappingFrame frame) {
        int minX = frame.pieces().stream()
                .mapToInt(SpriteMappingPiece::xOffset)
                .min()
                .orElse(0);
        int maxX = frame.pieces().stream()
                .mapToInt(piece -> piece.xOffset() + (piece.widthTiles() * 8))
                .max()
                .orElse(0);
        return (minX + maxX) / 2;
    }
}
