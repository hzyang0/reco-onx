package com.interview.minireco.service.downstream;

import com.interview.minireco.domain.Item;

import java.util.List;

public interface OnlineFeatureService {
    void fillOnlineFeatures(List<Item> items);
}
