package com.company.cragent.cockpit;

import com.company.cragent.cockpit.CockpitModel.*;
import com.company.cragent.config.CockpitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CockpitStoreTest {

    @TempDir Path tmp;

    CockpitStore store() { return new CockpitStore(new CockpitProperties(tmp, 0, false), new ObjectMapper()); }

    static Task t(String title, Integer est, String prio) { Task x = new Task(); x.title = title; x.est = est; x.priority = prio; return x; }

    @Test
    void addsAndMergesSimilarTasks() {
        CockpitStore s = store();
        var r1 = s.addTasks(List.of(t("补丁 CR 定稿并提交", 90, "P1"), t("回复 DBA 窗口邮件", 15, null)), "paste");
        assertThat(r1.created()).extracting(x -> x.id).containsExactly("T-0001", "T-0002");
        assertThat(r1.created().get(1).priority).isEqualTo("P2");

        var r2 = s.addTasks(List.of(t("补丁CR定稿提交", null, "P2"), t("整理 IntelliJ 接入步骤", 45, null)), "chat");
        assertThat(r2.merged()).extracting(x -> x.id).containsExactly("T-0001");
        assertThat(r2.created()).extracting(x -> x.id).containsExactly("T-0003");
        assertThat(s.tasks()).hasSize(3);
    }

    @Test
    void planCaptureAndCloseProduceStatsAndCarryOver() {
        CockpitStore s = store();
        s.addTasks(List.of(t("A", 60, "P1"), t("B", 30, "P2")), "paste");
        s.planDay("2026-09-29", List.of("T-0001", "T-0002"), "brief text", null);
        s.updateTask("T-0001", Map.of("status", "done"), "took 50 min");
        s.capture("2026-09-29", "坑 opatch 之后要重启监听", null, "T-0001");
        s.capture("2026-09-29", "plain note", null, null);
        Day closed = s.closeDay("2026-09-29", "one line", List.of("T-0002"));

        assertThat(closed.closed).isTrue();
        assertThat(closed.notes.get(0).kind).isEqualTo("pitfall");
        assertThat(closed.notes.get(0).text).isEqualTo("opatch 之后要重启监听");
        assertThat(closed.notes.get(1).kind).isNull();
        DayStat st = s.stats().days.get(0);
        assertThat(st.planned).isEqualTo(2);
        assertThat(st.done).isEqualTo(1);
        assertThat(st.estDoneMinutes).isEqualTo(60);
        assertThat(s.taskNotes("T-0001")).contains("took 50 min").contains("[pitfall] opatch");

        // B was planned on the 29th and not done: it carries over to the 30th
        assertThat(s.carryOver("2026-09-30")).extracting(x -> x.id).containsExactly("T-0002");
        s.planDay("2026-09-30", List.of("T-0002"), null, null);
        assertThat(s.task("T-0002").get().carried).isEqualTo(1);
    }

    @Test
    void knowledgeFilesAccumulateAndSearch() {
        CockpitStore s = store();
        s.saveKnowledge("Oracle RU 补丁流程", "回退耗时", "opatch rollback 实测 40 分钟,不是 20。", "2026-09-29", "T-0001");
        s.saveKnowledge("Oracle RU 补丁流程", "监听要重启", "datapatch 之后重启监听。", "2026-09-30", null);
        s.saveKnowledge("CAB 审批", "影响范围要有业务方名字", "CAB 找的是名字不是字数。", null, null);

        var cards = s.knowledgeCards();
        assertThat(cards).hasSize(2);
        var oracle = cards.stream().filter(c -> c.topic.equals("Oracle RU 补丁流程")).findFirst().orElseThrow();
        assertThat(oracle.entries).isEqualTo(2);
        assertThat(oracle.latestTitle).isEqualTo("监听要重启");
        assertThat(oracle.lastUpdated).isEqualTo("2026-09-30");
        assertThat(s.search("40 分钟", 10)).hasSize(1);
        assertThat(s.search("40 分钟", 10).get(0).get("file")).startsWith("knowledge/");
        assertThat(s.readKnowledge("Oracle RU 补丁流程")).contains("来源:任务 T-0001");

        // file names stay ASCII even for Chinese topics; the topic lives in the first line
        assertThat(s.topicFile("Oracle RU 补丁流程").getFileName().toString()).matches("[a-z0-9-]+\\.md");
        assertThat(s.topicFile("CAB 审批").getFileName().toString()).matches("[a-z0-9-]+\\.md");
        assertThat(CockpitStore.slug("Release rollback")).isEqualTo("release-rollback");
    }

    @Test
    void historySortsFiltersAndLinksEverything() {
        CockpitStore s = store();
        s.addTasks(List.of(t("Order Portal 补丁 CR", 90, "P1"), t("回复 DBA 邮件", 15, "P2"), t("整理 IntelliJ 步骤", 45, "P3")), "meeting");
        s.planDay("2026-09-29", List.of("T-0001", "T-0002"), null, null);
        s.capture("2026-09-29", "坑 srv-app-02 要等 01 验证完", null, "T-0001");
        s.updateTask("T-0001", Map.of("status", "done", "spent", "70"), null);
        s.updateTask("T-0003", Map.of("status", "dropped"), null);
        s.saveKnowledge("srv-app 补丁顺序", "02 等 01", "02 在 01 验证完之后再开始。", "2026-09-29", "T-0001");

        var all = s.history(null, null, null, null, "created", 50);
        assertThat(all).extracting(h -> h.task.id).containsExactly("T-0003", "T-0002", "T-0001");   // newest created first

        var done = s.history(null, "done", null, null, "done", 50);
        assertThat(done).hasSize(1);
        TaskHistory h = done.get(0);
        assertThat(h.days).containsExactly("2026-09-29");
        assertThat(h.notes).hasSize(1);
        assertThat(h.notes.get(0).get("kind")).isEqualTo("pitfall");
        assertThat(h.knowledge).hasSize(1);
        assertThat(h.knowledge.get(0).topic).isEqualTo("srv-app 补丁顺序");
        assertThat(h.task.spent).isEqualTo(70);

        // keyword reaches into knowledge and notes, not only the title
        assertThat(s.history("验证完", null, null, null, null, 50)).extracting(x -> x.task.id).containsExactly("T-0001");
        assertThat(s.history("intellij", null, null, null, null, 50)).extracting(x -> x.task.id).containsExactly("T-0003");
        assertThat(s.history("不存在的词", null, null, null, null, 50)).isEmpty();
    }

    @Test
    void waitingScheduledAndRecurringTasksAreTracked() {
        CockpitStore s = store();
        // last month's patch, finished, with a pitfall in its notes
        s.addTasks(List.of(t("给 CCS PROD 打 OS 补丁", null, "P2")), "paste");
        s.updateTask("T-0001", Map.of("status", "done", "spent", "150", "cr", "CHG0001"), "坑: 02 要等 01 验证完再开始");

        // this month's: estimate came from the AI, runs on a Sunday window, recurs monthly
        Task next = t("CCS PROD OS 补丁", 120, "P2");
        next.estBy = "ai"; next.scheduledAt = "2026-10-11 01:00"; next.repeat = "monthly";
        var r = s.addTasks(List.of(next), "paste");
        assertThat(r.created()).hasSize(1);
        assertThat(r.created().get(0).estBy).isEqualTo("ai");
        assertThat(r.related()).hasSize(1);
        assertThat(r.related().get(0).id).isEqualTo("T-0001");
        assertThat(r.related().get(0).spent).isEqualTo(150);
        assertThat(r.related().get(0).cr).isEqualTo("CHG0001");
        assertThat(r.related().get(0).pitfalls).hasSize(1).first().asString().contains("验证完");

        // waiting for approval: still open, not actionable, not nagged as carried over
        s.planDay("2026-10-05", List.of("T-0002"), null, null);
        Task w = s.updateTask("T-0002", Map.of("waitingOn", "CAB 审批"), null);
        assertThat(w.status).isEqualTo("waiting");
        assertThat(w.isOpen()).isTrue();
        assertThat(w.isActionable()).isFalse();
        assertThat(s.carryOver("2026-10-06")).isEmpty();
        assertThat(s.history(null, "open", null, null, null, 10)).extracting(h -> h.task.id).containsExactly("T-0002");

        Attention a = s.attention("2026-10-06");
        assertThat(a.waiting).extracting(x -> x.id).containsExactly("T-0002");
        assertThat(a.scheduled).extracting(x -> x.id).containsExactly("T-0002");
        assertThat(a.overdueRun).isEmpty();
        assertThat(s.attention("2026-10-12").overdueRun).extracting(x -> x.id).containsExactly("T-0002");

        // back to doing clears waitingOn; linking a CR writes the number and a note
        assertThat(s.updateTask("T-0002", Map.of("status", "doing"), null).waitingOn).isNull();
        Task linked = s.linkChange("T-0002", "CHG0002");
        assertThat(linked.cr).isEqualTo("CHG0002");
        assertThat(s.taskNotes("T-0002")).contains("CHG0002");
        assertThat(s.attention("2026-10-06").withChange).extracting(x -> x.id).containsExactly("T-0002");
        assertThatThrownBy(() -> s.updateTask("T-0002", Map.of("status", "paused"), null)).hasMessageContaining("Unknown status");
    }

    @Test
    void searchCoversDayLogsAndEnglishPrefixes() {
        CockpitStore s = store();
        Note n = s.capture("2026-09-30", "learned: CAB looks for names, not word count", null, null);
        assertThat(n.kind).isEqualTo("learned");
        assertThat(n.text).isEqualTo("CAB looks for names, not word count");
        var hits = s.search("word count", 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("file")).isEqualTo("days/2026-09-30.json");
        assertThat(hits.get(0).get("lines")).contains("[learned]");
    }
}
