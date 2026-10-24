package dev.local.galleryexclude.config;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;

public final class SharedConfig {
    private SharedConfig() { }

    @SuppressWarnings("deprecation") // Required LSPosed New XSharedPreferences capability, not Android private prefs.
    public static RuleRepository openWriter(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(ConfigCodec.PREFS_NAME, Context.MODE_WORLD_READABLE);
        // Android may expose corrupt XML as an empty map. A zero-byte, module-private marker
        // prevents a previously initialized configuration from being silently reset to revision 0.
        // No preference path is guessed, reflected, read with Root, or made more permissive.
        File initialized = new File(context.getNoBackupFilesDir(), ConfigCodec.PREFS_NAME + ".initialized");
        boolean firstUse = !initialized.exists() && prefs.getAll().isEmpty();
        try {
            if (!initialized.exists() && !initialized.createNewFile())
                throw new ConfigCodec.InvalidConfig("INITIALIZATION_GUARD_UNAVAILABLE");
        } catch (IOException error) {
            throw new ConfigCodec.InvalidConfig("INITIALIZATION_GUARD_UNAVAILABLE");
        }
        return new RuleRepository(new RuleRepository.Storage() {
            @Override public Object read() { return prefs.getAll().get(ConfigCodec.STATE_KEY); }
            @Override public boolean canInitialize() { return firstUse; }
            @Override public boolean commit(String payload) {
                SharedPreferences.Editor editor = prefs.edit();
                if (payload == null) editor.remove(ConfigCodec.STATE_KEY);
                else editor.putString(ConfigCodec.STATE_KEY, payload);
                return editor.commit();
            }
        }, DevicePaths.normalizer());
    }
}
