package com.company.cragent.tools;

import com.company.cragent.cockpit.CockpitModel.*;
import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.config.CockpitProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Daily task cockpit. Copilot does the thinking (what matters today, what is worth keeping);
 * these tools store the result as files and compute the numbers.
 */
@Component
public class CockpitTools {

    private final CockpitStore store;
    private final CockpitProperties props;

    public CockpitTools(CockpitStore store, CockpitProperties props) {
        this.store = store;
        this.props = props;
    }

    /** One task as Copilot extracts it from pasted text. */
    public record TaskInput(
            String title,
            @ToolParam(description = "Kind of work, reuse a known one (os-patch, oracle-ru …)", required = false) String kind,
            @ToolParam(description = "Minutes, only if the user said so", required = false) Integer est,
            @ToolParam(description = "yyyy-MM-dd, must be done by", required = false) String due,
            @ToolParam(description = "yyyy-MM-dd HH:mm, when the work runs", required = false) String scheduledAt,
            @ToolParam(description = "CHG number", required = false) String cr,
            @ToolParam(description = "weekly|monthly|quarterly", required = false) String repeat,
            @ToolParam(description = "P1|P2|P3, default P2", required = false) String priority,
            @ToolParam(description = "Original wording, who asked", required = false) String context,
            @ToolParam(required = false) List<String> tags) {}

    public record SlotInput(String time, String label, @ToolParam(required = false) Integer minutes, @ToolParam(required = false) String taskId) {}

    @Tool(name = "add_tasks", description = "Add tasks taken from pasted text. Near-duplicates are merged. Returns created, mergedInto, related (similar past tasks), history (per kind: typical minutes, estimate bias, pitfalls, playbook) and knownKinds.")
    public Map<String, Object> addTasks(
            List<TaskInput> tasks,
            @ToolParam(description = "paste|email|meeting|chat", required = false) String source) {
        List<Task> in = new ArrayList<>();
        for (TaskInput ti : tasks) {
            Task t = new Task();
            t.title = ti.title(); t.est = ti.est(); t.due = ti.due(); t.priority = ti.priority(); t.context = ti.context();
            t.scheduledAt = ti.scheduledAt(); t.cr = ti.cr(); t.repeat = ti.repeat(); t.kind = ti.kind();
            t.tags = ti.tags() == null ? new ArrayList<>() : ti.tags();
            in.add(t);
        }
        CockpitStore.AddResult r = store.addTasks(in, source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", r.created());
        out.put("mergedInto", r.merged());
        out.put("related", r.related());
        out.put("history", r.history());
        out.put("knownKinds", store.kinds());
        return out;
    }

    @Tool(name = "list_tasks", description = "List tasks with all their fields.")
    public List<Task> listTasks(@ToolParam(description = "open (default, incl. waiting)|actionable|todo|doing|waiting|done|dropped|all", required = false) String status) {
        String s = status == null || status.isBlank() ? "open" : status.trim().toLowerCase();
        return store.tasks().stream().filter(t -> switch (s) {
            case "all" -> true;
            case "open" -> t.isOpen();
            case "actionable" -> t.isActionable();
            default -> s.equals(t.status);
        }).collect(Collectors.toList());
    }

    @Tool(name = "update_task", description = """
            Change task fields: title, kind, status (todo|doing|waiting|done|dropped), waitingOn (sets waiting), priority,
            est, estBy (user|ai), spent (minutes), due, scheduledAt, cr, ice, repeat, context, tags (comma separated).""")
    public Task updateTask(
            @ToolParam(description = "T-0003") String id,
            @ToolParam(required = false) Map<String, String> fields,
            @ToolParam(description = "Line appended to the task's notes", required = false) String note) {
        return store.updateTask(id, fields == null ? Map.of() : fields, note);
    }

    @Tool(name = "task_notes", description = "A task's notes file: original text, follow-ups, pitfalls.")
    public String taskNotes(String id) {
        String n = store.taskNotes(id);
        return n.isEmpty() ? "(no notes yet for " + id + ")" : n;
    }

    @Tool(name = "get_day", description = "The day: plan, notes, carried-over and open tasks, attention (waiting, scheduled, overdueRun, dueSoon, withChange), kinds (history per kind of the open tasks), recent stats, knowledge topics, previous day.")
    public Map<String, Object> getDay(@ToolParam(description = "yyyy-MM-dd, default today", required = false) String date) {
        Day day = store.day(date);
        Map<String, Task> byId = store.tasks().stream().collect(Collectors.toMap(t -> t.id, t -> t, (a, b) -> a, LinkedHashMap::new));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day);
        out.put("planTasks", day.plan.stream().map(byId::get).filter(t -> t != null).collect(Collectors.toList()));
        out.put("carryOver", store.carryOver(day.date));
        out.put("openTasks", byId.values().stream().filter(t -> t.isOpen()).collect(Collectors.toList()));
        out.put("attention", store.attention(day.date));
        out.put("kinds", store.hints(byId.values().stream().filter(Task::isOpen).collect(Collectors.toList())));
        List<DayStat> stats = store.stats().days;
        out.put("recentStats", stats.subList(Math.max(0, stats.size() - 7), stats.size()));
        out.put("knowledgeTopics", store.knowledgeCards());
        List<String> recent = store.recentDays(2);
        String prev = recent.stream().filter(d -> d.compareTo(day.date) < 0).findFirst().orElse(null);
        out.put("previousDay", prev == null ? null : store.day(prev));
        out.put("cockpitUrl", url());
        return out;
    }

