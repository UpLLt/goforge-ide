package dev.goforge.remote;

import org.junit.Test;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class GoRemoteProfileTest {
    private GoRemoteProfile profile(String host,String root) {return new GoRemoteProfile(host,"dev",2222,"",root,"go","");}
    @Test public void refusesOptionsAndUserControlInjection() {
        for(String host:List.of("-oProxyCommand=bad","host name","user@host","host\ncommand","host;id"))
            assertThrows(IllegalArgumentException.class,() -> profile(host,"/work"));
        assertThrows(IllegalArgumentException.class,() -> new GoRemoteProfile("host","-bad",22,"","/work","go",""));
    }
    @Test public void refusesEscapingOrRootWorkspacePaths() {
        for(String path:List.of("/","relative","/work/../other","/work/./a","/work\nnext"))
            assertThrows(IllegalArgumentException.class,() -> profile("host",path));
        for(String path:List.of("/abs","../up","a/../up","a//b","a\\b","."))
            assertThrows(IllegalArgumentException.class,() -> GoRemoteProfile.relative(path));
    }
    @Test public void sshAlwaysUsesStrictKnownHostsAndNoForwardingOrSharedControlSocket() {
        var args=profile("dev-alias","/work").ssh("/usr/bin/ssh","printf ok",false);
        assertTrue(args.containsAll(List.of("StrictHostKeyChecking=yes","BatchMode=yes","ForwardAgent=no","ClearAllForwardings=yes","ControlPath=none")));
        assertEquals(List.of("--","dev-alias","printf ok"),args.subList(args.size()-3,args.size()));
        assertFalse(args.contains("StrictHostKeyChecking=no"));
    }
    @Test public void terminalAllowsInteractiveAuthenticationWithoutConstructingLocalShellCommand() {
        var p=profile("::1","/workspace with ' quotes");var args=p.ssh("/usr/bin/ssh",p.terminalCommand(),true);
        assertTrue(args.contains("-tt"));assertTrue(args.contains("BatchMode=no"));
        assertEquals("/usr/bin/ssh",args.getFirst());assertTrue(args.getLast().contains("'\"'\"'"));
    }
    @Test public void shellQuotedDataRemainsExactlyOneLiteralArgument() throws Exception {
        // This contract is for the POSIX remote host, not the local Windows SSH client.
        org.junit.Assume.assumeTrue("Requires the remote host's POSIX shell semantics",Files.isExecutable(java.nio.file.Path.of("/bin/sh")));
        String value="/workspace'; printf injected; $(echo injected) `echo bad`";
        var process=new ProcessBuilder("/bin/sh","-c","printf '%s' "+GoRemoteProfile.quote(value)).start();
        assertEquals(value,new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));assertEquals(0,process.waitFor());
    }
    @Test public void windowsSshPathRemainsOneLocalArgumentAndRemoteCommandRemainsPosix() {
        String executable="C:\\Program Files\\OpenSSH\\ssh.exe";
        var p=profile("host","/workspace with ' quotes");var args=p.ssh(executable,p.terminalCommand(),true);
        assertEquals(executable,args.getFirst());assertEquals(p.terminalCommand(),args.getLast());
        assertTrue(args.getLast().contains("'\"'\"'"));
    }
    @Test public void fileOptionsPreserveSpacesAndAreNeverInterpretedAsOptions() throws Exception {
        var file=Files.createTempFile("GoForge key with spaces", ".txt");
        try {var p=new GoRemoteProfile("host","",22,file.toString(),"/work","/opt/go bin/go",file.toString());var args=p.ssh("ssh","x",false);
            assertEquals(file.toString(),args.get(args.indexOf("-i")+1));assertTrue(args.stream().anyMatch(v -> v.startsWith("UserKnownHostsFile=\"")));
        }finally{Files.delete(file);}
    }
}
