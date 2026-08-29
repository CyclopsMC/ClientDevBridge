package org.cyclops.clientdevbridge.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.Map;

/**
 * Small Gson helpers, so handlers can build results without ceremony.
 *
 * Gson ships with Minecraft on every supported version, so this needs no extra dependency.
 *
 * @author rubensworks
 */
public class Json {

    public static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /**
     * Converts an arbitrary handler return value into a JSON tree.
     */
    public static JsonElement toTree(Object value) {
        if (value instanceof JsonElement element) {
            return element;
        }
        return GSON.toJsonTree(value);
    }

    public static JsonObject object() {
        return new JsonObject();
    }

    public static JsonArray arrayOfStrings(Collection<String> values) {
        JsonArray array = new JsonArray(values.size());
        values.forEach(array::add);
        return array;
    }

    public static JsonArray arrayOfNumbers(double... values) {
        JsonArray array = new JsonArray(values.length);
        for (double value : values) {
            array.add(value);
        }
        return array;
    }

    public static JsonObject objectOf(Map<String, ?> values) {
        JsonObject object = new JsonObject();
        values.forEach((key, value) -> object.add(key, toTree(value)));
        return object;
    }

}
