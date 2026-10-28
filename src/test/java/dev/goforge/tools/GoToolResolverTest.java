package dev.goforge.tools;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.Assert.*;

public class GoToolResolverTest {
    private static Path executable(Path p) throws Exception {
        Files.createDirectories(p.getParent()); Files.writeString(p, "discovery-only fixture");
        if (!HostTools.isWindows()) assertTrue(p.toFile().setExecutable(true)); return p;
    }
    @Test public void configuredPathTakesPrecedenceAndPreservesSpaces() throws Exception {
        Path dir = Files.createTempDirectory("goforge tools ");
        Path configured = executable(dir.resolve("custom go"));
        assertEquals(configured.toString(), GoToolResolver.resolve(configured.toString(), "go", null, Map.of()));
    }
    @Test public void explicitMissingPathDoesNotSilentlyUseAnotherVersion() throws Exception {
        Path dir = Files.createTempDirectory("goforge-tools");
        executable(dir.resolve(HostTools.executableName("go")));
        assertThrows(IllegalStateException.class, () -> GoToolResolver.resolve(dir.resolve("missing").toString(), "go", null, Map.of("PATH", dir.toString())));
    }
    @Test public void projectToolsAreFoundWithoutChangingGlobalPath() throws Exception {
        Path dir = Files.createTempDirectory("goforge-project");
        Path expected = executable(dir.resolve(".tools/bin/" + HostTools.executableName("gopls")));
        assertEquals(expected.toString(), GoToolResolver.resolve("", "gopls", dir.toString(), Map.of()));
    }
    @Test public void emptyPathEntryNeverExecutesFromCurrentDirectory() {
        assertThrows(IllegalStateException.class, () -> GoToolResolver.resolve("", "goforge-nonexistent", null, Map.of("PATH", ":")));
    }
    @Test public void usesTheSuppliedChildEnvironmentPath() throws Exception {
        Path dir = Files.createTempDirectory("shell path ");
        Path expected = executable(dir.resolve(HostTools.executableName("go")));
        assertEquals(expected.toString(), GoToolResolver.resolve("", "go", null, Map.of("PATH", dir.toString())));
    }
    @Test public void standaloneBundleWorksWithoutGlobalPath() throws Exception {
        Path bundle = Files.createTempDirectory("GoForge bundle ");
        Path expected = executable(bundle.resolve(HostTools.executableName("gopls")));
        assertEquals(expected.toString(), GoToolResolver.resolve("", "gopls", null, Map.of(), bundle));
    }
    @Test public void explicitEnvironmentAndProjectToolsStillOverrideBundle() throws Exception {
        Path bundle = Files.createTempDirectory("goforge-bundled");
        executable(bundle.resolve(HostTools.executableName("go")));
        Path environment = Files.createTempDirectory("goforge-env");
        Path environmentGo = executable(environment.resolve(HostTools.executableName("go")));
        Path project = Files.createTempDirectory("goforge-project-tools");
        Path projectGo = executable(project.resolve(".tools/bin/" + HostTools.executableName("go")));
        assertEquals(environmentGo.toString(), GoToolResolver.resolve("", "go", project.toString(), Map.of("GOFORGE_TOOLS_DIR", environment.toString()), bundle));
        assertEquals(projectGo.toString(), GoToolResolver.resolve("", "go", project.toString(), Map.of(), bundle));
    }
    @Test public void bundleUsesActualSdkGoWithoutWrapperAndRetainsLegacyFallback() throws Exception {
        Path bundle=Files.createTempDirectory("goforge-real-sdk").resolve("bin");
        Path shim=executable(bundle.resolve(HostTools.executableName("go")));
        Path real=executable(bundle.getParent().resolve("go/bin").resolve(HostTools.executableName("go")));
        assertEquals(real.toString(),GoToolResolver.resolve("","go",null,Map.of(),bundle));
        Files.delete(real);
        assertEquals(shim.toString(),GoToolResolver.resolve("","go",null,Map.of(),bundle));
    }
}
