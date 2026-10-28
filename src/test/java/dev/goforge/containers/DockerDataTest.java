package dev.goforge.containers;

import dev.goforge.coverage.GoCoverageProcess;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DockerDataTest {
    @Test public void contextsAcceptBooleanAndRetainConnectionErrors() {
        var contexts=DockerData.contexts("{\"Name\":\"local\",\"Current\":true,\"DockerEndpoint\":\"unix:///socket\"}\n{\"Name\":\"remote\",\"Error\":\"unreachable\"}");
        assertEquals(2,contexts.size()); assertTrue(contexts.getFirst().current()); assertEquals("unreachable",contexts.getLast().error());
    }
    @Test public void inventoryHandlesArraysLinesAndEmptyDaemon() {
        String row="{\"ID\":\"abc\",\"Names\":\"fixture\",\"State\":\"exited\",\"Ports\":\"127.0.0.1:80->80/tcp\"}";
        assertEquals(DockerData.containers(row),DockerData.containers("["+row+"]"));
        assertEquals("exited",DockerData.containers(row).getFirst().state()); assertTrue(DockerData.containers("").isEmpty());
        assertTrue(DockerData.images("[]").isEmpty());
    }
    @Test public void malformedAndMissingIdentityCannotBecomeEmptySuccess() {
        assertThrows(RuntimeException.class,()->DockerData.containers("not json"));
        assertThrows(IllegalArgumentException.class,()->DockerData.containers("{\"Names\":\"fixture\"}"));
        assertThrows(IllegalArgumentException.class,()->DockerData.connection("{\"Client\":{}}"));
        assertThrows(IllegalArgumentException.class,()->DockerData.objects("null"));
    }
    @Test public void imageRemovalUsesSelectedTagAndDanglingIdentity() {
        assertEquals("repo:tag",DockerCommands.imageReference(new DockerData.Image("sha256:abc","repo","tag","1MB","now")));
        assertEquals("sha256:abc",DockerCommands.imageReference(new DockerData.Image("sha256:abc","<none>","<none>","1MB","now")));
    }
    @Test public void cancelledFailedAndTruncatedResultsNeverBecomeCompleteInventory() {
        for (var process : List.of(new GoCoverageProcess.Result(null,true,false,"partial","",false,true),
                new GoCoverageProcess.Result(1,false,false,"","real daemon error",false,true),
                new GoCoverageProcess.Result(0,false,true,"partial","",false,true),
                new GoCoverageProcess.Result(0,false,false,"{}","",true,true))) {
            var result=new DockerClient.Result(DockerCommands.containers(),List.of("docker"),process);
            assertThrows(java.io.IOException.class,result::requiredJson);
            assertTrue(result.output().contains(result.status()));
        }
    }
}
