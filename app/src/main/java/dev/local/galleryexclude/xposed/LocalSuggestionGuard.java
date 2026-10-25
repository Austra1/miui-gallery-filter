package dev.local.galleryexclude.xposed;

import android.net.Uri;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.local.galleryexclude.filter.DisplayFilter;
import dev.local.galleryexclude.rules.PathRules;
import dev.local.galleryexclude.compat.CompatibilityState;
import dev.local.galleryexclude.compat.CompatibilityReport.Feature;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/** Local place/tag candidates are a separate file-icon route, before IconLoaderHolder. */
final class LocalSuggestionGuard {
    private static final class Frame {String kind;String failure;int removed;int rebuilt;}
    private final ThreadLocal<Frame> current=new ThreadLocal<>();
    private final PathRules rules;
    private final SearchIconGuard identities;
    private final Consumer<String> log;
    private final CompatibilityState compatibility;
    private final Function<Uri,long[]> albumCover;
    private final java.util.Set<String> reported=ConcurrentHashMap.newKeySet();
    private final List<XC_MethodHook.Unhook> hooks=new ArrayList<>();
    private final Class<?> locationNode,baseSuggestion,albumSource;
    private final Method locationQuery,sceneQuery,locationCreate,sceneCreate;
    private final Method generateSection;
    private final Method[] aiCacheReads;
    private final Method aiCacheSave;
    private final Class<?> flowable;

