package com.example.ftpengine.saf;

import android.content.Context;
import android.net.Uri;

import com.example.ftpengine.IFtpFileSystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SAF-backed IFtpFileSystem implementation.
 *
 * list() now returns bare file names (the FTP ls-format is built in
 * FtpCommandProcessor). isDirectory() is O(1) when the SAFFileObject
 * was produced by list() because the mime type is fetched in the same
 * Cursor query — no extra SAF round-trips per file.
 */
public class SAFFileSystem implements IFtpFileSystem {

    private final Context context;
    private final Uri rootUri;

    public SAFFileSystem(Context context, Uri rootUri) {
        this.context = context.getApplicationContext();
        this.rootUri = rootUri;
    }

    private SAFFileObject getFile(String path) {
        return new SAFFileObject(context, rootUri, normalize(path));
    }

    /* ===================== CORE ===================== */

    @Override
    public boolean exists(String path) {
        return getFile(path).exists();
    }

    @Override
    public boolean isDirectory(String path) {
        return getFile(path).isDirectory();
    }

    @Override
    public boolean mkdir(String path) throws IOException {
        return getFile(path).mkdir();
    }

    @Override
    public boolean delete(String path) throws IOException {
        return getFile(path).delete();
    }

    @Override
    public boolean rename(String from, String to) throws IOException {
        return getFile(from).renameTo(to);
    }

    /* ===================== LIST ===================== */

    @Override
    public String[] list(String path) throws IOException {
        List<SAFFileObject> files = getFile(path).list();

        if (files == null) return new String[0];

        List<String> names = new ArrayList<>();
        for (SAFFileObject f : files) {
            if (f == null) continue;
            String p = f.getPath();
            if (p == null) continue;
            int idx = p.lastIndexOf('/');
            names.add(idx >= 0 && idx < p.length() - 1
                    ? p.substring(idx + 1)
                    : p);
        }
        return names.toArray(new String[0]);
    }

    /* ===================== READ / WRITE ===================== */

    @Override
    public InputStream openInputStream(String path) throws IOException {
        return getFile(path).openInput();
    }

    @Override
    public OutputStream openOutputStream(String path) throws IOException {
        return getFile(path).openOutput(false);
    }
    
    /* ===================== HELPERS ===================== */

    private String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) path = "/" + path;
        return path.replaceAll("/+", "/");
    }
}
