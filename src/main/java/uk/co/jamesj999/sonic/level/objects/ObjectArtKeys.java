package uk.co.jamesj999.sonic.level.objects;

/**
 * Standard renderer/sheet keys shared across Sonic games.
 * These keys provide a common vocabulary for accessing object art
 * that exists in multiple games (Sonic 1, 2, 3&K, etc.).
 * <p>
 * Game-specific objects should use keys defined in game-specific classes
 * (e.g., Sonic2ObjectArtKeys for CNZ bumpers, CPZ objects).
 */
public final class ObjectArtKeys {

    private ObjectArtKeys() {
        // Constants only
    }

    // Common objects found in multiple Sonic games
    public static final String MONITOR = "monitor";
    public static final String SPIKE = "spike";
    public static final String SPIKE_SIDE = "spike_side";
    public static final String SPRING_VERTICAL = "spring_vertical";
    public static final String SPRING_HORIZONTAL = "spring_horizontal";
    public static final String SPRING_DIAGONAL = "spring_diagonal";
    public static final String SPRING_VERTICAL_RED = "spring_vertical_red";
    public static final String SPRING_HORIZONTAL_RED = "spring_horizontal_red";
    public static final String SPRING_DIAGONAL_RED = "spring_diagonal_red";
    public static final String EXPLOSION = "explosion";
    public static final String SHIELD = "shield";
    public static final String INVINCIBILITY_STARS = "invincibility_stars";
    public static final String CHECKPOINT = "checkpoint";
    public static final String CHECKPOINT_STAR = "checkpoint_star";
    public static final String SIGNPOST = "signpost";
    public static final String ANIMAL = "animal";
    public static final String POINTS = "points";
    public static final String BRIDGE = "bridge";
    public static final String RESULTS = "results";

    // Animation keys (common across games)
    public static final String ANIM_MONITOR = "monitor";
    public static final String ANIM_SPRING = "spring";
    public static final String ANIM_CHECKPOINT = "checkpoint";
    public static final String ANIM_SIGNPOST = "signpost";

    // Zone data keys
    public static final String ANIMAL_TYPE_A = "animal_type_a";
    public static final String ANIMAL_TYPE_B = "animal_type_b";

    // Common badniks (may have game-specific implementations)
    public static final String MASHER = "masher";
    public static final String BUZZER = "buzzer";
    public static final String COCONUTS = "coconuts";
}
