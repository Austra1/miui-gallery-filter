package dev.local.galleryexclude.compat;

import dev.local.galleryexclude.compat.CompatibilityReport.Feature;
import java.util.function.Consumer;

/** Each feature owns its preflight, installation and rollback; optional failures are isolated. */
public final class CapabilityInstaller {
    public interface Task { void run() throws Throwable; }
    private final CompatibilityState state;
    private final Consumer<String> log;
    public CapabilityInstaller(CompatibilityState state, Consumer<String> log) { this.state = state; this.log = log; }

    public boolean install(Feature feature, Task install, Task rollback) {
        if (feature != Feature.CORE && !state.coreAvailable()) return false;
        try {
            install.run();
            state.available(feature);
            log.accept("event=capability_available feature=" + feature);
            return true;
        } catch (Throwable error) {
            try { rollback.run(); }
            catch (Throwable cleanup) {
                state.unavailable(Feature.CORE);
                log.accept("event=capability_rollback_error feature=" + feature + " reason=" + cleanup.getClass().getSimpleName());
            }
            state.unavailable(feature);
            // Reflection messages identify API names. Gallery runtime messages may contain media paths.
            String member = error instanceof NoSuchMethodException || error instanceof NoSuchFieldException
                    || error instanceof ClassNotFoundException ? " member=" + error.getMessage() : "";
            log.accept("event=capability_unavailable feature=" + feature + " reason=" + error.getClass().getSimpleName() + member);
            return false;
        }
    }
}
