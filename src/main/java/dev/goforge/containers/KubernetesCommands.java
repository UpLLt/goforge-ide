package dev.goforge.containers;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

public final class KubernetesCommands {
    public record Resource(String kind,String namespace,String name,String uid,String status) { public String target(){return kind.toLowerCase(Locale.ROOT)+"/"+name;} }
    public record Manifest(Path path,byte[] bytes,String sha256) {
        public Manifest { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
        public void requireUnchanged() throws Exception { if(!sha256.equals(hash(Files.readAllBytes(path))))throw new IllegalStateException("Manifest changed after preview. Run Preview Diff again."); }
    }
    private KubernetesCommands(){}
    public static List<String> command(String kubectl,String context,String namespace,String... arguments) {
        if(context==null||context.isBlank()||context.startsWith("-")||context.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Choose an explicit Kubernetes context.");
        if(namespace==null||!namespace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))throw new IllegalArgumentException("Invalid namespace."); var result=new ArrayList<>(List.of(kubectl,"--context",context,"--namespace",namespace,"--request-timeout=20s"));result.addAll(List.of(arguments));return List.copyOf(result);
    }
    public static List<String> inventory(String kubectl,String context,String namespace){return command(kubectl,context,namespace,"get","pods,services,deployments","--output=json");}
    public static List<String> describe(String kubectl,String context,String namespace,Resource row){validate(row,namespace);return command(kubectl,context,namespace,"describe",row.target());}
    public static List<String> logs(String kubectl,String context,String namespace,Resource row){validate(row,namespace);if(!row.kind().equals("Pod"))throw new IllegalArgumentException("Select a Pod to read logs.");return command(kubectl,context,namespace,"logs",row.name(),"--all-containers=true","--tail=1000","--timestamps=true");}
    public static List<String> delete(String kubectl,String context,String namespace,Resource row,Path options){validate(row,namespace);String path=(row.kind().equals("Deployment")?"/apis/apps/v1":"/api/v1")+"/namespaces/"+namespace+"/"+switch(row.kind()){case "Pod"->"pods";case "Service"->"services";case "Deployment"->"deployments";default->throw new IllegalArgumentException("Unsupported kind");}+"/"+row.name();return command(kubectl,context,namespace,"delete","--raw",path,"--filename",options.toAbsolutePath().toString());}
    public static String deleteOptions(Resource row){if(row.uid()==null||row.uid().isBlank())throw new IllegalArgumentException("Select a resource with its UID.");var result=new JsonObject();result.addProperty("apiVersion","v1");result.addProperty("kind","DeleteOptions");result.addProperty("propagationPolicy","Background");var preconditions=new JsonObject();preconditions.addProperty("uid",row.uid());result.add("preconditions",preconditions);return result.toString();}
    public static List<String> forward(String kubectl,String context,String namespace,Resource row,int local,int remote){validate(row,namespace);port(local);port(remote);return command(kubectl,context,namespace,"port-forward","--address=127.0.0.1",row.target(),local+":"+remote);}
    public static List<String> manifest(String kubectl,String context,String namespace,Path snapshot,boolean apply){return command(kubectl,context,namespace,apply?"apply":"diff","--filename",snapshot.toAbsolutePath().toString());}
    public static boolean diffSuccessful(Integer exit,boolean cancelled,boolean timeout,boolean truncated){return !cancelled&&!timeout&&!truncated&&(Integer.valueOf(0).equals(exit)||Integer.valueOf(1).equals(exit));}
    public static Manifest readManifest(Path project,Path file) throws Exception {
        Path root=project.toRealPath(),real=(file.isAbsolute()?file:root.resolve(file)).toRealPath();if(!real.startsWith(root)||!Files.isRegularFile(real))throw new IllegalArgumentException("Choose one manifest file inside this project.");
        if(Files.size(real)>1024*1024)throw new IllegalArgumentException("Manifest exceeds the 1 MiB preview limit.");byte[] bytes=Files.readAllBytes(real);return new Manifest(real,bytes,hash(bytes));
    }
    public static List<Resource> resources(String json){
        var root=JsonParser.parseString(json).getAsJsonObject();if(!root.has("items")||!root.get("items").isJsonArray())throw new IllegalArgumentException("kubectl returned no resource list.");var result=new ArrayList<Resource>();
        for(var item:root.getAsJsonArray("items")){if(result.size()>=10000)throw new IllegalArgumentException("Resource list exceeds 10000 rows.");var o=item.getAsJsonObject();var m=o.getAsJsonObject("metadata");String kind=o.get("kind").getAsString();String status="";
            if(o.has("status")){var s=o.getAsJsonObject("status");if(s.has("phase"))status=s.get("phase").getAsString();else if(s.has("readyReplicas"))status=s.get("readyReplicas").getAsString()+" ready";}
            if(kind.equals("Service")&&o.has("spec")){var s=o.getAsJsonObject("spec");status=s.has("clusterIP")?s.get("clusterIP").getAsString():"";}
            result.add(new Resource(kind,m.get("namespace").getAsString(),m.get("name").getAsString(),m.get("uid").getAsString(),status));}return List.copyOf(result);
    }
    private static void validate(Resource row,String namespace){if(!List.of("Pod","Service","Deployment").contains(row.kind()))throw new IllegalArgumentException("Unsupported resource kind.");dns(row.name(),"resource name");if(!row.namespace().equals(namespace))throw new IllegalArgumentException("Selection belongs to a different namespace. Refresh first.");}
    private static void dns(String value,String field){if(value==null||value.length()>253||!value.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))throw new IllegalArgumentException("Invalid "+field+".");}
    private static void port(int port){if(port<1||port>65535)throw new IllegalArgumentException("Ports must be between 1 and 65535.");}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
