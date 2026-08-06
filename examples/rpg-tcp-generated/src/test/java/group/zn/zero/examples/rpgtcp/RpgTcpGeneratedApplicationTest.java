package group.zn.zero.examples.rpgtcp;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * RPG TCP generated dispatcher example test.
 *
 * @author zn
 */
class RpgTcpGeneratedApplicationTest {

    @Test
    void runDemoShouldDispatchGeneratedProtocolThroughTcp() throws Exception {
        RpgTcpGeneratedApplication.TcpDemoResult result = RpgTcpGeneratedApplication.runDemo();

        assertAll("RPG TCP generated example",
                () -> assertEquals(97101, result.protocolId()),
                () -> assertTrue(result.dispatched()),
                () -> assertEquals(1001L, result.uid()),
                () -> assertEquals("trace-rpg-tcp-1", result.traceId()),
                () -> assertEquals("tcp-example", result.channel()),
                () -> assertEquals(1L, result.requests()),
                () -> assertEquals("zero-example-rpg-tcp", result.handlerThread()),
                () -> assertTrue(result.sessionsClosed()),
                () -> assertTrue(result.summaryLine().startsWith("rpg-tcp=ok|protocol=97101")));
    }
}
