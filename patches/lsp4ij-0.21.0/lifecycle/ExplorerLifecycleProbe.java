// Copyright 2026 GoForge contributors. Licensed under Apache-2.0.
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.tree.ui.DefaultTreeLayoutCache;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.ServerStatus;
import com.redhat.devtools.lsp4ij.console.explorer.*;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import kotlin.Unit;
import javax.swing.SwingUtilities;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual dependency listener/nodes and platform cache; no IDE/server/process is launched. */
public final class ExplorerLifecycleProbe {
    static final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
    static int checks;
    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    // Only test adapters bypass their heavyweight UI/process constructors. The real listener,
    // process node, inherited server-node operations, model and layout cache execute unchanged.
    static <T> T adapter(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    static final class Wrapper extends LanguageServerWrapper {
        ServerStatus status = ServerStatus.starting;
        int traces;
        private Wrapper() { super(null, null); }
        public LanguageServerDefinition getServerDefinition() { return null; }
        public boolean isEnabled() { return false; }
        public ServerStatus getServerStatus() { return status; }
        public boolean addTrace(Message message, MessageConsumer consumer) { traces++; return false; }
    }
    static final class ServerNode extends LanguageServerTreeNode {
        final CyclicBarrier firstRead = new CyclicBarrier(2);
        ServerNode() { super(null); }
        public LanguageServerProcessTreeNode getActiveProcessTreeNode() {
            var value = super.getActiveProcessTreeNode();
            if (!SwingUtilities.isEventDispatchThread() && value == null) {
                try { firstRead.await(5, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError(error); }
            }
            return value;
        }
    }
    static final class Explorer extends LanguageServerExplorer {
        ServerNode server;
        DefaultTreeModel model;
        DefaultTreeLayoutCache cache;
        List<String> events;
        AtomicInteger offEdt;
        boolean projectDisposed;
        int errors;
        int renderedTraces;
        private Explorer() { super(null); }
        void initialize() {
            server = new ServerNode(); model = new DefaultTreeModel(server);
            events = new ArrayList<>(); offEdt = new AtomicInteger();
            cache = new DefaultTreeLayoutCache(20, path -> Unit.INSTANCE);
            cache.setRootVisible(true); cache.setModel(model);
            cache.setExpandedState(new TreePath(server), true);
            model.addTreeModelListener(new TreeModelListener() {
                void event(String kind) { onModelAccess(); events.add(kind); }
                public void treeNodesChanged(TreeModelEvent e) { event("changed"); cache.treeNodesChanged(e); }
                public void treeNodesInserted(TreeModelEvent e) { event("inserted"); cache.treeNodesInserted(e); }
                public void treeNodesRemoved(TreeModelEvent e) { event("removed"); cache.treeNodesRemoved(e); }
                public void treeStructureChanged(TreeModelEvent e) { event("structure"); cache.treeStructureChanged(e); }
            });
        }
        void onModelAccess() { if (!SwingUtilities.isEventDispatchThread()) offEdt.incrementAndGet(); }
        public LanguageServerTreeNode findNodeForServer(LanguageServerDefinition definition) { onModelAccess(); return server; }
        public DefaultTreeModel getTreeModel() { onModelAccess(); return model; }
        public boolean isDisposed() { return projectDisposed; }
        public boolean isEditingCommand(LanguageServerTreeNode node) { return false; }
        public void selectAndExpand(DefaultMutableTreeNode node) {
            onModelAccess(); cache.setExpandedState(new TreePath(server), true);
        }
        public void showError(LanguageServerProcessTreeNode node, Throwable error) { onModelAccess(); errors++; }
        public void showTrace(LanguageServerProcessTreeNode node, String message) { onModelAccess(); renderedTraces++; }
    }
    static Explorer explorer() throws Exception {
        Explorer value = adapter(Explorer.class);
        SwingUtilities.invokeAndWait(value::initialize);
        return value;
    }
    static Wrapper wrapper() throws Exception {
        Wrapper value = adapter(Wrapper.class); value.status = ServerStatus.starting; return value;
    }
    static void drain() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (Runnable task; (task = queued.poll()) != null;) task.run(); });
    }
    static void background(Runnable task) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try { executor.submit(task).get(10, TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
    }
    static Runnable publication(LanguageServerExplorerLifecycleListener listener, String method,
                                LanguageServerProcessTreeNode node, Object value) throws Exception {
        var target = LanguageServerExplorerLifecycleListener.class.getDeclaredMethod(method,
                LanguageServerProcessTreeNode.class, value instanceof String ? String.class : Throwable.class);
        target.setAccessible(true);
        return () -> {
            try { target.invoke(listener, node, value); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        };
    }
    static void cacheContract() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var root = new DefaultMutableTreeNode("server");
            for (int i = 0; i < 4; i++) root.add(new DefaultMutableTreeNode(i));
            var model = new DefaultTreeModel(root);
            var cache = new DefaultTreeLayoutCache(20, path -> Unit.INSTANCE);
            cache.setModel(model); cache.setExpandedState(new TreePath(root), true);
            var fifth = new DefaultMutableTreeNode("unannounced"); root.add(fifth);
            try {
                cache.treeNodesChanged(new TreeModelEvent(model, new Object[]{root}, new int[]{4}, new Object[]{fifth}));
                throw new AssertionError("Missing insertion was not reproduced");
            } catch (IndexOutOfBoundsException expected) {
                check(expected.getMessage().equals("Index 4 out of bounds for length 4"), "exact native cache exception");
            }
        });
    }
    public static void main(String[] arguments) throws Exception {
        Application app = (Application) Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[]{Application.class},
                (proxy, method, values) -> {
                    return switch (method.getName()) {
                        case "isDispatchThread" -> SwingUtilities.isEventDispatchThread();
                        case "invokeLater" -> { queued.add((Runnable) values[0]); yield null; }
                        case "isDisposed", "isDisposeInProgress" -> false;
                        case "isHeadlessEnvironment", "isUnitTestMode" -> true;
                        case "toString" -> "Explorer lifecycle probe application";
                        default -> throw new AssertionError("Unexpected Application method: " + method);
                    };
                });
        ApplicationManager.setApplication(app);
        cacheContract();
        Explorer explorer = explorer(); Wrapper wrapper = wrapper();
        var listener = new LanguageServerExplorerLifecycleListener(explorer);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> listener.handleStatusChanged(wrapper));
            var second = workers.submit(() -> listener.handleStatusChanged(wrapper));
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        System.out.println("before EDT: offEdt=" + explorer.offEdt + ", children=" + explorer.server.getChildCount());
        check(explorer.offEdt.get() == 0, "Background model lookup/creation before EDT dispatch");
        check(explorer.server.getChildCount() == 0, "No background node mutation");
        drain();
        check(explorer.server.getChildCount() == 1, "Concurrent first statuses must create only one process node");
        check(explorer.events.equals(List.of("inserted", "changed")), "Insertion must precede status change: " + explorer.events);
        check(explorer.server.getActiveProcessTreeNode().getServerStatus() == ServerStatus.starting, "Starting status retained");
        wrapper.status = ServerStatus.started;
        background(() -> listener.handleStatusChanged(wrapper)); drain();
        check(explorer.server.getChildCount() == 1, "Existing process reused");
        check(explorer.server.getActiveProcessTreeNode().getServerStatus() == ServerStatus.started, "Started status retained");
        background(() -> listener.handleLSPMessage(new NotificationMessage(), ignored -> {}, wrapper)); drain();
        check(wrapper.traces == 1, "Message trace path retained");
        background(() -> listener.handleError(wrapper, new IllegalStateException("fixture"))); drain();
        check(explorer.errors == 1 && explorer.offEdt.get() == 0, "Error display stays on EDT");
        for (boolean projectClose : new boolean[]{false, true}) {
            Explorer late = explorer(); Wrapper lateWrapper = wrapper();
            var lateListener = new LanguageServerExplorerLifecycleListener(late);
            background(() -> lateListener.handleStatusChanged(lateWrapper));
            if (projectClose) late.projectDisposed = true; else lateListener.dispose();
            drain();
            check(late.server.getChildCount() == 0 && late.events.isEmpty(), "Disposed listener/project discards queued mutation");
        }
        // Fix the precise after-background-flush/before-EDT-publication boundary.
        // Invoke the actual private publication methods; no alarm timing or sleep is assumed.
        var node = explorer.server.getActiveProcessTreeNode();
        queued.add(publication(listener, "showTrace", node, "generated fixture trace")); drain();
        check(explorer.renderedTraces == 1, "Normal trace publication retained");
        queued.add(publication(listener, "showTrace", node, "discarded fixture trace"));
        queued.add(publication(listener, "showError", node, new IllegalStateException("discarded")));
        listener.dispose(); drain();
        check(explorer.renderedTraces == 1 && explorer.errors == 1 && !explorer.projectDisposed,
                "Disposed listener must not publish queued traces/errors into a still-live explorer");
        check(queued.isEmpty(), "No pending UI work");
        System.out.println("lifecycle_actual_class_checks=" + checks);
    }
}
