package com.openggf.audio.session;

import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.DacData;

import java.util.Objects;

public record SmpsDacSelection(
        SmpsSourceDescriptor source,
        DacData data) {
    public SmpsDacSelection {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(data, "data");
    }
}
