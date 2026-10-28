package dev.goforge.containers;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit daemon integration fixture. Not part of ordinary tests and never targets existing resources. */
public final class DockerProtocolProbe {
    private final String docker, context, token, image, name;
    private final Path fixture, report;
    private final List<Map<String,Object>> checks=new ArrayList<>();
    private String containerId;
    private boolean composeCreated;
    private DockerProtocolProbe(String[] args) { docker=args[0]; context=args[1]; token=args[2]; fixture=Path.of(args[3]); report=Path.of(args[4]); image="goforge-fixture-"+token+":probe"; name="goforge-fixture-"+token; }
    public static void main(String[] args) throws Exception { new DockerProtocolProbe(args).run(); }
    private DockerClient.Result call(DockerCommands.Request request) throws Exception { return DockerClient.execute(docker,context,request,fixture,System.getenv(),()->false); }
    private DockerClient.Result ok(DockerCommands.Request request) throws Exception { var r=call(request); if(!r.succeeded())throw new IllegalStateException(r.output()); return r; }
    private void check(String name,boolean condition) { checks.add(Map.of("name",name,"passed",condition)); if(!condition)throw new AssertionError(name); }
    private DockerCommands.Request raw(String title,String... args) { return new DockerCommands.Request(title,List.of(args),Duration.ofSeconds(30),true); }
    private void run() throws Exception {
        String error=null; var cleanup=new ArrayList<String>();
        try {
            var contexts=DockerData.contexts(ok(DockerCommands.contexts()).requiredJson()); check("selected_context_exists_without_changing_default",contexts.stream().anyMatch(c->c.name().equals(context)));
            String connection=DockerData.connection(ok(DockerCommands.version()).requiredJson()); check("daemon_connection",connection.contains("Connected"));
            var before=DockerData.containers(ok(DockerCommands.containers()).requiredJson());
            var built=ok(DockerCommands.build(fixture,fixture,fixture.resolve("Dockerfile"),image)); check("scratch_image_build",built.succeeded());
            var images=DockerData.images(ok(DockerCommands.images()).requiredJson());
            var ownImage=images.stream().filter(i->i.repository().equals(image.substring(0,image.indexOf(':')))&&i.tag().equals("probe")).findFirst().orElseThrow();
            var imageDetails=JsonParser.parseString(ok(DockerCommands.inspectImage(ownImage.id())).requiredJson()).getAsJsonArray().get(0).getAsJsonObject();
            check("image_identity_and_ownership_label",imageDetails.getAsJsonObject("Config").getAsJsonObject("Labels").get("dev.goforge.fixture").getAsString().equals(token));
            containerId=ok(raw("Create isolated fixture","container","create","--name",name,"--label","dev.goforge.fixture="+token,"--network","none",image)).process().stdout().trim();
            check("created_only_new_container",before.stream().noneMatch(c->c.id().equals(containerId)));
            ok(DockerCommands.lifecycle(DockerCommands.Lifecycle.START,containerId));
            var listed=DockerData.containers(ok(DockerCommands.containers()).requiredJson());
            check("start_and_container_inventory",listed.stream().anyMatch(c->c.id().equals(containerId)&&c.state().equals("running")));
            var inspect=JsonParser.parseString(ok(DockerCommands.inspectContainer(containerId)).requiredJson()).getAsJsonArray().get(0).getAsJsonObject();
            check("container_details",inspect.getAsJsonObject("Config").getAsJsonObject("Labels").get("dev.goforge.fixture").getAsString().equals(token));
            var exec=ok(DockerCommands.exec(containerId,List.of("/probe","echo","literal ; $(touch /host-injection)"),false));
            check("exec_literal_arguments",exec.process().stdout().contains("literal ; $(touch /host-injection)"));
            var logs=ok(DockerCommands.logs(containerId)); check("real_container_logs",logs.process().stdout().contains("fixture-ready"));
            var cancellation=new AtomicBoolean(); var trigger=new Thread(()->{try{Thread.sleep(250);}catch(InterruptedException ignored){}cancellation.set(true);}); trigger.start();
            var follow=new DockerCommands.Request("Cancelable log follow",List.of("container","logs","--follow","--tail","0",containerId),Duration.ofSeconds(10),false);
            var cancelled=DockerClient.execute(docker,context,follow,fixture,System.getenv(),cancellation::get); trigger.join();
            check("cancel_stops_owned_cli",cancelled.process().cancelled()&&cancelled.process().knownProcessesStopped()&&!cancelled.succeeded());
            var timed=DockerClient.execute(docker,context,new DockerCommands.Request("Timed log follow",follow.arguments(),Duration.ofMillis(250),false),fixture,System.getenv(),()->false);
            check("timeout_stops_owned_cli",timed.process().timedOut()&&timed.process().knownProcessesStopped());
            ok(DockerCommands.lifecycle(DockerCommands.Lifecycle.STOP,containerId));
            check("stop_state",DockerData.containers(ok(DockerCommands.containers()).requiredJson()).stream().anyMatch(c->c.id().equals(containerId)&&c.state().equals("exited")));
            ok(DockerCommands.lifecycle(DockerCommands.Lifecycle.RESTART,containerId));
            check("restart_state",DockerData.containers(ok(DockerCommands.containers()).requiredJson()).stream().anyMatch(c->c.id().equals(containerId)&&c.state().equals("running")));
            ok(DockerCommands.lifecycle(DockerCommands.Lifecycle.STOP,containerId)); ok(DockerCommands.lifecycle(DockerCommands.Lifecycle.REMOVE,containerId));
            check("remove_selected_container",DockerData.containers(ok(DockerCommands.containers()).requiredJson()).stream().noneMatch(c->c.id().equals(containerId))); containerId=null;
            Path compose=fixture.resolve("compose.yaml");
            Files.writeString(compose,"services:\n  probe:\n    image: "+image+"\n    pull_policy: never\n    network_mode: none\n    labels:\n      dev.goforge.fixture: "+token+"\n");
            composeCreated=true; ok(DockerCommands.compose(fixture,compose,name,DockerCommands.Compose.UP));
            var composePs=DockerData.objects(ok(DockerCommands.compose(fixture,compose,name,DockerCommands.Compose.PS)).requiredJson());
            check("compose_up_and_ps",composePs.size()==1&&composePs.getFirst().get("State").getAsString().equals("running"));
            ok(DockerCommands.compose(fixture,compose,name,DockerCommands.Compose.DOWN)); composeCreated=false;
            check("compose_down",DockerData.objects(ok(DockerCommands.compose(fixture,compose,name,DockerCommands.Compose.PS)).requiredJson()).isEmpty());
            var failed=call(DockerCommands.inspectContainer("f".repeat(64))); check("real_daemon_error_retained",!failed.succeeded()&&!failed.process().stderr().isBlank());
            ok(DockerCommands.removeImage(image)); check("remove_own_image",DockerData.images(ok(DockerCommands.images()).requiredJson()).stream().noneMatch(i->i.repository().equals("goforge-fixture-"+token)));
        } catch(Throwable failure) { error=failure.toString(); }
        finally {
            if(composeCreated) try { ok(DockerCommands.compose(fixture,fixture.resolve("compose.yaml"),name,DockerCommands.Compose.DOWN)); } catch(Exception e) { cleanup.add(e.toString()); }
            if(containerId!=null) try { var d=JsonParser.parseString(ok(DockerCommands.inspectContainer(containerId)).requiredJson()).getAsJsonArray().get(0).getAsJsonObject(); if(d.getAsJsonObject("Config").getAsJsonObject("Labels").get("dev.goforge.fixture").getAsString().equals(token)) { call(DockerCommands.lifecycle(DockerCommands.Lifecycle.STOP,containerId)); ok(DockerCommands.lifecycle(DockerCommands.Lifecycle.REMOVE,containerId)); } } catch(Exception e) { cleanup.add(e.toString()); }
            try { var images=DockerData.images(ok(DockerCommands.images()).requiredJson()); if(images.stream().anyMatch(i->i.repository().equals("goforge-fixture-"+token))) ok(DockerCommands.removeImage(image)); } catch(Exception e) { cleanup.add(e.toString()); }
            try { String leftovers=ok(raw("Fixture leftovers","container","ls","--all","--quiet","--filter","label=dev.goforge.fixture="+token)).process().stdout().trim(); check("no_fixture_containers_left",leftovers.isEmpty()); } catch(Throwable e) { cleanup.add(e.toString()); }
            var data=new LinkedHashMap<String,Object>(); data.put("schema",1); data.put("time",Instant.now().toString()); data.put("context",context); data.put("fixture_label",token); data.put("checks",checks); data.put("error",error); data.put("cleanup_errors",cleanup); data.put("passed",error==null&&cleanup.isEmpty()); data.put("native_ui_verified",false); data.put("not_verified",List.of("image pull from external registry", "interactive IDE terminal", "remote Docker contexts", "Docker build cache may retain this fixture's layers; no global prune was performed"));
            Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(data));
        }
        if(error!=null||!cleanup.isEmpty())throw new AssertionError("Docker probe failed; see "+report+" "+error+" "+cleanup);
    }
}
