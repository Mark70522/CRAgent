package com.company.cragent.knowledge;

import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.servicenow.SnHttp;
import com.company.cragent.tools.ServiceNowTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.company.cragent.template.TemplateService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Historical change requests: import them (by number through the interface, or as JSON exported from it),
 * group them by service + title pattern, and turn a group into a template under knowledge/templates/.
 * Everything comes from the local ledger (cockpit/records/change), so the page can show it without the interface.
 */
@Component
public class HistoryService {

    private static final Pattern SERVER = Pattern.compile("\\b[a-z]+[-_][a-z0-9_-]*\\d+[a-z0-9_-]*\\b");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}[-/.]\\d{1,2}([-/.]\\d{1,2})?\\b|\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final RecordCache cache;
    private final ServiceNowTools sn;
    private final KnowledgeProperties knowledge;
    private final ObjectMapper json;
    private final List<String> serviceFields;

    public HistoryService(RecordCache cache, ServiceNowTools sn, KnowledgeProperties knowledge, ObjectMapper json,
                          @Value("${servicenow.service-field:business_service}") String serviceField) {
        this.cache = cache;
        this.sn = sn;
        this.knowledge = knowledge;
        this.json = json;
        List<String> f = new ArrayList<>();
        for (String s : serviceField.split(",")) if (!s.isBlank()) f.add(s.trim());
        for (String s : List.of("business_service", "u_service", "service", "cmdb_ci")) if (!f.contains(s)) f.add(s);
        this.serviceFields = f;
    }

    // ------------------------------------------------------------------ import

    /** Read each number through the interface; every success lands in the ledger. Returns per-number outcome. */
    public Map<String, Object> importNumbers(List<String> numbers) {
        List<String> ok = new ArrayList<>(), failed = new ArrayList<>();
        for (String n : numbers == null ? List.<String>of() : numbers) {
            String num = n == null ? "" : n.trim();
            if (num.isEmpty()) continue;
            try { sn.getChange(num); ok.add(num); } catch (Exception e) { failed.add(num + ": " + e.getMessage()); }
        }
        return Map.of("imported", ok, "failed", failed);
    }

    /**
     * Records exported from the interface (one JSON object per change, the record as the interface returns it,
     * tasks under "tasks" when present). They are flattened, run through the hard rules and stored as action=import.
     */
    public Map<String, Object> importRecords(List<Map<String, Object>> records) {
        List<String> ok = new ArrayList<>(), failed = new ArrayList<>();
        for (Map<String, Object> r : records == null ? List.<Map<String, Object>>of() : records) {
            try {
                JsonNode raw = json.valueToTree(r);
                Map<String, Object> fields = SnHttp.values(raw);
                fields.remove("tasks");
                List<Map<String, Object>> tasks = SnHttp.valuesList(raw.get("tasks"));
                String number = com.company.cragent.model.Values.text(fields.get("number"));
                if (number.isBlank()) { failed.add("a record without 'number' was skipped"); continue; }
                cache.putChange(sn.describe(new ChangeRecord(number, fields, tasks, raw)), "import");
                ok.add(number);
            } catch (Exception e) {
                failed.add(String.valueOf(e.getMessage()));
            }
        }
        return Map.of("imported", ok, "failed", failed);
    }

    // ------------------------------------------------------------------ groups

    /** One group of similar changes: same service, same title pattern. */
    public record Group(String key, String service, String pattern, int count, List<String> numbers, String latestNumber,
                        String latestTitle, String latestDate, Map<String, String> commonFields, Map<String, Integer> fieldAgreement,
                        String templateName) {}

    public List<Group> groups() {
        Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : cache.list()) {
            if (!"change".equals(row.get("kind"))) continue;
            cache.get("change", String.valueOf(row.get("id"))).ifPresent(rec -> {
                Map<String, String> f = fieldsOf(rec);
                String service = serviceOf(f), pattern = signature(f.getOrDefault("short_description", ""));
                byKey.computeIfAbsent(service + "|" + pattern, k -> new ArrayList<>()).add(rec);
            });
        }
        Set<String> templates = existingTemplates();
        List<Group> out = new ArrayList<>();
        byKey.forEach((key, recs) -> {
            // "latest" = the most recent change by its own dates (start_date, else updated), then by when we fetched it
            recs.sort(Comparator.comparing((Map<String, Object> r) -> {
                Map<String, String> f = fieldsOf(r);
                String d = f.getOrDefault("start_date", "");
                if (d.isBlank()) d = f.getOrDefault("sys_updated_on", "");
                return d + "|" + r.getOrDefault("fetchedAt", "");
            }).reversed());
            Map<String, Object> latest = recs.get(0);
            Map<String, String> lf = fieldsOf(latest);
            String service = key.substring(0, key.indexOf('|')), pattern = key.substring(key.indexOf('|') + 1);
            Map<String, String> common = new LinkedHashMap<>();
            Map<String, Integer> agree = new LinkedHashMap<>();
            commonValues(recs, common, agree);
            String tname = slug(service + " " + pattern);
            out.add(new Group(key, service, pattern, recs.size(), recs.stream().map(r -> String.valueOf(r.get("id"))).collect(Collectors.toList()),
                    String.valueOf(latest.get("id")), lf.getOrDefault("short_description", ""), String.valueOf(latest.getOrDefault("fetchedAt", "")),
                    common, agree, templates.contains(tname) ? tname : null));
        });
        out.sort(Comparator.comparingInt((Group g) -> g.count()).reversed().thenComparing(Group::service));
        return out;
    }

    /** Which field values the group agrees on (>= 60 % of records, non-empty, not a long text or a per-change value). */
    private void commonValues(List<Map<String, Object>> recs, Map<String, String> common, Map<String, Integer> agreement) {
        Set<String> skip = Set.of("number", "sys_id", "short_description", "description", "start_date", "end_date", "sys_updated_on", "sys_created_on",
                "state", "approval", "close_code", "close_notes", "work_notes", "opened_at", "closed_at", "requested_by", "opened_by");
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : recs) fieldsOf(r).forEach((k, v) -> {
            if (skip.contains(k) || v == null || v.isBlank() || v.length() > 200) return;
            counts.computeIfAbsent(k, x -> new LinkedHashMap<>()).merge(v, 1, Integer::sum);
        });
        counts.forEach((k, vs) -> vs.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> {
            int pct = e.getValue() * 100 / recs.size();
            if (pct >= 60) { common.put(k, e.getKey()); agreement.put(k, pct); }
        }));
    }

    // ------------------------------------------------------------------ template from a group

    /** Writes knowledge/templates/<name>.json from a group: agreed field values, title pattern, latest tasks and description headings. */
    public Map<String, Object> templateFromGroup(String groupKey, String name) {
        Group g = groups().stream().filter(x -> x.key().equals(groupKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such group: " + groupKey));
        String tname = name == null || name.isBlank() ? slug(g.service() + " " + g.pattern()) : slug(name);
        Map<String, Object> latest = cache.get("change", g.latestNumber()).orElseThrow();
        Map<String, String> lf = fieldsOf(latest);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", tname);
        t.put("description", "From " + g.count() + " historical change(s) of " + g.service() + ": " + g.latestTitle());
        t.put("match_keywords", keywords(g));
        t.put("fields", new LinkedHashMap<>(g.commonFields()));
        t.put("short_description_pattern", patternToTemplate(g.pattern()));
        t.put("default_duration_minutes", durationOf(lf));
        t.put("tasks", tasksOf(latest));
        t.put("description_sections", sectionsOf(lf.getOrDefault("description", "")));
        t.put("source", Map.of("service", g.service(), "pattern", g.pattern(), "numbers", g.numbers(), "generated", java.time.LocalDate.now().toString()));

        Path f;
        try { f = save(tname, json.writerWithDefaultPrettyPrinter().writeValueAsString(t)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e.getMessage(), e); }
        return Map.of("name", tname, "file", f.toString().replace('\\', '/'), "template", t);
    }

    /** One template as pretty JSON, for the editor page. An old YAML template comes back converted. */
    public String readTemplate(String name) {
        Path f = templateFiles(name).stream().filter(Files::exists).findFirst().orElseThrow(() -> new IllegalArgumentException("No template " + name));
        try {
            if (f.toString().endsWith(".json")) return Files.readString(f, StandardCharsets.UTF_8);
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(TemplateService.readMap(f));
        } catch (IOException e) { throw new IllegalStateException("Cannot read " + f + ": " + e.getMessage(), e); }
    }

    /** Save edited JSON; it must be an object with a name. Writes <name>.json and drops an old YAML copy. */
    public Map<String, Object> writeTemplate(String name, String text) {
        com.fasterxml.jackson.databind.JsonNode parsed;
        try { parsed = json.readTree(text); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Not valid JSON: " + e.getOriginalMessage()); }
        if (parsed == null || !parsed.isObject() || parsed.path("name").asText("").isBlank()) throw new IllegalArgumentException("Template JSON must be an object with a 'name'");
        Path f = save(name, text);
        return Map.of("name", name, "file", f.toString().replace('\\', '/'));
    }

    public boolean deleteTemplate(String name) {
        boolean any = false;
        try { for (Path f : templateFiles(name)) any |= Files.deleteIfExists(f); } catch (IOException e) { throw new IllegalStateException(e.getMessage(), e); }
        return any;
    }

    // ------------------------------------------------------------------ helpers

    private Path save(String name, String text) {
        List<Path> files = templateFiles(name);
        Path f = files.get(0);
        com.company.cragent.util.DirLock.run(f.getParent(), () -> {
            try {
                com.company.cragent.util.DirLock.writeAtomically(f, text);
                for (Path old : files.subList(1, files.size())) Files.deleteIfExists(old);
            } catch (IOException e) { throw new IllegalStateException("Cannot write " + f + ": " + e.getMessage(), e); }
        });
        return f;
    }

    /** <name>.json first, then the old YAML names. */
    private List<Path> templateFiles(String name) {
        String n = slug(name);
        if (n.isEmpty()) throw new IllegalArgumentException("template name is empty");
        return TemplateService.EXTENSIONS.stream().map(ext -> knowledge.templatesDir().resolve(n + ext)).toList();
    }

    private Set<String> existingTemplates() {
        Path dir = knowledge.templatesDir();
        if (!Files.isDirectory(dir)) return Set.of();
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.matches(".*\\.(json|ya?ml)$")).map(n -> n.replaceAll("\\.(json|ya?ml)$", "")).collect(Collectors.toSet());
        } catch (IOException e) { return Set.of(); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> fieldsOf(Map<String, Object> rec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rec.get("fields") instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), com.company.cragent.model.Values.text(v)));
        return out;
    }

    String serviceOf(Map<String, String> f) {
        for (String k : serviceFields) { String v = f.get(k); if (v != null && !v.isBlank()) return v.trim(); }
        return "(unknown service)";
    }

    /** Title with the per-change parts blanked: server names -> <x>, dates -> <date>, numbers -> <n>. */
    public static String signature(String title) {
        String s = title == null ? "" : title.toLowerCase().trim();
        s = DATE.matcher(s).replaceAll("<date>");
        s = SERVER.matcher(s).replaceAll("<x>");
        s = NUMBER.matcher(s).replaceAll("<n>");
        s = s.replaceAll("<n>([.\\-_/]<n>)+", "<n>");       // 2.3.1 -> <n>
        s = s.replaceAll("(<x>[,; ]*)+", "<x> ");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s.isEmpty() ? "(no title)" : s;
    }

    static String patternToTemplate(String pattern) {
        String p = pattern.replace("<x>", "{ci_list}").replace("<date>", "{summary}").replace("<n>", "{summary}");
        if (!p.contains("{summary}")) p = p + " - {summary}";
        return p.replaceAll("\\{summary}(\\s*[-·:]?\\s*\\{summary})+", "{summary}");
    }

    private static List<String> keywords(Group g) {
        Set<String> ks = new LinkedHashSet<>();
        for (String w : g.pattern().replaceAll("[<>\\[\\]{}()\\-:,]", " ").split(" ")) if (w.length() >= 3 && !w.equals("date") && !w.equals("x")) ks.add(w);
        if (!g.service().startsWith("(")) ks.add(g.service().toLowerCase());
        return new ArrayList<>(ks);
    }

    private static int durationOf(Map<String, String> f) {
        try {
            var a = java.time.LocalDateTime.parse(f.get("start_date").replace(' ', 'T'));
            var b = java.time.LocalDateTime.parse(f.get("end_date").replace(' ', 'T'));
            long m = java.time.Duration.between(a, b).toMinutes();
            return m > 0 && m < 24 * 60 ? (int) m : 240;
        } catch (Exception e) { return 240; }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasksOf(Map<String, Object> rec) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(rec.get("tasks") instanceof List<?> l)) return out;
        int order = 10;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("order", order);
            String title = com.company.cragent.model.Values.text(m.get("short_description")); if (!title.isBlank()) t.put("short_description", title);
            String desc = com.company.cragent.model.Values.text(m.get("description")); if (!desc.isBlank()) t.put("description", desc);
            String grp = com.company.cragent.model.Values.text(m.get("assignment_group")); if (!grp.isBlank()) t.put("assignment_group", grp);
            int minutes = 30;
            try {
                var a = java.time.LocalDateTime.parse(String.valueOf(m.get("planned_start_date")).replace(' ', 'T'));
                var b = java.time.LocalDateTime.parse(String.valueOf(m.get("planned_end_date")).replace(' ', 'T'));
                long d = java.time.Duration.between(a, b).toMinutes();
                if (d > 0 && d < 24 * 60) minutes = (int) d;
            } catch (Exception ignored) { }
            t.put("duration_minutes", minutes);
            out.add(t);
            order += 10;
        }
        return out;
    }

    /** Numbered or titled lines of a description become section headings with the first following line as the hint. */
    private static List<Map<String, String>> sectionsOf(String description) {
        List<Map<String, String>> out = new ArrayList<>();
        String[] lines = description.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.matches("^(\\d+[.、)]\\s*|[一二三四五六七八九十]+[、.]\\s*|#+\\s*).{1,40}$")) {
                Map<String, String> s = new LinkedHashMap<>();
                s.put("heading", l.replaceFirst("^(\\d+[.、)]\\s*|[一二三四五六七八九十]+[、.]\\s*|#+\\s*)", ""));
                String hint = "";
                for (int j = i + 1; j < lines.length; j++) { if (!lines[j].trim().isEmpty()) { hint = lines[j].trim(); break; } }
                s.put("hint", hint.length() > 120 ? hint.substring(0, 120) + "…" : hint);
                out.add(s);
            }
        }
        return out;
    }

    static String slug(String s) {
        String x = s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return x.length() > 60 ? x.substring(0, 60).replaceAll("-+$", "") : x;
    }
}
