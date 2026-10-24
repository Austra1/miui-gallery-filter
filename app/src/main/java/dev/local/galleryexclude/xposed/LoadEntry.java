package dev.local.galleryexclude.xposed;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import dev.local.galleryexclude.BuildConfig;
import dev.local.galleryexclude.ModuleInfo;
import dev.local.galleryexclude.config.ConfigCodec;
import dev.local.galleryexclude.config.DevicePaths;
import dev.local.galleryexclude.rules.RuleConfiguration;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Load one immutable configuration and the exact-version, display-only adapter. */
public final class LoadEntry implements IXposedHookLoadPackage {
    private boolean configurationAttempted;
    private RuleConfiguration configuration;
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
                    if (baselineMatches) {
                        readConfiguration(loadPackageParam.processName, loadPackageParam.classLoader,context);
                    } else {
                        emit("event=config_unavailable" + identity(loadPackageParam.processName)
                                + " reason=VERSION_UNSUPPORTED filtering=false");
                    }
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
                GalleryRestartReceiver.register(context,configuration.revision,LoadEntry::emit);
            }catch(RuntimeException error){
                emit("event=restart_channel_unavailable reason="+error.getClass().getSimpleName());
            }
            try {
                new Gallery35710Adapter(loader, configuration, LoadEntry::emit).install();
            } catch (Throwable error) {
                emit("event=filter_adapter_unsupported" + identity(processName)
                        + " reason=" + error.getClass().getSimpleName() + " filtering=false");
            }
        } catch (RuntimeException error) {
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