    @Tool(name = "plan_day", description = "Write today's plan (replaces it) after the user confirmed: ordered task ids, brief text, optional timeline.")
    public Day planDay(
            @ToolParam(description = "yyyy-MM-dd, default today", required = false) String date,
            @ToolParam(description = "In order") List<String> taskIds,
            @ToolParam(required = false) String brief,
            @ToolParam(description = "time is HH:mm", required = false) List<SlotInput> timeline) {
        List<Slot> slots = null;
        if (timeline != null) {
            slots = new ArrayList<>();
            for (SlotInput s : timeline) { Slot x = new Slot(); x.time = s.time(); x.label = s.label(); x.minutes = s.minutes(); x.taskId = s.taskId(); slots.add(x); }
        }
        return store.planDay(date, taskIds, brief, slots);
    }

    @Tool(name = "capture_note", description = "Add a note to today's log (and to the task's notes when taskId is given).")
    public Note captureNote(
            String text,
            @ToolParam(description = "decision|pitfall|learned; a 决定/坑/学到 prefix also works", required = false) String kind,
            @ToolParam(required = false) String taskId,
            @ToolParam(description = "yyyy-MM-dd, default today", required = false) String date) {
        return store.capture(date, text, kind, taskId);
    }

    public record MemoryItem(
            @ToolParam(description = "One sentence, user's words") String text,
            @ToolParam(description = "rule|fact|pitfall|decision|learned|preference") String kind,
            @ToolParam(description = "User's sentence it came from", required = false) String source,
            @ToolParam(required = false) String taskId) {}

    @Tool(name = "remember", description = "Keep lasting facts the conversation revealed (see instructions: Memory). Once per reply, all items; skip when nothing. Confirmed at evening close.")
    public List<Map<String, Object>> remember(List<MemoryItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MemoryItem it : items == null ? List.<MemoryItem>of() : items) {
            CockpitStore.Remembered r = store.remember(it.text(), it.kind(), it.source(), it.taskId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", r.status());
            m.put("text", it.text());
            if (r.note() != null && r.note().kind != null) m.put("kind", r.note().kind);
            if (r.duplicateOf() != null) m.put("duplicateOf", r.duplicateOf());
            out.add(m);
        }
        return out;
    }

    @Tool(name = "close_day", description = "Close the day: summary, tomorrow's task ids, stats. Returns digestCandidates (notes not yet saved as knowledge).")
    public Map<String, Object> closeDay(
            @ToolParam(description = "yyyy-MM-dd, default today", required = false) String date,
            @ToolParam(description = "One line") String summary,
            @ToolParam(required = false) List<String> tomorrowTaskIds) {
        Day day = store.closeDay(date, summary, tomorrowTaskIds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day);
        List<Map<String, Object>> cands = new ArrayList<>();
        for (int i = 0; i < day.notes.size(); i++) {
            Note n = day.notes.get(i);
            if (n.kind != null && !n.saved) cands.add(Map.of("noteIndex", i, "kind", n.kind, "text", n.text, "taskId", n.taskId == null ? "" : n.taskId));
        }
        out.put("digestCandidates", cands);
        out.put("stats", store.stats().days.stream().filter(s -> s.date.equals(day.date)).findFirst().orElse(null));
        return out;
    }

