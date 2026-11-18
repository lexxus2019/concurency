package ru.lab.concurrent.server.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.CreateLotRequest;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.server.engine.EngineRegistry;

import java.util.List;

/**
 * Бизнес-API биржи. Это контракт между тремя «командами» лабы, поэтому он стабилен:
 * менять форму запросов можно только договорившись со всеми (см. {@code docs/06-контракт-и-процесс.md}).
 *
 * <p>Два момента, на которые стоит смотреть в задании:</p>
 * <ul>
 *   <li>{@code If-Match} — заголовок версии в стиле HTTP-оптимистичной блокировки. Клиент,
 *       который его не передаёт, пишет «вслепую» (expectedVersion = -1);</li>
 *   <li>конкурентные отказы возвращаются кодами 409/503 с {@code Retry-After}, а не 500.
 *       Разница принципиальная: 500 — это наша ошибка, 409/503 — штатный сигнал «повтори».</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ExchangeController {

    private final EngineRegistry registry;

    public ExchangeController(EngineRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/lots")
    public ResponseEntity<LotView> createLot(@RequestBody CreateLotRequest request) {
        Call call = registry.current().createLot(request);
        return ResponseEntity.status(call.success() ? HttpStatus.CREATED : HttpStatus.valueOf(call.httpStatus()))
                .body(call.lot());
    }

    @GetMapping("/lots")
    public List<LotView> lots() {
        return registry.current().listLots();
    }

    @GetMapping("/lots/{id}")
    public ResponseEntity<LotView> lot(@PathVariable String id) {
        return registry.current().getLot(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/lots/{id}/buy")
    public ResponseEntity<Call> buy(@PathVariable String id,
                                   @RequestBody BuyRequest request,
                                   @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        BuyRequest effective = new BuyRequest(request.buyerId(), request.qty(),
                pickVersion(request.expectedVersion(), ifMatch));
        return respond(registry.current().buy(id, effective));
    }

    @PutMapping("/lots/{id}/price")
    public ResponseEntity<Call> price(@PathVariable String id,
                                      @RequestBody PriceRequest request,
                                      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        PriceRequest effective = new PriceRequest(request.price(),
                pickVersion(request.expectedVersion(), ifMatch));
        return respond(registry.current().setPrice(id, effective));
    }

    @PostMapping("/lots/{id}/cancel")
    public ResponseEntity<Call> cancel(@PathVariable String id) {
        return respond(registry.current().cancel(id));
    }

    // ------------------------------------------ утилиты -----------------------------

    /** Версия из тела приоритетна; если её нет (-1), берём If-Match. Иначе — «слепая» запись. */
    private static long pickVersion(long bodyVersion, String ifMatch) {
        if (bodyVersion >= 0) {
            return bodyVersion;
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            return BuyRequest.IGNORE_VERSION;
        }
        try {
            return Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            return BuyRequest.IGNORE_VERSION;
        }
    }

    private ResponseEntity<Call> respond(Call call) {
        int status = call.httpStatus() <= 0 ? (call.ok() ? 200 : 409) : call.httpStatus();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == 503) {
            // вежливый backpressure: сервер знает, что клиента надо подождать, а не «наказывать»
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return builder.body(call);
    }
}
