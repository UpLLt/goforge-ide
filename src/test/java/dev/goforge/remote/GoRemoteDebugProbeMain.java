package dev.goforge.remote;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Engine probe entrypoint: uses the exact production plan, command and remote supervisor. No platform stubs. */
public final class GoRemoteDebugProbeMain {
    public static void main(String[] args)throws Exception {
        var json=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var profile=new GoRemoteProfile(json.get("host").getAsString(),json.get("user").getAsString(),json.get("port").getAsInt(),json.get("keyFile").getAsString(),json.get("remoteDirectory").getAsString(),json.get("goExecutable").getAsString(),json.get("knownHostsFile").getAsString());
        Path root=Path.of(json.get("localProject").getAsString());var env=args.length>5?GoRemoteDebugOptions.environment(args[5]):Map.<String,String>of();
        var argv=args.length>6?GoRemoteDebugOptions.arguments(args[6]):List.<String>of();
        var snapshot=GoRemoteDebugSources.snapshot(root,()->false);
        var plan=json.has("localGoExecutable")?GoRemoteDebugPlan.review(profile,root,json.get("delveExecutable").getAsString(),args[1],args[2],Integer.parseInt(args[3]),snapshot,argv,env,json.get("localGoExecutable").getAsString(),System.getenv(),()->false):GoRemoteDebugPlan.create(profile,root,json.get("delveExecutable").getAsString(),args[1],args[2],Integer.parseInt(args[3]),snapshot,argv,env);plan.verifyLocal();
        var child=new ProcessBuilder(plan.command("/usr/bin/ssh")).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
        Files.writeString(Path.of(args[4]),new Gson().toJson(Map.of("sshPid",child.pid(),"mode",plan.mode(),"localRoot",plan.localRoot(),"files",plan.files().size())));
        // Explicit protocol simulation only: physical local files were reviewed above on this host.
        var startup=JsonParser.parseString(plan.startupJson()).getAsJsonObject();
        if(json.has("protocolLocalRoot"))startup.addProperty("localRoot",json.get("protocolLocalRoot").getAsString());
        child.getOutputStream().write((GoRemoteDebugScript.payload()+"\n"+new Gson().toJson(startup)+"\n").getBytes(StandardCharsets.UTF_8));child.getOutputStream().flush();
        var input=new Thread(()->{try{byte[] b=new byte[8192];int n;while((n=System.in.read(b))>=0){child.getOutputStream().write(b,0,n);child.getOutputStream().flush();}child.getOutputStream().close();}catch(Exception ignored){child.destroy();}});input.setDaemon(true);input.start();
        byte[] b=new byte[8192];int n;while((n=child.getInputStream().read(b))>=0){System.out.write(b,0,n);System.out.flush();}System.exit(child.waitFor());
    }
}
