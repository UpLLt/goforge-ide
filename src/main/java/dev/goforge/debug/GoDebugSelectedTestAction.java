package dev.goforge.debug;

import com.google.gson.Gson;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.execution.ParametersListUtil;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationProvider;
import dev.goforge.run.GoRunConfiguration;
import dev.goforge.settings.GoProjectSettings;
import dev.goforge.testing.GoTestConsoleProperties;
import dev.goforge.testing.GoTestPackageIndex;
import dev.goforge.testing.GoTestSelector;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/** Debugs the actual completed SM node using its mapped package and observed full name. */
public final class GoDebugSelectedTestAction extends DumbAwareAction {
    private static final Logger LOG = Logger.getInstance(GoDebugSelectedTestAction.class);
    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private final Supplier<GoRunConfiguration> selection;
    private final Preparation preparation;
    private final Consumer<RunnerAndConfigurationSettings> launcher;
    private final Consumer<String> stageObserver;

    @FunctionalInterface
    interface Preparation {
        Prepared prepare(GoRunConfiguration configuration, ProgressIndicator indicator,
                         Consumer<String> progress) throws Exception;
    }

    record Prepared(String directory, Map<String, Object> launch, GoSelectedTestDebugGuard guard) {
        Prepared {
            Objects.requireNonNull(directory);
            launch = Map.copyOf(launch);
            Objects.requireNonNull(guard);
        }
    }

