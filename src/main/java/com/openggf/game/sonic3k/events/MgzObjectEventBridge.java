package com.openggf.game.sonic3k.events;

/**
 * Narrow bridge for MGZ object code that needs to mutate level-event state.
 */
public interface MgzObjectEventBridge {
    void triggerBossCollapseHandoff();

    void completeDrillingRobotnikFlee();
}
