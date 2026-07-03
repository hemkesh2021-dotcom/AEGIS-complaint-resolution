package com.aegis.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AEGIS v2 orchestrator entry point.
 *
 * <p>This service chains the six complaint-resolution phases: ingest, classify
 * (via the Python model service), compliance, RAG-grounded draft, escalation, and
 * audit. Only the classifier is a learned model; the rest is explainable logic.
 */
@SpringBootApplication
@EnableScheduling
public class AegisApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AegisApiApplication.class, args);
    }
}
