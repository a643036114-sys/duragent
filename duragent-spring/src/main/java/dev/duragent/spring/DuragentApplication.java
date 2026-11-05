package dev.duragent.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Demo application: an HTTP API in front of the runtime, plus one embedded worker.
 *
 * <pre>
 *   mvn -pl duragent-spring spring-boot:run
 *   curl -X POST localhost:8080/api/runs -H 'content-type: application/json' \
 *        -d '{"workflow":"refund-agent","input":{"ticket":"T-9","amount":500}}'
 * </pre>
 */
@SpringBootApplication
@EnableScheduling
public class DuragentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DuragentApplication.class, args);
    }
}
