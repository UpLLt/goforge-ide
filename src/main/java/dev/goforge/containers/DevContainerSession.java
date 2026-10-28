package dev.goforge.containers;

import com.google.gson.*;
import dev.goforge.coverage.GoCoverageProcess;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Owns identities from before creation until publication or rollback, independently of Swing callbacks. */
public final class DevContainerSession {
    public record State(DevContainerPlan plan,String id,String docker,String context,Map<String,String> environment,
                        String imageId,String session,Path generated,DevContainerImage.OwnedImage image) { }
    public record Started(State state,String output) { }
    public record Cleanup(boolean complete,String output) { }
    public static final class Attempt {
        final DevContainerPlan plan; final String docker,context,token;
        final Map<String,String> environment;
        final List<DevContainerImage.OwnedImage> images=new ArrayList<>();
        final Set<String> createdIds=new LinkedHashSet<>();
        volatile boolean upSubmitted,upSucceeded,finished,committed,cleaned;
        volatile State candidate; volatile Path generated;
        Attempt(DevContainerPlan plan,String docker,String context,Map<String,String> environment) {
            this.plan=plan;this.docker=docker;this.context=context;this.environment=Map.copyOf(environment);
            token=UUID.randomUUID().toString().replace("-","");
        }
    }
    private State active;
    private final Set<Attempt> pending=new LinkedHashSet<>();
    public synchronized State state() { return active; }
    public synchronized boolean hasResources() { return active!=null||!pending.isEmpty(); }
    public synchronized String description() {
        var lines=new ArrayList<String>();
        if(active!=null) lines.add("Container "+active.id()+" (context "+active.context()+")");
        for(var a:pending) lines.add("Uncommitted attempt "+a.token+"; containers "+a.createdIds+"; tags "+a.images.stream().filter(i->!i.released).map(i->i.tag).toList());
        return String.join("\n",lines);
    }
    public synchronized Attempt begin(DevContainerPlan plan,String docker,String context,Map<String,String> environment) {
        if(!pending.isEmpty()) throw new IllegalStateException("A previous attempt needs cleanup. Use Remove to retry only its recorded resources.\n"+description());
        if(active!=null&&(!active.plan().sourceHash().equals(plan.sourceHash())||!active.plan().owner().equals(plan.owner())
                ||!active.docker().equals(docker)||!active.context().equals(context)))
            throw new IllegalStateException("Remove the current session before selecting a different configuration, Docker executable or context.");
        var attempt=new Attempt(plan,docker,context,environment);pending.add(attempt);return attempt;
    }
    public Started start(Attempt attempt,String node,Path cli,Path generated,BooleanSupplier cancelled)throws Exception {
        try {
            attempt.plan.requireUnchanged(); attempt.generated=generated;
            if(cancelled.getAsBoolean()) throw new IllegalStateException("Workspace start cancelled before creation.");
            State reuse=state();
            if(reuse!=null) {
                // A stopped session retains its immutable image and tag. Starting never builds another tag.
                DevContainerImage.inspectContainer(reuse.plan(),reuse.imageId(),reuse.docker(),reuse.context(),reuse.id(),reuse.environment(),cancelled);
                var result=execute(attempt,DockerCommands.lifecycle(DockerCommands.Lifecycle.START,reuse.id()),cancelled);
                if(!result.succeeded()) throw new IllegalStateException(result.output());
                attempt.candidate=reuse;
                return new Started(reuse,"Reused verified workspace and original image tag.\n"+result.output());
            }
            if(JsonParser.parseString(attempt.plan.configuration()).getAsJsonObject().has("build")) {
                var recovered=recoverBuild(attempt,cancelled);
                if(recovered!=null) {
                    var result=execute(attempt,DockerCommands.lifecycle(DockerCommands.Lifecycle.START,recovered.id()),cancelled);
                    if(!result.succeeded()) throw new IllegalStateException(result.output());
                    attempt.candidate=recovered;
                    return new Started(recovered,"Reconnected verified build workspace from its plan and image ownership labels. Rebuild requires Remove, then Start.\n"+result.output());
                }
            }
            var prepared=DevContainerImage.prepare(attempt.plan,attempt.docker,attempt.context,attempt.environment,cancelled,attempt.token,attempt.images::add);
            var configuration=JsonParser.parseString(prepared.configuration()).getAsJsonObject();
            configuration.getAsJsonArray("runArgs").add("--label");configuration.getAsJsonArray("runArgs").add("dev.goforge.plan="+attempt.plan.sourceHash());
            Files.createDirectories(generated.getParent());Files.writeString(generated,new GsonBuilder().setPrettyPrinting().create().toJson(configuration));
            String id=prepared.existingContainerId(),log;
            if(id!=null) {
                var result=execute(attempt,DockerCommands.lifecycle(DockerCommands.Lifecycle.START,id),cancelled);
                if(!result.succeeded()) throw new IllegalStateException(result.output());
                log=result.output();
            } else {
                if(cancelled.getAsBoolean()) throw new IllegalStateException("Workspace start cancelled before container creation.");
                attempt.upSubmitted=true;
                var result=GoCoverageProcess.run(attempt.plan.up(node,cli,attempt.docker,generated,attempt.token),attempt.plan.workspace(),attempt.environment,cancelled,Duration.ofMinutes(20));
                log="Exit "+result.exitCode()+"\n"+result.stdout()+"\n"+result.stderr();
                if(!Integer.valueOf(0).equals(result.exitCode())||result.cancelled()||result.timedOut()||result.outputTruncated()) throw new IllegalStateException(log);
                id=containerId(result.stdout());attempt.upSucceeded=true;
            }
            JsonObject container=container(attempt,id,cancelled);
            if(attempt.upSubmitted) recordCreatedContainer(attempt,id,prepared.imageId(),container);
            DevContainerImage.verifyContainer(attempt.plan,prepared.imageId(),container);
            String session=label(container,"dev.goforge.session");
            if(session.isBlank()) throw new IllegalStateException("Container has no session identity; refusing to adopt it.");
            var image=attempt.images.isEmpty()?null:attempt.images.getFirst();
            attempt.candidate=new State(attempt.plan,id,attempt.docker,attempt.context,attempt.environment,prepared.imageId(),session,generated,image);
            return new Started(attempt.candidate,"Verified immutable base image: "+prepared.imageId()+"\n"+log);
        } catch(Exception failure) {
            attempt.finished=true;
            var cleanup=abandon(attempt);
            throw new IllegalStateException((failure.getMessage()==null?failure.toString():failure.getMessage())+"\n\n"+cleanup.output(),failure);
        } finally { attempt.finished=true; }
    }
    private State recoverBuild(Attempt attempt,BooleanSupplier cancelled)throws Exception {
        var request=new DockerCommands.Request("Find previous build workspace",List.of("container","ls","--all","--quiet","--no-trunc","--filter","label=dev.goforge.workspace="+attempt.plan.owner()),Duration.ofSeconds(30),false);
        var ids=execute(attempt,request,cancelled).requiredJson().lines().filter(line->!line.isBlank()).toList();
        if(ids.isEmpty()) return null;
        if(ids.size()!=1) throw new IllegalStateException("Multiple containers match this workspace; select them explicitly in the Docker tab.");
        String id=ids.getFirst();var value=container(attempt,id,cancelled);String session=label(value,"dev.goforge.session");
        if(!attempt.plan.sourceHash().equals(label(value,"dev.goforge.plan"))||!session.matches("[a-f0-9]{32}"))
            throw new IllegalStateException("Existing build workspace has no matching recorded plan/session. It was not changed and no new image was built. Manage this legacy or changed workspace explicitly in the Docker tab, or choose another suffix.");
        String imageId=value.get("Image").getAsString();
        if(!matchesOwnedContainer(attempt.plan,value,id,session,imageId)) throw new IllegalStateException("Existing build workspace identity does not match the selected plan.");
        DevContainerImage.verifyContainer(attempt.plan,imageId,value);
        var imageResult=execute(attempt,new DockerCommands.Request("Validate original build image ownership",List.of("image","inspect","--",imageId),Duration.ofSeconds(30),false),cancelled);
        var image=JsonParser.parseString(imageResult.requiredJson()).getAsJsonArray().get(0).getAsJsonObject();
        if(!imageId.equals(DevContainerImage.verify(image))) throw new IllegalStateException("Existing image identity changed.");
        var lease=new DevContainerImage.OwnedImage("goforge-dev-base/"+attempt.plan.owner().substring(0,16)+":"+session,attempt.plan.owner(),session);
        lease.imageId=imageId;lease.buildCompleted=true;
        if(!DevContainerImage.matchesOwnedImage(lease,image)) throw new IllegalStateException("Existing build image owner/session labels do not match; no image ownership was adopted.");
        boolean tagged=image.has("RepoTags")&&image.get("RepoTags").isJsonArray()&&
                java.util.stream.StreamSupport.stream(image.getAsJsonArray("RepoTags").spliterator(),false).anyMatch(tag->lease.tag.equals(tag.getAsString()));
        return new State(attempt.plan,id,attempt.docker,attempt.context,attempt.environment,imageId,session,attempt.generated,tagged?lease:null);
    }
    static void recordCreatedContainer(Attempt attempt,String id,String imageId,JsonObject container) {
        if(!matchesOwnedContainer(attempt.plan,container,id,attempt.token,imageId))
            throw new IllegalStateException("New container identity does not match this exact creation attempt; only matching labels may be cleaned.");
        attempt.createdIds.add(id);
    }
    public synchronized void commit(Attempt attempt) {
        if(!attempt.finished||attempt.cleaned||attempt.candidate==null||!pending.contains(attempt)) throw new IllegalStateException("Workspace result is no longer available for publication.");
        active=attempt.candidate;attempt.committed=true;pending.remove(attempt);
    }
    public Cleanup abandon(Attempt attempt) {
        synchronized(attempt) {
            if(attempt.committed) return new Cleanup(true,"Workspace was already transferred to the active session.");
            if(attempt.cleaned) return new Cleanup(true,"Attempt resources already cleaned.");
            if(!attempt.finished) return new Cleanup(false,"Attempt is still running; cleanup will run after the worker finishes.");
            return cleanup(attempt,false);
        }
    }
    private Cleanup cleanup(Attempt attempt,boolean explicitRetry) {
        var log=new ArrayList<String>(); boolean complete=true;
        if(attempt.upSubmitted) {
            try {
                var request=new DockerCommands.Request("Find only this uncommitted attempt",List.of("container","ls","--all","--quiet","--no-trunc",
                        "--filter","label=dev.goforge.workspace="+attempt.plan.owner(),"--filter","label=dev.goforge.session="+attempt.token),Duration.ofSeconds(30),false);
                var ids=execute(attempt,request,()->false).requiredJson().lines().filter(s->!s.isBlank()).toList();
                attempt.createdIds.addAll(ids);
                for(String id:List.copyOf(attempt.createdIds)) {
                    removeOwned(attempt,id,attempt.token,null,log);
                    attempt.createdIds.remove(id);
                }
                if(!attempt.upSucceeded&&ids.isEmpty()&&!explicitRetry) {
                    complete=false;log.add("No current container found. A cancelled/failed daemon request may still finish later; the attempt labels remain recorded. Use Remove to retry discovery.");
                }
            } catch(Exception e) { complete=false;log.add("Container cleanup incomplete: "+e.getMessage()); }
        }
        if(attempt.createdIds.isEmpty()) for(var image:attempt.images) {
            try { log.add(DevContainerImage.release(image,attempt.plan.workspace(),attempt.docker,attempt.context,attempt.environment,explicitRetry)); }
            catch(Exception e) { complete=false;log.add("Image tag retained: "+image.tag+"\n"+e.getMessage()); }
        }
        if(!attempt.createdIds.isEmpty()) complete=false;
        if(complete) { attempt.cleaned=true;synchronized(this){pending.remove(attempt);} }
        log.add(complete?"Uncommitted attempt cleanup completed.":"Cleanup is incomplete. Remove remains available for the recorded attempt only.");
        persist(attempt,complete,log);
        return new Cleanup(complete,String.join("\n",log));
    }
    public String stop(BooleanSupplier cancelled)throws Exception {
        var current=state();if(current==null) throw new IllegalStateException("No active workspace. Use Remove for an unfinished attempt.");
        DevContainerImage.inspectContainer(current.plan(),current.imageId(),current.docker(),current.context(),current.id(),current.environment(),cancelled);
        var result=DockerClient.execute(current.docker(),current.context(),DockerCommands.lifecycle(DockerCommands.Lifecycle.STOP,current.id()),current.plan().workspace(),current.environment(),cancelled);
        if(!result.succeeded()) throw new IllegalStateException(result.output());return result.output();
    }
    /** Successful removals update worker-owned state before a potentially cancelled UI callback. */
    public String remove(BooleanSupplier cancelled)throws Exception {
        var logs=new ArrayList<String>();
        var current=state();
        if(current!=null) {
            if(cancelled.getAsBoolean()) throw new IllegalStateException("Removal cancelled before changing resources.");
            var attempt=new Attempt(current.plan(),current.docker(),current.context(),current.environment());
            attempt.generated=current.generated();
            removeOwned(attempt,current.id(),current.session(),current.imageId(),logs);
            // Container is gone even if image deletion fails; retain a tag-only recovery attempt.
            synchronized(this) { if(active==current)active=null; }
            if(current.image()!=null&&!current.image().released) {
                attempt.images.add(current.image());attempt.finished=true;
                synchronized(this) { pending.add(attempt); }
                var clean=cleanup(attempt,true);logs.add(clean.output());
            }
        }
        List<Attempt> retries;synchronized(this){retries=List.copyOf(pending);}
        for(var attempt:retries) {
            if(!attempt.finished) throw new IllegalStateException("Workspace start is still running.");
            synchronized(attempt) { logs.add(cleanup(attempt,true).output()); }
        }
        return String.join("\n",logs)+(hasResources()?"\nSome recorded resources remain; retry Remove.":"\nWorkspace removed. Session state cleared; project files were retained.");
    }
    private void removeOwned(Attempt attempt,String id,String expectedSession,String imageId,List<String> log)throws Exception {
        var result=execute(attempt,DockerCommands.inspectContainer(id),()->false);
        if(!result.succeeded()) {
            if(result.process().stderr().contains("No such object")||result.process().stderr().contains("No such container")) {log.add("Container already absent: "+id);return;}
            throw new IllegalStateException(result.output());
        }
        var value=JsonParser.parseString(result.requiredJson()).getAsJsonArray().get(0).getAsJsonObject();
        if(!matchesOwnedContainer(attempt.plan,value,id,expectedSession,imageId)) throw new IllegalStateException("Refusing cleanup: container ID, owner, name, session or image identity changed: "+id);
        var removed=execute(attempt,new DockerCommands.Request("Remove this owned workspace container",List.of("container","rm","--force","--",id),Duration.ofSeconds(45),true),()->false);
        if(!removed.succeeded()) throw new IllegalStateException(removed.output());log.add(removed.output());
    }
    static boolean matchesOwnedContainer(DevContainerPlan plan,JsonObject container,String id,String session,String imageId) {
        return id.matches("[0-9a-f]{64}")&&container.has("Id")&&id.equals(container.get("Id").getAsString())
                &&container.has("Name")&&("/"+plan.name()).equals(container.get("Name").getAsString())
                &&plan.owner().equals(label(container,"dev.goforge.workspace"))&&!session.isBlank()&&session.equals(label(container,"dev.goforge.session"))
                &&(imageId==null||(container.has("Image")&&imageId.equals(container.get("Image").getAsString())));
    }
    static String containerId(String stdout) {
        var value=JsonParser.parseString(stdout).getAsJsonObject();
        String id=value.has("containerId")?value.get("containerId").getAsString():"";
        if(!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Official CLI returned no immutable container ID; attempt discovery is required.");
        return id;
    }
    private static String label(JsonObject container,String key) {
        var config=container.getAsJsonObject("Config");var labels=config==null?null:config.getAsJsonObject("Labels");
        return labels!=null&&labels.has(key)&&!labels.get(key).isJsonNull()?labels.get(key).getAsString():"";
    }
    private static JsonObject container(Attempt attempt,String id,BooleanSupplier cancelled)throws Exception {
        return JsonParser.parseString(execute(attempt,DockerCommands.inspectContainer(id),cancelled).requiredJson()).getAsJsonArray().get(0).getAsJsonObject();
    }
    private static DockerClient.Result execute(Attempt attempt,DockerCommands.Request request,BooleanSupplier cancelled)throws Exception {
        return DockerClient.execute(attempt.docker,attempt.context,request,attempt.plan.workspace(),attempt.environment,cancelled);
    }
    private static void persist(Attempt attempt,boolean complete,List<String> log) {
        if(attempt.generated==null) return;
        try {
            var record=new LinkedHashMap<String,Object>();record.put("owner",attempt.plan.owner());record.put("attempt",attempt.token);record.put("docker_context",attempt.context);
            record.put("complete",complete);record.put("container_ids",attempt.createdIds);record.put("image_tags",attempt.images.stream().filter(i->!i.released).map(i->i.tag).toList());record.put("output",log);
            Files.createDirectories(attempt.generated.getParent());Files.writeString(attempt.generated.resolveSibling("cleanup-"+attempt.token+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(record));
        } catch(Exception e) { log.add("Could not write cleanup recovery record: "+e); }
    }
}
