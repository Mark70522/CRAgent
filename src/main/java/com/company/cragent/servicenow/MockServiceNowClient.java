package com.company.cragent.servicenow;

import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.CiInfo;
import com.company.cragent.model.MaintenanceWindow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory ServiceNow so the whole flow can be exercised from Copilot without
 * network access. Seeded with four servers, three approved changes and one rejected one.
 */
@Component
@ConditionalOnProperty(name = "servicenow.mock", havingValue = "true", matchIfMissing = true)
public class MockServiceNowClient implements ServiceNowGateway {

    private final List<CiInfo> cis = new ArrayList<>();
    private final Map<String, ChangeRecord> changes = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(30010);
    private final AtomicInteger taskSeq = new AtomicInteger(50000);

    public MockServiceNowClient() {
        cis.add(new CiInfo("ci-0001", "srv-app-01", "10.0.1.11", "cmdb_ci_win_server", "Windows Server 2019", "prod", "Wintel Ops", "Order Portal", "Sat 02:00-06:00"));
        cis.add(new CiInfo("ci-0002", "srv-app-02", "10.0.1.12", "cmdb_ci_win_server", "Windows Server 2019", "prod", "Wintel Ops", "Order Portal", "Sat 02:00-06:00"));
        cis.add(new CiInfo("ci-0003", "srv-db-01", "10.0.2.21", "cmdb_ci_linux_server", "RHEL 8", "prod", "DBA Team", "Order Portal", "Sun 01:00-05:00"));
        cis.add(new CiInfo("ci-0004", "srv-app-uat-01", "10.0.9.11", "cmdb_ci_win_server", "Windows Server 2019", "uat", "Wintel Ops", "Order Portal", "Any weekday 18:00-22:00"));

        seed("CHG0030001", "approved", "successful",
                "[PATCH] srv-app-01 srv-app-02 - 2026-08 Windows monthly security patches",
                "1. 变更对象\nsrv-app-01, srv-app-02 (prod, Windows Server 2019, Order Portal 应用服务器)\n\n"
                        + "2. 补丁清单及来源\n2026-08 Microsoft 月度安全补丁 (KB5041585 等), 来源 WSUS 已审批基线\n\n"
                        + "3. 影响范围\n打补丁期间 Order Portal 前端不可用约 45 分钟, 已通知业务方, 在维护窗口内执行\n\n"
                        + "4. 执行步骤\n见 change tasks\n\n"
                        + "5. 验证方法\n重启后检查 IIS 站点 200, 应用健康检查接口返回 OK, 事件日志无红色错误\n\n"
                        + "6. 回退方案\n还原 VMware 快照, 预计 20 分钟",
                "normal", "Software", "Moderate", "srv-app-01", "Wintel Ops",
                "2026-08-15 02:00:00", "2026-08-15 06:00:00",
                List.of(task(10, "Pre-check and snapshot", "02:00", "02:30"),
                        task(20, "Apply Windows patches", "02:30", "04:30"),
                        task(30, "Reboot and validate services", "04:30", "05:15"),
                        task(40, "Post-check and close", "05:15", "05:30")),
                List.of(appr("CAB Manager", "approved", "OK")));

        seed("CHG0030002", "approved", "successful",
                "[PATCH] srv-db-01 - Oracle 19c 2026Q2 RU patch",
                "1. 变更对象\nsrv-db-01 (prod, RHEL 8, Oracle 19c, Order Portal 数据库)\n\n"
                        + "2. 补丁清单及来源\nOracle 19.23 Release Update, MOS patch 36233263\n\n"
                        + "3. 影响范围\n数据库停机约 90 分钟, Order Portal 全站不可用, 已获业务方书面确认\n\n"
                        + "4. 执行步骤\n见 change tasks\n\n"
                        + "5. 验证方法\nopatch lsinventory 显示新版本, 应用连接池恢复, 关键 SQL 性能对比基线\n\n"
                        + "6. 回退方案\nopatch rollback, 预计 40 分钟, 已在 UAT 演练",
                "normal", "Software", "High", "srv-db-01", "DBA Team",
                "2026-07-20 01:00:00", "2026-07-20 05:00:00",
                List.of(task(10, "Backup and pre-check", "01:00", "01:30"),
                        task(20, "Apply Oracle RU", "01:30", "03:00"),
                        task(30, "Datapatch and validate", "03:00", "04:00"),
                        task(40, "Release to application", "04:00", "04:30")),
                List.of(appr("CAB Manager", "approved", "Good backout plan")));

        seed("CHG0030003", "approved", "successful",
                "[RELEASE] Order Portal v2.14.0 to prod",
                "1. 变更对象\nOrder Portal 应用 v2.14.0, 部署到 srv-app-01, srv-app-02\n\n"
                        + "2. 版本内容\n发布单 REL-2214: 优惠券模块重构, 修复 BUG-4411/4420\n\n"
                        + "3. 影响范围\n蓝绿切换, 用户无感知; 切换失败则回退到 v2.13.2\n\n"
                        + "4. 执行步骤\n见 change tasks\n\n"
                        + "5. 验证方法\n冒烟用例 12 条全部通过, 监控错误率 < 0.1% 持续 30 分钟\n\n"
                        + "6. 回退方案\n切回旧版本 slot, 预计 5 分钟",
                "normal", "Software", "Moderate", "srv-app-01", "App Release Team",
                "2026-09-05 20:00:00", "2026-09-05 22:00:00",
                List.of(task(10, "Deploy to green slot", "20:00", "20:30"),
                        task(20, "Smoke test", "20:30", "21:00"),
                        task(30, "Switch traffic and monitor", "21:00", "22:00")),
                List.of(appr("CAB Manager", "approved", "")));

        seed("CHG0030004", "rejected", "",
                "patch servers",
                "install patches on app servers this weekend",
                "normal", "Software", "Low", "srv-app-01", "Wintel Ops",
                "2026-09-13 10:00:00", "2026-09-13 12:00:00",
                List.of(),
                List.of(appr("CAB Manager", "rejected",
                        "1) 标题不符合 [PATCH] 前缀规范 2) 描述没有补丁清单和影响范围 3) prod 变更必须有 backout plan "
                                + "4) 时间不在维护窗口内 (Sat 02:00-06:00) 5) 没有 change task")));
    }

