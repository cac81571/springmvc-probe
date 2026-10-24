package com.example.probe.health;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** コネクション取得 + isValid を別スレッドで実行し、打ち切り時間で判定する */
public final class DbConnectionCheck {

    public static final String DS_JNDI = "java:jboss/datasources/OracleDS";
    /** 管理ポートの Health から呼ぶため java:comp ではなくグローバル JNDI（default-bindings と同じプール） */
    public static final String EXECUTOR_JNDI = "java:jboss/ee/concurrency/executor/default";
    public static final int VALID_TIMEOUT_SEC = 2;
    public static final int TOTAL_TIMEOUT_SEC = 3;

    static final String ENV_DS_JNDI = "PROBE_DB_CHECK_DS_JNDI";
    static final String ENV_VALID_TIMEOUT_SEC = "PROBE_DB_CHECK_VALID_TIMEOUT_SEC";
    static final String ENV_TOTAL_TIMEOUT_SEC = "PROBE_DB_CHECK_TOTAL_TIMEOUT_SEC";

    private DbConnectionCheck() {
    }

    public static String dsJndi() {
        return env(ENV_DS_JNDI, DS_JNDI);
    }

    public static int validTimeoutSec() {
        return envInt(ENV_VALID_TIMEOUT_SEC, VALID_TIMEOUT_SEC);
    }

    public static int totalTimeoutSec() {
        return envInt(ENV_TOTAL_TIMEOUT_SEC, TOTAL_TIMEOUT_SEC);
    }

    public static boolean isValid(DataSource dataSource) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(validTimeoutSec());
        }
    }

    /** UP なら {@code null}、DOWN なら reason */
    public static String downReason(DataSource dataSource, Executor executor) {
        CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
            try {
                return isValid(dataSource);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
        try {
            if (f.get(totalTimeoutSec(), TimeUnit.SECONDS)) {
                return null;
            }
            return "connection invalid";
        } catch (TimeoutException e) {
            f.cancel(true);  // 応答は返すが、裏の処理は blocking-timeout まで残る場合あり
            return "timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            Throwable c = e.getCause() != null && e.getCause().getCause() != null ? e.getCause().getCause() : e;
            return c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }

    public static boolean envFlag(String key) {
        String v = System.getenv(key);
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
    }

    static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return v.trim();
    }

    static int envInt(String key, int defaultValue) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
