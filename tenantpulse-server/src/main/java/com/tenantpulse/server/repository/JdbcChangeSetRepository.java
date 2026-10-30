package com.tenantpulse.server.repository;

import com.tenantpulse.core.model.ChangeKind;
import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingChange;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Postgres storage.
 *
 * <p><b>Ordering.</b> Publishing first locks the single {@code config_version} row. Concurrent publishers
 * queue on that lock, so versions are assigned one at a time and each transaction commits before the next
 * one starts. A reader that has seen version N therefore has seen every version below N. A plain SEQUENCE
 * cannot promise this: it hands out numbers before commit, so version 42 could become visible before 41.
 *
 * <p><b>Idempotency.</b> The duplicate-key check runs while holding that same lock, so two identical
 * requests cannot both pass it.
 */
@Repository
@Profile("!memory")
public class JdbcChangeSetRepository implements ChangeSetRepository {

    private static final String CHANGE_SET_COLUMNS =
            "version, author, reason, idempotency_key, kind, ref_version, published_at";

    private final JdbcTemplate jdbc;

    public JdbcChangeSetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public PublishedChangeSet publish(ChangeSet changeSet) {
        jdbc.queryForObject("select version from config_version where id = 1 for update", Long.class);

        String key = changeSet.idempotencyKey();
        if (key != null) {
            List<Long> existing = jdbc.queryForList(
                    "select version from change_set where idempotency_key = ?", Long.class, key);
            if (!existing.isEmpty()) {
                return findByVersion(existing.get(0)).orElseThrow();
            }
        }

        Long version = jdbc.queryForObject(
                "update config_version set version = version + 1 where id = 1 returning version", Long.class);
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        jdbc.update("insert into change_set (" + CHANGE_SET_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?)",
                version, changeSet.author(), changeSet.reason(), key, changeSet.kind().name(),
                changeSet.refVersion(), Timestamp.from(publishedAt));

        List<Object[]> rows = new ArrayList<>();
        int seq = 0;
        for (SettingChange change : changeSet.changes()) {
            Scope scope = change.scope();
            rows.add(new Object[]{version, seq++, change.key(), scope.level().name(), scope.clientId(),
                    scope.officeId(), scope.shiftId(), change.rawValue()});
        }
        jdbc.batchUpdate("insert into setting_change "
                + "(version, seq, setting_key, scope_level, client_id, office_id, shift_id, raw_value) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?)", rows);
        return new PublishedChangeSet(version, changeSet, publishedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublishedChangeSet> findSince(long version, int limit) {
        List<Header> headers = jdbc.query("select " + CHANGE_SET_COLUMNS
                        + " from change_set where version > ? order by version limit ?",
                (rs, n) -> header(rs), version, limit);
        return assemble(headers);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedChangeSet> findByVersion(long version) {
        List<Header> headers = jdbc.query("select " + CHANGE_SET_COLUMNS + " from change_set where version = ?",
                (rs, n) -> header(rs), version);
        return assemble(headers).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedChangeSet> findPromotionOf(long stageVersion) {
        List<Header> headers = jdbc.query("select " + CHANGE_SET_COLUMNS
                        + " from change_set where kind = 'ROLLOUT_PROMOTE' and ref_version = ? order by version limit 1",
                (rs, n) -> header(rs), stageVersion);
        return assemble(headers).stream().findFirst();
    }

    @Override
    public long latestVersion() {
        Long version = jdbc.queryForObject("select version from config_version where id = 1", Long.class);
        return version == null ? 0L : version;
    }

    private record Header(long version, String author, String reason, String idempotencyKey, ChangeKind kind,
                          Long refVersion, Instant publishedAt) {
    }

    private static Header header(java.sql.ResultSet rs) throws java.sql.SQLException {
        long ref = rs.getLong("ref_version");
        Long refVersion = rs.wasNull() ? null : ref;
        return new Header(rs.getLong("version"), rs.getString("author"), rs.getString("reason"),
                rs.getString("idempotency_key"), ChangeKind.valueOf(rs.getString("kind")), refVersion,
                rs.getTimestamp("published_at").toInstant());
    }

    /** Loads the edits of the given headers and joins them. Rows newer than the headers are ignored. */
    private List<PublishedChangeSet> assemble(List<Header> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        long min = headers.get(0).version();
        long max = headers.get(headers.size() - 1).version();
        Map<Long, List<SettingChange>> changesByVersion = new HashMap<>();
        jdbc.query("select version, setting_key, scope_level, client_id, office_id, shift_id, raw_value "
                        + "from setting_change where version >= ? and version <= ? order by version, seq",
                rs -> {
                    Scope scope = new Scope(ScopeLevel.valueOf(rs.getString("scope_level")),
                            rs.getString("client_id"), rs.getString("office_id"), rs.getString("shift_id"));
                    changesByVersion.computeIfAbsent(rs.getLong("version"), v -> new ArrayList<>())
                            .add(new SettingChange(rs.getString("setting_key"), scope, rs.getString("raw_value")));
                }, min, max);

        List<PublishedChangeSet> result = new ArrayList<>(headers.size());
        for (Header h : headers) {
            ChangeSet changeSet = new ChangeSet(h.author(), h.reason(), h.idempotencyKey(), h.kind(), h.refVersion(),
                    changesByVersion.getOrDefault(h.version(), List.of()));
            result.add(new PublishedChangeSet(h.version(), changeSet, h.publishedAt()));
        }
        return result;
    }
}
