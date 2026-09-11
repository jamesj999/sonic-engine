package com.openggf.game.sonic1.dataselect;

import java.util.Map;

public record S1DataSelectImageManifest(
        String engineVersion,
        int generatorFormatVersion,
        String romSha256,
        String generatedAt,
        Map<String, String> zones) {
}
