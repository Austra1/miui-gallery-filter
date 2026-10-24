package dev.local.galleryexclude.rules;

import java.util.Collections;
import java.util.Map;

/** Document IDs are already decoded by DocumentsContract. Never decode their path again. */
public final class StorageDocumentPath {
    public static final class Unavailable extends IllegalArgumentException {
        public final String reason;
        public Unavailable(String reason){super(reason);this.reason=reason;}
    }
    private StorageDocumentPath(){ }
    public static String resolve(String documentId,Map<String,String> roots){
        if(documentId==null)throw new Unavailable("INVALID_DOCUMENT_ID");
        int split=documentId.indexOf(':');
        if(split<=0)throw new Unavailable("INVALID_DOCUMENT_ID");
        String volume=documentId.substring(0,split),relative=documentId.substring(split+1);
        String root=null;
        for(Map.Entry<String,String> entry:roots.entrySet())if(entry.getKey().equalsIgnoreCase(volume)){
            root=entry.getValue();break;
        }
        if(root==null)throw new Unavailable("STORAGE_NOT_MOUNTED");
        if(relative.startsWith("/"))throw new Unavailable("INVALID_DOCUMENT_ID");
        return new PathRules.Normalizer(Collections.emptyMap()).normalize(root+"/"+relative);
    }
}
