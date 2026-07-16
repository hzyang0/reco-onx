package com.interview.minireco.service.operator;

import com.interview.minireco.service.context.RecommendContext;

public interface Operator {
    String name();

    void execute(RecommendContext context);
}
