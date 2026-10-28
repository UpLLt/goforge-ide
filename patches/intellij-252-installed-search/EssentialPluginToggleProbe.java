// Copyright 2026 GoForge contributors. Licensed under the Apache License, Version 2.0.
import com.intellij.ide.plugins.*;
import com.intellij.ide.ui.PluginBooleanOptionDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.diagnostic.LoadingState;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.*;
import java.lang.reflect.Proxy;
import com.intellij.l10n.LocalizationStateService;
import com.intellij.platform.plugins.parser.impl.PluginDescriptorBuilder;
import com.intellij.platform.plugins.parser.impl.elements.DependsElement;
import dev.goforge.platform.EssentialPluginToggleGuard;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Uses public platform descriptors/graph and the actual transformed cached option. */
public final class EssentialPluginToggleProbe {
  static int checks;
  static void check(boolean value) { if (!value) throw new AssertionError("essential check " + (checks + 1)); checks++; }
  static PluginMainDescriptor plugin(String id, String required, String optional) {
    var builder = PluginDescriptorBuilder.Companion.builder();
    builder.setId(id); builder.setName(id); builder.setVersion("1.0");
    if (required != null) builder.addDepends(new DependsElement(required, false, null));
    if (optional != null) builder.addDepends(new DependsElement(optional, true, null));
    var plugin = new PluginMainDescriptor(builder.build(), Path.of("fixture", id), false, false);
    plugin.setEnabled(true);
    return plugin;
  }
  static final class RecordingEnabler implements PluginEnabler {
    int disabled, enabled;
    List<PluginId> last = List.of();
    public boolean isDisabled(PluginId id) { return false; }
    public boolean enable(Collection<? extends IdeaPluginDescriptor> values) {
      enabled++; last = values.stream().map(IdeaPluginDescriptor::getPluginId).toList(); return true;
    }
    public boolean disable(Collection<? extends IdeaPluginDescriptor> values) {
      disabled++; last = values.stream().map(IdeaPluginDescriptor::getPluginId).toList();
      System.out.println("DISABLE_REACHED " + last); return true;
    }
  }
  static final class UnusedActionManager extends ActionManager {
    private AssertionError unused() { return new AssertionError("ActionManager operation is outside this closure probe"); }
    public ActionPopupMenu createActionPopupMenu(String p, ActionGroup g) { throw unused(); }
    public ActionToolbar createActionToolbar(String p, ActionGroup g, boolean h) { throw unused(); }
    public AnAction getAction(String id) { throw unused(); }
    public String getId(AnAction action) { throw unused(); }
    public void registerAction(String id, AnAction action) { throw unused(); }
    public void registerAction(String id, AnAction action, PluginId plugin) { throw unused(); }
    public void unregisterAction(String id) { throw unused(); }
    public void replaceAction(String id, AnAction action) { throw unused(); }
    public String[] getActionIds(String prefix) { throw unused(); }
    public List<String> getActionIdList(String prefix) { throw unused(); }
    public boolean isGroup(String id) { throw unused(); }
    public AnAction getActionOrStub(String id) { throw unused(); }
    public void addTimerListener(com.intellij.openapi.actionSystem.TimerListener listener) { throw unused(); }
    public void removeTimerListener(com.intellij.openapi.actionSystem.TimerListener listener) { throw unused(); }
    public com.intellij.openapi.util.ActionCallback tryToExecute(AnAction a, java.awt.event.InputEvent e, java.awt.Component c, String p, boolean now) { throw unused(); }
    public KeyboardShortcut getKeyboardShortcut(String id) { throw unused(); }
  }
  public static void main(String[] args) throws Exception {
    if (!ClassLoader.getSystemClassLoader().getClass().getName().equals("com.intellij.util.lang.PathClassLoader")) throw new AssertionError("Real loader required");
    var leaf = plugin("fixture.required.leaf", null, null);
    var dependency = plugin("fixture.required", leaf.getPluginId().getIdString(), null);
    var optional = plugin("fixture.optional", null, null);
    var ordinary = plugin("fixture.ordinary", null, null);
    var core = plugin("com.intellij", dependency.getPluginId().getIdString(), optional.getPluginId().getIdString());
    var all = new LinkedHashSet<>(List.of(core, dependency, leaf, optional, ordinary));
    PluginManagerCore.setPluginSet(new PluginSetBuilder(all).createPluginSet$intellij_platform_core_impl(all));
    var info = ApplicationInfoImpl.getShadowInstance();
    var enabler = new RecordingEnabler();
    NotificationsManager notifications = new NotificationsManager() {
      public void showNotification(Notification value, com.intellij.openapi.project.Project project) { throw new AssertionError("No UI notification expected in the headless probe"); }
      public void expire(Notification value) {}
      public <T extends Notification> T[] getNotificationsOfType(Class<T> type, com.intellij.openapi.project.Project project) {
        @SuppressWarnings("unchecked") T[] empty = (T[]) java.lang.reflect.Array.newInstance(type, 0);
        return empty;
      }
    };
    LocalizationStateService localization = new LocalizationStateService() {
      public String getSelectedLocale() { return "en"; }
      public String getLastSelectedLocale() { return "en"; }
      public Boolean isRestartRequired() { return false; }
      public void setSelectedLocale(String value) {}
      public void setSelectedLocale(String value, Boolean restart) {}
      public void resetLocaleIfNeeded() {}
    };
    // A small public service-container fixture; real platform classes and graph,
    // while the terminal enable/disable operation is recorded, never unloading an IDE.
    Application application = (Application) Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[]{Application.class}, (proxy, method, values) -> {
      if (method.getName().equals("getService")) {
        if (values[0] == ApplicationInfo.class) return info;
        if (values[0] == PluginEnabler.class) return enabler;
        if (values[0] == LocalizationStateService.class) return localization;
        if (values[0] == NotificationsManager.class) return notifications;
        throw new AssertionError("Unexpected platform service " + values[0]);
      }
      if (method.getName().equals("isHeadlessEnvironment") || method.getName().equals("isUnitTestMode")) return true;
      if (method.getName().equals("isDisposed") || method.getName().equals("isDisposeInProgress")) return false;
      if (method.getName().equals("toString")) return "Essential toggle probe Application";
      throw new AssertionError("Unexpected Application call " + method);
    });
    ApplicationManager.setApplication(application);
    LoadingState.setCurrentState(LoadingState.COMPONENTS_LOADED);
    var protectedIds = EssentialPluginToggleGuard.protectedPluginIds();
    check(protectedIds.contains(core.getPluginId()));
    check(protectedIds.contains(dependency.getPluginId()));
    check(protectedIds.contains(leaf.getPluginId()));
    check(!protectedIds.contains(optional.getPluginId()));
    check(!protectedIds.contains(ordinary.getPluginId()));
    check(EssentialPluginToggleGuard.canDisable(List.of(ordinary, optional)));
    check(!EssentialPluginToggleGuard.canDisable(List.of(ordinary, core)));
    check(!EssentialPluginToggleGuard.canDisable(List.of(dependency)));
    check(!EssentialPluginToggleGuard.canDisable(List.of(leaf)));
    check(EssentialPluginToggleGuard.toggleable(all.stream()).map(IdeaPluginDescriptor::getPluginId).toList()
        .equals(List.of(optional.getPluginId(), ordinary.getPluginId())));

