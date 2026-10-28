package dev.goforge.database;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** Only non-secret, structured connection settings. Never accepts a credential-bearing JDBC URL. */
public record DatabaseProfile(String id, String name, Kind kind, String sqlitePath,
                              String host, int port, String database, String username, boolean verifyTls) {
    public enum Kind {
        SQLITE("SQLite", "org.sqlite.JDBC", 0),
        POSTGRESQL("PostgreSQL", "org.postgresql.Driver", 5432),
        MYSQL("MySQL / MariaDB", "org.mariadb.jdbc.Driver", 3306);
        public final String label, driverClass;
        public final int defaultPort;
        Kind(String label, String driverClass, int port) { this.label = label; this.driverClass = driverClass; defaultPort = port; }
        @Override public String toString() { return label; }
    }

    public DatabaseProfile {
        Objects.requireNonNull(kind);
        id = clean(id); name = clean(name); sqlitePath = clean(sqlitePath);
        host = clean(host); database = clean(database); username = clean(username);
        if (id.isEmpty() || name.isEmpty()) throw new IllegalArgumentException("Connection name is required.");
        if (kind == Kind.SQLITE) {
            if (sqlitePath.isEmpty()) throw new IllegalArgumentException("Enter :memory: or an absolute SQLite file path.");
            if (!sqlitePath.equals(":memory:") && (!Path.of(sqlitePath).isAbsolute()
                    || sqlitePath.contains("?") || sqlitePath.contains("#")))
                throw new IllegalArgumentException("SQLite needs an absolute file path without URL options, or :memory:.");
        } else {
            if (!host.matches("[A-Za-z0-9._:-]+") || host.isBlank())
                throw new IllegalArgumentException("Enter a host name or IP address, without URL options or credentials.");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
            if (database.isEmpty()) throw new IllegalArgumentException("Database name is required.");
        }
    }

    private static String clean(String value) {
        value = value == null ? "" : value.trim();
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Connection fields cannot contain control characters.");
        return value;
    }
    public static DatabaseProfile scratch() {
        return new DatabaseProfile("scratch-sqlite", "Scratch SQLite (memory)", Kind.SQLITE, ":memory:", "", 0, "", "", true);
    }
    public static String newId() { return UUID.randomUUID().toString(); }
    public String jdbcUrl() {
        if (kind == Kind.SQLITE) return "jdbc:sqlite:" + sqlitePath;
        String address = host.contains(":") ? "[" + host + "]" : host;
        return (kind == Kind.POSTGRESQL ? "jdbc:postgresql://" : "jdbc:mariadb://") + address + ":" + port + "/"
                + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
    }
    Properties properties(char[] password) {
        Properties values = new Properties();
        if (kind == Kind.SQLITE) {
            values.setProperty("busy_timeout", "5000");
            values.setProperty("foreign_keys", "true");
        } else {
            values.setProperty("user", username);
            values.setProperty("password", new String(password));
            if (kind == Kind.POSTGRESQL) {
                values.setProperty("connectTimeout", "10"); values.setProperty("socketTimeout", "35");
                values.setProperty("sslmode", verifyTls ? "verify-full" : "disable");
                values.setProperty("ApplicationName", "GoForge");
            } else {
                values.setProperty("connectTimeout", "10000"); values.setProperty("socketTimeout", "35000");
                values.setProperty("sslMode", verifyTls ? "verify-full" : "disable");
                values.setProperty("allowLocalInfile", "false");
                values.setProperty("allowMultiQueries", "false");
            }
        }
        return values;
    }
    @Override public String toString() { return name; }
}
