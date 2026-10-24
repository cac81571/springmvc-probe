package com.example.probe.health;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;

/** {@code PROBE_DB_CHECK_ENABLED} が有効でないとき {@link DbReadinessCheck} を登録しない */
public class DbReadinessCheckExtension implements Extension {

    void vetoIfDisabled(@Observes ProcessAnnotatedType<DbReadinessCheck> pat) {
        if (!DbReadinessCheck.enabled()) {
            pat.veto();
        }
    }
}
