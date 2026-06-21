# 🐚 动态建表入库平台 (API Reverse Ingest Kit)

> 粘贴 JSON / 上传 Word → 自动建表 → 增量入库 → 多库路由 → 定时调度

## 快速开始

```bash
# 1. 构建
mvn clean package -DskipTests

# 2. 启动 (端口 8765)
java -jar target/auto-table-api-1.0.0.jar

# 3. 打开前端
open http://localhost:8765
```

## 功能概览

### 📥 数据入库

**POST /api/ingest** — 提交 JSON, 自动建表+入库

| 参数 | 类型 | 说明 |
|------|------|------|
| `table` | string | 目标表名（必填） |
| `data` | object/array | JSON 对象或对象数组 |
| `mode` | string | `append` 追加 (默认) / `upsert` 去重更新 |
| `keyColumn` | string | upsert 模式的主键列名（驼峰） |
| `connectionId` | string | 目标数据库连接 ID（空=默认H2） |

**示例:**

```bash
# 追加模式
curl -X POST http://localhost:8765/api/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "table": "users",
    "mode": "append",
    "data": [
      {"name":"张三","age":28,"email":"zs@test.com"},
      {"name":"李四","age":35,"email":"lisi@test.com"}
    ]
  }'

# 去重更新 (按 id 合并, 新字段覆盖旧值, 未传字段保留)
curl -X POST http://localhost:8765/api/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "table": "users",
    "mode": "upsert",
    "keyColumn": "id",
    "data": {"id":1,"email":"new@test.com"}
  }'

# 写入 MySQL
curl -X POST http://localhost:8765/api/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "table": "employees",
    "connectionId": "abc123",
    "mode": "append",
    "data": [{"name":"王五","dept":"研发"}]
  }'
```

**自动类型推断:**

| JSON 类型 | SQL 类型 |
|-----------|----------|
| `"hello"` | VARCHAR(512) |
| `true`/`false` | BOOLEAN |
| `28` | INTEGER |
| `9999999999` | BIGINT |
| `3.14` | DOUBLE PRECISION |
| 长文本 (>4000) | CLOB |
| 嵌套 `{}` `[]` | VARCHAR(512) (序列化) |

### 📄 Word 文档上传

**POST /api/upload/word** — 上传 .docx, 自动解析所有表格入库

| 参数 | 说明 |
|------|------|
| `file` | .docx 文件 (multipart) |
| `mode` | 入库模式 (可选) |
| `keyColumn` | 主键列 (可选) |
| `connectionId` | 目标库 (可选) |

**智能解析:**
- 表格上方段落 → 自动作为表名
- 第一行全部是字符串 → 自动识别为表头
- 数字列 → 数值类型, 文本列 → VARCHAR
- 合并单元格 → 自动处理

```bash
curl -X POST http://localhost:8765/api/upload/word \
  -F "file=@接口文档.docx" \
  -F "mode=upsert" \
  -F "keyColumn=编号"
```

### 🔗 数据库连接管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/connections` | 列出所有连接 |
| POST | `/api/connections` | 添加连接 |
| POST | `/api/connections/test` | 测试连接可用性 |
| DELETE | `/api/connections/{id}` | 删除连接 |

**支持的数据库:**

| 数据库 | JDBC URL 模板 |
|--------|--------------|
| H2 | `jdbc:h2:file:./data/mydb` |
| MySQL | `jdbc:mysql://host:3306/db?useSSL=false&serverTimezone=Asia/Shanghai` |
| MariaDB | `jdbc:mariadb://host:3306/db` |
| PostgreSQL | `jdbc:postgresql://host:5432/db` |
| SQL Server | `jdbc:sqlserver://host:1433;database=db;encrypt=false` |
| Oracle | `jdbc:oracle:thin:@host:1521/XEPDB1` |
| SQLite | `jdbc:sqlite:./data/mydb.db` |

```bash
# 添加 MySQL
curl -X POST http://localhost:8765/api/connections \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "生产库",
    "type": "MySQL",
    "url": "jdbc:mysql://192.168.1.100:3306/prod?useSSL=false&serverTimezone=Asia/Shanghai",
    "username": "app",
    "password": "xxx"
  }'

# 测试连接
curl -X POST http://localhost:8765/api/connections/test \
  -H 'Content-Type: application/json' \
  -d '{"type":"MySQL","url":"jdbc:mysql://...","username":"app","password":"xxx"}'
```

