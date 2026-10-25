package com.library;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 图书借阅管理系统 启动类
 */
@SpringBootApplication
@EnableScheduling // 预约过期回收等定时任务
@MapperScan("com.library.mapper") // 扫描 Mapper 接口所在包
public class LibraryApplication {

    public static void main(String[] args) {
        SpringApplication.run(LibraryApplication.class, args);
    }
}
