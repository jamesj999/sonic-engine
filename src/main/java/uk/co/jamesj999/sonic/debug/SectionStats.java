package uk.co.jamesj999.sonic.debug;

/**
 * Immutable statistics for a single profiled section.
 *
 * @param name The name of the section (e.g., "audio", "physics", "render.bg")
 * @param timeMs Average time spent in this section per frame, in milliseconds
 * @param percentage Percentage of total frame time spent in this section
 */
public record SectionStats(String name, double timeMs, double percentage) {
}
