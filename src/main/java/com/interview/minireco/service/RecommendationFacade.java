package com.interview.minireco.service;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.domain.RecommendResponse;

public interface RecommendationFacade {
    RecommendResponse recommend(RecommendRequest request);
}
