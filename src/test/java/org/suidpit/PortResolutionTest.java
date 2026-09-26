package org.suidpit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortResolutionTest {

    private Map<String, String> testEnv;

    @BeforeEach
    void setUp() {
        // Clear system properties before each test
        System.clearProperty("ghidra.mcp.port");
        // Create fresh test environment map
        testEnv = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        // Clean up system properties after each test
        System.clearProperty("ghidra.mcp.port");
        testEnv = null;
    }

    @Test
    void test_isPortAvailable_freePort() {
        // Find a free port by binding to 0
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        } catch (IOException e) {
            fail("Failed to find a free port: " + e.getMessage());
            return;
        }

        // The port should now be available (socket was closed)
        assertTrue(PortResolver.isPortAvailable(freePort),
                "Port " + freePort + " should be available");
    }

    @Test
    void test_isPortAvailable_occupiedPort() throws IOException {
        // Bind to an ephemeral port
        try (ServerSocket occupied = new ServerSocket(0)) {
            int occupiedPort = occupied.getLocalPort();

            // The port should not be available while the socket is open
            assertFalse(PortResolver.isPortAvailable(occupiedPort),
                    "Port " + occupiedPort + " should not be available");
        }
    }

    @Test
    void test_resolvePort_defaultIs8888() {
        PortResolver.PortConfig config = PortResolver.resolvePort(testEnv::get);

        assertEquals(8888, config.port(), "Default port should be 8888");
        assertFalse(config.isExplicit(), "Default port should not be marked as explicit");
    }

    @Test
    void test_resolvePort_systemPropertyTakesPrecedence() {
        System.setProperty("ghidra.mcp.port", "9999");

        PortResolver.PortConfig config = PortResolver.resolvePort(testEnv::get);

        assertEquals(9999, config.port(), "Port should be 9999 from system property");
        assertTrue(config.isExplicit(), "System property port should be marked as explicit");
    }

    @Test
    void test_resolvePort_envVarOverridesDefault() {
        testEnv.put("GHIDRA_MCP_PORT", "7777");

        PortResolver.PortConfig config = PortResolver.resolvePort(testEnv::get);

        assertEquals(7777, config.port(), "Port should be 7777 from environment variable");
        assertTrue(config.isExplicit(), "Environment variable port should be marked as explicit");
    }

    @Test
    void test_resolvePort_systemPropertyOverridesEnvVar() {
        testEnv.put("GHIDRA_MCP_PORT", "7777");
        System.setProperty("ghidra.mcp.port", "9999");

        PortResolver.PortConfig config = PortResolver.resolvePort(testEnv::get);

        assertEquals(9999, config.port(), "System property should take precedence over env var");
        assertTrue(config.isExplicit(), "System property port should be marked as explicit");
    }

    @Test
    void test_resolvePort_invalidSystemProperty() {
        System.setProperty("ghidra.mcp.port", "not-a-number");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PortResolver.resolvePort(testEnv::get));

        assertTrue(exception.getMessage().contains("ghidra.mcp.port"),
                "Exception should mention the property name");
        assertTrue(exception.getMessage().toLowerCase().contains("invalid"),
                "Exception should indicate the value is invalid");
    }

    @Test
    void test_resolvePort_invalidEnvVar() {
        testEnv.put("GHIDRA_MCP_PORT", "invalid-port");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PortResolver.resolvePort(testEnv::get));

        assertTrue(exception.getMessage().contains("GHIDRA_MCP_PORT"),
                "Exception should mention the environment variable name");
        assertTrue(exception.getMessage().toLowerCase().contains("invalid"),
                "Exception should indicate the value is invalid");
    }

    @Test
    void test_findAvailablePort_scansUpward() throws IOException {
        // Occupy a port
        try (ServerSocket occupied = new ServerSocket(0)) {
            int basePort = occupied.getLocalPort();

            // Find next available port starting from basePort
            int availablePort = PortResolver.findAvailablePort(basePort, false);

            // Should return basePort + 1 (or higher if that's also occupied)
            assertTrue(availablePort >= basePort + 1,
                    "Available port should be at least " + (basePort + 1));
            assertTrue(PortResolver.isPortAvailable(availablePort),
                    "Returned port should actually be available");
        }
    }

    @Test
    void test_findAvailablePort_explicitPortFailsHard() throws IOException {
        // Occupy a port
        try (ServerSocket occupied = new ServerSocket(0)) {
            int occupiedPort = occupied.getLocalPort();

            // Trying to use an occupied port with isExplicit=true should throw
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    PortResolver.findAvailablePort(occupiedPort, true)
            );

            assertTrue(exception.getMessage().contains(String.valueOf(occupiedPort)),
                    "Exception message should mention the occupied port");
            assertTrue(exception.getMessage().toLowerCase().contains("not available"),
                    "Exception message should mention port not being available");
        }
    }

    @Test
    void test_findAvailablePort_throwsWhenAllOccupied() throws IOException {
        // Occupy 10 consecutive ports to force the scan to fail
        ServerSocket[] sockets = new ServerSocket[10];
        try {
            // Find a base port and occupy it plus the next 9 ports
            int basePort;
            try (ServerSocket temp = new ServerSocket(0)) {
                basePort = temp.getLocalPort();
            }

            // Occupy the base port and the next 9 ports
            for (int i = 0; i < 10; i++) {
                sockets[i] = new ServerSocket(basePort + i);
            }

            // Now trying to find an available port starting from basePort should fail
            int finalBasePort = basePort;
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    PortResolver.findAvailablePort(finalBasePort, false)
            );

            assertTrue(exception.getMessage().toLowerCase().contains("no available port"),
                    "Exception message should mention no available port");
        } finally {
            // Clean up sockets
            for (ServerSocket socket : sockets) {
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                }
            }
        }
    }

    @Test
    void test_resolveHost_defaultIsLocalhost() {
        String host = PortResolver.resolveHost(testEnv::get);

        assertEquals("127.0.0.1", host, "Default host should be 127.0.0.1");
    }

    @Test
    void test_resolveHost_envVarOverrides() {
        testEnv.put("GHIDRA_MCP_HOST", "0.0.0.0");

        String host = PortResolver.resolveHost(testEnv::get);

        assertEquals("0.0.0.0", host, "Host should be 0.0.0.0 from environment variable");
    }

    @Test
    void test_resolveHost_allowsSpecificIP() {
        testEnv.put("GHIDRA_MCP_HOST", "192.168.1.100");

        String host = PortResolver.resolveHost(testEnv::get);

        assertEquals("192.168.1.100", host, "Host should be the specified IP address");
    }
}
