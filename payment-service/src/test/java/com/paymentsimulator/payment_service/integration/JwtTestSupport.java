package com.paymentsimulator.payment_service.integration;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Generates a throwaway RSA key pair per test JVM, so no private key is ever committed.
 *
 * The PUBLIC key is written to a temp file and handed to Spring through
 * spring.security.oauth2.resourceserver.jwt.public-key-location, which means the app
 * validates tokens with Spring Boot's real decoder, not a test double.
 */
final class JwtTestSupport {

    static final String AUDIENCE = "payment-service";

    private static final KeyPair TRUSTED_KEYS = generateKeyPair();
    private static final KeyPair UNTRUSTED_KEYS = generateKeyPair();
    private static final Path PUBLIC_KEY_FILE = writePublicKey(TRUSTED_KEYS);

    private JwtTestSupport() {
    }

    /** file: URI of the public key the application under test trusts. */
    static String publicKeyLocation() {
        return PUBLIC_KEY_FILE.toUri().toString();
    }

    static String validToken() {
        return token(AUDIENCE, Instant.now().plusSeconds(3600), TRUSTED_KEYS);
    }

    static String expiredToken() {
        return token(AUDIENCE, Instant.now().minusSeconds(3600), TRUSTED_KEYS);
    }

    static String tokenForAudience(String audience) {
        return token(audience, Instant.now().plusSeconds(3600), TRUSTED_KEYS);
    }

    /** Correct claims, but signed with a key the application does not trust. */
    static String tokenSignedWithUntrustedKey() {
        return token(AUDIENCE, Instant.now().plusSeconds(3600), UNTRUSTED_KEYS);
    }

    private static String token(String audience, Instant expiresAt, KeyPair signingKeys) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("test-user-001")
                    .issuer("payment-simulator-tests")
                    .audience(audience)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(expiresAt))
                    .claim("roles", List.of("USER"))
                    .build();

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) signingKeys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign test token", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePublicKey(KeyPair keys) {
        try {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(keys.getPublic().getEncoded());
            String pem = "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";

            Path file = Files.createTempFile("payment-service-test-public-", ".pem");
            Files.writeString(file, pem, StandardCharsets.US_ASCII);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Could not write test public key", e);
        }
    }
}
