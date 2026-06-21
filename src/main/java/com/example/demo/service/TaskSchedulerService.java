package com.example.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class TaskSchedulerService {

    private final JdbcTemplate metaJdbc;
    private final TaskScheduler scheduler;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();

    public TaskSchedulerService(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
        ThreadPoolTaskScheduler ts = new ThreadPoolTaskScheduler();
        ts.setPoolSize(4);
        ts.setThreadNamePrefix("task-");
        ts.initialize();
        this.scheduler = ts;
        initMetaTable();
        restoreTasks();
    }

    private void initMetaTable() {
        metaJdbc.execute("""
            CREATE TABLE IF NOT EXISTS scheduled_tasks (
                id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(200) NOT NULL,
                table_name VARCHAR(200) NOT NULL,
                connection_id VARCHAR(64) DEFAULT '',
                source_type VARCHAR(20) DEFAULT 'json',
                cron_expr VARCHAR(100) NOT NULL,
                cron_desc VARCHAR(200) DEFAULT '',
                enabled BOOLEAN DEFAULT TRUE,
                url VARCHAR(500) DEFAULT '',
                json_body CLOB DEFAULT '{}',
                last_run TIMESTAMP NULL,
                next_run TIMESTAMP NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
    }

    /** 列出所有任务 */
    public List<Map<String, Object>> listAll() {
        return metaJdbc.queryForList(
            "SELECT id, name, table_name, connection_id, source_type, cron_expr, cron_desc, enabled, last_run, next_run, created_at FROM scheduled_tasks ORDER BY created_at"
        );
    }

    /** 添加任务 */
    public Map<String, Object> add(String name, String tableName, String connectionId,
                                    String sourceType, String cronExpr, String cronDesc,
                                    String url, String jsonBody) {
        String id = UUID.randomUUID().toString().substring(0, 8);

        metaJdbc.update(
            "INSERT INTO scheduled_tasks (id, name, table_name, connection_id, source_type, cron_expr, cron_desc, enabled, url, json_body) VALUES (?,?,?,?,?,?,?,TRUE,?,?)",
            id, name, tableName, connectionId != null ? connectionId : "", sourceType, cronExpr, cronDesc, url, jsonBody
        );

        // 尝试启动 (失败不影响保存)
        try { startTask(id); } catch (Exception e) {
            System.err.println("任务启动失败 " + name + ": " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("id", id);
        result.put("name", name);
        return result;
    }

    /** 删除任务 */
    public void remove(String id) {
        stopTask(id);
        metaJdbc.update("DELETE FROM scheduled_tasks WHERE id = ?", id);
    }

    /** 启停 */
    public void setEnabled(String id, boolean enabled) {
        if (enabled) {
            metaJdbc.update("UPDATE scheduled_tasks SET enabled = TRUE WHERE id = ?", id);
            startTask(id);
        } else {
            metaJdbc.update("UPDATE scheduled_tasks SET enabled = FALSE WHERE id = ?", id);
            stopTask(id);
        }
    }

    /** 立即触发一次 */
    public void triggerNow(String id) {
        Map<String, Object> task = metaJdbc.queryForMap("SELECT * FROM scheduled_tasks WHERE id = ?", id);
        executeTask(task);
    }

    // --- internal ---

    private void restoreTasks() {
        List<Map<String, Object>> tasks = metaJdbc.queryForList("SELECT * FROM scheduled_tasks WHERE enabled = TRUE");
        for (Map<String, Object> t : tasks) {
            try {
                startTask((String) t.get("ID"));
            } catch (Exception e) {
                System.err.println("恢复任务失败 " + t.get("NAME") + ": " + e.getMessage());
            }
        }
    }

    private void startTask(String id) {
        stopTask(id);

        Map<String, Object> task = metaJdbc.queryForMap("SELECT * FROM scheduled_tasks WHERE id = ?", id);
        String cronExpr = (String) task.get("CRON_EXPR");

        ScheduledFuture<?> future = scheduler.schedule(
            () -> executeTask(task),
            new CronTrigger(cronExpr, TimeZone.getTimeZone("Asia/Shanghai"))
        );
        runningTasks.put(id, future);

        metaJdbc.update("UPDATE scheduled_tasks SET enabled = TRUE WHERE id = ?", id);
        System.out.println("任务已启动: " + task.get("NAME") + " cron=" + cronExpr);
    }

    private void stopTask(String id) {
        ScheduledFuture<?> f = runningTasks.remove(id);
        if (f != null) f.cancel(false);
    }

    private void executeTask(Map<String, Object> task) {
        String id = (String) task.get("ID");
        String name = (String) task.get("NAME");
        System.out.println("[" + LocalDateTime.now() + "] 执行任务: " + name);

        try {
            metaJdbc.update("UPDATE scheduled_tasks SET last_run = CURRENT_TIMESTAMP WHERE id = ?", id);
            // 任务体执行由外部注入, 这里只做调度记录
            // 实际 call 在 controller 层通过 REST 自调用
        } catch (Exception e) {
            System.err.println("任务执行失败 " + name + ": " + e.getMessage());
        }
    }
}
