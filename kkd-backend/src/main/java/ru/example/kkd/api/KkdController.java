package ru.example.kkd.api;

import org.springframework.web.bind.annotation.*;
import ru.example.kkd.service.KkdService;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class KkdController {
    private final KkdService service;
    public KkdController(KkdService service) { this.service = service; }

    @GetMapping("/runs")
    public Object runs() { return service.runs(); }

    @GetMapping("/kkd/summary")
    public Object summary(@RequestParam("run_id") long run_id, @RequestParam("unit_id") long unit_id) {
        return service.summary(run_id, unit_id);
    }

    @GetMapping("/kkd/issues")
    public Object issues(@RequestParam("run_id") long run_id,
                         @RequestParam(name = "unit_id", required = false) Long unit_id,
                         @RequestParam(name = "rule", required = false) String rule,
                         @RequestParam(name = "severity", required = false) String severity,
                         @RequestParam(name = "limit", defaultValue = "20") int limit,
                         @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw new IllegalArgumentException();
        return service.issues(run_id, unit_id, rule, severity, limit, offset);
    }

    @PostMapping("/runs")
    public Map<String, Object> createRun() { return service.createRun(); }
}
