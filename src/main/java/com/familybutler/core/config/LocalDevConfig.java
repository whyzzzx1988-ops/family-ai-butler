package com.familybutler.core.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import jakarta.annotation.PreDestroy;
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
* 同一个 H2 内存库（jdbc:h2:tcp://localhost:9092/mem:butler）。
* 注意：内存库只活在应用 JVM 里，应用没启动时连不上，这是预期行为。
* 生产/默认 Profile 不受影响，仍走 redis + Postgres。
*/
@Configuration
@Profile("local")
public class LocalDevConfig {

private static final Logger log = LoggerFactory.getLogger(LocalDevConfig.class);

private Server h2TcpServer;

@Bean
@Primary
public SaTokenDao localSaTokenDao() {
return new SaTokenDaoDefaultImpl();
}

/**
* H2 TCP 服务的都必须等到全部单例 Bean 初始化完成之后：
* 只要在上下文初始化期间构造出 Server 对象，H2 就会把本 JVM 内对
* jdbc:h2:mem:butler 的连接重定向到 localhost:9092 走远程会话，而那时
* 内存库尚未创建——服务已启动时报 90149（拒绝远程建库），未启动时报
* 90067（连接被拒），两种情况 Flyway 都拿不到连接、应用直接起不来。
* 等到这里回调时，数据源与 Flyway 迁移均已完成、内存库已存在，
* 之后应用内连接与 IDEA 的 TCP 连接打开的都是这个现成的库。
*/
@Bean
public SmartInitializingSingleton h2TcpServerStarter() {
return () -> {
try {
h2TcpServer = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", "9092");
h2TcpServer.start();
log.info("H2 TCP 服务已启动，IDEA 可用 jdbc:h2:tcp://localhost:9092/mem:butler 直连（sa / 空密码）");
} catch (SQLException e) {
throw new IllegalStateException("H2 TCP 服务启动失败", e);
}
};
}

@PreDestroy
public void stopH2TcpServer() {
if (h2TcpServer!= null) {
h2TcpServer.stop();
}
}
}
