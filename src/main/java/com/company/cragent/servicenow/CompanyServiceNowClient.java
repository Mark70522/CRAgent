package com.company.cragent.servicenow;

import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YOUR implementation against the company's ServiceNow interface. Selected with
 * {@code servicenow.client: company} in cr-agent.yml.
 *
 * Everything generic is already done by {@link SnHttp}: base-url, Basic / Bearer auth from environment
 * variables, extra headers, proxy, timeouts, JSON, readable errors. Fill in the three TODO blocks:
 * which path to call, what to send, where the record sits in the response.
 *
 * Until they are filled in, each method throws a clear "not implemented" error, so a half-done client
 * never silently returns empty data.
 */
@Component
@ConditionalOnProperty(name = "servicenow.client", havingValue = "company")
public class CompanyServiceNowClient implements ServiceNowClient {

    private final SnHttp http;

    public CompanyServiceNowClient(SnHttp http) { this.http = http; }

    @Override
    public String describe() { return "company client -> " + http.props().baseUrl() + " (CompanyServiceNowClient)"; }

    @Override
    public ChangeRecord getChange(String number) {
        // TODO 1/3: read one change request.
        // Example (adjust path and the result location to your interface):
        //   JsonNode resp = http.get("/change/" + number);
        //   JsonNode rec  = SnHttp.path(resp, "data");            // where the record is in the response
        //   return new ChangeRecord(number, SnHttp.flatten(rec), SnHttp.flattenList(rec.get("tasks")), resp);
        throw new ServiceNowException("CompanyServiceNowClient.getChange is not implemented yet");
    }

    @Override
    public ChangeRecord createChange(ChangeDraft draft) {
        // TODO 2/3: create a change request. draft.fields() and draft.tasks() carry exactly the names your
        // templates use (knowledge/templates/*.yaml), so you can send them as they are or wrap them:
        //   Map<String, Object> body = new LinkedHashMap<>(draft.fields());
        //   body.put("tasks", draft.tasks());                      // or a separate call per task
        //   JsonNode resp = http.post("/change", body);
        //   JsonNode rec  = SnHttp.path(resp, "data");
        //   return new ChangeRecord(rec.path("number").asText(), SnHttp.flatten(rec), SnHttp.flattenList(rec.get("tasks")), resp);
        throw new ServiceNowException("CompanyServiceNowClient.createChange is not implemented yet");
    }

    @Override
    public ChangeRecord updateChange(String number, Map<String, Object> fields) {
        // TODO 3/3: update fields of an existing change request.
        //   JsonNode resp = http.patch("/change/" + number, fields);   // or put / post, as your interface wants
        //   JsonNode rec  = SnHttp.path(resp, "data");
        //   return new ChangeRecord(number, SnHttp.flatten(rec), SnHttp.flattenList(rec.get("tasks")), resp);
        throw new ServiceNowException("CompanyServiceNowClient.updateChange is not implemented yet");
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> copy(Map<String, Object> m) { return new LinkedHashMap<>(m); }
}
