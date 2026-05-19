package com.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class AssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
        System.out.println("========================================");
        System.out.println("  桌面助手已启动!");
        System.out.println("  访问地址: http://localhost:8081");
        System.out.println("  H2控制台: http://localhost:8081/h2-console");
        System.out.println("========================================");
    }
}