### ⏰ 定时任务

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tasks` | 任务列表 |
| POST | `/api/tasks` | 创建任务 |
| DELETE | `/api/tasks/{id}` | 删除 |
| PUT | `/api/tasks/{id}/toggle` | 启用/停用 |
| POST | `/api/tasks/{id}/trigger` | 立即执行 |

**Cron 表达式模板:**

| 频率 | 表达式 |
|------|--------|
| 每天 08:00 | `0 8 * * *` |
| 每天 12:00 | `0 12 * * *` |
| 每天 18:00 | `0 18 * * *` |
| 每周一 08:00 | `0 8 * * 1` |
| 工作日 08:00 | `0 8 * * 1-5` |
| 每 6 小时 | `0 */6 * * *` |

```bash
# 创建每天 8 点同步任务
curl -X POST http://localhost:8765/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "每日用户同步",
    "tableName": "users",
    "connectionId": "abc123",
    "cronExpr": "0 8 * * *",
    "cronDesc": "每天08:00",
    "url": "https://api.example.com/users"
  }'

# 手动触发
curl -X POST http://localhost:8765/api/tasks/abc123/trigger

# 停用任务
curl -X PUT http://localhost:8765/api/tasks/abc123/toggle \
  -H 'Content-Type: application/json' \
  -d '{"enabled":false}'
```

### 📊 表管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tables?connectionId=` | 表列表 |
| GET | `/api/tables/{name}/describe?connectionId=` | 表结构 |
| GET | `/api/tables/{name}/data?limit=50&connectionId=` | 数据查询 |
| DELETE | `/api/tables/{name}/drop?connectionId=` | 删除表 |

## 架构设计

```
┌─────────────────────────────────────────────┐
│                   Web UI                     │
│     (数据入库 / 连接管理 / 定时任务)           │
└──────────────────┬──────────────────────────┘
                   │ REST API
┌──────────────────▼──────────────────────────┐
│          DynamicTableController              │
│   /api/ingest  /api/upload/word  ...         │
└──────┬──────────────┬───────────┬───────────┘
       │              │           │
       ▼              ▼           ▼
┌────────────┐ ┌──────────┐ ┌───────────────┐
│ Dynamic    │ │ Word     │ │ Task          │
│ Table      │ │ Parser   │ │ Scheduler     │
│ Service    │ │ Service  │ │ Service       │
└─────┬──────┘ └──────────┘ └───────────────┘
      │
      ▼
┌─────────────────────────────────────────────┐
│       ConnectionManagerService              │
│   ┌─────────┐ ┌─────────┐ ┌─────────┐      │
│   │  H2     │ │  MySQL  │ │ SQLite  │ ...  │
│   │ (默认)   │ │ Hikari  │ │ Hikari  │      │
│   └─────────┘ └─────────┘ └─────────┘      │
└─────────────────────────────────────────────┘
```

## 写入模式详解

### append — 纯追加

- 不检查重复, 直接 INSERT
- 适合作日志、事件流、时序数据
- 性能最高

### upsert — 去重更新

- 指定 keyColumn 作为唯一标识
- key 不存在 → INSERT 新行
- key 已存在 → 合并新旧数据后 DELETE+INSERT
- **部分字段更新**: 新数据有的列覆盖旧值, 新数据没传的列保留旧值
- 自动创建唯一索引

```
第1次 upsert: {id:1, name:"张三", age:28}
  → INSERT (id=1, name=张三, age=28)

第2次 upsert: {id:1, email:"zs@test.com"}
  → 查询旧行 → DELETE 旧行 → INSERT (id=1, name=张三, age=28, email=zs@test.com)
  ↑ age 和 name 保留！
```

## Schema 进化

入库时自动处理新字段:

```bash
# 第1次: 只有 name, age
→ CREATE TABLE users (_id, NAME, AGE)

# 第2次: 多了 email
→ ALTER TABLE users ADD COLUMN EMAIL VARCHAR(512)

# 第3次: 多了 phone, salary
→ ALTER TABLE users ADD COLUMN PHONE VARCHAR(512)
→ ALTER TABLE users ADD COLUMN SALARY DOUBLE PRECISION
```

## 配置说明

`application.properties`:

```properties
server.port=8765                                    # 服务端口
spring.servlet.multipart.max-file-size=50MB         # 上传限制
spring.datasource.url=jdbc:h2:file:./data/autodb    # 元数据库 (H2 持久化)
```

## 技术栈

- **后端**: Spring Boot 3.2 + JDBC + HikariCP
- **数据库**: H2 (元数据) + MySQL/MariaDB/PostgreSQL/SQL Server/Oracle/SQLite (目标库)
- **文档解析**: Apache POI (.docx)
- **定时调度**: Spring TaskScheduler + Cron
- **前端**: 原生 HTML/CSS/JS (暗色主题, 无框架依赖)

## License

MIT
