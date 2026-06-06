# FTP Hybrid Server Template

An Android FTP server template built on **Apache MINA 2.x** and a SAF-aware
FTP engine core. Tested with FileZilla; supports passive mode, binary transfers,
and Android's Storage Access Framework (SAF) as the filesystem backend.

---

## Module Structure

```
ftp-hybrid-template/
├── ftp-engine-core/      # FTP protocol logic, filesystem abstractions, user management
├── ftp-hybrid-server/    # Connects MINA networking to ftp-engine-core
└── sample-app/           # Working Android app — folder picker, start/stop, user management
```

---

## Requirements

| Tool | Version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android SDK | API 23 (Android 6.0) minimum |
| Java | 17 |
| Gradle | 8.x |

---

## Key Dependencies

Both `ftp-engine-core` and `ftp-hybrid-server` pull Apache MINA directly from
Maven Central — no local patched JAR or AAR is required.

```groovy
// ftp-engine-core/build.gradle and ftp-hybrid-server/build.gradle
implementation "org.apache.mina:mina-core:2.0.21"
```

The `mina-android-patched` module referenced in older versions of this template
has been **removed**. MINA 2.0.21 from Maven Central works on Android API 23+
without modification when the following packaging exclusions are present (already
configured in both modules):

```groovy
packagingOptions {
    resources {
        excludes += [
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/ASL2.0"
        ]
    }
}
```

---

## Getting Started

### 1. Clone

```bash
git clone [https://github.com/nahyo0901/ftp-hybrid-template-for-api23.git]
cd ftp-hybrid-template-for-api23
```

### 2. Open in Android Studio

File → Open → select the cloned folder. Let Gradle sync finish.

### 3. Run the sample app

Connect a physical device or start an emulator (API 23+), select
`sample-app` as the run configuration, and press Run.

---

## Using the Sample App

1. **Choose Folder** — opens the Android folder picker (SAF). Grant read/write
   permission to the folder you want to share over FTP.
2. **Add User** — enter a username and password, then tap Add User.
3. **Start Server** — starts the FTP server on port `2121`.
4. Connect from FileZilla (or any FTP client):
   - Host: your device's local IP address
   - Port: `2121`
   - Protocol: FTP (plain, no TLS)
   - Logon type: Normal
   - Enter the username and password you added

---

## Embedding the Server in Your Own App

```java
// 1. Get a SAF tree Uri from ACTION_OPEN_DOCUMENT_TREE
Uri treeUri = ...; // from ActivityResultLauncher

// 2. Take persistable permission
getContentResolver().takePersistableUriPermission(
    treeUri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
);

// 3. Build the filesystem and engine
SAFFileSystem fs = new SAFFileSystem(context, treeUri);
FtpEngineHybrid ftpEngine = new FtpEngineHybrid(context, fs);

// 4. Add users
ftpEngine.getUserManager().addUser("admin", "password");

// 5. Start (run off the main thread)
new Thread(() -> ftpEngine.start(2121)).start();

// 6. Stop when done
ftpEngine.stop();
```

For long-running use, wrap the engine in a foreground `Service` — see
`FtpServerService.java` in `sample-app` for a complete example.

---

## Architecture

```
FTP Client (FileZilla)
        │  TCP
        ▼
  FtpIoHandlerAndroid     ← MINA IoHandler (ftp-hybrid-server)
        │
        ▼
  FtpCommandProcessor     ← Parses FTP commands (ftp-engine-core)
        │
        ▼
  IFtpFileSystem          ← Filesystem interface (ftp-engine-core)
        │
   ┌────┴────┐
   │         │
SAFFileSystem  FtpFileSystem
(Android SAF)  (java.io.File)
```

### Key classes

| Class | Module | Purpose |
|---|---|---|
| `FtpEngineHybrid` | ftp-hybrid-server | Entry point — wires MINA to the command processor |
| `FtpCommandProcessor` | ftp-engine-core | Handles USER, PASS, PASV, LIST, RETR, STOR, etc. |
| `FtpSessionContext` | ftp-engine-core | Per-connection state (cwd, transfer type, passive socket) |
| `IFtpFileSystem` | ftp-engine-core | Filesystem interface — swap SAF for any other backend |
| `SAFFileSystem` | ftp-engine-core | Android SAF implementation |
| `SAFFileObject` | ftp-engine-core | SAF document URI resolution and I/O |
| `FtpUserManager` | ftp-engine-core | In-memory username/password store |

---

## Known Limitations

- **No TLS/FTPS** — plain FTP only. FileZilla will show "Insecure server" warning; this is expected.
- **Passive mode only** — active mode (PORT command) is not implemented.
- **In-memory user store** — users are lost when the server stops. Persist them yourself if needed.
- **Full file buffering** — RETR/STOR reads/writes the entire file into a `byte[]`. This works
  well for typical files but will cause `OutOfMemoryError` on very large files. Stream directly
  from the SAF `InputStream` to the socket for production use.
- **No MLSD/SIZE** — newer FTP clients prefer these commands for accurate file sizes and
  timestamps. `LIST` in Unix `ls -l` format is used instead.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
