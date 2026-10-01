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
            @ToolParam(description = "Estimated minutes, ONLY if the user stated a duration. Leave empty otherwise; never guess.", required = false) Integer est,
            @ToolParam(description = "Due date yyyy-MM-dd: when it must be finished, if stated", required = false) String due,
            @ToolParam(description = "When the work itself runs, yyyy-MM-dd HH:mm (a maintenance window, a release slot), if stated and different from due", required = false) String scheduledAt,
            @ToolParam(description = "Change request number if the text names one (CHG...)", required = false) String cr,
            @ToolParam(description = "monthly | weekly | quarterly when the text says it recurs (每月 / 每周 / 每季度)", required = false) String repeat,
            @ToolParam(description = "P1 must do today, P2 today, P3 can slip. Default P2", required = false) String priority,
            @ToolParam(description = "Where it came from and any detail worth keeping: who asked, the original wording", required = false) String context,
            @ToolParam(description = "Free tags, e.g. servicenow, patch, meeting", required = false) List<String> tags) {}

    public record SlotInput(String time, String label, @ToolParam(required = false) Integer minutes, @ToolParam(required = false) String taskId) {}

    @Tool(name = "add_tasks", description = """
            Store tasks extracted from text the user pasted (an email, meeting notes, a chat message, a one-liner).
            Duplicates of open tasks (same or very similar title) are merged instead of created; the result says which.
            Keep titles short and verb-first; put the original wording and who asked into context.
            The result also lists `related`: finished tasks that look like each new one, with last time's est / spent /
            CR number and the pitfall lines from their notes. Mention those to the user in one line each.""")
    public Map<String, Object> addTasks(
            @ToolParam(description = "Tasks to add") List<TaskInput> tasks,
            @ToolParam(description = "Source label: paste (default), email, meeting, chat", required = false) String source) {
        List<Task> in = new ArrayList<>();
        for (TaskInput ti : tasks) {
            Task t = new Task();
            t.title = ti.title(); t.est = ti.est(); t.due = ti.due(); t.priority = ti.priority(); t.context = ti.context();
            t.scheduledAt = ti.scheduledAt(); t.cr = ti.cr(); t.repeat = ti.repeat();
            t.tags = ti.tags() == null ? new ArrayList<>() : ti.tags();
            in.add(t);
        }
        CockpitStore.AddResult r = store.addTasks(in, source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", r.created());
        out.put("mergedInto", r.merged());
        out.put("related", r.related());
        return out;
    }

    @Tool(name = "list_tasks", description = """
            List tasks. status filter: open (todo+doing+waiting, default), actionable (todo+doing), todo, doing,
            waiting, done, dropped, all. Each task carries est (+estBy user|ai), due, scheduledAt (when it runs),
            waitingOn, cr (linked change number), repeat, priority, plannedFor and carried (days it slipped).""")
    public List<Task> listTasks(@ToolParam(description = "open | actionable | todo | doing | waiting | done | dropped | all", required = false) String status) {
        String s = status == null || status.isBlank() ? "open" : status.trim().toLowerCase();
        return store.tasks().stream().filter(t -> switch (s) {
            case "all" -> true;
            case "open" -> t.isOpen();
            case "actionable" -> t.isActionable();
            default -> s.equals(t.status);
        }).collect(Collectors.toList());
    }

    @Tool(name = "update_task", description = """
            Update a task: fields is a map of column -> value among title, status (todo|doing|waiting|done|dropped),
            waitingOn (what it waits for: approval, a reply, the window; setting it also sets status=waiting),
            priority (P1|P2|P3), est (minutes), estBy (user|ai), spent (minutes), due (yyyy-MM-dd),
            scheduledAt (yyyy-MM-dd HH:mm, when it runs), cr (change number), repeat (monthly|weekly|quarterly),
            context, tags (comma separated). Optional note is appended to the task's own notes file with a timestamp.""")
    public Task updateTask(
            @ToolParam(description = "Task id like T-0003") String id,
            @ToolParam(description = "Fields to change", required = false) Map<String, String> fields,
            @ToolParam(description = "A line to append to the task's notes", required = false) String note) {
        return store.updateTask(id, fields == null ? Map.of() : fields, note);
    }

    @Tool(name = "task_notes", description = "Read the notes file of a task: the pasted original, follow-ups, captured pitfalls.")
    public String taskNotes(@ToolParam(description = "Task id") String id) {
        String n = store.taskNotes(id);
        return n.isEmpty() ? "(no notes yet for " + id + ")" : n;
    }

    @Tool(name = "get_day", description = """
            Everything needed for the morning brief or a status check: the day's file (brief, plan, timeline, notes,
            summary), the planned tasks in full, tasks carried over from earlier days, `attention` (waiting tasks,
            runs scheduled within 7 days, runs whose scheduled time already passed, open tasks with a CR number),
            the last 7 closed days' stats and the knowledge topics that exist. date defaults to today.""")
    public Map<String, Object> getDay(@ToolParam(description = "yyyy-MM-dd, default today", required = false) String date) {
        Day day = store.day(date);
        Map<String, Task> byId = store.tasks().stream().collect(Collectors.toMap(t -> t.id, t -> t, (a, b) -> a, LinkedHashMap::new));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day);
        out.put("planTasks", day.plan.stream().map(byId::get).filter(t -> t != null).collect(Collectors.toList()));
        out.put("carryOver", store.carryOver(day.date));
        out.put("openTasks", byId.values().stream().filter(t -> t.isOpen()).collect(Collectors.toList()));
        out.put("attention", store.attention(day.date));
        List<DayStat> stats = store.stats().days;
        out.put("recentStats", stats.subList(Math.max(0, stats.size() - 7), stats.size()));
        out.put("knowledgeTopics", store.knowledgeCards());
        List<String> recent = store.recentDays(2);
        String prev = recent.stream().filter(d -> d.compareTo(day.date) < 0).findFirst().orElse(null);
        out.put("previousDay", prev == null ? null : store.day(prev));
        out.put("cockpitUrl", url());
        return out;
    }

    @Tool(name = "plan_day", description = """
            Write the day's plan after the user confirmed it: the ordered task ids, the morning brief text
            (what matters today and why, as shown to the user) and an optional timeline of slots.
            Tasks planned on an earlier day get their carried counter increased.""")
    public Day planDay(
            @ToolParam(description = "yyyy-MM-dd, default today", required = false) String date,
            @ToolParam(description = "Task ids in priority order") List<String> taskIds,
            @ToolParam(description = "The brief text shown to the user (2-5 sentences, why these tasks)", required = false) String brief,
            @ToolParam(description = "Timeline slots: time HH:mm, label, minutes, taskId", required = false) List<SlotInput> timeline) {
        List<Slot> slots = null;
        if (timeline != null) {
            slots = new ArrayList<>();
            for (SlotInput s : timeline) { Slot x = new Slot(); x.time = s.time(); x.label = s.label(); x.minutes = s.minutes(); x.taskId = s.taskId(); slots.add(x); }
        }
        return store.planDay(date, taskIds, brief, slots);
    }

    @Tool(name = "capture_note", description = """
            Append a quick note to today's log. kind: decision | pitfall | learned, or empty for a plain note
            (a Chinese prefix 决定/坑/学到 in the text is also recognised). Link it to a task with taskId when it
            is about one; the note is then also appended to that task's notes file.""")
    public Note captureNote(
            @ToolParam(description = "The note") String text,
            @ToolParam(description = "decision | pitfall | learned", required = false) String kind,
            @ToolParam(description = "Task id it belongs to", required = false) String taskId,
            @ToolParam(description = "yyyy-MM-dd, default today", required = false) String date) {
        return store.capture(date, text, kind, taskId);
    }

    @Tool(name = "close_day", description = """
            Close the day: stores the one-line summary and the proposed task ids for tomorrow, marks the day closed
            and records stats (planned/done/estimated minutes/notes). Returns the day plus digestCandidates:
            notes with a kind that have not been saved to knowledge yet, for the user to confirm one by one.""")
    public Map<String, Object> closeDay(
            @ToolParam(description = "yyyy-MM-dd, default today", required = false) String date,
            @ToolParam(description = "One-line summary of the day") String summary,
            @ToolParam(description = "Task ids proposed for tomorrow", required = false) List<String> tomorrowTaskIds) {
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

    @Tool(name = "save_knowledge", description = """
            Keep something for good: appends a dated entry to knowledge/<topic>.md (created if new). Use an existing
            topic when one fits (see get_day.knowledgeTopics); content is 1-5 sentences in the user's words.
            Pass noteIndex (from close_day.digestCandidates) to mark that note as saved. Only after the user agreed.""")
    public Map<String, Object> saveKnowledge(
            @ToolParam(description = "Topic, e.g. 'Oracle RU 补丁流程'") String topic,
            @ToolParam(description = "Entry title, one line") String title,
            @ToolParam(description = "The entry") String content,
            @ToolParam(description = "yyyy-MM-dd it came from, default today", required = false) String sourceDate,
            @ToolParam(description = "Task id it came from", required = false) String taskId,
            @ToolParam(description = "Index of the note in that day's notes, to mark it saved", required = false) Integer noteIndex) {
        var file = store.saveKnowledge(topic, title, content, sourceDate, taskId);
        if (noteIndex != null) store.markNoteSaved(sourceDate == null ? CockpitStore.today() : sourceDate, noteIndex);
        return Map.of("file", store.dir().relativize(file).toString().replace('\\', '/'), "topic", topic);
    }

    @Tool(name = "read_knowledge", description = "Read one knowledge topic file in full.")
    public String readKnowledge(@ToolParam(description = "Topic name as listed") String topic) {
        String t = store.readKnowledge(topic);
        return t.isEmpty() ? "(no such topic: " + topic + ")" : t;
    }

    @Tool(name = "search_knowledge", description = """
            Search the knowledge files and task notes for a word or phrase. Use before answering any
            'how did I do this last time' question, and quote the file in the answer.""")
    public List<Map<String, String>> searchKnowledge(@ToolParam(description = "Word or phrase") String query) {
        return store.search(query, 12);
    }

    @Tool(name = "task_history", description = """
            Task history sorted by time, newest first, each task with everything recorded about it: the days
            it was planned, its linked notes, the knowledge entries that came out of it, its own notes file,
            est vs spent, how often it slipped. Filter by keyword (matched against title, context, tags, notes
            and knowledge), status (all | open | done | dropped) and a date range; sort by created (default),
            done or updated. Use it for 'what did I do in September', 'when did I last touch X', 'show me
            everything about the patch CR'.""")
    public List<TaskHistory> taskHistory(
            @ToolParam(description = "Keyword, optional", required = false) String query,
            @ToolParam(description = "all | open | done | dropped, default all", required = false) String status,
            @ToolParam(description = "From date yyyy-MM-dd, optional", required = false) String from,
            @ToolParam(description = "To date yyyy-MM-dd, optional", required = false) String to,
            @ToolParam(description = "created | done | updated, default created", required = false) String sort,
            @ToolParam(description = "Max rows, default 30", required = false) Integer limit) {
        return store.history(query, status, from, to, sort, limit == null ? 30 : limit);
    }

    @Tool(name = "cockpit_url", description = "The local address of the cockpit page (same data as these tools).")
    public String url() {
        return props.webEnabled() ? "http://127.0.0.1:" + props.portNumber() + "/" : "(cockpit web page is disabled: cockpit.web=false)";
    }

    @Tool(name = "open_cockpit", description = """
            Open the cockpit page in the user's default browser (local page, nothing leaves the machine).
            Call it once at the start of the morning brief and whenever the user asks to see the page.
            view: morning | day | evening | history | knowledge | change (default morning).""")
    public String openCockpit(@ToolParam(description = "Which view to open, default morning", required = false) String view) {
        if (!props.webEnabled()) return "(cockpit web page is disabled: cockpit.web=false)";
        String v = view == null || view.isBlank() ? "morning" : view.trim().toLowerCase();
        String url = url() + "#" + v;
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
