package uk.co.jamesj999.sonic.timer;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by James on 26/03/15.
 */
public class TimerManager {
    private static TimerManager timerManager;

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
        // Iterate all our timers:
        var iterator = timers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Timer> timerEntry = iterator.next();
            Timer timer = timerEntry.getValue();
            // Decrement the tick value to indicate a tick has passed:
            timer.decrementTick();

            // Check if the tick is less than 1.
            if (timer.getTicks() < 1) {
                // Perform event
                // TODO: Improve the error reporting - use a proper Exception structure
                if (timer.perform()) {
                    iterator.remove();
                } else {
                    System.out.println(
                            "ERROR: " + timer.getClass() + " " + timer.getCode() + " failed to complete successfully.");
                    iterator.remove();
                }
            }
        }
    }

    public synchronized static TimerManager getInstance() {
        if (timerManager == null) {
            timerManager = new TimerManager();
        }
        return timerManager;
    }
}
