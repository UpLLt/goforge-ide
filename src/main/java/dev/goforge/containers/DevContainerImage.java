package dev.goforge.containers;

import com.google.gson.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Verifies the final image, including Dockerfile inheritance, before the reference CLI may create a container. */
public final class DevContainerImage {
    public record Prepared(String configuration,String imageId,String ownedImageTag,String existingContainerId){}
    private DevContainerImage(){}
    /** A tag is registered before build starts; its labels and immutable ID gate every deletion. */
    public static final class OwnedImage {
        public final String tag, owner, session;
        public volatile String imageId;
        public volatile boolean released, buildCompleted;
        OwnedImage(String tag,String owner,String session) { this.tag=tag;this.owner=owner;this.session=session; }
    }
    public static Prepared prepare(DevContainerPlan plan,String docker,String context,Map<String,String> environment,BooleanSupplier cancelled)throws Exception {
        var leases=new ArrayList<OwnedImage>();
        try { return prepare(plan,docker,context,environment,cancelled,UUID.randomUUID().toString().replace("-",""),leases::add); }
        catch(Exception failure) {
            for(var lease:leases) try { release(lease,plan.workspace(),docker,context,environment); }
            catch(Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    static Prepared prepare(DevContainerPlan plan,String docker,String context,Map<String,String> environment,
                            BooleanSupplier cancelled,String session,java.util.function.Consumer<OwnedImage> registered)throws Exception {
        plan.requireUnchanged();
        var config=JsonParser.parseString(plan.configuration()).getAsJsonObject();
        String reference; OwnedImage owned=null;
        if(config.has("build")) {
            var build=config.getAsJsonObject("build");
            owned=new OwnedImage("goforge-dev-base/"+plan.owner().substring(0,16)+":"+session,plan.owner(),session);
            registered.accept(owned);
            var args=new ArrayList<>(List.of("build","--progress","plain","--label","dev.goforge.workspace="+plan.owner(),
                    "--label","dev.goforge.session="+session,"--tag",owned.tag,"--file",build.get("dockerfile").getAsString()));
            if(build.has("args")) for(var entry:build.getAsJsonObject("args").entrySet()) {
                if(!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid Docker build argument name.");
                args.add("--build-arg");args.add(entry.getKey()+"="+entry.getValue().getAsString());
            }
            args.add("--");args.add(build.get("context").getAsString());
            var built=DockerClient.execute(docker,context,new DockerCommands.Request("Build workspace base image",args,Duration.ofMinutes(20),true),plan.workspace(),environment,cancelled);
            if(!built.succeeded()) throw new IllegalStateException(built.output());
            owned.buildCompleted=true;
            reference=owned.tag;
        } else reference=config.get("image").getAsString();
        var inspected=inspect(docker,context,reference,plan.workspace(),environment,cancelled);
        if(!inspected.succeeded()&&owned==null&&!cancelled.getAsBoolean()) {
            var pull=DockerClient.execute(docker,context,DockerCommands.pull(reference),plan.workspace(),environment,cancelled);
            if(!pull.succeeded()) throw new IllegalStateException(pull.output());
            inspected=inspect(docker,context,reference,plan.workspace(),environment,cancelled);
        }
        var json=JsonParser.parseString(inspected.requiredJson()).getAsJsonArray().get(0).getAsJsonObject();
        if(owned!=null) owned.imageId=json.get("Id").getAsString();
        String id=verify(json);config.remove("build");config.addProperty("image",id);
        String existing=existing(plan,id,docker,context,environment,cancelled);
        return new Prepared(new GsonBuilder().setPrettyPrinting().create().toJson(config),id,owned==null?null:owned.tag,existing);
    }
    static boolean matchesOwnedImage(OwnedImage lease,JsonObject image) {
        String id=image.has("Id")?image.get("Id").getAsString():"";
        if(!id.matches("sha256:[0-9a-f]{64}")||(lease.imageId!=null&&!lease.imageId.equals(id))) return false;
        var config=image.getAsJsonObject("Config");
        var labels=config==null?null:config.getAsJsonObject("Labels");
        return labels!=null&&labels.has("dev.goforge.workspace")&&labels.has("dev.goforge.session")
                &&lease.owner.equals(labels.get("dev.goforge.workspace").getAsString())
                &&lease.session.equals(labels.get("dev.goforge.session").getAsString());
    }
    /** Removes only the exact tag, never the image ID or unrelated tags sharing its layers. */
    static String release(OwnedImage lease,Path directory,String docker,String context,Map<String,String> environment)throws Exception {
        return release(lease,directory,docker,context,environment,false);
    }
    static String release(OwnedImage lease,Path directory,String docker,String context,Map<String,String> environment,boolean explicitRetry)throws Exception {
        if(lease.released) return "Owned image tag already released: "+lease.tag;
        var found=inspect(docker,context,lease.tag,directory,environment,()->false);
        if(!found.succeeded()) {
            if(found.process().stderr().contains("No such image")||found.process().stderr().contains("No such object")) {
                if(!lease.buildCompleted&&lease.imageId==null&&!explicitRetry) throw new IllegalStateException("Build did not complete; tag is currently absent but daemon work may continue. Retaining identity for explicit Remove retry: "+lease.tag);
                lease.released=true;return "Owned image tag is absent: "+lease.tag;
            }
            throw new IllegalStateException(found.output());
        }
        var image=JsonParser.parseString(found.requiredJson()).getAsJsonArray().get(0).getAsJsonObject();
        if(!matchesOwnedImage(lease,image)) throw new IllegalStateException("Refusing image cleanup: tag identity/owner/session changed: "+lease.tag);
        lease.imageId=image.get("Id").getAsString();
        var removed=DockerClient.execute(docker,context,DockerCommands.removeImage(lease.tag),directory,environment,()->false);
        if(!removed.succeeded()) throw new IllegalStateException(removed.output());
        lease.released=true;return removed.output();
    }
    private static String existing(DevContainerPlan plan,String imageId,String docker,String context,Map<String,String> env,BooleanSupplier cancelled)throws Exception{
        var request=new DockerCommands.Request("Find this workspace container",List.of("container","ls","--all","--quiet","--no-trunc","--filter","label=dev.goforge.workspace="+plan.owner()),Duration.ofSeconds(30),false);
        var listed=DockerClient.execute(docker,context,request,plan.workspace(),env,cancelled);var ids=listed.requiredJson().lines().filter(line->!line.isBlank()).toList();if(ids.size()>1)throw new IllegalStateException("Multiple containers match this workspace. Resolve them explicitly in the Docker tab.");
        if(ids.isEmpty())return null;inspectContainer(plan,imageId,docker,context,ids.getFirst(),env,cancelled);return ids.getFirst();
    }
    public static void inspectContainer(DevContainerPlan plan,String imageId,String docker,String context,String id,Map<String,String> env,BooleanSupplier cancelled)throws Exception{
        var inspected=DockerClient.execute(docker,context,DockerCommands.inspectContainer(id),plan.workspace(),env,cancelled);var container=JsonParser.parseString(inspected.requiredJson()).getAsJsonArray().get(0).getAsJsonObject();verifyContainer(plan,imageId,container);
    }
    public static void verifyContainer(DevContainerPlan plan,String imageId,JsonObject container){
        if(!imageId.equals(container.get("Image").getAsString()))throw new IllegalStateException("Existing workspace uses a different image. Remove it explicitly in the Docker tab or choose another name suffix.");
        if(!("/"+plan.name()).equals(container.get("Name").getAsString()))throw new IllegalStateException("Existing workspace container name does not match the approved plan.");
        var config=container.getAsJsonObject("Config");var labels=config.getAsJsonObject("Labels");if(labels==null||!labels.has("dev.goforge.workspace")||!plan.owner().equals(labels.get("dev.goforge.workspace").getAsString()))throw new IllegalStateException("Container ownership does not match this workspace.");
        verifyContainerMetadata(plan,labels);
        var host=container.getAsJsonObject("HostConfig");if(host.has("Privileged")&&host.get("Privileged").getAsBoolean())throw new IllegalStateException("Basic workspace cannot reuse a privileged container.");
        for(String field:List.of("CapAdd","SecurityOpt","Devices","DeviceRequests","VolumesFrom"))if(host.has(field)&&host.get(field).isJsonArray()&&!host.getAsJsonArray(field).isEmpty())throw new IllegalStateException("Basic workspace refuses custom host permissions: "+field);
        for(String field:List.of("NetworkMode","PidMode","IpcMode"))if(host.has(field)&&"host".equals(host.get(field).getAsString()))throw new IllegalStateException("Basic workspace refuses host namespaces.");
        int workspaceBinds=0;for(var item:container.getAsJsonArray("Mounts")){var mount=item.getAsJsonObject();if("bind".equals(mount.get("Type").getAsString())){if(!plan.workspace().toString().equals(mount.get("Source").getAsString())||!plan.workspaceFolder().equals(mount.get("Destination").getAsString()))throw new IllegalStateException("Existing container has an unapproved host bind mount.");if(mount.has("RW")&&!mount.get("RW").getAsBoolean())throw new IllegalStateException("Existing workspace bind is read-only.");workspaceBinds++;}}
        if(workspaceBinds!=1)throw new IllegalStateException("Expected exactly the approved workspace host bind mount.");
    }
    private static void verifyContainerMetadata(DevContainerPlan plan,JsonObject labels){
        var label=labels.get("devcontainer.metadata");if(label==null||label.isJsonNull())return;JsonElement metadata;try{metadata=JsonParser.parseString(label.getAsString());}catch(RuntimeException invalid){throw new IllegalArgumentException("Malformed container devcontainer.metadata",invalid);}
        if(!metadata.isJsonArray())throw new IllegalArgumentException("Unapproved container Dev Container metadata.");
        var approved=JsonParser.parseString(plan.configuration()).getAsJsonObject();var safe=Set.of("userEnvProbe","overrideCommand","updateRemoteUserUID","remoteUser","containerUser");
        for(var entry:metadata.getAsJsonArray()){if(!entry.isJsonObject())throw new IllegalArgumentException("Unapproved container Dev Container metadata.");for(var property:entry.getAsJsonObject().entrySet())if(!safe.contains(property.getKey())||!approved.has(property.getKey())||!approved.get(property.getKey()).equals(property.getValue()))throw new IllegalArgumentException("Container metadata differs from the approved basic plan: "+property.getKey());}
    }
    private static DockerClient.Result inspect(String docker,String context,String reference,Path directory,Map<String,String> env,BooleanSupplier cancelled)throws Exception{
        return DockerClient.execute(docker,context,new DockerCommands.Request("Inspect workspace base image",List.of("image","inspect","--",reference),Duration.ofSeconds(30),false),directory,env,cancelled);
    }
    public static String verify(JsonObject image){
        String id=image.get("Id").getAsString();if(!id.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("Docker returned no immutable image identity.");
        var config=image.getAsJsonObject("Config");if(config!=null&&config.has("Labels")&&config.get("Labels").isJsonObject()){
            var label=config.getAsJsonObject("Labels").get("devcontainer.metadata");
            if(label!=null&&!label.isJsonNull()){
                JsonElement metadata;try{metadata=JsonParser.parseString(label.getAsString());}catch(RuntimeException invalid){throw new IllegalArgumentException("Image devcontainer.metadata is malformed; this basic workflow refuses it.",invalid);}
                if(!metadata.isJsonArray()||!metadata.getAsJsonArray().isEmpty())throw new IllegalArgumentException("Image contains devcontainer.metadata. The basic workflow refuses inherited mounts, privileges and hooks. Use a plain base image without this metadata or a full external Dev Container client.");
            }
        }
        return id;
    }
}
