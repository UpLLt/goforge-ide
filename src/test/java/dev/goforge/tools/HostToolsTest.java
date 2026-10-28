package dev.goforge.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

public final class HostToolsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path executable(Path path) throws Exception {
        Files.createDirectories(path.getParent()); Files.writeString(path,"discovery-only fixture");
        if (!HostTools.isWindows()) assertTrue(path.toFile().setExecutable(true));
        return path;
    }
    @Test public void windowsFindsNativeExeInMixedCasePathAndPreservesSpaces() throws Exception {
        Path directory=temp.newFolder("tools with spaces").toPath();
        Path target=executable(directory.resolve("docker.exe"));
        assertEquals(target.toString(),HostTools.resolve("docker",Map.of("Path",directory.toString()),true));
        assertEquals(target.toString(),HostTools.resolve("docker.exe",Map.of("pAtH",directory.toString()),true));
    }
    @Test public void windowsSearchesSemicolonEntriesWithoutUsingCmdScripts() throws Exception {
        Path first=temp.newFolder("scripts").toPath(),second=temp.newFolder("native").toPath();
        executable(first.resolve("kubectl.cmd"));Path target=executable(second.resolve("kubectl.exe"));
        assertEquals(target.toString(),HostTools.resolve("kubectl",Map.of("Path",first+";\""+second+"\""),true));
        assertThrows(java.io.IOException.class,()->HostTools.resolve("kubectl",Map.of("PATH",first.toString()),true));
    }
    @Test public void prependingWindowsPathPreservesSystemSearchAndRemovesDuplicateCaseKeys() {
        Map<String,String> input=Map.of("Path","system tools","PATH","preferred","TOKEN","unchanged");
        var result=HostTools.withPrependedPath(input,Path.of("sdk"),true);
        assertEquals("sdk;preferred",result.get("PATH"));assertFalse(result.containsKey("Path"));
        assertEquals("unchanged",result.get("TOKEN"));assertEquals(3,input.size());
    }
    @Test public void unixEnvironmentRemainsCaseSensitive() {
        var result=HostTools.withPrependedPath(Map.of("Path","unrelated","PATH","/usr/bin"),Path.of("sdk"),false);
        assertEquals("sdk:/usr/bin",result.get("PATH"));assertEquals("unrelated",result.get("Path"));
        assertEquals("",HostTools.environmentValue(Map.of("Path","x"),"PATH",false));
    }
    @Test public void windowsOpenSshFallbackUsesSuppliedSystemRoot() throws Exception {
        Path system=temp.newFolder("Windows fixture").toPath();
        Path expected=executable(system.resolve("System32/OpenSSH/ssh.exe"));
        assertEquals(expected.toString(),HostTools.resolve("ssh",Map.of("SYSTEMROOT",system.toString()),true));
        assertThrows(java.io.IOException.class,()->HostTools.resolve("kubectl",Map.of("SYSTEMROOT",system.toString()),true));
    }
    @Test public void pathLookupNeverAddsEmptyRelativeOrInvalidEntries() {
        assertTrue(HostTools.pathCandidates("go",Map.of("PATH",";.;relative;\u0000"),true).isEmpty());
        assertTrue(HostTools.pathCandidates("go",Map.of("PATH",":.:relative:\u0000"),false).isEmpty());
    }
    @Test public void explicitPathIsExactAndDoesNotSilentlyChooseAnotherTool() throws Exception {
        Path existing=executable(temp.getRoot().toPath().resolve("custom tool.exe"));
        assertEquals(existing.toString(),HostTools.resolve(existing.toString(),Map.of(),true));
        assertThrows(java.io.IOException.class,()->HostTools.resolve(existing.resolveSibling("missing.exe").toString(),Map.of("Path",existing.getParent().toString()),true));
    }
    @Test public void childEnvironmentOverrideReplacesWindowsCaseAliasesWithoutDroppingUnrelatedValues() {
        var target=new LinkedHashMap<>(Map.of("Path","old","PATH","duplicate","SystemRoot","windows","KEEP","yes"));
        HostTools.putEnvironment(target,Map.of("PATH","new","SYSTEMROOT","provided"),true);
        assertEquals(Map.of("PATH","new","SYSTEMROOT","provided","KEEP","yes"),target);
    }
    @Test public void executableSuffixDoesNotDuplicateExeAndUnixNamesStayUnchanged() {
        assertEquals("node.exe",HostTools.executableName("node",true));
        assertEquals("node.EXE",HostTools.executableName("node.EXE",true));
        assertEquals("node",HostTools.executableName("node",false));
    }
}
