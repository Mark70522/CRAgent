package com.company.cragent.inventory;

import com.company.cragent.config.InventoryProperties;
import com.company.cragent.model.CiInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelCiDirectoryTest {

    private final ExcelCiDirectory dir = new ExcelCiDirectory(
            new InventoryProperties("knowledge/inventory.xlsx", "", null, "Sun 00:00-06:00"));

    @Test
    void readsHeadersIgnoringCaseAndSpaces() {
        assertThat(dir.entries()).hasSize(9);
        assertThat(dir.services()).containsExactly("Billing", "Order Portal");
    }

    @Test
    void looksUpByNameOrIp() {
        List<CiInfo> byName = dir.lookup("srv-app-0");
        assertThat(byName).extracting(CiInfo::name).containsExactly("srv-app-01", "srv-app-02");
        assertThat(byName.get(0).environment()).isEqualTo("prod");
        assertThat(byName.get(0).ownerGroup()).isEqualTo("Wintel Ops");
        assertThat(byName.get(0).businessApplication()).isEqualTo("Order Portal");

        assertThat(dir.lookup("10.0.3.21")).extracting(CiInfo::name).containsExactly("srv-bill-db-01");
    }

    @Test
    void expandsServiceAndEnvironmentToServers() {
        assertThat(dir.serversOf("order portal", "prod")).extracting(CiInfo::name)
                .containsExactly("srv-app-01", "srv-app-02", "srv-db-01");
        assertThat(dir.serversOf("Billing", null)).hasSize(4);
        assertThat(dir.serversOf("Billing", "dev")).extracting(CiInfo::maintenanceSchedule)
                .containsExactly("Any weekday 18:00-22:00");
    }

    @Test
    void defaultWindowWhenColumnEmpty() {
        assertThat(dir.windowsFor("srv-app-01").get(0).name()).isEqualTo("Sun 00:00-06:00");
        assertThat(dir.windowsFor("srv-bill-dev-01").get(0).name()).isEqualTo("Any weekday 18:00-22:00");
    }

    @Test
    void explainsMissingColumns() {
        InventoryProperties.Columns wrong = new InventoryProperties.Columns("Application", null, null, null, null, null, null);
        ExcelCiDirectory bad = new ExcelCiDirectory(new InventoryProperties("knowledge/inventory.xlsx", "", wrong, null));
        assertThatThrownBy(bad::entries).hasMessageContaining("no column 'Application'").hasMessageContaining("cr-agent.yml");
    }
}
