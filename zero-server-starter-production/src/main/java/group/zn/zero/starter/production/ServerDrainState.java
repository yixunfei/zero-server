package group.zn.zero.starter.production;

/** Server-level admission and drain state. */
public enum ServerDrainState {
    NEW,
    RUNNING,
    DRAINING,
    STOPPED,
    FAILED
}
