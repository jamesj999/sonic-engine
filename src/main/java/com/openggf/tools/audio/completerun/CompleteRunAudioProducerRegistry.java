package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioProducer.Request;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import java.util.Arrays;

/** Closed profile-to-class dispatcher. There is deliberately no registration or replacement seam. */
final class CompleteRunAudioProducerRegistry {
    enum Game {
        S1("s1_rev01_complete_emeralds.v1",
                "com.openggf.tools.audio.completerun.s1.S1CompleteRunAudioProfile",
                "com.openggf.tools.audio.completerun.s1.S1CompleteRunReferenceProducer",
                "com.openggf.tools.audio.completerun.s1.S1CompleteRunOpenGgfProducer"),
        S2("s2_rev01_complete_emeralds.v1",
                "com.openggf.tools.audio.completerun.s2.S2CompleteRunAudioProfile",
                "com.openggf.tools.audio.completerun.s2.S2CompleteRunReferenceProducer",
                "com.openggf.tools.audio.completerun.s2.S2CompleteRunOpenGgfProducer"),
        S3K("s3k_locked_on_knuckles_superemeralds.v1",
                "com.openggf.tools.audio.completerun.s3k.S3kCompleteRunAudioProfile",
                "com.openggf.tools.audio.completerun.s3k.S3kCompleteRunReferenceProducer",
                "com.openggf.tools.audio.completerun.s3k.S3kCompleteRunOpenGgfProducer");

        private final String profileId;
        private final String profileClass;
        private final String referenceClass;
        private final String engineClass;

        Game(String profileId, String profileClass, String referenceClass, String engineClass) {
            this.profileId = profileId;
            this.profileClass = profileClass;
            this.referenceClass = referenceClass;
            this.engineClass = engineClass;
        }
    }

    private CompleteRunAudioProducerRegistry() { }

    static boolean knowsProfile(String profileId) {
        return Arrays.stream(Game.values()).anyMatch(value -> value.profileId.equals(profileId));
    }

    static boolean isAvailable(String profileId) {
        return Arrays.stream(Game.values()).filter(value -> value.profileId.equals(profileId)).findFirst()
                .map(value -> tryLoadProfile(profileId) && bindingsPinned(profileId)
                        && classAvailable(value.referenceClass) && classAvailable(value.engineClass))
                .orElse(false);
    }

    static boolean isAvailable(String profileId, ProducerKind kind) {
        try {
            requireAvailable(profileId, kind);
            return true;
        } catch (ProducerUnavailableException | IllegalArgumentException unavailable) {
            return false;
        }
    }

    static CompleteRunAudioProfile requireAvailable(String profileId, ProducerKind kind)
            throws ProducerUnavailableException {
        Game game = Arrays.stream(Game.values())
                .filter(value -> value.profileId.equals(profileId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown fixed producer profile"));
        if (!tryLoadProfile(profileId)) {
            throw new ProducerUnavailableException("fixed producer profile is not installed", null);
        }
        CompleteRunAudioProfile profile = requirePinned(profileId, kind);
        String className = kind == ProducerKind.REFERENCE ? game.referenceClass : game.engineClass;
        if (!classAvailable(className)) {
            throw new ProducerUnavailableException(game.name() + " producer is not installed", null);
        }
        return profile;
    }

    private static boolean bindingsPinned(String profileId) {
        CompleteRunAudioProfile profile = CompleteRunAudioProfiles.require(profileId);
        return Arrays.stream(ProducerKind.values()).allMatch(kind ->
                profile.producerBindings().get(kind) instanceof CompleteRunAudioTrace.PinnedProducerBinding);
    }

    static CompleteRunAudioProfile requirePinned(String profileId, ProducerKind kind)
            throws ProducerUnavailableException {
        CompleteRunAudioProfile profile = CompleteRunAudioProfiles.require(profileId);
        CompleteRunAudioTrace.ProducerBinding binding = profile.producerBindings().get(kind);
        if (binding instanceof CompleteRunAudioTrace.UnavailableProducerBinding unavailable) {
            throw new ProducerUnavailableException(unavailable.reason(), null);
        }
        if (!(binding instanceof CompleteRunAudioTrace.PinnedProducerBinding pinned)
                || !pinned.runtimeIdentity().equals(profile.producerRuntimeIdentities().get(kind))) {
            throw new ProducerUnavailableException("fixed producer binding is inconsistent", null);
        }
        return profile;
    }

    /** Fixed lazy bootstrap used by every fresh CLI JVM; there is no caller-selected class seam. */
    static boolean tryLoadProfile(String profileId) {
        Game game = Arrays.stream(Game.values()).filter(value -> value.profileId.equals(profileId)).findFirst()
                .orElse(null);
        if (game == null) return false;
        try {
            Class.forName(game.profileClass, true, CompleteRunAudioProducerRegistry.class.getClassLoader());
            return CompleteRunAudioProfiles.isRegistered(profileId);
        } catch (ClassNotFoundException unavailable) {
            return false;
        }
    }

    private static boolean classAvailable(String name) {
        try {
            return CompleteRunAudioProducer.class.isAssignableFrom(Class.forName(name, false,
                    CompleteRunAudioProducerRegistry.class.getClassLoader()));
        } catch (ClassNotFoundException unavailable) {
            return false;
        }
    }

    static void capture(Request request) throws Exception {
        Game game = Arrays.stream(Game.values()).filter(value -> value.profileId.equals(request.profileId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown fixed producer profile"));
        CompleteRunAudioProfile profile = requireAvailable(request.profileId(), request.producerKind());
        String className = request.producerKind() == ProducerKind.REFERENCE
                ? game.referenceClass : game.engineClass;
        Class<?> type;
        try {
            type = Class.forName(className, true, CompleteRunAudioProducerRegistry.class.getClassLoader());
        } catch (ClassNotFoundException unavailable) {
            throw new ProducerUnavailableException(game.name() + " producer is not installed", unavailable);
        }
        if (!CompleteRunAudioProducer.class.isAssignableFrom(type)) {
            throw new ProducerUnavailableException("fixed producer class has the wrong contract", null);
        }
        CompleteRunAudioInputSnapshot.withBoundRequest(request, profile, bound -> {
            CompleteRunAudioProducer producer =
                    (CompleteRunAudioProducer) type.getDeclaredConstructor().newInstance();
            producer.capture(bound);
        });
    }

    static final class ProducerUnavailableException extends Exception {
        ProducerUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
