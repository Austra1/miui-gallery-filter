package dev.local.galleryexclude.xposed;

import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.local.galleryexclude.filter.DisplayFilter;
import dev.local.galleryexclude.filter.PreviewIndex;
import dev.local.galleryexclude.filter.TrashPath;
import dev.local.galleryexclude.filter.VisibleCursor;
import dev.local.galleryexclude.rules.PathRules;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Trash display snapshots and previews only; never filters deletion/recovery or the provider. */
final class TrashDisplayGuard {
    private final PathRules rules;
    private final Consumer<String> log;
    private final Class<?> trashLoader;
    private final String primaryStorage;
    private final Method sort,size,query,wrap,countFactory,countLoad;
    private final Map<Object,Long> selected=Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> countOwners=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<String> reported=ConcurrentHashMap.newKeySet();
    private final List<XC_MethodHook.Unhook> hooks=new ArrayList<>();
    private static final Uri URI=Uri.parse("content://com.miui.gallery.cloud.provider/trash_bin");

    TrashDisplayGuard(ClassLoader loader,PathRules rules,Consumer<String> log)throws ReflectiveOperationException{
        this.rules=rules;this.log=log;
        Class<?> viewModel=Class.forName("com.miui.gallery.viewmodel.TrashViewModel",false,loader);
        trashLoader=Class.forName("com.miui.gallery.loader.TrashPhotoSetLoader",false,loader);
        Class<?> storage=Class.forName("com.miui.gallery.util.StorageUtils",false,loader);
        sort=exact(viewModel,"sortTrashList",void.class,ArrayList.class);
        size=exact(viewModel,"computeTrashSize",long.class);
        query=exact(Class.forName("com.miui.gallery.loader.CursorSetLoader",false,loader),"query",Cursor.class,
                Uri.class,String[].class,String.class,String[].class,String.class,String.class);
        wrap=exact(trashLoader,"wrapDataSet",Class.forName("com.miui.gallery.model.CursorDataSet",false,loader),Cursor.class);
        Class<?> custom=Class.forName("com.miui.gallery.model.datalayer.utils.loader.CustomCursorLoader",false,loader);
        countFactory=exact(Class.forName("com.miui.gallery.dao.AlbumTableServices",false,loader),"getQueryTrashAlbumPhotoCountLoader",custom,Context.class);
        countLoad=exact(custom,"loadInBackground",Cursor.class);
        viewModel.getDeclaredField("mContentResolver");viewModel.getDeclaredMethod("buildSelection",List.class);
        Class<?> item=Class.forName("com.miui.gallery.trash.TrashBinItem",false,loader);
        item.getMethod("getAlbumPath");item.getMethod("getFileName");storage.getMethod("getPrimaryStoragePath");
        primaryStorage=(String)XposedHelpers.callStaticMethod(storage,"getPrimaryStoragePath");
    }
    void install(){
        hook(sort,new XC_MethodHook(){
            @Override @SuppressWarnings("unchecked") protected void beforeHookedMethod(MethodHookParam p){
                // Both callers construct a dedicated publication ArrayList from the unchanged cached map.
                List<Object> display=(List<Object>)p.args[0];
                DisplayFilter.Result<Object> result=DisplayFilter.select(display,rules,item->resolution(
                        (String)XposedHelpers.callMethod(item,"getAlbumPath"),(String)XposedHelpers.callMethod(item,"getFileName")));
                int input=display.size(),excluded=input-result.items.size();
                if(result.status==DisplayFilter.Status.FILTERED){display.clear();display.addAll(result.items);}
                report("event=trash_display input="+input+" excluded="+excluded+" status="+result.status+" source_cache_mutated=false");
            }
        });
        hook(size,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                try{
                    ContentResolver resolver=(ContentResolver)XposedHelpers.getObjectField(p.thisObject,"mContentResolver");
                    String selection=(String)XposedHelpers.callMethod(p.thisObject,"buildSelection",(Object)null);
                    long[] stats=stats(resolver,selection,null);p.setResult(stats[1]);
                }catch(RuntimeException error){report("event=trash_display_error consumer=SIZE reason=ORIGINAL_LOCATION_UNAVAILABLE");}
            }
        });
        hooks.addAll(XposedBridge.hookAllConstructors(trashLoader,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.args.length!=3 || !(p.args[2] instanceof Bundle))return;
                Object params=((Bundle)p.args[2]).getParcelable("photo_transition_data");
                if(params!=null)selected.put(p.thisObject,((Number)XposedHelpers.callMethod(params,"getKey")).longValue());
            }
        }));
        hook(query,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(!trashLoader.isInstance(p.thisObject) || p.hasThrowable() || !(p.getResult() instanceof Cursor))return;
                Cursor source=(Cursor)p.getResult();int before=source.getPosition();
                try{
                    if(!selected.containsKey(p.thisObject) && source.moveToPosition(XposedHelpers.getIntField(p.thisObject,"mInitPos")))
                        selected.put(p.thisObject,source.getLong(source.getColumnIndexOrThrow("_id")));
                    DisplayFilter.Result<Integer> result=select(source);
                    if(result.status==DisplayFilter.Status.FILTERED)p.setResult(new VisibleCursor(source,result.items));
                    report("event=trash_preview input="+source.getCount()+" excluded="+(source.getCount()-result.items.size())+" status="+result.status);
                }catch(RuntimeException error){report("event=trash_display_error consumer=PREVIEW reason=ORIGINAL_LOCATION_UNAVAILABLE");}
                finally{source.moveToPosition(before);}
            }
        });
        hook(wrap,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.hasThrowable() || p.getResult()==null || !(p.args[0] instanceof Cursor))return;
                Long key=selected.get(p.thisObject);if(key==null)return;
                Cursor cursor=(Cursor)XposedHelpers.getObjectField(p.getResult(),"mCursor");
                int before=cursor.getPosition();List<Long> ids=new ArrayList<>();
                try{for(int i=0;i<cursor.getCount();i++)if(cursor.moveToPosition(i))ids.add(cursor.getLong(0));}
                finally{cursor.moveToPosition(before);}
                XposedHelpers.callMethod(p.getResult(),"setInitPosition",PreviewIndex.locate(ids,key,0));
            }
        });
        hook(countFactory,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable() && p.getResult()!=null)countOwners.add(p.getResult());}
        });
        hook(countLoad,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(!countOwners.contains(p.thisObject) || p.hasThrowable() || !(p.getResult() instanceof Cursor))return;
                try{
                    long[] stats=stats(((Context)XposedHelpers.callMethod(p.thisObject,"getContext")).getContentResolver(),
                            (String)XposedHelpers.getObjectField(p.thisObject,"mSelection"),
                            (String[])XposedHelpers.getObjectField(p.thisObject,"mSelectionArgs"));
                    p.setResult(new CountCursor((Cursor)p.getResult(),stats[0]));
                    report("event=trash_count visible="+stats[0]+" status=FILTERED");
                }catch(RuntimeException error){report("event=trash_display_error consumer=COUNT reason=ORIGINAL_LOCATION_UNAVAILABLE");}
            }
        });
    }
    private PathRules.Resolution resolution(String album,String file){
        return new PathRules.Resolution(PathRules.Kind.ORIGINAL_PATH,TrashPath.original(album,file,primaryStorage));
    }
    private DisplayFilter.Result<Integer> select(Cursor source){
        int album=source.getColumnIndexOrThrow("albumPath"),file=source.getColumnIndexOrThrow("fileName");
        List<Integer> rows=new ArrayList<>();for(int i=0;i<source.getCount();i++)rows.add(i);
        return DisplayFilter.select(rows,rules,i->{source.moveToPosition(i);return resolution(source.getString(album),source.getString(file));});
    }
    private long[] stats(ContentResolver resolver,String selection,String[] args){
        try(Cursor cursor=resolver.query(URI,new String[]{"albumPath","fileName","imageSize"},selection,args,null)){
            if(cursor==null)throw new IllegalStateException("TRASH_QUERY_UNAVAILABLE");
            DisplayFilter.Result<Integer> result=select(cursor);
            if(result.status!=DisplayFilter.Status.FILTERED)throw new IllegalStateException("TRASH_PATH_UNRESOLVED");
            long bytes=0;for(int row:result.items){cursor.moveToPosition(row);bytes+=cursor.getLong(2);}
            return new long[]{result.items.size(),bytes};
        }
    }
    private static final class CountCursor extends CursorWrapper{
        private final long count;
        CountCursor(Cursor source,long count){super(source);this.count=count;}
        @Override public int getInt(int column){return (int)count;}
        @Override public long getLong(int column){return count;}
        @Override public short getShort(int column){return (short)count;}
        @Override public float getFloat(int column){return count;}
        @Override public double getDouble(int column){return count;}
        @Override public String getString(int column){return Long.toString(count);}
    }
    void close(){for(XC_MethodHook.Unhook hook:hooks)hook.unhook();hooks.clear();}
    private void hook(Method method,XC_MethodHook callback){hooks.add(XposedBridge.hookMethod(method,callback));}
    private void report(String message){if(reported.add(message))log.accept(message);}
    private static Method exact(Class<?> owner,String name,Class<?> returns,Class<?>...args)throws NoSuchMethodException{
        for(Method method:owner.getDeclaredMethods())if(method.getName().equals(name)&&method.getReturnType()==returns&&Arrays.equals(method.getParameterTypes(),args)){
            method.setAccessible(true);return method;
        }
        throw new NoSuchMethodException(owner.getName()+"."+name);
    }
}
