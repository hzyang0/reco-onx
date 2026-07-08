package com.interview.minireco.service.downstream;

import com.interview.minireco.domain.Item;

import java.util.List;
import java.util.Map;

public interface RecallService {
    String source();

    List<Item> recall(Map<String, Object> context);
}
