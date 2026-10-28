package dev.goforge.containers;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Deliberately bounded image/build workflow, not a replacement for the full Dev Container specification. */
public record DevContainerPlan(Path workspace,Path source,String sourceHash,String owner,String name,String workspaceFolder,
                               String configuration,String original,String lifecycle,List<String> omitted) {
    public static DevContainerPlan read(Path project,Path config,String suffix)throws Exception {
        Path root=project.toRealPath(),file=(config.isAbsolute()?config:root.resolve(config)).toRealPath();
        if(!file.startsWith(root)||!Files.isRegularFile(file)||Files.size(file)>1024*1024)throw new IllegalArgumentException("Choose a devcontainer JSON file inside this project (at most 1 MiB).");
        if(!suffix.matches("[a-z0-9][a-z0-9_-]{0,39}"))throw new IllegalArgumentException("Container suffix must use lowercase letters, digits, _ or -.");
        if(root.toString().contains(","))throw new IllegalArgumentException("This basic workspace mount does not support a comma in the project path.");
        String original=Files.readString(file);var input=JsonParser.parseString(jsonc(original)).getAsJsonObject();
        if(input.has("dockerComposeFile"))throw new IllegalArgumentException("This basic workflow supports image/build configurations. Use the Compose tab for Compose-based dev containers.");
        var output=new JsonObject();List<String> omitted=new ArrayList<>();
        if(input.has("image")){String image=input.get("image").getAsString();DockerCommands.pull(image);output.addProperty("image",image);}
        else if(input.has("build")){JsonObject build=input.get("build").getAsJsonObject();Path context=inside(root,file.getParent().resolve(build.has("context")?build.get("context").getAsString():"."));Path dockerfile=inside(root,file.getParent().resolve(build.has("dockerfile")?build.get("dockerfile").getAsString():"Dockerfile"));if(!Files.isDirectory(context)||!Files.isRegularFile(dockerfile))throw new IllegalArgumentException("Build context or Dockerfile does not exist.");var safe=new JsonObject();safe.addProperty("context",context.toString());safe.addProperty("dockerfile",dockerfile.toString());if(build.has("args")){var args=build.getAsJsonObject("args");for(var e:args.entrySet())if(!e.getValue().isJsonPrimitive()||!e.getValue().getAsJsonPrimitive().isString())throw new IllegalArgumentException("Build args must be strings.");safe.add("args",args.deepCopy());}output.add("build",safe);for(String key:build.keySet())if(!List.of("context","dockerfile","args").contains(key))omitted.add("build."+key);}
        else throw new IllegalArgumentException("The configuration needs image or build.");
        String owner=hash((root.toString()+"\n"+suffix).getBytes(StandardCharsets.UTF_8)),name="goforge-dev-"+owner.substring(0,10)+"-"+suffix,folder="/workspaces/"+suffix;
        output.addProperty("name",input.has("name")?input.get("name").getAsString():name);output.addProperty("workspaceMount","type=bind,source="+root+",target="+folder);output.addProperty("workspaceFolder",folder);output.addProperty("overrideCommand",true);output.addProperty("userEnvProbe","none");output.addProperty("updateRemoteUserUID",false);
        var runArgs=new JsonArray();for(String v:List.of("--name",name,"--label","dev.goforge.workspace="+owner))runArgs.add(v);output.add("runArgs",runArgs);
        for(String key:List.of("remoteUser","containerUser"))if(input.has(key))output.add(key,input.get(key).deepCopy());
        for(String key:input.keySet())if(!List.of("name","image","build","remoteUser","containerUser","postCreateCommand").contains(key))omitted.add(key);
        String lifecycle=input.has("postCreateCommand")?new Gson().toJson(input.get("postCreateCommand")):"null";
        // Validate the deferred command now, but do not include it in the configuration passed to `up`.
        commands(lifecycle);
        return new DevContainerPlan(root,file,hash(original.getBytes(StandardCharsets.UTF_8)),owner,name,folder,new GsonBuilder().setPrettyPrinting().create().toJson(output),original,lifecycle,List.copyOf(omitted));
    }
    public void requireUnchanged()throws Exception{if(!sourceHash.equals(hash(Files.readAllBytes(source))))throw new IllegalStateException("Dev container configuration changed. Load and review it again.");}
    public List<String> up(String node,Path cli,String docker,Path generated){return up(node,cli,docker,generated,UUID.randomUUID().toString().replace("-",""));}
    public List<String> up(String node,Path cli,String docker,Path generated,String session){if(!session.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid workspace attempt identity.");return List.of(node,cli.toString(),"up","--docker-path",docker,"--workspace-folder",workspace.toString(),"--config",generated.toString(),"--id-label","dev.goforge.session="+session,"--skip-post-create","--mount-workspace-git-root=false","--default-user-env-probe","none","--update-remote-user-uid-default","never","--no-lockfile");}
    public static List<List<String>> commands(String json){var value=JsonParser.parseString(json);var result=new ArrayList<List<String>>();appendCommand(value,result,0);return List.copyOf(result);}
    private static void appendCommand(JsonElement value,List<List<String>> out,int depth){if(depth>1)throw new IllegalArgumentException("Nested lifecycle command objects are unsupported.");if(value.isJsonNull())return;if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()){String command=value.getAsString();if(command.indexOf('\0')>=0)throw new IllegalArgumentException("Lifecycle command contains NUL.");if(!command.isBlank())out.add(List.of("/bin/sh","-c",command));}else if(value.isJsonArray()){out.add(DockerCommands.parseArguments(value.toString()));if(out.getLast().isEmpty())out.removeLast();}else if(value.isJsonObject()){for(var e:value.getAsJsonObject().entrySet())appendCommand(e.getValue(),out,depth+1);}else throw new IllegalArgumentException("postCreateCommand must be a string, string array or object of commands.");}
    private static Path inside(Path root,Path file)throws Exception{Path real=file.toRealPath();if(!real.startsWith(root))throw new IllegalArgumentException("Build paths must stay inside this workspace.");return real;}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String jsonc(String input){StringBuilder noComments=new StringBuilder();boolean string=false,escape=false;for(int i=0;i<input.length();i++){char c=input.charAt(i);if(string){noComments.append(c);if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}else if(c=='"'){string=true;noComments.append(c);}else if(c=='/'&&i+1<input.length()&&input.charAt(i+1)=='/'){while(i<input.length()&&input.charAt(i)!='\n')i++;noComments.append('\n');}else if(c=='/'&&i+1<input.length()&&input.charAt(i+1)=='*'){i+=2;while(i+1<input.length()&&!(input.charAt(i)=='*'&&input.charAt(i+1)=='/'))i++;if(i+1>=input.length())throw new IllegalArgumentException("Unclosed JSON comment.");i++;noComments.append(' ');}else noComments.append(c);}String cleaned=noComments.toString();StringBuilder result=new StringBuilder();string=false;escape=false;for(int i=0;i<cleaned.length();i++){char c=cleaned.charAt(i);if(string){result.append(c);if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}else if(c=='"'){string=true;result.append(c);}else if(c==','){int j=i+1;while(j<cleaned.length()&&Character.isWhitespace(cleaned.charAt(j)))j++;if(j>=cleaned.length()||cleaned.charAt(j)!='}'&&cleaned.charAt(j)!=']')result.append(c);}else result.append(c);}return result.toString();}
}
