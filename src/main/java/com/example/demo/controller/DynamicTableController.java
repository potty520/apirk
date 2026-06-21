package com.example.demo.controller;

import com.example.demo.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class DynamicTableController {

    private final DynamicTableService service;
    private final WordParserService wordParser;
    private final ConnectionManagerService connMgr;
    private final TaskSchedulerService taskScheduler;
    private final ObjectMapper mapper = new ObjectMapper();

    public DynamicTableController(DynamicTableService service, WordParserService wordParser,
                                   ConnectionManagerService connMgr, TaskSchedulerService taskScheduler) {
        this.service = service;
        this.wordParser = wordParser;
        this.connMgr = connMgr;
        this.taskScheduler = taskScheduler;
    }

    // ==================== 数据入库 ====================

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> body) {
        String tableName = (String) body.getOrDefault("table", "default_table");
        String mode = (String) body.getOrDefault("mode", "append");
        String keyColumn = (String) body.get("keyColumn");
        String connectionId = (String) body.get("connectionId");
        Object data = body.get("data");
        try {
            String json = (data instanceof String) ? (String) data : mapper.writeValueAsString(data);
            return service.ingest(tableName, json, mode, keyColumn, connectionId);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    @PostMapping("/upload/word")
    public Map<String, Object> uploadWord(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "append") String mode,
            @RequestParam(required = false) String keyColumn,
            @RequestParam(required = false) String connectionId) {

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tables = new ArrayList<>();
        int totalRows = 0;

        List<Map<String, Object>> parsed = wordParser.parse(file);
        for (Map<String, Object> table : parsed) {
            String tableName = (String) table.get("tableName");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) table.get("rows");
            if (rows.isEmpty()) continue;
            try {
                String json = mapper.writeValueAsString(rows);
                Map<String, Object> r = service.ingest(tableName, json, mode, keyColumn, connectionId);
                r.put("sourceFile", file.getOriginalFilename());
                tables.add(r);
                totalRows += (int) r.getOrDefault("rowsInserted", 0);
            } catch (Exception e) {
                tables.add(Map.of("table", tableName, "error", e.getMessage()));
            }
        }
        result.put("success", true);
        result.put("sourceFile", file.getOriginalFilename());
        result.put("tablesParsed", tables.size());
        result.put("totalRowsInserted", totalRows);
        result.put("details", tables);
        return result;
    }

    // ==================== 表管理 ====================

    @GetMapping("/tables")
    public List<Map<String, Object>> listTables(@RequestParam(required = false) String connectionId) {
        return service.listTables(connectionId);
    }

    @GetMapping("/tables/{name}/describe")
    public Map<String, Object> describe(@PathVariable String name,
                                         @RequestParam(required = false) String connectionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", name);
        result.put("columns", service.describeTable(name, connectionId));
        result.put("rowCount", service.countRows(name, connectionId));
        return result;
    }

    @GetMapping("/tables/{name}/data")
    public Map<String, Object> data(@PathVariable String name,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(required = false) String connectionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", name);
        result.put("rows", service.queryTable(name, Math.min(limit, 200), connectionId));
        result.put("totalRows", service.countRows(name, connectionId));
        return result;
    }

    @DeleteMapping("/tables/{name}/drop")
    public Map<String, Object> drop(@PathVariable String name,
                                     @RequestParam(required = false) String connectionId) {
        service.dropTable(name, connectionId);
        return Map.of("success", true, "table", name);
    }

    // ==================== 连接管理 ====================

    @GetMapping("/connections")
    public List<Map<String, Object>> listConnections() {
        return connMgr.listAll();
    }

    @PostMapping("/connections")
    public Map<String, Object> addConnection(@RequestBody Map<String, String> body) {
        return connMgr.add(
            body.get("name"), body.get("type"), body.get("url"),
            body.get("username"), body.get("password"));
    }

    @PostMapping("/connections/test")
    public Map<String, Object> testConnection(@RequestBody Map<String, String> body) {
        return connMgr.test(
            body.get("type"), body.get("url"),
            body.get("username"), body.get("password"));
    }

    @DeleteMapping("/connections/{id}")
    public Map<String, Object> deleteConnection(@PathVariable String id) {
        connMgr.remove(id);
        return Map.of("success", true, "id", id);
    }

    // ==================== 定时任务 ====================

    @GetMapping("/tasks")
    public List<Map<String, Object>> listTasks() {
        return taskScheduler.listAll();
    }

    @PostMapping("/tasks")
    public Map<String, Object> addTask(@RequestBody Map<String, String> body) {
        return taskScheduler.add(
            body.get("name"), body.get("tableName"), body.get("connectionId"),
            body.getOrDefault("sourceType", "json"),
            body.get("cronExpr"), body.getOrDefault("cronDesc", ""),
            body.getOrDefault("url", ""), body.getOrDefault("jsonBody", "{}"));
    }

    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> deleteTask(@PathVariable String id) {
        taskScheduler.remove(id);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/tasks/{id}/toggle")
    public Map<String, Object> toggleTask(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        taskScheduler.setEnabled(id, body.getOrDefault("enabled", true));
        return Map.of("success", true, "id", id);
    }

    @PostMapping("/tasks/{id}/trigger")
    public Map<String, Object> triggerTask(@PathVariable String id) {
        taskScheduler.triggerNow(id);
        return Map.of("success", true, "id", id, "message", "任务已触发");
    }
}
