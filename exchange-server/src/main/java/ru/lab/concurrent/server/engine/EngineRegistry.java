package ru.lab.concurrent.server.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.model.Book;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Реестр стратегий + «какая сейчас активна».
 *
 * <p>Spring собирает ВСЕ бины {@link ExchangeEngine} в список — поэтому, когда две команды
 * принесли по своей стратегии, им не нужно править один и тот же файл и терять время
 * на merge conflict. Это маленькая, но показательная деталь организации командной работы:
 * архитектура «регистра через DI» вместо {@code switch (type)} снижает число конфликтов
 * практически до нуля. (В {@code switch} два студента конфликтуют всегда.)</p>
 *
 * <p>Переключение на живом процессе: все стратегии работают с одной {@link Book}, поэтому
 * можно в середине лабы сказать «стоп, теперь синхронизация» — и увидеть, что
 * <b>исправление конкуренции не исправляет испорченные данные</b>. Нарушенные инварианты
 * остаются висеть до {@code reset}. Отдельный важный вывод: миграция/починка данных —
 * это отдельная задача, а не часть фикса гонки.</p>
 */
@Component
public class EngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(EngineRegistry.class);

    private final Map<String, ExchangeEngine> byName = new TreeMap<>();
    private final Book book;
    private final java.util.concurrent.atomic.AtomicReference<String> current;

    public EngineRegistry(List<ExchangeEngine> engines, Book book, LabProperties props) {
        this.book = book;
        for (ExchangeEngine engine : engines) {
            ExchangeEngine previous = byName.put(engine.name(), engine);
            if (previous != null) {
                throw new IllegalStateException("дублируем имя стратегии: " + engine.name()
                        + " — переименуй, иначе вторая команда потеряет свою реализацию");
            }
        }
        String initial = props.strategy();
        if (!byName.containsKey(initial)) {
            log.warn("стратегия '{}' не найдена среди {}", initial, byName.keySet());
            initial = "none";
        }
        this.current = new java.util.concurrent.atomic.AtomicReference<>(initial);
        log.info("стратегий доступно: {} — активна: {}", byName.keySet(), initial);
    }

    public Collection<ExchangeEngine> all() {
        return byName.values();
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public ExchangeEngine current() {
        ExchangeEngine engine = byName.get(current.get());
        if (engine == null) {
            throw new IllegalStateException("нет стратегии " + current.get());
        }
        return engine;
    }

    public String currentName() {
        return current.get();
    }

    /** Смена стратегии на живом сервисе. Возвращает прежнее имя — чтобы можно было откатиться. */
    public String use(String name) {
        if (!byName.containsKey(name)) {
            throw new IllegalArgumentException("неизвестная стратегия '" + name + "', доступны " + byName.keySet());
        }
        String previous = current.getAndSet(name);
        log.info("стратегия переключена: {} -> {}", previous, name);
        return previous;
    }

    /** Сброс состояния и счётчиков — прогон каждой стратегии начинается с чистого мира. */
    public void reset(ScenarioRequest scenario) {
        book.reset(scenario);
        for (ExchangeEngine engine : byName.values()) {
            engine.counters().resetAll();
        }
    }
}