    @Override public String instanceUrl() { return "https://mock.service-now.com"; }

    @Override
    public List<CiInfo> lookupCi(String nameOrIp) {
        String q = nameOrIp.toLowerCase();
        return cis.stream().filter(c -> c.name().toLowerCase().contains(q) || q.equals(c.ipAddress())).toList();
    }

    @Override
    public ChangeRecord getChange(String number) {
        ChangeRecord r = changes.get(number);
        if (r == null) throw new ServiceNowException("Change not found: " + number);
        return r;
    }

    @Override
    public List<ChangeRecord> queryChanges(String encodedQuery, int limit, boolean includeRelated) {
        // Very small subset of the encoded-query syntax: field=value joined with ^, plus LIKE and >=.
        List<ChangeRecord> out = new ArrayList<>();
        for (ChangeRecord r : changes.values()) {
            boolean match = true;
            for (String cond : encodedQuery.split("\\^")) {
                if (cond.isBlank() || cond.startsWith("ORDERBY")) continue;
                if (cond.contains("LIKE")) {
                    String[] kv = cond.split("LIKE", 2);
                    String v = r.field(kv[0].replace("cmdb_ci.name", "cmdb_ci"));
                    match &= v != null && v.toLowerCase().contains(kv[1].toLowerCase());
                } else if (cond.contains(">=")) {
                    String[] kv = cond.split(">=", 2);
                    String v = r.field(kv[0]);
                    match &= v != null && v.compareTo(kv[1]) >= 0;
                } else if (cond.contains("=")) {
                    String[] kv = cond.split("=", 2);
                    String v = r.field(kv[0].replace("cmdb_ci.name", "cmdb_ci"));
                    match &= kv[1].equalsIgnoreCase(v);
                }
            }
            if (match) out.add(r);
            if (out.size() >= limit) break;
        }
        return out;
    }

    @Override
    public List<Map<String, String>> findRejectedApprovals(String sinceDate, int limit) {
        List<Map<String, String>> out = new ArrayList<>();
        for (ChangeRecord r : changes.values()) {
            for (Map<String, String> a : r.approvals()) {
                if ("rejected".equals(a.get("state"))) {
                    Map<String, String> row = new LinkedHashMap<>(a);
                    row.put("sysapproval", r.number());
                    out.add(row);
                }
            }
        }
        return out;
    }

    @Override
    public List<MaintenanceWindow> getMaintenanceWindows(String ciName) {
        List<MaintenanceWindow> out = new ArrayList<>();
        for (CiInfo ci : lookupCi(ciName)) {
            out.add(new MaintenanceWindow(ci.maintenanceSchedule(), "maintenance", null, null, "Weekly window for " + ci.name()));
        }
        out.add(new MaintenanceWindow("Quarter-end freeze", "blackout", "2026-09-28 00:00:00", "2026-10-02 23:59:59", "No prod changes during quarter close"));
        return out;
    }

