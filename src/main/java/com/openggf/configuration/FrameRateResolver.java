package com.openggf.configuration;

public final class FrameRateResolver {
    private FrameRateResolver() {
    }

    public static int effective(SonicConfigurationService config) {
        return "PAL".equalsIgnoreCase(config.getString(SonicConfiguration.REGION))
                ? 50
                : Math.max(1, config.getInt(SonicConfiguration.FPS));
    }
}
