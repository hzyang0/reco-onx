package io.github.hzyang0.minireco.service;

import io.github.hzyang0.minireco.domain.RecommendRequest;
import io.github.hzyang0.minireco.domain.RecommendResponse;

public interface RecommendationFacade {
    RecommendResponse recommend(RecommendRequest request);
}
