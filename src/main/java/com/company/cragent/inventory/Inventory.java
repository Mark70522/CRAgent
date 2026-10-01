package com.company.cragent.inventory;

import com.company.cragent.config.InventoryProperties;
import com.company.cragent.model.CiInfo;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your server inventory, read from an .xlsx (or .csv) file. Re-read whenever the file changes.
 * Expected columns (names configurable in cr-agent.yml, order does not matter):
 *   service | environment | server | ip | os | owner_group | maintenance_window   (first three required)
 */
@Component
public class Inventory {

    private static final Logger log = LoggerFactory.getLogger(Inventory.class);

    public record Entry(String service, String environment, String server, String ip, String os, String ownerGroup, String maintenanceWindow) {
        CiInfo toCi() { return new CiInfo("inventory:" + server, server, ip, "server", os, environment, ownerGroup, service, maintenanceWindow); }
    }

    private final InventoryProperties props;
    private final Path file;
    private List<Entry> cache = List.of();
    private long cachedMtime = -1;

    public Inventory(InventoryProperties props) {
        this.props = props;
        this.file = props.file() == null || props.file().isBlank() ? null : Path.of(props.file());
    }

    public String source() { return file == null ? "(no inventory file configured)" : file.toString(); }

    public List<CiInfo> lookup(String nameOrIp) {
        String q = nameOrIp.trim().toLowerCase();
        return entries().stream().filter(e -> e.server().toLowerCase().contains(q) || q.equals(nz(e.ip()).toLowerCase())).map(Entry::toCi).toList();
    }

    public List<CiInfo> serversOf(String service, String environment) {
        String s = service.trim().toLowerCase();
        return entries().stream().filter(e -> e.service().toLowerCase().contains(s))
                .filter(e -> environment == null || environment.isBlank() || environment.trim().equalsIgnoreCase(e.environment()))
                .map(Entry::toCi).toList();
    }

    public List<String> services() { return entries().stream().map(Entry::service).distinct().sorted().toList(); }

    public String windowFor(CiInfo ci) {
        return ci.maintenanceSchedule() == null || ci.maintenanceSchedule().isBlank() ? props.maintenanceWindow() : ci.maintenanceSchedule();
    }

    public synchronized List<Entry> entries() {
        if (file == null || !Files.exists(file)) {
            if (file != null) log.warn("Inventory file not found: {}", file);
            return List.of();
        }
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (mtime != cachedMtime) {
                cache = file.toString().toLowerCase().endsWith(".csv") ? loadCsv() : loadXlsx();
                cachedMtime = mtime;
                log.info("Loaded {} inventory rows from {}", cache.size(), file);
            }
            return cache;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read inventory " + file, e);
        }
    }

    private List<Entry> loadXlsx() throws IOException {
        try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = props.sheet() == null || props.sheet().isBlank() ? wb.getSheetAt(0) : wb.getSheet(props.sheet());
            if (sheet == null) throw new IllegalStateException("Sheet '" + props.sheet() + "' not found in " + file);
            DataFormatter fmt = new DataFormatter();
            List<List<String>> rows = new ArrayList<>();
            for (Row r : sheet) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < r.getLastCellNum(); i++) { Cell c = r.getCell(i); cells.add(c == null ? "" : fmt.formatCellValue(c).trim()); }
                rows.add(cells);
            }
            return parse(rows);
        }
    }

    private List<Entry> loadCsv() throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            List<String> cells = new ArrayList<>();
            for (String c : line.split(",", -1)) cells.add(c.trim());
            rows.add(cells);
        }
        return parse(rows);
    }

    private List<Entry> parse(List<List<String>> rows) {
        if (rows.isEmpty()) return List.of();
        InventoryProperties.Columns col = props.columns();
        Map<String, Integer> idx = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) idx.put(norm(header.get(i)), i);
        int iService = require(idx, col.service()), iEnv = require(idx, col.environment()), iServer = require(idx, col.server());
        Integer iIp = idx.get(norm(col.ip())), iOs = idx.get(norm(col.os())), iGroup = idx.get(norm(col.ownerGroup())), iWin = idx.get(norm(col.maintenanceWindow()));
        List<Entry> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> c = rows.get(r);
            String server = at(c, iServer);
            if (server.isBlank()) continue;
            out.add(new Entry(at(c, iService), at(c, iEnv), server, at(c, iIp), at(c, iOs), at(c, iGroup), at(c, iWin)));
        }
        return out;
    }

    private int require(Map<String, Integer> idx, String name) {
        Integer i = idx.get(norm(name));
        if (i == null) throw new IllegalStateException("Inventory " + file + " has no column '" + name + "'. Columns found: " + idx.keySet() + ". Fix inventory.columns in cr-agent.yml.");
        return i;
    }
    private static String at(List<String> row, Integer i) { return i == null || i >= row.size() ? "" : nz(row.get(i)); }
    private static String norm(String s) { return s == null ? "" : s.toLowerCase().replaceAll("[\\s_\\-]", ""); }
    private static String nz(String s) { return s == null ? "" : s; }
}
