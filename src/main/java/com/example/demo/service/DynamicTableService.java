package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicTableService {

    private final JdbcTemplate defaultJdbc;
    private final ConnectionManagerService connMgr;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Set<String>> schemaCache = new ConcurrentHashMap<>();

    public DynamicTableService(JdbcTemplate defaultJdbc, ConnectionManagerService connMgr) {
        this.defaultJdbc = defaultJdbc;
        this.connMgr = connMgr;
    }

    // ==================== 数据库感知 ====================

    private JdbcTemplate jdbc(String connectionId) {
        if (connectionId == null || connectionId.isEmpty()) return defaultJdbc;
        return connMgr.get(connectionId);
    }

    /** 获取 DB 类型 (用于 SQL 方言适配) */
    private String dbType(String connectionId) {
        if (connectionId == null || connectionId.isEmpty()) return "H2";
        try {
            Map<String, Object> info = connMgr.getInfo(connectionId);
            return String.valueOf(info.getOrDefault("TYPE", "H2")).toUpperCase();
        } catch (Exception e) { return "H2"; }
    }

    /** 标识符引用: MySQL/MariaDB→反引号, 其他→双引号 */
    private String q(String id, String connectionId) {
        String t = dbType(connectionId);
        if ("MYSQL".equals(t) || "MARIADB".equals(t)) return "`" + id + "`";
        return "\"" + id + "\"";
    }

    private String cacheKey(String connId, String table) {
        return (connId != null ? connId : "_") + ":" + safeTableName(table);
    }

    // ==================== 公共 API ====================

    @Transactional
    public Map<String, Object> ingest(String tableName, String json, String mode, String keyColumn, String connectionId) {
        try {
            JsonNode root = mapper.readTree(json);
            List<Map<String, Object>> rows;
            if (root.isArray()) {
                rows = new ArrayList<>();
                for (JsonNode node : root) {
                    if (node.isObject()) rows.add(jsonNodeToMap(node));
                }
            } else if (root.isObject()) {
                rows = Collections.singletonList(jsonNodeToMap(root));
            } else {
                throw new IllegalArgumentException("JSON 必须是对象或对象数组");
            }

            Map<String, String> finalSchema = inferSchema(rows);
            ensureTable(tableName, finalSchema, connectionId);

            String actualMode = (mode != null) ? mode.toLowerCase() : "append";
            int count;
            if ("upsert".equals(actualMode) && keyColumn != null && !keyColumn.isEmpty()) {
                count = upsertRows(tableName, rows, finalSchema, keyColumn, connectionId);
            } else if ("upsert".equals(actualMode)) {
                throw new IllegalArgumentException("upsert 模式需要指定 keyColumn 参数");
            } else {
                count = insertRows(tableName, rows, finalSchema, connectionId);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true); result.put("table", tableName);
            result.put("connectionId", connectionId != null ? connectionId : "default");
            result.put("mode", actualMode); result.put("rowsInserted", count);
            result.put("columns", finalSchema);
            return result;

        } catch (Exception e) {
            throw new RuntimeException("入库失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> ingest(String tableName, String json, String mode, String keyColumn) {
        return ingest(tableName, json, mode, keyColumn, null);
    }

    public List<Map<String, Object>> listTables(String connectionId) {
        JdbcTemplate j = jdbc(connectionId);
        for (String sql : new String[]{
            "SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA IN ('PUBLIC','public','dbo') ORDER BY TABLE_NAME",
            "SELECT TABLE_NAME, 'TABLE' AS TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES ORDER BY TABLE_NAME",
            "SELECT name AS TABLE_NAME, 'TABLE' AS TABLE_TYPE FROM sqlite_master WHERE type='table' ORDER BY name",
            "SELECT table_name AS TABLE_NAME, 'TABLE' AS TABLE_TYPE FROM all_tables ORDER BY table_name",
            "SHOW TABLES"
        }) {
            try { List<Map<String, Object>> r = j.queryForList(sql); if (r.stream().anyMatch(m -> !m.isEmpty())) return r; }
            catch (Exception ignored) {}
        }
        return List.of();
    }

    public List<Map<String, Object>> describeTable(String tableName, String connectionId) {
        String safe = safeTableName(tableName);
        JdbcTemplate j = jdbc(connectionId);
        for (String sql : new String[]{
            "SHOW COLUMNS FROM " + q(safe, connectionId),
            "DESCRIBE " + q(safe, connectionId),
            "DESCRIBE " + safe,
            "SELECT COLUMN_NAME AS FIELD, DATA_TYPE AS TYPE, IS_NULLABLE AS \"NULL\" FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='" + safe + "' ORDER BY ORDINAL_POSITION",
            "PRAGMA table_info('" + safe + "')",
        }) {
            try { List<Map<String, Object>> r = j.queryForList(sql); if (!r.isEmpty()) return r; }
            catch (Exception ignored) {}
        }
        return List.of();
    }

    public List<Map<String, Object>> queryTable(String tableName, int limit, String connectionId) {
        String safe = safeTableName(tableName);
        return jdbc(connectionId).queryForList(
            "SELECT * FROM " + q(safe, connectionId) + " LIMIT " + limit);
    }

    public int countRows(String tableName, String connectionId) {
        String safe = safeTableName(tableName);
        Integer c = jdbc(connectionId).queryForObject(
            "SELECT COUNT(*) FROM " + q(safe, connectionId), Integer.class);
        return c == null ? 0 : c;
    }

    public void dropTable(String tableName, String connectionId) {
        String safe = safeTableName(tableName);
        String t = q(safe, connectionId);
        jdbc(connectionId).execute("DROP TABLE IF EXISTS " + t);
        schemaCache.remove(cacheKey(connectionId, safe));
    }

    // ==================== 私有方法 ====================

    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            map.put(f.getKey(), jsonNodeToValue(f.getValue()));
        }
        return map;
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node.isNull())    return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt())     return node.asInt();
        if (node.isLong())    return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isNumber())  return node.decimalValue();
        if (node.isTextual()) return node.asText();
        if (node.isArray() || node.isObject()) return node.toString();
        return node.asText();
    }

    private Map<String, String> inferSchema(List<Map<String, Object>> rows) {
        Map<String, String> schema = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
            for (Map.Entry<String, Object> e : row.entrySet())
                schema.put(e.getKey(), mergeType(schema.get(e.getKey()), javaTypeToSql(e.getValue())));
        return schema;
    }

    private String javaTypeToSql(Object value) {
        if (value == null)             return "VARCHAR(512)";
        if (value instanceof Boolean)  return "BOOLEAN";
        if (value instanceof Integer)  return "INTEGER";
        if (value instanceof Long)     return "BIGINT";
        if (value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal)
            return "DOUBLE PRECISION";
        String s = value.toString();
        if (s.length() > 4000) return "CLOB";
        if (s.length() > 512)  return "VARCHAR(" + (s.length() + 200) + ")";
        return "VARCHAR(512)";
    }

    private String mergeType(String t1, String t2) {
        if (t1 == null) return t2; if (t2 == null) return t1;
        return t1.equals(t2) ? t1 : "VARCHAR(1024)";
    }

    private void ensureTable(String tableName, Map<String, String> schema, String connectionId) {
        String safe = safeTableName(tableName);
        String ck = cacheKey(connectionId, safe);
        JdbcTemplate j = jdbc(connectionId);
        String Q = q(safe, connectionId);

        Set<String> existingCols = schemaCache.get(ck);
        if (existingCols == null) {
            existingCols = loadExistingColumns(safe, j);
            schemaCache.put(ck, existingCols);
        }

        if (existingCols.isEmpty()) {
            StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(Q).append(" (\n");
            ddl.append("  _id BIGINT AUTO_INCREMENT PRIMARY KEY");
            for (Map.Entry<String, String> col : schema.entrySet()) {
                String cn = toSnake(col.getKey()).toUpperCase();
                ddl.append(",\n  ").append(q(cn, connectionId)).append(" ").append(col.getValue());
            }
            ddl.append("\n)");
            j.execute(ddl.toString());
            for (String key : schema.keySet()) existingCols.add(toSnake(key).toUpperCase());
        } else {
            for (Map.Entry<String, String> col : schema.entrySet()) {
                String cn = toSnake(col.getKey()).toUpperCase();
                if (!existingCols.contains(cn)) {
                    j.execute("ALTER TABLE " + Q + " ADD COLUMN " + q(cn, connectionId) + " " + col.getValue());
                    existingCols.add(cn);
                }
            }
        }
    }

    private Set<String> loadExistingColumns(String tableName, JdbcTemplate j) {
        try {
            return new HashSet<>(j.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME=?",
                String.class, tableName));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private int insertRows(String tableName, List<Map<String, Object>> rows, Map<String, String> schema, String connectionId) {
        String safe = safeTableName(tableName);
        String Q = q(safe, connectionId);
        JdbcTemplate j = jdbc(connectionId);
        List<String> cols = new ArrayList<>(schema.keySet());

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(Q).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(q(toSnake(cols.get(i)).toUpperCase(), connectionId));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) { if (i > 0) sql.append(", "); sql.append("?"); }
        sql.append(")");

        List<Object[]> batchArgs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object[] args = new Object[cols.size()];
            for (int i = 0; i < cols.size(); i++) args[i] = row.get(cols.get(i));
            batchArgs.add(args);
        }
        return Arrays.stream(j.batchUpdate(sql.toString(), batchArgs)).sum();
    }

    private int upsertRows(String tableName, List<Map<String, Object>> rows, Map<String, String> schema,
                           String keyColumn, String connectionId) {
        String safe = safeTableName(tableName);
        String keyCol = toSnake(keyColumn).toUpperCase();
        String Q = q(safe, connectionId);
        String K = q(keyCol, connectionId);
        JdbcTemplate j = jdbc(connectionId);

        try {
            j.execute("CREATE UNIQUE INDEX idx_" + safe + "_" + keyCol + " ON " + Q + " (" + K + ")");
        } catch (Exception e) {
            throw new RuntimeException("无法创建唯一索引: 列 '" + keyColumn + "' 存在重复值", e);
        }

        int count = 0;
        for (Map<String, Object> row : rows) {
            Object keyVal = row.get(keyColumn);
            if (keyVal == null) throw new RuntimeException("upsert 行缺少 key 值: " + keyColumn);

            List<Map<String, Object>> existing = j.queryForList(
                "SELECT * FROM " + Q + " WHERE " + K + " = ?", keyVal);

            Map<String, Object> merged = new LinkedHashMap<>();
            if (!existing.isEmpty()) {
                Map<String, Object> old = existing.get(0);
                for (String k : old.keySet()) { if (!"_id".equalsIgnoreCase(k)) merged.put(k, old.get(k)); }
                j.update("DELETE FROM " + Q + " WHERE " + K + " = ?", keyVal);
            }
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getValue() != null) merged.put(toSnake(e.getKey()).toUpperCase(), e.getValue());
            }
            if (!merged.isEmpty()) {
                List<String> cols = new ArrayList<>(merged.keySet());
                StringBuilder sql = new StringBuilder("INSERT INTO ").append(Q).append(" (");
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) sql.append(", "); sql.append(q(cols.get(i), connectionId));
                }
                sql.append(") VALUES (");
                for (int i = 0; i < cols.size(); i++) { if (i > 0) sql.append(", "); sql.append("?"); }
                sql.append(")");
                j.update(sql.toString(), cols.stream().map(merged::get).toArray());
                count++;
            }
        }
        return count;
    }

    private String safeTableName(String raw) {
        return raw.replaceAll("[\"';`]", "").replaceAll("\\s+", "_").trim();
    }

    private String toSnake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2");
    }
}
