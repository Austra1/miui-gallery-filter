package dev.local.galleryexclude.config;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import dev.local.galleryexclude.rules.PathRules;
import dev.local.galleryexclude.rules.RuleConfiguration;

/** One JSON preference value makes format, revision and rules one atomic payload. */
public final class ConfigCodec {
    public static final String PREFS_NAME = "directory_rules";
    public static final String STATE_KEY = "state";

    public static final class InvalidConfig extends IllegalArgumentException {
        public final String reason;
        public InvalidConfig(String reason) { super(reason); this.reason = reason; }
    }

    private ConfigCodec() { }

    public static String encode(RuleConfiguration state) {
        try {
            return new JSONObject().put("format_version", RuleConfiguration.FORMAT_VERSION)
                    .put("revision", state.revision).put("rules", new JSONArray(state.rules.saved())).toString();
        } catch (JSONException error) {
            throw new InvalidConfig("ENCODE_FAILED");
        }
    }

    public static RuleConfiguration decode(Object payload, PathRules.Normalizer normalizer) {
        if (!(payload instanceof String)) throw new InvalidConfig("MISSING_OR_NON_STRING_STATE");
        try {
            JSONObject json = new JSONObject((String) payload);
            if (json.length() != 3) throw new InvalidConfig("INVALID_FIELDS");
            Object format = json.get("format_version");
            if (!(format instanceof Integer) || ((Integer) format) != RuleConfiguration.FORMAT_VERSION)
                throw new InvalidConfig("UNSUPPORTED_FORMAT");
            Object revision = json.get("revision");
            if (!(revision instanceof Integer || revision instanceof Long)
                    || ((Number) revision).longValue() < 0) throw new InvalidConfig("INVALID_REVISION");
            Object rawRules = json.get("rules");
            if (!(rawRules instanceof JSONArray)) throw new InvalidConfig("INVALID_RULES");
            JSONArray array = (JSONArray) rawRules;
            List<String> paths = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Object path = array.get(i);
                if (!(path instanceof String)) throw new InvalidConfig("INVALID_RULE_TYPE");
                paths.add((String) path);
            }
            PathRules rules = new PathRules(normalizer, paths);
            // Never silently repair a corrupted or non-canonical saved payload.
            if (!paths.equals(rules.saved())) throw new InvalidConfig("NON_CANONICAL_RULES");
            return new RuleConfiguration(((Number) revision).longValue(), rules);
        } catch (JSONException error) {
            throw new InvalidConfig("MALFORMED_STATE");
        } catch (PathRules.InvalidPath error) {
            throw new InvalidConfig("INVALID_PATH_" + error.reason);
        }
    }
}
