package dev.goforge.project;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class GoProjectScaffolderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void neverOverwritesAnExistingProject() throws Exception {
        Path project = temporary.newFolder("existing").toPath();
        Path source = Files.writeString(project.resolve("main.go"), "user-owned contents");
        assertThrows(FileAlreadyExistsException.class, () -> GoProjectScaffolder.create(project, "example.com/new", GoProjectScaffolder.Template.APPLICATION, "1.26"));
        assertEquals("user-owned contents", Files.readString(source));
        assertFalse(Files.exists(project.resolve("go.mod")));
    }

    @Test public void refusesAnExistingEmptyDirectoryOrSymlink() throws Exception {
        Path existing = temporary.newFolder("empty").toPath();
        assertThrows(FileAlreadyExistsException.class, () -> GoProjectScaffolder.create(existing, "example.com/new", GoProjectScaffolder.Template.APPLICATION, "1.26"));
        Path link = temporary.getRoot().toPath().resolve("linked");
        Files.createSymbolicLink(link, existing);
        assertThrows(FileAlreadyExistsException.class, () -> GoProjectScaffolder.create(link, "example.com/new", GoProjectScaffolder.Template.APPLICATION, "1.26"));
        assertTrue(Files.isSymbolicLink(link));
        try (var files = Files.list(existing)) { assertEquals(0, files.count()); }
    }

    @Test public void rejectsModuleInjectionBeforeCreatingDirectory() {
        Path project = temporary.getRoot().toPath().resolve("new");
        for (String module : new String[]{"../outside", "example.com/../outside", "example.com//empty", "example.com/x\nrequire bad v1.0.0", "a\"b", "foo/", ""}) {
            assertThrows(IllegalArgumentException.class, () -> GoProjectScaffolder.create(project, module, GoProjectScaffolder.Template.APPLICATION, "1.26"));
            assertFalse(Files.exists(project));
        }
    }

    @Test public void rejectsUnresolvedSdkBeforeCreatingDirectory() {
        Path project = temporary.getRoot().toPath().resolve("new");
        assertThrows(IllegalArgumentException.class, () -> GoProjectScaffolder.create(project, "example.com/new", GoProjectScaffolder.Template.APPLICATION, "invalid\nrequire"));
        assertFalse(Files.exists(project));
    }

    @Test public void supportsUnicodeAndSpacesInLocalProjectPath() throws Exception {
        Path project = temporary.getRoot().toPath().resolve("我的 Go 工程");
        var files = GoProjectScaffolder.create(project, "example.com/hello", GoProjectScaffolder.Template.APPLICATION, "1.26");
        assertTrue(files.stream().allMatch(p -> p.getParent().equals(project)));
        assertEquals(4, files.size());
        assertTrue(Files.readString(project.resolve("go.mod")).contains("module example.com/hello"));
        assertTrue(Files.exists(project.resolve("main_test.go")));
    }

    @Test public void libraryVersionSuffixAndKeywordProduceValidPackageIdentifier() throws Exception {
        Path project = temporary.getRoot().toPath().resolve("versioned-library");
        GoProjectScaffolder.create(project, "example.com/type/v2", GoProjectScaffolder.Template.LIBRARY, "1.26");
        assertTrue(Files.readString(project.resolve("hello.go")).contains("package typepkg\n"));
        assertTrue(Files.readString(project.resolve("hello_test.go")).startsWith("package typepkg\n"));
        assertFalse(Files.exists(project.resolve("main.go")));
    }

    @Test public void libraryNamedMainIsAnImportablePackageIncludingVersionSuffix() throws Exception {
        int index = 0;
        for (String module : new String[]{"main", "example.com/main", "example.com/main/v2"}) {
            Path project = temporary.getRoot().toPath().resolve("main-library-" + index++);
            GoProjectScaffolder.create(project, module, GoProjectScaffolder.Template.LIBRARY, "1.26");
            assertTrue(Files.readString(project.resolve("hello.go")).contains("package mainpkg\n"));
            assertTrue(Files.readString(project.resolve("hello_test.go")).startsWith("package mainpkg\n"));
            assertFalse(Files.exists(project.resolve("main.go")));
        }
    }

    @Test public void rejectsGoReservedAndShortNameSegmentsBeforeCreatingDirectory() {
        Path project = temporary.getRoot().toPath().resolve("invalid-module");
        for (String module : new String[]{"example.com/CON", "example.com/con.go", "example.com/Aux/v2",
                "example.com/lpt9", "example.com/foo~1", "example.com/foo~123.go", "com1/local"}) {
            assertThrows(module, IllegalArgumentException.class, () -> GoProjectScaffolder.create(
                    project, module, GoProjectScaffolder.Template.LIBRARY, "1.26"));
            assertFalse(Files.exists(project));
        }
    }

    @Test public void keepsValidLocalModulesAndNonReservedNames() {
        for (String module : new String[]{"localmodule", "localmodule/pkg", "example.com/console",
                "example.com/com10", "example.com/foo~v1", "example.com/foo~"}) {
            GoProjectScaffolder.validateModulePath(module);
        }
    }
}
