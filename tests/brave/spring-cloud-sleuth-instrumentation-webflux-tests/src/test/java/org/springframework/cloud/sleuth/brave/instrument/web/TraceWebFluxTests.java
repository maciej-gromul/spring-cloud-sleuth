/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.brave.instrument.web;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import brave.Span;
import brave.Tracer;
import brave.handler.SpanHandler;
import brave.sampler.Sampler;
import brave.test.TestSpanHandler;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import static org.assertj.core.api.BDDAssertions.then;

public class TraceWebFluxTests {

	public static final String EXPECTED_TRACE_ID = "b919095138aa4c6e";

	@Test
	public void should_instrument_web_filter() throws Exception {
		// setup
		ConfigurableApplicationContext context = new SpringApplicationBuilder(TraceWebFluxTests.Config.class)
				.web(WebApplicationType.REACTIVE)
				.properties("server.port=0", "spring.jmx.enabled=false", "spring.sleuth.web.skipPattern=/skipped",
						"spring.application.name=TraceWebFluxTests", "security.basic.enabled=false",
						"management.security.enabled=false")
				.run();
		TestSpanHandler spans = context.getBean(TestSpanHandler.class);
		AssertingWebFilter assertingWebFilter = context.getBean(AssertingWebFilter.class);
		int port = context.getBean(Environment.class).getProperty("local.server.port", Integer.class);
		Controller2 controller2 = context.getBean(Controller2.class);
		clean(spans, controller2);

		// when
		ClientResponse response = whenRequestIsSent(port, "/api/c2/10");
		// then
		thenSpanWasReportedWithTags(spans, response);
		// then #2002
		then(response.headers().header("mytraceid")).isNotEmpty();
		clean(spans, controller2);

		// when
		response = whenRequestIsSent(port, "/api/fn/20");
		// then
		thenFunctionalSpanWasReportedWithTags(spans, response);
		spans.clear();

		// when
		response = whenRequestIsSent(port, "/missing-endpoint");
		// then
		thenSpanWith404StatusCodeWasReported(spans, response);
		spans.clear();

		// when
		response = whenRequestIsSent(port, "/exception");
		// then
		thenSpanWithExceptionWasReported(spans, response);
		spans.clear();

		// when
		ClientResponse nonSampledResponse = whenNonSampledRequestIsSent(port);
		// then
		thenNoSpanWasReported(spans, nonSampledResponse, controller2);
		spans.clear();

		// when
		ClientResponse skippedPatternResponse = whenRequestIsSentToSkippedPattern(port);
		// then
		thenNoSpanWasReported(spans, skippedPatternResponse, controller2);

		// when (issue #1683)
		response = whenRequestWithXForwardedForIsSent(port, "/api/fn/20");
		// then
		thenSpanWasReportedWithRemoteIpTags(spans, response);

		thenNoTraceWasLeaked(assertingWebFilter);

		// cleanup
		context.close();
	}

	private void clean(TestSpanHandler spans, Controller2 controller2) {
		spans.clear();
		controller2.span = null;
	}

	private void thenSpanWasReportedWithTags(TestSpanHandler spans, ClientResponse response) {
		Awaitility.await().untilAsserted(() -> then(response.statusCode().value()).isEqualTo(200));
		then(spans).hasSize(1);
		then(spans.get(0).name()).isEqualTo("GET /api/c2/{id}");
		then(spans.get(0).tags()).containsEntry("mvc.controller.method", "successful")
				.containsEntry("mvc.controller.class", "Controller2");
		then(spans.get(0).remoteIp()).isEqualTo("127.0.0.1");
	}

	private void thenSpanWasReportedWithRemoteIpTags(TestSpanHandler spans, ClientResponse response) {
		Awaitility.await().untilAsserted(() -> then(response.statusCode().value()).isEqualTo(200));
		then(spans).hasSize(1);
		then(spans.get(0).remoteIp()).isEqualTo("203.0.113.195");
	}

	private void thenFunctionalSpanWasReportedWithTags(TestSpanHandler spans, ClientResponse response) {
		Awaitility.await().untilAsserted(() -> then(response.statusCode().value()).isEqualTo(200));
		then(spans).hasSize(1);
		then(spans.get(0).name()).isEqualTo("GET /api/fn/{id}");
		then(spans.get(0).tags()).hasEntrySatisfying("mvc.controller.class",
				value -> then(value).startsWith("TraceWebFluxTests$Config$$Lambda$"));
	}

	private void thenSpanWith404StatusCodeWasReported(TestSpanHandler spans, ClientResponse response) {
		Awaitility.await().untilAsserted(() -> then(response.statusCode().value()).isEqualTo(404));
		then(spans).hasSize(1);
		then(spans.get(0).tags()).hasEntrySatisfying("http.status_code", value -> then(value).isEqualTo("404"));
	}

