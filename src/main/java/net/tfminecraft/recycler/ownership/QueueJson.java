package net.tfminecraft.recycler.ownership;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Raw documents preserve unknown fields and explicit nulls; duplicate names are held. */
public final class QueueJson {
    public static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private QueueJson() {}
    public static JsonObject parse(String source) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            names(reader); reader.peek();
            return JsonParser.parseString(source).getAsJsonObject();
        } catch (RuntimeException ex) { throw new IOException("Unreadable return document", ex); }
    }
    private static void names(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            reader.beginObject(); var seen = new HashSet<String>();
            while (reader.hasNext()) {
                if (!seen.add(reader.nextName())) throw new IOException("Duplicate return field");
                names(reader);
            }
            reader.endObject();
        } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray(); while (reader.hasNext()) names(reader); reader.endArray();
        } else reader.skipValue();
    }
    public static byte[] bytes(JsonObject raw) { return GSON.toJson(raw).getBytes(StandardCharsets.UTF_8); }
}
