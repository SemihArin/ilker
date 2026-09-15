package com.ilker.opendrive.game;

/** Snapshot of the driver's inputs for one frame. */
public class Controls {
    public float throttle;   // 0..1
    public float brake;      // 0..1
    public float steer;      // -1 (left) .. +1 (right)
    public boolean handbrake;

    public void clear() {
        throttle = 0f;
        brake = 0f;
        steer = 0f;
        handbrake = false;
    }
}
