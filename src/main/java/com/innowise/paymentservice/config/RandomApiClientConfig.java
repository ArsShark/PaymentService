package com.innowise.paymentservice.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} that calls the random number service.
 *
 * <p>Both timeouts are set: a payment must not hang on a service that does not answer. The
 * service is a public one on the internet, so no credentials or internal headers are sent.
 */
@Configuration
@EnableConfigurationProperties(RandomApiProperties.class)
public class RandomApiClientConfig {

  /**
   * Creates the client of the random number service.
   *
   * <p>HTTP/1.1 is forced: the JDK client would first try to upgrade a plain HTTP connection to
   * HTTP/2, which some servers answer badly (WireMock in the tests is one). Redirects are not
   * followed: the answer is a number, and an answer that points somewhere else is not one.
   *
   * @param properties the address and the timeouts
   * @return a client with the base address and the timeouts set
   */
  @Bean
  public RestClient randomApiRestClient(RandomApiProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(properties.connectTimeout())
      .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return RestClient.builder()
      .baseUrl(properties.baseUri())
      .requestFactory(requestFactory)
      .defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN_VALUE)
      .build();
  }
}
