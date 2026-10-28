package dev.goforge.remote;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Explicit saved-source binding. Creating a plan does not connect or write a remote file. */
public record GoRemoteDebugPlan(GoRemoteProfile profile,String localRoot,String delve,String mode,
        String target,int processId,Map<String,String> files,List<String> arguments,Map<String,String> environment,GoRemoteDebugWorkspace workspace) {
    public static final String CONFIG_KEY="goforgeRemote";
    public GoRemoteDebugPlan {
        Objects.requireNonNull(profile);if(!profile.goExecutable().equals("go")&&!profile.goExecutable().endsWith("/go"))throw new IllegalArgumentException("Select the default go command or an absolute Go SDK bin/go path.");localRoot=Path.of(localRoot).toAbsolutePath().normalize().toString();
        delve=GoRemoteAnalysisConfig.validateExecutable(delve);
        if(!Set.of("debug","test","exec","attach").contains(mode))throw new IllegalArgumentException("Choose package, tests, executable or attach.");
        target=target==null||target.isBlank()?".":target.trim();
        if(!target.equals("."))GoRemoteProfile.relative(target);
        if(mode.equals("attach")&&processId<2)throw new IllegalArgumentException("Attach requires an existing remote PID greater than 1.");
        if(!mode.equals("attach")&&processId!=0)throw new IllegalArgumentException("Launch does not accept a PID.");
        arguments=List.copyOf(arguments);environment=Map.copyOf(environment);
        if(arguments.size()>64||environment.size()>128)throw new IllegalArgumentException("Too many arguments or environment variables.");
        for(String value:arguments){if(value.length()>4096)throw new IllegalArgumentException("Argument exceeds 4096 characters.");GoRemoteProfile.noControls(value);}
        environment.forEach((name,value)->{if(!name.matches("[A-Za-z_][A-Za-z0-9_]*")||value.length()>4096)throw new IllegalArgumentException("Invalid environment entry.");GoRemoteProfile.noControls(value);
            if(Set.of("GOROOT","GOPATH","GOOS","GOARCH","GOFLAGS","GOWORK","GOTOOLCHAIN","GOENV","GOPROXY","GOSUMDB","PATH","LD_PRELOAD","DYLD_INSERT_LIBRARIES").contains(name))throw new IllegalArgumentException("Toolchain/loader variable is not forwarded: "+name);
        });
        if(mode.equals("attach")&&(!arguments.isEmpty()||!environment.isEmpty()))throw new IllegalArgumentException("Attach cannot change the existing process arguments or environment.");
        files=Map.copyOf(files);
        if(files.isEmpty()||files.size()>512)throw new IllegalArgumentException("Debugging requires at most 512 visible saved files.");
        Objects.requireNonNull(workspace).requireFiles(files);
        files.forEach((name,hash)->{GoRemoteProfile.relative(name);if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid saved source hash.");});
    }
    public GoRemoteDebugPlan(GoRemoteProfile profile,String localRoot,String delve,String mode,String target,int processId,Map<String,String> files,List<String> arguments,Map<String,String> environment) {
        this(profile,localRoot,delve,mode,target,processId,files,arguments,environment,GoRemoteDebugWorkspace.singleModule());
    }
    public static GoRemoteDebugPlan review(GoRemoteProfile profile,Path local,String delve,String mode,String target,int pid,GoRemoteFiles.Snapshot snapshot,List<String> arguments,Map<String,String> environment,String localGo,Map<String,String> toolEnvironment,java.util.function.BooleanSupplier cancelled)throws IOException {
        var hashes=new TreeMap<String,String>();snapshot.files().forEach(f->hashes.put(f.path(),f.sha256()));
        var workspace=GoRemoteDebugWorkspace.inspect(localGo,local,hashes,toolEnvironment,cancelled);
        var plan=new GoRemoteDebugPlan(profile,local.toRealPath().toString(),delve,mode,target,pid,hashes,arguments,environment,workspace);
        if(workspace.enabled() && Set.of("debug","test").contains(mode) && workspace.modules().stream().noneMatch(m->m.equals(".")||plan.target().equals(m)||plan.target().startsWith(m+"/")))
            throw new IOException("Choose a package inside a reviewed go.work use module, not the workspace root.");
        plan.verifyLocal();return plan;
    }
    public static GoRemoteDebugPlan create(GoRemoteProfile profile,Path local,String delve,String mode,String target,int pid,GoRemoteFiles.Snapshot snapshot,List<String> arguments,Map<String,String> environment)throws IOException {
        var hashes=new TreeMap<String,String>();snapshot.files().forEach(f->hashes.put(f.path(),f.sha256()));
        return new GoRemoteDebugPlan(profile,local.toRealPath().toString(),delve,mode,target,pid,hashes,arguments,environment);
    }
    public boolean attach(){return mode.equals("attach");}
    public Map<String,Object> dapArguments() {
        var args=new LinkedHashMap<String,Object>();args.put("type","go");args.put("request",attach()?"attach":"launch");
        args.put("mode",attach()?"local":mode);args.put("cwd",profile.remoteDirectory());
        if(attach())args.put("processId",processId);
        else args.put("program",profile.remoteDirectory()+(target.equals(".")?"":"/"+target));
        args.put("args",arguments);args.put("env",environment);
        args.put("substitutePath",List.of(Map.of("from",localRoot,"to",profile.remoteDirectory())));
        return args;
    }
    public void verifyLocal()throws IOException {
        var now=GoRemoteDebugSources.snapshot(Path.of(localRoot),Thread.currentThread()::isInterrupted);var hashes=new HashMap<String,String>();now.files().forEach(f->hashes.put(f.path(),f.sha256()));
        if(!files.equals(hashes))throw new IOException("Saved source files changed since this configuration was reviewed. Start remote debugging again from Go Remote.");
    }
    public String startupJson() {
        var body=new LinkedHashMap<String,Object>();body.put("root",profile.remoteDirectory());body.put("localRoot",localRoot);body.put("dlv",delve);body.put("go",profile.goExecutable());body.put("mode",mode);body.put("target",target);body.put("pid",processId);body.put("files",files);body.put("arguments",dapArguments());body.put("workspace",workspace);
        String json=new Gson().toJson(body);
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>48000)throw new IllegalArgumentException("Remote debug source manifest exceeds 48 KiB; choose a smaller module.");
        return json;
    }
    public List<String> command(String ssh){return profile.ssh(ssh,GoRemoteScripts.python(GoRemoteDebugScript.SOURCE),false);}
}
