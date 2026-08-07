package com.lifeos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.shell.command.annotation.CommandScan;

@SpringBootApplication
@CommandScan
public class LifeOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LifeOsApplication.class, args);
    }
}
