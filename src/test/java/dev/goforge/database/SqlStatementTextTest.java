package dev.goforge.database;

import org.junit.Test;
import static org.junit.Assert.*;

public class SqlStatementTextTest {
    @Test public void permitsSemicolonsInQuotesAndComments() {
        String sql = "-- first;\n SELECT ';', 'it''s;ok', \"a;b\", [c;d] /* ; */; -- last\n";
        assertEquals(sql, SqlStatementText.single(sql, DatabaseProfile.Kind.SQLITE));
    }
    @Test public void rejectsMultipleStatementsBeforeDriverCanSilentlyIgnoreLaterOnes() {
        for (String sql : new String[]{"SELECT 1; SELECT 2", "SELECT 1;/* hidden */DROP TABLE data", "SELECT 1; -- hi\n DELETE FROM data"})
            assertThrows(IllegalArgumentException.class, () -> SqlStatementText.single(sql, DatabaseProfile.Kind.SQLITE));
    }
    @Test public void supportsDollarQuotedPostgresFunctionAndMysqlEscapes() {
        String postgres = "CREATE FUNCTION f() RETURNS text AS $body$ BEGIN RETURN ';'; END; $body$ LANGUAGE plpgsql;";
        assertEquals(postgres, SqlStatementText.single(postgres, DatabaseProfile.Kind.POSTGRESQL));
        String mysql = "SELECT 'a\\';b', `semi;colon`; # trailing\n";
        assertEquals(mysql, SqlStatementText.single(mysql, DatabaseProfile.Kind.MYSQL));
    }
    @Test public void rejectsMissingTextAndUnclosedTokens() {
        for (String sql : new String[]{"-- comment", "/* a */", "SELECT 'oops", "SELECT 1 /*oops", "SELECT $tag$oops"})
            assertThrows(IllegalArgumentException.class, () -> SqlStatementText.single(sql, DatabaseProfile.Kind.POSTGRESQL));
    }
    @Test public void transactionControlsCannotBypassConsoleState() {
        for (String sql : new String[]{"BEGIN", "/* x */ COMMIT", "ROLLBACK", "SAVEPOINT a", "START TRANSACTION"})
            assertThrows(IllegalArgumentException.class, () -> SqlStatementText.single(sql, DatabaseProfile.Kind.SQLITE));
        assertEquals("SELECT 'COMMIT'", SqlStatementText.single("SELECT 'COMMIT'", DatabaseProfile.Kind.SQLITE));
    }
}
