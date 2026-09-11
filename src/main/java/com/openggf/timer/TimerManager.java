package com.openggf.timer;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.TimerManagerSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Created by James on 26/03/15.
 */
public class TimerManager implements RewindSnapshottable<TimerManagerSnapshot> {
    private static final Logger LOGGER = Logger.getLogger(TimerManager.class.getName());

    private Map<String, Timer> timers = new HashMap<String, Timer>();

    public void registerTimer(Timer timer) {
        timers.put(timer.getCode(), timer);
    }

    public void removeTimerForCode(String code) {
        timers.remove(code);
    }

    public Timer getTimerForCode(String code) {
        return timers.get(code);
    }

    public void update() {
        // Iterate all our timers:
        var iterator = timers.entrySet().iterator();
        while (iterator.hasNext()) {
            Timer timer = iterator.next().getValue();
            // Display-phase countdowns belong to the owning character's own
            // display step, which runs after the movement modes; see
            // DisplayPhaseTimer for the ROM citations.
            if (timer instanceof DisplayPhaseTimer) {
                continue;
            }
            tickAndRetire(iterator, timer);
        }
    }

    /**
     * Ticks the {@link DisplayPhaseTimer}s owned by {@code owner}, from that
     * character's display step. Timers owned by anything else are untouched, so
     * a character's own display step never advances another character's
     * countdown.
     */
    public void updateDisplayPhaseTimersFor(Object owner) {
        if (owner == null) {
            return;
        }
        var iterator = timers.entrySet().iterator();
        while (iterator.hasNext()) {
            Timer timer = iterator.next().getValue();
            if (timer instanceof DisplayPhaseTimer displayTimer
                    && displayTimer.displayPhaseOwner() == owner) {
                tickAndRetire(iterator, timer);
            }
        }
    }

    private void tickAndRetire(java.util.Iterator<Map.Entry<String, Timer>> iterator, Timer timer) {
        // Decrement the tick value to indicate a tick has passed:
        timer.decrementTick();

        // Check if the tick is less than 1.
        if (timer.getTicks() < 1) {
            if (!timer.perform()) {
                LOGGER.warning("Timer failed: " + timer.getClass().getSimpleName()
                        + " code=" + timer.getCode());
            }
            iterator.remove();
        }
    }

    /**
     * Resets mutable state without destroying the singleton instance.
     */
    public void resetState() {
        timers.clear();
    }

    @Override
    public String key() {
        return "timermanager";
    }

    @Override
    public TimerManagerSnapshot capture() {
        Map<String, TimerManagerSnapshot.TimerState> states = new HashMap<>();
        for (Map.Entry<String, Timer> entry : timers.entrySet()) {
            Timer timer = entry.getValue();
            states.put(entry.getKey(),
                    new TimerManagerSnapshot.TimerState(timer.getCode(), timer.getTicks()));
        }
        return new TimerManagerSnapshot(states);
    }

    @Override
    public void restore(TimerManagerSnapshot snapshot) {
        timers.clear();
        for (Map.Entry<String, TimerManagerSnapshot.TimerState> entry : snapshot.timerStates().entrySet()) {
            TimerManagerSnapshot.TimerState state = entry.getValue();
            // Create a simple generic timer to restore the state
            GenericTimer timer = new GenericTimer(state.code(), state.ticks());
            timers.put(entry.getKey(), timer);
        }
    }

    /**
     * Simple timer implementation for snapshot restoration.
     * Used when restoring timer state from snapshots.
     */
    private static class GenericTimer implements Timer {
        private String code;
        private int ticks;

        GenericTimer(String code, int ticks) {
            this.code = code;
            this.ticks = ticks;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public void setCode(String code) {
            this.code = code;
        }

        @Override
        public int getTicks() {
            return ticks;
        }

        @Override
        public void setTicks(int ticks) {
            this.ticks = ticks;
        }

        @Override
        public void decrementTick() {
            ticks--;
        }

        @Override
        public boolean perform() {
            return true;
        }
    }

}
