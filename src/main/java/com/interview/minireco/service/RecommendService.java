package com.interview.minireco.service;

import com.interview.minireco.domain.Address;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.downstream.AbService;
import com.interview.minireco.service.downstream.AddressService;
import com.interview.minireco.service.downstream.MixRankService;
import com.interview.minireco.service.downstream.OnlineFeatureService;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.service.downstream.UserFeatureService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RecommendService {
    private final UserFeatureService userFeatureService;
    private final AbService abService;
    private final AddressService addressService;
    private final List<RecallService> recallServices;
    private final OnlineFeatureService onlineFeatureService;
    private final MixRankService mixRankService;

    public RecommendService(
            UserFeatureService userFeatureService,
            AbService abService,
            AddressService addressService,
            List<RecallService> recallServices,
            OnlineFeatureService onlineFeatureService,
            MixRankService mixRankService
    ) {
        this.userFeatureService = userFeatureService;
        this.abService = abService;
        this.addressService = addressService;
        this.recallServices = List.copyOf(recallServices);
        this.onlineFeatureService = onlineFeatureService;
        this.mixRankService = mixRankService;
    }

    public RecommendResponse recommend(RecommendRequest request) {
        long totalStart = System.nanoTime();
        String requestId = UUID.randomUUID().toString();

        // V1 的核心历史包袱：一次请求所有东西都放在这个大 Map 里。
        // 优点是开发快；缺点是 key 硬编码、类型不安全、耦合很高。
        Map<String, Object> context = new HashMap<>();
        Map<String, Object> debug = new LinkedHashMap<>();
        Map<String, Long> stageCostMs = new LinkedHashMap<>();

        context.put("request_id", requestId);
        context.put("request", request);

        time("prepare", stageCostMs, () -> prepare(request, context));

        List<Item> recalledItems = time("recall", stageCostMs, () -> recall(context));
        context.put("recalled_items", recalledItems);
        debug.put("recallItemCount", recalledItems.size());

        time("onlineFeature", stageCostMs, () -> onlineFeatureService.fillOnlineFeatures(recalledItems));

        List<Item> filteredItems = time("filter", stageCostMs, () -> filterValidItems(recalledItems));
        context.put("filtered_items", filteredItems);
        debug.put("filteredItemCount", filteredItems.size());

        List<Item> rankedItems = time("mixRank", stageCostMs,
                () -> mixRankService.rank(filteredItems, context, request.getLimit()));

        List<Item> finalItems = time("postProcess", stageCostMs,
                () -> postProcess(rankedItems, request.getLimit()));

        long totalCostMs = toMs(System.nanoTime() - totalStart);
        debug.put("stageCostMs", stageCostMs);
        debug.put("returnedItemCount", finalItems.size());

        System.out.printf(
                "requestId=%s userId=%d scene=%s totalCostMs=%d recall=%d filtered=%d returned=%d%n",
                requestId,
                request.getUserId(),
                request.getScene(),
                totalCostMs,
                recalledItems.size(),
                filteredItems.size(),
                finalItems.size()
        );

        return new RecommendResponse(requestId, request.getUserId(), request.getScene(), totalCostMs, finalItems, debug);
    }

    private void prepare(RecommendRequest request, Map<String, Object> context) {
        validateScene(request.getScene());

        UserFeature userFeature = userFeatureService.getUserFeature(request.getUserId());
        Map<String, String> abParams = abService.getAbParams(request.getUserId(), request.getScene());
        Address address = addressService.getDefaultAddress(request.getUserId());

        context.put("user_id", request.getUserId());
        context.put("scene", request.getScene());
        context.put("limit", request.getLimit());
        context.put("user_feature", userFeature);
        context.put("ab_params", abParams);
        context.put("address", address);
    }

    private List<Item> recall(Map<String, Object> context) {
        List<Item> items = new ArrayList<>();
        for (RecallService recallService : recallServices) {
            List<Item> recalled = recallService.recall(context);
            items.addAll(recalled);
        }
        return items;
    }

    private List<Item> filterValidItems(List<Item> items) {
        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            int stock = item.findAttr("stock")
                    .map(Integer::parseInt)
                    .orElse(0);
            String status = item.findAttr("status")
                    .orElse("UNKNOWN");

            if (stock > 0 && "ONLINE".equals(status)) {
                result.add(item);
            }
        }
        return result;
    }

    private List<Item> postProcess(List<Item> rankedItems, int limit) {
        List<Item> result = new ArrayList<>(rankedItems);
        int index = 0;
        while (result.size() < limit) {
            Item fallback = new Item(90_000L + index, "兜底热门商品-" + index, "fallback", "hot", 0.30);
            fallback.putAttr("price", String.valueOf(19 + index));
            fallback.putAttr("stock", "999");
            fallback.putAttr("status", "ONLINE");
            fallback.putAttr("recall_reason", "fallback");
            result.add(fallback);
            index++;
        }
        return result;
    }

    private void validateScene(String scene) {
        if (!List.of("mall", "buy_first", "single_column", "double_column", "new_user_card").contains(scene)) {
            throw new IllegalArgumentException("unsupported scene: " + scene);
        }
    }

    private void time(String name, Map<String, Long> stageCostMs, Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        stageCostMs.put(name, toMs(System.nanoTime() - start));
    }

    private <T> T time(String name, Map<String, Long> stageCostMs, StageSupplier<T> supplier) {
        long start = System.nanoTime();
        T result = supplier.get();
        stageCostMs.put(name, toMs(System.nanoTime() - start));
        return result;
    }

    private long toMs(long nanos) {
        return nanos / 1_000_000;
    }

    @FunctionalInterface
    private interface StageSupplier<T> {
        T get();
    }
}