    @Override
    public Map<String, String> createChange(Map<String, String> fields) {
        String number = "CHG00" + seq.incrementAndGet();
        Map<String, String> f = new LinkedHashMap<>(fields);
        f.put("number", number);
        f.put("sys_id", UUID.randomUUID().toString().replace("-", ""));
        f.putIfAbsent("state", "New");
        f.putIfAbsent("approval", "not requested");
        changes.put(number, new ChangeRecord(f.get("sys_id"), number, f, new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
        return f;
    }

    @Override
    public List<String> addTasks(String changeSysId, List<Map<String, String>> tasks) {
        List<String> numbers = new ArrayList<>();
        changes.values().stream().filter(c -> c.sysId().equals(changeSysId)).findFirst().ifPresent(c -> {
            for (Map<String, String> t : tasks) {
                Map<String, String> row = new LinkedHashMap<>(t);
                row.put("number", "CTASK00" + taskSeq.incrementAndGet());
                c.tasks().add(row);
                numbers.add(row.get("number"));
            }
        });
        return numbers;
    }

    @Override
    public Map<String, String> updateChange(String number, Map<String, String> fields) {
        ChangeRecord r = getChange(number);
        r.fields().putAll(fields);
        return r.fields();
    }

    @Override
    public List<Map<String, String>> rawGet(String table, String encodedQuery, int limit, String fields) {
        if ("change_request".equals(table)) {
            List<Map<String, String>> out = new ArrayList<>();
            for (ChangeRecord r : queryChanges(encodedQuery == null ? "" : encodedQuery, limit, false)) out.add(r.fields());
            return out;
        }
        return List.of(Map.of("_mock", "table '" + table + "' is not simulated; only change_request is"));
    }

    // ---------------------------------------------------------- seed helpers

    private void seed(String number, String approval, String closeCode, String title, String desc,
                      String type, String category, String risk, String ci, String group,
                      String start, String end, List<Map<String, String>> tasks, List<Map<String, String>> approvals) {
        boolean ok = "approved".equals(approval);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("sys_id", "sys-" + number);
        f.put("number", number);
        f.put("short_description", title);
        f.put("description", desc);
        f.put("type", type);
        f.put("category", category);
        f.put("risk", risk);
        f.put("impact", "2 - Medium");
        f.put("cmdb_ci", ci);
        f.put("assignment_group", group);
        f.put("start_date", start);
        f.put("end_date", end);
        f.put("state", ok ? "Closed" : "Canceled");
        f.put("approval", approval);
        f.put("close_code", closeCode);
        f.put("justification", ok ? "Security compliance / scheduled maintenance" : "");
        f.put("implementation_plan", ok ? "See change tasks; each task has owner and duration." : "");
        f.put("backout_plan", ok ? "Restore snapshot / rollback patch, verified in UAT." : "");
        f.put("test_plan", ok ? "Health check endpoints, service status, log review." : "");
        f.put("sys_updated_on", end);
        String day = start.substring(0, 10);
        List<Map<String, String>> fullTasks = new ArrayList<>();
        for (Map<String, String> t : tasks) {
            Map<String, String> tt = new LinkedHashMap<>(t);
            tt.put("planned_start_date", day + " " + t.get("planned_start_date") + ":00");
            tt.put("planned_end_date", day + " " + t.get("planned_end_date") + ":00");
            tt.put("assignment_group", group);
            tt.put("number", "CTASK00" + taskSeq.incrementAndGet());
            fullTasks.add(tt);
        }
        List<Map<String, String>> journal = new ArrayList<>();
        for (Map<String, String> a : approvals) {
            if (!a.get("comments").isBlank()) {
                journal.add(Map.of("element", "comments", "value", a.get("comments"),
                        "sys_created_by", a.get("approver"), "sys_created_on", end));
            }
        }
        changes.put(number, new ChangeRecord(f.get("sys_id"), number, f, fullTasks, new ArrayList<>(approvals), journal));
    }

    private static Map<String, String> task(int order, String title, String start, String end) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("order", String.valueOf(order));
        t.put("short_description", title);
        t.put("planned_start_date", start);
        t.put("planned_end_date", end);
        return t;
    }

    private static Map<String, String> appr(String approver, String state, String comments) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("approver", approver);
        a.put("state", state);
        a.put("comments", comments);
        a.put("sys_updated_on", "2026-09-10 09:00:00");
        return a;
    }
}
