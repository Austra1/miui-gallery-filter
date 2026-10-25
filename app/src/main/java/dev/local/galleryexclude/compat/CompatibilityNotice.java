package dev.local.galleryexclude.compat;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;
import dev.local.galleryexclude.R;
import java.util.ArrayList;
import java.util.List;

/** Quiet footer for missing extensions; a larger notice for unavailable core filtering. */
public final class CompatibilityNotice {
    private CompatibilityNotice() { }
    public static void bind(TextView view, CompatibilityReport report) {
        Context context = view.getContext();
        view.setVisibility(report.visible() ? View.VISIBLE : View.GONE);
        view.setTextSize(report.major() ? 16 : 14);
        view.setTypeface(Typeface.DEFAULT, report.major() ? Typeface.BOLD : Typeface.NORMAL);
        view.setTextColor(context.getColor(report.major() ? R.color.error : R.color.text_secondary));
        int text;
        switch (report.state) {
            case READY: view.setText(""); return;
            case PARTIAL:
                List<String> features = new ArrayList<>();
                for (CompatibilityReport.Feature feature : report.unavailable) features.add(context.getString(label(feature)));
                view.setText(context.getString(R.string.compat_partial, String.join("、", features))); return;
            case CORE_UNAVAILABLE: text = R.string.compat_core_unavailable; break;
            case CONFIG_UNAVAILABLE: text = R.string.compat_config_unavailable; break;
            case RUNTIME_ERROR: text = R.string.compat_runtime_error; break;
            case NATIVE_UNSUPPORTED: text = R.string.compat_native_unsupported; break;
            case GALLERY_MISSING: text = R.string.gallery_not_found; break;
            case RESTART_REQUIRED: text = R.string.compat_restart_required; break;
            default: text = R.string.compat_waiting;
        }
        view.setText(text);
    }
    private static int label(CompatibilityReport.Feature feature) {
        switch (feature) {
            case MAP: return R.string.compat_feature_map;
            case LEGACY_DETAIL: return R.string.compat_feature_legacy;
            case PICKER: return R.string.compat_feature_picker;
            case SEARCH: return R.string.compat_feature_search;
            case SUGGESTIONS: return R.string.compat_feature_suggestions;
            case TRASH: return R.string.compat_feature_trash;
            case EXTERNAL_PREVIEW: return R.string.compat_feature_external;
            default: return R.string.compat_feature_core;
        }
    }
}
