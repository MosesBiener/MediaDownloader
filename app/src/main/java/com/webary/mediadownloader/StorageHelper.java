package com.webary.mediadownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

final class StorageHelper {
    private StorageHelper() {}

    static String displayFolder(Context context, String treeUriString) {
        if (TextUtils.isEmpty(treeUriString)) return "Downloads / Media Downloader";
        try {
            Uri tree = Uri.parse(treeUriString);
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = context.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getString(0);
                }
            }
        } catch (Exception ignored) {}
        return "Selected folder";
    }

    static Uri exportFile(Context context, File source, String displayName, String mime,
                          String treeUriString, boolean createSubfolder, String subfolderName) throws Exception {
        if (!TextUtils.isEmpty(treeUriString)) {
            return exportToTree(context, source, displayName, mime, Uri.parse(treeUriString), createSubfolder, subfolderName);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return exportToMediaStore(context, source, displayName, mime, createSubfolder, subfolderName);
        }
        return exportLegacy(source, displayName, createSubfolder, subfolderName);
    }

    private static Uri exportToTree(Context context, File source, String displayName, String mime,
                                    Uri treeUri, boolean createSubfolder, String subfolderName) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));

        if (createSubfolder) {
            String safeFolder = sanitizeFolderName(subfolderName);
            Uri existing = findChild(resolver, treeUri, parent, safeFolder, true);
            if (existing != null) {
                parent = existing;
            } else {
                Uri created = DocumentsContract.createDocument(resolver, parent,
                        DocumentsContract.Document.MIME_TYPE_DIR, safeFolder);
                if (created != null) parent = created;
            }
        }

        Uri existingFile = findChild(resolver, treeUri, parent, displayName, false);
        if (existingFile != null) {
            try { DocumentsContract.deleteDocument(resolver, existingFile); } catch (Exception ignored) {}
        }

        Uri out = DocumentsContract.createDocument(resolver, parent, mime, displayName);
        if (out == null) throw new IllegalStateException("Could not create output file in selected folder");
        try (InputStream in = new FileInputStream(source); OutputStream os = resolver.openOutputStream(out, "w")) {
            if (os == null) throw new IllegalStateException("Could not open selected output folder");
            copy(in, os);
        }
        return out;
    }

    private static Uri findChild(ContentResolver resolver, Uri treeUri, Uri parent, String name, boolean directory) {
        Cursor c = null;
        try {
            String parentId = DocumentsContract.getDocumentId(parent);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            c = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String childName = c.getString(1);
                    String childMime = c.getString(2);
                    if (name.equals(childName) && (!directory || DocumentsContract.Document.MIME_TYPE_DIR.equals(childMime))) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static Uri exportToMediaStore(Context context, File source, String displayName, String mime,
                                          boolean createSubfolder, String subfolderName) throws Exception {
        String relative = Environment.DIRECTORY_DOWNLOADS + "/Media Downloader";
        if (createSubfolder) relative += "/" + sanitizeFolderName(subfolderName);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = resolver.insert(collection, values);
        if (item == null) throw new IllegalStateException("Android could not create the download file");

        boolean ok = false;
        try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(item, "w")) {
            if (out == null) throw new IllegalStateException("Android could not open the download file");
            copy(in, out);
            ok = true;
        } finally {
            if (!ok) {
                try { resolver.delete(item, null, null); } catch (Exception ignored) {}
            }
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(item, done, null, null);
        return item;
    }

    private static Uri exportLegacy(File source, String displayName, boolean createSubfolder, String subfolderName) throws Exception {
        File outDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Media Downloader");
        if (createSubfolder) outDir = new File(outDir, sanitizeFolderName(subfolderName));
        if (!outDir.exists() && !outDir.mkdirs()) throw new IllegalStateException("Could not create Downloads folder");
        File out = new File(outDir, displayName);
        try (InputStream in = new FileInputStream(source); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        }
        return Uri.fromFile(out);
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[1024 * 128];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        out.flush();
    }

    static String sanitizeFolderName(String name) {
        if (name == null) return "Download";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.isEmpty() ? "Download" : s;
    }

    static String sanitizeFileName(String name) {
        if (name == null) return "download";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (s.length() > 120) s = s.substring(0, 120).trim();
        return s.isEmpty() ? "download" : s;
    }
}
