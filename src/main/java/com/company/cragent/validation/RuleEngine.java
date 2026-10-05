package com.company.cragent.validation;

import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.model.Values;
import com.company.cragent.model.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic checks driven by hard-rules.yaml. The file is re-read on every call so
 * a new rule from the boss takes effect without restarting the server.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    public static final DateTimeFormatter SN_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path rulesFile;

    @org.springframework.beans.factory.annotation.Autowired
    public RuleEngine(KnowledgeProperties props) {
        this(props.rulesDir().resolve("hard-rules.yaml"));
    }

    public RuleEngine(Path rulesFile) {
        this.rulesFile = rulesFile;
    }

    public List<HardRule> loadRules() {
        if (!Files.exists(rulesFile)) {
            log.warn("No hard-rules.yaml at {}", rulesFile);
            return List.of();
        }
        try (InputStream in = Files.newInputStream(rulesFile)) {
            return parseRules(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + rulesFile, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<HardRule> parseRules(InputStream in) {
        Map<String, Object> doc = new Yaml().load(in);
        List<HardRule> rules = new ArrayList<>();
        if (doc == null || !(doc.get("rules") instanceof List<?> list)) return rules;
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            rules.add(new HardRule(
                    str(m.get("id")), str(m.get("description")), str(m.get("severity")), str(m.get("type")),
                    str(m.get("field")), intOrNull(m.get("min")), intOrNull(m.get("max")),
                    m.get("values") instanceof List<?> v ? v.stream().map(String::valueOf).toList() : null,
                    str(m.get("pattern")),
                    m.get("when") instanceof Map<?, ?> w ? (Map<String, Object>) w : null,
                    str(m.get("suggestion")),
                    m.get("days") instanceof List<?> d ? d.stream().map(String::valueOf).toList() : null,
                    str(m.get("from")), str(m.get("to"))));
        }
        return rules;
    }

    /**
     * @param fields change_request columns (ServiceNow names) -> values
     * @param tasks  change_task rows (short_description, order, planned_start_date, planned_end_date, assignment_group)
     */
    public List<Violation> validate(Map<String, String> fields, List<Map<String, String>> tasks) {
        return validate(loadRules(), fields, tasks == null ? List.of() : tasks);
    }

    public List<Violation> validate(Collection<HardRule> rules, Map<String, String> fields, List<Map<String, String>> tasks) {
        return validate(rules, fields, null, tasks);
    }

    /**
     * Same checks with the fields as they really are (lists, objects, reference objects), so a rule's field can
     * be a path into them: {@code cmdb_ci.value}, {@code servers[0]}, {@code steps[*].owner} (every item).
     */
    public List<Violation> validateValues(Map<String, Object> fields, List<Map<String, Object>> tasks) {
        return validate(loadRules(), Values.asText(fields), fields, Values.asTextList(tasks));
    }

    private List<Violation> validate(Collection<HardRule> rules, Map<String, String> fields, Map<String, Object> raw, List<Map<String, String>> tasks) {
        List<Violation> out = new ArrayList<>();
        List<Map<String, String>> t = tasks == null ? List.of() : tasks;
        for (HardRule r : rules) {
            if (r.when() != null && !conditionMatches(r.when(), fields, raw)) continue;
            try {
                check(r, fields, raw, t, out);
            } catch (RuntimeException e) {
                out.add(v(r, r.field(), "Rule could not be evaluated: " + e.getMessage()));
            }
        }
        return out;
    }

    private void check(HardRule r, Map<String, String> f, Map<String, Object> raw, List<Map<String, String>> tasks, List<Violation> out) {
        switch (r.type()) {
            case "required", "required_if" -> each(r, f, raw, (field, val) -> { if (isBlank(val)) out.add(v(r, field, "Field is required")); });
            case "min_length" -> each(r, f, raw, (field, val) -> {
                if (val == null || val.trim().length() < r.min())
                    out.add(v(r, field, "Needs at least " + r.min() + " characters, has " + (val == null ? 0 : val.trim().length())));
            });
            case "max_length" -> each(r, f, raw, (field, val) -> {
                if (val != null && val.length() > r.max()) out.add(v(r, field, "Exceeds " + r.max() + " characters"));
            });
            case "enum" -> each(r, f, raw, (field, val) -> {
                if (!isBlank(val) && r.values().stream().noneMatch(x -> x.equalsIgnoreCase(val)))
                    out.add(v(r, field, "Value '" + val + "' not in " + r.values()));
            });
            case "regex" -> each(r, f, raw, (field, val) -> {
                if (val == null || !Pattern.compile(r.pattern(), Pattern.DOTALL).matcher(val).matches())
                    out.add(v(r, field, "Does not match pattern " + r.pattern()));
            });
            case "forbidden_words" -> each(r, f, raw, (field, val) -> {
                if (val == null) return;
                String lower = val.toLowerCase();
                for (String w : r.values()) if (lower.contains(w.toLowerCase())) out.add(v(r, field, "Contains forbidden text '" + w + "'"));
            });
            case "min_items", "max_items" -> {
                int n = FieldPath.count(r.field(), f, raw);
                if ("min_items".equals(r.type()) && n < r.min()) out.add(v(r, r.field(), "Needs at least " + r.min() + " item(s), has " + n));
                if ("max_items".equals(r.type()) && n > r.max()) out.add(v(r, r.field(), "At most " + r.max() + " item(s), has " + n));
            }
            case "date_order" -> {
                LocalDateTime s = parse(f.get("start_date")), e = parse(f.get("end_date"));
                if (s == null || e == null) out.add(v(r, "start_date", "start_date/end_date missing or not yyyy-MM-dd HH:mm:ss"));
                else if (!s.isBefore(e)) out.add(v(r, "end_date", "end_date must be after start_date"));
            }
            case "tasks_min" -> {
                if (tasks.size() < r.min()) out.add(v(r, "tasks", "Needs at least " + r.min() + " change tasks, has " + tasks.size()));
            }
            case "tasks_within_window" -> {
                LocalDateTime s = parse(f.get("start_date")), e = parse(f.get("end_date"));
                if (s == null || e == null) return;
                for (Map<String, String> t : tasks) {
                    LocalDateTime ts = parse(t.get("planned_start_date")), te = parse(t.get("planned_end_date"));
                    if (ts == null || te == null || ts.isBefore(s) || te.isAfter(e))
                        out.add(v(r, "tasks", "Task '" + t.get("short_description") + "' is outside the change window"));
                }
            }
            case "tasks_sequential" -> {
                List<Map<String, String>> sorted = new ArrayList<>(tasks);
                sorted.sort(Comparator.comparingInt(t -> parseIntOr(t.get("order"), Integer.MAX_VALUE)));
                LocalDateTime prevEnd = null;
                for (Map<String, String> t : sorted) {
                    LocalDateTime ts = parse(t.get("planned_start_date")), te = parse(t.get("planned_end_date"));
                    if (ts == null || te == null) continue;
                    if (!ts.isBefore(te)) out.add(v(r, "tasks", "Task '" + t.get("short_description") + "' ends before it starts"));
                    if (prevEnd != null && ts.isBefore(prevEnd))
                        out.add(v(r, "tasks", "Task '" + t.get("short_description") + "' overlaps the previous task"));
                    prevEnd = te;
                }
            }
            case "tasks_field_required" -> {
                for (Map<String, String> t : tasks) {
                    if (isBlank(t.get(r.field())))
                        out.add(v(r, "tasks." + r.field(), "Task '" + t.get("short_description") + "' is missing " + r.field()));
                }
            }
            case "maintenance_window" -> {
                LocalDateTime s = parse(f.get("start_date")), e = parse(f.get("end_date"));
                if (s == null || e == null) { out.add(v(r, "start_date", "start_date/end_date missing")); return; }
                List<DayOfWeek> days = (r.days() == null ? List.<String>of() : r.days()).stream()
                        .map(d -> DayOfWeek.valueOf(d.trim().toUpperCase())).toList();
                LocalTime from = LocalTime.parse(r.from()), to = LocalTime.parse(r.to());
                String window = days.stream().map(d -> d.toString().substring(0, 3)).toList() + " " + from + "-" + to;
                if (!days.isEmpty() && !days.contains(s.getDayOfWeek()))
                    out.add(v(r, "start_date", "Starts on " + s.getDayOfWeek() + ", allowed window is " + window));
                else if (!s.toLocalDate().equals(e.toLocalDate()) && !(e.toLocalTime().equals(LocalTime.MIDNIGHT) && e.toLocalDate().equals(s.toLocalDate().plusDays(1))))
                    out.add(v(r, "end_date", "Must end on the same day it starts, allowed window is " + window));
                else if (s.toLocalTime().isBefore(from) || endAfter(e.toLocalTime(), to))
                    out.add(v(r, "start_date", "Planned " + s.toLocalTime() + "-" + e.toLocalTime() + " is outside the window " + window));
            }
            default -> out.add(v(r, r.field(), "Unknown rule type '" + r.type() + "' in hard-rules.yaml"));
        }
    }

    /** Runs a single-value check once per value the rule's field resolves to (one for a plain name, every item for [*]). */
    private static void each(HardRule r, Map<String, String> f, Map<String, Object> raw, java.util.function.BiConsumer<String, String> check) {
        if (r.field() == null) { check.accept(null, null); return; }
        for (Map.Entry<String, String> e : FieldPath.resolve(r.field(), f, raw).entrySet()) check.accept(e.getKey(), e.getValue());
    }

    static boolean conditionMatches(Map<String, Object> when, Map<String, String> f) { return conditionMatches(when, f, null); }

    static boolean conditionMatches(Map<String, Object> when, Map<String, String> f, Map<String, Object> raw) {
        String field = str(when.get("field"));
        String actual = field == null ? "" : FieldPath.resolve(field, f, raw).values().stream().findFirst().orElse("");
        if (when.containsKey("equals")) return String.valueOf(when.get("equals")).equalsIgnoreCase(actual);
        if (when.containsKey("not_equals")) return !String.valueOf(when.get("not_equals")).equalsIgnoreCase(actual);
        if (when.containsKey("contains")) return actual.toLowerCase().contains(String.valueOf(when.get("contains")).toLowerCase());
        if (when.containsKey("in") && when.get("in") instanceof List<?> l)
            return l.stream().anyMatch(x -> String.valueOf(x).equalsIgnoreCase(actual));
        if (when.containsKey("blank")) return Boolean.parseBoolean(String.valueOf(when.get("blank"))) == isBlank(actual);
        return true;
    }

    private static Violation v(HardRule r, String field, String msg) {
        return new Violation(r.id(), r.severityOrDefault(), field,
                (r.description() == null ? "" : r.description() + ": ") + msg, r.suggestion());
    }

    public static LocalDateTime parse(String s) {
        if (isBlank(s)) return null;
        try { return LocalDateTime.parse(s.trim(), SN_DATETIME); }
        catch (DateTimeParseException e) { return null; }
    }

    /** End time is after the window end; a window ending at 00:00 means midnight of the next day. */
    private static boolean endAfter(LocalTime end, LocalTime to) {
        if (to.equals(LocalTime.MIDNIGHT)) return false;
        return end.isAfter(to);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Integer intOrNull(Object o) { return o == null ? null : Integer.valueOf(String.valueOf(o)); }
    private static int parseIntOr(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }
}
