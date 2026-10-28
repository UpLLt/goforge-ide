package dev.goforge.containers;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.Assert.*;

public class DockerCommandsTest {
    private static final String ID="a".repeat(64);
    @Test public void contextAndExecAreLiteralArgumentsWithoutHostShell() {
        var request=DockerCommands.exec(ID,List.of("/bin/echo","$(touch /tmp/not-created); ' ; &"),false);
        var command=DockerCommands.command("/path with spaces/docker","test context",request);
        assertEquals(List.of("/path with spaces/docker","--context","test context","container","exec","--",ID,"/bin/echo","$(touch /tmp/not-created); ' ; &"),command);
        assertFalse(command.contains("sh"));
        assertThrows(IllegalArgumentException.class,()->DockerCommands.command("docker",null,DockerCommands.containers()));
        assertEquals("context",DockerCommands.command("docker",null,DockerCommands.contexts()).get(1));
    }
    @Test public void destructiveOperationsUseOneSelectedIdWithoutForceOrVolumes() {
        assertEquals(List.of("container","rm","--",ID),DockerCommands.lifecycle(DockerCommands.Lifecycle.REMOVE,ID).arguments());
        assertEquals(List.of("image","rm","--","example/image:tag"),DockerCommands.removeImage("example/image:tag").arguments());
        assertThrows(IllegalArgumentException.class,()->DockerCommands.lifecycle(DockerCommands.Lifecycle.STOP,"--all"));
        assertThrows(IllegalArgumentException.class,()->DockerCommands.pull("--all-tags"));
    }
    @Test public void composePinsFileDirectoryProjectAndKeepsVolumes() throws Exception {
        Path root=Files.createTempDirectory("goforge-compose-test-");
        try {
            Path file=Files.writeString(root.resolve("compose space.yaml"),"services: {}\n");
            var request=DockerCommands.compose(root,file,"fixture-1",DockerCommands.Compose.DOWN);
            assertEquals(List.of("compose","--ansi","never","--project-directory",root.toRealPath().toString(),"--file",file.toRealPath().toString(),"--project-name","fixture-1","down"),request.arguments());
            assertTrue(request.mutation());
            assertFalse(DockerCommands.compose(root,file,"fixture-1",DockerCommands.Compose.PS).mutation());
            assertThrows(IllegalArgumentException.class,()->DockerCommands.compose(root,file,"--bad",DockerCommands.Compose.UP));
        } finally { try(var files=Files.walk(root)) { for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
    @Test public void buildAndComposeRejectEscapesIncludingSymlink() throws Exception {
        Path root=Files.createTempDirectory("goforge-docker-paths-"),outside=Files.createTempFile("goforge-outside-",".yaml");
        try {
            Path dockerfile=Files.writeString(root.resolve("Dockerfile"),"FROM scratch\n");
            var build=DockerCommands.build(root,root,dockerfile,"local/fixture:dev");
            assertEquals(root.toRealPath().toString(),build.arguments().getLast());
            assertThrows(IllegalArgumentException.class,()->DockerCommands.compose(root,outside,"fixture",DockerCommands.Compose.PS));
            Path link=Files.createSymbolicLink(root.resolve("linked.yaml"),outside);
            assertThrows(IllegalArgumentException.class,()->DockerCommands.compose(root,link,"fixture",DockerCommands.Compose.PS));
        } finally { try(var files=Files.walk(root)) { for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); } Files.delete(outside); }
    }
    @Test public void argumentJsonPreservesSpacesAndRejectsNonStringsAndControls() {
        assertEquals(List.of("echo","a b",""),DockerCommands.parseArguments("[\"echo\",\"a b\",\"\"]"));
        assertThrows(IllegalArgumentException.class,()->DockerCommands.parseArguments("[1]"));
        assertThrows(IllegalArgumentException.class,()->DockerCommands.exec(ID,List.of(),false));
        assertThrows(IllegalArgumentException.class,()->DockerCommands.exec(ID,List.of("echo","x\nfoo"),false));
    }
    @Test public void runImagePinsImageIdentityAndDoesNotSilentlyAddHostAccess() {
        var request=DockerCommands.runImage("sha256:"+ID,"new-fixture","127.0.0.1:8080:80",List.of("/app","a b"));
        assertEquals(List.of("container","run","--detach","--name","new-fixture","--publish","127.0.0.1:8080:80","--","sha256:"+ID,"/app","a b"),request.arguments());
        assertThrows(IllegalArgumentException.class,()->DockerCommands.runImage(ID,"--privileged","",List.of()));
        assertThrows(IllegalArgumentException.class,()->DockerCommands.runImage(ID,"fixture","-v /:/host",List.of()));
    }
    @Test public void terminalStartsDockerDirectlyWithTypedArgv() {
        assertEquals(List.of("container","exec","-it","--",ID,"/bin/sh"),DockerCommands.exec(ID,List.of("/bin/sh"),true).arguments());
    }
}
