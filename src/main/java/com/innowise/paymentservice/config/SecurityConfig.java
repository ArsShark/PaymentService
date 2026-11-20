package com.innowise.paymentservice.config;

import com.innowise.paymentservice.security.ProblemDetailSecurityHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security setup of the Payment Service: a stateless resource server that is closed by
 * default.
 *
 * <p>The service is an API for other programs, so there are no sessions, no CSRF protection (it
 * protects cookie sessions, and there are none: the token is sent in the {@code Authorization}
 * header and a browser never adds it to a request to a foreign site on its own), no login form,
 * no HTTP Basic and no logout page. Every request that no rule opens is denied, so an unknown
 * path is answered by Security and never reaches Spring MVC: an anonymous caller gets 401, an
 * authenticated one 403.
 *
 * <p>Callers bring an access token of the Authentication Service in the header
 * {@code Authorization: Bearer ...}. It is verified here, with the public key of that service
 * (see {@link JwtConfig}), and the claim {@code role} becomes the authority. Both answers of the
 * chain, 401 and 403, are written as {@code ProblemDetail} by
 * {@link ProblemDetailSecurityHandler}.
 *
 * <p>The rules are the coarse ones: they decide by role and by HTTP method which kind of caller
 * may call an endpoint at all. The controller repeats the role with {@code @PreAuthorize}
 * (method security, {@link EnableMethodSecurity}), so a changed URL rule cannot open a method by
 * accident. Whose payment it is, is decided by the payment service from the caller built out of
 * the token, not here.
 *
 * <p>The ERROR dispatch is permitted: when the servlet container forwards a failed request to
 * the error page, that internal forward must not be denied again, otherwise the client would
 * see 401 or 403 instead of the real status. A direct request to {@code /error} is a normal
 * REQUEST dispatch and stays closed.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private static final String ADMIN = "ADMIN";
  private static final String USER = "USER";

  /**
   * Builds the one security filter chain of the service.
   *
   * @param http the builder provided by Spring Security
   * @param jwtDecoder verifies the access tokens
   * @param jwtConverter turns a verified token into an authentication with its role
   * @param resolver the exception resolver of Spring MVC; the name is needed because Spring Boot
   *   has more than one bean of this type
   * @return the chain that handles every request
   * @throws Exception if Spring Security cannot build the chain
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http,
    JwtDecoder jwtDecoder,
    JwtAuthenticationConverter jwtConverter,
    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
    throws Exception {
    ProblemDetailSecurityHandler errorHandler = new ProblemDetailSecurityHandler(resolver);
    return http
      .csrf(AbstractHttpConfigurer::disable)
      .formLogin(AbstractHttpConfigurer::disable)
      .httpBasic(AbstractHttpConfigurer::disable)
      .logout(AbstractHttpConfigurer::disable)
      .sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .exceptionHandling(handling -> handling
        .authenticationEntryPoint(errorHandler)
        .accessDeniedHandler(errorHandler))
      .oauth2ResourceServer(resourceServer -> resourceServer
        .authenticationEntryPoint(errorHandler)
        .accessDeniedHandler(errorHandler)
        .jwt(jwt -> jwt
          .decoder(jwtDecoder)
          .jwtAuthenticationConverter(jwtConverter)))
      .authorizeHttpRequests(requests -> requests
        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
        // The list and the total of everybody's payments are for an admin. These rules must
        // stay before the one for "/api/payments/*", which would match "/total" as an id.
        .requestMatchers(HttpMethod.GET, "/api/payments", "/api/payments/total")
        .hasRole(ADMIN)
        .requestMatchers(HttpMethod.POST, "/api/payments").hasAnyRole(ADMIN, USER)
        // The rest is open to both roles; the service decides whose payment it is (a payment
        // of somebody else is a 404). A payment never changes: no other method is opened.
        .requestMatchers(HttpMethod.GET, "/api/payments/my", "/api/payments/my/total")
        .hasAnyRole(ADMIN, USER)
        .requestMatchers(HttpMethod.GET, "/api/payments/*").hasAnyRole(ADMIN, USER)
        .anyRequest().denyAll())
      .build();
  }
}
