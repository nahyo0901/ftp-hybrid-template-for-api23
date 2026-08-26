package com.example.ftpengine;

import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.AuthorizationRequest;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real org.apache.ftpserver.ftplet.UserManager implementation, backed by the
 * same simple in-memory username/password map the old hand-rolled
 * FtpCommandProcessor used directly.
 *
 * Public API (constructor, addUser(username, password)) is unchanged, so
 * MainActivity.java and FtpServerService.java need no changes at all.
 *
 * NOTE: this project declares ftpserver-core:1.2.1 (not 1.1.1 like the
 * non-SAF descendant). UsernamePasswordAuthentication's package was
 * confirmed at org.apache.ftpserver.usermanager (no ".impl") for 1.1.1 --
 * if 1.2.1 moved it, this import is the first place to check on a build
 * error.
 */
public class FtpUserManager implements org.apache.ftpserver.ftplet.UserManager {

    private static final String ADMIN_NAME = "admin";

    private final ConcurrentHashMap<String, String> users = new ConcurrentHashMap<>();

    public FtpUserManager() {
        users.put("admin", "admin");
    }

    /** Same signature as before -- called directly by MainActivity.java. */
    public void addUser(String username, String password) {
        users.put(username, password);
    }

    /** Kept for anything still calling the old simple check directly. */
    public boolean authenticate(String username, String password) {
        String stored = users.get(username == null ? "" : username);
        return stored != null && stored.equals(password);
    }

    @Override
    public User getUserByName(String username) {
        String stored = users.get(username);
        if (stored == null) return null;
        return new SimpleFtpUser(username, stored);
    }

    @Override
    public String[] getAllUserNames() {
        return users.keySet().toArray(new String[0]);
    }

    @Override
    public void delete(String username) {
        users.remove(username);
    }

    @Override
    public void save(User user) {
        users.put(user.getName(), user.getPassword());
    }

    @Override
    public boolean doesExist(String username) {
        return users.containsKey(username);
    }

    @Override
    public User authenticate(Authentication authentication) throws AuthenticationFailedException {
        if (!(authentication instanceof UsernamePasswordAuthentication)) {
            throw new AuthenticationFailedException("Unsupported authentication type");
        }
        UsernamePasswordAuthentication upa = (UsernamePasswordAuthentication) authentication;
        String username = upa.getUsername();
        String password = upa.getPassword();
        String stored = users.get(username == null ? "" : username);
        if (stored != null && stored.equals(password)) {
            return new SimpleFtpUser(username, stored);
        }
        throw new AuthenticationFailedException("Bad username/password for " + username);
    }

    @Override
    public String getAdminName() {
        return ADMIN_NAME;
    }

    @Override
    public boolean isAdmin(String username) {
        return ADMIN_NAME.equals(username);
    }

    /** Minimal User implementation -- single root directory for every account. */
    private static class SimpleFtpUser implements User {
        private final String name;
        private final String password;

        SimpleFtpUser(String name, String password) {
            this.name = name;
            this.password = password;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public List<Authority> getAuthorities() {
            return new ArrayList<>();
        }

        @Override
        public List<Authority> getAuthorities(Class<? extends Authority> clazz) {
            return new ArrayList<>();
        }

        @Override
        public AuthorizationRequest authorize(AuthorizationRequest request) {
            return request; // allow everything -- same permissiveness as before
        }

        @Override
        public int getMaxIdleTime() {
            return 0; // no idle timeout
        }

        @Override
        public boolean getEnabled() {
            return true;
        }

        @Override
        public String getHomeDirectory() {
            return "/";
        }
    }
}
