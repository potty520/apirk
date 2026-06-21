package com.example.demo.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConnectionManagerService {

    private final JdbcTemplate metaJdbc; // 默认库: 存连接配置
    private final Map<String, JdbcTemplate> pool = new ConcurrentHashMap<>();
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

    public ConnectionManagerService(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
        initMetaTable();
        loadSavedConnections();
    }

    private void initMetaTable() {
        // 跨数据库兼容 DDL (无 DEFAULT 值, 无数据库特定语法)
        try {
            metaJdbc.execute("""
                CREATE TABLE IF NOT EXISTS db_connections (
                    id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    url VARCHAR(500) NOT NULL,
                    username VARCHAR(100),
                    password VARCHAR(200),
                    created_at TIMESTAMP
                )
                """);
        } catch (Exception e) {
            System.err.println("元数据表 db_connections 创建失败: " + e.getMessage());
        }
    }

    private void loadSavedConnections() {
        List<Map<String, Object>> saved = metaJdbc.queryForList("SELECT * FROM db_connections");
        for (Map<String, Object> row : saved) {
            try {
                createFromConfig(row);
            } catch (Exception e) {
                System.err.println("加载连接失败 " + row.get("name") + ": " + e.getMessage());
            }
        }
    }

    /** 获取连接 (不存在则自动创建) */
    public JdbcTemplate get(String id) {
        JdbcTemplate t = pool.get(id);
        if (t == null) {
            Map<String, Object> cfg = metaJdbc.queryForMap("SELECT * FROM db_connections WHERE id = ?", id);
            t = createFromConfig(cfg);
        }
        return t;
    }

    /** 获取默认连接 */
    public JdbcTemplate getDefault() {
        return metaJdbc;
    }

    /** 列出所有连接 */
    public List<Map<String, Object>> listAll() {
        return metaJdbc.queryForList("SELECT id, name, type, url, username, created_at FROM db_connections ORDER BY created_at");
    }

    /** 添加连接 (仅保存配置, 懒连接) */
    public Map<String, Object> add(String name, String type, String url, String username, String password) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        metaJdbc.update(
            "INSERT INTO db_connections (id, name, type, url, username, password, created_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            id, name, type.toUpperCase(), url, username, password
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("id", id);
        result.put("name", name);
        result.put("type", type);
        return result;
    }

    /** 测试连接 */
    public Map<String, Object> test(String type, String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5000);

        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try (HikariDataSource ds = new HikariDataSource(config);
             Connection conn = ds.getConnection()) {
            result.put("success", true);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("dbProduct", conn.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 删除连接 */
    public void remove(String id) {
        closeConnection(id);
        metaJdbc.update("DELETE FROM db_connections WHERE id = ?", id);
    }

    /** 获取连接信息 */
    public Map<String, Object> getInfo(String id) {
        return metaJdbc.queryForMap("SELECT * FROM db_connections WHERE id = ?", id);
    }

    private static final Map<String, String> DRIVER_CLASS = Map.of(
        "ORACLE", "oracle.jdbc.OracleDriver",
        "SQLSERVER", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "MYSQL", "com.mysql.cj.jdbc.Driver",
        "MARIADB", "org.mariadb.jdbc.Driver",
        "POSTGRESQL", "org.postgresql.Driver",
        "SQLITE", "org.sqlite.JDBC",
        "H2", "org.h2.Driver"
    );

    private JdbcTemplate createFromConfig(Map<String, Object> cfg) {
        String id = (String) cfg.get("ID");
        String type = String.valueOf(cfg.getOrDefault("TYPE", "H2")).toUpperCase();
        String url = (String) cfg.get("URL");
        String user = String.valueOf(cfg.getOrDefault("USERNAME", ""));
        String pass = String.valueOf(cfg.getOrDefault("PASSWORD", ""));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(300000);
        config.setPoolName("pool-" + id);

        String driver = DRIVER_CLASS.get(type);
        if (driver != null) config.setDriverClassName(driver);

        HikariDataSource ds = new HikariDataSource(config);
        JdbcTemplate jt = new JdbcTemplate(ds);

        pool.put(id, jt);
        dataSources.put(id, ds);
        return jt;
    }

    private void closeConnection(String id) {
        HikariDataSource ds = dataSources.remove(id);
        if (ds != null) ds.close();
        pool.remove(id);
    }
}
