package com.interview.minireco.service.operator;

import com.interview.minireco.service.context.RecommendContext;

public interface ExecutionEngine {
    void execute(RecommendContext context);
}
