package dev.local.galleryexclude.filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stable IDs are Gallery's namespace-encoded IDs; never mix owner/share raw row IDs. */
public final class PreviewIndex {
    private PreviewIndex() { }
    public static int locate(List<Long> visibleIds, long selectedId, int fallback) {
        int found = visibleIds.indexOf(selectedId);
        return found >= 0 ? found : Math.max(0, Math.min(fallback, visibleIds.size() - 1));
    }
    public static List<String> visibleRank(String[] rankedIds, List<Long> cursorIds) {
        Map<String, Boolean> visible = new HashMap<>();
        for (Long id : cursorIds) visible.put(Long.toString(id), true);
        List<String> result = new ArrayList<>();
        for (String id : rankedIds) if (visible.containsKey(id)) result.add(id);
        return result;
    }
}
