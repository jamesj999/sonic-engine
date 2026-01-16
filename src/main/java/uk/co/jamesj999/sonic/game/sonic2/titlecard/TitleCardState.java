package uk.co.jamesj999.sonic.game.sonic2.titlecard;

/**
 * States for the title card animation state machine.
 *
 * <p>The exit sequence follows the original Sonic 2 cascading order:
 * <pre>
 * SLIDE_IN → DISPLAY → EXIT_LEFT_SWOOSH → EXIT_BOTTOM_BAR → EXIT_BACKGROUND → TEXT_WAIT → TEXT_EXIT → COMPLETE
 * </pre>
 *
 * <p>This creates the distinctive effect where background elements exit first,
 * revealing the level behind, while the text stays visible for a moment before
 * sliding off.
 */
public enum TitleCardState {
    /** Elements are sliding into position */
    SLIDE_IN,

    /** All elements are at target positions, displaying for a set duration */
    DISPLAY,

    /** Left swoosh (red stripes) is sliding out - first to exit */
    EXIT_LEFT_SWOOSH,

    /** Bottom bar is sliding out - triggered when left swoosh finishes */
    EXIT_BOTTOM_BAR,

    /** Background is scrolling out - triggered when bottom bar finishes */
    EXIT_BACKGROUND,

    /** Text elements waiting before exit (45 frames per disassembly) - starts when background is gone */
    TEXT_WAIT,

    /** Text elements are sliding out */
    TEXT_EXIT,

    /** Title card is complete, ready to transition to level */
    COMPLETE
}
