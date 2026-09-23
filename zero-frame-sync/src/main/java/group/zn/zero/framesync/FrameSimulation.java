package group.zn.zero.framesync;

/** Simulation callback executed on the match lane. */
@FunctionalInterface
public interface FrameSimulation { void advance(long frameNo, FrameInputBatch inputs); }
