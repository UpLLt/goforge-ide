package dev.goforge.run;

import org.junit.Test;
import static org.junit.Assert.*;

public class GoSourcePackageTest {
    @Test public void readsPackageAfterBuildTagsAndBom() {
        assertEquals("main", GoSourcePackage.name("\uFEFF//go:build darwin\n\n/* documentation */\npackage main\nfunc main() {}"));
    }
    @Test public void mainInsideCommentDoesNotMakeLibraryRunnable() {
        assertEquals("numbers", GoSourcePackage.name("/*\npackage main\n*/\npackage numbers\n"));
    }
    @Test public void unclosedCommentAndMissingDeclarationStayUnknown() {
        assertNull(GoSourcePackage.name("/* package main"));
        assertNull(GoSourcePackage.name("var x = `package main`"));
    }
    @Test public void acceptsUnicodeIdentifiers() { assertEquals("计算", GoSourcePackage.name("package 计算\n")); }
    @Test public void commentBetweenKeywordAndNameIsTrivia() { assertEquals("main", GoSourcePackage.name("package/* generated */main;")); }
    @Test public void packagePrefixInsideIdentifierIsNotADeclaration() { assertNull(GoSourcePackage.name("packagemain main")); }
}
