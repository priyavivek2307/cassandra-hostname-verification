/*
 * Copyright 2015-2024 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.server.internal.eureka;

import com.jayway.jsonpath.JsonPath;
import com.linecorp.armeria.server.Server;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin.server.ZipkinServer;

import static okhttp3.Credentials.basic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.utility.DockerImageName.parse;

/**
 * Integration test for Eureka, which validates with <a href="https://github.com/Netflix/eureka/wiki/Eureka-REST-operations">Eureka REST operations</a>
 *
 * <p>Note: We only validate that authentication works, as it is cheaper than also testing without
 * it.
 */
@SpringBootTest(
  classes = ZipkinServer.class,
  webEnvironment = SpringBootTest.WebEnvironment.NONE, // RANDOM_PORT requires spring-web
  properties = {
    "server.port=0",
    "spring.config.name=zipkin-server",
    "spring.main.banner-mode=off",
    "zipkin.storage.type=", // cheat and test empty storage type
    "zipkin.collector.http.enabled=false",
    "zipkin.query.enabled=false",
    "zipkin.ui.enabled=false"
  })
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class) // so that deregistration tests don't flake the others.
abstract class BaseITZipkinEureka {
  /**
   * Path under the Eureka v2 endpoint for the app named "zipkin".
   * Note that Eureka always coerces app names to uppercase.
   */
  private static final String APPS_ZIPKIN = "apps/ZIPKIN";
  private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(false).build();

  @Autowired Server zipkin;

  final HttpUrl serviceUrl;

  BaseITZipkinEureka(HttpUrl serviceUrl) {
    this.serviceUrl = serviceUrl;
  }

  @BeforeEach void awaitRegistration() {
    // The zipkin server may start before Eureka processes the registration
    await().until(this::getEurekaZipkinAppAsString, (s) -> true);
  }

  @Test @Order(1) void registersInEureka() throws IOException {
    String json = getEurekaZipkinAppAsString();

    // Make sure the health status is OK
    assertThat(readString(json, "$.application.instance[0].status"))
      .isEqualTo("UP");

    String zipkinHostname = zipkin.defaultHostname();
    int zipkinPort = zipkin.activePort().localAddress().getPort();

    // Note: Netflix/Eureka says use hostname, which can conflict on laptops.
    // Armeria adopts the spring-cloud-netflix convention shown here.
    assertThat(readString(json, "$.application.instance[0].instanceId"))
      .isEqualTo(zipkinHostname + ":zipkin:" + zipkinPort);

    // Make sure the vip address is relevant
    assertThat(readString(json, "$.application.instance[0].vipAddress"))
      .isEqualTo(zipkinHostname + ":" + zipkinPort);
  }

  @Test @Order(2) void deregistersOnClose() {
    zipkin.close();
    await().untilAsserted( // wait for deregistration
      () -> {
        try (Response response = getEurekaZipkinApp()) {
          assertThat(response.code()).isEqualTo(404);
        }
      });
  }

  private String getEurekaZipkinAppAsString() throws IOException {
    try (Response response = getEurekaZipkinApp(); ResponseBody body = response.body()) {
      assertThat(response.isSuccessful()).withFailMessage(response.toString()).isTrue();
      return body != null ? body.string() : "";
    }
  }

  private Response getEurekaZipkinApp() throws IOException {
    HttpUrl url = serviceUrl.newBuilder().addEncodedPathSegments(APPS_ZIPKIN).build();
    Request.Builder rb = new Request.Builder().url(url)
      .header("Accept", "application/json"); // XML is default
    if (!url.username().isEmpty()) {
      rb.header("Authorization", basic(url.username(), url.password()));
    }
    return client.newCall(rb.build()).execute();
  }

  static final class EurekaContainer extends GenericContainer<EurekaContainer> {
    static final Logger LOGGER = LoggerFactory.getLogger(EurekaContainer.class);
    static final int EUREKA_PORT = 8761;

    EurekaContainer(Map<String, String> env) {
      super(parse("ghcr.io/openzipkin/zipkin-eureka:3.0.4"));
      withEnv(env);
      withExposedPorts(EUREKA_PORT);
      waitStrategy = Wait.forHealthcheck();
      withStartupTimeout(Duration.ofSeconds(60));
      withLogConsumer(new Slf4jLogConsumer(LOGGER));
    }

    HttpUrl serviceUrl() {
      return new HttpUrl.Builder()
        .scheme("http")
        .host(getHost()).port(getMappedPort(EUREKA_PORT))
        .addEncodedPathSegments("eureka/v2").build();
    }
  }

  static String readString(String json, String jsonPath) {
    return JsonPath.compile(jsonPath).read(json);
  }
}
