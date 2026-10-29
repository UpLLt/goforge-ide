package dev.goforge.debug;

public final class GoDelveCleanupChild {
    public static void main(String[] args) throws Exception {
        if (args.length != 0 && (args[0].equals("parent") || args[0].equals("detach"))) {
            String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            var child = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), GoDelveCleanupChild.class.getName(), "linger").start();
            System.out.println(child.pid()); System.out.flush(); if (args[0].equals("detach")) System.in.read(); else child.waitFor();
        } else if (args.length != 0 && args[0].equals("linger")) { Thread.sleep(30000); }
        else { System.out.println("ready"); System.out.flush(); System.in.read(); }
    }
}
