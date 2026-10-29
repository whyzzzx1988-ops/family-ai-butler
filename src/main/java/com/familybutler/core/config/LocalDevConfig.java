package com.familybutler.core.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
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

    private static final Logger log = LoggerFactory.getLogger(LocalDevConfig.class);

    @Bean
    @Primary
    public SaTokenDao localSaTokenDao() {
        return new SaTokenDaoDefaultImpl();
    }

    @Bean(destroyMethod = "stop")
    public Server h2TcpServer() throws SQLException {
        return Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", "9092");
    }

    /**
     * H2 TCP 服务必须等全部单例 Bean 初始化完成后再启动，不能跟随 Bean 创建直接 start：
     * 若 TCP 服务先于数据源启动，应用自己连 jdbc:h2:mem:butler 时会被 H2 路由成
     * 远程连接，而此时内存库还没被创建，服务端拒绝远程建库（错误 90149），
     * Flyway 初始化直接失败、应用起不来。等到这里回调时 Flyway 已建库并迁移完毕，
     * 内存库已存在，应用内连接和 IDEA 的 TCP 连接打开的都是现成的库。
     */
    @Bean
    public SmartInitializingSingleton h2TcpServerStarter(Server h2TcpServer) {
        return () -> {
            h2TcpServer.start();
            log.info("H2 TCP 服务已启动，IDEA 可用 jdbc:h2:tcp://localhost:9092/mem:butler 直连（sa / 空密码）");
        };
    }
}
