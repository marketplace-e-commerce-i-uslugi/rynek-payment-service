package pl.rynek.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
