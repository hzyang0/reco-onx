package io.github.hzyang0.minireco.service.downstream;

import io.github.hzyang0.minireco.domain.UserFeature;

public interface UserFeatureService {
    UserFeature getUserFeature(long userId);
}
