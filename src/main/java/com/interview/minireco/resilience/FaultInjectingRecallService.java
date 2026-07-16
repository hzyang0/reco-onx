package com.interview.minireco.resilience;

import com.interview.minireco.domain.Item;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.util.SimulatedLatency;

import java.util.List;

public class FaultInjectingRecallService implements RecallService {
    private final RecallService delegate;
    private final FaultInjectionManager faultInjectionManager;

    public FaultInjectingRecallService(RecallService delegate, FaultInjectionManager faultInjectionManager) {
        this.delegate = delegate;
        this.faultInjectionManager = faultInjectionManager;
    }

    @Override
    public String source() {
        return delegate.source();
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        FaultMode mode = faultInjectionManager.get(source());
        if (mode == FaultMode.ERROR) {
            throw new IllegalStateException("injected error for " + source() + " recall");
        }
        if (mode == FaultMode.TIMEOUT) {
            SimulatedLatency.sleepMs(1_000);
            throw new IllegalStateException("timeout fault was not interrupted");
        }
        return delegate.recall(context);
    }
}
