package io.github.hzyang0.minireco.service.downstream;

import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.service.context.RecommendContext;

import java.util.List;

public interface MixRankService {
    List<Item> rank(List<Item> items, RecommendContext context, int limit);
}
