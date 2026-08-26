package com.example.ftp;

import android.content.Context;
import android.util.Log;

import com.example.ftpengine.FtpUserManager;
import com.example.ftpengine.PasvPortPool; // now a FileSystemFactory adapter
import com.example.ftpengine.saf.SAFFileSystem;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.SimpleBufferAllocator;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * FTP Engine Hybrid - Main entry point for the FTP server.
 *
 * REDESIGNED: this used to hand-roll the entire protocol/data-connection
 * layer directly on top of raw MINA (NioSocketAcceptor + FtpCommandProcessor
 * + FtpIoHandlerAndroid). That approach only ever worked reliably with
 * FileZilla -- real Android FTP clients could log in and download, but
 * uploads (STOR) hung indefinitely with 0 bytes ever received.
 *
 * This version instead builds a real org.apache.ftpserver.FtpServer, the
 * same mature, complete FTP protocol implementation this app's sibling
 * non-SAF descendant was already redesigned to use, and the same one
 * proven in real-world use by prim-ftpd. All PASV/PORT/STOR/RETR/LIST
 * handling is now owned by that library instead of hand-rolled here.
 *
 * Constructor keeps the SAFFileSystem parameter type (rather than widening
 * to IFtpFileSystem) so MainActivity.java and FtpServerService.java need
 * no changes at all.
 */
public class FtpEngineHybrid {

    private static final String TAG = "FtpEngineHybrid";

    private final FtpServerFactory serverFactory;
    private final FtpUserManager userManager;
    private final String serverIp;
    private FtpServer server;
    private Listener listener;

    public FtpEngineHybrid(Context context, SAFFileSystem fs) {

        // Android NIO workaround: direct (off-heap) ByteBuffers are a known
        // trouble spot on some Android ART/manufacturer JVM builds. Force
        // MINA onto heap buffers.
        IoBuffer.setUseDirectBuffer(false);
        IoBuffer.setAllocator(new SimpleBufferAllocator());

        this.serverIp = resolveIp();
        this.userManager = new FtpUserManager();

        this.serverFactory = new FtpServerFactory();
        serverFactory.setUserManager(userManager);
        serverFactory.setFileSystem(new PasvPortPool(fs));
    }

    public void start(int port) throws Exception {
        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(port);
        listenerFactory.setServerAddress("0.0.0.0");

        listener = listenerFactory.createListener();
        serverFactory.addListener("default", listener);

        server = serverFactory.createServer();
        server.start();

        Log.i(TAG, "FTP started: ftp://" + serverIp + ":" + port);
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        listener = null;
    }

    public FtpUserManager getUserManager() {
        return userManager;
    }

    public int getConnectionCount() {
        if (listener == null) return 0;
        try {
            return listener.getActiveSessions().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private String resolveIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();

            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;

                Enumeration<InetAddress> addrs = ni.getInetAddresses();

                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();

                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}

        return "127.0.0.1";
    }
}
