package dev.local.galleryexclude.config;

import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.local.galleryexclude.rules.PathRules;

public final class DevicePaths {
    private DevicePaths() { }

    /** Confirm only storage-root aliases for this Android user; never inspect media or scan directories. */
    public static PathRules.Normalizer normalizer() {
        Map<String, String> aliases = new LinkedHashMap<>();
        try {
            String root = Environment.getExternalStorageDirectory().getCanonicalPath();
            for (String alias : new String[]{"/sdcard", "/storage/self/primary"}) {
                if (new File(alias).getCanonicalPath().equals(root)) aliases.put(alias, root);
            }
        } catch (IOException | SecurityException ignored) {
            // Unconfirmed absolute paths stay literal, as specified by the path contract.
        }
        return new PathRules.Normalizer(aliases);
    }
}
