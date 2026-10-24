package dev.local.galleryexclude.xposed;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.SparseArray;
import dev.local.galleryexclude.filter.DisplayFilter;
import dev.local.galleryexclude.filter.PreviewIndex;
import dev.local.galleryexclude.filter.QueryScope;
import dev.local.galleryexclude.filter.HeaderConsistency;
import dev.local.galleryexclude.filter.DetachedCopy;
import dev.local.galleryexclude.rules.PathRules;
import dev.local.galleryexclude.rules.RuleConfiguration;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/** Exact-version adapter. Only consumer-owned synchronous reads can reach the cache predicate. */
public final class Gallery35710Adapter {
    private final ClassLoader loader;
    private final RuleConfiguration config;
    private final Consumer<String> log;
    private final QueryScope scope = new QueryScope();
    private final ThreadLocal<Integer> startup = new ThreadLocal<>();
    private final Map<Object, String> liveOwners = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, String> cursorOwners = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Long> previewKeys = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final List<XC_MethodHook.Unhook> installed = new ArrayList<>();
    private volatile Object homeSource;
    private Class<?> mediaManager, shareManager, mediaItem, albumManager, albumItem, album, coverBean;
    private Class<?> liveData, homeViewModel, detailViewModel, mapViewModel, pickerViewModel, startupHelper, cloudLoader, searchLoader, clustered;
    private Method cursorCompute,legacyFactory,pickerFactory,recentPickerFactory;
    private Method compute, filter, mediaPath, mediaSize, mediaType, albumCount, albumCover, albumCopy;
    private Method homeJob, loadSnapshot, preloadCursor, albumDisplay, albumSnapshot, searchResult;
    private Method previewQuery, cloudWrap, searchWrap, homeIds, homeSnapshot;
    private Method snapshotPersist;
    private Field albumCache;
    private SearchIconGuard icons;
    private LocalSuggestionGuard suggestions;
    private TrashDisplayGuard trash;
    private ExternalPreviewGuard external;

    public Gallery35710Adapter(ClassLoader loader, RuleConfiguration config, Consumer<String> log) {
        this.loader = loader; this.config = config; this.log = log;
    }

