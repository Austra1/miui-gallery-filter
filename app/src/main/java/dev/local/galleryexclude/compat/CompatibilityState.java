package dev.local.galleryexclude.compat;

import dev.local.galleryexclude.compat.CompatibilityReport.Feature;
import dev.local.galleryexclude.compat.CompatibilityReport.State;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** One process-local source of truth, queried by the protected status channel. */
public final class CompatibilityState {
    private State terminal;
    private boolean coreReady;
    private final EnumSet<Feature> missing = EnumSet.noneOf(Feature.class);
    private final Map<String, Feature> runtimeFailures = new LinkedHashMap<>();

    public synchronized void available(Feature feature) {
        if (feature == Feature.CORE) coreReady = true;
        missing.remove(feature);
    }
    public synchronized void unavailable(Feature feature) {
        missing.add(feature);
        if (feature == Feature.CORE) { coreReady = false; terminal = State.CORE_UNAVAILABLE; }
    }
    public synchronized void configurationUnavailable() { terminal = State.CONFIG_UNAVAILABLE; }
    public synchronized void failed(String consumer, Feature feature) { runtimeFailures.put(consumer, feature); }
    public synchronized void recovered(String consumer) { runtimeFailures.remove(consumer); }
    public synchronized boolean coreAvailable() { return coreReady && terminal == null; }

    public synchronized CompatibilityReport report() {
        EnumSet<Feature> issues = missing.clone();
        issues.addAll(runtimeFailures.values());
        State state = terminal != null ? terminal : !coreReady ? State.WAITING
                : runtimeFailures.containsValue(Feature.CORE) ? State.RUNTIME_ERROR
                : issues.isEmpty() ? State.READY : State.PARTIAL;
        return new CompatibilityReport(state, new ArrayList<>(issues));
    }
}
