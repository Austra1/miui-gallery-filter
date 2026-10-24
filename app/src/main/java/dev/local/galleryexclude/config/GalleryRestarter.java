package dev.local.galleryexclude.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.local.galleryexclude.BuildConfig;
import dev.local.galleryexclude.ModuleInfo;
import java.util.function.Consumer;

/** User-initiated, signature-protected handshake with the already scoped Gallery process. */
public final class GalleryRestarter {
    public enum Phase { IDLE, RUNNING, SUCCEEDED, FAILED }
    public enum Failure { NONE, CHANNEL_UNAVAILABLE, RELOAD_FAILED, TIMEOUT, LAUNCH_FAILED }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Phase phase=Phase.IDLE;
    private Failure failure=Failure.NONE;
    private Consumer<Phase> listener;
    private RestartSession session;
    private long started;
    private int generation;
    public GalleryRestarter(Context context){this.context=context.getApplicationContext();}
    public Phase phase(){return phase;}
    public Failure failure(){return failure;}
    public String diagnostic(){return session==null?"NONE":"OLD_PID_"+session.oldPid();}
    public void listen(Consumer<Phase> value){listener=value;if(value!=null)value.accept(phase);}
    public void stopListening(Consumer<Phase> value){if(listener==value)listener=null;}
    public boolean start(long revision){
        if(phase==Phase.RUNNING)return false;
        session=new RestartSession(revision,BuildConfig.VERSION_CODE);started=SystemClock.uptimeMillis();generation++;
        failure=Failure.NONE;publish(Phase.RUNNING);probe(generation);return true;
    }
    private void probe(int run){
        if(run!=generation || phase!=Phase.RUNNING)return;
        if(SystemClock.uptimeMillis()-started>15000){fail(session.oldPid()==0?Failure.CHANNEL_UNAVAILABLE:Failure.TIMEOUT);return;}
        send(RestartProtocol.PROBE,new BroadcastReceiver(){
            @Override public void onReceive(Context unused,Intent intent){
                if(run!=generation || phase!=Phase.RUNNING)return;
                Bundle data=getResultExtras(false);
                RestartSession.Receipt receipt=null;
                if(getResultCode()==RestartProtocol.OK && data!=null && data.getInt("protocol")==RestartProtocol.VERSION && data.getInt("pid")>0)
                    receipt=new RestartSession.Receipt(data.getInt("pid"),data.getLong("revision",-1),data.getInt("module_version",-1));
                RestartSession.Action action=session.received(receipt);
                if(action==RestartSession.Action.SUCCEEDED){
                    if(launch())publish(Phase.SUCCEEDED);else fail(Failure.LAUNCH_FAILED);
                }else if(action==RestartSession.Action.FAILED)fail(Failure.RELOAD_FAILED);
                else if(action==RestartSession.Action.STOP){
                    send(RestartProtocol.STOP,new BroadcastReceiver(){
                        @Override public void onReceive(Context c,Intent i){schedule(run);}
                    });
                }else if(action==RestartSession.Action.LAUNCH){
                    if(launch())schedule(run);else fail(Failure.LAUNCH_FAILED);
                }else schedule(run);
            }
        });
    }
    private void send(int operation,BroadcastReceiver reply){
        Intent request=new Intent(RestartProtocol.ACTION).setPackage(ModuleInfo.TARGET_PACKAGE)
                .putExtra("protocol",RestartProtocol.VERSION).putExtra("operation",operation);
        context.sendOrderedBroadcast(request,null,reply,main,0,null,null);
    }
    private void schedule(int run){main.postDelayed(()->probe(run),200);}
    private boolean launch(){
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(ModuleInfo.TARGET_PACKAGE);
        if(launch==null)return false;
        try{context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}
        catch(RuntimeException error){return false;}
    }
    private void fail(Failure value){failure=value;publish(Phase.FAILED);}
    private void publish(Phase value){phase=value;if(listener!=null)listener.accept(value);}
}
