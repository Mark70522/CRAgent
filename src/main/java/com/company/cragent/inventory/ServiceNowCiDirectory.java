package com.company.cragent.inventory;

import com.company.cragent.config.InventoryProperties;
import com.company.cragent.model.CiInfo;
import com.company.cragent.model.MaintenanceWindow;
import com.company.cragent.servicenow.ServiceNowGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/** Falls back to the ServiceNow CMDB when no inventory file is configured. */
@Component
@ConditionalOnExpression("'${inventory.file:}' == ''")
public class ServiceNowCiDirectory implements CiDirectory {

    private final ServiceNowGateway sn;

    public ServiceNowCiDirectory(ServiceNowGateway sn, InventoryProperties unused) {
        this.sn = sn;
    }

    @Override
    public List<CiInfo> lookup(String nameOrIp) { return sn.lookupCi(nameOrIp); }

    @Override
    public List<CiInfo> serversOf(String service, String environment) {
        return sn.lookupCi(service).stream()
                .filter(c -> environment == null || environment.isBlank()
                        || environment.equalsIgnoreCase(c.environment()))
                .toList();
    }

    @Override
    public List<String> services() { return List.of(); }

    @Override
    public List<MaintenanceWindow> windowsFor(String nameOrIp) { return sn.getMaintenanceWindows(nameOrIp); }

    @Override
    public String sourceDescription() { return "ServiceNow CMDB"; }
}
