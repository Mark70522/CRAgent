package com.company.cragent.cockpit;

import com.company.cragent.cockpit.CockpitModel.*;
import com.company.cragent.config.CockpitProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * All cockpit data is plain files under cockpit/ so you can read and edit them by hand and git tracks them:
 *   backlog.json          every task, whatever its source
 *   days/2026-09-30.json  that day: brief, plan, timeline, notes, summary
 *   task-notes/T-0001.md  free-form notes per task (the pasted original, follow-ups)
 *   knowledge/<topic>.md  what you decided to keep, one dated entry per section
 *   stats.json            one row per closed day
 * Nothing here calls an LLM: Copilot writes the words, this class only stores and computes.
 */
@Component
public class CockpitStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final CockpitProperties props;
    private final ObjectMapper json;

    public CockpitStore(CockpitProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path dir() { return props.dir(); }

    // ------------------------------------------------------------------ tasks

    public synchronized List<Task> tasks() {
        return read(props.backlog(), new TypeReference<List<Task>>() {}, new ArrayList<>());
    }

    public synchronized Optional<Task> task(String id) {
        return tasks().stream().filter(t -> t.id.equalsIgnoreCase(id)).findFirst();
    }

    /**
     * Result of add: which were created, which merged into an existing task, and for each created task the
     * past tasks that look like it (last time's cost and pitfalls) so Copilot can mention them right away.
     */
    public record AddResult(List<Task> created, List<Task> merged, List<Related> related) {}

    public synchronized AddResult addTasks(List<Task> incoming, String source) {
        List<Task> all = tasks();
        List<Task> created = new ArrayList<>(), merged = new ArrayList<>();
        List<Related> related = new ArrayList<>();
        String now = now();
        for (Task in : incoming) {
            if (in.title == null || in.title.isBlank()) continue;
            Task dup = all.stream().filter(t -> t.isOpen() && similar(t.title, in.title)).findFirst().orElse(null);
            if (dup != null) {
                if (in.est != null && dup.est == null) { dup.est = in.est; dup.estBy = in.estBy; }
                if (in.due != null) dup.due = in.due;
                if (in.scheduledAt != null) dup.scheduledAt = in.scheduledAt;
                if (in.cr != null && dup.cr == null) dup.cr = in.cr;
                if (in.repeat != null && dup.repeat == null) dup.repeat = in.repeat;
                if (in.priority != null && "P1".equals(in.priority)) dup.priority = "P1";
                if (in.context != null && !in.context.isBlank()) appendTaskNote(dup.id, "补充(" + now + "):" + in.context);
                dup.updatedAt = now;
                merged.add(dup);
                continue;
            }
            Task t = new Task();
            t.id = nextId(all);
            t.title = in.title.trim();
            t.source = source == null ? "paste" : source;
            t.priority = in.priority == null ? "P2" : in.priority;
            t.est = in.est;
            t.estBy = in.est == null ? null : (in.estBy == null ? "user" : in.estBy);
            t.due = in.due;
            t.scheduledAt = in.scheduledAt;
            t.cr = in.cr;
            t.repeat = in.repeat;
            t.context = in.context;
            t.tags = in.tags == null ? new ArrayList<>() : in.tags;
            t.createdAt = now;
            t.updatedAt = now;
            all.add(t);
            created.add(t);
            if (in.context != null && !in.context.isBlank()) appendTaskNote(t.id, "来源(" + now + "):" + in.context);
            related.addAll(relatedPast(t, all));
        }
        write(props.backlog(), all);
        return new AddResult(created, merged, related);
    }

    /** Finished tasks that look like this one, newest first, with the pitfall / learned lines from their notes. */
    public synchronized List<Related> relatedPast(Task t, List<Task> all) {
        return all.stream()
                .filter(p -> !p.id.equals(t.id) && !p.isOpen() && similar(p.title, t.title))
                .sorted(Comparator.comparing((Task p) -> p.doneAt == null ? p.updatedAt == null ? "" : p.updatedAt : p.doneAt).reversed())
                .limit(3)
                .map(p -> {
                    Related r = new Related();
                    r.forTask = t.id; r.id = p.id; r.title = p.title; r.status = p.status; r.doneAt = p.doneAt;
                    r.est = p.est; r.spent = p.spent; r.cr = p.cr;
                    for (String line : taskNotes(p.id).split("\n")) {
                        String l = line.startsWith("- ") ? line.substring(2).trim() : line.trim();
                        if (l.matches("(?i).*(坑|学到|决定|pitfall|learned|decision).*")) r.pitfalls.add(l);
                    }
                    return r;
                })
                .collect(Collectors.toList());
    }

    /** Tie a task to the change request that was created for it. Idempotent; records the link in the task's notes. */
    public synchronized Task linkChange(String taskId, String number) {
        Task t = updateTask(taskId, Map.of("cr", number), "变更单 " + number + " 已创建");
        return t;
    }

    /** What to watch besides the plan: waiting tasks, runs coming up within a week, runs already past, open CRs. */
    public synchronized Attention attention(String date) {
        String d = date == null || date.isBlank() ? today() : date;
        String weekLater = LocalDate.parse(d).plusDays(7).toString();
        Attention a = new Attention();
        for (Task t : tasks()) {
            if (!t.isOpen()) continue;
            if ("waiting".equals(t.status)) a.waiting.add(t);
            if (t.cr != null && !t.cr.isBlank()) a.withChange.add(t);
            if (t.scheduledAt != null && !t.scheduledAt.isBlank()) {
                String day = t.scheduledAt.substring(0, Math.min(10, t.scheduledAt.length()));
                if (day.compareTo(d) < 0) a.overdueRun.add(t);
                else if (day.compareTo(weekLater) <= 0) a.scheduled.add(t);
            }
        }
        a.scheduled.sort(Comparator.comparing(t -> t.scheduledAt));
        a.overdueRun.sort(Comparator.comparing(t -> t.scheduledAt));
        return a;
    }

    public synchronized Task updateTask(String id, Map<String, String> fields, String note) {
        List<Task> all = tasks();
        Task t = all.stream().filter(x -> x.id.equalsIgnoreCase(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No task " + id));
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String v = e.getValue();
            switch (e.getKey()) {
                case "title" -> t.title = v;
                case "status" -> {
                    if (!Set.of("todo", "doing", "waiting", "done", "dropped").contains(v)) throw new IllegalArgumentException("Unknown status " + v);
                    t.status = v;
                    if ("done".equals(v)) t.doneAt = now(); else t.doneAt = null;
                    if (!"waiting".equals(v)) t.waitingOn = null;
                }
                case "priority" -> t.priority = v;
                case "est" -> { t.est = v == null || v.isBlank() ? null : Integer.parseInt(v.trim()); if (t.est != null && t.estBy == null) t.estBy = "user"; }
                case "estBy" -> t.estBy = blankToNull(v);
                case "spent" -> t.spent = v == null || v.isBlank() ? null : Integer.parseInt(v.trim());
                case "due" -> t.due = blankToNull(v);
                case "scheduledAt", "scheduled" -> t.scheduledAt = blankToNull(v);
                case "waitingOn" -> { t.waitingOn = blankToNull(v); if (t.waitingOn != null && t.isActionable()) t.status = "waiting"; }
                case "cr" -> t.cr = blankToNull(v);
                case "ice" -> t.ice = blankToNull(v);
                case "repeat" -> t.repeat = blankToNull(v);
                case "context" -> t.context = v;
                case "tags" -> t.tags = v == null ? new ArrayList<>() : Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                default -> throw new IllegalArgumentException("Unknown field " + e.getKey());
            }
        }
        t.updatedAt = now();
        write(props.backlog(), all);
        if (note != null && !note.isBlank()) appendTaskNote(t.id, now() + " " + note.trim());
        return t;
    }

    public synchronized void appendTaskNote(String id, String line) {
        Path f = props.notes().resolve(id + ".md");
        String head = Files.exists(f) ? "" : "# " + id + " " + task(id).map(t -> t.title).orElse("") + "\n\n";
        append(f, head + "- " + line.replace("\n", "\n  ") + "\n");
    }

    public synchronized String taskNotes(String id) {
        Path f = props.notes().resolve(id + ".md");
        return Files.exists(f) ? readString(f) : "";
    }

    // ------------------------------------------------------------------ days

    public synchronized Day day(String date) {
        String d = date == null || date.isBlank() ? today() : date;
        Day day = read(props.days().resolve(d + ".json"), new TypeReference<Day>() {}, null);
        if (day == null) { day = new Day(); day.date = d; }
        return day;
    }

    public synchronized void saveDay(Day day) {
        write(props.days().resolve(day.date + ".json"), day);
    }

    /** Tasks planned on an earlier day and still not done: they follow you until you finish or drop them. */
    public synchronized List<Task> carryOver(String date) {
        String d = date == null ? today() : date;
        return tasks().stream()
                .filter(Task::isActionable)
                .filter(t -> t.plannedFor != null && t.plannedFor.compareTo(d) < 0)
                .sorted(Comparator.comparing((Task t) -> t.priority).thenComparing(t -> t.plannedFor))
                .collect(Collectors.toList());
    }

    public synchronized Day planDay(String date, List<String> ids, String brief, List<Slot> timeline) {
        Day day = day(date);
        List<Task> all = tasks();
        for (String id : ids) {
            Task t = all.stream().filter(x -> x.id.equalsIgnoreCase(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No task " + id));
            if (t.plannedFor != null && t.plannedFor.compareTo(day.date) < 0) t.carried++;
            t.plannedFor = day.date;
            if ("todo".equals(t.status) || "doing".equals(t.status)) { /* keep */ }
        }
        write(props.backlog(), all);
        day.plan = new ArrayList<>(ids);
        if (brief != null) day.brief = brief;
        if (timeline != null) day.timeline = timeline;
        saveDay(day);
        return day;
    }

    public synchronized Note capture(String date, String text, String kind, String taskId) {
        Day day = day(date);
        Note n = new Note();
        n.t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        n.kind = normalizeKind(kind, text);
        n.text = stripKindPrefix(text);
        n.taskId = taskId;
        day.notes.add(n);
        saveDay(day);
        if (taskId != null && !taskId.isBlank()) appendTaskNote(taskId, n.t + " " + (n.kind == null ? "" : "[" + n.kind + "] ") + n.text);
        return n;
    }

    /** Result of remember: stored, or why not. */
    public record Remembered(String status, Note note, String duplicateOf) {}

    /**
     * What Copilot picked out of the conversation on its own. Goes into today's notes flagged auto, so the
     * evening close shows it for confirmation like any other note. Skipped when the same thing is already
     * in today's notes or in the knowledge files, so repeated conversations do not pile up copies.
     */
    public synchronized Remembered remember(String text, String kind, String source, String taskId) {
        String clean = stripKindPrefix(text == null ? "" : text.trim());
        if (clean.isBlank()) return new Remembered("empty", null, null);
        Day today = day(null);
        for (Note n : today.notes) if (similar(n.text, clean)) return new Remembered("duplicate", n, "today " + n.t);
        for (Map<String, String> hit : search(clean, 3)) {
            String lines = hit.getOrDefault("lines", "");
            if (lines.toLowerCase().contains(clean.toLowerCase()) || similar(firstLine(lines), clean))
                return new Remembered("duplicate", null, hit.get("file"));
        }
        Note n = new Note();
        n.t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        n.kind = normalizeKind(kind, text);
        if (n.kind == null) n.kind = "fact";
        n.text = clean;
        n.taskId = blankToNull(taskId);
        n.auto = true;
        n.source = source == null || source.isBlank() ? null : source.length() > 200 ? source.substring(0, 200) + "…" : source;
        today.notes.add(n);
        saveDay(today);
        if (n.taskId != null) appendTaskNote(n.taskId, n.t + " [" + n.kind + ", auto] " + n.text);
        return new Remembered("stored", n, null);
    }

    private static String firstLine(String s) { int i = s.indexOf('\n'); return i < 0 ? s : s.substring(0, i); }

    public synchronized Day closeDay(String date, String summary, List<String> tomorrow) {
        Day day = day(date);
        day.summary = summary;
        if (tomorrow != null) day.tomorrow = tomorrow;
        day.closed = true;
        day.closedAt = now();
        saveDay(day);

        Stats stats = read(props.stats(), new TypeReference<Stats>() {}, new Stats());
        stats.days.removeIf(s -> s.date.equals(day.date));
        DayStat s = new DayStat();
        s.date = day.date;
        Map<String, Task> byId = tasks().stream().collect(Collectors.toMap(t -> t.id, t -> t));
        s.planned = day.plan.size();
        for (String id : day.plan) {
            Task t = byId.get(id);
            if (t != null && "done".equals(t.status)) { s.done++; s.estDoneMinutes += t.est == null ? 0 : t.est; s.spentMinutes += t.spent == null ? 0 : t.spent; }
        }
        s.notes = day.notes.size();
        s.knowledgeSaved = (int) day.notes.stream().filter(n -> n.saved).count();
        stats.days.add(s);
        stats.days.sort(Comparator.comparing(x -> x.date));
        write(props.stats(), stats);
        return day;
    }

    public synchronized Stats stats() { return read(props.stats(), new TypeReference<Stats>() {}, new Stats()); }

    public synchronized List<String> recentDays(int n) {
        if (!Files.isDirectory(props.days())) return List.of();
        try (Stream<Path> s = Files.list(props.days())) {
            return s.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".json"))
                    .map(f -> f.substring(0, f.length() - 5)).sorted(Comparator.reverseOrder()).limit(n).collect(Collectors.toList());
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ knowledge

    /** The file for a topic: an existing file whose first line is "# topic" (whatever it is named), else a new ASCII-named one. */
    public synchronized Path topicFile(String topic) {
        String want = topic.trim().toLowerCase();
        if (Files.isDirectory(props.knowledge())) {
            try (Stream<Path> s = Files.list(props.knowledge())) {
                for (Path p : s.filter(x -> x.toString().endsWith(".md")).collect(Collectors.toList())) {
                    String first = readString(p).lines().findFirst().orElse("");
                    if (first.startsWith("# ") && first.substring(2).trim().toLowerCase().equals(want)) return p;
                }
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        return props.knowledge().resolve(slug(topic) + ".md");
    }

    public synchronized Path saveKnowledge(String topic, String title, String content, String sourceDate, String taskId) {
        Path f = topicFile(topic);
        String head = Files.exists(f) ? "" : "# " + topic.trim() + "\n";
        StringBuilder sb = new StringBuilder(head);
        sb.append("\n## ").append(title.trim()).append("  (").append(sourceDate == null ? today() : sourceDate).append(")\n\n");
        sb.append(content.trim()).append("\n");
        if (taskId != null && !taskId.isBlank()) sb.append("\n来源:任务 ").append(taskId).append(" · ").append(sourceDate == null ? today() : sourceDate).append("\n");
        append(f, sb.toString());
        return f;
    }

    public synchronized void markNoteSaved(String date, int noteIndex) {
        Day day = day(date);
        if (noteIndex >= 0 && noteIndex < day.notes.size()) { day.notes.get(noteIndex).saved = true; saveDay(day); }
    }

    public synchronized List<KnowledgeCard> knowledgeCards() {
        List<KnowledgeCard> out = new ArrayList<>();
        if (!Files.isDirectory(props.knowledge())) return out;
        try (Stream<Path> s = Files.list(props.knowledge())) {
            for (Path p : s.filter(x -> x.toString().endsWith(".md")).sorted().collect(Collectors.toList())) {
                String text = readString(p);
                KnowledgeCard c = new KnowledgeCard();
                c.file = "knowledge/" + p.getFileName();
                c.topic = text.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2).trim()).orElse(p.getFileName().toString());
                List<String> heads = text.lines().filter(l -> l.startsWith("## ")).collect(Collectors.toList());
                c.entries = heads.size();
                String last = heads.isEmpty() ? "" : heads.get(heads.size() - 1).substring(3);
                int i = last.lastIndexOf("(");
                c.latestTitle = i > 0 ? last.substring(0, i).trim() : last;
                c.lastUpdated = i > 0 ? last.substring(i + 1).replace(")", "").trim() : "";
                out.add(c);
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return out;
    }

    public synchronized String readKnowledge(String topic) {
        Path f = topicFile(topic);
        if (Files.exists(f)) return readString(f);
        // tolerate a partial topic name ("Oracle RU" for "Oracle RU 补丁流程")
        String q = topic.trim().toLowerCase();
        for (KnowledgeCard c : knowledgeCards()) {
            if (c.topic.toLowerCase().contains(q)) return readString(props.dir().resolve(c.file));
        }
        return "";
    }

    /** Case-insensitive substring search over knowledge files, task notes and the day logs; returns file + matching lines. */
    public synchronized List<Map<String, String>> search(String query, int limit) {
        String q = query.toLowerCase();
        List<Map<String, String>> hits = new ArrayList<>();
        // day logs: notes, brief and summary
        for (String d : recentDays(120)) {
            Day day = day(d);
            List<String> lines = new ArrayList<>();
            if (day.brief != null && day.brief.toLowerCase().contains(q)) lines.add("简报: " + day.brief);
            if (day.summary != null && day.summary.toLowerCase().contains(q)) lines.add("总结: " + day.summary);
            for (Note n : day.notes) if (n.text != null && n.text.toLowerCase().contains(q))
                lines.add(n.t + " " + (n.kind == null ? "" : "[" + n.kind + "] ") + n.text + (n.taskId == null ? "" : " (" + n.taskId + ")"));
            if (!lines.isEmpty()) {
                hits.add(Map.of("file", "days/" + d + ".json", "lines", String.join("\n", lines.subList(0, Math.min(4, lines.size())))));
                if (hits.size() >= limit) return hits;
            }
        }
        for (Path dir : List.of(props.knowledge(), props.notes())) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".md")).collect(Collectors.toList())) {
                    String text = readString(p);
                    if (!text.toLowerCase().contains(q)) continue;
                    String lines = text.lines().filter(l -> l.toLowerCase().contains(q)).limit(4).collect(Collectors.joining("\n"));
                    hits.add(Map.of("file", props.dir().relativize(p).toString().replace('\\', '/'), "lines", lines));
                    if (hits.size() >= limit) return hits;
                }
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        return hits;
    }

    // ------------------------------------------------------------------ history

    /** All "## title (date)" sections across the knowledge files, with the task they came from when recorded. */
    public synchronized List<KnowledgeEntry> knowledgeEntries() {
        List<KnowledgeEntry> out = new ArrayList<>();
        if (!Files.isDirectory(props.knowledge())) return out;
        try (Stream<Path> s = Files.list(props.knowledge())) {
            for (Path p : s.filter(x -> x.toString().endsWith(".md")).sorted().collect(Collectors.toList())) {
                String topic = p.getFileName().toString();
                KnowledgeEntry cur = null;
                StringBuilder body = new StringBuilder();
                for (String line : readString(p).lines().collect(Collectors.toList())) {
                    if (line.startsWith("# ")) { topic = line.substring(2).trim(); continue; }
                    if (line.startsWith("## ")) {
                        if (cur != null) { cur.content = body.toString().trim(); out.add(cur); }
                        cur = new KnowledgeEntry();
                        cur.file = "knowledge/" + p.getFileName();
                        cur.topic = topic;
                        String h = line.substring(3).trim();
                        int i = h.lastIndexOf("(");
                        cur.title = i > 0 ? h.substring(0, i).trim() : h;
                        cur.date = i > 0 ? h.substring(i + 1).replace(")", "").trim() : "";
                        body.setLength(0);
                        continue;
                    }
                    if (cur == null) continue;
                    if (line.startsWith("来源:任务 ")) {
                        String rest = line.substring("来源:任务 ".length());
                        int sp = rest.indexOf(' ');
                        cur.taskId = sp > 0 ? rest.substring(0, sp) : rest;
                        continue;
                    }
                    body.append(line).append('\n');
                }
                if (cur != null) { cur.content = body.toString().trim(); out.add(cur); }
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return out;
    }

    /**
     * Task history: every task (any status) with what was recorded about it, filtered by keyword,
     * status and date range, sorted by created / done / updated time, newest first.
     * The keyword is matched against title, context, tags, id, the task's notes, linked day notes
     * and the knowledge entries that came out of it.
     */
    public synchronized List<TaskHistory> history(String query, String status, String from, String to, String sort, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        String st = status == null || status.isBlank() ? "all" : status.trim().toLowerCase();
        String by = sort == null || sort.isBlank() ? "created" : sort.trim().toLowerCase();

        Map<String, TaskHistory> byId = new LinkedHashMap<>();
        for (Task t : tasks()) { TaskHistory h = new TaskHistory(); h.task = t; byId.put(t.id, h); }
        for (String d : recentDays(3650)) {
            Day day = day(d);
            for (String id : day.plan) { TaskHistory h = byId.get(id); if (h != null && !h.days.contains(d)) h.days.add(d); }
            for (Note n : day.notes) {
                if (n.taskId == null) continue;
                TaskHistory h = byId.get(n.taskId);
                if (h == null) continue;
                Map<String, String> m = new LinkedHashMap<>();
                m.put("date", d); m.put("t", n.t); m.put("kind", n.kind == null ? "" : n.kind); m.put("text", n.text);
                h.notes.add(m);
            }
        }
        for (KnowledgeEntry e : knowledgeEntries()) { TaskHistory h = e.taskId == null ? null : byId.get(e.taskId); if (h != null) h.knowledge.add(e); }
        for (TaskHistory h : byId.values()) {
            h.notesText = taskNotes(h.task.id);
            h.days.sort(Comparator.naturalOrder());
            h.sortTime = switch (by) {
                case "done" -> h.task.doneAt;
                case "updated" -> h.task.updatedAt;
                default -> h.task.createdAt;
            };
        }

        return byId.values().stream()
                .filter(h -> switch (st) {
                    case "all" -> true;
                    case "open" -> h.task.isOpen();
                    case "actionable" -> h.task.isActionable();
                    default -> st.equals(h.task.status);
                })
                .filter(h -> from == null || from.isBlank() || (h.sortTime != null && h.sortTime.compareTo(from) >= 0))
                .filter(h -> to == null || to.isBlank() || (h.sortTime != null && h.sortTime.substring(0, 10).compareTo(to) <= 0))
                .filter(h -> q.isEmpty() || matches(h, q))
                // newest first; timestamps are minute-resolution, so tasks created in the same minute fall back to id order
                .sorted(Comparator.comparing((TaskHistory h) -> h.sortTime == null ? "" : h.sortTime)
                        .thenComparing(h -> h.task.id).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    private static boolean matches(TaskHistory h, String q) {
        Task t = h.task;
        if (contains(t.id, q) || contains(t.title, q) || contains(t.context, q) || contains(t.source, q)) return true;
        if (t.tags != null && t.tags.stream().anyMatch(x -> contains(x, q))) return true;
        if (contains(h.notesText, q)) return true;
        if (h.notes.stream().anyMatch(n -> contains(n.get("text"), q))) return true;
        return h.knowledge.stream().anyMatch(e -> contains(e.title, q) || contains(e.content, q) || contains(e.topic, q));
    }
    private static boolean contains(String s, String q) { return s != null && s.toLowerCase().contains(q); }

    // ------------------------------------------------------------------ helpers

    public static String today() { return LocalDate.now().toString(); }
    static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    static String now() { return LocalDateTime.now().format(TS); }

    static String nextId(List<Task> all) {
        int max = 0;
        for (Task t : all) if (t.id != null && t.id.startsWith("T-")) max = Math.max(max, Integer.parseInt(t.id.substring(2)));
        return String.format("T-%04d", max + 1);
    }

    static final Pattern LATIN = Pattern.compile("[a-z0-9]+");
    /** Latin words + CJK character bigrams, so "补丁 CR 定稿并提交" and "补丁CR定稿提交" compare as the same thing. */
    static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        var m = LATIN.matcher(s.toLowerCase());
        while (m.find()) out.add(m.group());
        String cjk = s.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (cjk.length() == 1) out.add(cjk);
        for (int i = 0; i + 1 < cjk.length(); i++) out.add(cjk.substring(i, i + 2));
        return out;
    }
    static boolean similar(String a, String b) {
        String na = a.trim().toLowerCase(), nb = b.trim().toLowerCase();
        if (na.equals(nb) || na.contains(nb) || nb.contains(na)) return true;
        Set<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        long inter = ta.stream().filter(tb::contains).count();
        return (double) inter / (ta.size() + tb.size() - inter) >= 0.6;
    }

    static String normalizeKind(String kind, String text) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        if (k.isEmpty()) {
            String t = text.trim().toLowerCase();
            if (t.startsWith("决定") || t.startsWith("decision")) k = "decision";
            else if (t.startsWith("坑") || t.startsWith("pitfall")) k = "pitfall";
            else if (t.startsWith("学到") || t.startsWith("learned") || t.startsWith("lesson")) k = "learned";
        }
        return switch (k) {
            case "决定", "decision" -> "decision";
            case "坑", "pitfall", "bug" -> "pitfall";
            case "学到", "learned", "lesson" -> "learned";
            case "规则", "rule", "requirement" -> "rule";
            case "事实", "fact", "info" -> "fact";
            case "偏好", "习惯", "preference" -> "preference";
            default -> null;
        };
    }
    static String stripKindPrefix(String text) {
        String t = text.trim();
        for (String p : List.of("决定", "坑", "学到", "decision", "pitfall", "learned", "lesson")) {
            if (t.toLowerCase().startsWith(p)) return t.substring(p.length()).replaceFirst("^[\\s:：,，、]+", "");
        }
        return t;
    }
    /**
     * File name for a topic: ASCII only, so the files survive zip / git / build tools on machines whose
     * default encoding is not UTF-8. Latin words are kept; anything else (Chinese titles) becomes a short
     * stable hash. The readable topic name is always the first line inside the file.
     */
    static String slug(String s) {
        String t = s.trim();
        String ascii = t.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        boolean lossy = !t.replaceAll("[\\s/\\\\:*?\"<>|._-]+", "").chars().allMatch(c -> c < 128);
        if (!lossy && !ascii.isEmpty()) return ascii;
        String hash = Integer.toHexString(t.hashCode() & 0x7fffffff);
        return (ascii.isEmpty() ? "topic" : ascii) + "-" + hash;
    }

    private <T> T read(Path p, TypeReference<T> type, T dflt) {
        if (!Files.exists(p)) return dflt;
        try { return json.readValue(Files.readAllBytes(p), type); }
        catch (IOException e) { throw new IllegalStateException("Cannot read " + p, e); }
    }
    private void write(Path p, Object value) {
        try { Files.createDirectories(p.getParent()); Files.write(p, json.writeValueAsBytes(value)); }
        catch (IOException e) { throw new IllegalStateException("Cannot write " + p, e); }
    }
    private static void append(Path f, String text) {
        try { Files.createDirectories(f.getParent()); Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new IllegalStateException("Cannot write " + f, e); }
    }
    private static String readString(Path p) {
        try { return Files.readString(p, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("Cannot read " + p, e); }
    }
}
