package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import worldsim.dto.SqlDebugDto;
import worldsim.dto.SqlStepDto;
import worldsim.dto.SqlTableStatDto;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SqlDebugService {

    @Inject
    EntityManager entityManager;

    @Inject
    TickMetrics tickMetrics;

    @Transactional
    public SqlDebugDto snapshot() {
        List<SqlStepDto> physics = tickMetrics.physicsSql();
        List<SqlStepDto> ai = tickMetrics.aiSql();
        List<SqlTableStatDto> tables = tableStats();
        List<String> statements = statementStats();
        return new SqlDebugDto(physics, ai, tables, statements, hints(physics, ai, tables));
    }

    public String formatText(SqlDebugDto debug) {
        StringBuilder out = new StringBuilder();
        out.append("worldsim sql\n");
        out.append("physics (last tick)\n");
        appendSteps(out, debug.physics());
        out.append("ai (last tick)\n");
        appendSteps(out, debug.ai());
        out.append("tables\n");
        for (SqlTableStatDto table : debug.tables()) {
            out.append(String.format(
                    "  %-18s seq=%-8d idx=%-8d rows=%-6d upd=%-8d ins=%d%n",
                    table.table(),
                    table.seqScan(),
                    table.idxScan(),
                    table.liveRows(),
                    table.updates(),
                    table.inserts()));
        }
        if (!debug.statements().isEmpty()) {
            out.append("pg_stat_statements (top by total time)\n");
            for (String line : debug.statements()) {
                out.append("  ").append(line).append('\n');
            }
        }
        if (!debug.hints().isEmpty()) {
            out.append("hints\n");
            for (String hint : debug.hints()) {
                out.append("  - ").append(hint).append('\n');
            }
        }
        return out.toString();
    }

    private static void appendSteps(StringBuilder out, List<SqlStepDto> steps) {
        if (steps.isEmpty()) {
            out.append("  (no sample yet)\n");
            return;
        }
        for (SqlStepDto step : steps) {
            out.append(String.format("  %-14s %4dms  rows=%d%n", step.name(), step.ms(), step.rows()));
        }
    }

    private List<String> hints(
            List<SqlStepDto> physics, List<SqlStepDto> ai, List<SqlTableStatDto> tables) {
        List<String> hints = new ArrayList<>();
        slowest(physics).ifPresent(step -> {
            if (step.ms() >= 40) {
                hints.add("Physics '" + step.name() + "' is the slowest SQL step (" + step.ms() + "ms).");
            }
        });
        slowest(ai).ifPresent(step -> {
            if (step.ms() >= 40) {
                hints.add("AI '" + step.name() + "' is the slowest SQL step (" + step.ms() + "ms).");
            }
        });
        for (SqlTableStatDto table : tables) {
            if (table.liveRows() < 1000 || "routing_edges".equals(table.table())) {
                continue;
            }
            if (table.seqScan() > table.idxScan() * 2 && table.seqScan() > 1000) {
                hints.add(table.table() + " is mostly sequential-scanned; consider a tighter query or index.");
            }
        }
        return hints;
    }

    private static java.util.Optional<SqlStepDto> slowest(List<SqlStepDto> steps) {
        return steps.stream().max(java.util.Comparator.comparingLong(SqlStepDto::ms));
    }

    @SuppressWarnings("unchecked")
    private List<SqlTableStatDto> tableStats() {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT relname,
                               seq_scan,
                               COALESCE(idx_scan, 0),
                               n_tup_upd,
                               n_tup_ins,
                               n_live_tup
                        FROM pg_stat_user_tables
                        WHERE schemaname = 'public'
                        ORDER BY seq_scan DESC
                        """)
                .getResultList();
        List<SqlTableStatDto> stats = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            stats.add(new SqlTableStatDto(
                    row[0].toString(),
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    ((Number) row[5]).longValue()));
        }
        return stats;
    }

    @SuppressWarnings("unchecked")
    private List<String> statementStats() {
        Number enabled = (Number) entityManager.createNativeQuery("""
                        SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_stat_statements'
                        """)
                .getSingleResult();
        if (enabled.intValue() == 0) {
            return List.of();
        }
        try {
            List<Object[]> rows = entityManager.createNativeQuery("""
                            SELECT ROUND(total_exec_time::numeric, 0)::int,
                                   calls,
                                   ROUND(mean_exec_time::numeric, 1),
                                   LEFT(regexp_replace(query, '\\s+', ' ', 'g'), 120)
                            FROM pg_stat_statements
                            WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                              AND query NOT LIKE '%pg_stat_statements%'
                            ORDER BY total_exec_time DESC
                            LIMIT 12
                            """)
                    .getResultList();
            List<String> lines = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                lines.add(String.format(
                        "%sms  calls=%s  avg=%sms  %s",
                        row[0], row[1], row[2], row[3]));
            }
            return lines;
        } catch (RuntimeException e) {
            return List.of("pg_stat_statements is installed but not readable: " + e.getMessage());
        }
    }
}
