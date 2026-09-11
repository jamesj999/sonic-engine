package com.openggf.game.sonic2.dataselect;

import java.util.Map;

public record S2DataSelectImageManifest(
        String engineVersion,
        int generatorFormatVersion,
        String romSha256,
        String generatedAt,
        Map<String, String> zones) {
}
