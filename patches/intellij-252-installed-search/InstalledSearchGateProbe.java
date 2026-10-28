// Copyright 2026 GoForge contributors. Licensed under the Apache License, Version 2.0.
import dev.goforge.platform.InstalledSearchGate;
import javax.swing.SwingUtilities;
import java.util.Objects;
import com.intellij.ide.plugins.newui.EventHandler;
import com.intellij.ide.plugins.newui.PluginsGroupComponent;
import com.intellij.ide.plugins.newui.ListPluginComponent;
import com.intellij.ide.plugins.newui.PluginUiModel;
import com.intellij.ide.plugins.newui.PluginsGroup;
import com.intellij.ide.plugins.newui.SearchResultPanel;
import com.intellij.openapi.util.text.HtmlChunk;
import java.util.List;
import java.util.function.Consumer;

/** Executable lifecycle checks, separate from full platform/native UI acceptance. */
public final class InstalledSearchGateProbe {
  static int checks;
  static void check(boolean value) { if (!value) throw new AssertionError("check " + (checks + 1)); checks++; }
  public static void main(String[] args) throws Exception {
    if (!ClassLoader.getSystemClassLoader().getClass().getName().equals("com.intellij.util.lang.PathClassLoader")) {
      throw new AssertionError("Must exercise the real platform PathClassLoader");
    }
    SwingUtilities.invokeAndWait(() -> {
      InstalledSearchGate gate = new InstalledSearchGate();
      check(gate.defer("first"));
      check(Objects.equals("first", gate.pendingQuery()));
      check(!gate.isReady());
      check(gate.defer("last"));
      check(Objects.equals("last", gate.pendingQuery()));
      check(Objects.equals("last", gate.initialized()));
      check(gate.isReady());
      check(gate.pendingQuery() == null);
      check(!gate.defer("last"));
      check(gate.initialized() == null);
      InstalledSearchGate cleared = new InstalledSearchGate();
      check(cleared.defer("pending"));
      check(cleared.defer(""));
      check(Objects.equals("", cleared.pendingQuery()));
      check(Objects.equals("", cleared.initialized()));
      InstalledSearchGate disposed = new InstalledSearchGate();
      check(disposed.defer("pending"));
      disposed.dispose();
      check(disposed.initialized() == null);
      check(disposed.defer("late"));
      check(disposed.pendingQuery() == null);
      check(!disposed.isReady());
      check(gate.isReady()); // An unrelated settings instance is not invalidated.
      gate.dispose();
      check(!gate.isReady());
      check(gate.defer("after ready then disposed"));
      InstalledSearchGate noQuery = new InstalledSearchGate();
      check(noQuery.initialized() == null);
      check(!noQuery.defer("normal"));
    });
    try {
      new InstalledSearchGate().defer("wrong thread");
      throw new AssertionError("Worker access was accepted");
    } catch (IllegalStateException expected) { checks++; }
    // Load/verify the actual transformed platform classes, not substitute stubs.
    for (String name : new String[]{"com.intellij.ide.plugins.PluginManagerConfigurable$13",
        "com.intellij.ide.plugins.PluginManagerConfigurable$13$6",
        "com.intellij.ide.plugins.PluginManagerConfigurable$13$3"}) {
      Class<?> type = Class.forName(name, false, InstalledSearchGateProbe.class.getClassLoader());
      check(type.getDeclaredMethods().length > 0);
    }
    SwingUtilities.invokeAndWait(() -> {
      try {
        var component = new PluginsGroupComponent(new EventHandler() {}) {
          protected ListPluginComponent createListComponent(PluginUiModel model, PluginsGroup group, List<HtmlChunk> errors) {
            throw new AssertionError("No initialized model is available in this pending-query test");
          }
        };
        var type = Class.forName("com.intellij.ide.plugins.PluginManagerConfigurable$13$6");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        SearchResultPanel panel = (SearchResultPanel) constructor.newInstance(null, null, component, false, 0, 0, (Consumer<Object>) ignored -> {});
        panel.setQuery("first");
        check(!panel.isEmpty());
        check(panel.getQuery().equals("first"));
        panel.setQuery("last");
        check(panel.getQuery().equals("last"));
        check(component.getEmptyText().getText().contains("Loading installed plugins"));
        // Mirrors PluginsTab.hideSearchPanel's conditional clear (the original bug boundary).
        if (!panel.isEmpty()) panel.setQuery("");
        check(panel.isEmpty());
        check(panel.getQuery().isEmpty());
        panel.setQuery("discarded by reset");
        panel.setEmpty();
        check(panel.isEmpty());
        var initialized = type.getDeclaredMethod("goforgeModelInitialized");
        initialized.setAccessible(true);
        initialized.invoke(panel); // Empty query must not touch the absent model.
        check(panel.getQuery().isEmpty());
        panel.dispose();
        panel.setQuery("late after disposal");
        initialized.invoke(panel);
        check(panel.getQuery().isEmpty());
      } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    });
    System.out.println("gate_and_real_class_checks=" + checks);
  }
}
