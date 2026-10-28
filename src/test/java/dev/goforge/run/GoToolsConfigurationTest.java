package dev.goforge.run;

import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import org.jdom.Element;
import java.util.List;

public final class GoToolsConfigurationTest extends BasePlatformTestCase {
    private String previousGo;
    private java.nio.file.Path workingDirectory;
    @Override protected void setUp() throws Exception {
        super.setUp(); previousGo = GoForgeSettings.getInstance().goPath;
        GoForgeSettings.getInstance().goPath = java.nio.file.Path.of(System.getProperty("java.home"), "bin", dev.goforge.tools.HostTools.executableName("java")).toString();
        workingDirectory = java.nio.file.Files.createTempDirectory("goforge-go-tools-");
    }
    @Override protected void tearDown() throws Exception {
        try { GoForgeSettings.getInstance().goPath = previousGo; java.nio.file.Files.deleteIfExists(workingDirectory); } finally { super.tearDown(); }
    }
    public void testModuleAndWorkspaceSubcommandsArePassedWithoutAShell() throws Exception {
        var run = configuration();
        run.setDirectory(workingDirectory.toString());
        run.setCommand(GoCommand.WORK); run.setTarget("use \"module with spaces\"");
        run.checkConfiguration();
        assertEquals(List.of("work", "use", "module with spaces"), run.createCommandLine().getParametersList().getList());
        run.setTarget("edit"); run.setGoArguments("-json"); run.checkConfiguration();
        assertEquals(List.of("work", "edit", "-json"), run.createCommandLine().getParametersList().getList());
        run.setGoArguments("");
        run.setCommand(GoCommand.MOD); run.setTarget("edit -replace example.com/lib=../lib");
        run.checkConfiguration();
        assertEquals(List.of("mod", "edit", "-replace", "example.com/lib=../lib"), run.createCommandLine().getParametersList().getList());
        run.setCommand(GoCommand.TOOL); run.setTarget("pprof -http=127.0.0.1:0 fixture.pprof"); run.checkConfiguration();
        run.setCommand(GoCommand.RUN); run.setTarget(". -flag");
        assertThrows(com.intellij.execution.configurations.RuntimeConfigurationError.class, run::checkConfiguration);
    }
    public void testBuildDefaultsApplyWithoutLeakingIntoModuleSubcommands() {
        var defaults = GoProjectSettings.getInstance(getProject()); defaults.buildTags = "integration,custom";
        defaults.goos = "linux"; defaults.goarch = "arm64";
        var run = configuration(); run.setCommand(GoCommand.TEST); run.setTarget("./...");
        run.setGoArguments("-run TestOne -tags=override"); run.getEnvironment().put("GOOS", "darwin");
        var line = run.createCommandLine();
        assertEquals(List.of("test", "-tags=integration,custom", "-run", "TestOne", "-tags=override", "-json", "./..."), line.getParametersList().getList());
        assertEquals("darwin", line.getEffectiveEnvironment().get("GOOS")); assertEquals("arm64", line.getEffectiveEnvironment().get("GOARCH"));
        run.setCommand(GoCommand.MOD); run.setGoArguments(""); run.setTarget("tidy");
        assertEquals(List.of("mod", "tidy"), run.createCommandLine().getParametersList().getList());
    }
    public void testNewCommandsRoundTripAndCloneKeepsEnvironmentIndependent() throws Exception {
        for (var command : new GoCommand[]{GoCommand.MOD, GoCommand.WORK, GoCommand.GENERATE, GoCommand.LIST, GoCommand.ENV}) {
            var original = configuration(); original.setCommand(command); original.setTarget("quoted \"two words\""); original.getEnvironment().put("GOWORK", "off");
            var xml = new Element("configuration"); original.writeExternal(xml);
            var copy = configuration(); copy.readExternal(xml);
            assertEquals(command, copy.getCommand()); assertEquals(original.getTarget(), copy.getTarget());
            var clone = original.clone(); clone.getEnvironment().put("GOWORK", "other");
            assertEquals("off", original.getEnvironment().get("GOWORK"));
        }
    }
    public void testCoverageAndDebuggerEnvironmentPreservesInheritedFlags() {
        var defaults = GoProjectSettings.getInstance(getProject()); defaults.buildTags = "fixture"; defaults.goos = "linux";
        var source = java.util.Map.of("GOFLAGS", "-mod=readonly", "PATH", "/fixture");
        var actual = defaults.buildEnvironment(source);
        assertEquals("-mod=readonly -tags=fixture", actual.get("GOFLAGS")); assertEquals("linux", actual.get("GOOS"));
        assertEquals("-mod=readonly", source.get("GOFLAGS"));
    }
    private GoRunConfiguration configuration() {
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        return new GoRunConfiguration(getProject(), factory, "fixture");
    }
}
