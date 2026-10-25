package dev.local.galleryexclude.xposed;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import dev.local.galleryexclude.BuildConfig;
import dev.local.galleryexclude.ModuleInfo;
import dev.local.galleryexclude.compat.CompatibilityReport;
import dev.local.galleryexclude.compat.CompatibilityState;
import dev.local.galleryexclude.config.ConfigCodec;
import dev.local.galleryexclude.config.DevicePaths;
import dev.local.galleryexclude.rules.RuleConfiguration;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Load one immutable configuration; actual target interfaces decide which features can run. */
public final class LoadEntry implements IXposedHookLoadPackage {
    private boolean configurationAttempted;
    private RuleConfiguration configuration;
    private final CompatibilityState compatibility = new CompatibilityState();
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!ModuleInfo.isTargetProcess(loadPackageParam.packageName, loadPackageParam.processName)) {
            return;
        }

        emit("event=package_loaded" + identity(loadPackageParam.processName) + " filtering=false");

        // Context is available after attach; configuration is read only once per process.
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                if (!ModuleInfo.TARGET_PACKAGE.equals(context.getPackageName())) {
                    return;
                }
                try {
                    PackageInfo gallery = context.getPackageManager()
                            .getPackageInfo(ModuleInfo.TARGET_PACKAGE, 0);
                    long versionCode = gallery.getLongVersionCode();
                    boolean baselineMatches = versionCode == ModuleInfo.BASELINE_VERSION_CODE
                            && ModuleInfo.BASELINE_VERSION_NAME.equals(gallery.versionName);
                    emit("event=application_attached" + identity(loadPackageParam.processName)
                            + " gallery_version=" + gallery.versionName
                            + " gallery_version_code=" + versionCode
                            + " analysis_baseline_match=" + baselineMatches
                            + " filtering=false");
                    // The known version is diagnostic context, never an admission rule.
                    readConfiguration(loadPackageParam.processName, loadPackageParam.classLoader,context);
                } catch (PackageManager.NameNotFoundException error) {
                    emit("event=package_metadata_unavailable" + identity(loadPackageParam.processName)
                            + " reason=NameNotFoundException filtering=false");
                }
            }
        });
    }

    private void readConfiguration(String processName, ClassLoader loader,Context context) {
        if (configurationAttempted) return;
        configurationAttempted = true;
        try {
            GalleryRestartReceiver.register(context, () -> configuration == null ? -1 : configuration.revision,
                    compatibility::report, LoadEntry::emit);
        } catch (RuntimeException error) {
            emit("event=restart_channel_unavailable reason=" + error.getClass().getSimpleName());
        }
        try {
            XSharedPreferences prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID, ConfigCodec.PREFS_NAME);
            if (!prefs.getFile().canRead()) throw new ConfigCodec.InvalidConfig("SHARED_FILE_UNREADABLE");
            prefs.reload();
            configuration = ConfigCodec.decode(prefs.getAll().get(ConfigCodec.STATE_KEY), DevicePaths.normalizer());
            emit("event=config_loaded" + identity(processName)
                    + " revision=" + configuration.revision
                    + " rule_count=" + configuration.rules.saved().size()
                    + " effective_count=" + configuration.rules.effective().size()
                    + " filtering=false");
            try {
                new GalleryCacheAdapter(loader, configuration, compatibility, LoadEntry::emit).install();
            } catch (Throwable error) {
                compatibility.unavailable(CompatibilityReport.Feature.CORE);
                emit("event=filter_adapter_unsupported" + identity(processName)
                        + " reason=" + error.getClass().getSimpleName() + " filtering=false");
            }
            emit("event=compatibility_checked state=" + compatibility.report().state
                    + " unavailable=" + compatibility.report().unavailable);
        } catch (RuntimeException error) {
            compatibility.configurationUnavailable();
            String reason = error instanceof ConfigCodec.InvalidConfig
                    ? ((ConfigCodec.InvalidConfig) error).reason : error.getClass().getSimpleName();
            emit("event=config_unreadable" + identity(processName)
                    + " reason=" + reason + " filtering=false");
        }
    }

    private static String identity(String processName) {
        return " package=" + ModuleInfo.TARGET_PACKAGE
                + " process=" + processName
                + " pid=" + Process.myPid()
                + " module_version=" + BuildConfig.VERSION_NAME
                + " module_version_code=" + BuildConfig.VERSION_CODE;
    }

    private static void emit(String message) {
        Log.i(ModuleInfo.LOG_TAG, message);
        XposedBridge.log(ModuleInfo.LOG_TAG + " " + message);
    }
}
