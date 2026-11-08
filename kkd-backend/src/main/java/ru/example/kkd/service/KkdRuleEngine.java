package ru.example.kkd.service;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Проверяет активные цели и их ключевые результаты по выбранным правилам ККД. */
@Component
public class KkdRuleEngine {
    public record Goal(long id, String title, String owner, String status) {}
    public record KeyResult(Long id, BigDecimal targetValue, BigDecimal weight) {}
    public record Issue(long goalId, String ruleCode, String severity, String description) {}

    public List<Issue> evaluate(Goal goal, List<KeyResult> keyResults) {
        List<Issue> issues = new ArrayList<>();
        if (!"ACTIVE".equalsIgnoreCase(goal.status())) return issues;
        if (goal.owner() == null || goal.owner().isBlank()) {
            issues.add(new Issue(goal.id(), "GOAL_NO_OWNER", "critical", "У цели не указан ответственный"));
        }
        if (keyResults == null || keyResults.isEmpty()) {
            issues.add(new Issue(goal.id(), "GOAL_NO_KR", "critical", "У активной цели нет ключевых результатов"));
            return issues;
        }
        BigDecimal sum = keyResults.stream().map(KeyResult::weight)
                .filter(w -> w != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(new BigDecimal("100")) != 0) {
            issues.add(new Issue(goal.id(), "KR_WEIGHTS_SUM", "major", "Сумма весов ключевых результатов не равна 100%"));
        }
        if (keyResults.stream().anyMatch(kr -> kr.targetValue() == null)) {
            issues.add(new Issue(goal.id(), "KR_NO_TARGET", "major", "У ключевого результата не заполнено целевое значение"));
        }
        return issues;
    }
}
