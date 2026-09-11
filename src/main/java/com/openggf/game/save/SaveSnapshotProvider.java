package com.openggf.game.save;

import java.util.Map;

@FunctionalInterface
public interface SaveSnapshotProvider {
    Map<String, Object> capture(SaveReason reason, RuntimeSaveContext context);
}
