package dev.goforge.containers;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.openapi.util.Key;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Owns one loopback kubectl process; output is readable while it is running. */
final class KubernetesPortForward {
    private final StringBuilder output=new StringBuilder();
    synchronized String output(){return output.toString();}
    private synchronized void append(String text){int count=Math.min(text.length(),65536-output.length());if(count>0)output.append(text,0,count);}
    String run(List<String> argv,Path directory,BooleanSupplier cancelled,Duration timeout)throws Exception{
        var handler=new KillableProcessHandler(new GeneralCommandLine(argv).withWorkDirectory(directory.toFile()).withCharset(StandardCharsets.UTF_8));
        handler.setShouldDestroyProcessRecursively(true);handler.setShouldKillProcessSoftly(false);
        handler.addProcessListener(new ProcessAdapter(){@Override public void onTextAvailable(ProcessEvent event,Key type){append(event.getText());}});
        handler.startNotify();long start=System.nanoTime();boolean interrupted=false;
        try{while(!handler.waitFor(100)){if(cancelled.getAsBoolean()||System.nanoTime()-start>timeout.toNanos())break;}}
        finally{interrupted=Thread.interrupted();try{if(!handler.isProcessTerminated()){handler.destroyProcess();if(!handler.waitFor(500))handler.killProcess();handler.waitFor(1500);}}finally{if(interrupted)Thread.currentThread().interrupt();}}
        return output()+"\nPort-forward ended; exit="+handler.getExitCode()+". Only 127.0.0.1 was bound.\n";
    }
}
