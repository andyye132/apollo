package edu.uw.ubicomp.apollo.eyetrack;

/**
 * What the passive service is doing, for the activity to display. Written by the service, read by the UI.
 * A prototype stand-in: in the real app the service writes Readings through HealthRepository instead.
 */
final class PassiveState {
    static volatile boolean running;
    static volatile String status = "stopped";      // "tracking", "paused: screen off", "paused: camera in use", ...
    static volatile int blinks, shifts;
    static volatile double faceSeconds, pausedSeconds, fps;
    static volatile long startedAtMs;
    static volatile String logPath = "";

    private PassiveState() {}
}