    LocalSuggestionGuard(ClassLoader loader,PathRules rules,SearchIconGuard identities,CompatibilityState compatibility,Consumer<String> log,Function<Uri,long[]> albumCover) throws ReflectiveOperationException {
        this.rules=rules;this.identities=identities;this.log=log;
        this.compatibility=compatibility;
        this.albumCover=albumCover;
        Class<?> location=Class.forName("com.miui.gallery.search.core.source.local.LocationSource",false,loader);
        Class<?> scene=Class.forName("com.miui.gallery.search.core.source.local.LocalSceneSource",false,loader);
        Class<?> query=Class.forName("com.miui.gallery.search.core.QueryInfo",false,loader);
        locationNode=Class.forName("com.miui.gallery.search.location.MediaLocationNode",false,loader);
        baseSuggestion=Class.forName("com.miui.gallery.search.core.suggestion.BaseSuggestion",false,loader);
        Class<?> suggestion=Class.forName("com.miui.gallery.search.core.suggestion.Suggestion",false,loader);
        Class<?> sceneNode=Class.forName("com.miui.gallery.card.scenario.SceneTagNode",false,loader);
        locationQuery=exact(location,"querySuggestion",List.class,String.class,query);
        sceneQuery=exact(scene,"querySuggestion",List.class,String.class,query);
        locationCreate=exact(location,"createLocationSuggestion",suggestion,locationNode);
        sceneCreate=exact(scene,"createSuggestion",suggestion,String.class,sceneNode);
        albumSource=Class.forName("com.miui.gallery.search.core.source.local.AlbumSource",false,loader);
        generateSection=exact(Class.forName("com.miui.gallery.search.core.source.local.LocalSingleSectionSuggestionSource",false,loader),
                "generateSection",Class.forName("com.miui.gallery.search.core.suggestion.BaseSuggestionSection",false,loader),query,List.class);
        locationNode.getConstructor(String.class,String.class);locationNode.getDeclaredField("mMediaIds");
        baseSuggestion.getConstructor();baseSuggestion.getDeclaredField("mMediaIds");
        flowable=Class.forName("io.reactivex.Flowable",false,loader);
        Class<?> cache=Class.forName("com.miui.gallery.model.datalayer.repository.album.ai.datasource.local.AIAlbumCacheDataSource",false,loader);
        aiCacheReads=new Method[]{exact(cache,"queryAIAlbumCover",flowable,Integer.class,Integer.class,Integer.class),
                exact(cache,"queryLocationsAlbum",flowable,Integer.class),exact(cache,"queryTagsAlbum",flowable,Integer.class)};
        aiCacheSave=exact(cache,"saveCache",void.class,Class.forName("com.miui.gallery.model.datalayer.utils.CacheBean",false,loader));
    }
    void install() {
        // These display caches can contain old hidden covers. The formal source still follows.
        // Do not persist this rule-specific display selection into Gallery's derived file cache.
        for(Method read:aiCacheReads)hooks.add(XposedBridge.hookMethod(read,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                p.setResult(XposedHelpers.callStaticMethod(flowable,"empty"));
                report("event=ai_cover_file_cache_bypassed operation=READ");
            }
        }));
        hooks.add(XposedBridge.hookMethod(aiCacheSave,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                String key=(String)XposedHelpers.callMethod(p.args[0],"getKey");
                if(Arrays.asList("cache_ai_cover","cache_location","cache_tags").contains(key)){
                    p.setResult(null);report("event=ai_cover_file_cache_bypassed operation=SAVE_DISPLAY_SELECTION");
                }
            }
        }));
        // Album candidates have already been ranked here. Preserve that ranking and the raw content cache.
        hooks.add(XposedBridge.hookMethod(generateSection,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if(rules.saved().isEmpty() || !albumSource.isInstance(p.thisObject) || p.args[1]==null)return;
                try {
                    List<Object> result=new ArrayList<>();
                    for(Object source:(List<?>)p.args[1]) {
                        Uri action=Uri.parse((String)XposedHelpers.callMethod(source,"getIntentActionURI"));
                        long[] cover=albumCover.apply(action);
                        Object copy=shallowSuggestion(source);
                        String icon=cover[0]>0 && cover[1]>0?imageUri(cover[1]):"";
                        XposedHelpers.setObjectField(copy,"mIcon",icon);
                        XposedHelpers.setObjectField(copy,"mBackupIcons",Collections.emptyList());
                        XposedHelpers.setObjectField(copy,"mSuggestionIcons",Collections.emptyList());
                        result.add(copy);
                    }
                    p.args[1]=result;compatibility.recovered("ALBUM_SUGGESTIONS");report("event=album_suggestions rebuilt="+result.size()+" source_mutated=false");
                }catch(ReflectiveOperationException|RuntimeException|LinkageError error){compatibility.failed("ALBUM_SUGGESTIONS",Feature.SUGGESTIONS);report("event=album_suggestions_error reason=IDENTITY_OR_PATH_UNRESOLVED");}
            }
        }));
        hooks.add(XposedBridge.hookMethod(locationQuery,boundary("LOCATION")));
        hooks.add(XposedBridge.hookMethod(sceneQuery,boundary("TAG")));
        hooks.add(XposedBridge.hookMethod(locationCreate,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Frame frame=current.get();if(frame==null || !frame.kind.equals("LOCATION") || frame.failure!=null)return;
                try {
                    List<?> raw=(List<?>)XposedHelpers.callMethod(p.args[0],"getMediaIds");
                    List<Long> ids=new ArrayList<>();for(Object id:raw)ids.add(Long.valueOf(id.toString()));
                    List<Long> visible=visible(ids,frame);if(visible==null)return;
                    if(visible.isEmpty()){p.setResult(null);frame.removed++;return;}
                    Object media=identities.original(visible.get(0));
                    String cover=(String)XposedHelpers.callMethod(media,"getAliasClearThumbnail");
                    Object copy=XposedHelpers.newInstance(locationNode,XposedHelpers.callMethod(p.args[0],"getLocationKey"),cover);
                    List<String> strings=new ArrayList<>();for(Long id:visible)strings.add(id.toString());
                    XposedHelpers.setObjectField(copy,"mMediaIds",strings);
                    p.args[0]=copy;p.setObjectExtra("visible_members",visible);frame.rebuilt++;
                }catch(RuntimeException | LinkageError error){frame.failure="FILTER_ERROR";}
            }
            @Override @SuppressWarnings("unchecked") protected void afterHookedMethod(MethodHookParam p) {
                Frame frame=current.get();List<Long> ids=(List<Long>)p.getObjectExtra("visible_members");
                if(frame==null || ids==null || p.hasThrowable() || p.getResult()==null)return;
                try{p.setResult(copySuggestion(p.getResult(),ids));}catch(ReflectiveOperationException|RuntimeException|LinkageError error){frame.failure="FILTER_ERROR";}
            }
        }));
        hooks.add(XposedBridge.hookMethod(sceneCreate,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Frame frame=current.get();if(frame==null || !frame.kind.equals("TAG") || frame.failure!=null || p.hasThrowable() || p.getResult()==null)return;
                try {
                    String csv=(String)XposedHelpers.callMethod(p.getResult(),"getSuggestionMediaIds");
                    if(csv==null || csv.isEmpty()){frame.failure="MEDIA_IDS_UNAVAILABLE";return;}
                    List<Long> ids=new ArrayList<>();for(String id:csv.split(","))ids.add(Long.valueOf(id));
                    List<Long> visible=visible(ids,frame);if(visible==null)return;
                    if(visible.isEmpty()){p.setResult(null);frame.removed++;return;}
                    // The scene cache keeps its original suggestion; only this display result is copied.
                    p.setResult(copySuggestion(p.getResult(),visible));frame.rebuilt++;
                }catch(ReflectiveOperationException|RuntimeException|LinkageError error){frame.failure="FILTER_ERROR";}
            }
        }));
    }
    private XC_MethodHook boundary(String kind) {
        return new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if(rules.saved().isEmpty() || current.get()!=null)return;
                try {
                    // Search QueryTask workers may run before the media cache has loaded.
                    // Wait at the producer boundary, never while resolving individual members.
                    if(!identities.awaitOriginals()){
                        compatibility.failed("SUGGESTIONS_"+kind,Feature.SUGGESTIONS);
                        report("event=local_suggestions kind="+kind+" status=CACHE_NOT_READY");return;
                    }
                }catch(RuntimeException | LinkageError error){compatibility.failed("SUGGESTIONS_"+kind,Feature.SUGGESTIONS);report("event=local_suggestions kind="+kind+" status=CACHE_WAIT_FAILED");return;}
                Frame frame=new Frame();frame.kind=kind;current.set(frame);p.setObjectExtra("suggestion_frame",frame);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Frame frame=(Frame)p.getObjectExtra("suggestion_frame");if(frame==null)return;current.remove();
                if(p.hasThrowable())return;
                if(frame.failure!=null){
                    compatibility.failed("SUGGESTIONS_"+kind,Feature.SUGGESTIONS);
                    p.setResult(XposedBridge.invokeOriginalMethod((Method)p.method,p.thisObject,p.args));
                }else compatibility.recovered("SUGGESTIONS_"+kind);
                String message="event=local_suggestions kind="+kind+" removed="+frame.removed+" rebuilt="+frame.rebuilt
                        +" status="+(frame.failure==null?"FILTERED":frame.failure)+" source_mutated=false cover_kind=FILE_VISIBLE";
                report(message);
            }
        };
    }
    private List<Long> visible(List<Long> ids,Frame frame) {
        DisplayFilter.Result<Long> result=DisplayFilter.select(ids,rules,id->{
            Object item=identities.original(id);
            if(item==null)return new PathRules.Resolution(PathRules.Kind.UNRESOLVED,null);
            return new PathRules.Resolution(PathRules.Kind.ORIGINAL_PATH,(String)XposedHelpers.callMethod(item,"getFilePath"));
        });
        if(result.status!=DisplayFilter.Status.FILTERED){frame.failure=result.status.name();return null;}
        return result.items;
    }
    private Object copySuggestion(Object source,List<Long> visible) throws ReflectiveOperationException {
        Object copy=shallowSuggestion(source);
        List<String> ids=new ArrayList<>();List<String> icons=new ArrayList<>();
        for(Long id:visible){ids.add(id.toString());if(icons.size()<4)icons.add(fileIcon(id));}
        XposedHelpers.setObjectField(copy,"mMediaIds",String.join(",",ids));
        XposedHelpers.setObjectField(copy,"mIcon",icons.get(0));
        // Native local sources have no backup list. AI album uses that absence to dispatch
        // through IconImageLoader; a nonempty list takes the generic Glide image:// route.
        XposedHelpers.setObjectField(copy,"mBackupIcons",Collections.emptyList());
        Object multiple=XposedHelpers.getObjectField(source,"mSuggestionIcons");
        if(multiple instanceof List && !((List<?>)multiple).isEmpty())XposedHelpers.setObjectField(copy,"mSuggestionIcons",new ArrayList<>(icons));
        return copy;
    }
    private String fileIcon(long id){
        Object item=identities.original(id);
        String displayPath=(String)XposedHelpers.callMethod(item,"getAliasClearThumbnail");
        if(displayPath==null || !displayPath.startsWith("/"))throw new IllegalStateException("DISPLAY_PATH_UNAVAILABLE");
        // Members were selected using getFilePath; this alias is only a display resource.
        // The outer AI four-cover converter requires the native FILE representation and getPath().
        return Uri.fromFile(new File(displayPath)).toString();
    }
    private Object shallowSuggestion(Object source) throws ReflectiveOperationException {
        Object copy=baseSuggestion.getConstructor().newInstance();
        for(Field field:baseSuggestion.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){
            field.setAccessible(true);field.set(copy,field.get(source));
        }
        return copy;
    }
    private static String imageUri(long id){return Uri.parse("image://image").buildUpon().appendQueryParameter("id",Long.toString(id)).build().toString();}
    private void report(String message){if(reported.add(message))log.accept(message);}
    void close(){for(XC_MethodHook.Unhook hook:hooks)hook.unhook();hooks.clear();}
    private static Method exact(Class<?> owner,String name,Class<?> returns,Class<?>...args)throws NoSuchMethodException{
        for(Method m:owner.getDeclaredMethods())if(m.getName().equals(name)&&m.getReturnType()==returns&&Arrays.equals(m.getParameterTypes(),args)){m.setAccessible(true);return m;}
        throw new NoSuchMethodException(owner.getName()+"."+name);
    }
}
