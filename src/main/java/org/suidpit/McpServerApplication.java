package org.suidpit;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import ghidra.app.services.ProgramManager;
import ghidra.app.util.importer.AutoImporter;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.LoadResults;
import ghidra.framework.model.DomainFile;
import ghidra.framework.model.DomainFolder;
import ghidra.framework.model.Project;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.task.TaskMonitor;


@SpringBootApplication
public class McpServerApplication {
    private static ConfigurableApplicationContext context;
    private static final List<GhidraMCPPlugin> plugins = new CopyOnWriteArrayList<>();
    private static volatile GhidraMCPPlugin selectedPlugin;
    private static volatile int activePort = 0;
    private static volatile String activeHost = "127.0.0.1";
    private static final Path MCP_DIR = Paths.get(System.getProperty("user.home"), ".ghidra-mcp");

    public static void startServer(GhidraMCPPlugin plugin) {
        plugins.add(plugin);
        if (context != null && context.isRunning()) {
            return;
        }

        try {
            // Clean up stale config files
            cleanStalePortFiles();

            // Resolve host and port configuration
            String host = PortResolver.resolveHost();
            PortResolver.PortConfig portConfig = PortResolver.resolvePort();
            int port = PortResolver.findAvailablePort(portConfig.port(), portConfig.isExplicit());
            activeHost = host;
            activePort = port;

            // Warn if binding to non-localhost
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                Msg.warn(McpServerApplication.class,
                        "Warning: MCP server is accessible from the network (" + host + "). " +
                        "No authentication is configured.");
            }

            // Start Spring Boot with the resolved host and port
            // Command-line args have highest priority and will override application.yml
            context = SpringApplication.run(McpServerApplication.class,
                    "--server.address=" + host,
                    "--server.port=" + port,
                    "--spring.ai.mcp.server.port=" + port);

            // Write config files and register shutdown hook
            writeConfigFiles(host, port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteConfigFiles()));

