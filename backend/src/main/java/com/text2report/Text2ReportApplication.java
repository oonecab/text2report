package com.text2report;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Text2Report Backend Application
 *
 * Natural language to report platform.
 * Converts controlled business questions into safe SQL,
 * executes them through a governed read-only path,
 * and renders results as charts, tables, summaries, and downloadable reports.
 */
@SpringBootApplication
@EnableAsync
public class Text2ReportApplication {

    public static void main(String[] args) {
        SpringApplication.run(Text2ReportApplication.class, args);
    }
}
