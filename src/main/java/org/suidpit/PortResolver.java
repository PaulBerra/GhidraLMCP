package org.suidpit;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Utility class for resolving and checking port availability.
 * Separated from McpServerApplication to enable testing without Ghidra dependencies.
 */
public class PortResolver {

    /**
     * Configuration for port resolution containing the port number and whether it was explicitly set.
     */
    public record PortConfig(int port, boolean isExplicit) {}

    /**
     * Resolves the port to use for the MCP server.
     * Priority: system property > environment variable > default (8888)
     *
     * @return PortConfig containing the resolved port and whether it was explicitly configured
     */
    public static PortConfig resolvePort() {
        return resolvePort(System::getenv);
    }

    /**
     * Resolves the port to use for the MCP server with injectable environment variable lookup.
     * Package-private for testing.
     *
     * @param envLookup function to look up environment variables
     * @return PortConfig containing the resolved port and whether it was explicitly configured
     */
    static PortConfig resolvePort(java.util.function.Function<String, String> envLookup) {
        // Check system property first
        String sysProp = System.getProperty("ghidra.mcp.port");
        if (sysProp != null && !sysProp.isEmpty()) {
            try {
                return new PortConfig(Integer.parseInt(sysProp), true);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid port in system property ghidra.mcp.port: " + sysProp, e);
            }
        }

        // Check environment variable second
        String envVar = envLookup.apply("GHIDRA_MCP_PORT");
        if (envVar != null && !envVar.isEmpty()) {
            try {
                return new PortConfig(Integer.parseInt(envVar), true);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid port in environment variable GHIDRA_MCP_PORT: " + envVar, e);
            }
        }

        // Default port
        return new PortConfig(8888, false);
    }

    /**
     * Resolves the host address to bind the MCP server to.
     * Defaults to 127.0.0.1 (localhost only). Set GHIDRA_MCP_HOST to override.
     *
     * @return the host address to bind to
     */
    public static String resolveHost() {
        return resolveHost(System::getenv);
    }

    /**
     * Resolves the host address with injectable environment variable lookup.
     * Package-private for testing.
     *
     * @param envLookup function to look up environment variables
     * @return the host address to bind to
     */
    static String resolveHost(java.util.function.Function<String, String> envLookup) {
        String envVar = envLookup.apply("GHIDRA_MCP_HOST");
        if (envVar != null && !envVar.isEmpty()) {
            return envVar;
        }
        // Default to localhost-only binding for security
        return "127.0.0.1";
    }

    /**
     * Checks if a port is available for binding.
     *
     * @param port the port to check
     * @return true if the port is available, false otherwise
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Finds an available port starting from the base port.
     *
     * @param basePort the starting port to check
     * @param isExplicit whether this port was explicitly configured by the user
     * @return the first available port found
     * @throws RuntimeException if no available port is found
     */
    public static int findAvailablePort(int basePort, boolean isExplicit) {
        if (isExplicit) {
            // User explicitly set this port - don't scan, fail if unavailable
            if (!isPortAvailable(basePort)) {
                throw new RuntimeException(
                        "Port " + basePort + " is not available. " +
                                "Check if another application is using it.");
            }
            return basePort;
        }

        // Default port - scan up to 10 ports to find an available one
        for (int i = 0; i < 10; i++) {
            int port = basePort + i;
            if (isPortAvailable(port)) {
                return port;
            }
        }

        throw new RuntimeException(
                "No available port found in range " + basePort + "-" + (basePort + 9) + ". " +
                        "Set GHIDRA_MCP_PORT environment variable or -Dghidra.mcp.port system property " +
                        "to specify a different port.");
    }
}
