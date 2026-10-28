package dev.goforge.http;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original declarative checks; never evaluates scripts or expressions. JSON uses RFC6901 pointers. */
public final class HttpAssertion {
    public record Outcome(String label,boolean passed,String detail,int sourceLine) { }
    private final String kind,target;private final JsonElement expected;private final int line;
    private HttpAssertion(String kind,String target,JsonElement expected,int line){this.kind=kind;this.target=target;this.expected=expected;this.line=line;}
    public static HttpAssertion parse(String declaration,int line,Map<String,String> variables){
        String value=declaration.trim();int space=value.indexOf(' ');if(space<0)throw invalid(line);
        String kind=value.substring(0,space),rest=value.substring(space+1).trim();
        if(kind.equals("status")){
            if(!rest.matches("[1-5][0-9]{2}"))throw invalid(line);
            return new HttpAssertion(kind,"",new JsonPrimitive(Integer.parseInt(rest)),line);
        }
        int split=rest.indexOf(' ');if(split<0)throw invalid(line);
        String target=rest.substring(0,split);String raw=HttpRequestFile.expand(rest.substring(split+1).trim(),variables);
        final JsonElement expected;try{expected=JsonParser.parseString(raw);}catch(RuntimeException e){throw invalid(line);}
        if(kind.equals("header")){
            if(!target.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||!raw.startsWith("\"")||!expected.isJsonPrimitive()||!expected.getAsJsonPrimitive().isString())throw invalid(line);
        }else if(kind.equals("json")){
            if(target.equals("\"\""))target="";
            if(!target.isEmpty()&&!target.startsWith("/")||target.matches(".*~(?:[^01]|$).*"))throw invalid(line);
            if(!raw.matches("(?s)(?:[\\\"\\[\\{0-9-].*|true|false|null)"))throw invalid(line);
        }else throw invalid(line);
        return new HttpAssertion(kind,target,expected,line);
    }
    public Outcome evaluate(HttpRequestEngine.Result response){
        if(kind.equals("status")){boolean passed=response.status()==expected.getAsInt();return new Outcome("Status "+expected.getAsInt(),passed,passed?"Matched":"Actual HTTP status "+response.status(),line);}
        if(kind.equals("header")){
            boolean matched=response.headers().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(target)).flatMap(e->e.getValue().stream()).anyMatch(expected.getAsString()::equals);
            return new Outcome("Header "+target,matched,matched?"Matched":"Header missing or different; expected/actual values are hidden",line);
        }
        String label="JSON "+(target.isEmpty()?"(root)":target);
        if(response.body().truncated())return new Outcome(label,false,"Cannot validate a truncated JSON body",line);
        try{
            JsonElement found=JsonParser.parseString(new String(response.body().bytes(),StandardCharsets.UTF_8));
            if(!target.isEmpty())for(String segment:target.substring(1).split("/",-1)){
                String key=segment.replace("~1","/").replace("~0","~");
                if(found.isJsonObject()){if(!found.getAsJsonObject().has(key))throw new IllegalArgumentException();found=found.getAsJsonObject().get(key);}
                else if(found.isJsonArray()&&key.matches("0|[1-9][0-9]*"))found=found.getAsJsonArray().get(Integer.parseInt(key));
                else throw new IllegalArgumentException();
            }
            boolean matched=expected.equals(found);return new Outcome(label,matched,matched?"Matched":"JSON value differs; expected/actual values are hidden",line);
        }catch(RuntimeException invalid){return new Outcome(label,false,"JSON body is invalid or pointer is absent",line);}
    }
    private static IllegalArgumentException invalid(int line){return new IllegalArgumentException("Line "+(line+1)+": use # @expect status 200, header Name \"value\", or json /pointer <JSON value>.");}
    @Override public String toString(){return "HTTP "+kind+" assertion (expected value hidden)";}
}
