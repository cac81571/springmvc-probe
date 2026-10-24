package com.example.probe.config;

import com.example.probe.health.DbConnectionCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.lookup.JndiDataSourceLookup;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;

@Configuration
public class AppConfig {

    /** EAP のデータソースを JNDI で取得 */
    @Bean
    public DataSource dataSource() {
        return new JndiDataSourceLookup().getDataSource(DbConnectionCheck.dsJndi());
    }

    /**
     * EAP の ManagedExecutorService。コンテナ管理なので shutdown しない
     * （Spring の destroy 推論を空文字で抑止する）。
     */
    @Bean(destroyMethod = "")
    public ExecutorService healthExecutor() throws NamingException {
        return InitialContext.doLookup(DbConnectionCheck.EXECUTOR_JNDI);
    }
}
