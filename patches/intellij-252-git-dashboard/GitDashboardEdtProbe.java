// Copyright 2026 GoForge contributors. Licensed under the Apache License, Version 2.0.
import com.intellij.openapi.application.*;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.application.impl.PlatformCoroutineSupport;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.platform.locking.impl.IntelliJLockingUtil;
import com.intellij.platform.kernel.KernelService;
import com.intellij.platform.kernel.util.KernelUtilsKt;
import com.intellij.util.ui.UiScopeKt;
import com.intellij.util.ui.tree.TreeUtil;
import dev.goforge.platform.GitDashboardEdt;
import java.awt.event.HierarchyEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import kotlin.Unit;
import kotlin.coroutines.*;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.*;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.tree.*;

/** Real platform dispatchers, lock policy, tree expansion, and show/hide jobs.
 * Only Application service lookup and OS event delivery use a headless fixture.
 * This is not a full native Git tool-window test.
 */
public final class GitDashboardEdtProbe {
  static final List<String> checks = new ArrayList<>();
  static final AtomicReference<Throwable> unexpected = new AtomicReference<>();
  static boolean patched;
  static final CompletableDeferred<CoroutineScope> kernelScope = CompletableDeferredKt.CompletableDeferred((Job) null);
  static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    checks.add(label);
  }
  static void await(BooleanSupplier condition, String label) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.getAsBoolean() && unexpected.get() == null && System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(() -> {});
      Thread.sleep(10);
    }
    if (unexpected.get() != null) throw new AssertionError(label, unexpected.get());
    check(condition.getAsBoolean(), label);
  }
  static final class ShowingPanel extends JPanel {
    boolean showing;
    @Override public boolean isShowing() { return showing; }
    void showForProbe(boolean value) {
      showing = value;
      dispatchEvent(new HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, null, HierarchyEvent.SHOWING_CHANGED));
    }
  }
  static Object computation(Object value) {
    try {
      if (value instanceof Runnable runnable) { runnable.run(); return null; }
      if (value instanceof Computable<?> computable) return computable.compute();
      if (value instanceof ThrowableComputable<?, ?> computable) return computable.compute();
      throw new AssertionError("Unexpected computation " + value.getClass());
    } catch (Throwable failure) { return GitDashboardEdtProbe.<RuntimeException, Object>rethrow(failure); }
  }
  @SuppressWarnings("unchecked") static <T extends Throwable, R> R rethrow(Throwable failure) throws T { throw (T) failure; }

  static void applicationFixture() {
    var locks = IntelliJLockingUtil.getGlobalNestedLockingThreadingSupport();
    var support = new PlatformCoroutineSupport();
    ApplicationEx app = (ApplicationEx) Proxy.newProxyInstance(ApplicationEx.class.getClassLoader(), new Class<?>[]{ApplicationEx.class}, (proxy, method, args) -> {
      return switch (method.getName()) {
        case "getService" -> {
          if (args[0] == CoroutineSupport.class) yield support;
          if (args[0] == ThreadingSupport.class) yield locks;
          if (args[0] == KernelService.class) yield (KernelService) () -> kernelScope;
          throw new AssertionError("Unexpected service " + args[0]);
        }
        case "runReadAction" -> locks.runReadAction(GitDashboardEdtProbe.class, () -> computation(args[0]));
        case "runWriteIntentReadAction" -> locks.runWriteIntentReadAction(() -> computation(args[0]));
        case "prohibitTakingLocksInsideAndRun" -> { locks.prohibitTakingLocksInsideAndRun((Runnable) args[0], (boolean) args[1], (String) args[2]); yield null; }
        case "allowTakingLocksInsideAndRun" -> { locks.allowTakingLocksInsideAndRun((Runnable) args[0]); yield null; }
        case "dispatchCoroutineOnEDT", "invokeLater" -> { SwingUtilities.invokeLater((Runnable) args[0]); yield null; }
        case "getModalityStateForComponent", "getCurrentModalityState", "getDefaultModalityState", "getNoneModalityState", "getAnyModalityState" -> ModalityState.NON_MODAL;
        case "isReadAccessAllowed", "holdsReadLock" -> locks.isReadAccessAllowed();
        case "isWriteAccessAllowed" -> locks.isWriteAccessAllowed();
        case "isWriteIntentLockAcquired" -> locks.isWriteIntentLocked();
        case "isLockingProhibited" -> locks.getLockingProhibitedAdvice();
        case "isParallelizedReadAction" -> locks.isParallelizedReadAction((CoroutineContext) args[0]);
        case "isTopmostReadAccessAllowed" -> locks.isInTopmostReadAction();
        case "isDispatchThread" -> SwingUtilities.isEventDispatchThread();
        case "assertIsDispatchThread" -> { if (!SwingUtilities.isEventDispatchThread()) throw new AssertionError("Must run on EDT"); yield null; }
        case "isHeadlessEnvironment", "isUnitTestMode", "isCommandLine", "isActive" -> true;
        case "isDisposed", "isDisposeInProgress", "isExitInProgress", "isInternal", "isEAP" -> false;
        case "toString" -> "Git dashboard isolated dispatcher/lock fixture";
        default -> throw new AssertionError("Unexpected Application call " + method);
      };
    });
    ApplicationManager.setApplication(app);
    Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> { unexpected.compareAndSet(null, failure); failure.printStackTrace(System.err); });
  }

  static boolean inspectHook() throws Exception {
    String path = "git4idea/ui/branch/dashboard/FilteringBranchesTree.class";
    ClassNode node = new ClassNode();
    try (var stream = ClassLoader.getSystemResourceAsStream(path)) {
      if (stream == null) throw new AssertionError("Missing actual platform target class");
      new ClassReader(stream.readAllBytes()).accept(node, 0);
    }
    int hooks = 0, launches = 0;
    for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
      if (instruction instanceof MethodInsnNode call) {
        if (call.owner.equals("dev/goforge/platform/GitDashboardEdt")) {
          check(method.name.equals("<init>") && call.name.equals("wrap") && call.desc.equals("(Lkotlin/jvm/functions/Function2;)Lkotlin/jvm/functions/Function2;"), "exact wrapper ABI at constructor");
          hooks++;
        }
        if (call.owner.equals("com/intellij/util/ui/UiScopeKt") && call.name.equals("launchOnShow$default")) launches++;
      }
    }
    check(launches == 1 && hooks <= 1, "one original launchOnShow retained");
    // Select the exact callsite behavior from the actual platform bytes. The
    // same assertions run with the unmodified class first on the classpath.
    return hooks == 1;
  }
  static Function2<CoroutineScope, Continuation<? super Unit>, Object> actualCallsite(
      Function2<CoroutineScope, Continuation<? super Unit>, Object> block) {
    return patched ? GitDashboardEdt.wrap(block) : block;
  }

  static final class Lifecycle {
    final AtomicInteger starts = new AtomicInteger(), adds = new AtomicInteger(), removes = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final ShowingPanel component = new ShowingPanel();
    final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root"), local = new DefaultMutableTreeNode("Local");
    final JTree tree;
    Job job;
    int originalHierarchyListeners;
    Lifecycle() {
      root.add(local); local.add(new DefaultMutableTreeNode("main"));
      tree = new JTree(root); tree.collapseRow(1);
    }
    void install() {
      originalHierarchyListeners = component.getHierarchyListeners().length;
      Function2<CoroutineScope, Continuation<? super Unit>, Object> block = (scope, continuation) -> {
        starts.incrementAndGet();
        try {
          check(SwingUtilities.isEventDispatchThread(), "tree initialization on Swing EDT");
          TreeUtil.expandAll(tree);
          JobKt.ensureActive(scope.getCoroutineContext());
          adds.incrementAndGet();
          // The pinned original block similarly registers its model listener,
          // awaits cancellation, then removes the listener in finally.
          return DelayKt.awaitCancellation(new Continuation<Object>() {
            public CoroutineContext getContext() { return continuation.getContext(); }
            public void resumeWith(Object result) {
              removes.incrementAndGet();
              continuation.resumeWith(result);
            }
          });
        } catch (Throwable error) { failure.set(error); throw error; }
      };
      job = UiScopeKt.launchOnShow(component, "Git Dashboard Tree probe", EmptyCoroutineContext.INSTANCE, actualCallsite(block));
    }
  }

  static void verifyLifecycle() throws Exception {
    AtomicReference<Lifecycle> ref = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> { var state = new Lifecycle(); state.install(); ref.set(state); });
    Lifecycle state = ref.get();
    check(state.starts.get() == 0, "hidden component does not start");
    check(state.component.getHierarchyListeners().length > state.originalHierarchyListeners, "original lifecycle listener installed");
    SwingUtilities.invokeAndWait(() -> state.component.showForProbe(true));
    await(() -> state.adds.get() == 1 || state.failure.get() != null, "shown initialization reaches listener registration");
    if (state.failure.get() != null) {
      Throwable error = state.failure.get();
      error.printStackTrace(System.out);
      throw new AssertionError("TreeUtil.expandAll must allow its real read action", error);
    }
    SwingUtilities.invokeAndWait(() -> check(state.tree.isExpanded(1), "Local branch actually expands"));
    check(state.job.isActive() && state.removes.get() == 0, "suspended block remains attached to show lifecycle");
    SwingUtilities.invokeAndWait(() -> state.component.showForProbe(false));
    await(() -> state.removes.get() == 1, "hide cancels child and removes listener once");
    check(state.job.isActive(), "hide retains outer show observer");
    SwingUtilities.invokeAndWait(() -> state.component.showForProbe(true));
    await(() -> state.adds.get() == 2, "re-show starts a new child");
    check(state.removes.get() == 1, "re-show retains exactly one active listener");
    SwingUtilities.invokeAndWait(() -> state.job.cancel(null));
    await(() -> state.job.isCompleted() && state.removes.get() == 2, "outer cancellation propagates and cleans suspended child");
    await(() -> state.component.getHierarchyListeners().length == state.originalHierarchyListeners, "outer cancellation removes original hierarchy listener");
    SwingUtilities.invokeAndWait(() -> { state.component.showForProbe(false); state.component.showForProbe(true); });
    check(state.starts.get() == 2 && state.adds.get() == state.removes.get(), "disposed observer cannot restart or leak listener");
    AtomicReference<Lifecycle> canceledBeforeShow = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> {
      Lifecycle hidden = new Lifecycle(); hidden.install(); hidden.job.cancel(null); hidden.component.showForProbe(true);
      canceledBeforeShow.set(hidden);
    });
    Lifecycle hidden = canceledBeforeShow.get();
    await(hidden.job::isCompleted, "hidden observer cancellation completes");
    check(hidden.starts.get() == 0 && hidden.adds.get() == 0 && hidden.removes.get() == 0,
        "canceled before show never registers a model listener");
    check(hidden.component.getHierarchyListeners().length == hidden.originalHierarchyListeners,
        "canceled hidden observer removes hierarchy listener");
    RuntimeException sentinel = new RuntimeException("intentional block failure");
    try {
      BuildersKt.runBlocking(CoroutinesKt.getUI(Dispatchers.INSTANCE), actualCallsite((scope, continuation) -> { throw sentinel; }));
      throw new AssertionError("Block failure was swallowed");
    } catch (RuntimeException failure) {
      check(failure == sentinel, "block errors propagate unchanged");
    }
  }

  public static void main(String[] args) throws Exception {
    if (!ClassLoader.getSystemClassLoader().getClass().getName().equals("com.intellij.util.lang.PathClassLoader")) throw new AssertionError("Real platform loader required");
    applicationFixture();
    Job kernel = BuildersKt.launch(CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault()), EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
        (scope, continuation) -> KernelUtilsKt.withKernel((change, operation) -> operation.invoke(change), (readyScope, suspended) -> {
          kernelScope.complete(readyScope);
          return DelayKt.awaitCancellation(suspended);
        }, continuation));
    try {
      await(kernelScope::isCompleted, "real isolated kernel starts");
      patched = inspectHook();
      System.out.println("actual_patched_callsite=" + patched);
      verifyLifecycle();
      System.out.println("git_dashboard_real_dispatcher_lifecycle_checks=" + checks.size());
      checks.forEach(value -> System.out.println("PASS " + value));
    } finally {
      kernel.cancel(null);
      CountDownLatch completed = new CountDownLatch(1);
      kernel.invokeOnCompletion(failure -> { completed.countDown(); return Unit.INSTANCE; });
      if (!completed.await(8, TimeUnit.SECONDS)) throw new AssertionError("Kernel fixture did not stop");
    }
  }
}
