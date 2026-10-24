package dev.local.galleryexclude.rules;

import java.util.HashSet;
import java.util.List;

/** Immutable configuration snapshot; revision counts committed membership changes. */
public final class RuleConfiguration {
    public static final int FORMAT_VERSION = 1;
    public final long revision;
    public final PathRules rules;

    public RuleConfiguration(long revision, PathRules rules) {
        if (revision < 0) throw new IllegalArgumentException("INVALID_REVISION");
        this.revision = revision;
        this.rules = rules;
    }

    public RuleConfiguration withDraft(PathRules.Normalizer normalizer, List<String> draft) {
        PathRules next = new PathRules(normalizer, draft); // Validate everything before any commit.
        if (new HashSet<>(rules.saved()).equals(new HashSet<>(next.saved()))) return this;
        if (revision == Long.MAX_VALUE) throw new IllegalArgumentException("REVISION_EXHAUSTED");
        return new RuleConfiguration(revision + 1, next);
    }
}
