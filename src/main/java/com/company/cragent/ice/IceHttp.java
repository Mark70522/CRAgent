package com.company.cragent.ice;

import com.company.cragent.config.IceProperties;
import com.company.cragent.servicenow.SnHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** SnHttp pointed at the ice: section of cr-agent.yml. Its own type, so CompanyIceClient can ask for it by name. */
@Component
public class IceHttp extends SnHttp {
    public IceHttp(IceProperties props, ObjectMapper json) { super(props, json); }
}
