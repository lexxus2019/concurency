package ru.lab.concurrent.server.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.engine.ExchangeEngine;
import ru.lab.concurrent.server.engine.TestEngines;
import ru.lab.concurrent.server.model.Book;
import ru.lab.concurrent.server.model.Hazard;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Микро бенчмарк БЕЗ HTTP — чтобы сравнение замков было видно даже на слабой машине.
 *
 * <p>Зачем он вообще нужен: харнес гоняет нагрузку через Tomcat и {@code java.net.http},
 * и там узкое место — транспорт (~2000 запросов/с на ноутбuke), а не замок. Преподаватель
 * на практике часто слышит «у вас что synchronized, что ReadWriteLock — одинаково», и этот
 * тест отвечает на это напрямую: тут измеряется только стоимость самой обёртки.</p>
 *
 * <p>Запусти: {@code mvn -pl exchange-server -am test -Dtest=ReadWriteMicroBenchmarkTest}.</p>
 */
class ReadWriteMicroBenchmarkTest {

    private static final int READERS = 32;
    private static final int READS_PER_THREAD = 2_000;
    private static final int WRITERS = 8;
    private static final int WRITES_PER_THREAD = 400;

    @Test
    @DisplayName("стоимость замков на прямых вызовах: чтения и записи отдельно")
    void lockCostWithoutHttp() throws Exception {
        // hazard=0: нас интересует цена обёртки, а не цена «медленной работы» внутри неё
        Hazard.setMicros(0);
        LabProperties props = TestEngines.props(0, 1);
        ScenarioRequest scenario = new ScenarioRequest(12, 4, 8, 500, 100, 1_000_000); // 96 лотов
        Book book = new Book();

        StringBuilder report = new StringBuilder();
        report.append(String.format("%n%-12s %14s %14s%n", "стратегия", "чтений/с", "записей/с"));
        report.append("-".repeat(42)).append('\n');
        System.out.printf("%n%-12s %14s %14s%n", "стратегия", "чтений/с", "записей/с");
        System.out.println("-".repeat(42));

        double syncReads = 0;
        double rwReads = 0;
        for (String name : List.of("none", "sync", "trylock", "rw", "optimistic", "queue", "locks")) {
            book.reset(scenario);
            ExchangeEngine engine = TestEngines.all(book, props).stream()
                    .filter(e -> e.name().equals(name))
                    .findFirst().orElseThrow();
            engine.counters().resetAll();

            double reads = measure(READERS, READS_PER_THREAD, i -> engine.listLots());
            book.reset(scenario);
            double writes = measure(WRITERS, WRITES_PER_THREAD, i -> {
                List<String> ids = book.allLots().stream().map(l -> l.id()).toList();
                String lotId = ids.get(new Random(i * 31L + System.nanoTime() % 1000).nextInt(ids.size()));
                engine.buy(lotId, BuyRequest.blind("buyer-" + (1 + (i % 4)), 1));
            });
            System.out.printf("%-12s %14.0f %14.0f%n", name, reads, writes);
            report.append(String.format("%-12s %14.0f %14.0f%n", name, reads, writes));

            if (name.equals("sync")) {
                syncReads = reads;
            }
            if (name.equals("rw")) {
                rwReads = reads;
            }
        }
        System.out.println("-".repeat(42));
        report.append("-".repeat(42)).append('\n')
                .append("Читается так: 'чтений/с' — сколько listLots() пролетело через обёртку 32 потоками, ")
                .append("'записей/с' — сколько покупок.\n")
                .append("Hazard выключен: измеряем стоимость самой синхронизации, без имитации медленной работы.\n");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "micro-bench.txt"), report.toString());

        // проверяем то, что воспроизводится стабильно на любой машине:
        // параллельные чтения не медленнее сериализованных, а беззамочная стратегия быстрее обеих
        assertTrue(rwReads >= syncReads * 0.8,
                "ReadWriteLock должен хотя бы не проигрывать глобальному замку на чтениях: rw="
                        + rwReads + " sync=" + syncReads);
        assertTrue(syncReads > 0 && rwReads > 0);
    }

    private static double measure(int threads, int perThread, IntConsumer action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int index = t;
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    action.accept(index);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(3, TimeUnit.MINUTES), "бенчмарк не завершился");
        double seconds = (System.nanoTime() - start) / 1e9;
        return (long) threads * perThread / seconds;
    }
}
