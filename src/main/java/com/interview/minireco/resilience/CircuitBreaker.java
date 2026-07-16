package com.interview.minireco.resilience;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public class CircuitBreaker {
    private final int failureThreshold;
    private final long openDurationMs;
    private final LongSupplier currentTimeMs;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    private long openedAtMs;
    private boolean halfOpenTrialInProgress;

    public CircuitBreaker(int failureThreshold, long openDurationMs) {
        this(failureThreshold, openDurationMs, System::currentTimeMillis);
    }

    CircuitBreaker(int failureThreshold, long openDurationMs, LongSupplier currentTimeMs) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        if (openDurationMs <= 0) {
            throw new IllegalArgumentException("openDurationMs must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
        this.currentTimeMs = currentTimeMs;
    }

    public synchronized boolean tryAcquirePermission() {
        if (state == CircuitState.CLOSED) {
            return true;
        }

        if (state == CircuitState.OPEN) {
            if (currentTimeMs.getAsLong() - openedAtMs < openDurationMs) {
                return false;
            }
            state = CircuitState.HALF_OPEN;
            halfOpenTrialInProgress = false;
        }

        if (halfOpenTrialInProgress) {
            return false;
        }
        halfOpenTrialInProgress = true;
        return true;
    }

    public synchronized void recordSuccess() {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        openedAtMs = 0;
        halfOpenTrialInProgress = false;
    }

    public synchronized void recordFailure() {
        if (state == CircuitState.HALF_OPEN) {
            openCircuit();
            return;
        }

        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openCircuit();
        }
    }

    public synchronized void reset() {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        openedAtMs = 0;
        halfOpenTrialInProgress = false;
    }

    public synchronized CircuitState getState() {
        refreshStateForSnapshot();
        return state;
    }

    public synchronized Map<String, Object> snapshot() {
        refreshStateForSnapshot();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", state.name());
        data.put("consecutiveFailures", consecutiveFailures);
        data.put("failureThreshold", failureThreshold);
        data.put("openDurationMs", openDurationMs);
        data.put("openRemainingMs", openRemainingMs());
        return data;
    }

    private void openCircuit() {
        state = CircuitState.OPEN;
        openedAtMs = currentTimeMs.getAsLong();
        halfOpenTrialInProgress = false;
    }

    private void refreshStateForSnapshot() {
        if (state == CircuitState.OPEN && currentTimeMs.getAsLong() - openedAtMs >= openDurationMs) {
            state = CircuitState.HALF_OPEN;
            halfOpenTrialInProgress = false;
        }
    }

    private long openRemainingMs() {
        if (state != CircuitState.OPEN) {
            return 0;
        }
        long elapsed = currentTimeMs.getAsLong() - openedAtMs;
        return Math.max(0, openDurationMs - elapsed);
    }
}
