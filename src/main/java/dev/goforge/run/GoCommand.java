package dev.goforge.run;

public enum GoCommand {
    RUN("run"), TEST("test"), BUILD("build"), VET("vet"),
    GENERATE("generate"), LIST("list"), MOD("mod"), WORK("work"), ENV("env"), TOOL("tool");
    private final String argument;
    GoCommand(String argument) { this.argument = argument; }
    public String argument() { return argument; }
    @Override public String toString() { return "go " + argument; }
}
