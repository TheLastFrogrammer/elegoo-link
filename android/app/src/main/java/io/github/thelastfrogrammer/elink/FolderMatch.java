package io.github.thelastfrogrammer.elink;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * A file in a folder the user chose (Storage Access Framework tree), found by name: the folder and up to two levels of
 * subfolders, at most 3000 entries. Where several have the name, the newest wins. Read-only.
 */
final class FolderMatch {
    static final int MAX_DEPTH = 2, MAX_ENTRIES = 3000;
    final Uri uri; final long size, modified;
    FolderMatch(Uri uri, long size, long modified) { this.uri = uri; this.size = size; this.modified = modified; }

    static boolean sameName(String a, String b) { return a != null && b != null && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT)); }

    static FolderMatch search(ContentResolver resolver, Uri tree, String name) {
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        ArrayDeque<Object[]> folders = new ArrayDeque<>();
        folders.add(new Object[] {DocumentsContract.getTreeDocumentId(tree), 0});
        FolderMatch best = null; int seen = 0;
        while (!folders.isEmpty() && seen < MAX_ENTRIES) {
            Object[] folder = folders.poll(); String id = (String) folder[0]; int depth = (Integer) folder[1];
            try (Cursor cursor = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null)) {
                while (cursor != null && cursor.moveToNext() && seen++ < MAX_ENTRIES) {
                    String child = cursor.getString(0), display = cursor.getString(1), type = cursor.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) { if (depth < MAX_DEPTH) folders.add(new Object[] {child, depth + 1}); continue; }
                    if (!sameName(display, name)) continue;
                    long size = cursor.isNull(3) ? -1 : cursor.getLong(3), modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                    if (best == null || modified > best.modified) best = new FolderMatch(DocumentsContract.buildDocumentUriUsingTree(tree, child), size, modified);
                }
            }
        }
        return best;
    }
}
