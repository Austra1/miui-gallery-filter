package dev.local.galleryexclude;

/** Package/process boundaries shared by the screen and the diagnostic entry. */
public final class ModuleInfo {
    public static final String TARGET_PACKAGE = "com.miui.gallery";
    public static final String BASELINE_VERSION_NAME = "3.5.7.10";
    public static final long BASELINE_VERSION_CODE = 403690L;
    public static final String LOG_TAG = "GalleryExclude";

    private ModuleInfo() {
    }

    public static boolean isTargetProcess(String packageName, String processName) {
        return TARGET_PACKAGE.equals(packageName) && TARGET_PACKAGE.equals(processName);
    }
}
