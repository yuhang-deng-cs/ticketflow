package com.ticketflow.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketflow.auth.model.User;
import com.ticketflow.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Makes {@code POST} requests carrying an {@code Idempotency-Key} header safe to retry.
 *
 * <ul>
 *   <li>First request with a key: processed normally; a final (non-5xx) response is stored for 24h.</li>
 *   <li>Same key while the first is still running: {@code 409 Conflict}.</li>
 *   <li>Same key after it finished: the stored response is replayed with {@code Idempotent-Replayed: true}.
 *       The client gets the same order back instead of a second booking.</li>
 *   <li>Same key with a different body or path: {@code 422 Unprocessable Entity}.</li>
 *   <li>A 5xx response or exception frees the key, so the client can retry.</li>
 * </ul>
 *
 * Keys are scoped per user, so one customer cannot collide with (or read) another's responses.
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final int MAX_KEY_LENGTH = 128;
    private static final Duration LOCK_TTL = Duration.ofMinutes(1);
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !StringUtils.hasText(request.getHeader(IDEMPOTENCY_HEADER));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(IDEMPOTENCY_HEADER).trim();
        if (key.length() > MAX_KEY_LENGTH) {
            writeError(response, HttpStatus.BAD_REQUEST, "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
            return;
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String fingerprint = fingerprint(cachedRequest);
        String scopedKey = currentUserScope() + ":" + key;

        Optional<IdempotencyRecord> existing = store.claim(scopedKey, fingerprint, LOCK_TTL);
        if (existing.isPresent()) {
            handleRepeat(existing.get(), fingerprint, key, response);
            return;
        }

        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(cachedRequest, cachedResponse);
            if (cachedResponse.getStatus() < 500) {
                store.complete(scopedKey, IdempotencyRecord.completed(
                        fingerprint,
                        cachedResponse.getStatus(),
                        cachedResponse.getContentType(),
                        new String(cachedResponse.getContentAsByteArray(), StandardCharsets.UTF_8)), RETENTION);
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(scopedKey);
            }
            cachedResponse.copyBodyToResponse();
        }
    }

    private void handleRepeat(IdempotencyRecord record, String fingerprint, String key,
                              HttpServletResponse response) throws IOException {
        if (!record.fingerprint().equals(fingerprint)) {
            writeError(response, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency-Key was already used for a different request");
            return;
        }
        if (record.state() == IdempotencyRecord.State.IN_PROGRESS) {
            log.info("Rejected concurrent duplicate for Idempotency-Key {}", key);
            writeError(response, HttpStatus.CONFLICT, "A request with this Idempotency-Key is still being processed");
            return;
        }
        log.info("Replaying stored response for Idempotency-Key {}", key);
        response.setStatus(record.status());
        if (record.contentType() != null) {
            response.setContentType(record.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(record.body() == null ? "" : record.body());
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(message)));
    }

    private static String currentUserScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return "user-" + user.getId();
        }
        return "anonymous";
    }

    private static String fingerprint(CachedBodyHttpServletRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.getCachedBody());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