    public GoDebugSelectedTestAction() {
        this(null, GoDebugSelectedTestAction::prepare,
                settings -> ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance()), ignored -> {});
    }

    /** Tests replace only external discovery and final dispatch; the action, Task and RunManager remain real. */
    GoDebugSelectedTestAction(Preparation preparation, Consumer<RunnerAndConfigurationSettings> launcher,
                              Consumer<String> stageObserver) {
        this(null, preparation, launcher, stageObserver);
    }

    private GoDebugSelectedTestAction(Supplier<GoRunConfiguration> selection, Preparation preparation,
                                     Consumer<RunnerAndConfigurationSettings> launcher, Consumer<String> stageObserver) {
        super("Debug selected Go test/subtree", "Debug the selected observed Test/Example and its subtree once",
                AllIcons.Actions.StartDebugger);
        this.selection = selection;
        this.preparation = Objects.requireNonNull(preparation);
        this.launcher = Objects.requireNonNull(launcher);
        this.stageObserver = Objects.requireNonNull(stageObserver);
    }
    /** Returns a per-console action; the supplier reads an EDT-captured immutable selection. */
    public GoDebugSelectedTestAction withSelection(Supplier<GoRunConfiguration> selection) {
        return new GoDebugSelectedTestAction(Objects.requireNonNull(selection), preparation, launcher, stageObserver);
    }
    private GoRunConfiguration selectedConfiguration(AnActionEvent event) {
        return selection == null ? GoTestConsoleProperties.selectedConfiguration(event) : selection.get();
    }

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setDescription(getTemplatePresentation().getDescription());
        try {
            var config = selectedConfiguration(event);
            event.getPresentation().setEnabled(config != null);
        } catch (IllegalArgumentException | IllegalStateException error) {
            event.getPresentation().setEnabled(false);
            event.getPresentation().setDescription(error.getMessage());
        }
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        final long attempt = ATTEMPTS.incrementAndGet();
        stage(attempt, "clicked");
        final GoRunConfiguration config;
        try { config = selectedConfiguration(event); }
        catch (IllegalArgumentException | IllegalStateException error) {
            stage(attempt, "selection_failed type=" + error.getClass().getSimpleName());
            Messages.showErrorDialog(event.getProject(), error.getMessage(), "Go Test Debug");
            return;
        }
        stage(attempt, "selectionPresent=" + (config != null));
        if (config == null) return;
        var project = config.getProject();
        stage(attempt, "prepare_queued");
        new Task.Backgroundable(project, "Prepare selected Go test debug", true) {
            private Prepared prepared;

            @Override public void run(@NotNull ProgressIndicator indicator) {
                stage(attempt, "prepare_start");
                try {
                    prepared = Objects.requireNonNull(preparation.prepare(config, indicator,
                            progress -> stage(attempt, progress)), "Selected test debug preparation did not finish.");
                    indicator.checkCanceled();
                    stage(attempt, "prepare_end");
                } catch (java.util.concurrent.CancellationException cancelled) {
                    stage(attempt, "prepare_cancelled");
                    throw new com.intellij.openapi.progress.ProcessCanceledException();
                } catch (RuntimeException error) {
                    stage(attempt, "prepare_failure type=" + error.getClass().getSimpleName());
                    throw error;
                } catch (Exception error) {
                    stage(attempt, "prepare_failure type=" + error.getClass().getSimpleName());
                    throw new RuntimeException(error.getMessage(), error);
                }
            }

            @Override public void onSuccess() {
                stage(attempt, "onSuccess projectDisposed=" + project.isDisposed() + " launchPresent=" + (prepared != null));
                if (project.isDisposed()) return;
                try {
                    if (prepared == null) throw new IllegalStateException("Selected test debug preparation did not finish. Select Debug again.");
                    prepared.guard().validate();
                    var settings = createConfiguration(config, prepared.directory(), prepared.launch());
                    stage(attempt, "configuration_created");
                    prepared.guard().bind((DAPRunConfiguration) settings.getConfiguration());
                    var manager = RunManager.getInstance(project);
                    manager.setTemporaryConfiguration(settings);
                    manager.setSelectedConfiguration(settings);
                    stage(attempt, "configuration_selected");
                    launcher.accept(settings);
                    stage(attempt, "launch_dispatched");
                } catch (Exception error) {
                    stage(attempt, "launch_failure type=" + error.getClass().getSimpleName());
                    Messages.showErrorDialog(project, error.getMessage(), "Go Test Debug");
                }
            }

            @Override public void onCancel() {
                stage(attempt, "onCancel");
            }

            @Override public void onThrowable(@NotNull Throwable error) {
                stage(attempt, "onThrowable type=" + error.getClass().getSimpleName());
                if (!project.isDisposed()) Messages.showErrorDialog(project, error.getMessage(), "Go Test Debug");
            }
        }.queue();
    }

    private static Prepared prepare(GoRunConfiguration config, ProgressIndicator indicator,
                                    Consumer<String> progress) throws Exception {
        config.checkConfiguration();
        progress.accept("configuration_checked");
        indicator.checkCanceled();
        var project = config.getProject();
        var sources = GoTestPackageIndex.prepare(config, () -> indicator.isCanceled() || project.isDisposed());
        progress.accept("packages_prepared");
        var guard = GoSelectedTestDebugGuard.capture(project, sources);
        progress.accept("guard_captured");
        indicator.checkCanceled();
        String directory = Path.of(config.getDirectory()).toRealPath().toString();
        var arguments = new ArrayList<>(GoTestSelector.splitGoFlags(GoTestPackageIndex.preparedGoFlags(config)));
        arguments.addAll(GoProjectSettings.getInstance(project).buildFlags());
        arguments.addAll(ParametersListUtil.parse(config.getGoArguments()));
        var launch = GoSelectedTestDebugPlan.launch(directory, config.getTestSelection(), arguments);
        return new Prepared(directory, launch, guard);
    }

    /** Stage-only diagnostics: never include selected names, paths, arguments, environment or exception text. */
    private void stage(long attempt, String stage) {
        LOG.info("selected-test-debug attempt=" + attempt + " stage=" + stage);
        stageObserver.accept(stage);
    }

    public static RunnerAndConfigurationSettings createConfiguration(
            GoRunConfiguration source, String canonicalDirectory, Map<String, Object> launch) {
        var manager = RunManager.getInstance(source.getProject());
        var factory = new DAPRunConfigurationProvider().getConfigurationFactory();
        var settings = manager.createConfiguration((String) launch.get("name"), factory);
        var config = (DAPRunConfiguration) settings.getConfiguration();
        config.setServerId("goforge.delve");
        config.setServerName("GoForge — Delve");
        config.setDebugMode(DebugMode.LAUNCH);
        config.setWorkingDirectory(canonicalDirectory);
        config.setEnvData(EnvironmentVariablesData.create(Map.copyOf(source.getEnvironment()), true));
        config.setLaunchConfigurationId("goforge_selected_test");
        config.setLaunchConfiguration(new Gson().toJson(launch));
        return settings;
    }
}
