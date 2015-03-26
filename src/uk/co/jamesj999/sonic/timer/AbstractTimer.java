package uk.co.jamesj999.sonic.timer;

/**
 * Created by Jamesjohnstone on 26/03/15.
 */
public abstract class AbstractTimer implements Timer {
    private String code;
    private int ticks;

    public AbstractTimer(String code, int ticks) {
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
}
