package io.github.hzyang0.minireco.service.downstream.impl;

import io.github.hzyang0.minireco.service.data.JdbcDataRepository;
import io.github.hzyang0.minireco.service.downstream.AbService;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JdbcAbService implements AbService {
    private final JdbcDataRepository repository;

    public JdbcAbService(JdbcDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<String, String> getAbParams(long userId, String scene) {
        JdbcDataRepository.ExperimentAssignment assignment = repository.findExperiment(userId, scene)
                .orElse(new JdbcDataRepository.ExperimentAssignment(userId, scene, "CONTROL", "DEFAULT"));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("recall_exp", assignment.recallExp());
        params.put("rank_exp", assignment.rankExp());
        return params;
    }
}
