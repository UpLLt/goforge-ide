// Copyright 2026 GoForge contributors. Licensed under the Apache License, Version 2.0.
// Transforms one callsite in a separately SHA-256-pinned public Community class.
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.tree.*;
import org.jetbrains.org.objectweb.asm.util.CheckClassAdapter;

public final class PatchGitDashboard implements Opcodes {
  static final String OWNER = "git4idea/ui/branch/dashboard/FilteringBranchesTree";
  static final String HELPER = "dev/goforge/platform/GitDashboardEdt";
  static final String BLOCK = "Lkotlin/jvm/functions/Function2;";
  static final String LAUNCH = "(Ljava/awt/Component;Ljava/lang/String;Lkotlin/coroutines/CoroutineContext;" + BLOCK + "ILjava/lang/Object;)Lkotlinx/coroutines/Job;";
  static final String CONSTRUCTOR = "(Lcom/intellij/openapi/project/Project;Lgit4idea/ui/branch/dashboard/BranchesTreeModel;Lgit4idea/ui/branch/dashboard/BranchesTreeComponent;Ljava/lang/String;Lcom/intellij/openapi/Disposable;)V";

  public static void main(String[] args) throws Exception {
    Path input = Path.of(args[0]), output = Path.of(args[1]);
    var reader = new ClassReader(Files.readAllBytes(input.resolve(OWNER + ".class")));
    var node = new ClassNode();
    reader.accept(node, 0);
    if (!node.name.equals(OWNER)) throw new IllegalStateException("Unexpected target class");
    MethodNode constructor = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(CONSTRUCTOR)).findFirst().orElseThrow();
    int launches = 0, names = 0, blocks = 0;
    for (MethodNode method : node.methods) {
      for (AbstractInsnNode instruction : method.instructions.toArray()) {
        if (instruction instanceof MethodInsnNode call && call.owner.equals(HELPER)) throw new IllegalStateException("Already hooked class");
        if (instruction instanceof LdcInsnNode constant && constant.cst.equals("Git Dashboard Tree")) {
          if (method != constructor) throw new IllegalStateException("Unexpected coroutine name placement");
          names++;
        }
        if (instruction instanceof TypeInsnNode type && type.getOpcode() == NEW && type.desc.equals(OWNER + "$1")) {
          if (method != constructor) throw new IllegalStateException("Unexpected coroutine construction");
          blocks++;
        }
        if (instruction instanceof MethodInsnNode call && call.owner.equals("com/intellij/util/ui/UiScopeKt") && call.name.equals("launchOnShow$default")) {
          if (method != constructor || call.getOpcode() != INVOKESTATIC || !call.desc.equals(LAUNCH)) throw new IllegalStateException("Unexpected launchOnShow ABI");
          AbstractInsnNode nullArg = previous(call), mask = previous(nullArg), cast = previous(mask), creation = previous(cast);
          if (nullArg.getOpcode() != ACONST_NULL || mask.getOpcode() != ICONST_2
              || !(cast instanceof TypeInsnNode type) || type.getOpcode() != CHECKCAST || !type.desc.equals("kotlin/jvm/functions/Function2")
              || !(creation instanceof MethodInsnNode init) || init.getOpcode() != INVOKESPECIAL || !init.owner.equals(OWNER + "$1") || !init.name.equals("<init>")) {
            throw new IllegalStateException("Unexpected launchOnShow block operand layout");
          }
          method.instructions.insert(cast, new MethodInsnNode(INVOKESTATIC, HELPER, "wrap", "(" + BLOCK + ")" + BLOCK, false));
          launches++;
        }
      }
    }
    if (launches != 1 || names != 1 || blocks != 1) throw new IllegalStateException("Expected exactly one named Git Dashboard launch block");
    // Function2 -> Function2 preserves stack height and all original frames.
    var writer = new ClassWriter(reader, 0);
    node.accept(writer);
    byte[] bytes = writer.toByteArray();
    var errors = new StringWriter();
    CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(errors));
    if (!errors.toString().isBlank()) throw new IllegalStateException(errors.toString());
    Path target = output.resolve(OWNER + ".class");
    Files.createDirectories(target.getParent());
    Files.write(target, bytes);
  }

  static AbstractInsnNode previous(AbstractInsnNode instruction) {
    AbstractInsnNode value = instruction.getPrevious();
    while (value != null && value.getOpcode() < 0) value = value.getPrevious();
    if (value == null) throw new IllegalStateException("Incomplete callsite");
    return value;
  }
}
