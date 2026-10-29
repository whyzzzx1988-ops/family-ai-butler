package com.familybutler.core.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.h2.tools.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.sql.SQLException;

/**
 * 仅 local Profile 生效：
 * 1. 把 Sa-Token 的 token 存储从 redis 换成内存实现，这样本地调试不用起 Redis。
 * 2. 起一个 H2 TCP 服务（端口 9092），让 IDEA 数据库面板能直连应用进程内的
 *    同一个 H2 内存库（jdbc:h2:tcp://localhost:9092/mem:butler）。
 *    注意：内存库只活在应用 JVM 里，应用没启动时连不上，这是预期行为。
 * 生产/默认 Profile 不受影响，仍走 redis + Postgres。
 */
@Configuration
@Profile("local")
public class LocalDevConfig {

    @Bean
    @Primary
    public SaTokenDao localSaTokenDao() {
        return new SaTokenDaoDefaultImpl();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public Server h2TcpServer() throws SQLException {
        return Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", "9092");
    }
}
