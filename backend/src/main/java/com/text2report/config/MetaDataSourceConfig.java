package com.text2report.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * Metadata database (read-write) datasource configuration
 * Points to text2report-meta database
 */
@Configuration
@MapperScan(basePackages = "com.text2report.domain.meta.mapper",
        sqlSessionFactoryRef = "metaSqlSessionFactory")
public class MetaDataSourceConfig {

    @Bean(name = "metaDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.meta")
    public DataSource metaDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setConnectionInitSql("SET TIME ZONE '" + TimeZoneConfig.BUSINESS_TIME_ZONE + "'");
        return dataSource;
    }

    @Primary
    @Bean(name = "metaSqlSessionFactory")
    public SqlSessionFactory metaSqlSessionFactory(
            @Qualifier("metaDataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/meta/*.xml")
        );
        factoryBean.setTypeAliasesPackage("com.text2report.domain.meta.entity");
        return factoryBean.getObject();
    }

    @Bean(name = "metaTransactionManager")
    public DataSourceTransactionManager metaTransactionManager(
            @Qualifier("metaDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
