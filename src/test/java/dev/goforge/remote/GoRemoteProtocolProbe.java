package dev.goforge.remote;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Explicit localhost SSH integration; never part of ordinary test execution. */
public final class GoRemoteProtocolProbe {
    public static void main(String[] args) throws Exception {
        Path local=Path.of(args[0]),remote=Path.of(args[1]),output=Path.of(args[7]);
        var profile=new GoRemoteProfile("127.0.0.1",System.getProperty("user.name"),Integer.parseInt(args[2]),args[3],remote.toString(),args[5],args[4]);
        var backend=new GoRemoteBackend("/usr/bin/ssh",local,System.getenv());var checks=new LinkedHashMap<String,Object>();
        checks.put("connection",backend.check(profile,() -> false));
        var listing=backend.list(profile,"",() -> false);require(listing.stream().anyMatch(e -> e.name().equals("main.go")),"remote listing");checks.put("browse",true);
        byte[] downloaded=backend.download(profile,"main.go",() -> false);require(Arrays.equals(downloaded,Files.readAllBytes(remote.resolve("main.go"))),"download bytes");
        Files.createDirectory(local.resolve("downloaded"));GoRemoteFiles.writeNew(local,"downloaded/main.go",downloaded);checks.put("download_new_file",true);
        var snapshot=GoRemoteFiles.snapshot(local,() -> false);String name="goforge-upload-probe";var upload=backend.upload(profile,name,snapshot,() -> false);
        require(Files.readString(remote.resolve(name).resolve("note';touch SHOULD_NOT_EXIST;'.txt")).equals("literal name"),"quoted filename");
        require(!Files.exists(remote.resolve("SHOULD_NOT_EXIST")),"no filename command execution");checks.put("upload",upload);
        try {backend.upload(profile,name,snapshot,() -> false);throw new AssertionError("existing snapshot replaced");}catch(java.io.IOException expected){checks.put("existing_remote_snapshot_rejected",true);}
        try {backend.download(profile,"escape/outside",() -> false);throw new AssertionError("followed remote symlink");}catch(java.io.IOException expected){checks.put("remote_symlink_escape_rejected",true);}
        var bad=new GoRemoteProfile(profile.host(),profile.user(),profile.port(),profile.keyFile(),profile.remoteDirectory(),profile.goExecutable(),args[6]);
        try {backend.check(bad,() -> false);throw new AssertionError("accepted unknown host key");}catch(java.io.IOException expected){require(expected.getMessage().contains("Host key verification failed")||expected.getMessage().contains("HOST IDENTIFICATION HAS CHANGED"),"real host-key refusal");checks.put("unknown_host_key_rejected",true);}
        for(var task:List.of(GoRemoteBackend.GoTask.ENV,GoRemoteBackend.GoTask.BUILD,GoRemoteBackend.GoTask.TEST,GoRemoteBackend.GoTask.RUN)) {
            var result=backend.go(profile,task,() -> false,s -> {});require(result.success(),task+": "+result.failure());checks.put(task.name().toLowerCase(),Map.of("exit",result.exitCode(),"stdout",result.stdout(),"stderr",result.stderr()));
        }
        Files.writeString(remote.resolve("main.go"),"package main\nimport(\"fmt\";\"os\";\"time\";\"os/signal\";\"syscall\")\nfunc main(){signal.Ignore(syscall.SIGTERM);fmt.Printf(\"CANCEL_CHILD=%d\\n\",os.Getpid());time.Sleep(time.Minute)}\n");
        var ready=new AtomicBoolean();var log=new StringBuffer();long started=System.nanoTime();
        var cancelled=backend.go(profile,GoRemoteBackend.GoTask.RUN,() -> ready.get()||System.nanoTime()-started>15_000_000_000L,s -> {log.append(s);if(log.indexOf("CANCEL_CHILD=")>=0&&log.toString().matches("(?s).*CANCEL_CHILD=\\d+\\n.*"))ready.set(true);});
        require(cancelled.cancelled()&&ready.get(),"cancel actual running remote Go child");var matcher=Pattern.compile("CANCEL_CHILD=(\\d+)").matcher(log);require(matcher.find(),"remote child pid");long pid=Long.parseLong(matcher.group(1));
        for(int i=0;i<40&&ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);i++)Thread.sleep(100);
        require(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"remote Go child stopped");require(cancelled.localProcessesStopped(),"local SSH stopped");checks.put("cancel_remote_go_group",Map.of("child_pid",pid,"child_alive",false,"local_stopped",true));
        var result=new LinkedHashMap<String,Object>();result.put("passed",true);result.put("transport","real temporary localhost OpenSSH sshd; independent keys/known_hosts");result.put("checks",checks);result.put("scope","SSH workspace only; no remote indexing backend, arbitrary host acceptance or native UI acceptance");
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
