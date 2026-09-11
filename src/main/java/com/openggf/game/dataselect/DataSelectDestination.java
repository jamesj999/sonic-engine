package com.openggf.game.dataselect;

/**
 * Launch destination selected from the Data Select screen.
 */
public record DataSelectDestination(int zone, int act) {
    public static DataSelectDestination of(int zone, int act) {
        return new DataSelectDestination(zone, act);
    }
}