    public void install() throws Throwable {
        // Resolve every required member before installing any hook. No fuzzy overload matching.
        preflight();
        try {
            ownLiveData(homeViewModel, "HOME", "liveData");
            ownLiveData(detailViewModel, "DETAIL", "liveData");
            // Map markers and the smart-album cover are separate consumer-owned queries.
            // Filter before the native eight-cover limit, coordinate clustering and marker counts.
            ownLiveData(mapViewModel, "MAP", "mAllMediasShowOnMap");
            ownLiveData(mapViewModel, "MAP_COVERS", "mMapAlbumCovers");
            ownLiveData(pickerViewModel, "PICKER_HOME", "liveData");
            ownCursorLoader(legacyFactory,"LEGACY_DETAIL");
            ownCursorLoader(pickerFactory,"PICKER_DETAIL");
            ownCursorLoader(recentPickerFactory,"PICKER_RECENT");
            hook(compute, readBoundary(null));
            hook(cursorCompute,readBoundary("CURSOR_LIST"));
            hook(filter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { filterMedia(p); }
                    catch (Throwable error) {
                        QueryScope.Frame frame = scope.active(); if (frame != null) frame.failure = "FILTER_ERROR";
                        report("event=filter_error reason=" + safeReason(error));
                    }
                }
            });
            hook(homeJob, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || scope.isSuspended()) return;
                    Integer n = startup.get(); startup.set(n == null ? 1 : n + 1);
                    p.setObjectExtra("startup", true);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("startup") == null) return;
                    int n = startup.get(); if (n == 1) startup.remove(); else startup.set(n - 1);
                }
            });
            hook(loadSnapshot, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (enabled() && startup.get() != null && !scope.isSuspended()) {
                        p.setResult(null); report("event=snapshot_bypassed kind=HOME_DISK");
                    }
                }
            });
            hook(preloadCursor, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (enabled()) { p.setResult(null); report("event=snapshot_bypassed kind=HOME_MEMORY"); }
                }
            });
            // The job also reads this key directly. Its other/background consumers keep original data.
            hook(method(type("com.miui.gallery.util.GalleryMemoryCacheHelper"), "remove", Object.class, String.class),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (enabled() && startup.get() != null && "first_home_page_datas".equals(p.args[0]))
                                p.setResult(null);
                        }
                    });
            // UI snapshots and persisted first-page IDs receive an independent UNFILTERED query result.
            XC_MethodHook sourceOnly = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    try {
                        if (homeSource == null) throw new IllegalStateException("HOME_SOURCE_NOT_READY");
                        Object source = snapshotView(homeSource, p.thisObject);
                        validateSnapshot(source);
                        p.args[0] = source;
                        report("event=snapshot_source kind=HOME unfiltered=true");
                    } catch (Throwable error) {
                        p.setResult(null); // Never let a failed adapter forward a filtered snapshot for persistence.
                        report("event=snapshot_source_error reason=" + safeReason(error));
                    }
                }
            };
            hook(homeIds, sourceOnly); hook(homeSnapshot, sourceOnly);
            hook(snapshotPersist, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    Object snapshot = p.args[0];
                    int count = ((List<?>) XposedHelpers.callMethod(snapshot, "getMedias")).size();
                    report("event=original_snapshot_persisted success=" + (!p.hasThrowable() && Boolean.TRUE.equals(p.getResult()))
                            + " medias=" + count);
                }
            });
            hook(albumSnapshot, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (enabled()) {
                        p.setResult(XposedHelpers.callStaticMethod(typeUnchecked("io.reactivex.Flowable"), "empty"));
                        report("event=snapshot_bypassed kind=ALBUM_DISPLAY");
                    }
                }
            });
            hook(albumDisplay, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (enabled() && !scope.isSuspended()) p.args[0] = displayAlbums((List<?>) p.args[0]);
                }
            });
            hook(searchResult, readBoundary("SEARCH"));
            XposedBridge.hookAllConstructors(cloudLoader, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length != 3 || !(p.args[2] instanceof Bundle)) return;
                    Object params = ((Bundle) p.args[2]).getParcelable("photo_transition_data");
                    if (params != null) previewKeys.put(p.thisObject, ((Number) XposedHelpers.callMethod(params, "getKey")).longValue());
                }
            }).forEach(installed::add);
            hook(previewQuery, readBoundary("PREVIEW"));
            XC_MethodHook wrap = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable { preparePreview(p); }
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable { finishPreview(p); }
            };
            hook(cloudWrap, wrap); hook(searchWrap, wrap);
            if (enabled()) { icons.install(loader); suggestions.install(); trash.install(); external.install(); }
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            if (icons != null) icons.close();
            if (suggestions != null) suggestions.close();
            if (trash != null) trash.close();
            if (external != null) external.close();
            installed.clear(); throw error;
        }
        log.accept("event=filter_adapter_installed revision=" + config.revision + " filtering=" + enabled()
                + " adapter=gallery_3_5_7_10 installed_hooks=home_detail_album_map_search_preview_icons_trash_picker_external");
    }

    private boolean enabled() { return !config.rules.saved().isEmpty(); }

    private void ownLiveData(Class<?> owner, String name, String field) {
        installed.addAll(XposedBridge.hookAllConstructors(owner, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                liveOwners.put(XposedHelpers.getObjectField(p.thisObject, field), name);
            }
        }));
    }

    private void ownCursorLoader(Method factory,String name){
        hook(factory,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(!p.hasThrowable() && p.getResult()!=null)cursorOwners.put(p.getResult(),name);
            }
        });
    }

    private XC_MethodHook readBoundary(String fixed) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled() || scope.isSuspended() || scope.active() != null) return;
                String consumer = fixed;
                if("CURSOR_LIST".equals(consumer)){
                    consumer=cursorOwners.get(p.thisObject);
                    if(consumer==null || !localMedia((Uri)XposedHelpers.callMethod(p.thisObject,"getUri")))return;
                }
                if (consumer == null) {
                    // Map observers can compute before queryItemsShowOnMap supplies a URI.
                    // Native compute then only clears observers and returns empty; it reads no media.
                    if (XposedHelpers.getObjectField(p.thisObject, "uri") == null) return;
                    consumer = liveOwners.get(p.thisObject);
                    if (consumer == null && startup.get() != null) consumer = "HOME_STARTUP";
                }
                if (consumer == null) return;
                if ("PREVIEW".equals(consumer) && (!cloudLoader.isInstance(p.thisObject) || !localMedia((Uri) p.args[0]))) return;
                if (consumer.startsWith("HOME")) {
                    Uri uri = (Uri) XposedHelpers.getObjectField(p.thisObject, "uri");
                    if (uri != null && uri.getQueryParameter("require_full_load") == null) {
                        p.setObjectExtra("original_uri", uri);
                        XposedHelpers.setObjectField(p.thisObject, "uri", uri.buildUpon().appendQueryParameter("require_full_load", "true").build());
                    }
                }
                p.setObjectExtra("display_frame", scope.enter(consumer));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                QueryScope.Frame frame = (QueryScope.Frame) p.getObjectExtra("display_frame");
                if (frame == null) return;
                scope.exit(frame);
                try {
                if (p.hasThrowable()) { report("event=filter_query_error consumer=" + frame.consumer); return; }
                if (frame.filterCalls == 0) frame.failure = "READ_CHAIN_NOT_REACHED";
                boolean home = frame.consumer.startsWith("HOME");
                // Re-read for the original persistence consumers, or for all-or-original error recovery.
                if (home || frame.failure != null) {
                    scope.suspend();
                    try {
                        Object original = XposedBridge.invokeOriginalMethod((Method) p.method, p.thisObject, p.args);
                        if (home) homeSource = original;
                        if (frame.failure != null) {
                            if (p.getResult() instanceof Cursor && p.getResult() != original) ((Cursor) p.getResult()).close();
                            p.setResult(original);
                        }
                    } catch (Throwable error) {
                        if (home) homeSource = null;
                        report("event=filter_recovery_error consumer=" + frame.consumer + " reason=" + safeReason(error));
                        if (frame.failure != null) {
                            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                                    ? ((java.lang.reflect.InvocationTargetException) error).getCause() : error;
                            p.setThrowable(cause); // Preserve the original query's cancellation/error, never a partially filtered result.
                        }
                    } finally { scope.resume(); }
                }
                report("event=display_query consumer=" + frame.consumer + " revision=" + config.revision
                        + " input=" + frame.inputCount + " excluded=" + frame.excludedCount
                        + " excluded_images=" + frame.excludedImages + " excluded_videos=" + frame.excludedVideos
                        + " status=" + (frame.failure == null ? "FILTERED" : frame.failure));
                } finally {
                    Object uri = p.getObjectExtra("original_uri");
                    if (uri != null) XposedHelpers.setObjectField(p.thisObject, "uri", uri);
                }
            }
        };
    }

    private void filterMedia(XC_MethodHook.MethodHookParam p) throws Throwable {
        QueryScope.Frame frame = scope.active();
        if (frame == null || p.hasThrowable() || !(mediaManager.isInstance(p.thisObject) || shareManager.isInstance(p.thisObject))) return;
        List<?> source = (List<?>) p.getResult();
        frame.filterCalls++;
        frame.inputCount += source.size();
        if (frame.failure != null) return;
        DisplayFilter.Result<?> selected = DisplayFilter.select(source, config.rules, item -> {
            try {
                if (!mediaItem.isInstance(item)) return new PathRules.Resolution(PathRules.Kind.UNRESOLVED, null);
                String path = (String) mediaPath.invoke(item);
                return new PathRules.Resolution(PathRules.Kind.ORIGINAL_PATH, path);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("PATH_ACCESS_FAILED", error); }
        });
        if (selected.status != DisplayFilter.Status.FILTERED) { frame.failure = selected.status.name(); return; }
        frame.excludedCount += source.size() - selected.items.size();
        java.util.IdentityHashMap<Object, Boolean> visible = new java.util.IdentityHashMap<>();
        for (Object item : selected.items) visible.put(item, true);
        for (Object item : source) if (!visible.containsKey(item)) {
            int type = ((Number) mediaType.invoke(item)).intValue();
            if (type == 1) frame.excludedImages++;
            else if (type == 2) frame.excludedVideos++;
        }
        frame.selectedBytes = 0;
        for (Object item : selected.items) frame.selectedBytes += ((Number) mediaSize.invoke(item)).longValue();
        p.setResult(selected.items);
    }

    private Object snapshotView(Object source, Object fragment) {
        if (!clustered.isInstance(source)) return source;
        Object mode = XposedHelpers.getObjectField(fragment, "mViewMode");
        int key = ((Number) XposedHelpers.callMethod(mode, "getClusterKey")).intValue();
        // A new wrapper owns its selection state; its content/cluster objects are only read here.
        return XposedHelpers.newInstance(clustered, XposedHelpers.getObjectField(source, "contents"),
                XposedHelpers.getObjectField(source, "sections"), XposedHelpers.getObjectField(source, "clusters"), false, key);
    }

    @SuppressWarnings("unchecked")
    private void validateSnapshot(Object source) {
        Object companion = XposedHelpers.getStaticObjectField(typeUnchecked("com.miui.gallery.activity.HomePageStartupHelper2$Snapshot"), "Companion");
        Object snapshot = XposedHelpers.callMethod(companion, "build", source);
        Object header = XposedHelpers.callMethod(snapshot, "getHeader");
        Class<?> headerType = typeUnchecked("com.miui.gallery.model.HomeMediaHeader");
        List<Integer> counts = (List<Integer>) XposedHelpers.callStaticMethod(headerType, "getGroupItemCount", header);
        List<Integer> starts = (List<Integer>) XposedHelpers.callStaticMethod(headerType, "getGroupItemStartPos", header);
        int count = ((List<?>) XposedHelpers.callMethod(snapshot, "getMedias")).size();
        if (!HeaderConsistency.valid(count, counts, starts)) throw new IllegalStateException("SNAPSHOT_HEADER_MISMATCH");
        report("event=snapshot_source_validated medias=" + count + " groups=" + (counts == null ? 0 : counts.size()));
    }

    private List<?> displayAlbums(List<?> source) {
        QueryScope.Frame frame = scope.enter("ALBUM_DISPLAY");
        try {
            Object manager = XposedHelpers.callStaticMethod(albumManager, "getInstance");
            Object media = XposedHelpers.callStaticMethod(mediaManager, "getInstance");
            if (!(Boolean) XposedHelpers.callMethod(media, "isInitialized")) throw new IllegalStateException("CACHE_NOT_READY");
            Map<Long, Object> originals = new HashMap<>();
            Lock lock = (Lock) XposedHelpers.getObjectField(manager, "mReadLock");
            lock.lock();
            try {
                for (Object item : (List<?>) albumCache.get(manager)) originals.put(((Number) XposedHelpers.callMethod(item, "getId")).longValue(), item);
            } finally { lock.unlock(); }
            List<Object> result = new ArrayList<>(source.size());
            for (Object dto : source) {
                long id = ((Number) XposedHelpers.callMethod(dto, "getAlbumId")).longValue();
                Object original = originals.get(id);
                if (original == null) throw new IllegalStateException("ALBUM_SOURCE_MISSING");
                Object copy = albumCopy.invoke(original);
                // The application's copy() omits this field, which its cover comparator reads.
                XposedHelpers.setObjectField(copy, "isVirtualAlbum", XposedHelpers.getObjectField(original, "isVirtualAlbum"));
                frame.selectedBytes = 0;
                int calls = frame.filterCalls;
                List<?> items = (List<?>) albumCount.invoke(copy, null, null);
                if (calls == frame.filterCalls) throw new IllegalStateException("ALBUM_MEDIA_CACHE_NOT_READY");
                long bytes = frame.selectedBytes;
                albumCover.invoke(copy, items); // Manual cover re-query passes through the same predicate.
                if (frame.failure != null) throw new IllegalStateException(frame.failure);
                Object display = DetachedCopy.copy(dto, album);
                if (((Number) XposedHelpers.callMethod(display, "getAlbumId")).longValue() != id)
                    throw new IllegalStateException("ALBUM_IDENTITY_CHANGED");
                XposedHelpers.callMethod(display, "setPhotoCount", ((Number) XposedHelpers.getObjectField(copy, "mPhotoCount")).intValue());
                XposedHelpers.callMethod(display, "setAlbumSize", bytes);
                Object coverId = XposedHelpers.getObjectField(copy, "mCoverId");
                Object cover = coverId == null ? null : XposedHelpers.newInstance(coverBean,
                        ((Number) coverId).longValue(), XposedHelpers.getObjectField(copy, "mCoverPath"),
                        XposedHelpers.getObjectField(copy, "mCoverSha1"), intField(copy, "mCoverSyncState"),
                        longField(copy, "mCoverSize"), XposedHelpers.getBooleanField(copy, "isManualSetCover"));
                XposedHelpers.callMethod(display, "setCover", cover);
                result.add(display);
            }
            report("event=album_display revision=" + config.revision + " albums=" + result.size() + " source_mutated=false");
            return result;
        } catch (Throwable error) {
            report("event=album_display_error reason=" + safeReason(error));
            return source;
        } finally { scope.exit(frame); }
    }

    private long[] albumSuggestionCover(Uri action) {
        try {
            String id=action.getQueryParameter("id"),server=action.getQueryParameter("serverId");
            Long resolved=id==null?null:Long.valueOf(id);
            if(resolved==null && server!=null) {
                Object manager=XposedHelpers.callStaticMethod(albumManager,"getInstance");
                Lock lock=(Lock)XposedHelpers.getObjectField(manager,"mReadLock");lock.lock();
                try {for(Object item:(List<?>)albumCache.get(manager))
                    if(server.equals(XposedHelpers.callMethod(item,"getServerId"))) {
                        if(resolved!=null)throw new IllegalStateException("AMBIGUOUS_ALBUM_ID");
                        resolved=((Number)XposedHelpers.callMethod(item,"getId")).longValue();
                    }
                }finally{lock.unlock();}
            }
            if(resolved==null)throw new IllegalStateException("ALBUM_ID_MISSING");
            Object dto=XposedHelpers.newInstance(album,resolved.longValue());
            List<?> request=Collections.singletonList(dto),display=displayAlbums(request);
            if(display==request)throw new IllegalStateException("ALBUM_DISPLAY_UNRESOLVED");
            Object result=display.get(0);
            return new long[]{((Number)XposedHelpers.callMethod(result,"getPhotoCount")).longValue(),
                    ((Number)XposedHelpers.callMethod(result,"getCoverId")).longValue()};
        }catch(ReflectiveOperationException error){throw new IllegalStateException("ALBUM_LOOKUP_FAILED",error);}
    }

    private void preparePreview(XC_MethodHook.MethodHookParam p) {
        if (!enabled() || !(p.args[0] instanceof Cursor)) return;
        Long key = previewKeys.get(p.thisObject);
        if (searchLoader.isInstance(p.thisObject)) {
            String[] ids = (String[]) XposedHelpers.getObjectField(p.thisObject, "mIds");
            if (ids == null) return;
            p.setObjectExtra("ranked_source", ids);
            int position = XposedHelpers.getIntField(p.thisObject, "mInitPos");
            if (key == null && position >= 0 && position < ids.length) key = Long.valueOf(ids[position]);
            List<String> visible = PreviewIndex.visibleRank(ids, cursorIds((Cursor) p.args[0]));
            XposedHelpers.setObjectField(p.thisObject, "mIds", visible.toArray(new String[0]));
        }
        p.setObjectExtra("selected_id", key);
    }

    private void finishPreview(XC_MethodHook.MethodHookParam p) {
        if (!enabled()) return;
        Object ranked = p.getObjectExtra("ranked_source");
        if (ranked != null) XposedHelpers.setObjectField(p.thisObject, "mIds", ranked);
        if (p.hasThrowable() || p.getResult() == null) return;
        Long key = (Long) p.getObjectExtra("selected_id");
        if (key == null) { report("event=preview_reindex_error reason=SELECTED_ID_MISSING"); return; }
        Object dataSet = p.getResult();
        List<Long> ids;
        if (searchLoader.isInstance(p.thisObject)) {
            List<?> rankedIds = (List<?>) XposedHelpers.getObjectField(dataSet, "mIds");
            ids = new ArrayList<>(); for (Object id : rankedIds) ids.add(Long.valueOf(id.toString()));
        } else ids = cursorIds((Cursor) XposedHelpers.getObjectField(dataSet, "mCursor"));
        int fallback = ((Number) XposedHelpers.callMethod(dataSet, "getInitPosition")).intValue();
        XposedHelpers.callMethod(dataSet, "setInitPosition", PreviewIndex.locate(ids, key, fallback));
        report("event=preview_reindexed revision=" + config.revision + " count=" + ids.size());
    }

    private static List<Long> cursorIds(Cursor cursor) {
        int before = cursor.getPosition();
        List<Long> result = new ArrayList<>(cursor.getCount());
        try { for (int i = 0; i < cursor.getCount(); i++) if (cursor.moveToPosition(i)) result.add(cursor.getLong(0)); }
        finally { cursor.moveToPosition(before); }
        return result;
    }

    private boolean localMedia(Uri uri) {
        if (uri == null || !"com.miui.gallery.cloud.provider".equals(uri.getAuthority()) || uri.getPathSegments().isEmpty()) return false;
        return Arrays.asList("media", "album_media", "share_album_media", "cloud_and_share", "recent_media").contains(uri.getPathSegments().get(0));
    }
    private static int intField(Object item, String field) { Object value = XposedHelpers.getObjectField(item, field); return value == null ? 0 : ((Number) value).intValue(); }
    private static long longField(Object item, String field) { Object value = XposedHelpers.getObjectField(item, field); return value == null ? 0 : ((Number) value).longValue(); }
    private static String safeReason(Throwable error) {
        // Do not log exception messages from Gallery: they can contain private paths or queries.
        return error.getClass().getSimpleName();
    }
    private void report(String message) { if (reported.add(message)) log.accept(message); }
    private void hook(Method member, XC_MethodHook callback) { installed.add(XposedBridge.hookMethod(member, callback)); }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private Class<?> typeUnchecked(String name) { try { return type(name); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); } }
    private static Method method(Class<?> owner, String name, Class<?> returns, Class<?>... parameters) throws NoSuchMethodException {
        for (Method member : owner.getDeclaredMethods()) {
            if (member.getName().equals(name) && member.getReturnType().equals(returns) && Arrays.equals(member.getParameterTypes(), parameters)) {
                member.setAccessible(true); return member;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private void preflight() throws ReflectiveOperationException {
        icons = new SearchIconGuard(loader, config.rules, log);
        suggestions = new LocalSuggestionGuard(loader, config.rules, icons, log, this::albumSuggestionCover);
        trash = new TrashDisplayGuard(loader, config.rules, log);
        external = new ExternalPreviewGuard(loader, config.rules, log);
        mediaManager = type("com.miui.gallery.provider.cache.MediaManager");
        shareManager = type("com.miui.gallery.provider.cache.ShareMediaManager");
        mediaItem = type("com.miui.gallery.provider.cache.MediaCacheItem");
        albumManager = type("com.miui.gallery.provider.cache.AlbumCacheManager");
        albumItem = type("com.miui.gallery.provider.cache.AlbumCacheItem");
        album = type("com.miui.gallery.model.dto.Album"); coverBean = type("com.miui.gallery.model.dto.Album$CoverBean");
        Class<?> cacheManager = type("com.miui.gallery.provider.cache.CacheManager");
        albumCache = cacheManager.getDeclaredField("mCache"); albumCache.setAccessible(true);
        liveData = type("com.miui.gallery.provider.cache.CacheLiveData");
        homeViewModel = type("com.miui.gallery.ui.HomePageViewModel");
        detailViewModel = type("com.miui.gallery.ui.PhotoListViewModel");
        mapViewModel = type("com.miui.gallery.ui.MapViewModel");
        pickerViewModel = type("com.miui.gallery.picker.PickHomePageViewMode");
        Class<?> nativeLoader=type("androidx.loader.content.Loader");
        legacyFactory=method(type("com.miui.gallery.ui.PhotoListFragmentBase$PhotoListLoaderCallback"),"onCreateLoader",nativeLoader,int.class,Bundle.class);
        pickerFactory=method(type("com.miui.gallery.picker.PickAlbumDetailFragment$AlbumDetailLoaderCallback"),"onCreateLoader",nativeLoader,int.class,Bundle.class);
        recentPickerFactory=method(type("com.miui.gallery.picker.PickRecentDiscoveryFragment$RecentDiscoveryLoaderCallback"),"onCreateLoader",nativeLoader,int.class,Bundle.class);
        cursorCompute=method(type("androidx.loader.content.CursorLoader"),"loadInBackground",Cursor.class);
        startupHelper = type("com.miui.gallery.activity.HomePageStartupHelper2");
        clustered = type("com.miui.gallery.data.ClusteredList");
        cloudLoader = type("com.miui.gallery.loader.CloudSetLoader"); searchLoader = type("com.miui.gallery.loader.SearchResultSetLoader");
        compute = method(liveData, "compute", List.class);
        filter = method(cacheManager, "filter", List.class, type("com.miui.gallery.provider.cache.Filter"));
        mediaPath = method(mediaItem, "getFilePath", String.class); mediaSize = method(mediaItem, "getSize", long.class);
        mediaType = method(mediaItem, "getType", int.class);
        albumCount = method(albumItem, "internalCalculateItemCountAndGetItems", List.class, Integer.class, type("com.miui.gallery.model.AlbumConstants$ExcludeMimeType"));
        albumCover = method(albumItem, "internalCalculateCoverInfo", void.class, List.class);
        albumCopy = method(albumItem, "copy", albumItem);
        homeJob = method(startupHelper, "loadHomePageData$lambda-6", type("kotlin.Unit"), startupHelper, type("com.miui.gallery.concurrent.ThreadPool$JobContext"));
        loadSnapshot = method(startupHelper, "loadHomePageDatas", List.class, type("com.miui.gallery.ui.pictures.PictureViewMode"));
        preloadCursor = method(startupHelper, "checkIsContainsPreLoadCursor", List.class);
        Class<?> homeFragment = type("com.miui.gallery.ui.HomePageFragment");
        homeIds = method(homeFragment, "saveHomePageIds", void.class, List.class);
        homeSnapshot = method(homeFragment, "updateSnapshot", void.class, List.class);
        snapshotPersist = method(type("com.miui.gallery.activity.HomePageStartupHelper2$SnapshotFunction"), "apply", Boolean.class,
                type("com.miui.gallery.activity.HomePageStartupHelper2$Snapshot"));
        albumDisplay = method(type("com.miui.gallery.ui.album.main.utils.AlbumGroupByAlbumTypeFunction$1"), "apply", type("org.reactivestreams.Publisher"), List.class);
        albumSnapshot = method(type("com.miui.gallery.ui.album.main.usecase.QuerySnapAlbumList"), "buildUseCaseFlowable", type("io.reactivex.Flowable"), type("com.miui.gallery.ui.album.main.utils.AlbumGroupByAlbumTypeFunction$Config"));
        searchResult = method(type("com.miui.gallery.search.resultpage.SearchImageResultHelper$ImageResultProcessor"), "createSuggestionResult", type("com.miui.gallery.search.core.result.SuggestionResult"), List.class, type("com.miui.gallery.search.core.QueryInfo"), type("com.miui.gallery.search.core.suggestion.RankInfo"), type("com.miui.gallery.search.core.result.ErrorInfo"));
        previewQuery = method(type("com.miui.gallery.loader.CursorSetLoader"), "query", Cursor.class, Uri.class, String[].class, String.class, String[].class, String.class, String.class);
        Class<?> dataSet = type("com.miui.gallery.model.CursorDataSet");
        cloudWrap = method(cloudLoader, "wrapDataSet", dataSet, Cursor.class); searchWrap = method(searchLoader, "wrapDataSet", dataSet, Cursor.class);
        method(type("com.miui.gallery.util.GalleryMemoryCacheHelper"), "remove", Object.class, String.class);
        // Parcelable omits the routing ID and other business fields; verify a complete detached DTO copy instead.
        DetachedCopy.verify(album); coverBean.getConstructor(long.class, String.class, String.class, int.class, long.class, boolean.class);
        album.getMethod("setPhotoCount", int.class); album.getMethod("setAlbumSize", long.class); album.getMethod("setCover", coverBean);
        liveData.getDeclaredField("uri"); homeViewModel.getDeclaredField("liveData"); detailViewModel.getDeclaredField("liveData");
        pickerViewModel.getDeclaredField("liveData");
        for (String field : Arrays.asList("mAllMediasShowOnMap", "mMapAlbumCovers"))
            if (mapViewModel.getDeclaredField(field).getType() != liveData)
                throw new NoSuchFieldException("MapViewModel." + field);
    }
}
