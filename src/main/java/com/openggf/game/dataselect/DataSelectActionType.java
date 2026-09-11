package com.openggf.game.dataselect;

/**
 * Types of actions that can result from data select screen interaction.
 */
public enum DataSelectActionType {
    /** No action pending. */
    NONE,
    /** Start a new game without saving (No Save slot). */
    NO_SAVE_START,
    /** Start a new game in an empty save slot. */
    NEW_SLOT_START,
    /** Load an existing save slot and resume. */
    LOAD_SLOT,
    /** Clear a completed slot and restart from zone 1. */
    CLEAR_RESTART,
    /** Delete a save slot. */
    DELETE_SLOT
}
