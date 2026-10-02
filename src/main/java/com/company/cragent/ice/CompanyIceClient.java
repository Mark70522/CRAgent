package com.company.cragent.ice;

import com.company.cragent.servicenow.ServiceNowException;
import com.company.cragent.servicenow.SnHttp;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YOUR implementation against the company's ICE interface. Selected with {@code ice.client: company}.
 * {@link IceHttp} already does base-url, auth, headers, proxy, timeouts, JSON and errors from the ice:
 * section; fill in the three TODO blocks: which path, what to send, where the record is in the response.
 */
@Component
@ConditionalOnProperty(name = "ice.client", havingValue = "company")
public class CompanyIceClient implements IceClient {

    private final IceHttp http;

    public CompanyIceClient(IceHttp http) { this.http = http; }

    @Override public boolean configured() { return http.props().baseUrl() != null && !http.props().baseUrl().isBlank(); }

    @Override public String describe() { return "ICE company client -> " + http.props().baseUrl() + " (CompanyIceClient)"; }

    @Override
    public IceRecord get(String iceId) {
        // TODO 1/3: read one ICE record. Example:
        //   JsonNode resp = http.get("/ice/" + iceId);
        //   JsonNode rec  = SnHttp.path(resp, "data");
        //   return new IceRecord(iceId, SnHttp.flatten(rec), resp);
        throw new ServiceNowException("CompanyIceClient.get is not implemented yet");
    }

    @Override
    public IceRecord create(String changeNumber, Map<String, Object> fields) {
        // TODO 2/3: register the change request in ICE. Example:
        //   Map<String, Object> body = new LinkedHashMap<>(fields);
        //   body.put("changeNumber", changeNumber);
        //   JsonNode resp = http.post("/ice", body);
        //   JsonNode rec  = SnHttp.path(resp, "data");
        //   return new IceRecord(rec.path("id").asText(), SnHttp.flatten(rec), resp);
        throw new ServiceNowException("CompanyIceClient.create is not implemented yet");
    }

    @Override
    public IceRecord update(String iceId, Map<String, Object> fields) {
        // TODO 3/3: update an ICE record.
        //   JsonNode resp = http.put("/ice/" + iceId, fields);
        //   JsonNode rec  = SnHttp.path(resp, "data");
        //   return new IceRecord(iceId, SnHttp.flatten(rec), resp);
        throw new ServiceNowException("CompanyIceClient.update is not implemented yet");
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> copy(Map<String, Object> m) { return new LinkedHashMap<>(m); }
}
