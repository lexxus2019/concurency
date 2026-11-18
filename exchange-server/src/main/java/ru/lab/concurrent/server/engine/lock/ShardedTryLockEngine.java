package ru.lab.concurrent.server.engine.lock;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Стратегия №2 — декомпозиция замка: столько замков, сколько лотов (точнее — шардов по лотам),
 * плюс {@code tryLock(timeout)} вместо вечного ожидания.
 *
 * <p>Два урока в одном файле:</p>
 * <ol>
 *   <li><b>Гранулярность.</b> Конкурентность бывает только внутри одного лота. Два клиента,
 *       воюющие за разные лоты, не обязаны стоять в одной очереди — на таблице это
 *       превращает «throughput одного замка» в «throughput на один горячий лот».</li>
 *   <li><b>Отказ вместо зависания.</b> {@code tryLock} с таймаутом отдаёт 503 BUSY и позволяет
 *       клиенту повторить с backoff. Поток, ушедший в бесконечное {@code lock()}, держит
 *       соединение Tomcat вечно: это путь к исчерпанию пула, а не к взаимоблокировке.</li>
 * </ol>
 *
 * <p><b>Ловушка, которую лаба показывает специально:</b> ключ шарда — лот, а транзакция
 * задевает ещё и кошелёк покупателя. Два разных лота одного покупателя попадают в разные
 * шарды, и баланс становится в минус (NEGATIVE_BALANCE). Правильный ответ — либо общий
 * кошелёк-замок с упорядоченным захватом (см. {@link TwoLevelLockEngine}), либо ключ
 * «покупатель+лот». Держать «замок по одному объекту» и думать, что этого достаточно, —
 * самая частая ошибка в реальных ревью.</p>
 */
@Component
public class ShardedTryLockEngine extends AbstractEngine {

    private final ReentrantLock[] shards;
    private final long timeoutMillis;

    public ShardedTryLockEngine(Book book, LabProperties props) {
        super(book, "trylock");
        int n = Integer.highestOneBit(Math.max(1, props.lockShards()));
        this.shards = new ReentrantLock[n];
        for (int i = 0; i < n; i++) {
            shards[i] = new ReentrantLock(props.fair());
        }
        this.timeoutMillis = props.lockTimeoutMs();
    }

    @Override
    public String name() {
        return "trylock";
    }

    @Override
    public String title() {
        return "поделённые замки + tryLock(таймаут)";
    }

    @Override
    public String howItWorks() {
        return "Замок на шард лота, ожидание ограничено таймаутом, при споре — 503 BUSY и повтор на клиенте."
                + " Throughput масштабируется числом лотов. Кошелёк при этом не защищён — следи за NEGATIVE_BALANCE.";
    }

    private ReentrantLock lockFor(String lotId) {
        int h = lotId.hashCode();
        // перемешиваем старшие биты: у id вида lot-1-1 .. lot-3-4 хеши сливаются в один шард без этого
        h ^= (h >>> 16);
        return shards[Math.floorMod(h, shards.length)];
    }

    @Override
    public Call buy(String lotId, BuyRequest request) {
        return guardedTx(lockFor(lotId), timeoutMillis,
                () -> Transactions.buy(book, lotId, request.buyerId(), request.qty(), request.expectedVersion()));
    }

    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        return guardedTx(lockFor(lotId), timeoutMillis,
                () -> Transactions.setPrice(book, lotId, request.price(), request.expectedVersion()));
    }

    @Override
    public Call cancel(String lotId) {
        return guardedTx(lockFor(lotId), timeoutMillis, () -> Transactions.cancel(book, lotId));
    }

    @Override
    public Optional<LotView> getLot(String id) {
        // чтение одного поля из AtomicReference атомарно само по себе — замок не нужен
        counters.read();
        return Optional.ofNullable(book.peekLot(id)).map(l -> l.toView());
    }

    @Override
    public List<LotView> listLots() {
        counters.read();
        List<LotView> out = new ArrayList<>();
        // читаем по одному лоту: список может быть «рваным» по времени — это допустимо для витрины
        book.allLots().forEach(l -> out.add(l.toView()));
        return out;
    }

    @Override
    public ru.lab.concurrent.core.dto.StateView snapshot() {
        // согласованный снимок требует ВСЕХ шардов: берём их строго по возрастанию индекса,
        // иначе два snapshot-потока возьмут замки в разном порядке и застрянут (deadlock)
        List<ReentrantLock> ordered = new ArrayList<>(List.of(shards));
        for (ReentrantLock lock : ordered) {
            lock.lock();
        }
        try {
            counters.read();
            return book.snapshot(counters);
        } finally {
            for (int i = ordered.size() - 1; i >= 0; i--) {
                ordered.get(i).unlock();
            }
        }
    }
}
