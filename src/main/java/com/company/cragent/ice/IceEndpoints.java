package com.company.cragent.ice;

import com.company.cragent.config.IceProperties;
import com.company.cragent.servicenow.EndpointClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** EndpointClient over ice.endpoints. Same placeholder / body / result rules as the ServiceNow one. */
@Component
public class IceEndpoints extends EndpointClient {
    public IceEndpoints(IceProperties props, IceHttp http, ObjectMapper json) { super(props, http, json); }
}
