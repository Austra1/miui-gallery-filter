package dev.local.galleryexclude.config;

import java.util.Collections;
import java.util.List;
import dev.local.galleryexclude.rules.PathRules;
import dev.local.galleryexclude.rules.RuleConfiguration;

/** UI writer. A failed disk commit restores the last payload, including the in-memory preference. */
public final class RuleRepository {
    public interface Storage {
        Object read();
        boolean canInitialize(); // First use only; missing/unreadable state after initialization is an error.
        boolean commit(String payload); // null removes this value
    }
    private final Storage storage;
    private final PathRules.Normalizer normalizer;
    private RuleConfiguration current;
    private boolean storageHealthy = true;

    public RuleRepository(Storage storage, PathRules.Normalizer normalizer) {
        this.storage = storage;
        this.normalizer = normalizer;
        if (storage.canInitialize()) {
            RuleConfiguration empty = new RuleConfiguration(0, new PathRules(normalizer, Collections.emptyList()));
            commitOrRestore(ConfigCodec.encode(empty), null);
        }
        current = ConfigCodec.decode(storage.read(), normalizer);
    }

    public RuleConfiguration current() { return current; }

    public RuleConfiguration save(List<String> draft) {
        if (!storageHealthy) throw new ConfigCodec.InvalidConfig("SAVE_FAILED_RECOVERY_REQUIRED");
        RuleConfiguration next = current.withDraft(normalizer, draft);
        if (next == current) return current;
        commitOrRestore(ConfigCodec.encode(next), ConfigCodec.encode(current));
        current = next;
        return current;
    }

    private void commitOrRestore(String next, String previous) {
        if (!storage.commit(next)) {
            boolean restored = storage.commit(previous);
            storageHealthy = restored;
            throw new ConfigCodec.InvalidConfig(restored ? "SAVE_FAILED" : "SAVE_FAILED_RECOVERY_REQUIRED");
        }
    }
}
