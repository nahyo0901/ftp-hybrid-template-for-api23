package com.example.ftpengine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface IFtpFileSystem {

    boolean exists(String path);
    boolean isDirectory(String path);
    boolean delete(String path) throws IOException;
    boolean mkdir(String path) throws IOException;
    boolean rename(String from, String to) throws IOException;
    String[] list(String path) throws IOException;

    /**
     * Get the size of a file in bytes.
     *
     * @param path file path relative to the FTP root
     * @return file size in bytes, or -1 if the file doesn't exist
     * @throws IOException on error
     */
    long length(String path) throws IOException;

    /**
     * Get the last-modified time of a file/directory.
     *
     * @param path file path relative to the FTP root
     * @return last-modified time as epoch millis, or -1 if unavailable
     * @throws IOException on error
     */
    long lastModified(String path) throws IOException;

    /**
     * Open a stream to read a file's contents.
     * Caller is responsible for closing the returned stream.
     *
     * @param path file path relative to the FTP root
     * @return an InputStream positioned at the start of the file
     * @throws IOException on error
     */
    InputStream openInputStream(String path) throws IOException;

    /**
     * Open a stream to write a file, creating parent directories if needed.
     * Caller is responsible for closing the returned stream.
     *
     * @param path file path relative to the FTP root
     * @return an OutputStream that overwrites the target file
     * @throws IOException on error
     */
    OutputStream openOutputStream(String path) throws IOException;
}
