package com.company.cragent.inventory;

import com.company.cragent.config.InventoryProperties;
import com.company.cragent.model.CiInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    private final Inventory inv = new Inventory(new InventoryProperties("knowledge/inventory.xlsx", "", null, "Sun 00:00-06:00"));

    @Test
    void readsHeadersIgnoringCaseAndSpaces() {
        assertThat(inv.entries()).hasSize(9);
        assertThat(inv.services()).containsExactly("Billing", "Order Portal");
    }

    @Test
    void looksUpByNameOrIpAndByService() {
        List<CiInfo> byName = inv.lookup("srv-app-0");
        assertThat(byName).extracting(CiInfo::name).containsExactly("srv-app-01", "srv-app-02");
        assertThat(byName.get(0).ownerGroup()).isEqualTo("Wintel Ops");
        assertThat(inv.lookup("10.0.3.21")).extracting(CiInfo::name).containsExactly("srv-bill-db-01");
        assertThat(inv.serversOf("order portal", "prod")).extracting(CiInfo::name).containsExactly("srv-app-01", "srv-app-02", "srv-db-01");
        assertThat(inv.windowFor(inv.lookup("srv-app-01").get(0))).isEqualTo("Sun 00:00-06:00");
        assertThat(inv.windowFor(inv.lookup("srv-bill-dev-01").get(0))).isEqualTo("Any weekday 18:00-22:00");
    }

    @Test
    void explainsMissingColumns() {
        InventoryProperties.Columns wrong = new InventoryProperties.Columns("Application", null, null, null, null, null, null);
        Inventory bad = new Inventory(new InventoryProperties("knowledge/inventory.xlsx", "", wrong, null));
        assertThatThrownBy(bad::entries).hasMessageContaining("no column 'Application'").hasMessageContaining("cr-agent.yml");
    }
}
