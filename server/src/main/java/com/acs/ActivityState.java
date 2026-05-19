package com.acs;

/**
 * Shared global state for activity monitoring.
 *
 * ServerUI sets this when broadcasting START_ACTIVITY / STOP_ACTIVITY.
 * ClientDetailDialog reads this on open to reflect the correct state.
 *
 * Adding a new global state later (e.g. keylogging active):
 *   Just add another static volatile boolean here. Same pattern.
 */
public class ActivityState {
    private static volatile boolean monitoring = false;

    public static boolean isMonitoring()           { return monitoring; }
    public static void    setMonitoring(boolean v) { monitoring = v;    }
}