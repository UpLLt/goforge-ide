package dev.goforge.database;

import org.junit.Test;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class DatabaseProfileTest {
    private DatabaseProfile remote(DatabaseProfile.Kind kind, String host, String database) {
        return new DatabaseProfile("id", "Test", kind, "", host, kind.defaultPort, database, "db-user", true);
    }
    @Test public void remoteUrlsEncodeDatabaseAndKeepCredentialsSeparate() {
        var profile = remote(DatabaseProfile.Kind.POSTGRESQL, "::1", "sales & reports");
        assertEquals("jdbc:postgresql://[::1]:5432/sales%20%26%20reports", profile.jdbcUrl());
        var properties = profile.properties("secret-value".toCharArray());
        assertEquals("secret-value", properties.getProperty("password"));
        assertEquals("verify-full", properties.getProperty("sslmode"));
        assertFalse(profile.jdbcUrl().contains("secret")); assertFalse(profile.toString().contains("db-user"));
    }
    @Test public void mysqlUsesMariaDriverAndDisablesLocalFileAndBatchLoading() {
        var profile = remote(DatabaseProfile.Kind.MYSQL, "localhost", "example");
        assertEquals("jdbc:mariadb://localhost:3306/example", profile.jdbcUrl());
        var properties = profile.properties(new char[0]);
        assertEquals("false", properties.getProperty("allowLocalInfile"));
        assertEquals("false", properties.getProperty("allowMultiQueries"));
        assertEquals("verify-full", properties.getProperty("sslMode"));
    }
    @Test public void rejectsCredentialOrOptionBearingHostAndSqliteUris() {
        for (String host : new String[]{"user:password@localhost", "localhost/db?password=secret", "localhost&ssl=false", "host\n"}) {
            // Trailing whitespace is normalized; embedded newlines and URL syntax are rejected.
            if (host.equals("host\n")) host = "ho\nst";
            String badHost = host;
            assertThrows(IllegalArgumentException.class, () -> remote(DatabaseProfile.Kind.POSTGRESQL, badHost, "db"));
        }
        for (String path : new String[]{"relative.db", "file:/tmp/data?mode=rw", "/tmp/data?password=secret"})
            assertThrows(IllegalArgumentException.class, () -> new DatabaseProfile("id", "Test", DatabaseProfile.Kind.SQLITE, path, "", 0, "", "", true));
    }
    @Test public void sqliteSupportsSpaceUnicodeAbsoluteFilesWithoutCreatingThem() {
        String path = Path.of(System.getProperty("java.io.tmpdir"), "数据库 unused file.db").toAbsolutePath().toString();
        var profile = new DatabaseProfile("id", "Local", DatabaseProfile.Kind.SQLITE, path, "", 0, "", "", true);
        assertEquals("jdbc:sqlite:" + path, profile.jdbcUrl());
        assertEquals("jdbc:sqlite::memory:", DatabaseProfile.scratch().jdbcUrl());
    }
}
