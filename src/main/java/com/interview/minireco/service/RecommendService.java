package com.interview.minireco.service;

import com.interview.minireco.domain.Address;
import com.interview.minireco.domain.AttrName;
import com.interview.minireco.domain.Item;
import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;
import com.interview.minireco.domain.UserFeature;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.downstream.AbService;
import com.interview.minireco.service.downstream.AddressService;
import com.interview.minireco.service.downstream.MixRankService;
import com.interview.minireco.service.downstream.OnlineFeatureService;
import com.interview.minireco.service.downstream.RecallService;
import com.interview.minireco.service.downstream.UserFeatureService;

import java.util.ArrayList;
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
        RecommendContext context = new RecommendContext(UUID.randomUUID().toString(), request);

        time("prepare", context, () -> prepare(context));

        List<Item> recalledItems = time("recall", context, () -> recall(context));
        context.setRecalledItems(recalledItems);
        context.putDebug("recallItemCount", recalledItems.size());

        time("onlineFeature", context, () -> onlineFeatureService.fillOnlineFeatures(context.getRecalledItems()));

        List<Item> filteredItems = time("filter", context, () -> filterValidItems(context.getRecalledItems()));
        context.setFilteredItems(filteredItems);
        context.putDebug("filteredItemCount", filteredItems.size());

        List<Item> rankedItems = time("mixRank", context,
                () -> mixRankService.rank(context.getFilteredItems(), context, context.getLimit()));

        List<Item> finalItems = time("postProcess", context,
                () -> postProcess(rankedItems, context.getLimit()));
        context.putDebug("returnedItemCount", finalItems.size());

        long totalCostMs = toMs(System.nanoTime() - totalStart);

        System.out.printf(
                "requestId=%s userId=%d scene=%s totalCostMs=%d recall=%d filtered=%d returned=%d%n",
                context.getRequestId(),
                context.getUserId(),
                context.getScene(),
                totalCostMs,
                recalledItems.size(),
                filteredItems.size(),
                finalItems.size()
        );

        return new RecommendResponse(
                context.getRequestId(),
                context.getUserId(),
                context.getScene(),
                totalCostMs,
                finalItems,
                context.buildDebugSnapshot()
        );
    }

    private void prepare(RecommendContext context) {
        validateScene(context.getScene());

        UserFeature userFeature = userFeatureService.getUserFeature(context.getUserId());
        Map<String, String> abParams = abService.getAbParams(context.getUserId(), context.getScene());
        Address address = addressService.getDefaultAddress(context.getUserId());

        context.setUserFeature(userFeature);
        context.setAbParams(abParams);
        context.setAddress(address);
    }

    private List<Item> recall(RecommendContext context) {
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
            int stock = item.findAttr(AttrName.STOCK)
                    .map(Integer::parseInt)
                    .orElse(0);
            String status = item.findAttr(AttrName.STATUS)
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
            Item fallback = new Item(90_000L + index, "Fallback hot item-" + index, "fallback", "hot", 0.30);
            fallback.putAttr(AttrName.PRICE, String.valueOf(19 + index));
            fallback.putAttr(AttrName.STOCK, "999");
            fallback.putAttr(AttrName.STATUS, "ONLINE");
            fallback.putAttr(AttrName.RECALL_REASON, "fallback");
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

    private void time(String name, RecommendContext context, Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        context.addStageCostMs(name, toMs(System.nanoTime() - start));
    }

    private <T> T time(String name, RecommendContext context, StageSupplier<T> supplier) {
        long start = System.nanoTime();
        T result = supplier.get();
        context.addStageCostMs(name, toMs(System.nanoTime() - start));
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
