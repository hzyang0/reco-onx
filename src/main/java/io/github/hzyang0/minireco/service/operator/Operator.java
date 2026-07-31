package io.github.hzyang0.minireco.service.operator;

import io.github.hzyang0.minireco.service.context.RecommendContext;

public interface Operator {
    String name();

    void execute(RecommendContext context);
}
