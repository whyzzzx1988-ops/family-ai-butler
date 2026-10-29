package com.familybutler.core.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 仅 local Profile 生效：把 Sa-Token 的 token 存储从 redis 换成内存实现，
 * 这样本地调试不用起 Redis。生产/默认 Profile 不受影响，仍走 redis。
 */
@Configuration
@Profile("local")
public class LocalDevConfig {

    @Bean
    @Primary
    public SaTokenDao localSaTokenDao() {
        return new SaTokenDaoDefaultImpl();
    }
}
