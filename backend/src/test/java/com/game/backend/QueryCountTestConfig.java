package com.game.backend;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

@TestConfiguration
public class QueryCountTestConfig {

    @Bean
    public DataSourceQueryCounter dataSourceQueryCounter() {
        return new DataSourceQueryCounter();
    }

    @Bean
    public BeanPostProcessor dataSourceQueryCounterPostProcessor(DataSourceQueryCounter counter) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource
                        && beanName.equals("dataSource")
                        && !Proxy.isProxyClass(bean.getClass())) {
                    return counter.wrap((DataSource) bean);
                }
                return bean;
            }
        };
    }
}
