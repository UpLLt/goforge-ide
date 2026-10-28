package dev.goforge.containers;

import com.google.gson.*;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Strict bounded decoding of Docker's JSON output, accepting JSON-lines and arrays. */
public final class DockerData {
    public record Context(String name, String endpoint, boolean current, String error) { @Override public String toString() { return name; } }
    public record Container(String id, String name, String image, String state, String status, String ports) { }
    public record Image(String id, String repository, String tag, String size, String created) { }
    private DockerData() { }
    public static List<JsonObject> objects(String text) {
        var result = new ArrayList<JsonObject>();
        if (text.isBlank()) return List.of();
        var parser = new JsonStreamParser(new StringReader(text));
        while (parser.hasNext()) {
            JsonElement item = parser.next();
            if (item.isJsonArray()) for (JsonElement entry : item.getAsJsonArray()) add(result, entry);
            else add(result, item);
        }
        return List.copyOf(result);
    }
    private static void add(List<JsonObject> result, JsonElement item) {
        if (!item.isJsonObject()) throw new IllegalArgumentException("Expected a Docker JSON object.");
        if (result.size() >= 10_000) throw new IllegalArgumentException("Docker returned more than 10000 entries. Use the CLI to narrow this inventory.");
        result.add(item.getAsJsonObject());
    }
    public static List<Context> contexts(String text) { return objects(text).stream().map(o -> new Context(required(o,"Name"), string(o,"DockerEndpoint"), "true".equals(string(o,"Current")), string(o,"Error"))).toList(); }
    public static List<Container> containers(String text) { return objects(text).stream().map(o -> new Container(required(o,"ID"), required(o,"Names"), string(o,"Image"), string(o,"State"), string(o,"Status"), string(o,"Ports"))).toList(); }
    public static List<Image> images(String text) { return objects(text).stream().map(o -> new Image(required(o,"ID"), required(o,"Repository"), required(o,"Tag"), string(o,"Size"), string(o,"CreatedSince"))).toList(); }
    public static String connection(String text) {
        var root = JsonParser.parseString(text).getAsJsonObject();
        if (!root.has("Server") || !root.get("Server").isJsonObject()) throw new IllegalArgumentException("Docker returned no daemon version.");
        var server = root.getAsJsonObject("Server");
        return "Connected · Docker " + required(server, "Version") + " · " + string(server,"Os") + "/" + string(server,"Arch");
    }
    private static String required(JsonObject object, String key) { String value = string(object,key); if (value.isBlank()) throw new IllegalArgumentException("Docker JSON is missing " + key); return value; }
    private static String string(JsonObject object, String key) { var value = object.get(key); return value == null || value.isJsonNull() ? "" : value.getAsString(); }
    public static String pretty(String text) { try { var values = objects(text); return new GsonBuilder().setPrettyPrinting().create().toJson(values.size() == 1 ? values.getFirst() : values); } catch (RuntimeException notJson) { return text; } }
}
