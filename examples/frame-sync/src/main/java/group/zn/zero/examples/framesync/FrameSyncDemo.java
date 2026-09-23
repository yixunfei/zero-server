package group.zn.zero.examples.framesync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Deterministic, single-owner local frame-sync demonstration. */
public final class FrameSyncDemo {
    private FrameSyncDemo() { }

    public static void main(String[] args) {
        var match = new LocalFrameSync(new Config(4, 2, 64, MissingPolicy.EMPTY_INPUT, LatePolicy.REJECT));
        match.join("match-1", "alice");
        match.join("match-1", "bob");
        var accepted = match.submit(new Input("alice", 1, 1, "move-right"));
        var duplicate = match.submit(new Input("alice", 1, 1, "move-right"));
        match.submit(new Input("bob", 1, 2, "move-left"));
        var frame = match.tick(1);
        var late = match.submit(new Input("bob", 2, 1, "late"));
        var snapshot = match.snapshot();
        match.close();
        System.out.println("frame-sync=ok|mode=local|frameNo=" + snapshot.frameNo()
                + "|accepted=" + accepted.status() + "|duplicate=" + duplicate.status()
                + "|inputs=" + frame.inputs().size() + "|late=" + late.status()
                + "|digest=" + snapshot.digest() + "|productionReady=false");
    }

    public enum MissingPolicy { EMPTY_INPUT }
    public enum LatePolicy { REJECT, MARK }
    public enum InputStatus { ACCEPTED, DUPLICATE, REJECTED_TOO_EARLY, REJECTED_TOO_LATE, REJECTED_CONFLICT }
    public record Config(int maxPlayers, int futureWindow, int maxPayloadBytes,
                         MissingPolicy missingPolicy, LatePolicy latePolicy) { }
    public record Input(String uid, long inputSeq, long targetFrame, String payload) { }
    public record InputResult(InputStatus status) { }
    public record Frame(long frameNo, List<Input> inputs, List<String> missing) {
        public Frame { inputs = List.copyOf(inputs); missing = List.copyOf(missing); }
    }
    public record Snapshot(long frameNo, String digest) { }

    /** All state is mutated by this match owner; no internal executor is created. */
    public static final class LocalFrameSync {
        private final Config config;
        private final Set<String> players = new TreeSet<>();
        private final Map<String, Map<Long, Input>> received = new HashMap<>();
        private final List<Frame> committed = new ArrayList<>();
        private long frameNo;
        private boolean closed;

        public LocalFrameSync(Config config) { this.config = Objects.requireNonNull(config); }
        public void join(String matchId, String uid) {
            requireOpen();
            if (players.size() >= config.maxPlayers() && !players.contains(uid)) throw new IllegalStateException("player capacity");
            if (!"match-1".equals(matchId) || uid == null || uid.isBlank()) throw new IllegalArgumentException("invalid match or player");
            players.add(uid); received.computeIfAbsent(uid, ignored -> new HashMap<>());
        }
        public InputResult submit(Input input) {
            requireOpen(); Objects.requireNonNull(input);
            if (!players.contains(input.uid())) throw new IllegalArgumentException("unknown player");
            if (input.payload() == null || input.payload().getBytes(StandardCharsets.UTF_8).length > config.maxPayloadBytes()) throw new IllegalArgumentException("payload limit");
            if (input.targetFrame() <= frameNo) return new InputResult(config.latePolicy() == LatePolicy.MARK ? InputStatus.REJECTED_TOO_LATE : InputStatus.REJECTED_TOO_LATE);
            if (input.targetFrame() > frameNo + config.futureWindow()) return new InputResult(InputStatus.REJECTED_TOO_EARLY);
            var bySeq = received.get(input.uid());
            var prior = bySeq.get(input.inputSeq());
            if (prior != null) return new InputResult(prior.payload().equals(input.payload()) && prior.targetFrame() == input.targetFrame() ? InputStatus.DUPLICATE : InputStatus.REJECTED_CONFLICT);
            if (bySeq.keySet().stream().anyMatch(seq -> seq > input.inputSeq())) return new InputResult(InputStatus.REJECTED_CONFLICT);
            bySeq.put(input.inputSeq(), input);
            return new InputResult(InputStatus.ACCEPTED);
        }
        public Frame tick(long expectedFrame) {
            requireOpen();
            if (expectedFrame != frameNo + 1) throw new IllegalArgumentException("expected consecutive frame");
            var inputs = new ArrayList<Input>(); var missing = new ArrayList<String>();
            for (String uid : players) {
                var candidate = received.get(uid).values().stream().filter(i -> i.targetFrame() == expectedFrame).findFirst();
                if (candidate.isPresent()) inputs.add(candidate.get()); else if (config.missingPolicy() == MissingPolicy.EMPTY_INPUT) missing.add(uid);
            }
            inputs.sort(Comparator.comparing(Input::uid).thenComparingLong(Input::inputSeq));
            var result = new Frame(++frameNo, inputs, missing); committed.add(result); return result;
        }
        public Snapshot snapshot() { requireOpen(); return new Snapshot(frameNo, digest()); }
        public void close() { closed = true; }
        private String digest() {
            try { var md = MessageDigest.getInstance("SHA-256"); return HexFormat.of().formatHex(md.digest(committed.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 12); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        private void requireOpen() { if (closed) throw new IllegalStateException("match closed"); }
    }
}
