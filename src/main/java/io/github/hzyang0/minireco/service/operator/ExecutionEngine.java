package io.github.hzyang0.minireco.service.operator;

import io.github.hzyang0.minireco.service.context.RecommendContext;

public interface ExecutionEngine extends AutoCloseable {
    void execute(RecommendContext context);

    @Override
    default void close() {
    }
}
