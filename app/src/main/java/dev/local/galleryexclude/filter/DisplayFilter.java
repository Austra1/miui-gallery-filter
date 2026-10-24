package dev.local.galleryexclude.filter;

import dev.local.galleryexclude.rules.PathRules;
import java.util.ArrayList;
import java.util.List;

/** All-or-original selection. The caller supplies original-path evidence, never a thumbnail fallback. */
public final class DisplayFilter {
    public interface Resolver<T> { PathRules.Resolution resolve(T item); }
    public enum Status { FILTERED, BYPASS, PATH_UNRESOLVED, FILTER_ERROR }
    public static final class Result<T> {
        public final List<T> items;
        public final Status status;
        Result(List<T> items, Status status) { this.items = items; this.status = status; }
    }
    private DisplayFilter() { }

    public static <T> Result<T> select(List<T> source, PathRules rules, Resolver<T> resolver) {
        if (rules.saved().isEmpty()) return new Result<>(source, Status.BYPASS);
        ArrayList<T> visible = new ArrayList<>(source.size());
        try {
            for (T item : source) {
                PathRules.Decision decision = rules.decide(() -> resolver.resolve(item));
                if (decision == PathRules.Decision.QUERY_PATH_ERROR_RETURN_ORIGINAL)
                    return new Result<>(source, Status.PATH_UNRESOLVED);
                if (decision != PathRules.Decision.EXCLUDE) visible.add(item);
            }
            return new Result<>(visible, Status.FILTERED);
        } catch (RuntimeException error) {
            return new Result<>(source, Status.FILTER_ERROR);
        }
    }
}
