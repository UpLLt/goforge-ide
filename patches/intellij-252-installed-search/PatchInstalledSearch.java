// Copyright 2026 GoForge contributors. Licensed under the Apache License, Version 2.0.
// Transforms only the separately SHA-256-pinned public Community 252.28539.97 classes.
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.tree.*;
import org.jetbrains.org.objectweb.asm.util.CheckClassAdapter;

public final class PatchInstalledSearch implements Opcodes {
  static final String OWNER = "com/intellij/ide/plugins/PluginManagerConfigurable";
  static final String TAB = OWNER + "$13";
  static final String PANEL = TAB + "$6";
  static final String POPUP = TAB + "$3";
  static final String BASE = "com/intellij/ide/plugins/newui/SearchResultPanel";
  static final String GATE = "dev/goforge/platform/InstalledSearchGate";
  static final String FIELD = "goforgeInstalledSearchGate";
  static final String CAPTURE = "goforgeCapturedSearchPanel";
  static final String PROVIDER = "com/intellij/ide/ui/PluginOptionsTopHitProvider";
  static final String OPTION = "com/intellij/ide/ui/PluginBooleanOptionDescriptor";
  static final String UNDO = OPTION + "$1";
  static final String ESSENTIAL = "dev/goforge/platform/EssentialPluginToggleGuard";

