package dev.local.galleryexclude.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Interface availability is distinct from configuration save/restart success. */
public final class CompatibilityReport {
    public enum State {
        WAITING, RESTART_REQUIRED, READY, PARTIAL, CORE_UNAVAILABLE,
        CONFIG_UNAVAILABLE, RUNTIME_ERROR, NATIVE_UNSUPPORTED, GALLERY_MISSING
    }
    public enum Feature { CORE, MAP, LEGACY_DETAIL, PICKER, SEARCH, SUGGESTIONS, TRASH, EXTERNAL_PREVIEW }
    public final State state;
    public final List<Feature> unavailable;

    public CompatibilityReport(State state, List<Feature> unavailable) {
        if (state == null || unavailable == null || unavailable.contains(null))
            throw new IllegalArgumentException("INVALID_COMPATIBILITY_REPORT");
        this.state = state;
        this.unavailable = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(unavailable)));
        if (state == State.PARTIAL && (this.unavailable.isEmpty() || this.unavailable.contains(Feature.CORE)))
            throw new IllegalArgumentException("INVALID_PARTIAL_REPORT");
        if (state == State.READY && !this.unavailable.isEmpty())
            throw new IllegalArgumentException("INVALID_READY_REPORT");
    }

    public static CompatibilityReport of(State state) {
        return new CompatibilityReport(state, Collections.emptyList());
    }
    public boolean major() {
        return state == State.CORE_UNAVAILABLE || state == State.CONFIG_UNAVAILABLE
                || state == State.RUNTIME_ERROR || state == State.NATIVE_UNSUPPORTED || state == State.GALLERY_MISSING;
    }
    public boolean visible() { return state != State.READY; }
}
