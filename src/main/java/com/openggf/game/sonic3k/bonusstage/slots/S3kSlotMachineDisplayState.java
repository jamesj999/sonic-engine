package com.openggf.game.sonic3k.bonusstage.slots;

public record S3kSlotMachineDisplayState(
        int worldX,
        int worldY,
        int[] faces,
        int[] nextFaces,
        float[] offsets
) {
    private static final int STATE_IDLE = 0x18;

    public static S3kSlotMachineDisplayState fromState(S3kSlotStageState state, int worldX, int worldY) {
        if (state == null) {
            return new S3kSlotMachineDisplayState(worldX, worldY, new int[] {0, 0, 0},
                    new int[] {0, 0, 0}, new float[] {0f, 0f, 0f});
        }
        if (state.optionCycleState() == STATE_IDLE && state.optionCycleLastPrize() != Integer.MIN_VALUE) {
            int[] faces = {
                    state.optionCycleTargetPackedBC() & 0x0F,
                    (state.optionCycleTargetPackedBC() >>> 4) & 0x0F,
                    state.optionCycleTargetReelA() & 0x0F
            };
            int[] nextFaces = {
                    nextSymbolFor(faces[0], S3kSlotRomData.REEL_SEQUENCE_A),
                    nextSymbolFor(faces[1], S3kSlotRomData.REEL_SEQUENCE_B),
                    nextSymbolFor(faces[2], S3kSlotRomData.REEL_SEQUENCE_C)
            };
            return new S3kSlotMachineDisplayState(worldX, worldY, faces, nextFaces, new float[] {0f, 0f, 0f});
        }
        int[] reelWords = state.optionCycleReelWords();
        if (hasActiveReelWords(reelWords)) {
            int[] faces = {
                    faceForReelWord(reelWords[0], S3kSlotRomData.REEL_SEQUENCE_A),
                    faceForReelWord(reelWords[1], S3kSlotRomData.REEL_SEQUENCE_B),
                    faceForReelWord(reelWords[2], S3kSlotRomData.REEL_SEQUENCE_C)
            };
            int[] nextFaces = {
                    nextFaceForReelWord(reelWords[0], S3kSlotRomData.REEL_SEQUENCE_A),
                    nextFaceForReelWord(reelWords[1], S3kSlotRomData.REEL_SEQUENCE_B),
                    nextFaceForReelWord(reelWords[2], S3kSlotRomData.REEL_SEQUENCE_C)
            };
            float[] offsets = {
                    ((reelWords[0] & 0xF8) / 256f),
                    ((reelWords[1] & 0xF8) / 256f),
                    ((reelWords[2] & 0xF8) / 256f)
            };
            return new S3kSlotMachineDisplayState(worldX, worldY, faces, nextFaces, offsets);
        }
        int[] faces = state.optionCycleDisplaySymbols().clone();
        int[] nextFaces = {
                nextSymbolFor(faces[0], S3kSlotRomData.REEL_SEQUENCE_A),
                nextSymbolFor(faces[1], S3kSlotRomData.REEL_SEQUENCE_B),
                nextSymbolFor(faces[2], S3kSlotRomData.REEL_SEQUENCE_C)
        };
        int[] rawOffsets = state.optionCycleOffsets();
        float[] offsets = {
                (rawOffsets[0] & 0xFF) / 256f,
                (rawOffsets[1] & 0xFF) / 256f,
                (rawOffsets[2] & 0xFF) / 256f
        };
        return new S3kSlotMachineDisplayState(worldX, worldY, faces, nextFaces, offsets);
    }

    static void syncPanelPatterns(S3kSlotStageState state, S3kSlotMachinePanelAnimator animator) {
        if (state == null) {
            animator.syncPanelPatterns(0, 0, 0, 0, 0, 0, 0f, 0f, 0f);
            return;
        }
        if (state.optionCycleState() == STATE_IDLE && state.optionCycleLastPrize() != Integer.MIN_VALUE) {
            int face0 = state.optionCycleTargetPackedBC() & 0x0F;
            int face1 = (state.optionCycleTargetPackedBC() >>> 4) & 0x0F;
            int face2 = state.optionCycleTargetReelA() & 0x0F;
            animator.syncPanelPatterns(
                    face0, face1, face2,
                    nextSymbolFor(face0, S3kSlotRomData.REEL_SEQUENCE_A),
                    nextSymbolFor(face1, S3kSlotRomData.REEL_SEQUENCE_B),
                    nextSymbolFor(face2, S3kSlotRomData.REEL_SEQUENCE_C),
                    0f, 0f, 0f);
            return;
        }
        int[] reelWords = state.optionCycleReelWords();
        if (hasActiveReelWords(reelWords)) {
            animator.syncPanelPatterns(
                    faceForReelWord(reelWords[0], S3kSlotRomData.REEL_SEQUENCE_A),
                    faceForReelWord(reelWords[1], S3kSlotRomData.REEL_SEQUENCE_B),
                    faceForReelWord(reelWords[2], S3kSlotRomData.REEL_SEQUENCE_C),
                    nextFaceForReelWord(reelWords[0], S3kSlotRomData.REEL_SEQUENCE_A),
                    nextFaceForReelWord(reelWords[1], S3kSlotRomData.REEL_SEQUENCE_B),
                    nextFaceForReelWord(reelWords[2], S3kSlotRomData.REEL_SEQUENCE_C),
                    (reelWords[0] & 0xF8) / 256f,
                    (reelWords[1] & 0xF8) / 256f,
                    (reelWords[2] & 0xF8) / 256f);
            return;
        }
        int[] faces = state.optionCycleDisplaySymbols();
        int[] rawOffsets = state.optionCycleOffsets();
        animator.syncPanelPatterns(
                faces[0], faces[1], faces[2],
                nextSymbolFor(faces[0], S3kSlotRomData.REEL_SEQUENCE_A),
                nextSymbolFor(faces[1], S3kSlotRomData.REEL_SEQUENCE_B),
                nextSymbolFor(faces[2], S3kSlotRomData.REEL_SEQUENCE_C),
                (rawOffsets[0] & 0xFF) / 256f,
                (rawOffsets[1] & 0xFF) / 256f,
                (rawOffsets[2] & 0xFF) / 256f);
    }

    private static boolean hasActiveReelWords(int[] reelWords) {
        return reelWords != null
                && (reelWords[0] != 0 || reelWords[1] != 0 || reelWords[2] != 0);
    }

    private static int faceForReelWord(int reelWord, byte[] sequence) {
        return sequence[(reelWord >>> 8) & 0x07] & 0xFF;
    }

    private static int nextFaceForReelWord(int reelWord, byte[] sequence) {
        return sequence[(((reelWord >>> 8) & 0x07) + 1) & 0x07] & 0xFF;
    }

    private static int nextSymbolFor(int currentSymbol, byte[] sequence) {
        if (sequence == null || sequence.length == 0) {
            return currentSymbol;
        }
        for (int i = 0; i < sequence.length; i++) {
            if ((sequence[i] & 0xFF) == (currentSymbol & 0xFF)) {
                return sequence[(i + 1) % sequence.length] & 0xFF;
            }
        }
        return currentSymbol & 0xFF;
    }
}
