package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.AttrName;
import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.service.downstream.RecallService;

import java.util.Comparator;
import java.util.List;

public final class JdbcRecallService implements RecallService {
    private final String source;
    private final int candidateLimit;
    private final JdbcDataRepository repository;

    public JdbcRecallService(String source, int candidateLimit, JdbcDataRepository repository) {
        if (candidateLimit <= 0) {
            throw new IllegalArgumentException("candidateLimit must be positive");
        }
        this.source = source;
        this.candidateLimit = candidateLimit;
        this.repository = repository;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public List<Item> recall(RecommendContext context) {
        String preferredCategory = context.getUserFeature().getPreferredCategory();
        return repository.findCatalogBySource(source).stream()
                .sorted(Comparator
                        .comparing((JdbcDataRepository.CatalogItem item) -> !preferredCategory.equals(item.category()))
                        .thenComparing(JdbcDataRepository.CatalogItem::baseScore, Comparator.reverseOrder()))
                .limit(candidateLimit)
                .map(this::toItem)
                .toList();
    }

    private Item toItem(JdbcDataRepository.CatalogItem entry) {
        Item item = new Item(
                entry.itemId(), entry.title(), entry.source(), entry.category(), entry.baseScore()
        );
        item.putAttr(AttrName.RECALL_REASON, entry.recallReason());
        if (entry.roomId() != null && !entry.roomId().isBlank()) {
            item.putAttr(AttrName.ROOM_ID, entry.roomId());
        }
        if (entry.creativeId() != null && !entry.creativeId().isBlank()) {
            item.putAttr(AttrName.CREATIVE_ID, entry.creativeId());
        }
        return item;
    }
}