  public static void main(String[] args) throws Exception {
    Path input = Path.of(args[0]), output = Path.of(args[1]);
    for (String name : new String[]{TAB, PANEL, POPUP, PROVIDER, OPTION, UNDO}) {
      ClassNode node = new ClassNode();
      new ClassReader(Files.readAllBytes(input.resolve(name + ".class"))).accept(node, 0);
      if (name.equals(TAB)) patchTab(node);
      else if (name.equals(PANEL)) patchPanel(node);
      else if (name.equals(POPUP)) patchPopup(node);
      else patchToggle(node);
      verifyHooks(node);
      ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      node.accept(writer);
      byte[] bytes = writer.toByteArray();
      StringWriter errors = new StringWriter();
      CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(errors));
      if (!errors.toString().isBlank()) throw new IllegalStateException(errors.toString());
      Path target = output.resolve(name + ".class");
      Files.createDirectories(target.getParent());
      Files.write(target, bytes);
    }
  }

  static void patchToggle(ClassNode node) {
    if (node.name.equals(PROVIDER)) {
      MethodNode options = method(node, "getOptions", "()Ljava/util/Collection;");
      int hooks = 0;
      for (AbstractInsnNode instruction : options.instructions.toArray()) {
        if (instruction instanceof MethodInsnNode call && call.owner.equals("com/intellij/ide/plugins/PluginManager")
            && call.name.equals("getVisiblePlugins") && call.desc.equals("(Z)Ljava/util/stream/Stream;")) {
          options.instructions.insert(call, new MethodInsnNode(INVOKESTATIC, ESSENTIAL, "toggleable", "(Ljava/util/stream/Stream;)Ljava/util/stream/Stream;", false));
          hooks++;
        }
      }
      if (hooks != 1) throw new IllegalStateException("Unexpected plugin option enumeration");
      return;
    }
    if (node.name.equals(OPTION)) {
      MethodNode toggle = method(node, "togglePluginState", "(Ljava/util/Collection;Z)V");
      InsnList guard = new InsnList();
      LabelNode original = new LabelNode();
      guard.add(new VarInsnNode(ILOAD, 1));
      guard.add(new JumpInsnNode(IFNE, original)); // Enabling remains the original implementation.
      guard.add(new VarInsnNode(ALOAD, 0));
      guard.add(new MethodInsnNode(INVOKESTATIC, ESSENTIAL, "canDisable", "(Ljava/util/Collection;)Z", false));
      guard.add(new JumpInsnNode(IFNE, original));
      guard.add(new InsnNode(RETURN));
      guard.add(original);
      toggle.instructions.insert(guard);
      return;
    }
    MethodNode action = method(node, "actionPerformed", "(Lcom/intellij/openapi/actionSystem/AnActionEvent;Lcom/intellij/notification/Notification;)V");
    AbstractInsnNode anchor = null;
    for (AbstractInsnNode instruction : action.instructions.toArray()) {
      if (instruction instanceof FieldInsnNode field && field.owner.equals(UNDO) && field.name.equals("val$enabled")) {
        anchor = instruction.getPrevious(); break;
      }
    }
    if (!(anchor instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != 0) {
      throw new IllegalStateException("Unexpected notification undo entry");
    }
    InsnList guard = new InsnList();
    LabelNode original = new LabelNode();
    guard.add(new VarInsnNode(ALOAD, 0));
    guard.add(new FieldInsnNode(GETFIELD, UNDO, "val$enabled", "Z"));
    guard.add(new JumpInsnNode(IFEQ, original));
    guard.add(new VarInsnNode(ALOAD, 0));
    guard.add(new FieldInsnNode(GETFIELD, UNDO, "val$descriptors", "Ljava/util/Collection;"));
    guard.add(new MethodInsnNode(INVOKESTATIC, ESSENTIAL, "canDisable", "(Ljava/util/Collection;)Z", false));
    guard.add(new JumpInsnNode(IFNE, original));
    guard.add(new VarInsnNode(ALOAD, 2));
    guard.add(new MethodInsnNode(INVOKEVIRTUAL, "com/intellij/notification/Notification", "expire", "()V", false));
    guard.add(new InsnNode(RETURN));
    guard.add(original);
    action.instructions.insertBefore(anchor, guard);
  }

  static void verifyHooks(ClassNode node) {
    if (!node.name.equals(TAB)) return;
    MethodNode callback = node.methods.stream().filter(m -> m.name.equals("lambda$createPluginsPanel$8")).findFirst().orElseThrow();
    int active = 0, ready = 0, captures = 0;
    boolean bodyStarted = false;
    for (AbstractInsnNode instruction : callback.instructions.toArray()) {
      if (instruction instanceof FieldInsnNode field && field.name.equals(CAPTURE)) captures++;
      if (instruction instanceof MethodInsnNode call) {
        if (call.name.equals("goforgeModelActive")) {
          if (bodyStarted) throw new IllegalStateException("Lifecycle guard follows original callback code");
          active++;
        } else if (call.name.equals("goforgeModelInitialized")) {
          AbstractInsnNode previous = call.getPrevious();
          if (!(previous instanceof FieldInsnNode field) || !field.owner.equals(TAB) || !field.name.equals(CAPTURE)) {
            throw new IllegalStateException("Completion must use the captured same-tab panel");
          }
          ready++;
        } else bodyStarted = true;
      }
    }
    if (active != 1 || ready != 1 || captures != 2) throw new IllegalStateException("Unexpected lifecycle hooks");
  }

  static void patchTab(ClassNode node) {
    node.fields.add(new FieldNode(ACC_PRIVATE, CAPTURE, "L" + PANEL + ";", null, null));
    MethodNode create = method(node, "createSearchPanel", "(Ljava/util/function/Consumer;)L" + BASE + ";");
    int captures = 0;
    for (AbstractInsnNode insn : create.instructions.toArray()) if (insn.getOpcode() == ARETURN) {
      InsnList capture = new InsnList();
      capture.add(new InsnNode(DUP));
      capture.add(new VarInsnNode(ALOAD, 0));
      capture.add(new InsnNode(SWAP));
      capture.add(new TypeInsnNode(CHECKCAST, PANEL));
      capture.add(new FieldInsnNode(PUTFIELD, TAB, CAPTURE, "L" + PANEL + ";"));
      create.instructions.insertBefore(insn, capture);
      captures++;
    }
    if (captures != 1) throw new IllegalStateException("Unexpected search panel construction returns");
    MethodNode callback = method(node, "lambda$createPluginsPanel$8",
      "(Lcom/intellij/ide/plugins/newui/UiPluginManager;Ljava/util/function/Consumer;Lcom/intellij/ide/plugins/CreateInstalledPanelModel;)Lkotlin/Unit;");
    int returns = 0;
    for (AbstractInsnNode insn : callback.instructions.toArray()) {
      if (insn.getOpcode() == ARETURN) {
        // The original successful return is after all groups and stopLoading.
        // Its catch/rethrow path is deliberately unchanged and cannot mark ready.
        InsnList code = new InsnList();
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new FieldInsnNode(GETFIELD, TAB, CAPTURE, "L" + PANEL + ";"));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, PANEL, "goforgeModelInitialized", "()V", false));
        callback.instructions.insertBefore(insn, code);
        returns++;
      }
    }
    if (returns != 1) throw new IllegalStateException("Unexpected installed callback returns");
    // The coroutine scope is canceled on disposal, but capture/guard identity as
    // well: a late old callback must never initialize a replacement settings UI.
    InsnList entry = new InsnList();
    entry.add(new VarInsnNode(ALOAD, 0));
    entry.add(new FieldInsnNode(GETFIELD, TAB, CAPTURE, "L" + PANEL + ";"));
    entry.add(new MethodInsnNode(INVOKEVIRTUAL, PANEL, "goforgeModelActive", "()Z", false));
    LabelNode active = new LabelNode();
    entry.add(new JumpInsnNode(IFNE, active));
    entry.add(new InsnNode(ACONST_NULL));
    entry.add(new InsnNode(ARETURN));
    entry.add(active);
    callback.instructions.insert(entry);
  }

  static void patchPanel(ClassNode node) {
    node.fields.add(new FieldNode(ACC_PRIVATE | ACC_FINAL, FIELD, "L" + GATE + ";", null, null));
    int constructors = 0;
    for (MethodNode method : node.methods) if (method.name.equals("<init>")) {
      for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == RETURN) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new TypeInsnNode(NEW, GATE));
        code.add(new InsnNode(DUP));
        code.add(new MethodInsnNode(INVOKESPECIAL, GATE, "<init>", "()V", false));
        code.add(new FieldInsnNode(PUTFIELD, PANEL, FIELD, "L" + GATE + ";"));
        method.instructions.insertBefore(insn, code);
        constructors++;
      }
    }
    if (constructors != 1) throw new IllegalStateException("Unexpected installed search constructor");

    MethodVisitor set = add(node, "setQuery", "(Ljava/lang/String;)V");
    gate(set);
    set.visitVarInsn(ALOAD, 1);
    set.visitMethodInsn(INVOKEVIRTUAL, GATE, "defer", "(Ljava/lang/String;)Z", false);
    Label apply = new Label(), end = new Label();
    set.visitJumpInsn(IFEQ, apply);
    gate(set);
    set.visitMethodInsn(INVOKEVIRTUAL, GATE, "isDisposed", "()Z", false);
    set.visitJumpInsn(IFNE, end);
    set.visitVarInsn(ALOAD, 1);
    set.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
    // Empty queries are safe before initialization and must clear the view.
    set.visitJumpInsn(IFNE, apply);
    set.visitVarInsn(ALOAD, 0);
    set.visitMethodInsn(INVOKEVIRTUAL, BASE, "getPanel", "()Lcom/intellij/ide/plugins/newui/PluginsGroupComponent;", false);
    set.visitMethodInsn(INVOKEVIRTUAL, "com/intellij/ide/plugins/newui/PluginsGroupComponent", "getEmptyText", "()Lcom/intellij/util/ui/StatusText;", false);
    set.visitLdcInsn("Loading installed plugins…");
    set.visitMethodInsn(INVOKEVIRTUAL, "com/intellij/util/ui/StatusText", "setText", "(Ljava/lang/String;)Lcom/intellij/util/ui/StatusText;", false);
    set.visitInsn(POP);
    set.visitJumpInsn(GOTO, end);
    set.visitLabel(apply);
    set.visitVarInsn(ALOAD, 0);
    set.visitVarInsn(ALOAD, 1);
    set.visitMethodInsn(INVOKESPECIAL, BASE, "setQuery", "(Ljava/lang/String;)V", false);
    set.visitLabel(end);
    finish(set);

    MethodVisitor ready = add(node, "goforgeModelInitialized", "()V");
    gate(ready);
    ready.visitMethodInsn(INVOKEVIRTUAL, GATE, "initialized", "()Ljava/lang/String;", false);
    ready.visitVarInsn(ASTORE, 1);
    ready.visitVarInsn(ALOAD, 1);
    Label noQuery = new Label();
    ready.visitJumpInsn(IFNULL, noQuery);
    ready.visitVarInsn(ALOAD, 0);
    ready.visitVarInsn(ALOAD, 1);
    ready.visitMethodInsn(INVOKEVIRTUAL, PANEL, "setQuery", "(Ljava/lang/String;)V", false);
    ready.visitLabel(noQuery);
    finish(ready);

    for (String name : new String[]{"getQuery", "isEmpty"}) {
      boolean empty = name.equals("isEmpty");
      String descriptor = empty ? "()Z" : "()Ljava/lang/String;";
      MethodVisitor query = add(node, name, descriptor);
      gate(query);
      query.visitMethodInsn(INVOKEVIRTUAL, GATE, "pendingQuery", "()Ljava/lang/String;", false);
      query.visitVarInsn(ASTORE, 1);
      query.visitVarInsn(ALOAD, 1);
      Label original = new Label();
      query.visitJumpInsn(IFNULL, original);
      query.visitVarInsn(ALOAD, 1);
      if (empty) query.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
      query.visitInsn(empty ? IRETURN : ARETURN);
      query.visitLabel(original);
      query.visitVarInsn(ALOAD, 0);
      query.visitMethodInsn(INVOKESPECIAL, BASE, name, descriptor, false);
      query.visitInsn(empty ? IRETURN : ARETURN);
      query.visitMaxs(0, 0); query.visitEnd();
    }

    MethodVisitor clear = add(node, "setEmpty", "()V");
    gate(clear);
    clear.visitLdcInsn("");
    clear.visitMethodInsn(INVOKEVIRTUAL, GATE, "defer", "(Ljava/lang/String;)Z", false);
    clear.visitInsn(POP);
    clear.visitVarInsn(ALOAD, 0);
    clear.visitMethodInsn(INVOKESPECIAL, BASE, "setEmpty", "()V", false);
    finish(clear);

    MethodVisitor isReady = add(node, "goforgeModelReady", "()Z");
    gate(isReady);
    isReady.visitMethodInsn(INVOKEVIRTUAL, GATE, "isReady", "()Z", false);
    isReady.visitInsn(IRETURN);
    isReady.visitMaxs(0, 0); isReady.visitEnd();

    MethodVisitor active = add(node, "goforgeModelActive", "()Z");
    gate(active);
    active.visitMethodInsn(INVOKEVIRTUAL, GATE, "isDisposed", "()Z", false);
    active.visitInsn(ICONST_1);
    active.visitInsn(IXOR);
    active.visitInsn(IRETURN);
    active.visitMaxs(0, 0); active.visitEnd();

    MethodVisitor dispose = add(node, "dispose", "()V");
    gate(dispose);
    dispose.visitMethodInsn(INVOKEVIRTUAL, GATE, "dispose", "()V", false);
    dispose.visitVarInsn(ALOAD, 0);
    dispose.visitMethodInsn(INVOKESPECIAL, BASE, "dispose", "()V", false);
    finish(dispose);
  }

  static void patchPopup(ClassNode node) {
    MethodNode values = method(node, "getValues", "(Ljava/lang/String;)Ljava/util/SortedSet;");
    InsnList code = new InsnList();
    code.add(new VarInsnNode(ALOAD, 0));
    code.add(new FieldInsnNode(GETFIELD, POPUP, "this$1", "L" + TAB + ";"));
    code.add(new FieldInsnNode(GETFIELD, TAB, CAPTURE, "L" + PANEL + ";"));
    code.add(new InsnNode(DUP));
    LabelNode exists = new LabelNode(), ready = new LabelNode();
    code.add(new JumpInsnNode(IFNONNULL, exists));
    code.add(new InsnNode(POP));
    code.add(new InsnNode(ACONST_NULL));
    code.add(new InsnNode(ARETURN));
    code.add(exists);
    code.add(new TypeInsnNode(CHECKCAST, PANEL));
    code.add(new MethodInsnNode(INVOKEVIRTUAL, PANEL, "goforgeModelReady", "()Z", false));
    code.add(new JumpInsnNode(IFNE, ready));
    // Nullable suggestions only; the search query itself is retained and replayed.
    code.add(new InsnNode(ACONST_NULL));
    code.add(new InsnNode(ARETURN));
    code.add(ready);
    values.instructions.insert(code);
  }

  static MethodNode method(ClassNode node, String name, String descriptor) {
    return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElseThrow();
  }
  static MethodVisitor add(ClassNode node, String name, String descriptor) {
    if (node.methods.stream().anyMatch(m -> m.name.equals(name))) throw new IllegalStateException("Already patched method " + name);
    MethodNode method = new MethodNode(ACC_PUBLIC, name, descriptor, null, null);
    node.methods.add(method); method.visitCode(); return method;
  }
  static void gate(MethodVisitor method) {
    method.visitVarInsn(ALOAD, 0);
    method.visitFieldInsn(GETFIELD, PANEL, FIELD, "L" + GATE + ";");
  }
  static void finish(MethodVisitor method) {
    method.visitInsn(RETURN); method.visitMaxs(0, 0); method.visitEnd();
  }
}
