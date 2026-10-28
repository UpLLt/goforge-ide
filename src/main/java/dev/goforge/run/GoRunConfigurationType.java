package dev.goforge.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class GoRunConfigurationType extends ConfigurationTypeBase {
    public GoRunConfigurationType() {
        super("GoForgeGo", "GoForge Go", "Run, test, build, or vet a Go package", AllIcons.RunConfigurations.Application);
        addFactory(new ConfigurationFactory(this) {
            @Override public @NotNull String getId() { return "GoForgeGoFactory"; }
            @Override public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
                return new GoRunConfiguration(project, this, "Go");
            }
        });
    }
}
