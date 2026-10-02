package com.company.cragent.ice;

import com.company.cragent.config.IceProperties;
import com.company.cragent.servicenow.FieldMap;
import com.company.cragent.servicenow.SnHttp;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IceClient driven by cr-agent.yml (ice.endpoints: create-ice / update-ice). No Java to write.
 * Selected by default, or with {@code ice.client: yaml}.
 */
@Component
@ConditionalOnProperty(name = "ice.client", havingValue = "yaml", matchIfMissing = true)
public class YamlIceClient implements IceClient {

    public static final String GET = "get-ice", CREATE = "create-ice", UPDATE = "update-ice";

    private final IceEndpoints endpoints;
    private final IceProperties props;
    private final FieldMap names;

    public YamlIceClient(IceEndpoints endpoints, IceProperties props) {
        this.endpoints = endpoints;
        this.props = props;
        this.names = new FieldMap(props.fieldMap());
    }

    @Override public boolean configured() { return endpoints.configured(); }

    @Override
    public String describe() {
        if (!configured()) return "ICE: not configured (no ice: section in cr-agent.yml)";
        return "ICE yaml client -> " + props.baseUrl() + " " + endpoints.endpoints();
    }

    @Override
    public IceRecord get(String iceId) {
        return record(iceId, endpoints.call(GET, Map.of("id", iceId)));
    }

    @Override
    public IceRecord create(String changeNumber, Map<String, Object> fields) {
        Map<String, Object> f = names.out(fields == null ? Map.of() : fields);
        Map<String, Object> params = new LinkedHashMap<>(f);
        params.put("number", changeNumber);
        params.put("fields", f);
        JsonNode rec = endpoints.call(CREATE, params);
        return record(null, rec);
    }

    @Override
    public IceRecord update(String iceId, Map<String, Object> fields) {
        Map<String, Object> f = names.out(fields == null ? Map.of() : fields);
        Map<String, Object> params = new LinkedHashMap<>(f);
        params.put("id", iceId);
        params.put("fields", f);
        return record(iceId, endpoints.call(UPDATE, params));
    }

    private IceRecord record(String id, JsonNode rec) {
        Map<String, String> raw = SnHttp.flatten(rec);
        String i = id != null ? id : raw.getOrDefault(props.idField(), "");
        return new IceRecord(i, names.in(raw), rec);
    }
}
