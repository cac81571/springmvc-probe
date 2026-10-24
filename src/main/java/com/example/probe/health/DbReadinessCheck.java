package com.example.probe.health;

import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import javax.naming.InitialContext;
import javax.sql.DataSource;

/** MicroProfile Health の readiness。EAP の {@code /health/ready} に載る */
@Readiness
@ApplicationScoped
public class DbReadinessCheck implements HealthCheck {

    static final String NAME = "db-connection";
    static final String ENV_NAME = "PROBE_DB_CHECK_NAME";
    static final String ENV_ENABLED = "PROBE_DB_CHECK_ENABLED";

    @Resource(lookup = DbConnectionCheck.EXECUTOR_JNDI)
    ManagedExecutorService executor;

    static boolean enabled() {
        return DbConnectionCheck.envFlag(ENV_ENABLED);
    }

    static String name() {
        return DbConnectionCheck.env(ENV_NAME, NAME);
    }

    @Override
    public HealthCheckResponse call() {
        String checkName = name();
        try {
            DataSource dataSource = InitialContext.doLookup(DbConnectionCheck.dsJndi());
            String reason = DbConnectionCheck.downReason(dataSource, executor);
            if (reason == null) {
                return HealthCheckResponse.up(checkName);
            }
            return HealthCheckResponse.named(checkName)
                    .down()
                    .withData("reason", reason)
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.named(checkName)
                    .down()
                    .withData("reason", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
