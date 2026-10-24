package dev.local.galleryexclude.config;

import dev.local.galleryexclude.BuildConfig;

public final class RestartProtocol {
    public static final String ACTION=BuildConfig.APPLICATION_ID+".action.RESTART_CONTROL";
    public static final String PERMISSION=BuildConfig.APPLICATION_ID+".permission.RESTART_GALLERY";
    public static final int VERSION=1,PROBE=0,STOP=1,OK=1;
    private RestartProtocol(){ }
}
