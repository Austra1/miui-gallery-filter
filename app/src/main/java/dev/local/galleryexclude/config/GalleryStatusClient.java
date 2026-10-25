package dev.local.galleryexclude.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import dev.local.galleryexclude.BuildConfig;
import dev.local.galleryexclude.ModuleInfo;
import dev.local.galleryexclude.compat.CompatibilityCodec;
import dev.local.galleryexclude.compat.CompatibilityReport;
import java.util.function.Consumer;

/** Reads live target-process evidence without launching, stopping or changing Gallery. */
public final class GalleryStatusClient {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    public GalleryStatusClient(Context context) { this.context = context.getApplicationContext(); }
    public void cancel() { generation++; main.removeCallbacksAndMessages(null); }
    public void refresh(long revision, Consumer<CompatibilityReport> result) {
        cancel();
        int request = generation;
        final boolean[] delivered = {false};
        Runnable timeout = () -> {
            if (request == generation && !delivered[0]) {
                delivered[0] = true;
                result.accept(CompatibilityReport.of(CompatibilityReport.State.WAITING));
            }
        };
        main.postDelayed(timeout, 1800);
        Intent probe = new Intent(RestartProtocol.ACTION).setPackage(ModuleInfo.TARGET_PACKAGE)
                .putExtra("protocol", RestartProtocol.VERSION).putExtra("operation", RestartProtocol.PROBE);
        context.sendOrderedBroadcast(probe, null, new BroadcastReceiver() {
            @Override public void onReceive(Context unused, Intent intent) {
                if (request != generation || delivered[0]) return;
                delivered[0] = true; main.removeCallbacks(timeout);
                Bundle data = getResultExtras(false);
                CompatibilityReport report = CompatibilityReport.of(CompatibilityReport.State.WAITING);
                if (getResultCode() == RestartProtocol.OK && data != null
                        && data.getInt("protocol") == RestartProtocol.VERSION && data.getInt("pid") > 0) {
                    if (data.getInt("module_version", -1) != BuildConfig.VERSION_CODE) {
                        report = CompatibilityReport.of(CompatibilityReport.State.RESTART_REQUIRED);
                    } else try {
                        report = CompatibilityCodec.decode(data.getString(CompatibilityCodec.EXTRA));
                        if (!report.major() && data.getLong("revision", -1) != revision)
                            report = CompatibilityReport.of(CompatibilityReport.State.RESTART_REQUIRED);
                    } catch (RuntimeException ignored) { /* An invalid receipt is never compatibility success. */ }
                }
                result.accept(report);
            }
        }, main, 0, null, null);
    }
}
