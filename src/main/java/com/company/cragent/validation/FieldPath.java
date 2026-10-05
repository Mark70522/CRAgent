package com.company.cragent.validation;

import com.company.cragent.model.Values;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule's {@code field} is a field name, or a path into a structured field:
 * <pre>
 *   short_description      the field itself (plain names behave exactly as before)
 *   cmdb_ci.value          a key of an object (e.g. the id of a reference field)
 *   servers[0]             one item of a list
 *   steps[*].owner         every item: the rule is checked once per item
 * </pre>
 * Values come from the real field values when the caller has them, otherwise from the JSON text the
 * text view holds for lists and objects.
 */
final class FieldPath {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SEGMENT = Pattern.compile("\\.([^.\\[\\]]+)|\\[(\\d+|\\*)]");

    private FieldPath() {}

    private record Node(String label, Object value) {}

    /** label (the concrete path, e.g. steps[1].owner) -> value as text. A plain name always yields one entry. */
    static Map<String, String> resolve(String path, Map<String, String> text, Map<String, Object> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!isPath(path, text)) { out.put(path, text.getOrDefault(path, "")); return out; }
        for (Node n : walk(path, text, raw)) out.put(n.label(), Values.text(n.value()));
        return out;
    }

    /** How many items the path holds: list size; a blank value counts 0, anything else 1; [*] counts the matches. */
    static int count(String path, Map<String, String> text, Map<String, Object> raw) {
        List<Node> nodes = isPath(path, text) || raw != null ? walk(path, text, raw) : List.of(new Node(path, rootOf(path, text, raw)));
        if (path.contains("[*]")) return (int) nodes.stream().filter(n -> !Values.text(n.value()).isBlank()).count();
        if (nodes.isEmpty()) return 0;
        Object v = nodes.get(0).value();
        if (v instanceof List<?> l) return l.size();
        if (v instanceof Map<?, ?> m) return m.isEmpty() ? 0 : 1;
        return Values.text(v).isBlank() ? 0 : 1;
    }

    private static boolean isPath(String path, Map<String, String> text) {
        return !text.containsKey(path) && (path.contains(".") || path.contains("["));
    }

    private static List<Node> walk(String path, Map<String, String> text, Map<String, Object> raw) {
        int cut = firstSegment(path);
        String root = path.substring(0, cut);
        List<Node> nodes = new ArrayList<>(List.of(new Node(root, rootOf(root, text, raw))));
        Matcher m = SEGMENT.matcher(path);
        int at = cut;
        while (at < path.length() && m.find(at) && m.start() == at) {
            List<Node> next = new ArrayList<>();
            for (Node n : nodes) {
                if (m.group(1) != null) {
                    Object v = n.value() instanceof Map<?, ?> map ? map.get(m.group(1)) : null;
                    next.add(new Node(n.label() + "." + m.group(1), v));
                } else if ("*".equals(m.group(2))) {
                    if (n.value() instanceof List<?> l) for (int i = 0; i < l.size(); i++) next.add(new Node(n.label() + "[" + i + "]", l.get(i)));
                } else {
                    int i = Integer.parseInt(m.group(2));
                    Object v = n.value() instanceof List<?> l && i < l.size() ? l.get(i) : null;
                    next.add(new Node(n.label() + "[" + i + "]", v));
                }
            }
            nodes = next;
            at = m.end();
        }
        if (at < path.length()) throw new IllegalArgumentException("cannot read field path '" + path + "' at '" + path.substring(at) + "'");
        return nodes;
    }

    private static int firstSegment(String path) {
        int dot = path.indexOf('.'), br = path.indexOf('[');
        int cut = dot < 0 ? br : br < 0 ? dot : Math.min(dot, br);
        return cut < 0 ? path.length() : cut;
    }

    /** The root field: the real value when we have it, else the text view (lists / objects parsed back from JSON). */
    private static Object rootOf(String root, Map<String, String> text, Map<String, Object> raw) {
        if (raw != null && raw.containsKey(root)) return raw.get(root);
        String s = text.get(root);
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("[") || t.startsWith("{")) {
            try { return JSON.readValue(t, Object.class); } catch (Exception ignored) { return s; }
        }
        return s;
    }
}
