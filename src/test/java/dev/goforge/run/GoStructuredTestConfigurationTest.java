package dev.goforge.run;

import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.testing.GoTestSelection;
import org.jdom.Element;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GoStructuredTestConfigurationTest extends BasePlatformTestCase {
    private String previousGo;
    private Path directory;

    @Override protected void setUp() throws Exception {
        super.setUp();
        previousGo = GoForgeSettings.getInstance().goPath;
        GoForgeSettings.getInstance().goPath = Path.of(System.getProperty("java.home"), "bin", dev.goforge.tools.HostTools.executableName("java")).toString();
        directory = Files.createTempDirectory("goforge-selected-tests-");
    }

    @Override protected void tearDown() throws Exception {
        try { GoForgeSettings.getInstance().goPath = previousGo; Files.deleteIfExists(directory); }
        finally { super.tearDown(); }
    }

    public void testNamedCommandHasOneLiteralSelectorAndPreservesBuildEnvironment() throws Exception {
        var config = configuration();
        var defaults = GoProjectSettings.getInstance(getProject()); defaults.buildTags = "integration"; defaults.goarch = "arm64";
        config.setTestSelection(GoTestSelection.rerun(List.of("TestA/x+y", "Example_output")));
        config.setGoArguments("-race -timeout 30s -gcflags \"all=-N -l\"");
        config.getEnvironment().put("GOOS", "linux"); config.getEnvironment().put("GOFLAGS", "-mod=readonly");
        config.checkConfiguration();
        var line = config.createCommandLine();
        assertEquals(List.of("test", "-tags=integration", "-race", "-timeout", "30s", "-gcflags", "all=-N -l", "-json",
            "-run=^TestA$/^x\\+y$|^Example_output$", "-count=1", "."), line.getParametersList().getList());
        assertEquals("linux", line.getEffectiveEnvironment().get("GOOS"));
        assertEquals("arm64", line.getEffectiveEnvironment().get("GOARCH"));
        assertEquals("-mod=readonly", line.getEffectiveEnvironment().get("GOFLAGS"));
    }

    public void testLegacyPackageAndManualRunFlagsKeepTheirExactSemantics() throws Exception {
        var config = configuration(); config.setTarget("./..."); config.setGoArguments("-run TestA -count=2");
        config.checkConfiguration();
        assertEquals(List.of("test", "-run", "TestA", "-count=2", "-json", "./..."), config.createCommandLine().getParametersList().getList());
        var xml = new Element("configuration"); config.writeExternal(xml);
        assertNull(xml.getChild("goforge-test-selection"));
        var restored = configuration(); restored.readExternal(xml); assertNull(restored.getTestSelection());
        assertEquals(config.createCommandLine().getParametersList().getList(), restored.createCommandLine().getParametersList().getList());
    }

    public void testNamedSelectionRoundTripsWithoutMutatingTheOriginalWhenCloned() throws Exception {
        var config = configuration(); config.setTestSelection(GoTestSelection.rerun(List.of("TestA/a+b/", "Example_output")));
        var xml = new Element("configuration"); config.writeExternal(xml);
        var restored = configuration(); restored.readExternal(xml); restored.checkConfiguration();
        assertEquals(config.getTestSelection(), restored.getTestSelection());
        var copy = restored.clone(); copy.setTestSelection(GoTestSelection.single("TestOther")); copy.getEnvironment().put("GOFLAGS", "-race");
        assertEquals(config.getTestSelection(), restored.getTestSelection());
        assertEquals("", restored.getEnvironment().get("GOFLAGS"));
    }

    public void testConflictingSelectionAndPositionalFlagsFailBeforeACommandCanRun() {
        var config = configuration(); config.setTestSelection(GoTestSelection.single("TestA"));
        for (String args : List.of("-run=Other", "-test.run Other", "-args -test.run=Other", "-count 2", "./...", "-- -run Other")) {
            config.setGoArguments(args);
            assertThrows(RuntimeConfigurationError.class, config::checkConfiguration);
            assertThrows(IllegalArgumentException.class, config::createCommandLine);
        }
        config.setGoArguments(""); config.getEnvironment().put("GOFLAGS", "'-test.run=Other'");
        assertThrows(RuntimeConfigurationError.class, config::checkConfiguration);
        config.getEnvironment().put("GOFLAGS", ""); config.setTarget("./...");
        assertThrows(RuntimeConfigurationError.class, config::checkConfiguration);
        config.setTarget("."); config.setCommand(GoCommand.RUN);
        assertThrows(RuntimeConfigurationError.class, config::checkConfiguration);
    }

    public void testMalformedSavedSelectionNeverFallsBackToAnUnfilteredPackage() {
        for (Element selected : List.of(
            new Element("goforge-test-selection").setAttribute("version", "2").addContent(new Element("test").setAttribute("name", "TestA")),
            new Element("goforge-test-selection").setAttribute("version", "1").setAttribute("rerun", "yes").addContent(new Element("test").setAttribute("name", "TestA")),
            new Element("goforge-test-selection").setAttribute("version", "1"),
            new Element("goforge-test-selection").setAttribute("version", "1").addContent(new Element("test")),
            new Element("goforge-test-selection").setAttribute("version", "1").addContent(new Element("unknown")))) {
            var original = configuration(); var xml = new Element("configuration"); original.writeExternal(xml); xml.addContent(selected);
            var malformed = configuration(); malformed.readExternal(xml);
            assertNotNull(malformed.getTestSelectionReadError());
            assertThrows(RuntimeConfigurationError.class, malformed::checkConfiguration);
            assertThrows(IllegalArgumentException.class, malformed::createCommandLine);
            var reSaved = new Element("configuration"); malformed.clone().writeExternal(reSaved);
            var reloaded = configuration(); reloaded.readExternal(reSaved);
            assertNotNull(reloaded.getTestSelectionReadError());
            assertThrows(IllegalArgumentException.class, reloaded::createCommandLine);
            reloaded.setTestSelection(null); assertEquals(List.of("test", "-json", "."), reloaded.createCommandLine().getParametersList().getList());
        }
    }

    public void testDuplicateSelectionXmlIsRetainedAsAnError() {
        var config = configuration(); var xml = new Element("configuration"); config.writeExternal(xml);
        for (String name : List.of("TestA", "TestB")) xml.addContent(new Element("goforge-test-selection").setAttribute("version", "1")
            .addContent(new Element("test").setAttribute("name", name)));
        config.readExternal(xml); assertThrows(IllegalArgumentException.class, config::createCommandLine);
        var saved = new Element("configuration"); config.writeExternal(saved); assertEquals(2, saved.getChildren("goforge-test-selection").size());
    }

    public void testActualSettingsEditorAppliesLiteralNamesAndVisibleFreshRunOption() throws Exception {
        var config = configuration(); var editor = new GoRunSettingsEditor(); editor.resetEditorFrom(config);
        JComponent ui = editor.createEditor();
        var scope = (JComboBox<?>) named(ui, "Test selection"); scope.setSelectedIndex(1);
        var names = (JTextArea) named(ui, "Test names"); assertTrue(names.isEnabled()); names.setText("TestA/a+b\nExample_output");
        JCheckBox fresh = checkbox(ui); assertTrue(fresh.getText().contains("-count=1")); fresh.setSelected(true);
        editor.applyEditorTo(config);
        assertEquals(GoTestSelection.rerun(List.of("TestA/a+b", "Example_output")), config.getTestSelection());
        assertTrue(config.createCommandLine().getParametersList().getList().contains("-count=1"));
        scope.setSelectedIndex(0); editor.applyEditorTo(config); assertNull(config.getTestSelection()); assertFalse(names.isEnabled());
    }

    private GoRunConfiguration configuration() {
        var factory = ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
        var config = new GoRunConfiguration(getProject(), factory, "selected fixture");
        config.setCommand(GoCommand.TEST); config.setDirectory(directory.toString()); config.setTarget("."); config.getEnvironment().put("GOFLAGS", "");
        return config;
    }

    private static Component named(Component component, String name) {
        if (component instanceof JComponent swing && name.equals(swing.getAccessibleContext().getAccessibleName())) return component;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            Component found = named(child, name); if (found != null) return found;
        }
        return null;
    }

    private static JCheckBox checkbox(Component component) {
        if (component instanceof JCheckBox box && box.getText().contains("-count=1")) return box;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            JCheckBox found = checkbox(child); if (found != null) return found;
        }
        return null;
    }
}
