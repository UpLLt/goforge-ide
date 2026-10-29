package dev.goforge.remote;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import static org.junit.Assert.*;

public final class GoRemoteDebugSdkTransportTest {
    @Test public void CompressedLoaderContainsTheExactUtf8BridgeAndNoSourceFileWrite()throws Exception{
        String command=GoRemoteDebugScript.command();assertTrue(command.startsWith("python3 -c 'import base64,zlib;exec(zlib.decompress(base64.b64decode("));
        var match=java.util.regex.Pattern.compile("[A-Za-z0-9+/=]{100,}").matcher(command);assertTrue(match.find());String payload=match.group();assertFalse(match.find());
        try(var input=new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload)))){
            assertArrayEquals(GoRemoteDebugScript.SOURCE.getBytes(StandardCharsets.UTF_8),input.readAllBytes());
        }
    }
    @Test public void ActualRemoteDebugArgvFitsConservativeWindowsLaunchBudget(){
        var profile=new GoRemoteProfile("fixture.invalid","fixture",2222,"","/remote workspace","go","");
        var plan=new GoRemoteDebugPlan(profile,"/local","dlv","debug",".",0,Map.of("go.mod","a".repeat(64)),List.of(),Map.of());
        List<String> argv=plan.command("ssh.exe");assertEquals(GoRemoteDebugScript.command(),argv.get(argv.size()-1));
        // Quotes/backslashes can expand in a Windows command line. Reserve another full command's
        // length plus 4KiB for normal user key/known-host paths; this is a size gate, not Windows execution.
        int conservative=argv.stream().mapToInt(s->s.length()*2+3).sum()+4096;
        assertTrue("SSH command would exceed Windows launch budget: "+conservative,conservative<32767);
    }
}