    // An already-created descriptor bypasses provider filtering but not execution.
    var constructor = PluginBooleanOptionDescriptor.class.getDeclaredConstructor(IdeaPluginDescriptor.class);
    constructor.setAccessible(true);
    var cachedCore = constructor.newInstance(core);
    cachedCore.setOptionState(false);
    check(enabler.disabled == 0);
    check(enabler.enabled == 0);
    PluginBooleanOptionDescriptor.togglePluginState(List.of(ordinary, leaf), false);
    check(enabler.disabled == 0);
    check(enabler.enabled == 0);
    // Empty collections remain a no-op.
    PluginBooleanOptionDescriptor.togglePluginState(List.of(), false);
    check(enabler.disabled == 0);
    var cachedOrdinary = constructor.newInstance(ordinary);
    cachedOrdinary.setOptionState(false);
    check(enabler.disabled == 1);
    check(enabler.last.equals(List.of(ordinary.getPluginId())));
    cachedOrdinary.setOptionState(true);
    check(enabler.enabled == 1);
    check(enabler.last.equals(List.of(ordinary.getPluginId())));

    var providerType = Class.forName("com.intellij.ide.ui.PluginOptionsTopHitProvider");
    var providerConstructor = providerType.getDeclaredConstructors()[0]; providerConstructor.setAccessible(true);
    var options = providerType.getDeclaredMethod("getOptions"); options.setAccessible(true);
    check(((Collection<?>) options.invoke(providerConstructor.newInstance())).size() == 2);

    // A notification action captured before filtering must also recheck at click time.
    var undoType = Class.forName("com.intellij.ide.ui.PluginBooleanOptionDescriptor$1");
    var undoConstructor = undoType.getDeclaredConstructors()[0]; undoConstructor.setAccessible(true);
    var action = undoType.getDeclaredMethod("actionPerformed", AnActionEvent.class, Notification.class); action.setAccessible(true);
    var event = new AnActionEvent(null, DataContext.EMPTY_CONTEXT, "probe", new Presentation(), new UnusedActionManager(), 0);
    var notice = new Notification("probe", "probe", NotificationType.INFORMATION);
    action.invoke(undoConstructor.newInstance("Undo", true, enabler, List.of(core)), event, notice);
    check(enabler.disabled == 1);
    check(notice.isExpired());
    action.invoke(undoConstructor.newInstance("Undo", true, enabler, List.of(ordinary)), event,
                  new Notification("probe", "probe", NotificationType.INFORMATION));
    check(enabler.disabled == 2);
    check(enabler.last.equals(List.of(ordinary.getPluginId())));
    action.invoke(undoConstructor.newInstance("Undo", false, enabler, List.of(core)), event,
                  new Notification("probe", "probe", NotificationType.INFORMATION));
    check(enabler.enabled == 2); // Core enable path remains available.
    System.out.println("essential_actual_platform_checks=" + checks);
  }
}
