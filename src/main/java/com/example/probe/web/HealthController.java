package com.example.probe.web;

import com.example.probe.health.DbConnectionCheck;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/health")
public class HealthController {

    /** テスト用 `/health/test` を有効にする環境変数。未設定・false は 404 */
    static final String PROBE_DB_CHECK_TEST_ENABLE = "PROBE_DB_CHECK_TEST_ENABLE";

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
        String reason = DbConnectionCheck.downReason(dataSource, healthExecutor);
        return reason == null ? ResponseEntity.ok(Map.of("status", "UP")) : down(reason);
    }

    /**
     * テスト用。プールを {@code count} 本占有する。1本で {@code DBMS_SESSION.SLEEP} し、残りは閉じずに掴んだままにする。
     * {@code count} はデータソースの {@code max-pool-size} 以下にすること。超えると自分自身の空き待ちで詰まる。
     * 環境変数 {@code PROBE_DB_CHECK_TEST_ENABLE=true} のときだけ有効。未設定・false は 404。
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test(
            @RequestParam(name = "seconds", defaultValue = "30") int seconds,
            @RequestParam(name = "count", defaultValue = "1") int count) {
        if (!probeTestEnabled()) {
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

    private static boolean probeTestEnabled() {
        return DbConnectionCheck.envFlag(PROBE_DB_CHECK_TEST_ENABLE);
    }

    private ResponseEntity<Map<String, String>> down(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "DOWN", "reason", String.valueOf(reason)));
    }
}
