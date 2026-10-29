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
        var snapshot=GoRemoteFiles.snapshot(local,() -> false);
        var review=GoRemoteAnalysisReview.inspect(local,snapshot,args.length>8?args[8]:args[5],System.getenv(),() -> false);
        var config=GoRemoteAnalysisConfig.create(backend,profile,local,args[6],review,() -> false);
        var details=new JsonObject();details.addProperty("remoteRoot",config.remoteRoot());details.addProperty("name",config.name());
        details.add("workspace",new Gson().toJsonTree(config.workspace()));
        try(var transport=new GoRemoteLspTransport(config,System.getenv(),System.err::print)) {
            transport.awaitReady(Duration.ofSeconds(15));details.addProperty("remotePid",transport.remotePid());details.addProperty("sshPid",transport.sshPid());
            Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(details));
            Thread input=new Thread(() -> {try {System.in.transferTo(transport.output());}catch(Exception ignored){}finally{transport.closeAsync();}},"fixture input");input.setDaemon(true);input.start();
            try {transport.input().transferTo(System.out);System.out.flush();}
            catch(java.io.IOException ended) {
                details.addProperty("streamEnd",ended.getMessage());
                // A deliberately failed production transport may close its writer while this
                // recorder is blocked. Preserve both that event and final process evidence.
                if(!transport.failed())throw ended;
            }finally {
                transport.close();
                details.addProperty("transportFailed",transport.failed());details.addProperty("sshAlive",ProcessHandle.of(transport.sshPid()).map(ProcessHandle::isAlive).orElse(false));
                details.addProperty("remoteAlive",ProcessHandle.of(transport.remotePid()).map(ProcessHandle::isAlive).orElse(false));
                Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(details));
            }
        }
    }
}
