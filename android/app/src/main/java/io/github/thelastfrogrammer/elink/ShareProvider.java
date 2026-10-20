package io.github.thelastfrogrammer.elink;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands files to other apps for Share: read-only, and only files the app put in cache/share for that (an exported project, a
 * cover page). Not exported; other apps reach a file only through the one-time grant on the share intent.
 */
public final class ShareProvider extends ContentProvider {
    static File folder(Context context) { File dir = new File(context.getCacheDir(), "share"); dir.mkdirs(); return dir; }
    static Uri uriFor(Context context, File file) { return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".share").appendPath(file.getName()).build(); }
    /** A fresh file to share under this name; older shared files are cleared first. */
    static File fresh(Context context, String name) {
        File dir = folder(context); File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete();
        return new File(dir, Projects.displayName(name).replace('/', '_'));
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.equals("..") || getContext() == null) throw new FileNotFoundException();
        try {
            File dir = folder(getContext()).getCanonicalFile(), file = new File(dir, name).getCanonicalFile();
            if (!dir.equals(file.getParentFile()) || !file.isFile()) throw new FileNotFoundException();
            return file;
        } catch (IOException e) { throw new FileNotFoundException(); }
    }
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File file; try { file = resolve(uri); } catch (FileNotFoundException e) { return null; }
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {file.getName(), file.length()}); return cursor;
    }
    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png") ? "image/png" : name.endsWith(".mp4") ? "video/mp4" : name.endsWith(".pdf") ? "application/pdf" : name.endsWith(".txt") ? "text/plain" : name.endsWith(ProjectArchive.EXTENSION) ? ProjectArchive.MIME : "application/octet-stream";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
