package ru.example.kkd.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.kkd.api.NotFoundException;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/** Сервис расчёта ККД, хранения прогонов и подготовки данных для REST API. */
@Service
public class KkdService {
    private final JdbcTemplate jdbc;
    private final KkdRuleEngine ruleEngine;
    private final DeltaCalculator deltaCalculator;
    private final KkdMetricsCalculator metricsCalculator;

    public KkdService(JdbcTemplate jdbc, KkdRuleEngine ruleEngine, DeltaCalculator deltaCalculator, KkdMetricsCalculator metricsCalculator) {
        this.jdbc = jdbc;
        this.ruleEngine = ruleEngine;
        this.deltaCalculator = deltaCalculator;
        this.metricsCalculator = metricsCalculator;
    }

    public List<Map<String, Object>> runs() {
        return jdbc.queryForList("""
            SELECT r.id, r.status, r.snapshot_date, r.compared_to_id, COUNT(i.id) AS issues_count
            FROM kkd_runs r LEFT JOIN kkd_issues i ON i.run_id = r.id
            GROUP BY r.id ORDER BY r.id DESC
            """);
    }

    public Map<String, Object> summary(long runId, long unitId) {
        Map<String, Object> run = getRun(runId);
        Map<String, Object> unit = one("SELECT id, name FROM units WHERE id = ?", unitId, "Подразделение не найдено");
        Long previousId = run.get("compared_to_id") == null ? null : ((Number) run.get("compared_to_id")).longValue();

        long totalGoals = activeGoals(unitId);
        long badGoals = badGoalCount(runId, unitId);
        double cleanShare = metricsCalculator.percentage(totalGoals == 0 ? 0 : totalGoals - badGoals, totalGoals);
        Double previousShare = previousId == null ? null : metricsCalculator.percentage(
                Math.max(0, activeGoals(unitId) - badGoalCount(previousId, unitId)), activeGoals(unitId));
        long totalIssues = issueCount(runId, unitId);
        Long previousIssues = previousId == null ? null : issueCount(previousId, unitId);
        long criticalIssues = severityCount(runId, unitId, "critical");
        Long previousCritical = previousId == null ? null : severityCount(previousId, unitId, "critical");

        List<Map<String, Object>> metrics = List.of(
                metricsCalculator.metric("clean_goals_share", "Доля корректных целей", cleanShare, previousShare, "%", true),
                metricsCalculator.metric("issues_count", "Количество проблем", totalIssues, previousIssues, "шт.", false),
                metricsCalculator.metric("critical_issues_count", "Критические проблемы", criticalIssues, previousCritical, "шт.", false)
        );

        List<Map<String, Object>> severity = new ArrayList<>();
        for (String level : List.of("critical", "major", "minor")) {
            long current = severityCount(runId, unitId, level);
            Long previous = previousId == null ? null : severityCount(previousId, unitId, level);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", level);
            row.put("current", current);
            row.put("previous", previous);
            row.put("delta", previous == null ? null : current - previous);
            severity.add(row);
        }

        Map<String, Object> comparedTo = previousId == null ? null : getRun(previousId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("compared_to", comparedTo);
        result.put("unit", unit);
        result.put("metrics", metrics);
        result.put("issues_by_severity", severity);
        result.put("delta", delta(runId, previousId, unitId));
        return result;
    }

    public Map<String, Object> issues(long runId, Long unitId, String rule, String severity, int limit, int offset) {
        ensureRun(runId);
        if (limit < 1 || limit > 100 || offset < 0) throw new IllegalArgumentException("Некорректная пагинация");
        StringBuilder where = new StringBuilder(" WHERE i.run_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(runId);
        if (unitId != null) {
            ensureUnit(unitId);
            where.append(" AND g.unit_id IN (WITH RECURSIVE tree(id) AS (SELECT id FROM units WHERE id = ? UNION ALL SELECT u.id FROM units u JOIN tree t ON u.parent_id = t.id) SELECT id FROM tree) ");
            args.add(unitId);
        }
        if (rule != null && !rule.isBlank()) {
            if (jdbc.queryForList("SELECT code FROM kkd_rules WHERE code = ?", rule).isEmpty()) throw new IllegalArgumentException("Неизвестное правило");
            where.append(" AND i.rule_code = ? "); args.add(rule);
        }
        if (severity != null && !severity.isBlank()) {
            if (!Set.of("critical", "major", "minor").contains(severity)) throw new IllegalArgumentException("Неизвестная важность");
            where.append(" AND i.severity = ? "); args.add(severity);
        }
        String from = " FROM kkd_issues i JOIN goals g ON g.id = i.goal_id" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit); pageArgs.add(offset);
        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT i.id, i.goal_id, g.title AS goal_title, i.rule_code, i.severity, i.description
            """ + from + " ORDER BY i.id LIMIT ? OFFSET ?", pageArgs.toArray());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items); response.put("limit", limit); response.put("offset", offset); response.put("total", total);
        return response;
    }

    @Transactional
    public Map<String, Object> createRun() {
        Long previous = jdbc.queryForObject("SELECT id FROM kkd_runs ORDER BY id DESC LIMIT 1", Long.class);
        if (previous == null) throw new IllegalStateException("Нет предыдущего прогона для сравнения");
        Long runId = jdbc.queryForObject("INSERT INTO kkd_runs(snapshot_date, compared_to_id) VALUES (?, ?) RETURNING id",
                Long.class, Date.valueOf(LocalDate.now()), previous);

        List<Map<String, Object>> goals = jdbc.queryForList("SELECT id, title, owner, status FROM goals");
        Map<Long, List<KkdRuleEngine.KeyResult>> resultsByGoal = jdbc.query(
                "SELECT id, goal_id, target_value, weight FROM key_results",
                rs -> {
                    Map<Long, List<KkdRuleEngine.KeyResult>> map = new HashMap<>();
                    while (rs.next()) {
                        long goalId = rs.getLong("goal_id");
                        map.computeIfAbsent(goalId, ignored -> new ArrayList<>()).add(
                                new KkdRuleEngine.KeyResult(rs.getLong("id"), rs.getBigDecimal("target_value"), rs.getBigDecimal("weight")));
                    }
                    return map;
                });

        for (Map<String, Object> row : goals) {
            long id = ((Number) row.get("id")).longValue();
            KkdRuleEngine.Goal goal = new KkdRuleEngine.Goal(id, (String) row.get("title"),
                    (String) row.get("owner"), (String) row.get("status"));
            for (KkdRuleEngine.Issue issue : ruleEngine.evaluate(goal, resultsByGoal.getOrDefault(id, List.of()))) {
                jdbc.update("INSERT INTO kkd_issues(run_id, goal_id, rule_code, severity, description) VALUES (?, ?, ?, ?, ?)",
                        runId, issue.goalId(), issue.ruleCode(), issue.severity(), issue.description());
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", runId); response.put("status", "COMPLETED"); response.put("snapshot_date", LocalDate.now()); response.put("compared_to_id", previous);
        return response;
    }

    private Map<String, Object> delta(long runId, Long previousId, long unitId) {
        if (previousId == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("new", null); empty.put("persisting", null); empty.put("resolved", null);
            return empty;
        }
        Set<String> current = issueKeys(runId, unitId);
        Set<String> previous = issueKeys(previousId, unitId);
        DeltaCalculator.Counts counts = deltaCalculator.calculate(current, previous);
        return Map.of("new", counts.newIssues(), "persisting", counts.persisting(), "resolved", counts.resolved());
    }

    private Set<String> issueKeys(long runId, long unitId) {
        return new HashSet<>(jdbc.queryForList("""
            WITH RECURSIVE tree(id) AS (
              SELECT id FROM units WHERE id = ?
              UNION ALL SELECT u.id FROM units u JOIN tree t ON u.parent_id = t.id
            )
            SELECT CAST(i.goal_id AS TEXT) || ':' || i.rule_code
            FROM kkd_issues i JOIN goals g ON g.id = i.goal_id JOIN tree t ON t.id = g.unit_id
            WHERE i.run_id = ?
            """, String.class, unitId, runId));
    }

    private long activeGoals(long unitId) {
        return jdbc.queryForObject("""
            WITH RECURSIVE tree(id) AS (SELECT id FROM units WHERE id = ? UNION ALL SELECT u.id FROM units u JOIN tree t ON u.parent_id = t.id)
            SELECT COUNT(*) FROM goals g JOIN tree t ON t.id = g.unit_id WHERE g.status = 'ACTIVE'
            """, Long.class, unitId);
    }
    private long badGoalCount(long runId, long unitId) {
        return jdbc.queryForObject("""
            WITH RECURSIVE tree(id) AS (SELECT id FROM units WHERE id = ? UNION ALL SELECT u.id FROM units u JOIN tree t ON u.parent_id = t.id)
            SELECT COUNT(DISTINCT i.goal_id) FROM kkd_issues i JOIN goals g ON g.id = i.goal_id JOIN tree t ON t.id = g.unit_id WHERE i.run_id = ?
            """, Long.class, unitId, runId);
    }
    private long issueCount(long runId, long unitId) {
        return jdbc.queryForObject("""
            WITH RECURSIVE tree(id) AS (SELECT id FROM units WHERE id = ? UNION ALL SELECT u.id FROM units u JOIN tree t ON u.parent_id = t.id)
            SELECT COUNT(*) FROM kkd_issues i JOIN goals g ON g.id = i.goal_id JOIN tree t ON t.id = g.unit_id WHERE i.run_id = ?
            """, Long.class, unitId, runId);
    }
    private long severityCount(long runId, long unitId, String severity) {
        return jdbc.queryForObject("""
            WITH RECURSIVE tree(id) AS (SELECT id FROM units WHERE id = ? UNION ALL SELECT u.id FROM units u JOIN tree t ON u.parent_id = t.id)
            SELECT COUNT(*) FROM kkd_issues i JOIN goals g ON g.id = i.goal_id JOIN tree t ON t.id = g.unit_id
            WHERE i.run_id = ? AND i.severity = ?
            """, Long.class, unitId, runId, severity);
    }
    private Map<String, Object> getRun(long id) { return one("SELECT id, status, snapshot_date, compared_to_id FROM kkd_runs WHERE id = ?", id, "Прогон не найден"); }
    private void ensureRun(long id) { getRun(id); }
    private void ensureUnit(long id) { one("SELECT id FROM units WHERE id = ?", id, "Подразделение не найдено"); }
    private Map<String, Object> one(String sql, Object arg, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, arg);
        if (rows.isEmpty()) throw new NotFoundException(message);
        return rows.get(0);
    }
}
