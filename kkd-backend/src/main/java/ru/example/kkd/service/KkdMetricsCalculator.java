package ru.example.kkd.service;

import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/** Рассчитывает метрики качества и направление изменения относительно прошлого прогона. */
@Component
public class KkdMetricsCalculator {
    public double percentage(long value, long total) {
        return total == 0 ? 100.0 : Math.round(value * 10000.0 / total) / 100.0;
    }

    public Map<String, Object> metric(String key, String title, Number current, Number previous,
                                      String unit, boolean higherIsBetter) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("key", key);
        metric.put("title", title);
        metric.put("unit", unit);
        metric.put("value", current);
        metric.put("previous", previous);
        Double delta = previous == null ? null : current.doubleValue() - previous.doubleValue();
        metric.put("delta", delta);
        metric.put("trend", delta == null ? null : delta == 0 ? "same" :
                ((delta > 0) == higherIsBetter ? "better" : "worse"));
        return metric;
    }
}
