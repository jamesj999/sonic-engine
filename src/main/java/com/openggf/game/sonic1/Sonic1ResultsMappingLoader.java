package com.openggf.game.sonic1;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpriteMappingPieces;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Builds the composite S1 results-screen mapping set from ROM-backed tables.
 */
public final class Sonic1ResultsMappingLoader {

    private Sonic1ResultsMappingLoader() {
    }

    public static List<SpriteMappingFrame> load(RomByteReader reader) {
        return load(addr -> S1SpriteDataLoader.loadMappingFramesWithSignedOffsets(reader, addr));
    }

    public static List<SpriteMappingFrame> load(Function<Integer, List<SpriteMappingFrame>> mappingLoader) {
        List<SpriteMappingFrame> gotFrames = adjustFrames(
                mappingLoader.apply(Sonic1Constants.MAP_RESULTS_GOT_ADDR));
        List<SpriteMappingFrame> specialStageFrames = adjustFrames(
                mappingLoader.apply(Sonic1Constants.MAP_RESULTS_SPECIAL_STAGE_ADDR));
        if (gotFrames.size() < 9 || specialStageFrames.size() < 9) {
            return List.of();
        }

        SpriteMappingFrame scoreFrame = gotFrames.get(2);
        List<SpriteMappingFrame> frames = new ArrayList<>(13);
        frames.add(gotFrames.get(0));
        frames.add(gotFrames.get(1));
        frames.add(slice(scoreFrame, 0, 4));
        frames.add(gotFrames.get(3));
        frames.add(gotFrames.get(4));
        frames.add(gotFrames.get(5));
        frames.add(gotFrames.get(6));
        frames.add(gotFrames.get(7));
        frames.add(gotFrames.get(8));
        frames.add(slice(scoreFrame, 4, 6));
        frames.add(specialStageFrames.get(0));
        frames.add(specialStageFrames.get(7));
        frames.add(specialStageFrames.get(8));
        return List.copyOf(frames);
    }

    private static List<SpriteMappingFrame> adjustFrames(List<SpriteMappingFrame> frames) {
        return frames.stream()
                .map(Sonic1ResultsMappingLoader::adjustFrame)
                .toList();
    }

    private static SpriteMappingFrame adjustFrame(SpriteMappingFrame frame) {
        return new SpriteMappingFrame(frame.pieces().stream()
                .map(Sonic1ResultsMappingLoader::adjustPiece)
                .toList());
    }

    private static SpriteMappingPiece adjustPiece(SpriteMappingPiece piece) {
        int adjustedWord = (SpriteMappingPieces.toTileWord(piece) + Sonic1Constants.RESULTS_TILE_ADJUST) & 0xFFFF;
        return SpriteMappingPieces.withTileWord(piece, adjustedWord);
    }

    private static SpriteMappingFrame slice(SpriteMappingFrame frame, int fromIndex, int toIndex) {
        return new SpriteMappingFrame(List.copyOf(frame.pieces().subList(fromIndex, toIndex)));
    }
}
