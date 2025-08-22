package ar.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "ar.monitor")
@EnableScheduling
public class MonitorApplication {
  public static void main(String[] args) {
    SpringApplication.run(MonitorApplication.class, args);
  }
}
