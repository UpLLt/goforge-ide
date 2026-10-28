package dev.goforge.containers;

import com.google.gson.*;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class DevContainerSessionTest {
    private static final String ID="a".repeat(64),IMAGE="sha256:"+"b".repeat(64),TOKEN="c".repeat(32);
    private DevContainerPlan plan(){return new DevContainerPlan(Path.of("/fixture"),Path.of("/fixture/devcontainer.json"),"hash","owner","goforge-dev-fixture","/workspaces/fixture","{}","{}","null",List.of());}
    private JsonObject container(){
        var value=new JsonObject();value.addProperty("Id",ID);value.addProperty("Name","/goforge-dev-fixture");value.addProperty("Image",IMAGE);
        var config=new JsonObject();var labels=new JsonObject();labels.addProperty("dev.goforge.workspace","owner");labels.addProperty("dev.goforge.session",TOKEN);config.add("Labels",labels);value.add("Config",config);return value;
    }
    @Test public void rollbackUsesCreationIdentityEvenWhenSafetyVerificationFailed(){
        var value=container();value.getAsJsonObject("Config").getAsJsonObject("Labels").addProperty("devcontainer.metadata","[{\"privileged\":true}]");
        assertTrue(DevContainerSession.matchesOwnedContainer(plan(),value,ID,TOKEN,null));
    }
    @Test public void refusesContainerWithReplacedIdentityOwnerSessionNameOrImage(){
        for(String field:List.of("Id","Name","Image")){var value=container();value.addProperty(field,"different");assertFalse(field,DevContainerSession.matchesOwnedContainer(plan(),value,ID,TOKEN,IMAGE));}
        for(String field:List.of("dev.goforge.workspace","dev.goforge.session")){var value=container();value.getAsJsonObject("Config").getAsJsonObject("Labels").addProperty(field,"different");assertFalse(field,DevContainerSession.matchesOwnedContainer(plan(),value,ID,TOKEN,IMAGE));}
        assertFalse(DevContainerSession.matchesOwnedContainer(plan(),container(),"a",TOKEN,null));
    }
    @Test public void foreignReturnedSessionIsNotRecordedForRollback(){
        var session=new DevContainerSession();var attempt=session.begin(plan(),"docker","test",Map.of());
        try{DevContainerSession.recordCreatedContainer(attempt,ID,IMAGE,container());fail();}catch(IllegalStateException expected){}
        assertTrue(attempt.createdIds.isEmpty());
    }
    @Test public void verifiedCreationIdentityIsRecordedBeforeSafetyPlanChecks(){
        var session=new DevContainerSession();var attempt=session.begin(plan(),"docker","test",Map.of());var value=container();
        value.getAsJsonObject("Config").getAsJsonObject("Labels").addProperty("dev.goforge.session",attempt.token);
        value.getAsJsonObject("Config").getAsJsonObject("Labels").addProperty("devcontainer.metadata","[{\"privileged\":true}]");
        DevContainerSession.recordCreatedContainer(attempt,ID,IMAGE,value);assertEquals(Set.of(ID),attempt.createdIds);
    }
    @Test public void imageTagReleaseRequiresOwnerSessionAndImmutableIdentity(){
        var lease=new DevContainerImage.OwnedImage("goforge-dev-base/owner:"+TOKEN,"owner",TOKEN);lease.imageId=IMAGE;
        var value=container();value.addProperty("Id",IMAGE);assertTrue(DevContainerImage.matchesOwnedImage(lease,value));
        value.addProperty("Id","sha256:"+"d".repeat(64));assertFalse(DevContainerImage.matchesOwnedImage(lease,value));
        value.addProperty("Id",IMAGE);value.getAsJsonObject("Config").getAsJsonObject("Labels").addProperty("dev.goforge.session","other");assertFalse(DevContainerImage.matchesOwnedImage(lease,value));
        value.getAsJsonObject("Config").getAsJsonObject("Labels").addProperty("dev.goforge.session",TOKEN);value.getAsJsonObject("Config").getAsJsonObject("Labels").remove("dev.goforge.workspace");assertFalse(DevContainerImage.matchesOwnedImage(lease,value));
    }
    @Test public void failedBuildCanDiscoverOnlyItsOwnSessionImage(){
        var lease=new DevContainerImage.OwnedImage("goforge-dev-base/owner:"+TOKEN,"owner",TOKEN);
        var value=container();value.addProperty("Id",IMAGE);assertTrue(DevContainerImage.matchesOwnedImage(lease,value));
        value.getAsJsonObject("Config").getAsJsonObject("Labels").addProperty("dev.goforge.workspace","external");assertFalse(DevContainerImage.matchesOwnedImage(lease,value));
    }
    @Test public void cliOutputNeedsCompleteImmutableContainerIdentity(){
        assertEquals(ID,DevContainerSession.containerId("{\"outcome\":\"success\",\"containerId\":\""+ID+"\"}\n"));
        for(String text:List.of("{}","{\"containerId\":\"name\"}","{\"containerId\":\"--all\"}","log\n{}")){
            try{DevContainerSession.containerId(text);fail(text);}catch(RuntimeException expected){}
        }
    }
    @Test public void attemptIdentityIsFixedBeforeCallingReferenceCli(){
        var command=plan().up("node",Path.of("cli.js"),"docker",Path.of("devcontainer.json"),TOKEN);
        assertTrue(command.contains("dev.goforge.session="+TOKEN));
        try{plan().up("node",Path.of("cli.js"),"docker",Path.of("devcontainer.json"),"--bad");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void unstartedAttemptHasNoResourceCleanupAndDoesNotPublish(){
        var session=new DevContainerSession();var attempt=session.begin(plan(),"docker","test",Map.of());
        assertTrue(session.hasResources());assertNull(session.state());assertFalse(session.abandon(attempt).complete());
        attempt.finished=true;assertTrue(session.abandon(attempt).complete());assertFalse(session.hasResources());
        assertTrue(session.abandon(attempt).complete());
        try{session.commit(attempt);fail();}catch(IllegalStateException expected){}
    }
}
