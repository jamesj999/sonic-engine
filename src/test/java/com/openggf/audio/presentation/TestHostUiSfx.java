package com.openggf.audio.presentation;

import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the bootstrap UI fallback is real PCM, not a silent command. */
class TestHostUiSfx {

    @Test
    void fallbackCuesResolveToDistinctNonSilentPcm() throws Exception {
        AudioPresentationSourceFactory factory = new AudioPresentationSourceFactory(
                () -> true,
                new SmpsCoordFlagHandlerOwner(new SmpsCoordFlagRuntimeState()));

        SampleBackedVoice navigate = factory.fallbackSfx(1, "UI_NAVIGATE", 0, 1.0f);
        SampleBackedVoice confirm = factory.fallbackSfx(2, "UI_CONFIRM", 0, 1.0f);
        SampleBackedVoice error = factory.fallbackSfx(3, "UI_ERROR", 0, 1.0f);

        String navigateAsset = ((PresentationVoiceSnapshot.Sample) navigate.snapshot()).assetId();
        String confirmAsset = ((PresentationVoiceSnapshot.Sample) confirm.snapshot()).assetId();
        String errorAsset = ((PresentationVoiceSnapshot.Sample) error.snapshot()).assetId();
        assertEquals("host/ui/ui_navigate", navigateAsset);
        assertEquals("host/ui/ui_confirm", confirmAsset);
        assertEquals("host/ui/ui_error", errorAsset);
        DecodedPcm navigatePcm = factory.resolvePcm(navigateAsset);
        DecodedPcm confirmPcm = factory.resolvePcm(confirmAsset);
        DecodedPcm errorPcm = factory.resolvePcm(errorAsset);
        assertNotNull(navigatePcm);
        assertNotNull(confirmPcm);
        assertNotNull(errorPcm);
        assertSame(navigatePcm, factory.resolvePcm(navigateAsset));
        assertSame(confirmPcm, factory.resolvePcm(confirmAsset));
        assertSame(errorPcm, factory.resolvePcm(errorAsset));
        assertFalse(Arrays.equals(
                navigatePcm.copySamples(), confirmPcm.copySamples()));
        assertFalse(Arrays.equals(
                confirmPcm.copySamples(), errorPcm.copySamples()));

        for (SampleBackedVoice voice : new SampleBackedVoice[]{navigate, confirm, error}) {
            long[] mixed = new long[2 * 512];
            voice.mixInto(mixed, 512);
            assertTrue(Arrays.stream(mixed).anyMatch(sample -> sample != 0),
                    "host cue must mix to nonzero PCM: " + voice.snapshot());
        }
    }
}
