package com.openggf.audio.rewind;

public record AudioTimelineEntry(long frame, int order, AudioCommand command) {
}
