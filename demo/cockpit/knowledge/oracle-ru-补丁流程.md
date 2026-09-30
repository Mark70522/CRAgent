# Oracle RU 补丁流程

## 回退实测 40 分钟,不是 20  (2026-06-20)

srv-db-uat-01 上 opatch rollback 到 19.22 实测 40 分钟,预估只留了 20。以后回退时间按 40 分钟起算。

## datapatch 之后要重启监听  (2026-06-20)

datapatch 跑完监听不会自动恢复,应用连接池报错到你重启监听为止。步骤:备份 → opatch apply → datapatch → 重启监听 → 恢复应用连接池。
