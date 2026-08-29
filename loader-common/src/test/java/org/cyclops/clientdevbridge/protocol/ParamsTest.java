package org.cyclops.clientdevbridge.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These messages are what an agent sees when it gets a call wrong, so they are worth asserting on.
 *
 * @author rubensworks
 */
class ParamsTest {

    private static Params of(String json) {
        return new Params(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void readsTypedValues() {
        Params params = of("{\"n\":3,\"s\":\"hi\",\"b\":true,\"d\":1.5}");
        assertEquals(3, params.getInt("n"));
        assertEquals("hi", params.getString("s"));
        assertTrue(params.getBoolean("b", false));
        assertEquals(1.5d, params.getDouble("d"));
    }

    @Test
    void usesFallbacksForAbsentValues() {
        Params params = new Params(new JsonObject());
        assertEquals(7, params.getInt("missing", 7));
        assertEquals("x", params.getString("missing", "x"));
        assertTrue(params.getBoolean("missing", true));
    }

    @Test
    void namesTheMissingParameter() {
        RpcException thrown = assertThrows(RpcException.class, () -> new Params(new JsonObject()).getString("world"));
        assertEquals(RpcErrorCodes.INVALID_PARAMS, thrown.getCode());
        assertTrue(thrown.getMessage().contains("'world'"), thrown.getMessage());
    }

    @Test
    void saysWhatTypeItGotInstead() {
        RpcException thrown = assertThrows(RpcException.class, () -> of("{\"ticks\":\"ten\"}").getInt("ticks"));
        assertTrue(thrown.getMessage().contains("must be a number"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'ten'"), thrown.getMessage());
    }

    @Test
    void treatsExplicitNullAsAbsent() {
        assertEquals(5, of("{\"n\":null}").getInt("n", 5));
    }

    @Test
    void readsFixedLengthNumberArrays() {
        double[] pos = of("{\"blockPos\":[1,2,3]}").getNumberArray("blockPos", 3);
        assertEquals(3, pos.length);
        assertEquals(2d, pos[1]);
    }

    @Test
    void rejectsAnArrayOfTheWrongLength() {
        RpcException thrown = assertThrows(RpcException.class,
                () -> of("{\"blockPos\":[1,2]}").getNumberArray("blockPos", 3));
        assertTrue(thrown.getMessage().contains("exactly 3"), thrown.getMessage());
    }

    @Test
    void listsTheAllowedValuesOfAnEnum() {
        RpcException thrown = assertThrows(RpcException.class,
                () -> of("{\"action\":\"wiggle\"}").getEnum("action", "tap", "press", "release", "tap"));
        assertTrue(thrown.getMessage().contains("press, release, tap"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("wiggle"), thrown.getMessage());
    }

    @Test
    void acceptsAValidEnumAndItsFallback() {
        assertEquals("press", of("{\"action\":\"press\"}").getEnum("action", "tap", "press", "tap"));
        assertEquals("tap", new Params(new JsonObject()).getEnum("action", "tap", "press", "tap"));
    }

}
