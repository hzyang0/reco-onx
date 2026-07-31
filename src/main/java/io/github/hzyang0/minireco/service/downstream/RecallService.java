package io.github.hzyang0.minireco.service.downstream;

import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.service.context.RecommendContext;

import java.util.List;

public interface RecallService {
    String source();

    List<Item> recall(RecommendContext context);
}
