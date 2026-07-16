package com.interview.minireco.service.context;

import com.interview.minireco.domain.Address;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.UserFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RecommendContext {
    private final String requestId;
    private final RecommendRequest request;
    private UserFeature userFeature;
    private Map<String, String> abParams = Map.of();
    private Address address;
    private List<Item> recalledItems = List.of();
    private List<Item> filteredItems = List.of();
    private final Map<String, Long> stageCostMs = new LinkedHashMap<>();
    private final Map<String, Object> debug = new LinkedHashMap<>();

    public RecommendContext(String requestId, RecommendRequest request) {
        this.requestId = requestId;
        this.request = request;
    }

    public String getRequestId() {
        return requestId;
    }

    public RecommendRequest getRequest() {
        return request;
    }

    public long getUserId() {
        return request.getUserId();
    }

    public String getScene() {
        return request.getScene();
    }

    public int getLimit() {
        return request.getLimit();
    }

    public UserFeature getUserFeature() {
        return userFeature;
    }

    public void setUserFeature(UserFeature userFeature) {
        this.userFeature = userFeature;
    }

    public Map<String, String> getAbParams() {
        return abParams;
    }

    public void setAbParams(Map<String, String> abParams) {
        this.abParams = Map.copyOf(abParams);
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public List<Item> getRecalledItems() {
        return recalledItems;
    }

    public void setRecalledItems(List<Item> recalledItems) {
        this.recalledItems = new ArrayList<>(recalledItems);
    }

    public List<Item> getFilteredItems() {
        return filteredItems;
    }

    public void setFilteredItems(List<Item> filteredItems) {
        this.filteredItems = new ArrayList<>(filteredItems);
    }

    public void addStageCostMs(String stageName, long costMs) {
        stageCostMs.put(stageName, costMs);
    }

    public Map<String, Long> getStageCostMs() {
        return Map.copyOf(stageCostMs);
    }

    public void putDebug(String key, Object value) {
        debug.put(key, value);
    }

    public Map<String, Object> buildDebugSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.putAll(debug);
        snapshot.put("stageCostMs", new LinkedHashMap<>(stageCostMs));
        return snapshot;
    }
}
