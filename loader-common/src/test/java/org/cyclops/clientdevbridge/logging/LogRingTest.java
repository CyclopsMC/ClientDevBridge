package org.cyclops.clientdevbridge.logging;

import org.cyclops.clientdevbridge.protocol.RpcException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author rubensworks
 */
class LogRingTest {

    @Test
    void keepsOnlyTheMostRecentLines() {
        LogRing ring = new LogRing(3);
        for (int i = 1; i <= 5; i++) {
            ring.add(LogRing.Level.INFO, "line " + i);
        }
        assertEquals(3, ring.size());
        assertEquals(List.of("line 3", "line 4", "line 5"), ring.tail(10, null, LogRing.Level.TRACE));
    }

    @Test
    void tailsOldestFirst() {
        LogRing ring = new LogRing(10);
        ring.add(LogRing.Level.INFO, "first");
        ring.add(LogRing.Level.INFO, "second");
        assertEquals(List.of("first", "second"), ring.tail(10, null, LogRing.Level.INFO));
    }

    @Test
    void limitsAfterFiltering() {
        LogRing ring = new LogRing(10);
        ring.add(LogRing.Level.INFO, "keep a");
        ring.add(LogRing.Level.INFO, "drop");
        ring.add(LogRing.Level.INFO, "keep b");
        assertEquals(List.of("keep b"), ring.tail(1, "keep", LogRing.Level.INFO));
    }

    @Test
    void hidesLinesBelowTheRequestedLevel() {
        LogRing ring = new LogRing(10);
        ring.add(LogRing.Level.TRACE, "noise");
        ring.add(LogRing.Level.WARN, "signal");
        assertEquals(List.of("signal"), ring.tail(10, null, LogRing.Level.INFO));
        assertEquals(2, ring.tail(10, null, LogRing.Level.TRACE).size());
    }

    @Test
    void reportsAnInvalidRegexRatherThanCrashing() {
        LogRing ring = new LogRing(10);
        ring.add(LogRing.Level.INFO, "line");
        RpcException thrown = assertThrows(RpcException.class, () -> ring.tail(10, "[unclosed", LogRing.Level.INFO));
        assertTrue(thrown.getMessage().contains("regular expression"), thrown.getMessage());
    }

    @Test
    void rejectsAnUnknownLevelInsteadOfSilentlyDefaulting() {
        RpcException thrown = assertThrows(RpcException.class, () -> LogRing.requireLevel("verbose"));
        assertTrue(thrown.getMessage().contains("trace, debug, info"), thrown.getMessage());
    }

    @Test
    void defaultsToInfoWhenNoLevelWasAskedFor() {
        assertEquals(LogRing.Level.INFO, LogRing.requireLevel(null));
        assertEquals(LogRing.Level.WARN, LogRing.requireLevel("WARN"));
    }

    @Test
    void ordersLevelsBySeverity() {
        assertTrue(LogRing.Level.ERROR.atLeast(LogRing.Level.INFO));
        assertTrue(!LogRing.Level.DEBUG.atLeast(LogRing.Level.INFO));
    }

}
