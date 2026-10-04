package com.text2report.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * Vector database datasource configuration for pgvector-backed embeddings.
 */
@Configuration
@MapperScan(
        basePackages = "com.text2report.domain.vector.mapper",
        sqlSessionFactoryRef = "vectorSqlSessionFactory"
)
public class VectorDataSourceConfig {

    @Bean(name = "vectorDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.vector")
    public DataSource vectorDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setConnectionInitSql("SET TIME ZONE '" + TimeZoneConfig.BUSINESS_TIME_ZONE + "'");
        return dataSource;
    }

    @Bean(name = "vectorSqlSessionFactory")
    public SqlSessionFactory vectorSqlSessionFactory(
            @Qualifier("vectorDataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/vector/*.xml")
        );
        factoryBean.setTypeAliasesPackage("com.text2report.domain.vector.entity");
        factoryBean.setTypeHandlersPackage("com.text2report.domain.vector.typehandler");
        return factoryBean.getObject();
    }

    @Bean(name = "vectorTransactionManager")
    public DataSourceTransactionManager vectorTransactionManager(
            @Qualifier("vectorDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
