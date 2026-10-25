package dev.local.galleryexclude.compat;

import dev.local.galleryexclude.compat.CompatibilityReport.Feature;
import dev.local.galleryexclude.compat.CompatibilityReport.State;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Status contains feature identities only, never rules, paths or media information. */
public final class CompatibilityCodec {
    public static final String EXTRA = "compatibility_report";
    private CompatibilityCodec() { }
    public static String encode(CompatibilityReport report) {
        try {
            JSONArray features = new JSONArray();
            for (Feature feature : report.unavailable) features.put(feature.name());
            return new JSONObject().put("format", 1).put("state", report.state.name()).put("unavailable", features).toString();
        } catch (JSONException error) { throw new IllegalArgumentException("STATUS_ENCODE_FAILED", error); }
    }
    public static CompatibilityReport decode(String payload) {
        try {
            JSONObject value = new JSONObject(payload);
            if (value.length() != 3 || !Integer.valueOf(1).equals(value.get("format")))
                throw new IllegalArgumentException("STATUS_FORMAT_UNSUPPORTED");
            Object rawState = value.get("state"), rawFeatures = value.get("unavailable");
            if (!(rawState instanceof String) || !(rawFeatures instanceof JSONArray))
                throw new IllegalArgumentException("STATUS_TYPE_INVALID");
            List<Feature> features = new ArrayList<>();
            JSONArray array = (JSONArray) rawFeatures;
            for (int i = 0; i < array.length(); i++) {
                Object raw = array.get(i);
                if (!(raw instanceof String)) throw new IllegalArgumentException("STATUS_FEATURE_INVALID");
                Feature feature = Feature.valueOf((String) raw);
                if (features.contains(feature)) throw new IllegalArgumentException("STATUS_FEATURE_DUPLICATE");
                features.add(feature);
            }
            return new CompatibilityReport(State.valueOf((String) rawState), features);
        } catch (JSONException | NullPointerException error) { throw new IllegalArgumentException("STATUS_INVALID", error); }
    }
}
