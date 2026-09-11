package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kBadnikBaseArchitecture {

    @Test
    void s3kBadnikBaseUsesSharedBadnikRuntimeState() {
        assertTrue(AbstractBadnikInstance.class.isAssignableFrom(AbstractS3kBadnikInstance.class),
                "S3K badniks must inherit shared badnik update, dynamic-spawn, and rewind state");
    }
}