	private void thenSpanWithExceptionWasReported(TestSpanHandler spans, ClientResponse response) {
		Awaitility.await().untilAsserted(() -> then(response.statusCode().value()).isEqualTo(500));
		then(spans).hasSize(1);
		then(spans.get(0).tags()).hasEntrySatisfying("http.status_code", value -> then(value).isEqualTo("500"));
	}

	private void thenNoTraceWasLeaked(AssertingWebFilter assertingWebFilter) {
		then(assertingWebFilter.getSpans()).isEmpty();
	}

	private void thenNoSpanWasReported(TestSpanHandler spans, ClientResponse response, Controller2 controller2) {
		Awaitility.await().untilAsserted(() -> {
			then(response.statusCode().value()).isEqualTo(200);
			then(spans).isEmpty();
		});
		then(controller2.span).isNotNull();
		then(controller2.span.context().traceIdString()).isEqualTo(EXPECTED_TRACE_ID);
	}

	private ClientResponse whenRequestIsSent(int port, String path) {
		Mono<ClientResponse> exchange = WebClient.create().get().uri("http://localhost:" + port + path).exchange();
		return exchange.block();
	}

	private ClientResponse whenRequestWithXForwardedForIsSent(int port, String path) {
		Mono<ClientResponse> exchange = WebClient.create().get().uri("http://localhost:" + port + path)
				.header("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178").exchange();
		return exchange.block();
	}

	private ClientResponse whenRequestIsSentToSkippedPattern(int port) {
		Mono<ClientResponse> exchange = WebClient.create().get().uri("http://localhost:" + port + "/skipped")
				.exchange();
		return exchange.block();
	}

	private ClientResponse whenNonSampledRequestIsSent(int port) {
		Mono<ClientResponse> exchange = WebClient.create().get().uri("http://localhost:" + port + "/api/c2/10")
				.header("b3", EXPECTED_TRACE_ID + "-" + EXPECTED_TRACE_ID + "-0").exchange();
		return exchange.block();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class Config {

		private static final Logger log = LoggerFactory.getLogger(Config.class);

		@Bean
		WebClient webClient() {
			return WebClient.create();
		}

		@Bean
		Sampler sampler() {
			return Sampler.ALWAYS_SAMPLE;
		}

		@Bean
		SpanHandler testSpanHandler() {
			return new TestSpanHandler();
		}

		@Bean
		Controller2 controller2(Tracer tracer) {
			return new Controller2(tracer);
		}

		@Bean
		RouterFunction<ServerResponse> route() {
			return RouterFunctions.route().GET("/api/fn/{id}",
					serverRequest -> ServerResponse.ok().bodyValue(serverRequest.pathVariable("id"))).build();
		}

		@Bean
		WebFilter traceIdInResponseFilter(Tracer tracer) {
			return (exchange, chain) -> {
				Span currentSpan = tracer.currentSpan();
				if (currentSpan != null) {
					exchange.getResponse().getHeaders().add("mytraceid", currentSpan.context().traceIdString());
				}
				return chain.filter(exchange);
			};
		}

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		AssertingWebFilter traceIdInResponseLastFilter(Tracer tracer) {
			return new AssertingWebFilter(tracer);
		}

	}

	static class AssertingWebFilter implements WebFilter {

		Queue<Span> spans = new ConcurrentLinkedQueue<>();

		private final Tracer tracer;

		AssertingWebFilter(Tracer tracer) {
			this.tracer = tracer;
		}

		@Override
		public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
			return chain.filter(exchange).doFinally(__ -> {
				Span currentSpan = tracer.currentSpan();
				if (currentSpan != null) {
					spans.add(currentSpan);
				}
			});
		}

		Queue<Span> getSpans() {
			return spans;
		}

	}

	@RestController
	static class Controller2 {

		private final Tracer tracer;

		Span span;

		Controller2(Tracer tracer) {
			this.tracer = tracer;
		}

		@GetMapping("/api/c2/{id}")
		public Flux<String> successful(@PathVariable Long id) {
			// #786
			then(MDC.get("traceId")).isNotEmpty();
			this.span = this.tracer.currentSpan();
			return Flux.just(id.toString());
		}

		@GetMapping("/skipped")
		public Flux<String> skipped() {
			Boolean sampled = this.tracer.currentSpan().context().sampled();
			then(sampled).isFalse();
			return Flux.just(sampled.toString());
		}

		@GetMapping("/exception")
		public Flux<Void> exception() {
			throw new RuntimeException("Exception");
		}

	}

}
