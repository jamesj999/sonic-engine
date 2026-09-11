package com.openggf.audio.presentation;

import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoadReadiness;

public interface AudioPresentationDependencyResolver {
    interface DiagnosticTransaction {
        DiagnosticTransaction NONE = new DiagnosticTransaction() {
            @Override
            public void endPreparation() {
            }

            @Override
            public void commit() {
            }

            @Override
            public void discard() {
            }
        };

        /** Stops associating newly constructed voices with this transaction. */
        void endPreparation();

        /** Publishes every deferred callback in its original cross-observer order. */
        void commit();

        /** Drops deferred callbacks and suppresses cleanup callbacks from its voices. */
        void discard();
    }

    default DiagnosticTransaction beginDiagnosticTransaction() {
        return DiagnosticTransaction.NONE;
    }

    DecodedPcm resolvePcm(String assetId);

    default DacData resolveDac(SmpsSourceDescriptor source) {
        throw new IllegalStateException(
                "no cached DAC dependency for " + source);
    }

    default SmpsLoadReadiness resolveSmpsLoadReadiness(
            SmpsSourceDescriptor source) {
        return SmpsLoadReadiness.immediatePlan();
    }

    default PresentationVoice recreateVoice(
            AudioPresentationCommand.VoiceDescriptor descriptor) {
        if (descriptor instanceof AudioPresentationCommand.SampleVoiceDescriptor sample) {
            PresentationVoiceSnapshot.Sample snapshot = sample.snapshot();
            return SampleBackedVoice.restore(
                    snapshot, resolvePcm(snapshot.assetId()));
        }
        throw new IllegalArgumentException(
                "SMPS voices are recreated by the session owner");
    }

}
