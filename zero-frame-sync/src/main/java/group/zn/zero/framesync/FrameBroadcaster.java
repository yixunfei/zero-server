package group.zn.zero.framesync;

/** Broadcast SPI. The runtime only invokes it synchronously and never owns IO threads. */
@FunctionalInterface
public interface FrameBroadcaster { void broadcast(FrameCommitted event); }
