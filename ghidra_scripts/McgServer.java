// McG Headless Server Script
// Run via: analyzeHeadless <project_dir> <project_name> -import <binary> -postScript McgServer.java
//@category McG

import ghidra.app.script.GhidraScript;
import org.suidpit.McpServerApplication;
import ghidra.util.Msg;
import java.util.concurrent.CountDownLatch;

public class McgServer extends GhidraScript {

    @Override
    protected void run() throws Exception {
        if (currentProgram == null) {
            println("ERROR: No program loaded. Use -import to load a binary.");
            return;
        }

        println("McG Headless Server starting...");
        println("Program: " + currentProgram.getName());
        println("Language: " + currentProgram.getLanguageID());
        println("Compiler: " + currentProgram.getCompilerSpec().getCompilerSpecID());

        // Start the MCP server with the headless program
        McpServerApplication.startServerHeadless(currentProgram);

        int port = McpServerApplication.getPort();
        if (port > 0) {
            println("===================================");
            println("McG server running on port " + port);
            println("SSE URL: http://localhost:" + port + "/sse");
            println("Press Ctrl+C to stop");
            println("===================================");
        } else {
            println("ERROR: Server failed to start. Check logs.");
            return;
        }

        // Block until shutdown signal
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            println("Shutting down McG server...");
            McpServerApplication.stopServer();
            shutdownLatch.countDown();
        }));

        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            println("McG server interrupted.");
            McpServerApplication.stopServer();
        }
    }
}
