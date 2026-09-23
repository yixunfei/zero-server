package group.zn.zero.framesync;

/** Non-blocking event sink; implementations must perform IO outside the actor lane. */
@FunctionalInterface
public interface FrameEventSink { void publish(FrameCommitted event); }
