package com.openggf.audio.output;

import com.openggf.audio.presentation.AudioPresentationCommandQueue;
import com.openggf.audio.presentation.AudioPresentationMixer;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.PresentationMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestNoDeviceAudioSink {
    @Test
    void noDeviceSinkDoesNotAccumulateOrBackpressureAcrossOneHour() {
        int sampleRate = 60;
        int frameRate = 60;
        AudioVoiceRegistry registry = new AudioVoiceRegistry();
        NoDeviceAudioSink sink = new NoDeviceAudioSink(sampleRate);
        AudioPresentationProducer producer = new AudioPresentationProducer(
                sampleRate, frameRate, sampleRate, 0, registry,
                new AudioPresentationCommandQueue(registry::isRendering),
                new AudioPresentationMixer(1, registry::onVoiceFailure), sink);

        for (int frame = 0; frame < 60 * 60 * 60; frame++) {
            producer.present(frame, PresentationMode.SILENT);
        }

        assertEquals(sampleRate, sink.sampleRate());
        for (Field field : NoDeviceAudioSink.class.getDeclaredFields()) {
            assertFalse(field.getType().isArray(),
                    "discard sink must not retain packet storage");
            assertFalse(java.util.Collection.class.isAssignableFrom(field.getType()),
                    "discard sink must not retain an accumulating collection");
        }
    }
}
