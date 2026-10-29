package com.familybutler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 三合一家庭 AI 管家入口。
 * 底座包 core.* 与业务包 module.* 同属一个应用，模块间只通过底座接口协作。
 */
@SpringBootApplication
@EnableScheduling
public class ButlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ButlerApplication.class, args);
    }
}
