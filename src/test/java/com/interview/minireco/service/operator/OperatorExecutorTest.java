package com.interview.minireco.service.operator;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.service.context.RecommendContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OperatorExecutorTest {
    @Test
    void disabledOperatorShouldBeSkippedAndReported() {
        AtomicBoolean called = new AtomicBoolean(false);
        Operator demoOperator = new Operator() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public void execute(RecommendContext context) {
                called.set(true);
            }
        };

        OperatorExecutor executor = new OperatorExecutor(
                List.of(demoOperator),
                List.of(OperatorConfig.disabled("demo"))
        );
        RecommendContext context = new RecommendContext("request-1", new RecommendRequest(1L, "mall", 1));

        executor.execute(context);

        assertFalse(called.get());
        assertEquals(0L, context.getStageCostMs().get("demo"));
        Map<String, Object> debug = context.buildDebugSnapshot();
        assertEquals(true, debug.get("demoSkipped"));
    }
}
