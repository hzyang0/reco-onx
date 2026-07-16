package com.interview.minireco.service.downstream;

import com.interview.minireco.domain.Item;
import com.interview.minireco.service.context.RecommendContext;

import java.util.List;

public interface MixRankService {
    List<Item> rank(List<Item> items, RecommendContext context, int limit);
}
