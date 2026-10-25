package dev.local.galleryexclude.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.local.galleryexclude.config.StorageDocuments;
import dev.local.galleryexclude.filter.DisplayFilter;
import dev.local.galleryexclude.filter.VisibleCursor;
import dev.local.galleryexclude.rules.PathRules;
import dev.local.galleryexclude.compat.CompatibilityState;
import dev.local.galleryexclude.compat.CompatibilityReport.Feature;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Gallery-owned external viewers only. Never hooks MediaProvider, file access, or another app. */
final class ExternalPreviewGuard {
    private final PathRules rules;
    private final Consumer<String> log;
    private final CompatibilityState compatibility;
    private final Class<?> activityType,mediaLoader,contentUtils;
    private final Method create,permissions,newIntent,query;
    private final Constructor<?> storageData;
    private final List<XC_MethodHook.Unhook> hooks=new ArrayList<>();
    private final Set<String> reported=ConcurrentHashMap.newKeySet();

    ExternalPreviewGuard(ClassLoader loader,PathRules rules,CompatibilityState compatibility,Consumer<String> log)throws ReflectiveOperationException{
        this.rules=rules;this.compatibility=compatibility;this.log=log;
        activityType=Class.forName("com.miui.gallery.activity.BaseExternalPhotoPageActivity",false,loader);
        mediaLoader=Class.forName("com.miui.gallery.loader.MediaSetLoader",false,loader);
        contentUtils=Class.forName("com.miui.gallery.util.ContentUtils",false,loader);
        create=exact(activityType,"onCreate",void.class,Bundle.class);
        permissions=exact(activityType,"onPermissionsChecked",void.class,
                Class.forName("[Lcom.miui.gallery.permission.core.Permission;",false,loader),int[].class,boolean[].class);
        newIntent=Activity.class.getDeclaredMethod("onNewIntent",Intent.class);
        query=exact(Class.forName("com.miui.gallery.loader.CursorSetLoader",false,loader),"query",Cursor.class,
                Uri.class,String[].class,String.class,String[].class,String.class,String.class);
        Class<?> storageLoader=Class.forName("com.miui.gallery.loader.StorageSetLoader",false,loader);
        Class<?> data=Class.forName("com.miui.gallery.loader.StorageSetLoader$StorageDataSet",false,loader);
        storageData=data.getDeclaredConstructor(storageLoader,List.class,String.class);storageData.setAccessible(true);
        contentUtils.getDeclaredMethod("getValidFilePathForContentUri",Context.class,Uri.class);
    }
    void install(){
        XC_MethodHook entry=new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                Activity activity=(Activity)p.thisObject;
                if(blocked(activity,activity.getIntent())){
                    activity.setIntent(withoutMedia(activity.getIntent()));
                    if(p.method.equals(create))p.args[0]=null; // Do not restore a previously loaded photo fragment.
                    p.setObjectExtra("excluded_external",true);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.getObjectExtra("excluded_external")==null)return;
                Activity activity=(Activity)p.thisObject;
                if(!activity.isFinishing())activity.finish();
                Toast.makeText(activity.getApplicationContext(),"该目录已被排除",Toast.LENGTH_SHORT).show();
            }
        };
        hook(create,entry);hook(permissions,entry);
        hook(newIntent,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(!activityType.isInstance(p.thisObject))return;
                Activity activity=(Activity)p.thisObject;
                if(blocked(activity,(Intent)p.args[0])){
                    p.args[0]=withoutMedia((Intent)p.args[0]);
                    activity.setIntent((Intent)p.args[0]);p.setObjectExtra("excluded_external",true);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.getObjectExtra("excluded_external")!=null)((Activity)p.thisObject).finish();
            }
        });
        hook(query,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(!mediaLoader.isInstance(p.thisObject) || p.hasThrowable() || !(p.getResult() instanceof Cursor))return;
                Cursor source=(Cursor)p.getResult();int before=source.getPosition();
                try{
                    int column=source.getColumnIndex("_data");if(column<0)column=source.getColumnIndexOrThrow("media_path");
                    final int pathColumn=column;
                    List<Integer> rows=new ArrayList<>();for(int i=0;i<source.getCount();i++)rows.add(i);
                    DisplayFilter.Result<Integer> result=DisplayFilter.select(rows,rules,i->{
                        source.moveToPosition(i);
                        return new PathRules.Resolution(PathRules.Kind.ORIGINAL_PATH,source.getString(pathColumn));
                    });
                    if(result.status==DisplayFilter.Status.FILTERED)p.setResult(new VisibleCursor(source,result.items));
                    status("EXTERNAL_CURSOR",result.status);
                    report("event=external_media_query input="+source.getCount()+" excluded="+(source.getCount()-result.items.size())+" status="+result.status);
                }catch(RuntimeException | LinkageError error){compatibility.failed("EXTERNAL_CURSOR",Feature.EXTERNAL_PREVIEW);report("event=external_preview_error consumer=MEDIA_CURSOR reason=PATH_UNAVAILABLE");}
                finally{source.moveToPosition(before);}
            }
        });
        hook(storageData,new XC_MethodHook(){
            @Override @SuppressWarnings("unchecked") protected void beforeHookedMethod(MethodHookParam p){
                if(p.args[1]==null)return;
                List<File> source=(List<File>)p.args[1];
                DisplayFilter.Result<File> result=DisplayFilter.select(source,rules,file->new PathRules.Resolution(
                        PathRules.Kind.ORIGINAL_PATH,file.getAbsolutePath()));
                if(result.status==DisplayFilter.Status.FILTERED)p.args[1]=result.items;
                status("EXTERNAL_STORAGE",result.status);
                report("event=external_storage_query input="+source.size()+" excluded="+(source.size()-result.items.size())+" status="+result.status);
            }
        });
    }
    private boolean blocked(Context context,Intent intent){
        if(intent==null || intent.getData()==null)return false;
        Uri uri=intent.getData();
        try{
            String path=originalPath(context,uri);
            if(path==null){
                if("content".equals(uri.getScheme())){compatibility.failed("EXTERNAL_ENTRY",Feature.EXTERNAL_PREVIEW);report("event=external_preview_error consumer=ENTRY reason=ORIGINAL_PATH_UNAVAILABLE");}
                return false;
            }
            boolean excluded=rules.matchesOriginalPath(path);
            compatibility.recovered("EXTERNAL_ENTRY");
            report("event=external_preview_entry scheme="+("file".equals(uri.getScheme())?"FILE":"CONTENT")
                    +" status="+(excluded?"EXCLUDED_BEFORE_PRELOAD":"VISIBLE"));
            return excluded;
        }catch(RuntimeException | LinkageError error){compatibility.failed("EXTERNAL_ENTRY",Feature.EXTERNAL_PREVIEW);report("event=external_preview_error consumer=ENTRY reason=PATH_UNAVAILABLE");return false;}
    }
    private void status(String consumer,DisplayFilter.Status status){
        if(status==DisplayFilter.Status.FILTERED || status==DisplayFilter.Status.BYPASS)compatibility.recovered(consumer);
        else compatibility.failed(consumer,Feature.EXTERNAL_PREVIEW);
    }
    private String originalPath(Context context,Uri uri){
        if("file".equals(uri.getScheme()))return uri.getPath();
        if(!"content".equals(uri.getScheme()))return null;
        String document=StorageDocuments.documentFile(context,uri);if(document!=null)return document;
        // Same metadata-only resolver used by Gallery's ContentProxyLoader; no stream or media decoding.
        return (String)XposedHelpers.callStaticMethod(contentUtils,"getValidFilePathForContentUri",context,uri);
    }
    private static Intent withoutMedia(Intent original){
        Intent intent=new Intent(original);intent.setData(null);intent.replaceExtras((Bundle)null);intent.setClipData(null);return intent;
    }
    void close(){for(XC_MethodHook.Unhook hook:hooks)hook.unhook();hooks.clear();}
    private void hook(java.lang.reflect.Member member,XC_MethodHook callback){hooks.add(XposedBridge.hookMethod(member,callback));}
    private void report(String message){if(reported.add(message))log.accept(message);}
    private static Method exact(Class<?> owner,String name,Class<?> returns,Class<?>...args)throws NoSuchMethodException{
        for(Method method:owner.getDeclaredMethods())if(method.getName().equals(name)&&method.getReturnType()==returns&&Arrays.equals(method.getParameterTypes(),args)){
            method.setAccessible(true);return method;
        }
        throw new NoSuchMethodException(owner.getName()+"."+name);
    }
}
