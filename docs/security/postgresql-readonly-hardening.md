# PostgreSQL 只读账号加固

本次发布阻断修复配套的数据库侧建议如下：

1. 业务查询账号只授予 `SELECT` 到业务报表所需表，不授予 `CREATE`、`TEMP`、`USAGE` 到系统 schema，也不授予函数执行扩展权限。
2. 显式回收对 `pg_catalog`、`information_schema` 以外不必要对象的访问权限，并禁止安装/使用 `dblink`、`file_fdw`、`postgres_fdw` 等跨库或文件相关扩展给报表账号。
3. 业务 datasource 继续保持 JDBC `readOnly=true`，并配合数据库角色侧 `default_transaction_read_only=on`。
4. 将连接池账号与运维账号分离，不复用高权限用户到 Text2Report。
5. 审计数据库侧应记录登录、查询超时和权限拒绝事件，与应用层 `audit_query_logs` 形成双重审计。
