package dev.goforge.http;

import com.google.gson.Gson;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Masks known local secrets. No heuristic can guarantee that an arbitrary server body has no other secrets. */
public final class HttpPrivacy {
    private final List<String> secrets;
    public HttpPrivacy(Collection<String> values){
        var variants=new HashSet<String>();
        for(String value:values)if(value!=null&&!value.isEmpty()){
            variants.add(value);variants.add(URLEncoder.encode(value,StandardCharsets.UTF_8));
            variants.add(URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"));
            String json=new Gson().toJson(value);variants.add(json.substring(1,json.length()-1));
            variants.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        secrets=variants.stream().filter(v->!v.isEmpty()).sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }
    public static HttpPrivacy forRun(HttpEnvironment.Selection environment,List<HttpRequestFile.Request> requests){
        var values=new ArrayList<>(environment.secrets());
        for(var request:requests)for(var header:request.headers())if(sensitiveHeader(header.name()))values.add(header.value());
        return new HttpPrivacy(values);
    }
    public String redact(String text){
        var hidden=new BitSet(text.length());
        for(String value:secrets){int at=0;while((at=text.indexOf(value,at))>=0){hidden.set(at,at+value.length());at+=value.length();}}
        if(hidden.isEmpty())return text;
        var result=new StringBuilder();int position=0;
        while(position<text.length()){int start=hidden.nextSetBit(position);if(start<0){result.append(text,position,text.length());break;}result.append(text,position,start).append("[redacted]");position=hidden.nextClearBit(start);}
        return result.toString();
    }
    public String header(String name,String value){return sensitiveHeader(name)?"[redacted]":redact(value);}
    static boolean sensitiveHeader(String name){return name.toLowerCase(Locale.ROOT).matches("authorization|proxy-authorization|cookie|set-cookie|.*(token|secret|api[-_]?key).*");}
    @Override public String toString(){return "HTTP privacy filter (values hidden)";}
}
