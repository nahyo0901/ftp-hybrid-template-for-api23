package com.example.ftpengine.saf;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SAF-backed representation of a single file or folder.
 *
 * Root causes of "550 LIST failed" — all fixed here:
 *
 * BUG 1 (previous round) — resolveDocumentUri() used `current` (a document
 *   Uri after the first iteration) as the tree arg of
 *   buildChildDocumentsUriUsingTree(). SAF requires rootUri there always.
 *   Fixed by tracking only the currentDocId string, never the Uri.
 *
 * BUG 2 (previous round) — list() called buildChildDocumentsUriUsingTree()
 *   with `doc` instead of rootUri. Same tree/document confusion. Fixed.
 *
 * BUG 3 (this round) — list() called DocumentsContract.getDocumentId(doc)
 *   when doc == rootUri (the root "/" case). getDocumentId() expects a
 *   document Uri (with a /document/ path segment) and throws
 *   IllegalArgumentException on a plain tree Uri. This exception was
 *   swallowed by the catch block in FtpCommandProcessor.list() → 550.
 *   Fixed by using getTreeDocumentId(rootUri) for the root case.
 *
 * BUG 4 (this round) — resolveDocumentUri() seeded currentDocId with
 *   getDocumentId(rootUri) on the very first call, which has the same
 *   IllegalArgumentException problem as Bug 3.
 *   Fixed by using getTreeDocumentId(rootUri) to seed the walk.
 *
 * BUG 5 (this round) — list() fetched MIME_TYPE but the null check for
 *   the fallback docId used a null-check that never triggered because
 *   getDocumentId throws rather than returning null.
 *   Fixed with the root/non-root branch.
 *
 * License: Apache 2.0
 */
public class SAFFileObject {

    private final Context context;
    private final ContentResolver resolver;

    /**
     * The tree Uri returned by ACTION_OPEN_DOCUMENT_TREE.
     * Always used as the first arg to buildChildDocumentsUriUsingTree().
     * Never changes.
     */
    private final Uri rootUri;

    /** FTP-style absolute path, e.g. "/" or "/Photos/img.jpg". */
    private final String path;

    /**
     * Cached MIME type — populated by list() so isDirectory() needs no
     * extra SAF query for children returned from list().
     */
    private final String mimeType;

    public SAFFileObject(Context ctx, Uri rootUri, String path) {
        this(ctx, rootUri, path, null);
    }

    /** Used internally by list() to carry mime without extra queries. */
    SAFFileObject(Context ctx, Uri rootUri, String path, String mimeType) {
        this.context  = ctx;
        this.resolver = ctx.getContentResolver();
        this.rootUri  = rootUri;
        this.path     = normalizePath(path);
        this.mimeType = mimeType;
    }

