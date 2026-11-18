package ru.lab.concurrent.server.engine.lock;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Стратегия №3 — «замок на каждую сущность, но в едином порядке».
 *
 * <p>Чинит дыру {@link ShardedTryLockEngine}: транзакция покупки задевает сразу три ресурса
 * (лот, кошелёк покупателя, кошелёк продавца), и все три надо взять вместе. Порядок захвата
 * — по возрастанию хеша имени ресурса. Это классический способ исключить взаимоблокировку
 * без tryLock: если все берут замки в одном глобальном порядке, цикл «я жду тебя, ты ждёшь меня»
 * не может возникнуть.</p>
 *
 * <p>Хороший момент для обсуждения на практике: упорядоченный захват стоит дороже
 * {@code tryLock}, зато не создаёт отказов BUSY, и клиенту не нужен retry-код. Выбор
 * «дороже, но без отказов» против «дешевле, но с повторами» — это и есть проектную
 * решение команды, а не вопрос синтаксиса Java.</p>
 */
@Component
public class TwoLevelLockEngine extends AbstractEngine {

    private static final int SHARDS = 64;
    private final ReentrantLock[] shards = new ReentrantLock[SHARDS];
    private final long timeoutMillis;

    public TwoLevelLockEngine(Book book, LabProperties props) {
        super(book, "locks");
        for (int i = 0; i < SHARDS; i++) {
            shards[i] = new ReentrantLock(props.fair());
        }
        this.timeoutMillis = props.lockTimeoutMs();
    }

    @Override
    public String name() {
        return "locks";
    }

    @Override
    public String title() {
        return "несколько замков в едином порядке";
    }

    @Override
    public String howItWorks() {
        return "Берём замки лота + покупателя + продавца, отсортировав их по индексу шарда:"
                + " взаимоблокировка исключена конструктивно, отказов BUSY почти нет.";
    }

    /** Индекс шарда для именованного ресурса ("lot:lot-1-2", "buyer:buyer-3"). */
    private int shardOf(String resourceKey) {
        int h = resourceKey.hashCode();
        h ^= (h >>> 16);
        return Math.floorMod(h, SHARDS);
    }

    /**
     * Собирает замки всех ресурсов транзакции и упорядочивает их: дубликаты убираются
     * (лот мог принадлежать тому же продавцу, что и покупатель — тогда замок уже взят).
     */
    private List<ReentrantLock> orderedLocks(String lotId, String buyerId, String sellerId) {
        List<Integer> indexes = new ArrayList<>(3);
        indexes.add(shardOf("lot:" + lotId));
        if (buyerId != null) {
            indexes.add(shardOf("buyer:" + buyerId));
        }
        if (sellerId != null) {
            indexes.add(shardOf("seller:" + sellerId));
        }
        List<ReentrantLock> locks = indexes.stream().distinct().sorted()
                .map(i -> shards[i]).toList();
        return locks;
    }

    private Call underOrderedLocks(List<ReentrantLock> locks, java.util.function.Supplier<Transactions.Outcome> body) {
        List<ReentrantLock> taken = new ArrayList<>(locks.size());
        try {
            for (ReentrantLock lock : locks) {
                long waitStart = System.nanoTime();
                boolean got;
                try {
                    got = lock.tryLock(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    counters.busy();
                    return Call.fail(503, Call.Reason.TIMEOUT, null);
                }
                counters.lockWait(microsSince(waitStart));
                if (!got) {
                    counters.busy();
                    return Call.fail(503, Call.Reason.BUSY, null);
                }
                taken.add(lock);
            }
            long holdStart = System.nanoTime();
            Call call = publish(body.get());
            counters.hold(microsSince(holdStart));
            return call;
        } finally {
            // освобождаем в обратном порядке — не обязательно, но так удобнее читать тред-дамп
            for (int i = taken.size() - 1; i >= 0; i--) {
                taken.get(i).unlock();
            }
        }
    }

    @Override
    public Call buy(String lotId, BuyRequest request) {
        var lot = book.peekLot(lotId);
        List<ReentrantLock> locks = orderedLocks(lotId, request.buyerId(), lot == null ? null : lot.sellerId());
        return underOrderedLocks(locks, () -> Transactions.buy(book, lotId, request.buyerId(),
                request.qty(), request.expectedVersion()));
    }

    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        var lot = book.peekLot(lotId);
        List<ReentrantLock> locks = orderedLocks(lotId, null, lot == null ? null : lot.sellerId());
        return underOrderedLocks(locks, () -> Transactions.setPrice(book, lotId, request.price(),
                request.expectedVersion()));
    }

    @Override
    public Call cancel(String lotId) {
        var lot = book.peekLot(lotId);
        List<ReentrantLock> locks = orderedLocks(lotId, null, lot == null ? null : lot.sellerId());
        return underOrderedLocks(locks, () -> Transactions.cancel(book, lotId));
    }

    @Override
    public StateView snapshot() {
        List<ReentrantLock> all = new ArrayList<>(SHARDS);
        for (ReentrantLock shard : shards) {
            shard.lock();
            all.add(shard);
        }
        try {
            counters.read();
            return book.snapshot(counters);
        } finally {
            for (int i = all.size() - 1; i >= 0; i--) {
                all.get(i).unlock();
            }
        }
    }
}
