package dev.goforge.http;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Explicit files only; values and paths are never persisted by the HTTP client. */
public final class HttpEnvironment {
    public static final class Selection {
        private final String name;
        private final Map<String,String> variables;
        private final Set<String> secrets;
        Selection(String name,Map<String,String> variables,Set<String> secrets) {
            this.name=name;this.variables=Map.copyOf(variables);this.secrets=Set.copyOf(secrets);
        }
        public String name(){return name;}
        public Map<String,String> variables(){return variables;}
        public Set<String> secrets(){return secrets;}
        @Override public String toString(){return name;}
    }
    public static final Selection NONE=new Selection("No environment",Map.of(),Set.of());
    private final Map<String,Map<String,String>> normal,privateValues;
    private HttpEnvironment(Map<String,Map<String,String>> normal,Map<String,Map<String,String>> privateValues){this.normal=normal;this.privateValues=privateValues;}
    public static HttpEnvironment empty(){return new HttpEnvironment(Map.of(),Map.of());}
    public static HttpEnvironment load(Path normal,Path privateFile)throws Exception{return new HttpEnvironment(read(normal,"Environment"),read(privateFile,"Private environment"));}
    public List<String> names(){var names=new TreeSet<String>();names.addAll(normal.keySet());names.addAll(privateValues.keySet());return List.copyOf(names);}
    public Selection select(String name){
        if(!normal.containsKey(name)&&!privateValues.containsKey(name))throw new IllegalArgumentException("Selected environment is absent from the loaded files.");
        var values=new LinkedHashMap<String,String>();values.putAll(normal.getOrDefault(name,Map.of()));values.putAll(privateValues.getOrDefault(name,Map.of()));
        var secrets=new LinkedHashSet<String>();secrets.addAll(privateValues.getOrDefault(name,Map.of()).values());
        values.forEach((key,value)->{if(key.toLowerCase(Locale.ROOT).matches(".*(token|secret|password|credential|api.?key).*"))secrets.add(value);});
        // Expand aliases only in memory; private-derived aliases must also be masked in echoed output.
        var resolved=new LinkedHashMap<String,String>();for(var entry:values.entrySet())resolved.put(entry.getKey(),HttpRequestFile.expand(entry.getValue(),values));
        for(var entry:resolved.entrySet())if(privateValues.getOrDefault(name,Map.of()).containsKey(entry.getKey())||entry.getKey().toLowerCase(Locale.ROOT).matches(".*(token|secret|password|credential|api.?key).*"))secrets.add(entry.getValue());
        return new Selection(name,resolved,secrets);
    }
    private static Map<String,Map<String,String>> read(Path file,String kind)throws Exception{
        if(file==null)return Map.of();
        if(!Files.isRegularFile(file)||Files.size(file)>1_048_576)throw new IllegalArgumentException(kind+" file must be a local JSON file at most 1 MiB.");
        final JsonObject json;
        try{json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();}catch(Exception failure){throw new IllegalArgumentException(kind+" file is not a valid JSON object; its contents were not logged.");}
        if(json.size()>128)throw new IllegalArgumentException("Too many named environments.");
        var result=new LinkedHashMap<String,Map<String,String>>();
        for(var entry:json.entrySet()){
            if(entry.getKey().equals(NONE.name())||entry.getKey().isBlank()||entry.getKey().length()>100||!entry.getValue().isJsonObject())throw new IllegalArgumentException("Each environment needs a short name and an object of variables.");
            var values=new LinkedHashMap<String,String>();flatten(entry.getValue().getAsJsonObject(),"",values,0);result.put(entry.getKey(),Map.copyOf(values));
        }
        return Map.copyOf(result);
    }
    private static void flatten(JsonObject object,String prefix,Map<String,String> values,int depth){
        if(depth>8)throw new IllegalArgumentException("Environment objects are too deeply nested.");
        for(var entry:object.entrySet()){
            String key=prefix+entry.getKey();if(!key.matches("[A-Za-z_][A-Za-z0-9_.-]*"))throw new IllegalArgumentException("Environment variable names must use letters, digits, underscore, dot or hyphen.");
            if(entry.getValue().isJsonObject()){flatten(entry.getValue().getAsJsonObject(),key+".",values,depth+1);continue;}
            if(!entry.getValue().isJsonPrimitive())throw new IllegalArgumentException("Environment leaves must be strings, numbers or booleans; arrays/null are unsupported.");
            if(values.size()>=256||values.putIfAbsent(key,entry.getValue().getAsString())!=null)throw new IllegalArgumentException("Duplicate flattened variable or more than 256 variables.");
        }
    }
    @Override public String toString(){return "HTTP environment files ("+names().size()+" named environments; values hidden)";}
}
