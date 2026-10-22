package vn.flashsale.common;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/demo/faults")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class DemoFaultController {
    private final DemoFaults faults;
    @GetMapping public DemoFaults.Settings get() { return faults.get(); }
    @PostMapping public DemoFaults.Settings set(@RequestBody DemoFaults.Settings settings) { faults.configure(settings); return faults.get(); }
}
