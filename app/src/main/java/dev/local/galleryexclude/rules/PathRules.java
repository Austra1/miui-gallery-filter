package dev.local.galleryexclude.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Literal Android path rules. No filesystem access, Android, SQL or regular expressions. */
public final class PathRules {
    public static final class InvalidPath extends IllegalArgumentException {
        public final String reason;
        InvalidPath(String reason) { super(reason); this.reason = reason; }
    }

    public static final class Normalizer {
        private final Map<String, String> aliases;

        public Normalizer(Map<String, String> confirmedAliases) {
            List<Map.Entry<String, String>> entries = new ArrayList<>(confirmedAliases.entrySet());
            entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : entries) {
                copy.put(clean(entry.getKey()), clean(entry.getValue()));
            }
            aliases = Collections.unmodifiableMap(copy);
        }

        public String normalize(String path) {
            String result = clean(path);
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                if (covers(alias.getKey(), result)) {
                    String suffix = result.substring(alias.getKey().equals("/") ? 0 : alias.getKey().length());
                    return clean(alias.getValue() + "/" + suffix);
                }
            }
            return result;
        }
    }

    private final Normalizer normalizer;
    private final List<String> saved;
    private final List<String> effective;
    private final Map<String, String> shadowed;

    public PathRules(Normalizer normalizer, List<String> input) {
        this.normalizer = normalizer;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String path : input) unique.add(normalizer.normalize(path));
        saved = Collections.unmodifiableList(new ArrayList<>(unique));
        LinkedHashMap<String, String> parents = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (String path : saved) {
            String nearest = null;
            for (String candidate : saved) {
                if (!candidate.equals(path) && covers(candidate, path)
                        && (nearest == null || candidate.length() > nearest.length())) nearest = candidate;
            }
            if (nearest == null) roots.add(path); else parents.put(path, nearest);
        }
        effective = Collections.unmodifiableList(roots);
        shadowed = Collections.unmodifiableMap(parents);
    }

    public List<String> saved() { return saved; }
    public List<String> effective() { return effective; }
    public Map<String, String> shadowedBy() { return shadowed; }

    public boolean matchesOriginalPath(String original) {
        if (saved.isEmpty()) return false;
        String path = normalizer.normalize(original);
        for (String root : effective) if (covers(root, path)) return true;
        return false;
    }

    public enum Decision { EXCLUDE, KEEP, QUERY_PATH_ERROR_RETURN_ORIGINAL, BYPASS_RETURN_ORIGINAL }
    public enum Kind { ORIGINAL_PATH, CLOUD_ONLY, UNRESOLVED }
    public static final class Resolution {
        public final Kind kind;
        public final String path;
        public Resolution(Kind kind, String path) { this.kind = kind; this.path = path; }
    }

    /** A query adapter must abort the whole query on QUERY_PATH_ERROR_RETURN_ORIGINAL. */
    public Decision decide(Supplier<Resolution> resolve) {
        if (saved.isEmpty()) return Decision.BYPASS_RETURN_ORIGINAL;
        Resolution value = resolve.get();
        if (value == null || value.kind == null || value.kind == Kind.UNRESOLVED)
            return Decision.QUERY_PATH_ERROR_RETURN_ORIGINAL;
        if (value.kind == Kind.CLOUD_ONLY) return Decision.KEEP;
        try {
            return matchesOriginalPath(value.path) ? Decision.EXCLUDE : Decision.KEEP;
        } catch (InvalidPath error) {
            return Decision.QUERY_PATH_ERROR_RETURN_ORIGINAL;
        }
    }

    private static boolean covers(String directory, String path) {
        return directory.equals("/") ? path.startsWith("/")
                : path.equals(directory) || path.startsWith(directory + "/");
    }

    private static String clean(String path) {
        if (path == null || path.isEmpty()) throw new InvalidPath("EMPTY_PATH");
        if (path.charAt(0) != '/') throw new InvalidPath("NOT_ABSOLUTE");
        if (path.indexOf('\0') >= 0) throw new InvalidPath("NUL_CHARACTER");
        StringBuilder result = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) throw new InvalidPath("DOT_SEGMENT");
            if (!segment.isEmpty()) result.append('/').append(segment);
        }
        return result.length() == 0 ? "/" : result.toString();
    }
}
