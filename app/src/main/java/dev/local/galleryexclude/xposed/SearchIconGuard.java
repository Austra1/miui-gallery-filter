package dev.local.galleryexclude.xposed;

import android.content.ContentUris;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.local.galleryexclude.rules.PathRules;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/** Checks final icon display results, including memory-cache hits. Never changes the icon caches. */
final class SearchIconGuard {
    private final PathRules rules;
    private final Consumer<String> log;
    private final Class<?> ownerType, shareType, listenerType, glide;
    private final Method consume, awaitFullLoad;
    private final Map<Long, Object> originals = new ConcurrentHashMap<>();
    private final List<Object> managers = new ArrayList<>();
    private final Map<Object, Boolean> indexed = new java.util.IdentityHashMap<>();
    private final java.util.Set<String> reported = ConcurrentHashMap.newKeySet();
    private Object observer;
    private XC_MethodHook.Unhook hook;

    SearchIconGuard(ClassLoader loader, PathRules rules, Consumer<String> log) throws ReflectiveOperationException {
        this.rules=rules; this.log=log;
        ownerType=Class.forName("com.miui.gallery.provider.cache.MediaManager",false,loader);
        shareType=Class.forName("com.miui.gallery.provider.cache.ShareMediaManager",false,loader);
        listenerType=Class.forName("com.miui.gallery.provider.cache.CacheManager$OnOperationListener",false,loader);
        awaitFullLoad=ownerType.getDeclaredMethod("isInitializedLock");
        if(awaitFullLoad.getReturnType()!=boolean.class)throw new NoSuchMethodException("MediaManager.isInitializedLock");
        Class<?> holder=Class.forName("com.miui.gallery.search.core.display.icon.IconImageLoader$IconLoaderHolder",false,loader);
        Class<?> result=Class.forName("com.miui.gallery.search.core.display.icon.IconLoaderTask$IconLoaderResult",false,loader);
        consume=holder.getDeclaredMethod("consume",result);
        if (consume.getReturnType()!=boolean.class) throw new NoSuchMethodException("IconHolder.consume");
        consume.setAccessible(true);
        result.getDeclaredField("downloadUri"); holder.getDeclaredField("mViewRef");
        glide=Class.forName("com.bumptech.glide.Glide",false,loader);
        glide.getMethod("with",View.class).getReturnType().getMethod("clear",View.class);
    }

    void install(ClassLoader loader) {
        observer=Proxy.newProxyInstance(loader,new Class<?>[]{listenerType},(proxy,method,args)-> {
            String name=method.getName();
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy==args[0];
            if (name.equals("toString")) return "GalleryExcludeReadObserver";
            try {
                if (name.equals("onInsert")) remember(args[0],false);
                else if (Arrays.asList("onBulkInsert","onUpdate","onRemove").contains(name))
                    for(Object item:(List<?>)args[0]) remember(item,name.equals("onRemove"));
            } catch (RuntimeException error) { report("event=icon_identity_error reason=UPDATE_FAILED"); }
            return null;
        });
        for (Class<?> type:Arrays.asList(ownerType,shareType)) {
            Object manager=XposedHelpers.callStaticMethod(type,"getInstance");
            XposedHelpers.callMethod(manager,"addOperationListener",observer); managers.add(manager);
        }
        hook=XposedBridge.hookMethod(consume,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (rules.saved().isEmpty() || p.args[0]==null) return;
                try {
                    Uri download=(Uri)XposedHelpers.getObjectField(p.args[0],"downloadUri");
                    if (download==null || !"com.miui.gallery.cloud.provider".equals(download.getAuthority())) {
                        report("event=icon_identity_error reason=MEDIA_ID_UNAVAILABLE"); return;
                    }
                    long id=ContentUris.parseId(download);
                    if (download.getPathSegments().contains("share_image"))
                        id=((Number)XposedHelpers.callStaticMethod(shareType,"convertToMediaId",id)).longValue();
                    indexReadyCaches();
                    Object item=originals.get(id);
                    if (item==null) {report("event=icon_identity_error reason=MEDIA_NOT_IN_CACHE");return;}
                    String original=(String)XposedHelpers.callMethod(item,"getFilePath");
                    if (!rules.matchesOriginalPath(original)) return;
                    // Null follows the application's backup-icon policy. Clear an older bound image too.
                    WeakReference<?> ref=(WeakReference<?>)XposedHelpers.getObjectField(p.thisObject,"mViewRef");
                    Object view=ref==null?null:ref.get();
                    if (view instanceof ImageView) {
                        Object requests=XposedHelpers.callStaticMethod(glide,"with",view);
                        XposedHelpers.callMethod(requests,"clear",view);
                        ((ImageView)view).setImageDrawable(null);
                    }
                    p.args[0]=null;
                    report("event=search_icon_excluded identity=ORIGINAL_MEDIA cache_mutated=false");
                } catch (PathRules.InvalidPath error) {report("event=icon_identity_error reason=PATH_UNRESOLVED");}
                catch (RuntimeException error) {report("event=icon_identity_error reason=FILTER_ERROR");}
            }
        });
    }

    private synchronized void indexReadyCaches() {
        for(Object manager:managers) {
            if (indexed.containsKey(manager) || !(Boolean)XposedHelpers.callMethod(manager,"isInitialized")) continue;
            Lock lock=(Lock)XposedHelpers.getObjectField(manager,"mReadLock");lock.lock();
            try {for(Object item:(List<?>)XposedHelpers.getObjectField(manager,"mCache")) remember(item,false);}
            finally {lock.unlock();}
            indexed.put(manager,true);
        }
    }
    private void remember(Object item,boolean remove) {
        long id=((Number)XposedHelpers.callMethod(item,"getId")).longValue();
        if(remove) originals.remove(id);else originals.put(id,item);
    }
    Object original(long id) { indexReadyCaches();return originals.get(id); }
    boolean awaitOriginals() {
        // Called by local suggestion producer workers, before holding any module/cache lock.
        // Native full-load completion also includes ShareMediaManager.load(). Initial loading
        // publishes directly to mCache, so operation observers alone cannot provide these IDs.
        Object owner=XposedHelpers.callStaticMethod(ownerType,"getInstance");
        if(!(Boolean)XposedHelpers.callMethod(owner,awaitFullLoad.getName()))return false;
        indexReadyCaches();return true;
    }
    private void report(String message) {if(reported.add(message)) log.accept(message);}
    void close() {
        if(hook!=null) hook.unhook();
        for(Object manager:managers) XposedHelpers.callMethod(manager,"removeOperationListener",observer);
        managers.clear();originals.clear();
    }
}
