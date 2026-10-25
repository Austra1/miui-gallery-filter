package dev.local.galleryexclude.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import dev.local.galleryexclude.BuildConfig;
import dev.local.galleryexclude.config.RestartProtocol;
import dev.local.galleryexclude.compat.CompatibilityCodec;
import dev.local.galleryexclude.compat.CompatibilityReport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Registered only in Gallery's main process. Sender must hold the module's signature permission. */
final class GalleryRestartReceiver extends BroadcastReceiver {
    private final LongSupplier revision;
    private final Supplier<CompatibilityReport> compatibility;
    private final Consumer<String> log;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean stopping;
    private GalleryRestartReceiver(LongSupplier revision,Supplier<CompatibilityReport> compatibility,Consumer<String> log){
        this.revision=revision;this.compatibility=compatibility;this.log=log;
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Pre-33 overload; both branches require the sender's signature permission.
    static void register(Context context,LongSupplier revision,Supplier<CompatibilityReport> compatibility,Consumer<String> log){
        GalleryRestartReceiver receiver=new GalleryRestartReceiver(revision,compatibility,log);
        IntentFilter filter=new IntentFilter(RestartProtocol.ACTION);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,RestartProtocol.PERMISSION,receiver.main,Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver,filter,RestartProtocol.PERMISSION,receiver.main);
        log.accept("event=restart_channel_ready protocol="+RestartProtocol.VERSION);
    }
    @Override public void onReceive(Context context,Intent intent){
        if(!RestartProtocol.ACTION.equals(intent.getAction()) || intent.getIntExtra("protocol",0)!=RestartProtocol.VERSION)return;
        int operation=intent.getIntExtra("operation",-1);
        if(operation!=RestartProtocol.PROBE && operation!=RestartProtocol.STOP)return;
        Bundle reply=new Bundle();reply.putInt("protocol",RestartProtocol.VERSION);reply.putInt("pid",Process.myPid());
        reply.putLong("revision",revision.getAsLong());reply.putInt("module_version",BuildConfig.VERSION_CODE);
        reply.putString(CompatibilityCodec.EXTRA,CompatibilityCodec.encode(compatibility.get()));
        setResultCode(RestartProtocol.OK);setResultExtras(reply);
        if(operation==RestartProtocol.STOP && !stopping){
            stopping=true;log.accept("event=gallery_self_restart_requested revision="+revision.getAsLong());
            main.postDelayed(()->Process.killProcess(Process.myPid()),150);
        }
    }
}
