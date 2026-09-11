package com.openggf.game.startup;

import com.openggf.game.GameId;

public record DataSelectPresentationResolution(
        boolean dataSelectEligible,
        GameId presentationGameId) {

    public boolean usesS3kPresentation() {
        return dataSelectEligible && presentationGameId == GameId.S3K;
    }
}
