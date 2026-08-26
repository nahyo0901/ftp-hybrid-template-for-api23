package com.example.ftpengine;

import org.apache.ftpserver.ftplet.FtpFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapts an IFtpFileSystem path to Apache FtpServer's FtpFile interface.
 *
 * Recycled from the old (unused) FtpSession helper. All actual path
 * operations still go through IFtpFileSystem / FtpFileSystem / SAFFileSystem
 * exactly as before -- only the protocol-facing wrapper changed.
 */
public class FtpSession implements FtpFile {

    private final IFtpFileSystem fs;
    private final String path; // absolute path, e.g. "/foo/bar.txt"

    public FtpSession(IFtpFileSystem fs, String path) {
        this.fs = fs;
        this.path = (path == null || path.isEmpty()) ? "/" : path;
    }

    @Override
    public String getAbsolutePath() {
        return path;
    }

    @Override
    public String getName() {
        if (path.equals("/")) return "/";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    @Override
    public boolean isHidden() {
        String name = getName();
        return name.startsWith(".");
    }

    @Override
    public boolean isDirectory() {
        try {
            return fs.isDirectory(path);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isFile() {
        try {
            return fs.exists(path) && !fs.isDirectory(path);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean doesExist() {
        try {
            return fs.exists(path);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isReadable() {
        return true;
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    @Override
    public boolean isRemovable() {
        return !path.equals("/");
    }

    @Override
    public String getOwnerName() {
        return "ftp";
    }

    @Override
    public String getGroupName() {
        return "ftp";
    }

    @Override
    public int getLinkCount() {
        return isDirectory() ? 3 : 1;
    }

    @Override
    public long getLastModified() {
        try {
            long m = fs.lastModified(path);
            return m < 0 ? 0 : m;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean setLastModified(long time) {
        return false; // not supported by IFtpFileSystem backends
    }

    @Override
    public long getSize() {
        try {
            if (fs.isDirectory(path)) return 0;
            long l = fs.length(path);
            return l < 0 ? 0 : l;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public Object getPhysicalFile() {
        return null;
    }

    @Override
    public boolean mkdir() {
        try {
            return fs.mkdir(path);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean delete() {
        try {
            return fs.delete(path);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean move(FtpFile destination) {
        try {
            return fs.rename(path, destination.getAbsolutePath());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<FtpFile> listFiles() {
        List<FtpFile> result = new ArrayList<>();
        try {
            String[] names = fs.list(path);
            if (names == null) return result;
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                String childPath = path.equals("/") ? "/" + name : path + "/" + name;
                result.add(new FtpSession(fs, childPath));
            }
        } catch (Exception e) {
            // Previously silently swallowed -- this hid the "." path bug
            // (fs.list() failing on a bogus resolved path) behind a
            // false-empty "successful" listing. Log it so a real failure
            // is now visible instead of looking identical to an empty
            // directory.
            android.util.Log.e("FtpSession", "listFiles failed for path=" + path, e);
        }
        return result;
    }

    @Override
    public OutputStream createOutputStream(long offset) throws IOException {
        // IFtpFileSystem backends don't support resume offsets; ignore offset
        // (matches the previous hand-rolled STOR behavior, which also
        // always wrote from the start).
        return fs.openOutputStream(path);
    }

    @Override
    public InputStream createInputStream(long offset) throws IOException {
        InputStream in = fs.openInputStream(path);
        if (offset > 0) {
            long skipped = 0;
            while (skipped < offset) {
                long s = in.skip(offset - skipped);
                if (s <= 0) break;
                skipped += s;
            }
        }
        return in;
    }
}
