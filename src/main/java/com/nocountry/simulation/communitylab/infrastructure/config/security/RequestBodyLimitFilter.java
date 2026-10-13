package com.nocountry.simulation.communitylab.infrastructure.config.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Why a filter: Bean Validation (@Size) runs after Jackson has read the whole body, so without a cap an
// anonymous client could load megabytes into the heap per request (spec 007 RNF-01: PATCH body <= 16KB).
@Component
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 16_384;

    private static final String TOO_LARGE_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,\"detail\":\"request body over 16KB\"}";

    // The PATCH revision routes are the only endpoints that read a body.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.PATCH.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(TOO_LARGE_BODY);
            return;
        }
        // Why also wrap: a chunked body declares no length, so it can only be cut while it is read.
        chain.doFilter(new LimitedBodyRequest(request), response);
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private ServletInputStream limited;

        LimitedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedInputStream(super.getInputStream());
            }
            return limited;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long read;

        LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        // Jackson turns this into an unreadable body (400), after reading at most 16KB.
        private void count(int n) throws IOException {
            read += n;
            if (read > MAX_BODY_BYTES) {
                throw new IOException("request body over 16KB");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
