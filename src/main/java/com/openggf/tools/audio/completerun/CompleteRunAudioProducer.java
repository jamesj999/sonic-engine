package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import java.nio.file.Path;

/** Fixed in-process producer boundary; game tasks supply only the reserved implementations. */
public interface CompleteRunAudioProducer {
    void capture(Request request) throws Exception;

    record Request(ProducerKind producerKind, String profileId, Path rom, Path bk2,
            Path runManifest, Path referenceHome, Path output) { }
}
