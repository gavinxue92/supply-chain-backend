package dev.mattest.supplychain.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LoginRateLimiter {
    private final Map<String, Window> windows = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > 10_000;
        }
    };
    private final int maximumAttempts;
    private final Duration windowDuration;

    public LoginRateLimiter(
            @Value("${app.security.login-rate-limit.max-attempts:10}") int maximumAttempts,
            @Value("${app.security.login-rate-limit.window:PT1M}") Duration windowDuration) {
        if (maximumAttempts < 1 || windowDuration.isNegative() || windowDuration.isZero()) {
            throw new IllegalArgumentException("Login rate-limit configuration must be positive");
        }
        this.maximumAttempts = maximumAttempts;
        this.windowDuration = windowDuration;
    }

    public boolean tryAcquire(String key) {
        Instant now = Instant.now();
        synchronized (windows) {
            Window current = windows.get(key);
            Window window;
            if (current == null || !current.startedAt().plus(windowDuration).isAfter(now)) {
                window = new Window(now, 1);
            } else {
                window = new Window(current.startedAt(), current.attempts() + 1);
            }
            windows.put(key, window);
            return window.attempts() <= maximumAttempts;
        }
    }

    private record Window(Instant startedAt, int attempts) {
    }
}
