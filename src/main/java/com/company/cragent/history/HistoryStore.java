package com.company.cragent.history;

import com.company.cragent.model.ChangeRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Local SQLite copy of historical changes so similarity lookups are fast and work offline. */
@Component
public class HistoryStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public HistoryStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void upsert(ChangeRecord r) {
        try {
            jdbc.update("""
                    INSERT OR REPLACE INTO change_history
                    (number, sys_id, type, category, cmdb_ci, assignment_group, state, close_code, approval,
                     short_description, description, record_json, sys_updated_on, synced_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    r.number(), r.sysId(), r.field("type"), r.field("category"), r.field("cmdb_ci"),
                    r.field("assignment_group"), r.field("state"), r.field("close_code"), r.field("approval"),
                    r.field("short_description"), r.field("description"),
                    json.writeValueAsString(r), r.field("sys_updated_on"), LocalDateTime.now().toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM change_history", Integer.class);
        return n == null ? 0 : n;
    }

    public String lastSyncedUpdatedOn() {
        return jdbc.query("SELECT MAX(sys_updated_on) FROM change_history",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    /**
     * Similar changes, approved ones first. Any argument may be null/blank to skip that filter.
     */
    public List<ChangeRecord> findSimilar(String ci, String type, String keyword, boolean approvedOnly, int limit) {
        StringBuilder sql = new StringBuilder("SELECT record_json FROM change_history WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (notBlank(ci))      { sql.append(" AND lower(cmdb_ci) LIKE ?"); args.add("%" + ci.toLowerCase() + "%"); }
        if (notBlank(type))    { sql.append(" AND lower(type) = ?");       args.add(type.toLowerCase()); }
        if (notBlank(keyword)) {
            sql.append(" AND (lower(short_description) LIKE ? OR lower(description) LIKE ?)");
            args.add("%" + keyword.toLowerCase() + "%");
            args.add("%" + keyword.toLowerCase() + "%");
        }
        if (approvedOnly) sql.append(" AND lower(approval) = 'approved'");
        sql.append(" ORDER BY CASE WHEN lower(approval)='approved' THEN 0 ELSE 1 END, sys_updated_on DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> fromJson(rs.getString(1)), args.toArray());
    }

    public List<ChangeRecord> findRejected(int limit) {
        return jdbc.query("SELECT record_json FROM change_history WHERE lower(approval)='rejected' "
                        + "ORDER BY sys_updated_on DESC LIMIT ?",
                (rs, i) -> fromJson(rs.getString(1)), limit);
    }

    /** Field value distribution for a CI / type, used to derive defaults from history. */
    public List<Map<String, Object>> fieldStats(String ci, String type, String column) {
        if (!column.matches("[a-z_]+")) throw new IllegalArgumentException("bad column " + column);
        String sql = "SELECT " + column + " AS value, COUNT(*) AS n FROM change_history WHERE lower(approval)='approved'"
                + (notBlank(ci) ? " AND lower(cmdb_ci) LIKE '%" + ci.toLowerCase().replace("'", "''") + "%'" : "")
                + (notBlank(type) ? " AND lower(type) = '" + type.toLowerCase().replace("'", "''") + "'" : "")
                + " GROUP BY " + column + " ORDER BY n DESC LIMIT 10";
        return jdbc.queryForList(sql);
    }

    private ChangeRecord fromJson(String s) {
        try { return json.readValue(s, ChangeRecord.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
