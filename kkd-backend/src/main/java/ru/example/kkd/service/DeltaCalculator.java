package ru.example.kkd.service;

import org.springframework.stereotype.Component;
import java.util.Set;

/** Считает новые, сохраняющиеся и исправленные проблемы между двумя прогонами. */
@Component
public class DeltaCalculator {
    public record Counts(int newIssues, int persisting, int resolved) {}

    public Counts calculate(Set<String> current, Set<String> previous) {
        int persisting = 0;
        for (String issue : current) if (previous.contains(issue)) persisting++;
        return new Counts(current.size() - persisting, persisting, previous.size() - persisting);
    }
}
