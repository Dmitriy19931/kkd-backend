package ru.example.kkd.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class KkdRulesTest {
    private final KkdRuleEngine engine = new KkdRuleEngine();
    private final DeltaCalculator deltaCalculator = new DeltaCalculator();

    @Test void goalWithoutOwnerProducesCriticalIssue() {
        var issues = engine.evaluate(new KkdRuleEngine.Goal(1, "Goal", " ", "ACTIVE"),
                List.of(new KkdRuleEngine.KeyResult(1L, BigDecimal.TEN, new BigDecimal("100"))));
        assertTrue(issues.stream().anyMatch(i -> i.ruleCode().equals("GOAL_NO_OWNER") && i.severity().equals("critical")));
    }

    @Test void goalWithoutKeyResultsProducesCriticalIssue() {
        var issues = engine.evaluate(new KkdRuleEngine.Goal(1, "Goal", "Owner", "ACTIVE"), List.of());
        assertEquals(1, issues.size());
        assertEquals("GOAL_NO_KR", issues.get(0).ruleCode());
    }

    @Test void weightsExactlyOneHundredAreAcceptedButBoundaryAboveIsRejected() {
        var accepted = engine.evaluate(new KkdRuleEngine.Goal(1, "Goal", "Owner", "ACTIVE"), List.of(
                new KkdRuleEngine.KeyResult(1L, BigDecimal.ONE, new BigDecimal("50")),
                new KkdRuleEngine.KeyResult(2L, BigDecimal.ONE, new BigDecimal("50"))));
        var rejected = engine.evaluate(new KkdRuleEngine.Goal(2, "Goal 2", "Owner", "ACTIVE"), List.of(
                new KkdRuleEngine.KeyResult(3L, BigDecimal.ONE, new BigDecimal("50")),
                new KkdRuleEngine.KeyResult(4L, BigDecimal.ONE, new BigDecimal("50.01"))));
        assertFalse(accepted.stream().anyMatch(i -> i.ruleCode().equals("KR_WEIGHTS_SUM")));
        assertTrue(rejected.stream().anyMatch(i -> i.ruleCode().equals("KR_WEIGHTS_SUM")));
    }

    @Test void missingTargetIsDetectedAndInactiveGoalIsIgnored() {
        var issues = engine.evaluate(new KkdRuleEngine.Goal(1, "Goal", "Owner", "ACTIVE"),
                List.of(new KkdRuleEngine.KeyResult(1L, null, new BigDecimal("100"))));
        assertTrue(issues.stream().anyMatch(i -> i.ruleCode().equals("KR_NO_TARGET")));
        assertTrue(engine.evaluate(new KkdRuleEngine.Goal(2, "Closed", null, "DONE"), List.of()).isEmpty());
    }

    @Test void metricCalculatesShareDeltaAndTrend() {
        var calculator = new KkdMetricsCalculator();
        assertEquals(75.0, calculator.percentage(3, 4));
        var metric = calculator.metric("clean_goals_share", "Доля корректных целей", 75, 60, "%", true);
        assertEquals(15.0, metric.get("delta"));
        assertEquals("better", metric.get("trend"));
    }

    @Test void deltaCountsNewPersistingAndResolvedIssues() {
        var counts = deltaCalculator.calculate(Set.of("1:A", "2:B", "3:C"), Set.of("1:A", "2:B", "4:D"));
        assertEquals(1, counts.newIssues());
        assertEquals(2, counts.persisting());
        assertEquals(1, counts.resolved());
    }
}
