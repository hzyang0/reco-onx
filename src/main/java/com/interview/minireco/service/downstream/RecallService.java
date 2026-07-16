package com.interview.minireco.service.downstream;

import com.interview.minireco.domain.Item;
import com.interview.minireco.service.context.RecommendContext;

import java.util.List;

public interface RecallService {
    String source();

    List<Item> recall(RecommendContext context);
}
