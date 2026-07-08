package com.interview.minireco.service.downstream;

import com.interview.minireco.domain.Item;

import java.util.List;
import java.util.Map;

public interface MixRankService {
    List<Item> rank(List<Item> items, Map<String, Object> context, int limit);
}
