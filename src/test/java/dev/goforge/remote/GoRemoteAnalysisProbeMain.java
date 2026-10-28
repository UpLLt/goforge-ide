package dev.goforge.remote;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Real SSH fixture entrypoint. Shares the production transport, mapper and save implementation. */
public final class GoRemoteAnalysisProbeMain {
    public static void main(String[] args)throws Exception {
        Path local=Path.of(args[0]),receipt=Path.of(args[7]);
        var profile=new GoRemoteProfile("127.0.0.1",System.getProperty("user.name"),Integer.parseInt(args[2]),args[3],args[1],args[5],args[4]);
        var backend=new GoRemoteBackend("/usr/bin/ssh",local,System.getenv());
        var config=GoRemoteAnalysisConfig.create(backend,profile,local,args[6],GoRemoteFiles.snapshot(local,() -> false),() -> false);
        var details=new JsonObject();details.addProperty("remoteRoot",config.remoteRoot());details.addProperty("name",config.name());
        try(var transport=new GoRemoteLspTransport(config,System.getenv(),System.err::print)) {
            transport.awaitReady(Duration.ofSeconds(15));details.addProperty("remotePid",transport.remotePid());details.addProperty("sshPid",transport.sshPid());
            Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(details));
            Thread input=new Thread(() -> {try {System.in.transferTo(transport.output());}catch(Exception ignored){}finally{transport.closeAsync();}},"fixture input");input.setDaemon(true);input.start();
            transport.input().transferTo(System.out);System.out.flush();transport.close();
            details.addProperty("transportFailed",transport.failed());details.addProperty("sshAlive",ProcessHandle.of(transport.sshPid()).map(ProcessHandle::isAlive).orElse(false));
            details.addProperty("remoteAlive",ProcessHandle.of(transport.remotePid()).map(ProcessHandle::isAlive).orElse(false));
            Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(details));
        }
    }
}
