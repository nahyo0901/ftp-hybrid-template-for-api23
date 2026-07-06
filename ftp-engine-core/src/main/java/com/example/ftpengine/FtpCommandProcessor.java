package com.example.ftpengine;

import org.apache.mina.core.session.IoSession;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FtpCommandProcessor {

    private final IFtpFileSystem fs;
    private final FtpUserManager users;
    private final String ip;

    public FtpCommandProcessor(IFtpFileSystem fs, FtpUserManager users, String ip) {
        this.fs = fs;
        this.users = users;
        this.ip = ip;
    }

    /* ===================== REPLY ===================== */

    private void reply(IoSession s, String msg) {
        try {
            s.write(msg + "\r\n");
        } catch (Exception e) {
            s.closeNow();
        }
    }

    /**
     * Writes a raw response to the session without appending an extra \r\n.
     * Use this for multiline responses (FEAT, STAT, etc.) that already contain
     * their own \r\n line endings — reply() would add a second \r\n after the
     * final line, producing a blank line that commons-net 3.11.x rejects with
     * MalformedServerReplyException.
     */
    private void replyRaw(IoSession s, String msg) {
        try {
            s.write(msg);
        } catch (Exception e) {
            s.closeNow();
        }
    }

    /* ===================== MAIN HANDLER ===================== */

    public void handle(IoSession s, FtpSessionContext c, String line) {

        if (line == null) return;

        if (c.cwd == null) c.cwd = "/";
        if (c.transferType == null) c.transferType = "I";

        String[] parts = line.split(" ", 2);
        String cmd = parts[0].toUpperCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : null;

        boolean preLogin =
                cmd.equals("USER") || cmd.equals("PASS") ||
                cmd.equals("SYST") || cmd.equals("FEAT") ||
                cmd.equals("NOOP") || cmd.equals("QUIT") ||
                cmd.equals("PWD");

        if (!c.loggedIn && !preLogin) {
            reply(s, "530 Login required");
            return;
        }

        try {
            switch (cmd) {

                case "USER":
                    c.username = arg;
                    // FIX 1: RFC 959 §4.1.1 requires the text to indicate a
                    // password is needed. "331 OK" is non-standard and causes
                    // strict clients (Screenshot 3) to fail parsing the USER
                    // response before they even send PASS.
                    reply(s, "331 Password required for " + (arg != null ? arg : ""));
                    break;

                case "PASS":
                    if (users.authenticate(c.username, arg)) {
                        c.loggedIn = true;
                        reply(s, "230 Login successful");
                    } else {
                        reply(s, "530 Login incorrect");
                    }
                    break;

                case "SYST":
                    reply(s, "215 UNIX Type: L8");
                    break;

                case "FEAT":
                    // FIX 2: Use replyRaw() instead of reply() here.
                    //
                    // reply() appends \r\n unconditionally. When the argument
                    // already ends with \r\n (as a multiline response must),
                    // reply() produces a trailing blank line:
                    //
                    //   "211 End\r\n"  ← last real line
                    //   "\r\n"         ← extra blank line from reply()
                    //
                    // commons-net 3.11.1 reads that blank line as a new FTP
                    // response, tries to parse "" as a reply code, and throws
                    // MalformedServerReplyException with "Server Reply: " (blank)
                    // — exactly the error in the log.
                    //
                    // RFC 2389 also requires the opening line to be "211-" with
                    // NO trailing colon. "211-Features:" (with colon) is what
                    // Apache FtpServer emits; we use "211-Features" (no colon).
                    replyRaw(s,
                            "211-Features\r\n" +
                            " PASV\r\n" +
                            " UTF8\r\n" +
                            "211 End\r\n");
                    break;

                case "NOOP":
                    reply(s, "200 OK");
                    break;

                case "PWD":
                    reply(s, "257 \"" + c.cwd + "\"");
                    break;

                case "TYPE":
                    c.transferType = (arg == null) ? "I" : arg.toUpperCase(Locale.ROOT);
                    reply(s, "200 Type set to " + c.transferType);
                    break;

                case "CWD":
                    if (arg != null && fs.exists(normalize(c.cwd + "/" + arg))) {
                        c.cwd = normalize(c.cwd + "/" + arg);
                        reply(s, "250 Directory changed");
                    } else {
                        reply(s, "550 Directory not found");
                    }
                    break;

                case "PASV":
                    openPasv(s, c);
                    break;

                case "LIST":
                    list(s, c);
                    break;

                case "RETR":
                    retr(s, c, arg);
                    break;

                case "STOR":
                    stor(s, c, arg);
                    break;

                case "QUIT":
                    reply(s, "221 Goodbye");
                    s.closeNow();
                    break;

                default:
                    reply(s, "502 Command not implemented");
            }

        } catch (Exception e) {
            e.printStackTrace();
            reply(s, "550 Internal error");
        }
    }

    /* ===================== PASV ===================== */

    private void openPasv(IoSession s, FtpSessionContext c) {

        try {
            cleanup(c);

            ServerSocket ss = new ServerSocket(0);
            ss.setReuseAddress(true);

            c.passiveServerSocket = ss;
            c.passiveDataSocket = null;
            c.pasvPort = ss.getLocalPort();

            new Thread(() -> {
                try {
                    Socket socket = ss.accept();
                    socket.setKeepAlive(true);
                    socket.setTcpNoDelay(true);
                    c.passiveDataSocket = socket;
                } catch (Exception ignored) {}
            }).start();

            String[] ipParts = ip.split("\\.");

            reply(s,
                    "227 Entering Passive Mode (" +
                            ipParts[0] + "," +
                            ipParts[1] + "," +
                            ipParts[2] + "," +
                            ipParts[3] + "," +
                            (c.pasvPort / 256) + "," +
                            (c.pasvPort % 256) + ")"
            );

        } catch (Exception e) {
            reply(s, "425 Can't open passive connection");
        }
    }

    /* ===================== LIST ===================== */

    private void list(IoSession s, FtpSessionContext c) {

        try {
            reply(s, "150 Opening data connection");

            Socket d = waitForData(c);
            if (d == null) {
                reply(s, "425 No data connection");
                return;
            }

            StringBuilder sb = new StringBuilder();

            String[] files = fs.list(c.cwd);
            if (files != null) {
                String timestamp = new SimpleDateFormat("MMM dd HH:mm", Locale.US)
                        .format(new Date());

                for (String name : files) {
                    if (name == null || name.isEmpty()) continue;

                    String entryPath = c.cwd.equals("/")
                            ? "/" + name
                            : c.cwd + "/" + name;

                    boolean isDir = fs.isDirectory(entryPath);

                    sb.append(isDir ? "drwxr-xr-x" : "-rw-r--r--")
                      .append(" 1 ftp ftp 0 ")
                      .append(timestamp)
                      .append(" ")
                      .append(name)
                      .append("\r\n");
                }
            }

            d.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
            d.getOutputStream().flush();
            d.close();

            reply(s, "226 Transfer complete");

        } catch (Exception e) {
            e.printStackTrace();
            reply(s, "550 LIST failed");
        }
    }

    /* ===================== RETR ===================== */

      private static final int BUFFER_SIZE = 64 * 1024; // 64KB, never holds more than this in memory

private void retr(IoSession s, FtpSessionContext c, String f) throws Exception {

    if (f == null) {
        reply(s, "501 Missing filename");
        return;
    }

    reply(s, "150 Opening data connection");

    Socket d = waitForData(c);
    if (d == null) {
        reply(s, "425 No data connection");
        return;
    }

    try (InputStream in = fs.openInputStream(c.cwd + "/" + f);
         OutputStream out = d.getOutputStream()) {

        byte[] buf = new byte[BUFFER_SIZE];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        out.flush();

    } finally {
        d.close();
    }

    reply(s, "226 Transfer complete");
}

private void stor(IoSession s, FtpSessionContext c, String f) throws Exception {

    if (f == null) {
        reply(s, "501 Missing filename");
        return;
    }

    reply(s, "150 Opening data connection");

    Socket d = waitForData(c);
    if (d == null) {
        reply(s, "425 No data connection");
        return;
    }

    try (InputStream in = d.getInputStream();
         OutputStream out = fs.openOutputStream(c.cwd + "/" + f)) {

        byte[] buf = new byte[BUFFER_SIZE];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        out.flush();

    } finally {
        d.close();
    }

    reply(s, "226 Transfer complete");
}

    /* ===================== SAFE WAIT ===================== */

    private Socket waitForData(FtpSessionContext c) {

        for (int i = 0; i < 200; i++) {

            if (c.passiveDataSocket != null) {
                return c.passiveDataSocket;
            }

            try {
                Thread.sleep(25);
            } catch (InterruptedException ignored) {}
        }

        return null;
    }

    /* ===================== CLEANUP ===================== */

    private void cleanup(FtpSessionContext c) {

        try { if (c.passiveDataSocket != null) c.passiveDataSocket.close(); } catch (Exception ignored) {}
        try { if (c.passiveServerSocket != null) c.passiveServerSocket.close(); } catch (Exception ignored) {}

        c.passiveDataSocket = null;
        c.passiveServerSocket = null;
        c.pasvPort = -1;
    }

    /* ===================== UTIL ===================== */

    private String normalize(String p) {
        if (p == null || p.isEmpty()) return "/";
        return p.replace("//", "/");
    }
}
