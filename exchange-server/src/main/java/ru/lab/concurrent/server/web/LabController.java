package ru.lab.concurrent.server.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.engine.EngineRegistry;
import ru.lab.concurrent.server.engine.ExchangeEngine;
import ru.lab.concurrent.server.model.Hazard;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Служебный API лаборатории. Он не имеет отношения к бизнес-логике, но без него
 * практика превращается в «перезапусти и посмотри»: здесь можно на живом сервисе
 * сменить стратегию, обнулить мир, посмотреть метрики и найти взаимоблокировку.
 */
@RestController
@RequestMapping("/api/lab")
public class LabController {

    private final EngineRegistry registry;

    public LabController(EngineRegistry registry) {
        this.registry = registry;
    }

    /** Простая готовность для waitFor у клиентов и харнеса. */
    @GetMapping("/ready")
    public String ready() {
        return "ready";
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody(required = false) ScenarioRequest scenario) {
        ScenarioRequest effective = scenario == null ? ScenarioRequest.labDefault() : scenario;
        registry.reset(effective);
        StateView after = registry.current().snapshot();
        return Map.of(
                "strategy", registry.currentName(),
                "lots", after.lotCount(),
                "money", after.moneyTotalAtStart(),
                "stock", after.stockTotalAtStart());
    }

    @GetMapping("/state")
    public StateView state() {
        return registry.current().snapshot();
    }

    @GetMapping("/strategies")
    public List<String> strategies() {
        return registry.names();
    }

    /** Что доступно и как это называется — это же описание использует таблица харнеса. */
    @GetMapping("/catalog")
    public List<Map<String, String>> catalog() {
        List<Map<String, String>> out = new ArrayList<>();
        for (ExchangeEngine engine : registry.all()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", engine.name());
            row.put("title", engine.title());
            row.put("how", engine.howItWorks());
            out.add(row);
        }
        return out;
    }

    @GetMapping("/strategy")
    public String currentStrategy() {
        return registry.currentName();
    }

    /** Живое переключение стратегии: возвращает прежнее имя, чтобы можно было откатиться. */
    @PostMapping("/strategy")
    public String switchStrategy(@RequestParam String name) {
        return registry.use(name);
    }

    /** Регулировка «медленной работы» внутри критической секции. Микросекунды. */
    @PostMapping("/hazard")
    public String hazard(@RequestParam long micros) {
        long previous = Hazard.micros();
        Hazard.setMicros(micros);
        return "hazard " + previous + " -> " + Hazard.micros() + " micros";
    }

    @GetMapping("/hazard")
    public long hazardValue() {
        return Hazard.micros();
    }

    /**
     * Диагностика живых потоков и поиск взаимоблокировки через {@link ThreadMXBean}.
     * Именно этим пользуется преподаватель, когда «сервис просто перестал отвечать».
     */
    @GetMapping("/threads")
    public Map<String, Object> threads() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long[] deadlocked = bean.findDeadlockedThreads();
        List<String> victims = new ArrayList<>();
        if (deadlocked != null) {
            for (ThreadInfo info : bean.getThreadInfo(deadlocked, true, true)) {
                if (info == null) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(info.getThreadName()).append(" [").append(info.getThreadState())
                        .append("] ждёт монитор ").append(info.getLockName())
                        .append(" (владельца не видно: ").append(info.getLockOwnerName()).append(")\n");
                StackTraceElement[] stack = info.getStackTrace();
                for (int i = 0; i < Math.min(6, stack.length); i++) {
                    sb.append("    at ").append(stack[i]).append('\n');
                }
                victims.add(sb.toString());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalThreads", bean.getThreadCount());
        out.put("peakThreads", bean.getPeakThreadCount());
        out.put("deadlocked", !victims.isEmpty());
        out.put("deadlockedCount", deadlocked == null ? 0 : deadlocked.length);
        out.put("details", victims);
        out.put("busyThreads", List.of(bean.getThreadInfo(bean.getAllThreadIds(), 1)).stream()
                .filter(java.util.Objects::nonNull)
                .filter(t -> t.getThreadState() == Thread.State.BLOCKED || t.getThreadState() == Thread.State.WAITING)
                .map(ThreadInfo::getThreadName)
                .limit(40)
                .toList());
        return out;
    }
}
