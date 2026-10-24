package dev.local.galleryexclude.filter;

import java.util.List;

/** Checks the native snapshot builder's count/start contract before handing data to persistence. */
public final class HeaderConsistency {
    private HeaderConsistency() { }
    public static boolean valid(int mediaCount, List<Integer> counts, List<Integer> starts) {
        if (mediaCount == 0) return (counts == null || counts.isEmpty()) && (starts == null || starts.isEmpty());
        if (counts == null || starts == null || counts.isEmpty() || counts.size() != starts.size()) return false;
        int offset = 0;
        for (int i = 0; i < counts.size(); i++) {
            if (counts.get(i) == null || starts.get(i) == null || counts.get(i) <= 0 || starts.get(i) != offset) return false;
            offset += counts.get(i);
        }
        return offset == mediaCount;
    }
}
