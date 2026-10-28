package dev.goforge.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class GoRunConfigurationProducer extends LazyRunConfigurationProducer<GoRunConfiguration> {
    @Override public @NotNull ConfigurationFactory getConfigurationFactory() {
        return ConfigurationTypeUtil.findConfigurationType(GoRunConfigurationType.class).getConfigurationFactories()[0];
    }
    @Override protected boolean setupConfigurationFromContext(@NotNull GoRunConfiguration configuration,
            @NotNull ConfigurationContext context, @NotNull Ref<PsiElement> sourceElement) {
        var location = context.getPsiLocation();
        if (location == null || location.getContainingFile() == null) return false;
        var file = location.getContainingFile().getVirtualFile();
        if (file == null || !"go".equals(file.getExtension()) || file.getParent() == null) return false;
        if (!file.getName().endsWith("_test.go")
                && !"main".equals(GoSourcePackage.name(location.getContainingFile().getText()))) return false;
        configuration.setCommand(file.getName().endsWith("_test.go") ? GoCommand.TEST : GoCommand.RUN);
        configuration.setDirectory(file.getParent().getPath());
        configuration.setTarget(".");
        configuration.setName(configuration.getCommand().argument() + " " + file.getParent().getName());
        sourceElement.set(location.getContainingFile());
        return true;
    }
    @Override public boolean isConfigurationFromContext(@NotNull GoRunConfiguration configuration, @NotNull ConfigurationContext context) {
        var location = context.getPsiLocation();
        if (location == null || location.getContainingFile() == null) return false;
        var file = location.getContainingFile().getVirtualFile();
        if (file == null || file.getParent() == null || !"go".equals(file.getExtension())) return false;
        if (!file.getName().endsWith("_test.go")
                && !"main".equals(GoSourcePackage.name(location.getContainingFile().getText()))) return false;
        GoCommand command = file.getName().endsWith("_test.go") ? GoCommand.TEST : GoCommand.RUN;
        return configuration.getCommand() == command && configuration.getTarget().equals(".")
            && configuration.getDirectory().equals(file.getParent().getPath());
    }
}