    private String normalizePath(String p) {
        if (p == null || p.isEmpty()) return "/";
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    public String getPath() { return path; }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public boolean exists() {
        return resolveDocumentId() != null;
    }

    public boolean isDirectory() {
        if (mimeType != null) {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        }
        String docId = resolveDocumentId();
        if (docId == null) return false;

        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId);
        try (Cursor c = resolver.query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0));
            }
        } catch (Exception ignored) {}
        return false;
    }

    public boolean isFile() {
        return exists() && !isDirectory();
    }

    /**
     * Returns file size in bytes, or -1 if not found/unavailable.
     * Follows the same document-id resolution pattern as isDirectory().
     */
    public long length() {
        String docId = resolveDocumentId();
        if (docId == null) return -1;

        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId);
        try (Cursor c = resolver.query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Returns last-modified time as epoch millis, or -1 if not found/unavailable.
     */
    public long lastModified() {
        String docId = resolveDocumentId();
        if (docId == null) return -1;

        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId);
        try (Cursor c = resolver.query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core: resolve FTP path → SAF document ID string
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Walks the FTP path segment-by-segment and returns the SAF document ID
     * string for the target, or null if not found.
     *
     * We track only the document ID string (not a Uri) so we never
     * accidentally pass a document Uri where a tree Uri is required.
     *
     * BUG 3+4 FIX: seed the walk with getTreeDocumentId(rootUri), NOT
     * getDocumentId(rootUri). getDocumentId() throws IllegalArgumentException
     * on a tree Uri because tree Uris have no /document/ path segment.
     */
    private String resolveDocumentId() {
        try {
            String clean = path.replaceAll("/+", "/");

            // BUG 4 FIX: use getTreeDocumentId for the root, not getDocumentId.
            String currentDocId = DocumentsContract.getTreeDocumentId(rootUri);

            if (clean.equals("/")) return currentDocId;

            for (String part : clean.split("/")) {
                if (part == null || part.isEmpty()) continue;

                // BUG 1 FIX: always use rootUri (tree) as first arg.
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        rootUri,       // ← tree Uri, constant
                        currentDocId   // ← current folder's document id string
                );

                try (Cursor c = resolver.query(childrenUri,
                        new String[]{
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME
                        }, null, null, null)) {

                    if (c == null) return null;

                    String found = null;
                    while (c.moveToNext()) {
                        if (part.equals(c.getString(1))) {
                            found = c.getString(0);
                            break;
                        }
                    }
                    if (found == null) return null;
                    currentDocId = found;
                }
            }

            return currentDocId;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIST
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists direct children. Each child carries its MIME type so the caller
     * can call isDirectory() at zero cost (no extra SAF query needed).
     *
     * BUG 2+3 FIX: use rootUri (tree) in buildChildDocumentsUriUsingTree()
     * and use getTreeDocumentId / getDocumentId correctly per case.
     */
    public List<SAFFileObject> list() throws IOException {
        String docId = resolveDocumentId();
        if (docId == null) throw new IOException("Directory not found: " + path);

        // BUG 2 FIX: rootUri is the tree, docId is the current folder's id.
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri, docId);

        List<SAFFileObject> result = new ArrayList<>();

        try (Cursor c = resolver.query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,   // 0
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,  // 1
                        DocumentsContract.Document.COLUMN_MIME_TYPE      // 2
                }, null, null, null)) {

            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    String childPath = path.equals("/") ? "/" + name : path + "/" + name;
                    result.add(new SAFFileObject(context, rootUri, childPath, mime));
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to list: " + path, e);
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read / Write / Delete / MkDir / Rename
    // ─────────────────────────────────────────────────────────────────────────

    public InputStream openInput() throws IOException {
        String docId = resolveDocumentId();
        if (docId == null) throw new IOException("File not found: " + path);
        return resolver.openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(rootUri, docId));
    }

    public OutputStream openOutput(boolean append) throws IOException {
        String docId = resolveDocumentId();
        Uri docUri = (docId != null)
                ? DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                : createFile(path);
        return resolver.openOutputStream(docUri, append ? "wa" : "w");
    }

    public boolean delete() throws IOException {
        String docId = resolveDocumentId();
        if (docId == null) return false;
        return DocumentsContract.deleteDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(rootUri, docId));
    }

    public boolean mkdir() throws IOException {
        createFolder(path);
        return true;
    }

    public boolean renameTo(String newPath) throws IOException {
        String newName = newPath.substring(newPath.lastIndexOf("/") + 1);
        String docId = resolveDocumentId();
        if (docId == null) throw new IOException("File not found: " + path);
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId);
        return DocumentsContract.renameDocument(resolver, docUri, newName) != null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Uri createFile(String fullPath) throws IOException {
        String parentPath = fullPath.substring(0, fullPath.lastIndexOf('/'));
        String filename   = fullPath.substring(fullPath.lastIndexOf('/') + 1);

        String parentDocId = new SAFFileObject(context, rootUri,
                parentPath.isEmpty() ? "/" : parentPath).resolveDocumentId();
        if (parentDocId == null)
            throw new IOException("Parent folder does not exist: " + parentPath);

        Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, parentDocId);
        Uri newDoc = DocumentsContract.createDocument(
                resolver, parentDocUri, "application/octet-stream", filename);
        if (newDoc == null)
            throw new IOException("Failed to create file: " + filename);
        return newDoc;
    }

    private void createFolder(String fullPath) throws IOException {
        String parentPath  = fullPath.substring(0, fullPath.lastIndexOf('/'));
        String folderName  = fullPath.substring(fullPath.lastIndexOf('/') + 1);

        String parentDocId = new SAFFileObject(context, rootUri,
                parentPath.isEmpty() ? "/" : parentPath).resolveDocumentId();
        if (parentDocId == null)
            throw new IOException("Parent folder does not exist: " + parentPath);

        Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, parentDocId);
        Uri newDoc = DocumentsContract.createDocument(
                resolver, parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR, folderName);
        if (newDoc == null)
            throw new IOException("Failed to create folder: " + folderName);
    }
}
