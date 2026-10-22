package com.example.probe.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.lookup.JndiDataSourceLookup;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;

@Configuration
public class AppConfig {

    /** 監視対象データソースの JNDI 名 */
    static final String DS_JNDI = "java:jboss/datasources/OracleDS";
    /** EAP 既定のマネージドスレッドプール */
    static final String EXECUTOR_JNDI = "java:comp/DefaultManagedExecutorService";

    /** EAP のデータソースを JNDI で取得 */
    @Bean
    public DataSource dataSource() {
        return new JndiDataSourceLookup().getDataSource(DS_JNDI);
    }

    /**
     * EAP の ManagedExecutorService。コンテナ管理なので shutdown しない
     * （Spring の destroy 推論を空文字で抑止する）。
     */
    @Bean(destroyMethod = "")
    public ExecutorService healthExecutor() throws NamingException {
        return InitialContext.doLookup(EXECUTOR_JNDI);
    }
}
