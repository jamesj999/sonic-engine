package com.openggf.tools.audio.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioBackend;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestAudioTimelineAuthorityGuard {
    private static final Set<String> AUDIO_MUTATIONS = Set.of("playMusic", "playSfx",
            "replayTimelineCommand", "replayTimelineCommandLogically", "restoreLogicalSnapshot",
            "presentFrame", "update", "beginGameplayAudioFrame", "beginCommandTimelineFrame");

    @Test
    void timelineProducerCannotCallAudioMutationOrTimingAuthority() {
        // Break caught: tooling capture begins driving audio or trace timing instead of observing it.
        var classes = new ClassFileImporter().importClasses(S1Ghz1OpenGgfAudioTimelineCapture.class);
        List<String> forbidden = classes.stream()
                .flatMap(type -> type.getMethodCallsFromSelf().stream())
                .filter(TestAudioTimelineAuthorityGuard::forbidden)
                .map(JavaMethodCall::getDescription)
                .sorted()
                .toList();
        assertEquals(List.of(), forbidden);
    }

    private static boolean forbidden(JavaMethodCall call) {
        return AUDIO_MUTATIONS.contains(call.getName())
                && (call.getTargetOwner().isAssignableTo(AudioManager.class)
                        || call.getTargetOwner().isAssignableTo(AudioBackend.class))
                || call.getTargetOwner().getPackageName().contains("hardware")
                        || call.getTargetOwner().getPackageName().contains("timing");
    }
}
