package com.company.cragent.knowledge;

import com.company.cragent.config.KnowledgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The field catalog is data: save replaces it, learn adds keys the interface returned, the shipped files parse. */
class FormCatalogTest {

    @TempDir Path tmp;

    @Test
    void shippedCatalogsParse() {
        FormCatalog c = new FormCatalog(new KnowledgeProperties(Path.of("knowledge")), new ObjectMapper());
        for (String kind : FormCatalog.KINDS) assertThat(c.fields(kind)).as(kind).isNotEmpty().allSatisfy(f -> assertThat(f).containsKeys("key", "label", "type"));
        assertThat(c.fields("change")).anySatisfy(f -> assertThat(f.get("key")).isEqualTo("short_description"));
        assertThatThrownBy(() -> c.get("incident")).hasMessageContaining("form kind");
    }

    @Test
    void saveAndLearn() {
        FormCatalog c = new FormCatalog(new KnowledgeProperties(tmp), new ObjectMapper());
        assertThat(c.fields("ice")).isEmpty();

        Map<String, Object> saved = c.save("ice", List.of(Map.of("key", "title", "label", "标题"), Map.of("key", "title"), Map.of("key", " ")));
        assertThat((List<?>) saved.get("fields")).hasSize(1);   // duplicates and blanks dropped
        assertThat(c.fields("ice").get(0)).containsEntry("type", "text");

        List<String> added = c.learn("ice", Map.of("title", "x", "created_on", "2026-10-03 10:00", "notes", "a".repeat(200), "score", "87", "flag", "true"));
        assertThat(added).containsExactlyInAnyOrder("created_on", "notes", "score", "flag");
        Map<String, Map<String, Object>> byKey = new java.util.HashMap<>();
        for (Map<String, Object> f : c.fields("ice")) byKey.put(String.valueOf(f.get("key")), f);
        assertThat(byKey.get("created_on")).containsEntry("type", "datetime").containsEntry("learned", true);
        assertThat(byKey.get("notes")).containsEntry("type", "textarea");
        assertThat(byKey.get("score")).containsEntry("type", "number");
        assertThat(byKey.get("flag")).containsEntry("type", "boolean");
        assertThat(c.learn("ice", Map.of("title", "again"))).isEmpty();
    }

    @Test
    void guessesEveryTypeFromTheValue() {
        assertThat(FormCatalog.guessType("cmdb_ci", Map.of("value", "abc123", "display_value", "srv-01"))).isEqualTo("reference");
        assertThat(FormCatalog.guessType("labels", Map.of("env", "prod", "tier", 1))).isEqualTo("object");
        assertThat(FormCatalog.guessType("meta", Map.of("owner", Map.of("name", "x")))).isEqualTo("json");
        assertThat(FormCatalog.guessType("servers", List.of("srv-01", "srv-02"))).isEqualTo("list");
        assertThat(FormCatalog.guessType("steps", List.of(Map.of("no", 1, "text", "a")))).isEqualTo("table");
        assertThat(FormCatalog.guessType("mixed", List.of("a", Map.of("b", List.of())))).isEqualTo("json");
        assertThat(FormCatalog.guessType("active", true)).isEqualTo("boolean");
        assertThat(FormCatalog.guessType("count", 3)).isEqualTo("number");
        assertThat(FormCatalog.guessType("due", "2026-10-05")).isEqualTo("date");
        assertThat(FormCatalog.guessType("at", "01:30")).isEqualTo("time");
        assertThat(FormCatalog.guessType("mail", "a@b.com")).isEqualTo("email");
        assertThat(FormCatalog.guessType("link", "https://x.y/z")).isEqualTo("url");
        assertThat(FormCatalog.TYPES).contains("multiselect", "select", "text", "textarea", "datetime");
    }
}
