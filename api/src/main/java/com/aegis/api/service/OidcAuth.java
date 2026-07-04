package com.aegis.api.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

/**
 * OIDC bearer-token validation (Keycloak) — real identities and roles for
 * operators, alongside the API key which remains as the break-glass/dev path.
 *
 * <p>Deliberately NOT the full Spring Security filter chain: the existing
 * AuthGate filter stays in charge of routing decisions; this class only
 * answers "is this JWT valid, and who is it?". Signature keys come from the
 * issuer's JWKS endpoint (fetched lazily and cached by Nimbus), issuer and
 * expiry are validated on every request.
 *
 * <p>Disabled (all bearer tokens rejected) unless both
 * {@code aegis.oidc.issuer-uri} and {@code aegis.oidc.jwk-set-uri} are set.
 */
@Service
public class OidcAuth {

    private static final Logger log = LoggerFactory.getLogger(OidcAuth.class);

    /** An authenticated operator: who they are and what they may do. */
    public record Principal(String username, Set<String> roles) {
    }

    private final String issuer;
    private final String jwkSetUri;
    private volatile JwtDecoder decoder;

    public OidcAuth(@Value("${aegis.oidc.issuer-uri:}") String issuer,
                    @Value("${aegis.oidc.jwk-set-uri:}") String jwkSetUri) {
        this.issuer = issuer == null ? "" : issuer.trim();
        this.jwkSetUri = jwkSetUri == null ? "" : jwkSetUri.trim();
    }

    public boolean enabled() {
        return !issuer.isEmpty() && !jwkSetUri.isEmpty();
    }

    /** @return the validated principal, or null if the token is invalid/OIDC disabled. */
    public Principal authenticate(String bearerToken) {
        if (!enabled() || bearerToken == null || bearerToken.isBlank()) {
            return null;
        }
        try {
            Jwt jwt = decoder().decode(bearerToken);
            Set<String> roles = new HashSet<>();
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> list) {
                for (Object r : list) {
                    roles.add(String.valueOf(r));
                }
            }
            String name = jwt.getClaimAsString("preferred_username");
            return new Principal(name == null || name.isBlank() ? jwt.getSubject() : name, roles);
        } catch (Exception e) {
            log.debug("bearer token rejected: {}", e.getMessage());
            return null;
        }
    }

    private JwtDecoder decoder() {
        JwtDecoder d = decoder;
        if (d == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
                    // validates signature (JWKS), expiry, and the iss claim
                    nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
                    decoder = nimbus;
                }
                d = decoder;
            }
        }
        return d;
    }
}
