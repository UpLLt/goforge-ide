// Copyright 2026 GoForge contributors. Licensed under the Apache License, Version 2.0.
package dev.goforge.platform;

import com.intellij.ide.plugins.ContentModuleDescriptor;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.updateSettings.impl.UpdateChecker;
import java.nio.file.FileVisitResult;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Keeps application-essential plugins and their nonoptional dependency closure usable. */
public final class EssentialPluginToggleGuard {
  private EssentialPluginToggleGuard() {}

  public static Stream<? extends IdeaPluginDescriptor> toggleable(Stream<? extends IdeaPluginDescriptor> plugins) {
    Set<PluginId> protectedIds = protectedPluginIds();
    return plugins.filter(plugin -> !protectedIds.contains(plugin.getPluginId()));
  }

  /** Evaluated when clicked, including descriptors cached before the provider was filtered. */
  public static boolean canDisable(Collection<? extends IdeaPluginDescriptor> plugins) {
    if (plugins.isEmpty()) return true;
    Set<PluginId> protectedIds = protectedPluginIds();
    for (IdeaPluginDescriptor plugin : plugins) {
      if (protectedIds.contains(plugin.getPluginId())) {
        // Normal user feedback, not an assertion/exception or a restart request.
        var application = ApplicationManager.getApplication();
        if (application != null && !application.isDisposed() && !application.isHeadlessEnvironment()) {
          UpdateChecker.getNotificationGroupForPluginUpdateResults()
            .createNotification("This plugin is required by the application and cannot be disabled.", NotificationType.WARNING)
            .setTitle("Required application plugin")
            .setDisplayId("goforge.required.plugin")
            .notify(null);
        }
        return false; // Reject the whole request, without partially disabling its ordinary plugins.
      }
    }
    return true;
  }

  public static Set<PluginId> protectedPluginIds() {
    ApplicationInfoEx info = ApplicationInfoEx.getInstanceEx();
    Map<PluginId, IdeaPluginDescriptorImpl> plugins = PluginManagerCore.INSTANCE.buildPluginIdMap();
    Map<String, ContentModuleDescriptor> modules = PluginManagerCore.getPluginSet().buildContentModuleIdMap();
    Set<PluginId> result = new HashSet<>(info.getEssentialPluginIds());
    result.add(PluginManagerCore.CORE_ID);
    for (IdeaPluginDescriptorImpl plugin : new HashSet<>(plugins.values())) {
      if (!info.isEssentialPlugin(plugin.getPluginId())) continue;
      result.add(plugin.getPluginId());
      PluginManagerCore.INSTANCE.processAllNonOptionalDependencies(plugin, plugins, modules, dependency -> {
        result.add(dependency.getPluginId());
        return FileVisitResult.CONTINUE;
      });
    }
    return result;
  }
}
