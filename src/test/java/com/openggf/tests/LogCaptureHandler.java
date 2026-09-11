package com.openggf.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public final class LogCaptureHandler extends Handler {
    private final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
        if (record != null) {
            records.add(record);
        }
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public void close() {
        records.clear();
    }

    public long countAtOrAbove(Level level) {
        return records.stream()
                .filter(record -> record.getLevel().intValue() >= level.intValue())
                .count();
    }
}
