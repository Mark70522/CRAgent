package com.company.cragent.model;

import com.company.cragent.servicenow.SnHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Lists and objects read from the interface stay lists and objects, so writing them back sends the same JSON. */
class ValuesTest {

    @Test
    void keepsTypesAndGivesATextView() throws Exception {
        JsonNode rec = new ObjectMapper().readTree("""
                {"number":"CHG1","servers":["a","b"],"meta":{"x":1},"cmdb_ci":{"value":"abc","display_value":"srv-01"},
                 "count":3,"flag":true,"none":null}""");
        Map<String, Object> v = SnHttp.values(rec);
        assertThat(v.get("servers")).isEqualTo(List.of("a", "b"));
        assertThat(v.get("meta")).isEqualTo(Map.of("x", 1));
        assertThat(v.get("count")).isEqualTo(3);
        assertThat(v.get("flag")).isEqualTo(true);
        assertThat(v).containsKey("none");

        // round trip: what was read serialises back to the same JSON
        JsonNode back = new ObjectMapper().valueToTree(v);
        assertThat(back).isEqualTo(rec);

        Map<String, String> t = Values.asText(v);
        assertThat(t).containsEntry("servers", "[\"a\",\"b\"]").containsEntry("meta", "{\"x\":1}")
                .containsEntry("cmdb_ci", "srv-01").containsEntry("count", "3").containsEntry("none", "");
        assertThat(new ChangeRecord("CHG1", v, List.of(), rec).field("cmdb_ci")).isEqualTo("srv-01");
    }
}
