package dev.local.galleryexclude.config;

import android.content.Context;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import dev.local.galleryexclude.rules.StorageDocumentPath;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps only Android's external-storage provider IDs to the actual mounted volume roots. */
public final class StorageDocuments {
    private static final String AUTHORITY="com.android.externalstorage.documents";
    private StorageDocuments(){ }
    private static Map<String,String> roots(Context context){
        Map<String,String> roots=new LinkedHashMap<>();
        for(StorageVolume volume:context.getSystemService(StorageManager.class).getStorageVolumes()){
            File directory=volume.getDirectory();
            if(directory==null)continue;
            String id=volume.isPrimary()?"primary":volume.getUuid();
            if(id!=null)roots.put(id,directory.getAbsolutePath());
        }
        return roots;
    }
    public static String selectedTree(Context context,Uri uri){
        if(uri==null || !AUTHORITY.equals(uri.getAuthority()) || !DocumentsContract.isTreeUri(uri))
            throw new StorageDocumentPath.Unavailable("UNSUPPORTED_DIRECTORY_PROVIDER");
        return StorageDocumentPath.resolve(DocumentsContract.getTreeDocumentId(uri),roots(context));
    }
    public static String documentFile(Context context,Uri uri){
        if(uri==null || !AUTHORITY.equals(uri.getAuthority()) || !DocumentsContract.isDocumentUri(context,uri))return null;
        return StorageDocumentPath.resolve(DocumentsContract.getDocumentId(uri),roots(context));
    }
    public static Uri initialDirectory(Context context,String path){
        if(path==null)return null;
        for(Map.Entry<String,String> root:roots(context).entrySet()){
            if(path.equals(root.getValue()) || path.startsWith(root.getValue()+"/")){
                String relative=path.equals(root.getValue())?"":path.substring(root.getValue().length()+1);
                return DocumentsContract.buildDocumentUri(AUTHORITY,root.getKey()+":"+relative);
            }
        }
        return null;
    }
}
