package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.save.SelectedTeam;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class S3kCustomTeamPortraitComposer {
    private static final int FRAME_SONIC = 5;
    private static final int FRAME_TAILS = 6;
    private static final int FRAME_KNUCKLES = 7;

    private static final int MAIN_SINGLE_X = -12;
    private static final int MAIN_SINGLE_WITH_SIDEKICK_X = -14;
    private static final int MAIN_SINGLE_BOTTOM = 0;
    private static final int MAIN_MULTI_X = 0;
    private static final int MAIN_MULTI_BOTTOM = 5;
    private static final int LEFT_SIDEKICK_X = -14;
    private static final int SINGLE_SIDEKICK_BOTTOM = 0;
    private static final int BOTTOM_ROW_BOTTOM = -5;
    private static final int RIGHT_SINGLE_SIDEKICK_X = 8;
    private static final int RIGHT_SIDEKICK_X = 14;
    private static final int EXTRA_STACK_X = 0;
    private static final int EXTRA_STACK_BOTTOM = -10;
    private static final int EXTRA_STACK_BOTTOM_STEP = 0;
    private static final int TARGET_SINGLE_SIDEKICK_OVERLAP = 10;

    private final List<SpriteMappingFrame> mappings;

    S3kCustomTeamPortraitComposer(List<SpriteMappingFrame> mappings) {
        this.mappings = List.copyOf(Objects.requireNonNull(mappings, "mappings"));
    }

    List<PortraitLayer> layoutLayers(SelectedTeam team) {
        if (team == null) {
            return List.of();
        }
        List<String> sidekicks = team.sidekicks();
        List<PortraitLayer> layers = new ArrayList<>();
        if (sidekicks.size() <= 1) {
            String main = canonical(team.mainCharacter());
            int mainX = sidekicks.isEmpty() ? MAIN_SINGLE_X : MAIN_SINGLE_WITH_SIDEKICK_X;
            layers.add(layerFor(main, mainX, MAIN_SINGLE_BOTTOM, shouldMirror(main, Position.LEFT)));
            if (!sidekicks.isEmpty()) {
                String sidekick = canonical(sidekicks.getFirst());
                layers.add(layerFor(sidekick, rightSingleSidekickX(mainX, main, sidekick), SINGLE_SIDEKICK_BOTTOM,
                        shouldMirror(sidekick, Position.RIGHT)));
            }
            return List.copyOf(layers);
        }

        String main = canonical(team.mainCharacter());
        String left = canonical(sidekicks.get(0));
        String right = canonical(sidekicks.get(1));
        layers.add(layerFor(main, MAIN_MULTI_X, MAIN_MULTI_BOTTOM, false));
        layers.add(layerFor(left, LEFT_SIDEKICK_X, BOTTOM_ROW_BOTTOM, shouldMirror(left, Position.LEFT)));
        layers.add(layerFor(right, RIGHT_SIDEKICK_X, BOTTOM_ROW_BOTTOM, shouldMirror(right, Position.RIGHT)));

        List<String> extras = uniqueOverflowCharacters(team);
        for (int i = 0; i < extras.size(); i++) {
            layers.add(layerFor(extras.get(i), EXTRA_STACK_X, EXTRA_STACK_BOTTOM + (i * EXTRA_STACK_BOTTOM_STEP),
                    false));
        }
        return List.copyOf(layers);
    }

    SpriteMappingFrame compose(SelectedTeam team) {
        List<SpriteMappingPiece> combined = new ArrayList<>();
        for (PortraitLayer layer : layoutLayers(team)) {
            SpriteMappingFrame frame = baseFrameFor(layer.characterCode());
            if (frame == null) {
                continue;
            }
            FrameBounds bounds = frameBounds(frame);
            for (SpriteMappingPiece piece : frame.pieces()) {
                int pieceWidthPixels = piece.widthTiles() * 8;
                int xOffset = layer.hFlip()
                        ? bounds.minX() + bounds.maxXExclusive() - piece.xOffset() - pieceWidthPixels + layer.xOffset()
                        : piece.xOffset() + layer.xOffset();
                combined.add(new SpriteMappingPiece(
                        xOffset,
                        piece.yOffset() + layer.yOffset(),
                        piece.widthTiles(),
                        piece.heightTiles(),
                        piece.tileIndex(),
                        layer.hFlip() ^ piece.hFlip(),
                        piece.vFlip(),
                        piece.paletteIndex(),
                        piece.priority()));
            }
        }
        return new SpriteMappingFrame(List.copyOf(combined));
    }

    boolean requiresCustomComposition(SelectedTeam team) {
        if (team == null) {
            return false;
        }
        String main = canonical(team.mainCharacter());
        List<String> sidekicks = team.sidekicks().stream().map(S3kCustomTeamPortraitComposer::canonical).toList();
        if ("knuckles".equals(main) && sidekicks.isEmpty()) {
            return false;
        }
        if ("tails".equals(main) && sidekicks.isEmpty()) {
            return false;
        }
        if ("sonic".equals(main) && sidekicks.isEmpty()) {
            return false;
        }
        return !("sonic".equals(main) && sidekicks.equals(List.of("tails")));
    }

    private PortraitLayer layerFor(String characterCode, int xOffset, int targetBottom, boolean hFlip) {
        SpriteMappingFrame frame = baseFrameFor(characterCode);
        if (frame == null) {
            return new PortraitLayer(characterCode, xOffset, targetBottom, hFlip);
        }
        FrameBounds bounds = frameBounds(frame);
        return new PortraitLayer(characterCode, xOffset, targetBottom - bounds.maxYExclusive(), hFlip);
    }

    private int rightSingleSidekickX(int mainX, String mainCharacter, String sidekickCharacter) {
        SpriteMappingFrame mainFrame = baseFrameFor(mainCharacter);
        SpriteMappingFrame sidekickFrame = baseFrameFor(sidekickCharacter);
        if (mainFrame == null || sidekickFrame == null) {
            return RIGHT_SINGLE_SIDEKICK_X;
        }
        FrameBounds mainBounds = frameBounds(mainFrame);
        FrameBounds sidekickBounds = frameBounds(sidekickFrame);
        if (mainBounds.minX() >= 0 && sidekickBounds.minX() >= 0) {
            return RIGHT_SINGLE_SIDEKICK_X;
        }
        int mainRight = mainX + mainBounds.maxXExclusive();
        return mainRight - TARGET_SINGLE_SIDEKICK_OVERLAP - sidekickBounds.minX();
    }

    private List<String> uniqueOverflowCharacters(SelectedTeam team) {
        List<String> remainder = team.sidekicks().stream()
                .skip(2)
                .map(S3kCustomTeamPortraitComposer::canonical)
                .toList();
        if (remainder.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>(remainder);
        List<String> extras = new ArrayList<>(ordered);
        String main = canonical(team.mainCharacter());
        if (extras.remove(main)) {
            extras.add(0, main);
        }
        return List.copyOf(extras);
    }

    private SpriteMappingFrame baseFrameFor(String characterCode) {
        int frameIndex = switch (canonical(characterCode)) {
            case "tails" -> FRAME_TAILS;
            case "knuckles" -> FRAME_KNUCKLES;
            default -> FRAME_SONIC;
        };
        if (frameIndex < 0 || frameIndex >= mappings.size()) {
            return null;
        }
        return mappings.get(frameIndex);
    }

    private static String canonical(String characterCode) {
        if (characterCode == null || characterCode.isBlank()) {
            return "sonic";
        }
        return characterCode.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean shouldMirror(String characterCode, Position position) {
        if (position == Position.CENTER) {
            return false;
        }
        String canonical = canonical(characterCode);
        return switch (canonical) {
            case "sonic" -> position == Position.RIGHT;
            case "tails" -> position != Position.CENTER;
            case "knuckles" -> position == Position.LEFT;
            default -> false;
        };
    }

    private static FrameBounds frameBounds(SpriteMappingFrame frame) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (SpriteMappingPiece piece : frame.pieces()) {
            minX = Math.min(minX, piece.xOffset());
            minY = Math.min(minY, piece.yOffset());
            maxX = Math.max(maxX, piece.xOffset() + (piece.widthTiles() * 8));
            maxY = Math.max(maxY, piece.yOffset() + (piece.heightTiles() * 8));
        }
        if (minX == Integer.MAX_VALUE) {
            return new FrameBounds(0, 0, 0, 0);
        }
        return new FrameBounds(minX, minY, maxX, maxY);
    }

    private enum Position {
        LEFT,
        RIGHT,
        CENTER
    }

    private record FrameBounds(int minX, int minY, int maxXExclusive, int maxYExclusive) {
    }

    record PortraitLayer(String characterCode, int xOffset, int yOffset, boolean hFlip) {
    }
}
