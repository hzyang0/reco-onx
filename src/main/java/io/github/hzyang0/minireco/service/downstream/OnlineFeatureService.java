package io.github.hzyang0.minireco.service.downstream;

import io.github.hzyang0.minireco.domain.Item;

import java.util.List;

public interface OnlineFeatureService {
    void fillOnlineFeatures(List<Item> items);
}
