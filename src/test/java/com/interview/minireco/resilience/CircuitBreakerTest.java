package com.interview.minireco.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {
    @Test
    void shouldOpenRecoverWithHalfOpenProbeAndClose() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker breaker = new CircuitBreaker(2, 1_000, clock::get);

        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure();
        assertEquals(CircuitState.CLOSED, breaker.getState());

        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure();
        assertEquals(CircuitState.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission());

        clock.set(1_000);
        assertTrue(breaker.tryAcquirePermission());
        assertEquals(CircuitState.HALF_OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission());

        breaker.recordSuccess();
        assertEquals(CircuitState.CLOSED, breaker.getState());
        assertTrue(breaker.tryAcquirePermission());
    }
}
