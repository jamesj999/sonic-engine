package uk.co.jamesj999.sonic.timer;

/**
 * Created by Jamesjohnstone on 26/03/15.
 */
public interface Timer {

    public String getCode();

    public void setCode(String code);

    public int getTicks();

    public void setTicks(int ticks);

    public void decrementTick();

    /**
     * Override this method in your own timer to specify the action that should take place once the timer has completed.
     *
     * @return a boolean indicating whether the perform was successful. Dunno if the engine will do anything about it though :)
     */
    public boolean perform();
}
