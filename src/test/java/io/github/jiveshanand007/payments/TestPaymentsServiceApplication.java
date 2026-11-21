package io.github.jiveshanand007.payments;

import org.springframework.boot.SpringApplication;

public class TestPaymentsServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymentsServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
