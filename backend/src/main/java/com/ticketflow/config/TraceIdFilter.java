package com.ticketflow.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a trace id: reuses an incoming {@code X-Trace-Id} if it looks sane, otherwise
 * generates one. The id goes into the logging MDC, the response header and every {@code ApiResponse}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    /** Client-supplied ids end up in logs, so only accept short, plain tokens (no log injection). */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