    @Tool(name = "save_knowledge", description = "Append a dated entry to knowledge/<topic>.md, only after the user agreed. noteIndex marks that day's note saved.")
    public Map<String, Object> saveKnowledge(
            @ToolParam(description = "Existing topic when one fits") String topic,
            String title,
            String content,
            @ToolParam(description = "yyyy-MM-dd, default today", required = false) String sourceDate,
            @ToolParam(required = false) String taskId,
            @ToolParam(required = false) Integer noteIndex) {
        var file = store.saveKnowledge(topic, title, content, sourceDate, taskId);
        if (noteIndex != null) store.markNoteSaved(sourceDate == null ? CockpitStore.today() : sourceDate, noteIndex);
        return Map.of("file", store.dir().relativize(file).toString().replace('\\', '/'), "topic", topic);
    }

    @Tool(name = "read_knowledge", description = "One knowledge topic file in full.")
    public String readKnowledge(String topic) {
        String t = store.readKnowledge(topic);
        return t.isEmpty() ? "(no such topic: " + topic + ")" : t;
    }

    @Tool(name = "search_knowledge", description = "Search knowledge files and task notes. Use before answering about the user's past; name the file.")
    public List<Map<String, String>> searchKnowledge(String query) {
        return store.search(query, 12);
    }

    @Tool(name = "task_history", description = "Past tasks, newest first, each with its planned days, notes, knowledge, est vs spent. For 'what did I do in …', 'when did I last …'.")
    public List<TaskHistory> taskHistory(
            @ToolParam(description = "Matches title, context, tags, notes, knowledge", required = false) String query,
            @ToolParam(description = "all|open|done|dropped", required = false) String status,
            @ToolParam(description = "yyyy-MM-dd", required = false) String from,
            @ToolParam(description = "yyyy-MM-dd", required = false) String to,
            @ToolParam(description = "created|done|updated", required = false) String sort,
            @ToolParam(description = "default 30", required = false) Integer limit) {
        return store.history(query, status, from, to, sort, limit == null ? 30 : limit);
    }

    @Tool(name = "review", description = """
            Look back on a period (default the last 7 days): done/created/dropped, plan completion, estimate bias,
            per kind (typical minutes, estRatio, recurrence, pitfalls, knowledge, playbook), stuck tasks, done tasks
            without spent, unclassified tasks, and computed suggestions.""")
    public Review review(
            @ToolParam(description = "yyyy-MM-dd", required = false) String from,
            @ToolParam(description = "yyyy-MM-dd, default today", required = false) String to) {
        return store.review(from, to);
    }

    @Tool(name = "get_playbook", description = "The standard way of doing one kind of work (steps, typical time, checks, pitfalls). Empty when none yet.")
    public String getPlaybook(String kind) {
        String p = store.playbook(kind);
        return p.isEmpty() ? "(no playbook for " + kind + " yet; known: " + store.playbooks().stream().map(m -> String.valueOf(m.get("kind"))).toList() + ")" : p;
    }

    @Tool(name = "save_playbook", description = "Write a kind's playbook (whole markdown, replaces it; the old version is kept). Only after the user agreed.")
    public Map<String, Object> savePlaybook(String kind, @ToolParam(description = "Markdown") String content) {
        var f = store.savePlaybook(kind, content);
        return Map.of("kind", kind, "file", store.dir().relativize(f).toString().replace('\\', '/'));
    }

    @Tool(name = "cockpit_url", description = "Address of the web page.")
    public String url() {
        return props.webEnabled() ? "http://127.0.0.1:" + props.portNumber() + "/" : "(cockpit web page is disabled: cockpit.web=false)";
    }

    @Tool(name = "open_cockpit", description = "Open the web page in the user's browser.")
    public String openCockpit(@ToolParam(description = "today (default)|todos|knowledge|changes|ices|ledger|dashboard", required = false) String view) {
        if (!props.webEnabled()) return "(cockpit web page is disabled: cockpit.web=false)";
        String v = view == null || view.isBlank() ? "morning" : view.trim().toLowerCase();
        // the React UI: old view names map onto its routes; unknown names fall back to the day page
        String route = switch (v) {
            case "history", "todos", "tasks" -> "todos";
            case "knowledge" -> "knowledge";
            case "change", "changes", "cr" -> "changes";
            case "ice", "ices" -> "ices";
            case "records", "ledger" -> "ledger";
            case "dashboard", "status" -> "dashboard";
            default -> "today";
        };
        String url = url() + "app/" + route;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            List<String> cmd = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                    : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return "opened " + url;
        } catch (Exception e) {
            return "could not open a browser (" + e.getMessage() + "); open " + url + " by hand";
        }
    }
}
