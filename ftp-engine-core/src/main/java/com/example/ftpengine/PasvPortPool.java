package com.example.ftpengine;

import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;

/**
 * Adapts an IFtpFileSystem to Apache FtpServer's FileSystemFactory interface.
 * Every logged-in user gets the same backing IFtpFileSystem (single-root
 * server, matching the previous Hybrid engine's behavior), just wrapped in
 * its own FileSystemView so each session tracks its own cwd independently.
 *
 * Recycled from the old (unused) PasvPortPool helper -- manual PASV port
 * management is no longer needed at all, since ftpserver-core handles PASV
 * (and PORT) port allocation internally.
 */
public class PasvPortPool implements FileSystemFactory {

    private final IFtpFileSystem fs;

    public PasvPortPool(IFtpFileSystem fs) {
        this.fs = fs;
    }

    @Override
    public FileSystemView createFileSystemView(User user) throws FtpException {
        return new FtpDataConnection(fs);
    }
}
