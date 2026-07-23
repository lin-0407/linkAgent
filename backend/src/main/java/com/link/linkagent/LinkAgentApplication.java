package com.link.linkagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LinkAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkAgentApplication.class, args);
    }
}
