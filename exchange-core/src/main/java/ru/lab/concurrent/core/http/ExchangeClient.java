package ru.lab.concurrent.core.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.CreateLotRequest;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.core.dto.StateView;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Тонкий HTTP-клиент поверх {@code java.net.http} — без Spring, чтобы контрактный модуль
 * оставался крошечным и одинаково работал в сервере, обоих клиентах и харнесе.
 *
 * <p>Это и есть «договор между командами»: сервер обязан отвечать на эти пути и в этих
 * форматах. Хочешь добавить возможность — сначала правь контракт и договорись.</p>
 */
public final class ExchangeClient implements AutoCloseable {

    public static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final HttpClient http;
    private final String base;
    private final Duration timeout;

    public ExchangeClient(String base) {
        this(base, Duration.ofSeconds(10));
    }

    public ExchangeClient(String base, Duration timeout) {
        this.base = trimTrailingSlash(base);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String baseUrl() {
        return base;
    }

    // ------------------------- лабораторные служебные вызовы -------------------------

    /** Простая готовность для waitFor у клиентов и харнеса. */
    public boolean healthy() {
        try {
            return exchange("GET", "/api/lab/ready", null).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public void reset(ScenarioRequest scenario) {
        post("/api/lab/reset", scenario, Void.class);
    }

    public StateView state() {
        return get("/api/lab/state", StateView.class).orElseThrow();
    }

    public List<String> strategies() {
        return getList("/api/lab/strategies", String.class);
    }

    /** Смена стратегии на живом сервисе — без рестарта, чтобы в лабе переключать за секунды. */
    public String strategy(String name) {
        return post("/api/lab/strategy?name=" + name, null, String.class);
    }

    public String currentStrategy() {
        return get("/api/lab/strategy", String.class).orElseThrow();
    }

    /** Сколько критических секций «спит» в hazard-точках (микросекунды). Только для замеров. */
    public void hazardMicros(long micros) {
        post("/api/lab/hazard?micros=" + micros, null, String.class);
    }

    /** Каталог стратегий: name / title / how — из него харнес рисует объяснения. */
    public List<Map<String, String>> catalog() {
        return getMapList("/api/lab/catalog");
    }

    /** Диагностика потоков: есть ли взаимоблокировка и кто висит. */
    public Map<String, Object> threads() {
        try {
            return getMap("/api/lab/threads");
        } catch (Exception e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String path) {
        HttpResponse<String> r = exchange("GET", path, null);
        try {
            return JSON.readValue(r.body(), Map.class);
        } catch (IOException e) {
            throw new TransportException("не распарсить ответ GET " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> getMapList(String path) {
        HttpResponse<String> r = exchange("GET", path, null);
        try {
            return JSON.readValue(r.body(), List.class);
        } catch (IOException e) {
            throw new TransportException("не распарсить ответ GET " + path, e);
        }
    }

    // ------------------------------ бизнес-операции ---------------------------------

    public Call createLot(CreateLotRequest request) {
        return toCall(exchange("POST", "/api/lots", request));
    }

    public List<LotView> lots() {
        return getList("/api/lots", LotView.class);
    }

    public Optional<LotView> lot(String id) {
        return get("/api/lots/" + id, LotView.class);
    }

    public Call buy(String lotId, BuyRequest request) {
        return toCall(exchange("POST", "/api/lots/" + lotId + "/buy", request));
    }

    public Call setPrice(String lotId, PriceRequest request) {
        return toCall(exchange("PUT", "/api/lots/" + lotId + "/price", request));
    }

    public Call cancel(String lotId) {
        return toCall(exchange("POST", "/api/lots/" + lotId + "/cancel", null));
    }

    // --------------------------------- транспорт ------------------------------------

    private HttpResponse<String> exchange(String method, String path, Object body) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(writeJson(body));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .method(method, publisher);
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TransportException(method + " " + path + " -> " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException(method + " " + path + " прерван", e);
        }
    }

    private Call toCall(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200 || status == 201) {
            // успешный ответ может быть телом лота либо {"ok":true,...}
            Call parsed = tryParseCall(response.body());
            if (parsed != null) {
                return parsed;
            }
            LotView lot = read(LotView.class, response.body());
            return new Call(status, true, "OK", lot);
        }
        Call parsed = tryParseCall(response.body());
        if (parsed != null) {
            return new Call(status, false, parsed.reason(), parsed.lot());
        }
        return new Call(status, false, status >= 500 ? Call.Reason.OVERLOADED : Call.Reason.NOT_FOUND, null);
    }

    private Call tryParseCall(String body) {
        if (body == null || body.isBlank() || !body.trim().startsWith("{")) {
            return null;
        }
        try {
            Map<?, ?> map = JSON.readValue(body, Map.class);
            if (!map.containsKey("reason") && !map.containsKey("ok")) {
                return null;
            }
            return new Call(
                    0,
                    Boolean.TRUE.equals(map.get("ok")),
                    String.valueOf(map.get("reason")),
                    map.get("lot") == null ? null : JSON.convertValue(map.get("lot"), LotView.class));
        } catch (Exception e) {
            return null;
        }
    }

    private <T> Optional<T> get(String path, Class<T> type) {
        HttpResponse<String> r = exchange("GET", path, null);
        if (r.statusCode() != 200 || r.body() == null || r.body().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(read(type, r.body()));
    }

    private <T> List<T> getList(String path, Class<T> type) {
        HttpResponse<String> r = exchange("GET", path, null);
        if (r.statusCode() != 200) {
            throw new TransportException("GET " + path + " -> HTTP " + r.statusCode());
        }
        try {
            return JSON.readValue(r.body(), JSON.getTypeFactory().constructCollectionType(List.class, type));
        } catch (IOException e) {
            throw new TransportException("не распарсить ответ GET " + path + ": " + e.getMessage(), e);
        }
    }

    private <T> T post(String path, Object body, Class<T> type) {
        HttpResponse<String> r = exchange("POST", path, body);
        if (r.statusCode() / 100 != 2) {
            throw new TransportException("POST " + path + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return read(type, r.body());
    }

    private <T> T read(Class<T> type, String body) {
        if (type == Void.class || body == null || body.isBlank()) {
            return null;
        }
        if (type == String.class) {
            // строку Spring отдаёт как JSON-строку в кавычках — снимаем кавычки, а не парсим
            String t = body.trim();
            if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                t = t.substring(1, t.length() - 1);
            }
            return type.cast(t);
        }
        try {
            return JSON.readValue(body, type);
        } catch (IOException e) {
            throw new TransportException("не распарсить ответ (" + type.getSimpleName() + "): " + body, e);
        }
    }

    private static String writeJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public void close() {
        // HttpClient закрывается сборщиком мусора; метод есть, чтобы клиенты писали try-with-resources
    }

    /** Сетевой сбой/чужой формат ответа — не путать с бизнес-отказом. */
    public static class TransportException extends RuntimeException {
        public TransportException(String message) {
            super(message);
        }

        public TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
