package dev.local.galleryexclude.filter;

/** A synchronous, consumer-owned read scope, not a package/UID-wide filter flag. */
public final class QueryScope {
    public static final class Frame {
        public final String consumer;
        public String failure;
        public int inputCount;
        public int excludedCount;
        public int excludedImages;
        public int excludedVideos;
        public int filterCalls;
        public long selectedBytes;
        Frame(String consumer) { this.consumer = consumer; }
    }
    private final ThreadLocal<Frame> current = new ThreadLocal<>();
    private final ThreadLocal<Integer> suspended = new ThreadLocal<>();
    public Frame active() { return suspended.get() == null ? current.get() : null; }
    public Frame enter(String consumer) {
        if (active() != null) throw new IllegalStateException("Nested display boundary");
        Frame frame = new Frame(consumer); current.set(frame); return frame;
    }
    public void exit(Frame frame) {
        if (current.get() != frame) throw new IllegalStateException("Wrong display boundary");
        current.remove();
    }
    public void suspend() { Integer n = suspended.get(); suspended.set(n == null ? 1 : n + 1); }
    public void resume() {
        Integer n = suspended.get();
        if (n == null) throw new IllegalStateException("Unbalanced suspension");
        if (n == 1) suspended.remove(); else suspended.set(n - 1);
    }
    public boolean isSuspended() { return suspended.get() != null; }
}
