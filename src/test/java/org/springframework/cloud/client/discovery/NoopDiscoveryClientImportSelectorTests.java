package org.springframework.cloud.client.discovery;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.junit.Assert.*;

/**
 * Tests if @EnableDiscoveryClient is NOT used, then NoopDiscoveryClient is created.
 * @author Spencer Gibb
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = NoopDiscoveryClientImportSelectorTests.App.class)
public class NoopDiscoveryClientImportSelectorTests {

	@Autowired
	DiscoveryClient discoveryClient;

	@Test
	public void testDiscoveryClientIsNoop() {
		assertTrue("discoveryClient is wrong instance type", discoveryClient instanceof NoopDiscoveryClient);
	}

	@EnableAutoConfiguration
	@Configuration
	public static class App {
		public static void main(String[] args) {
			SpringApplication.run(App.class, args);
		}
	}
}