package com.company.cragent.knowledge;

import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.model.ChangeRecord;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Archived change requests as structured YAML, so they serve two purposes at once:
 * few-shot examples for Copilot and a regression set for the rules (see Regression).
 *
 *   knowledge/examples/<category>/CHG0012345.yaml   outcome: approved
 *   knowledge/rejected/CHG0012346.yaml              outcome: rejected, reason, expected_rules
 */
@Component
public class ExampleStore {

    /** One archived change. */
    public record Example(Path file, String number, String category, String outcome, String reason,
                          List<String> expectedRules, Map<String, String> fields, List<Map<String, String>> tasks, String savedOn) {
        public boolean approved() { return "approved".equalsIgnoreCase(outcome); }
        public String relative(Path root) { return root.relativize(file).toString().replace('\\', '/'); }
    }

    private final KnowledgeProperties props;

    public ExampleStore(KnowledgeProperties props) { this.props = props; }

    public Path saveApproved(ChangeRecord rec, String category) {
        Path f = props.examplesDir().resolve(safe(category)).resolve(rec.number() + ".yaml");
        write(f, doc(rec, safe(category), "approved", null, List.of()));
        return f;
    }

    public Path saveRejected(ChangeRecord rec, String reason, List<String> expectedRules) {
        Path f = props.rejectedDir().resolve(rec.number() + ".yaml");
        write(f, doc(rec, null, "rejected", reason, expectedRules == null ? List.of() : expectedRules));
        return f;
    }

    public List<Example> approved() { return load(props.examplesDir()); }

    public List<Example> rejected() { return load(props.rejectedDir()); }

    public List<Example> all() { List<Example> a = new ArrayList<>(approved()); a.addAll(rejected()); return a; }

    public String read(String relativePath) {
        Path p = props.knowledgeDir().resolve(relativePath).normalize();
        if (!p.startsWith(props.knowledgeDir().normalize())) throw new IllegalArgumentException("Path outside knowledge dir");
        try { return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : ""; }
        catch (IOException e) { throw new IllegalStateException("Cannot read " + p, e); }
    }

    public Path root() { return props.knowledgeDir(); }

    // ------------------------------------------------------------------ yaml

    private static Map<String, Object> doc(ChangeRecord rec, String category, String outcome, String reason, List<String> expectedRules) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("number", rec.number());
        if (category != null) d.put("category", category);
        d.put("outcome", outcome);
        if (reason != null) d.put("reason", reason);
        if (!expectedRules.isEmpty()) d.put("expected_rules", expectedRules);
        d.put("saved_on", LocalDate.now().toString());
        d.put("fields", new LinkedHashMap<>(rec.fields()));
        d.put("tasks", rec.tasks().stream().map(LinkedHashMap::new).collect(Collectors.toList()));
        return d;
    }

    @SuppressWarnings("unchecked")
    private List<Example> load(Path dir) {
        List<Example> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".yaml") || x.toString().endsWith(".yml")).sorted().collect(Collectors.toList())) {
                try (InputStream in = Files.newInputStream(p)) {
                    Map<String, Object> m = new Yaml().load(in);
                    if (m == null) continue;
                    Map<String, String> fields = new LinkedHashMap<>();
                    if (m.get("fields") instanceof Map<?, ?> fm) fm.forEach((k, v) -> fields.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                    List<Map<String, String>> tasks = new ArrayList<>();
                    if (m.get("tasks") instanceof List<?> tl) for (Object o : tl) {
                        Map<String, String> t = new LinkedHashMap<>();
                        if (o instanceof Map<?, ?> tm) tm.forEach((k, v) -> t.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                        tasks.add(t);
                    }
                    List<String> expected = m.get("expected_rules") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
                    String category = m.get("category") == null ? p.getParent().getFileName().toString() : String.valueOf(m.get("category"));
                    out.add(new Example(p, str(m.get("number")), category, str(m.get("outcome")), str(m.get("reason")), expected, fields, tasks, str(m.get("saved_on"))));
                }
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot read " + dir, e); }
        return out;
    }

    private static void write(Path f, Map<String, Object> doc) {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setPrettyFlow(true);
        o.setAllowUnicode(true);
        o.setWidth(120);
        try {
            com.company.cragent.util.DirLock.writeAtomically(f, new Yaml(o).dump(doc));
        } catch (IOException e) { throw new IllegalStateException("Cannot write " + f, e); }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String safe(String s) { return s == null ? "misc" : s.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "-"); }
}
