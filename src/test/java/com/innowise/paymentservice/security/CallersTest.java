package com.innowise.paymentservice.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.service.Caller;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Tests of {@link Callers}: the caller comes from the verified token and nowhere else. */
class CallersTest {

  private static JwtAuthenticationToken token(String role, Consumer<Jwt.Builder> claims) {
    Jwt.Builder builder = Jwt.withTokenValue("token")
      .header("alg", "RS256")
      .issuedAt(Instant.parse("2026-11-19T10:00:00Z"))
      .expiresAt(Instant.parse("2026-11-19T10:15:00Z"))
      .subject("2");
    claims.accept(builder);
    return new JwtAuthenticationToken(builder.build(),
      List.of(new SimpleGrantedAuthority(String.format("ROLE_%s", role))));
  }

  private static JwtAuthenticationToken user() {
    return token("USER", jwt -> jwt.claim("user_id", 7L));
  }

  @Test
  void aUserTokenGivesTheProfileAndNoAdminRight() {
    Caller caller = Callers.from(user());

    assertAll(
      () -> assertEquals(7L, caller.userId()),
      () -> assertFalse(caller.admin()));
  }

  @Test
  void anAdminTokenIsAnAdminWithOrWithoutAProfile() {
    Caller withoutProfile = Callers.from(token("ADMIN", jwt -> jwt.claim("role", "ADMIN")));
    Caller withProfile = Callers.from(token("ADMIN", jwt -> jwt.claim("user_id", 1L)));

    assertAll(
      () -> assertTrue(withoutProfile.admin()),
      () -> assertNull(withoutProfile.userId()),
      () -> assertTrue(withProfile.admin()),
      () -> assertEquals(1L, withProfile.userId()));
  }

  /** The id comes as a JSON number; the token of an int-sized id is an Integer after parsing. */
  @Test
  void aNumberOfAnyKindIsAUserId() {
    Caller caller = Callers.from(token("USER", jwt -> jwt.claim("user_id", 7)));

    assertEquals(7L, caller.userId());
  }

  @Test
  void aUserIdThatIsNotANumberCountsAsNoProfile() {
    JwtAuthenticationToken text = token("USER", jwt -> jwt.claim("user_id", "7"));

    assertAll(
      () -> assertNull(Callers.from(text).userId()),
      () -> assertThrows(AccessDeniedException.class, () -> Callers.owner(text)));
  }

  @Test
  void ownerReturnsTheCallerOfATokenWithAProfile() {
    assertEquals(new Caller(7L, false), Callers.owner(user()));
  }

  @Test
  void ownerRefusesATokenWithoutAProfileWhateverTheRole() {
    JwtAuthenticationToken admin = token("ADMIN", jwt -> jwt.claim("email", "admin@example.com"));
    JwtAuthenticationToken regularUser = token("USER", jwt -> jwt.claim("role", "USER"));

    assertAll(
      () -> assertThrows(AccessDeniedException.class, () -> Callers.owner(admin)),
      () -> assertThrows(AccessDeniedException.class, () -> Callers.owner(regularUser)));
  }

  /** The role of a token is its authority, and only the exact ROLE_ADMIN makes an admin. */
  @Test
  void onlyTheAuthorityRoleAdminMakesAnAdmin() {
    JwtAuthenticationToken claimOnly = new JwtAuthenticationToken(
      Jwt.withTokenValue("token").header("alg", "RS256").claim("role", "ADMIN")
        .claim("user_id", 7L).build(),
      List.of(new SimpleGrantedAuthority("ROLE_USER")));

    assertFalse(Callers.from(claimOnly).admin());
  }

  /** Anything but a verified token is refused, so a configuration mistake closes the door. */
  @Test
  void anAuthenticationThatIsNotAVerifiedTokenIsRefused() {
    UsernamePasswordAuthenticationToken mock = UsernamePasswordAuthenticationToken.authenticated(
      "user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertAll(
      () -> assertThrows(AccessDeniedException.class, () -> Callers.from(mock)),
      () -> assertThrows(AccessDeniedException.class, () -> Callers.owner(mock)),
      () -> assertThrows(AccessDeniedException.class, () -> Callers.from(null)));
  }
}
