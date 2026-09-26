/*
* Copyright 2024 - 2024 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.suidpit;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.program.util.string.FoundString;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;

import java.util.Iterator;

@Service
public class GhidraService {

    private Program getProgram() {
        Program program = McpServerApplication.getActiveProgram();
        if (program == null) {
            throw new IllegalStateException("No program is open. Open a binary in Ghidra or import one in headless mode.");
        }
        return program;
    }

    private Function requireFunction(String functionName) {
        for (Function function : getProgram().getFunctionManager().getFunctions(true)) {
            if (function.getName().equals(functionName)) {
                return function;
            }
        }
        throw new IllegalArgumentException("Function not found: " + functionName);
    }

    private Address requireAddress(String address) {
        Address addr = getProgram().getAddressFactory().getAddress(address);
        if (addr == null) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
        return addr;
    }

    /**
     * Runs a program modification inside a transaction.
     * In GUI mode, dispatches to the Swing EDT for UI synchronization.
     * In headless mode, runs directly on the calling thread.
     */
    private void runTransaction(String description, Runnable action) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            // Headless: run directly on calling thread
            Program program = getProgram();
            int tx = program.startTransaction(description);
            boolean success = false;
            try {
                action.run();
                success = true;
            } finally {
                program.endTransaction(tx, success);
            }
        } else {
            // GUI: dispatch to Swing EDT
            AtomicReference<Exception> error = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    Program program = getProgram();
                    int tx = program.startTransaction(description);
                    boolean success = false;
                    try {
                        action.run();
                        success = true;
                    } catch (Exception e) {
                        error.set(e);
                    } finally {
                        program.endTransaction(tx, success);
                    }
                });
            } catch (InterruptedException | InvocationTargetException e) {
                throw new RuntimeException("Failed to execute on Swing thread: " + e.getMessage(), e);
            }
            if (error.get() != null) {
                Exception e = error.get();
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    @Tool(description = "List all open programs across all CodeBrowser windows. Shows which program is currently active.")
    public List<String> listOpenPrograms() {
        return McpServerApplication.getOpenPrograms();
    }

    @Tool(description = "Select which open program to operate on by name. Use listOpenPrograms to see available programs.")
    public String selectProgram(String programName) {
        if (McpServerApplication.selectProgram(programName)) {
            return "Selected program: " + programName;
        }
        throw new IllegalArgumentException("Program not found: " + programName
                + ". Use listOpenPrograms to see available programs.");
    }

    @Tool(description = "List files available in the active Ghidra project, recursively under folderPath (use \"/\" for the project root). Use this to discover binaries that can be opened with openProgram without asking the user to name them.")
    public List<String> listProjectFiles(String folderPath) {
        return McpServerApplication.listProjectFiles(folderPath);
    }

    @Tool(description = "Open a program already present in the active Ghidra project by its project path (see listProjectFiles), and make it the active program. If it has never been analyzed, this runs full auto-analysis synchronously first (may take a while on large binaries) so Ghidra never blocks on its 'Would you like to analyze it now?' dialog. Not available in headless mode.")
    public String openProgram(String projectPath) {
        var program = McpServerApplication.openProjectProgram(projectPath);
        return "Opened and selected program: " + program.getName();
    }

    @Tool(description = "Import a binary file from disk (a path on the machine running Ghidra) into the active project and open it, without requiring a manual File > Import. Uses Ghidra's format auto-detection, then runs full auto-analysis synchronously before activating the program (may take a while on large binaries) so Ghidra never blocks on its 'Would you like to analyze it now?' dialog. Not available in headless mode.")
    public String importProgram(String filePath) {
        var program = McpServerApplication.importProgram(filePath);
        return "Imported and selected program: " + program.getName();
    }

    @Tool(description = "Close an open program by name. If saveChanges is true, unsaved modifications are written before closing; if false, they are discarded without prompting. Use listOpenPrograms to see open program names. Not available in headless mode.")
    public String closeProgram(String programName, boolean saveChanges) {
        return McpServerApplication.closeProgramByName(programName, saveChanges);
    }

    @Tool(description = "List functions in the current program, paginated. offset skips the first N functions (pass 0 to start from the beginning); limit caps how many names are returned (pass 100-200 for large binaries to avoid flooding the context; pass 0 or negative for no cap). Returns the total function count as the first line, then up to limit names starting at offset.")
    public List<String> listFunctions(int offset, int limit) {
        if (offset < 0) {
            offset = 0;
        }
        int effectiveLimit = limit > 0 ? limit : Integer.MAX_VALUE;

        var all = new ArrayList<String>();
        for (Function function : getProgram().getFunctionManager().getFunctions(true)) {
            all.add(function.getName());
        }

        var result = new ArrayList<String>();
        result.add("Total functions: " + all.size());
        int end = Math.min(all.size(), offset + effectiveLimit);
        for (int i = offset; i < end; i++) {
            result.add(all.get(i));
        }
        return result;
    }

    @Tool(description = "Get function entry point address by name")
    public String getFunctionAddressByName(String functionName) {
        return requireFunction(functionName).getEntryPoint().toString();
    }

    @Tool(description = "Decompile a function to C pseudocode by name")
    public String decompileFunctionByName(String functionName) {
        var function = requireFunction(functionName);
        var decompInterface = new DecompInterface();
        try {
            decompInterface.setOptions(new DecompileOptions());
            decompInterface.openProgram(getProgram());
            var decompiled = decompInterface.decompileFunction(function, 30, null);
            if (decompiled == null || !decompiled.decompileCompleted()) {
                throw new RuntimeException("Decompilation failed for function: " + functionName);
            }
            return decompiled.getDecompiledFunction().getC();
        } finally {
            decompInterface.dispose();
        }
    }

    @Tool(description = "Rename a function")
    public String renameFunction(String currentName, String newName) {
        runTransaction("Rename function", () -> {
            var function = requireFunction(currentName);
            try {
                function.setName(newName, SourceType.USER_DEFINED);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set function name: " + e.getMessage(), e);
            }
        });
        return "Renamed " + currentName + " to " + newName;
    }

    @Tool(description = "Add a comment to a function")
    public String addCommentToFunction(String functionName, String comment) {
        runTransaction("Add comment to function", () -> {
            requireFunction(functionName).setComment(comment);
        });
        return "Comment added to " + functionName;
    }

    @Tool(description = "Rename a local variable within a function")
    public String renameLocalVariableInFunction(String functionName, String currentVariableName,
            String newVariableName) {
        runTransaction("Rename local variable in function", () -> {
            var function = requireFunction(functionName);
            for (Variable var : function.getAllVariables()) {
                if (var.getName().equals(currentVariableName)) {
                    try {
                        var.setName(newVariableName, SourceType.USER_DEFINED);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to rename variable: " + e.getMessage(), e);
                    }
                    return;
                }
            }
            throw new IllegalArgumentException(
                    "Variable " + currentVariableName + " not found in function " + functionName);
        });
        return "Renamed variable " + currentVariableName + " to " + newVariableName;
    }

    @Tool(description = "Get references to an address. Returns addresses and code units that reference the given address.")
    public List<String> getReferencesToAddress(String address) {
        var addr = requireAddress(address);
        var references = getProgram().getReferenceManager().getReferencesTo(addr);
        var result = new ArrayList<String>();
        for (Reference ref : references) {
            var codeUnit = getProgram().getListing().getCodeUnitAt(ref.getFromAddress());
            result.add(ref.getFromAddress().toString() + " | "
                    + (codeUnit != null ? codeUnit.toString() : "unknown"));
        }
        return result;
    }

    @Tool(description = "Get references from an address. Returns addresses and code units that are referenced from the given address.")
    public List<String> getReferencesFromAddress(String address) {
        var addr = requireAddress(address);
        var references = getProgram().getReferenceManager().getReferencesFrom(addr);
        var result = new ArrayList<String>();
        for (Reference ref : references) {
            var codeUnit = getProgram().getListing().getCodeUnitAt(ref.getToAddress());
            result.add(ref.getToAddress().toString() + " | "
                    + (codeUnit != null ? codeUnit.toString() : "unknown"));
        }
        return result;
    }

    @Tool(description = "Get functions that call the given function")
    public List<String> getFunctionCallers(String functionName) {
        var function = requireFunction(functionName);
        var program = getProgram();
        var references = program.getReferenceManager().getReferencesTo(function.getEntryPoint());
        var result = new ArrayList<String>();
        for (Reference ref : references) {
            var caller = program.getFunctionManager().getFunctionContaining(ref.getFromAddress());
            if (caller != null) {
                result.add(caller.getName());
            }
        }
        return result;
    }

    @Tool(description = "Search for strings in the program containing the query. Minimum string length is 5 characters.")
    public List<String> searchForStrings(String query) {
        var program = getProgram();
        var result = new ArrayList<String>();
        FlatProgramAPI flatProgramAPI = new FlatProgramAPI(program);
        var foundStrings = flatProgramAPI.findStrings(null, 5, 1, true, true);
        for (FoundString foundString : foundStrings) {
            String value = foundString.getString(program.getMemory());
            if (value.contains(query)) {
                result.add(foundString.getAddress().toString() + " | " + value);
            }
        }
        return result;
    }

    /**
     * Resolves a type name string to a Ghidra DataType object.
     * Handles built-in types, pointers, Windows types, and custom types.
     *
     * @param typeName the type name (e.g., "int", "char *", "DWORD", "MyStruct")
     * @return the resolved DataType
     * @throws IllegalArgumentException if the type cannot be resolved
     */
    private DataType resolveDataType(String typeName) {
        Program program = getProgram();
        DataTypeManager dtm = program.getDataTypeManager();

        // Handle pointer syntax (e.g., "char *", "int*")
        if (typeName.endsWith("*")) {
            String baseTypeName = typeName.substring(0, typeName.length() - 1).trim();
            DataType baseType = resolveDataType(baseTypeName);
            return new PointerDataType(baseType);
        }

        // Try exact path lookup first
        DataType exactMatch = dtm.getDataType("/" + typeName);
        if (exactMatch != null) {
            return exactMatch;
        }

        // Search all categories by name (case-insensitive)
        Iterator<DataType> allTypes = dtm.getAllDataTypes();
        while (allTypes.hasNext()) {
            DataType dt = allTypes.next();
            if (dt.getName().equalsIgnoreCase(typeName)) {
                return dt;
            }
        }

        // Handle common built-in type aliases
        String normalized = typeName.toLowerCase();
        String builtinPath = switch (normalized) {
            case "int", "long" -> "/int";
            case "uint", "unsigned int", "unsigned long", "dword" -> "/uint";
            case "short" -> "/short";
            case "ushort", "unsigned short", "word" -> "/ushort";
            case "char", "byte" -> "/char";
            case "uchar", "unsigned char" -> "/uchar";
            case "longlong", "__int64", "qword" -> "/longlong";
            case "ulonglong", "unsigned __int64" -> "/ulonglong";
            case "bool", "boolean" -> "/bool";
            case "void" -> "/void";
            case "float" -> "/float";
            case "double" -> "/double";
            default -> null;
        };

        if (builtinPath != null) {
            DataType builtin = dtm.getDataType(builtinPath);
            if (builtin != null) {
                return builtin;
            }
        }

        throw new IllegalArgumentException(
                "Unknown data type: " + typeName + ". Use listTypes() to see available types.");
    }

    // ===== Struct and Enum Management Tools =====

    @Tool(description = "Create a new structure data type. Size of 0 creates an auto-sized struct.")
    public String createStruct(String name, int size) {
        runTransaction("Create struct", () -> {
            try {
                Program program = getProgram();
                StructureDataType struct = new StructureDataType(CategoryPath.ROOT, name, size);
                program.getDataTypeManager().addDataType(struct, null);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create struct: " + e.getMessage(), e);
            }
        });
        return "Created struct: " + name + " (size: " + size + ")";
    }

    @Tool(description = "Add a field to a structure. fieldSize of -1 uses the type's default size.")
    public String addStructField(String structName, String fieldName, String fieldType,
                                   int fieldSize, String comment) {
        runTransaction("Add struct field", () -> {
            try {
                Structure struct = findStruct(structName);
                DataType fieldDataType = resolveDataType(fieldType);

                int actualSize = fieldSize == -1 ? fieldDataType.getLength() : fieldSize;
                struct.add(fieldDataType, actualSize, fieldName, comment);
            } catch (Exception e) {
                throw new RuntimeException("Failed to add field: " + e.getMessage(), e);
            }
        });
        return "Added field '" + fieldName + "' (" + fieldType + ") to struct " + structName;
    }

    @Tool(description = "Get details about a structure including all fields")
    public List<String> getStruct(String structName) {
        Structure struct = findStruct(structName);
        var result = new ArrayList<String>();

        result.add("Name: " + struct.getName());
        result.add("Size: " + struct.getLength());
        result.add("Alignment: " + struct.getAlignment());
        result.add("Fields:");

        for (DataTypeComponent component : struct.getComponents()) {
            String fieldInfo = String.format("  %d | %s | %s | %d",
                    component.getOffset(),
                    component.getFieldName(),
                    component.getDataType().getName(),
                    component.getLength());
            result.add(fieldInfo);
        }

        return result;
    }

    @Tool(description = "List all structure data types in the program")
    public List<String> listStructs() {
        var result = new ArrayList<String>();
        DataTypeManager dtm = getProgram().getDataTypeManager();
        Iterator<DataType> allTypes = dtm.getAllDataTypes();

        while (allTypes.hasNext()) {
            DataType dt = allTypes.next();
            if (dt instanceof Structure) {
                result.add(dt.getName() + " (size: " + dt.getLength() + ")");
            }
        }

        return result;
    }

    @Tool(description = "Delete a structure data type")
    public String deleteStruct(String structName) {
        runTransaction("Delete struct", () -> {
            try {
                Structure struct = findStruct(structName);
                getProgram().getDataTypeManager().remove(struct, null);
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete struct: " + e.getMessage(), e);
            }
        });
        return "Deleted struct: " + structName;
    }

    @Tool(description = "Create a new enum data type. Size should be 1, 2, or 4 bytes.")
    public String createEnum(String name, int size) {
        runTransaction("Create enum", () -> {
            try {
                Program program = getProgram();
                EnumDataType enumType = new EnumDataType(CategoryPath.ROOT, name, size);
                program.getDataTypeManager().addDataType(enumType, null);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create enum: " + e.getMessage(), e);
            }
        });
        return "Created enum: " + name + " (size: " + size + " bytes)";
    }

    @Tool(description = "Add a named value to an enum")
    public String addEnumValue(String enumName, String valueName, long value) {
        runTransaction("Add enum value", () -> {
            try {
                ghidra.program.model.data.Enum enumType = findEnum(enumName);
                enumType.add(valueName, value);
            } catch (Exception e) {
                throw new RuntimeException("Failed to add enum value: " + e.getMessage(), e);
            }
        });
        return "Added value '" + valueName + "' = " + value + " to enum " + enumName;
    }

    @Tool(description = "Get details about an enum including all values")
    public List<String> getEnum(String enumName) {
        ghidra.program.model.data.Enum enumType = findEnum(enumName);
        var result = new ArrayList<String>();

        result.add("Name: " + enumType.getName());
        result.add("Size: " + enumType.getLength());
        result.add("Values:");

        for (String valueName : enumType.getNames()) {
            long value = enumType.getValue(valueName);
            result.add("  " + valueName + " = " + value);
        }

        return result;
    }

    @Tool(description = "Apply a structure type at a memory address")
    public String applyStructAtAddress(String structName, String address) {
        runTransaction("Apply struct at address", () -> {
            try {
                Structure struct = findStruct(structName);
                Address addr = requireAddress(address);
                getProgram().getListing().createData(addr, struct);
            } catch (CodeUnitInsertionException e) {
                throw new RuntimeException("Failed to apply struct (may conflict with existing data): "
                        + e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Failed to apply struct: " + e.getMessage(), e);
            }
        });
        return "Applied struct " + structName + " at " + address;
    }

    @Tool(description = "List all data types, optionally filtered by category (e.g., '/BuiltInTypes')")
    public List<String> listTypes(String category) {
        var result = new ArrayList<String>();
        DataTypeManager dtm = getProgram().getDataTypeManager();
        Iterator<DataType> allTypes = dtm.getAllDataTypes();

        while (allTypes.hasNext()) {
            DataType dt = allTypes.next();
            if (category == null || category.isEmpty() || dt.getCategoryPath().getPath().startsWith(category)) {
                result.add(dt.getCategoryPath() + "/" + dt.getName());
            }
        }

        return result;
    }

    // Helper methods for struct/enum lookups

    private Structure findStruct(String structName) {
        DataTypeManager dtm = getProgram().getDataTypeManager();
        Iterator<DataType> allTypes = dtm.getAllDataTypes();

        while (allTypes.hasNext()) {
            DataType dt = allTypes.next();
            if (dt instanceof Structure && dt.getName().equals(structName)) {
                return (Structure) dt;
            }
        }

        throw new IllegalArgumentException("Struct not found: " + structName + ". Use listStructs() to see available structures.");
    }

    private ghidra.program.model.data.Enum findEnum(String enumName) {
        DataTypeManager dtm = getProgram().getDataTypeManager();
        Iterator<DataType> allTypes = dtm.getAllDataTypes();

        while (allTypes.hasNext()) {
            DataType dt = allTypes.next();
            if (dt instanceof ghidra.program.model.data.Enum && dt.getName().equals(enumName)) {
                return (ghidra.program.model.data.Enum) dt;
            }
        }

        throw new IllegalArgumentException("Enum not found: " + enumName);
    }

    // ===== Raw Memory Operations =====

    @Tool(description = "Read raw bytes from memory as hex dump. Maximum 4096 bytes. Default length is 256.")
    public List<String> readBytes(String address, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
        }
        if (length > 4096) {
            throw new IllegalArgumentException("Length exceeds maximum of 4096 bytes. Request smaller chunks.");
        }

        try {
            Address addr = requireAddress(address);
            byte[] buffer = new byte[length];
            int bytesRead = getProgram().getMemory().getBytes(addr, buffer);

            var result = new ArrayList<String>();
            result.add("Address: " + address + " | " + bytesRead + " bytes");

            // Format as hex dump with ASCII sidebar (16 bytes per line)
            for (int i = 0; i < bytesRead; i += 16) {
                StringBuilder hexPart = new StringBuilder();
                StringBuilder asciiPart = new StringBuilder();

                for (int j = 0; j < 16 && (i + j) < bytesRead; j++) {
                    byte b = buffer[i + j];
                    hexPart.append(String.format("%02x ", b & 0xFF));
                    char c = (b >= 32 && b < 127) ? (char) b : '.';
                    asciiPart.append(c);
                }

                // Pad hex part to align ASCII
                while (hexPart.length() < 48) {
                    hexPart.append(' ');
                }

                result.add(String.format("%08x: %s| %s", i, hexPart, asciiPart));
            }

            return result;
        } catch (MemoryAccessException e) {
            throw new RuntimeException("Memory access error at " + address + ": " + e.getMessage(), e);
        }
    }

    @Tool(description = "Search for a byte pattern in memory. Returns up to maxResults addresses (default 100). Pattern format: space-separated hex bytes (e.g., '48 8b 05')")
    public List<String> searchBytes(String hexPattern, int maxResults) {
        try {
            if (maxResults <= 0) {
                maxResults = 100;
            }

            // Parse hex pattern to byte array
            String[] hexBytes = hexPattern.trim().split("\\s+");
            byte[] pattern = new byte[hexBytes.length];
            for (int i = 0; i < hexBytes.length; i++) {
                pattern[i] = (byte) Integer.parseInt(hexBytes[i], 16);
            }

            var result = new ArrayList<String>();
            Memory memory = getProgram().getMemory();

            // Search in all initialized memory blocks
            for (MemoryBlock block : memory.getBlocks()) {
                if (!block.isInitialized()) {
                    continue;
                }

                Address searchStart = block.getStart();
                Address searchEnd = block.getEnd();

                while (searchStart != null && searchStart.compareTo(searchEnd) < 0) {
                    Address found = memory.findBytes(searchStart, searchEnd, pattern, null, true, null);
                    if (found == null) {
                        break;
                    }

                    result.add(found.toString());
                    if (result.size() >= maxResults) {
                        return result;
                    }

                    // Move search start past this match
                    searchStart = found.add(1);
                }
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Search failed: " + e.getMessage(), e);
        }
    }

    @Tool(description = "Get information about data defined at an address")
    public List<String> getDataAtAddress(String address) {
        Address addr = requireAddress(address);
        Data data = getProgram().getListing().getDataAt(addr);

        var result = new ArrayList<String>();
        if (data == null || !data.isDefined()) {
            result.add("Address: " + address);
            result.add("Status: undefined");
            return result;
        }

        result.add("Address: " + address);
        result.add("Type: " + data.getDataType().getName());
        result.add("Size: " + data.getLength());
        result.add("Value: " + (data.getValue() != null ? data.getValue().toString() : "N/A"));

        Symbol symbol = getProgram().getSymbolTable().getPrimarySymbol(addr);
        if (symbol != null) {
            result.add("Label: " + symbol.getName());
        }

        return result;
    }

    @Tool(description = "Define typed data at an address with an optional label")
    public String defineData(String address, String dataType, String label) {
        runTransaction("Define data", () -> {
            try {
                Address addr = requireAddress(address);
                DataType dt = resolveDataType(dataType);
                getProgram().getListing().createData(addr, dt);

                if (label != null && !label.isEmpty()) {
                    getProgram().getSymbolTable().createLabel(addr, label, SourceType.USER_DEFINED);
                }
            } catch (CodeUnitInsertionException e) {
                throw new RuntimeException("Failed to define data (may conflict with existing data): "
                        + e.getMessage(), e);
            } catch (InvalidInputException e) {
                throw new RuntimeException("Invalid label: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Failed to define data: " + e.getMessage(), e);
            }
        });

        String labelInfo = (label != null && !label.isEmpty()) ? " with label '" + label + "'" : "";
        return "Defined " + dataType + " at " + address + labelInfo;
    }

    @Tool(description = "Clear/undefine data in a range")
    public String clearData(String address, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive");
        }

        runTransaction("Clear data", () -> {
            try {
                Address addr = requireAddress(address);
                Address endAddr = addr.add(size - 1);
                getProgram().getListing().clearCodeUnits(addr, endAddr, false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to clear data: " + e.getMessage(), e);
            }
        });

        return "Cleared " + size + " bytes at " + address;
    }

    // ===== Batch Operations =====

    @Tool(description = "Rename multiple functions in a single transaction. oldNames and newNames must be parallel lists of equal length.")
    public String batchRenameFunctions(List<String> oldNames, List<String> newNames) {
        if (oldNames.size() != newNames.size()) {
            throw new IllegalArgumentException(
                    "oldNames and newNames lists must be the same length (got " +
                    oldNames.size() + " and " + newNames.size() + ")");
        }

        var errors = new ArrayList<String>();
        var successCount = new AtomicReference<>(0);

        runTransaction("Batch rename functions", () -> {
            for (int i = 0; i < oldNames.size(); i++) {
                try {
                    var function = requireFunction(oldNames.get(i));
                    function.setName(newNames.get(i), SourceType.USER_DEFINED);
                    successCount.set(successCount.get() + 1);
                } catch (Exception e) {
                    errors.add(oldNames.get(i) + " (" + e.getMessage() + ")");
                }
            }
        });

        if (errors.isEmpty()) {
            return "Renamed " + successCount.get() + " functions successfully";
        } else {
            return "Renamed " + successCount.get() + "/" + oldNames.size() + " functions. " +
                    "Errors: " + String.join(", ", errors);
        }
    }

    @Tool(description = "Set comments on multiple functions in a single transaction. functionNames and comments must be parallel lists of equal length.")
    public String batchSetComments(List<String> functionNames, List<String> comments) {
        if (functionNames.size() != comments.size()) {
            throw new IllegalArgumentException(
                    "functionNames and comments lists must be the same length (got " +
                    functionNames.size() + " and " + comments.size() + ")");
        }

        var errors = new ArrayList<String>();
        var successCount = new AtomicReference<>(0);

        runTransaction("Batch set comments", () -> {
            for (int i = 0; i < functionNames.size(); i++) {
                try {
                    var function = requireFunction(functionNames.get(i));
                    function.setComment(comments.get(i));
                    successCount.set(successCount.get() + 1);
                } catch (Exception e) {
                    errors.add(functionNames.get(i) + " (" + e.getMessage() + ")");
                }
            }
        });

        if (errors.isEmpty()) {
            return "Set comments on " + successCount.get() + " functions successfully";
        } else {
            return "Set comments on " + successCount.get() + "/" + functionNames.size() + " functions. " +
                    "Errors: " + String.join(", ", errors);
        }
    }

    // ===== Program Rebase =====

    @Tool(description = "Change the program's image base address. This shifts all addresses and is useful for aligning static analysis with runtime addresses from a debugger. Warning: may invalidate existing bookmarks. Address should typically be page-aligned (0x1000 boundary).")
    public String rebaseProgram(String newBaseAddress) {
        var oldBase = new AtomicReference<Address>();
        var newBase = new AtomicReference<Address>();

        runTransaction("Rebase program", () -> {
            try {
                Program program = getProgram();
                oldBase.set(program.getImageBase());
                newBase.set(requireAddress(newBaseAddress));
                program.setImageBase(newBase.get(), true);
            } catch (Exception e) {
                throw new RuntimeException("Failed to rebase program: " + e.getMessage(), e);
            }
        });

        return "Rebased program from " + oldBase.get() + " to " + newBase.get();
    }
}
