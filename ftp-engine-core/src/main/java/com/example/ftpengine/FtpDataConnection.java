package com.example.ftpengine;

import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FtpException;

/**
 * Adapts an IFtpFileSystem to Apache FtpServer's FileSystemView interface.
 * One instance per logged-in session, tracking that session's cwd.
 *
 * Recycled from the old (unused) FtpDataConnection helper. The actual
 * data-connection handling (PASV/PORT/STOR/RETR socket work) is now owned
 * entirely by the ftpserver-core library -- that hand-rolled logic is what
 * this whole redesign replaces.
 */
public class FtpDataConnection implements FileSystemView {

    private final IFtpFileSystem fs;
    private String cwd = "/";

    public FtpDataConnection(IFtpFileSystem fs) {
        this.fs = fs;
    }

    @Override
    public FtpFile getHomeDirectory() throws FtpException {
        return new FtpSession(fs, "/");
    }

    @Override
    public FtpFile getWorkingDirectory() throws FtpException {
        return new FtpSession(fs, cwd);
    }

    @Override
    public boolean changeWorkingDirectory(String dir) throws FtpException {
        String target = resolve(dir);
        try {
            if (fs.exists(target) && fs.isDirectory(target)) {
                cwd = target;
                return true;
            }
        } catch (Exception e) {
            android.util.Log.e("FtpDataConnection", "changeWorkingDirectory failed for target=" + target, e);
        }
        return false;
    }

    @Override
    public FtpFile getFile(String file) throws FtpException {
        return new FtpSession(fs, resolve(file));
    }

    @Override
    public boolean isRandomAccessible() throws FtpException {
        return true;
    }

    @Override
    public void dispose() {
        // no persistent resources to release
    }

    /**
     * Resolves an FTP-supplied path argument against the current working
     * directory into a clean, absolute path.
     *
     * BUG FIX: the previous version only collapsed repeated slashes via
     * a regex, so a literal "." segment (sent by many FTP command
     * implementations for a bare LIST/argument-less request, e.g.
     * getFile(".")) was never stripped -- resolve(".") against cwd "/"
     * produced the literal path "/." instead of "/". That bogus path
     * failed to match any real file/folder in the SAF backend, causing
     * fs.list()/fs.exists() to fail, which FtpSession.listFiles() then
     * silently swallowed into an empty (but "successful") directory
     * listing -- explaining why every root, both internal and SD-card
     * storage, showed as empty despite having real content.
     *
     * This version tokenizes the path segment-by-segment and explicitly
     * skips "." and handles ".." (moving up one level), instead of doing
     * naive string concatenation + regex cleanup.
     */
    private String resolve(String arg) {
        if (arg == null || arg.isEmpty() || arg.equals(".")) return cwd;

        String raw = arg.startsWith("/") ? arg : (cwd.equals("/") ? "/" + arg : cwd + "/" + arg);

        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String part : raw.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue; // skip empty segments (from repeated slashes) and "." (current dir)
            }
            if (part.equals("..")) {
                stack.pollLast(); // go up one level; no-op if already at root
                continue;
            }
            stack.addLast(part);
        }

        if (stack.isEmpty()) return "/";

        StringBuilder sb = new StringBuilder();
        for (String part : stack) {
            sb.append('/').append(part);
        }
        return sb.toString();
    }
}
