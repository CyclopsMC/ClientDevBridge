package org.cyclops.clientdevbridge.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed, validating access to a request's {@code params} object.
 *
 * Every accessor reports the offending field by name, because these messages are what the
 * agent driving the CLI sees when it gets a call wrong.
 *
 * @author rubensworks
 */
public class Params {

    private final JsonObject raw;

    public Params(@Nullable JsonObject raw) {
        this.raw = raw == null ? new JsonObject() : raw;
    }

    public JsonObject raw() {
        return this.raw;
    }

    public boolean has(String name) {
        return this.raw.has(name) && !this.raw.get(name).isJsonNull();
    }

    public int getInt(String name) {
        return (int) requireNumber(name);
    }

    public int getInt(String name, int fallback) {
        return has(name) ? getInt(name) : fallback;
    }

    public long getLong(String name, long fallback) {
        return has(name) ? (long) requireNumber(name) : fallback;
    }

    public double getDouble(String name) {
        return requireNumber(name);
    }

    public double getDouble(String name, double fallback) {
        return has(name) ? requireNumber(name) : fallback;
    }

    public String getString(String name) {
        JsonElement element = require(name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw RpcException.invalidParams("Parameter '" + name + "' must be a string, but was " + describe(element));
        }
        return element.getAsString();
    }

    @Nullable
    public String getString(String name, @Nullable String fallback) {
        return has(name) ? getString(name) : fallback;
    }

    public boolean getBoolean(String name, boolean fallback) {
        if (!has(name)) {
            return fallback;
        }
        JsonElement element = this.raw.get(name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw RpcException.invalidParams("Parameter '" + name + "' must be a boolean, but was " + describe(element));
        }
        return element.getAsBoolean();
    }

    @Nullable
    public JsonObject getObject(String name) {
        if (!has(name)) {
            return null;
        }
        JsonElement element = this.raw.get(name);
        if (!element.isJsonObject()) {
            throw RpcException.invalidParams("Parameter '" + name + "' must be an object, but was " + describe(element));
        }
        return element.getAsJsonObject();
    }

    /**
     * Reads a fixed-length numeric array, such as the {@code [x, y, z]} of a block position.
     */
    public double[] getNumberArray(String name, int expectedLength) {
        JsonElement element = require(name);
        if (!element.isJsonArray()) {
            throw RpcException.invalidParams("Parameter '" + name + "' must be an array of "
                    + expectedLength + " numbers, but was " + describe(element));
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != expectedLength) {
            throw RpcException.invalidParams("Parameter '" + name + "' must have exactly "
                    + expectedLength + " numbers, but had " + array.size());
        }
        double[] values = new double[expectedLength];
        for (int i = 0; i < expectedLength; i++) {
            JsonElement entry = array.get(i);
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()) {
                throw RpcException.invalidParams("Parameter '" + name + "'[" + i + "] must be a number, but was "
                        + describe(entry));
            }
            values[i] = entry.getAsDouble();
        }
        return values;
    }

    public List<String> getStringList(String name) {
        List<String> result = new ArrayList<>();
        if (!has(name)) {
            return result;
        }
        JsonElement element = this.raw.get(name);
        if (!element.isJsonArray()) {
            throw RpcException.invalidParams("Parameter '" + name + "' must be an array of strings, but was "
                    + describe(element));
        }
        for (JsonElement entry : element.getAsJsonArray()) {
            result.add(entry.getAsString());
        }
        return result;
    }

    /**
     * Reads an enum-like string parameter, listing the valid values when it does not match.
     */
    public String getEnum(String name, String fallback, String... allowed) {
        String value = has(name) ? getString(name) : fallback;
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw RpcException.invalidParams("Parameter '" + name + "' must be one of "
                + String.join(", ", allowed) + ", but was '" + value + "'");
    }

    private JsonElement require(String name) {
        if (!has(name)) {
            throw RpcException.invalidParams("Missing required parameter '" + name + "'");
        }
        return this.raw.get(name);
    }

    private double requireNumber(String name) {
        JsonElement element = require(name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw RpcException.invalidParams("Parameter '" + name + "' must be a number, but was " + describe(element));
        }
        return element.getAsDouble();
    }

    private static String describe(JsonElement element) {
        if (element.isJsonNull()) {
            return "null";
        } else if (element.isJsonArray()) {
            return "an array";
        } else if (element.isJsonObject()) {
            return "an object";
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return "a boolean";
        } else if (primitive.isNumber()) {
            return "a number";
        }
        return "the string '" + primitive.getAsString() + "'";
    }

}
