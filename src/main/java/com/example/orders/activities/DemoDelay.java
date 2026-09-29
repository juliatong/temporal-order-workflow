package com.example.orders.activities;

import java.time.Duration;

/** Demo only: a pause inside an activity that opens a window to kill the worker. Zero in tests. */
final class DemoDelay {

    private DemoDelay() {}

    static void pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
