package com.example.probe.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/health")
public class HealthController {

    /** isValid の待ち時間（秒） */
    static final int VALID_TIMEOUT_SEC = 2;
    /** Probe 全体の打ち切り時間（秒）。Probe の timeoutSeconds より短くする */
    static final int TOTAL_TIMEOUT_SEC = 3;
    static final String APP_ENV = "APP_ENV";
    static final String APP_ENV_PROD = "prod";

    private final DataSource dataSource;
    private final ExecutorService healthExecutor;

    public HealthController(DataSource dataSource, ExecutorService healthExecutor) {
        this.dataSource = dataSource;
        this.healthExecutor = healthExecutor;
    }

    /** liveness: DB を見ずに 200 を返す */
    @GetMapping("/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    /** readiness: コネクション取得 + isValid を別スレッドで実行し 3 秒で打ち切る */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
            try {
                return checkConnection();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, healthExecutor);
        try {
            if (f.get(TOTAL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                return ResponseEntity.ok(Map.of("status", "UP"));
            }
            return down("connection invalid");
        } catch (TimeoutException e) {
            f.cancel(true);  // 応答は返すが、裏の処理は blocking-timeout まで残る場合あり
            return down("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down("interrupted");
        } catch (Exception e) {
            Throwable c = e.getCause() != null && e.getCause().getCause() != null ? e.getCause().getCause() : e;
            return down(c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    /**
     * プールを {@code count} 本占有する。1本で {@code DBMS_SESSION.SLEEP} し、残りは閉じずに掴んだままにする。
     * {@code count} はデータソースの {@code max-pool-size} 以下にすること。超えると自分自身の空き待ちで詰まる。
     * 環境変数 {@code APP_ENV=prod} のときは 404。
     */
    @GetMapping("/hold")
    public ResponseEntity<Map<String, Object>> hold(
            @RequestParam(defaultValue = "30") int seconds,
            @RequestParam(defaultValue = "1") int count) {
        if (APP_ENV_PROD.equalsIgnoreCase(System.getenv(APP_ENV))) {
            return ResponseEntity.notFound().build();
        }
        int sec = Math.min(Math.max(seconds, 1), 120);
        int n = Math.min(Math.max(count, 1), 200);
        long start = System.nanoTime();
        List<Connection> held = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                held.add(dataSource.getConnection());
            }
            try (CallableStatement cs = held.get(0).prepareCall("{ call DBMS_SESSION.SLEEP(?) }")) {
                cs.setInt(1, sec);
                cs.execute();
            }
            return ResponseEntity.ok(Map.of(
                    "status", "released",
                    "seconds", sec,
                    "count", n,
                    "elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "reason", String.valueOf(e.getClass().getSimpleName() + ": " + e.getMessage())));
        } finally {
            for (Connection c : held) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // 返却失敗は次の接続に進む
                }
            }
        }
    }

    /** プールから借りて isValid。呼び出しスレッドを blocking-timeout + VALID_TIMEOUT_SEC まで占有しうる */
    private boolean checkConnection() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(VALID_TIMEOUT_SEC);
        }
    }

    private ResponseEntity<Map<String, String>> down(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "DOWN", "reason", String.valueOf(reason)));
    }
}
