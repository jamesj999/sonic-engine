package uk.co.jamesj999.sonic.game.sonic2.titlecard;

/**
 * States for the title card animation state machine.
 */
public enum TitleCardState {
    /** Elements are sliding into position */
    SLIDE_IN,

    /** All elements are at target positions, displaying for a set duration */
    DISPLAY,

    /** Elements are sliding out of view */
    SLIDE_OUT,

    /** Title card is complete, ready to transition to level */
    COMPLETE
}
