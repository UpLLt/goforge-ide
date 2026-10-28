package dev.goforge.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.util.execution.ParametersListUtil;
import dev.goforge.settings.GoForgeSettings;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.tools.GoToolResolver;
import dev.goforge.testing.GoTestProcessHandler;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GoRunConfiguration extends RunConfigurationBase<RunConfigurationOptions> {
    private GoCommand command = GoCommand.RUN;
    private String directory;
    private String target = ".";
    private String goArguments = "";
    private String programArguments = "";
    private Map<String, String> environment = new LinkedHashMap<>();

    public GoRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
        directory = project.getBasePath() == null ? "" : project.getBasePath();
    }
    public GoCommand getCommand() { return command; }
    public void setCommand(GoCommand value) { command = value; }
    public String getDirectory() { return directory; }
    public void setDirectory(String value) { directory = value; }
    public String getTarget() { return target; }
    public void setTarget(String value) { target = value; }
    public String getGoArguments() { return goArguments; }
    public void setGoArguments(String value) { goArguments = value; }
    public String getProgramArguments() { return programArguments; }
    public void setProgramArguments(String value) { programArguments = value; }
    public Map<String, String> getEnvironment() { return environment; }

    @Override public GoRunConfiguration clone() {
        GoRunConfiguration copy = (GoRunConfiguration) super.clone();
        copy.environment = new LinkedHashMap<>(environment);
        return copy;
    }

    @Override public @NotNull SettingsEditor<GoRunConfiguration> getConfigurationEditor() { return new GoRunSettingsEditor(); }

    @Override public void checkConfiguration() throws RuntimeConfigurationException {
        try {
            if (directory == null || directory.isBlank() || !Files.isDirectory(Path.of(directory)))
                throw new RuntimeConfigurationError("Choose an existing working directory.");
            if (target == null || target.isBlank()) throw new RuntimeConfigurationError("Enter a package target or subcommand.");
            var targets = ParametersListUtil.parse(target);
            boolean subcommand = command == GoCommand.MOD || command == GoCommand.WORK || command == GoCommand.TOOL;
            if (targets.isEmpty() || targets.getFirst().startsWith("-")
                    || (!subcommand && targets.stream().anyMatch(p -> p.startsWith("-"))))
                throw new RuntimeConfigurationError(subcommand ? "Enter a subcommand followed by its options."
                        : "Put flags in Go arguments, not in Package / file target.");
            for (String key : environment.keySet()) if (!key.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new RuntimeConfigurationError("Invalid environment variable name: " + key);
            GoToolResolver.resolve(GoForgeSettings.getInstance().goPath, "go", getProject().getBasePath(),
                new GeneralCommandLine().withEnvironment(environment).getEffectiveEnvironment());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new RuntimeConfigurationError(e.getMessage());
        }
    }

    public GeneralCommandLine createCommandLine() {
        var defaults = GoProjectSettings.getInstance(getProject());
        var line = new GeneralCommandLine().withEnvironment(defaults.environment()).withEnvironment(environment).withWorkDirectory(directory).withCharset(StandardCharsets.UTF_8);
        String go = GoToolResolver.resolve(GoForgeSettings.getInstance().goPath, "go", getProject().getBasePath(), line.getEffectiveEnvironment());
        line.withExePath(go).withParameters(command.argument());
        if (command == GoCommand.RUN || command == GoCommand.TEST || command == GoCommand.BUILD
                || command == GoCommand.VET || command == GoCommand.LIST || command == GoCommand.GENERATE)
            line.addParameters(defaults.buildFlags());
        var targets = ParametersListUtil.parse(target);
        if ((command == GoCommand.MOD || command == GoCommand.WORK || command == GoCommand.TOOL) && !targets.isEmpty()) {
            line.addParameter(targets.getFirst());
            line.addParameters(ParametersListUtil.parse(goArguments));
            line.addParameters(targets.subList(1, targets.size()));
        } else {
            line.addParameters(ParametersListUtil.parse(goArguments));
            if (command == GoCommand.TEST) line.addParameter("-json");
            line.addParameters(targets);
        }
        if (command == GoCommand.RUN) line.addParameters(ParametersListUtil.parse(programArguments));
        return line;
    }

    @Override public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        return new CommandLineState(environment) {
            @Override public @NotNull ExecutionResult execute(@NotNull Executor executor, @NotNull ProgramRunner<?> runner) throws ExecutionException {
                if (command != GoCommand.TEST) return super.execute(executor, runner);
                ProcessHandler handler = startProcess();
                var properties = new SMTRunnerConsoleProperties(GoRunConfiguration.this, "GoForge", executor);
                properties.setIdBasedTestTree(true);
                var console = SMTestRunnerConnectionUtil.createAndAttachConsole("GoForge", handler, properties);
                return new DefaultExecutionResult(console, handler, createActions(console, handler, executor));
            }
            @Override protected @NotNull ProcessHandler startProcess() throws ExecutionException {
                try {
                    var handler = command == GoCommand.TEST ? new GoTestProcessHandler(createCommandLine())
                        : new KillableColoredProcessHandler(createCommandLine());
                    handler.setShouldDestroyProcessRecursively(true);
                    ProcessTerminatedListener.attach(handler);
                    return handler;
                } catch (IllegalArgumentException | IllegalStateException e) {
                    throw new ExecutionException(e.getMessage(), e);
                }
            }
        };
    }

    @Override public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        element.setAttribute("goforgeCommand", command.name());
        element.setAttribute("goforgeDirectory", directory);
        element.setAttribute("goforgeTarget", target);
        element.setAttribute("goforgeArguments", goArguments);
        element.setAttribute("goforgeProgramArguments", programArguments);
        element.removeChildren("goforge-environment");
        Element env = new Element("goforge-environment");
        environment.forEach((key,value) -> env.addContent(new Element("entry").setAttribute("name",key).setAttribute("value",value)));
        element.addContent(env);
    }

    @Override public void readExternal(@NotNull Element element) {
        super.readExternal(element);
        try { command = GoCommand.valueOf(element.getAttributeValue("goforgeCommand", "RUN")); }
        catch (IllegalArgumentException ignored) { command = GoCommand.RUN; }
        directory = element.getAttributeValue("goforgeDirectory", directory);
        target = element.getAttributeValue("goforgeTarget", ".");
        goArguments = element.getAttributeValue("goforgeArguments", "");
        programArguments = element.getAttributeValue("goforgeProgramArguments", "");
        environment.clear();
        Element env = element.getChild("goforge-environment");
        if (env != null) for (Element entry : env.getChildren("entry")) {
            String name = entry.getAttributeValue("name"), value = entry.getAttributeValue("value");
            if (name != null && value != null) environment.put(name, value);
        }
    }
}
