package com.openggf.game;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** One cancellable background read. Only the owning UI thread consumes its result. */
final class MenuLoadTask<T> {
    private final FutureTask<T> task;
    private final Thread worker;

    MenuLoadTask(Callable<T> read) {
        task = new FutureTask<>(read);
        worker = Thread.ofVirtual().name("menu-catalog-read").start(task);
    }

    boolean done() { return task.isDone(); }
    T result() throws Exception { return task.get(); }
    void cancel() { task.cancel(true); worker.interrupt(); }
}
