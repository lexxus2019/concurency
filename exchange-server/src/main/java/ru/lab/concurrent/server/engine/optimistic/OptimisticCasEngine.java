package ru.lab.concurrent.server.engine.optimistic;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.LockSupport;

/**
 * Стратегия №5 — оптимистичная блокировка: никаких замков, вместо них CAS по {@code version}
 * и повтор всей транзакции при проигрыше.
 *
 * <p>Так же устроены {@code @Version} в JPA/Hibernate, {@code UPDATE ... WHERE version = ?}
 * в SQL, {@code seq_no} в Kafka-компенсаторах и conditional write в S3/DynamoDB. Студентам
 * важно увидеть, что это не «ещё один замок», а другая модель: конкурентность не блокируется,
 * а разрешается конфликтом post-factum.</p>
 *
 * <p>Профиль нагрузки решает всё. Конфликты редки (много лотов, разные клиенты) — стратегия
 * выигрывает у всех замков. Конфликты часты (один горячий лот, 8 покупателей) — все они
 * крутятся в {@code retries}, латентность растёт, и в пределе мы получаем livelock:
 * работа есть, прогресса нет. В лабе это видно на {@code --scenario=hot}.</p>
 *
 * <p>И отдельно: чтобы это работало, контракт обязан включать версию
 * ({@link ru.lab.concurrent.core.dto.BuyRequest#expectedVersion()}). То есть «выбрать
 * оптимистичную блокировку» — это не решение бэкенда, а решение ВСЕХ команд сразу.
 * Отличный пример того, почему контракт согласуется до кода, а не после.</p>
 */
@Component
public class OptimisticCasEngine extends AbstractEngine {

    private final int maxRetries;

    public OptimisticCasEngine(Book book, LabProperties props) {
        super(book, "optimistic");
        this.maxRetries = props.optimisticRetries();
    }

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public String title() {
        return "оптимистично: version + CAS + retry";
    }

    @Override
    public String howItWorks() {
        return "Не блокируем, а сравниваем версию и повторяем. Победителей не ждём — проигравшие"
                + " платят retries. Хорошо при редких конфликтах, плохо на одном горячем лоте.";
    }

    /**
     * Общий цикл повторов. Внутри — полная транзакция, поэтому при неудаче откатывать нечего:
     * {@link Transactions#buyCas} сама делает compensating-откат, если не удалось списать деньги.
     */
    private Call retryLoop(java.util.function.Supplier<Transactions.Outcome> body) {
        long start = System.nanoTime();
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            Transactions.Outcome outcome = body.get();
            if (!outcome.casConflict()) {
                counters.hold(microsSince(start));
                return publish(outcome);
            }
            counters.conflict();
            if (attempt + 1 < maxRetries) {
                counters.retry();
                // экспоненциальный backoff прямо здесь: без него все проигравшие толпятся на одном CAS
                LockSupport.parkNanos(2_000L << attempt);
            }
        }
        counters.hold(microsSince(start));
        counters.busy();
        return Call.fail(409, Call.Reason.CONFLICT, null);
    }

    @Override
    public Call buy(String lotId, BuyRequest request) {
        return retryLoop(() -> Transactions.buyCas(book, lotId, request.buyerId(),
                request.qty(), request.expectedVersion()));
    }

    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        return retryLoop(() -> Transactions.setPriceCas(book, lotId, request.price()));
    }

    @Override
    public Call cancel(String lotId) {
        return retryLoop(() -> Transactions.cancelCas(book, lotId));
    }
}
