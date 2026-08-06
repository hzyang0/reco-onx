package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.domain.UserFeature;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.service.downstream.UserFeatureService;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JdbcUserFeatureService implements UserFeatureService {
    private final JdbcDataRepository repository;

    public JdbcUserFeatureService(JdbcDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserFeature getUserFeature(long userId) {
        JdbcDataRepository.UserProfile profile = repository.findUser(userId)
                .orElse(new JdbcDataRepository.UserProfile(userId, 25, true, "home", "未知", "未知"));
        return new UserFeature(
                userId,
                profile.newUser(),
                inferPreferredCategory(userId, profile.defaultCategory()),
                profile.age()
        );
    }

    private String inferPreferredCategory(long userId, String fallbackCategory) {
        Map<String, Integer> scoreByCategory = new LinkedHashMap<>();
        for (JdbcDataRepository.UserEvent event : repository.findEvents(userId)) {
            scoreByCategory.merge(event.category(), eventWeight(event.eventType()), Integer::sum);
        }
        return scoreByCategory.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(fallbackCategory);
    }

    private int eventWeight(String eventType) {
        return switch (eventType) {
            case "purchase" -> 5;
            case "cart" -> 3;
            case "click" -> 2;
            default -> 1;
        };
    }
}
