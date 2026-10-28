package dev.goforge.remote;

import com.google.gson.JsonParser;
import java.util.*;

public final class GoRemoteDebugOptions {
    private GoRemoteDebugOptions(){}
    public static List<String> arguments(String text){
        var value=JsonParser.parseString(text);if(!value.isJsonArray())throw new IllegalArgumentException("Arguments must be a JSON array of strings.");var result=new ArrayList<String>();
        for(var entry:value.getAsJsonArray()){if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Every argument must be a string.");result.add(entry.getAsString());}return List.copyOf(result);
    }
    public static Map<String,String> environmentLines(String text){
        var result=new LinkedHashMap<String,String>();
        for(String line:text.split("\\n")){
            if(line.isBlank())continue;int equals=line.indexOf('=');
            if(equals<1||!line.substring(0,equals).matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Every environment entry must have the form NAME=value.");
            result.put(line.substring(0,equals),line.substring(equals+1));
        }return Map.copyOf(result);
    }
    public static Map<String,String> environment(String text){
        var value=JsonParser.parseString(text);if(!value.isJsonObject())throw new IllegalArgumentException("Environment must be a JSON string object.");var result=new LinkedHashMap<String,String>();
        for(var entry:value.getAsJsonObject().entrySet()){if(!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isString())throw new IllegalArgumentException("Every environment value must be a string.");result.put(entry.getKey(),entry.getValue().getAsString());}return Map.copyOf(result);
    }
}
