package ar.monitor.web;

import ar.monitor.service.BoncerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BoncerController {

  private final BoncerService service;

  public BoncerController(BoncerService service) {
    this.service = service;
  }

  @GetMapping("/api/boncer")
  public BoncerService.BoncerResponse boncer() {
    return service.load();
  }
}
