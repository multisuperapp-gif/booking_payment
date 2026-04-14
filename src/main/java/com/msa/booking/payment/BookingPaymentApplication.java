package com.msa.booking.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BookingPaymentApplication {

	public static void main(String[] args) {
		SpringApplication.run(BookingPaymentApplication.class, args);
	}

}
