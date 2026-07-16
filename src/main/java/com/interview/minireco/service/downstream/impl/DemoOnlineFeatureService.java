package com.interview.minireco.service.downstream.impl;

import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.service.downstream.OnlineFeatureService;
import com.interview.minireco.util.SimulatedLatency;

import java.util.List;

public class DemoOnlineFeatureService implements OnlineFeatureService {
    @Override
    public void fillOnlineFeatures(List<Item> items) {
        SimulatedLatency.sleepMs(5L * items.size());
        for (Item item : items) {
            int price = 20 + (int) (item.getItemId() % 180);
            int stock = item.getItemId() % 11 == 0 ? 0 : 20 + (int) (item.getItemId() % 50);
            String status = item.getItemId() % 17 == 0 ? "OFFLINE" : "ONLINE";
            item.putAttr(AttrName.PRICE, String.valueOf(price));
            item.putAttr(AttrName.STOCK, String.valueOf(stock));
            item.putAttr(AttrName.STATUS, status);
        }
    }
}
