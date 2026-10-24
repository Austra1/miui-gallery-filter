package dev.local.galleryexclude.filter;

/** Reconstructs the recorded original location, never the recycled file/thumbnail location. */
public final class TrashPath {
    private TrashPath() { }
    public static String original(String album, String fileName, String primaryStorage) {
        if (album == null || album.isEmpty() || fileName == null || fileName.isEmpty()
                || fileName.indexOf('/') >= 0 || fileName.equals(".") || fileName.equals(".."))
            throw new IllegalArgumentException("TRASH_ORIGINAL_LOCATION_UNAVAILABLE");
        if (!album.startsWith("/")) {
            if (primaryStorage == null || !primaryStorage.startsWith("/"))
                throw new IllegalArgumentException("PRIMARY_STORAGE_UNAVAILABLE");
            album = primaryStorage + "/" + album;
        }
        return album + "/" + fileName;
    }
}