            // Use localhost in URLs for readability when bound to 127.0.0.1
            String displayHost = "127.0.0.1".equals(host) ? "localhost" : host;
            Msg.info(McpServerApplication.class,
                    "McG server started at http://" + displayHost + ":" + port + "/sse");
        } catch (RuntimeException e) {
            // Handle port resolution failures and TOCTOU race conditions
            if (e instanceof PortInUseException || e.getCause() instanceof BindException) {
                Msg.error(McpServerApplication.class,
                        "Port " + activePort + " was available during pre-check but is now in use by another process. " +
                                "This is a timing race condition. Try restarting Ghidra, or set GHIDRA_MCP_PORT " +
                                "environment variable or -Dghidra.mcp.port system property to specify a different port.");
            } else {
                Msg.error(McpServerApplication.class,
                        "Failed to start GhidraMCP server: " + e.getMessage(), e);
            }
            activePort = 0;
            context = null;
        }
    }

    /**
     * Starts the server in headless mode with a Program directly (no GUI plugin).
     * Used by McgServer GhidraScript via analyzeHeadless.
     */
    public static void startServerHeadless(Program program) {
        // Create a lightweight plugin-like wrapper for headless program access
        headlessProgram = program;

        if (context != null && context.isRunning()) {
            return;
        }

        try {
            cleanStalePortFiles();

            String host = PortResolver.resolveHost();
            PortResolver.PortConfig portConfig = PortResolver.resolvePort();
            int port = PortResolver.findAvailablePort(portConfig.port(), portConfig.isExplicit());
            activeHost = host;
            activePort = port;

            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                Msg.warn(McpServerApplication.class,
                        "Warning: MCP server is accessible from the network (" + host + "). " +
                        "No authentication is configured.");
            }

            context = SpringApplication.run(McpServerApplication.class,
                    "--server.address=" + host,
                    "--server.port=" + port,
                    "--spring.ai.mcp.server.port=" + port);

            writeConfigFiles(host, port);

            String displayHost = "127.0.0.1".equals(host) ? "localhost" : host;
            Msg.info(McpServerApplication.class,
                    "McG server started at http://" + displayHost + ":" + port + "/sse");
        } catch (RuntimeException e) {
            if (e instanceof PortInUseException || e.getCause() instanceof BindException) {
                Msg.error(McpServerApplication.class,
                        "Port " + activePort + " was available during pre-check but is now in use. " +
                                "Set GHIDRA_MCP_PORT to specify a different port.");
            } else {
                Msg.error(McpServerApplication.class,
                        "Failed to start McG server: " + e.getMessage(), e);
            }
            activePort = 0;
            context = null;
        }
    }

    // Headless program reference (used when no GUI plugin is available)
    private static volatile Program headlessProgram;

    /**
     * Returns the active program, checking headless mode first, then plugins.
     */
    static Program getActiveProgram() {
        // Check headless program first
        if (headlessProgram != null) {
            return headlessProgram;
        }
        // Fall back to GUI plugin
        GhidraMCPPlugin plugin = getActivePlugin();
        return plugin != null ? plugin.getCurrentProgram() : null;
    }

    /**
     * Returns the active port number, or 0 if the server is not running.
     */
    public static int getPort() {
        return activePort;
    }

    /**
     * Writes MCP client config files to ~/.ghidra-mcp/
     *
     * Generated files:
     *   mcp.json         - latest instance config (symlink target for projects)
     *   mcp.<port>.json  - per-instance config keyed by port
     */
    private static void writeConfigFiles(String host, int port) {
        try {
            Files.createDirectories(MCP_DIR);

            // Use localhost for readability when bound to 127.0.0.1
            String urlHost = "127.0.0.1".equals(host) ? "localhost" : host;

            String mcpConfig = "{\n" +
                    "  \"mcpServers\": {\n" +
                    "    \"McG\": {\n" +
                    "      \"type\": \"sse\",\n" +
                    "      \"url\": \"http://" + urlHost + ":" + port + "/sse\"\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n";


            Files.writeString(MCP_DIR.resolve("mcp." + port + ".json"), mcpConfig);
            Files.writeString(MCP_DIR.resolve("mcp.json"), mcpConfig);

            Msg.info(McpServerApplication.class,
                    "MCP config: " + MCP_DIR.resolve("mcp.json") +
                    " (symlink this into your project as .mcp.json)");
        } catch (IOException e) {
            Msg.warn(McpServerApplication.class,
                    "Failed to write config files (server will still run): " + e.getMessage());
        }
    }

    /**
     * Cleans up stale config files by checking if the port is still in use.
     * If the port is available (no server listening), the config file is stale.
     */
    private static void cleanStalePortFiles() {
        try {
            if (!Files.exists(MCP_DIR)) {
                return;
            }

            try (Stream<Path> files = Files.list(MCP_DIR)) {
                files.filter(path -> {
                            String name = path.getFileName().toString();
                            return name.startsWith("mcp.") && name.endsWith(".json") && !name.equals("mcp.json");
                        })
                        .forEach(file -> {
                            try {
                                String fileName = file.getFileName().toString();
                                int port = Integer.parseInt(
                                        fileName.substring("mcp.".length(), fileName.length() - ".json".length()));

                                // If the port is available, no server is listening — file is stale
                                if (PortResolver.isPortAvailable(port)) {
                                    Files.deleteIfExists(file);
                                    Msg.info(McpServerApplication.class,
                                            "Cleaned up stale config: " + file);
                                }
                            } catch (NumberFormatException | IOException e) {
                                // Ignore malformed or inaccessible files
                            }
                        });
            }
        } catch (IOException e) {
            Msg.warn(McpServerApplication.class,
                    "Failed to clean stale config files: " + e.getMessage());
        }
    }

    /**
     * Deletes config files for the current server instance
     */
    private static void deleteConfigFiles() {
        try {
            Files.deleteIfExists(MCP_DIR.resolve("mcp." + activePort + ".json"));
            // Only remove mcp.json if we're the last instance
            if (plugins.isEmpty()) {
                Files.deleteIfExists(MCP_DIR.resolve("mcp.json"));
            }
        } catch (IOException e) {
            // Ignore cleanup errors
        }
    }

    public static void removePlugin(GhidraMCPPlugin plugin) {
        plugins.remove(plugin);
        if (selectedPlugin == plugin) {
            selectedPlugin = null;
        }

        // If this was the last plugin, stop the server and clean up
        if (plugins.isEmpty()) {
            stopServer();
        }
    }

    /**
     * Returns the explicitly selected plugin, or the first registered plugin
     * that has an active program open, or the first registered plugin if none
     * have a program.
     */
    static GhidraMCPPlugin getActivePlugin() {
        if (selectedPlugin != null && selectedPlugin.getCurrentProgram() != null) {
            return selectedPlugin;
        }
        for (GhidraMCPPlugin p : plugins) {
            if (p.getCurrentProgram() != null) {
                return p;
            }
        }
        return plugins.isEmpty() ? null : plugins.get(0);
    }

    /**
     * Returns info about every program open in every CodeBrowser window, including
     * background tabs that are not the window's current program (e.g. after openProgram
     * or importProgram added one without switching to it). Each window's current program
     * is marked "[current]" within that window, and the tool-wide active program (the one
     * further tool calls operate on) is marked "[active]".
     */
    static List<String> getOpenPrograms() {
        var result = new ArrayList<String>();
        Program activeProgram = getActiveProgram();
        for (GhidraMCPPlugin p : plugins) {
            ProgramManager pm = p.getProgramManager();
            if (pm == null) {
                continue;
            }
            Program current = pm.getCurrentProgram();
            for (Program prog : pm.getAllOpenPrograms()) {
                var markers = new ArrayList<String>();
                if (prog == current) {
                    markers.add("current");
                }
                if (prog == activeProgram) {
                    markers.add("active");
                }
                String marker = markers.isEmpty() ? "" : " [" + String.join(", ", markers) + "]";
                result.add(prog.getName() + " (" + prog.getLanguageID() + ")" + marker);
            }
        }
        return result;
    }

    /**
     * Select a specific program by name, searching every open program in every window
     * (not just each window's current tab), and brings it to the front in its window.
     * Returns true if found.
     */
    static boolean selectProgram(String programName) {
        for (GhidraMCPPlugin p : plugins) {
            ProgramManager pm = p.getProgramManager();
            if (pm == null) {
                continue;
            }
            for (Program prog : pm.getAllOpenPrograms()) {
                if (prog.getName().equals(programName)) {
                    pm.setCurrentProgram(prog);
                    selectedPlugin = p;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when running via analyzeHeadless (McgServer script) rather than the GUI plugin.
     * Opening, closing, and importing additional programs requires a PluginTool and is
     * therefore only available in GUI mode.
     */
    static boolean isHeadless() {
        return headlessProgram != null;
    }

    private static GhidraMCPPlugin requireGuiPlugin() {
        if (isHeadless()) {
            throw new IllegalStateException(
                    "Opening, closing, and importing programs is not supported in headless mode. "
                    + "Load the desired binary with -import at startup instead.");
        }
        GhidraMCPPlugin plugin = getActivePlugin();
        if (plugin == null) {
            throw new IllegalStateException("No Ghidra CodeBrowser window is available.");
        }
        return plugin;
    }

    /**
     * Lists project files under the given folder path (recursively), so an LLM can discover
     * what is available to open without the user naming files manually.
     * Pass "/" (or null/empty) for the project root.
     */
    static List<String> listProjectFiles(String folderPath) {
        GhidraMCPPlugin plugin = requireGuiPlugin();
        Project project = plugin.getProject();
        if (project == null || project.getProjectData() == null) {
            throw new IllegalStateException("No active Ghidra project.");
        }

        DomainFolder start = project.getProjectData().getRootFolder();
        if (folderPath != null && !folderPath.isBlank() && !folderPath.equals("/")) {
            DomainFolder found = project.getProjectData().getFolder(folderPath);
            if (found == null) {
                throw new IllegalArgumentException("No such project folder: " + folderPath);
            }
            start = found;
        }

        var result = new ArrayList<String>();
        collectDomainFiles(start, result);
        return result;
    }

    private static void collectDomainFiles(DomainFolder folder, List<String> result) {
        for (DomainFile file : folder.getFiles()) {
            result.add(file.getPathname() + " (" + file.getContentType() + ")");
        }
        for (DomainFolder sub : folder.getFolders()) {
            collectDomainFiles(sub, result);
        }
    }

    /**
     * Opens a program that is already present in the active Ghidra project, by its project
     * path (e.g. "/libc.so"), and makes it the active program for subsequent tool calls.
     * Use listProjectFiles to discover valid paths.
     */
    static Program openProjectProgram(String projectPath) {
        GhidraMCPPlugin plugin = requireGuiPlugin();
        Project project = plugin.getProject();
        if (project == null || project.getProjectData() == null) {
            throw new IllegalStateException("No active Ghidra project.");
        }

        DomainFile file = project.getProjectData().getFile(projectPath);
        if (file == null) {
            throw new IllegalArgumentException(
                    "No file at project path: " + projectPath + ". Use listProjectFiles to see available files.");
        }

        ProgramManager pm = plugin.getProgramManager();
        if (pm == null) {
            throw new IllegalStateException("No ProgramManager service registered on this tool.");
        }

        Program program = pm.openProgram(file);
        if (program == null) {
            throw new RuntimeException("Ghidra failed to open program at: " + projectPath);
        }
        pm.setCurrentProgram(program);
        selectedPlugin = plugin;
        return program;
    }

    /**
     * Imports a binary file from disk into the active Ghidra project's root folder and opens
     * it, so the LLM does not need the user to run File > Import manually. Best-effort: relies
     * on AutoImporter's format auto-detection, same as Ghidra's own "guess format" import path.
     */
    static Program importProgram(String filePath) {
        GhidraMCPPlugin plugin = requireGuiPlugin();
        Project project = plugin.getProject();
        if (project == null || project.getProjectData() == null) {
            throw new IllegalStateException("No active Ghidra project.");
        }

        File file = new File(filePath);
        if (!file.isFile()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        ProgramManager pm = plugin.getProgramManager();
        if (pm == null) {
            throw new IllegalStateException("No ProgramManager service registered on this tool.");
        }

        Object consumer = McpServerApplication.class;
        MessageLog log = new MessageLog();
        LoadResults<Program> loadResults = null;
        try {
            loadResults = AutoImporter.importByUsingBestGuess(
                    file, project, "/", consumer, log, TaskMonitor.DUMMY);
            loadResults.save(TaskMonitor.DUMMY);

            Program program = loadResults.getPrimaryDomainObject();
            pm.openProgram(program);
            pm.setCurrentProgram(program);
            selectedPlugin = plugin;
            return program;
        } catch (Exception e) {
            String logText = log.toString();
            throw new RuntimeException("Failed to import " + filePath + ": " + e.getMessage()
                    + (logText.isBlank() ? "" : " | " + logText), e);
        } finally {
            if (loadResults != null) {
                loadResults.release(consumer);
            }
        }
    }

    /**
     * Closes an open program by name. When saveChanges is true, unsaved modifications are
     * written to the project before closing; when false, they are discarded without prompting
     * (closeProgram never blocks on a GUI confirmation dialog).
     */
    static String closeProgramByName(String programName, boolean saveChanges) {
        if (isHeadless()) {
            throw new IllegalStateException(
                    "Opening, closing, and importing programs is not supported in headless mode. "
                    + "Load the desired binary with -import at startup instead.");
        }

        // Search across every CodeBrowser window's ProgramManager, not just the active one:
        // the program to close may be open in a window other than the currently selected one.
        GhidraMCPPlugin owningPlugin = null;
        ProgramManager pm = null;
        Program target = null;
        for (GhidraMCPPlugin p : plugins) {
            ProgramManager candidatePm = p.getProgramManager();
            if (candidatePm == null) {
                continue;
            }
            for (Program candidate : candidatePm.getAllOpenPrograms()) {
                if (candidate.getName().equals(programName)) {
                    owningPlugin = p;
                    pm = candidatePm;
                    target = candidate;
                    break;
                }
            }
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException(
                    "Program not found among open programs: " + programName
                    + ". Use listOpenPrograms to see available programs.");
        }

        if (saveChanges && target.getDomainFile() != null && target.getDomainFile().canSave()) {
            try {
                target.getDomainFile().save(TaskMonitor.DUMMY);
            } catch (Exception e) {
                throw new RuntimeException("Failed to save " + programName + " before closing: " + e.getMessage(), e);
            }
        }

        boolean closed = pm.closeProgram(target, true);
        if (!closed) {
            throw new RuntimeException("Ghidra refused to close program: " + programName);
        }
        if (selectedPlugin == owningPlugin) {
            selectedPlugin = null;
        }
        return "Closed " + programName + (saveChanges ? " (saved)" : " (changes discarded if any)");
    }

    @Bean
    public ToolCallbackProvider ghidraTools(GhidraService ghidraService) {
        return MethodToolCallbackProvider.builder().toolObjects(ghidraService).build();
    }

    public static void stopServer() {
        if (context != null) {
            deleteConfigFiles();
            context.close();
            context = null;
            activePort = 0;
            activeHost = "127.0.0.1";
        }
    }
}
