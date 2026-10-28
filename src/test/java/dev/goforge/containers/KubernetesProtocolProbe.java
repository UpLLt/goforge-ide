package dev.goforge.containers;

import com.google.gson.*;
import dev.goforge.coverage.GoCoverageProcess;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Operates exclusively on the explicit disposable kind context supplied by the verification script. */
public final class KubernetesProtocolProbe {
    public static void main(String[] args)throws Exception{
        String kubectl=args[0],context=args[1],namespace=args[2];Path fixture=Path.of(args[3]),report=Path.of(args[4]);String image=args[5];
        if(!context.startsWith("kind-goforge-fixture-"))throw new IllegalArgumentException("Only the generated kind fixture context is accepted.");
        var checks=new LinkedHashMap<String,Boolean>();String error=null;
        try{
            var objects=new JsonArray();var deployment=JsonParser.parseString("{\"apiVersion\":\"apps/v1\",\"kind\":\"Deployment\",\"metadata\":{\"name\":\"probe\"},\"spec\":{\"replicas\":1,\"selector\":{\"matchLabels\":{\"app\":\"goforge-probe\"}},\"template\":{\"metadata\":{\"labels\":{\"app\":\"goforge-probe\"}},\"spec\":{\"containers\":[{\"name\":\"probe\",\"imagePullPolicy\":\"Never\",\"ports\":[{\"containerPort\":8080}]}]}}}} ").getAsJsonObject();deployment.getAsJsonObject("spec").getAsJsonObject("template").getAsJsonObject("spec").getAsJsonArray("containers").get(0).getAsJsonObject().addProperty("image",image);objects.add(deployment);
            objects.add(JsonParser.parseString("{\"apiVersion\":\"v1\",\"kind\":\"Service\",\"metadata\":{\"name\":\"probe\"},\"spec\":{\"selector\":{\"app\":\"goforge-probe\"},\"ports\":[{\"port\":80,\"targetPort\":8080}]}}"));
            var manifest=new JsonObject();manifest.addProperty("apiVersion","v1");manifest.addProperty("kind","List");manifest.add("items",objects);Path file=fixture.resolve("manifest.json");Files.writeString(file,manifest.toString());
            var snapshot=KubernetesCommands.readManifest(fixture,file);var diff=run(KubernetesCommands.manifest(kubectl,context,namespace,file,false),fixture,()->false,Duration.ofSeconds(60));checks.put("real_diff_before_apply",KubernetesCommands.diffSuccessful(diff.exitCode(),diff.cancelled(),diff.timedOut(),diff.outputTruncated())&&Integer.valueOf(1).equals(diff.exitCode()));
            snapshot.requireUnchanged();ok(run(KubernetesCommands.manifest(kubectl,context,namespace,file,true),fixture,()->false,Duration.ofSeconds(60)));checks.put("real_apply_reviewed_manifest",true);
            ok(run(KubernetesCommands.command(kubectl,context,namespace,"rollout","status","deployment/probe","--timeout=90s"),fixture,()->false,Duration.ofSeconds(100)));
            var inventory=KubernetesCommands.resources(ok(run(KubernetesCommands.inventory(kubectl,context,namespace),fixture,()->false,Duration.ofSeconds(30))));checks.put("pods_services_deployments_inventory",inventory.stream().anyMatch(r->r.kind().equals("Pod"))&&inventory.stream().anyMatch(r->r.kind().equals("Service"))&&inventory.stream().anyMatch(r->r.kind().equals("Deployment")));
            var pod=inventory.stream().filter(r->r.kind().equals("Pod")).findFirst().orElseThrow();var service=inventory.stream().filter(r->r.kind().equals("Service")).findFirst().orElseThrow();checks.put("describe_selected_pod",ok(run(KubernetesCommands.describe(kubectl,context,namespace,pod),fixture,()->false,Duration.ofSeconds(30))).contains(pod.name()));checks.put("real_pod_logs",ok(run(KubernetesCommands.logs(kubectl,context,namespace,pod),fixture,()->false,Duration.ofSeconds(30))).contains("goforge-k8s-ready"));
            int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}var cancelled=new AtomicBoolean();final int forwardPort=port;var forward=CompletableFuture.supplyAsync(()->{try{return run(KubernetesCommands.forward(kubectl,context,namespace,pod,forwardPort,8080),fixture,cancelled::get,Duration.ofSeconds(30));}catch(Exception e){throw new CompletionException(e);}});
            boolean reachable=false;try{for(int i=0;i<40&&!reachable;i++){try{var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port)).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.ofString());reachable=response.body().equals("goforge-k8s-ok");}catch(Exception unavailable){Thread.sleep(250);}}}finally{cancelled.set(true);}var ended=forward.get(5,TimeUnit.SECONDS);checks.put("loopback_port_forward_real_http",reachable);checks.put("port_forward_cli_cancelled_and_stopped",ended.cancelled()&&ended.knownProcessesStopped());
            Path options=fixture.resolve("delete-options.json");var wrong=new KubernetesCommands.Resource(service.kind(),service.namespace(),service.name(),UUID.randomUUID().toString(),service.status());Files.writeString(options,KubernetesCommands.deleteOptions(wrong));var refused=run(KubernetesCommands.delete(kubectl,context,namespace,service,options),fixture,()->false,Duration.ofSeconds(30));checks.put("wrong_uid_delete_refused_atomically",!Integer.valueOf(0).equals(refused.exitCode())&&refused.stderr().contains("UID"));
            Files.writeString(options,KubernetesCommands.deleteOptions(service));ok(run(KubernetesCommands.delete(kubectl,context,namespace,service,options),fixture,()->false,Duration.ofSeconds(30)));checks.put("selected_uid_delete",KubernetesCommands.resources(ok(run(KubernetesCommands.inventory(kubectl,context,namespace),fixture,()->false,Duration.ofSeconds(30)))).stream().noneMatch(r->r.kind().equals("Service")&&r.name().equals(service.name())));
            if(checks.values().stream().anyMatch(v->!v))throw new AssertionError(checks);
        }catch(Throwable e){error=e.toString();}
        var result=new LinkedHashMap<String,Object>();result.put("passed",error==null);result.put("context",context);result.put("namespace",namespace);result.put("checks",checks);result.put("error",error);result.put("native_ui_verified",false);result.put("scope","Disposable kind cluster only; original user clusters and kubeconfig were never used.");Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(result));if(error!=null)throw new AssertionError(error);
    }
    private static GoCoverageProcess.Result run(List<String> argv,Path cwd,java.util.function.BooleanSupplier cancelled,Duration timeout)throws Exception{return GoCoverageProcess.run(argv,cwd,System.getenv(),cancelled,timeout);}
    private static String ok(GoCoverageProcess.Result result){if(!Integer.valueOf(0).equals(result.exitCode())||result.cancelled()||result.timedOut()||result.outputTruncated())throw new IllegalStateException(result.stdout()+"\n"+result.stderr());return result.stdout();}
}
