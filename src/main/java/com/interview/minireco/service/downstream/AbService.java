package com.interview.minireco.service.downstream;

import java.util.Map;

public interface AbService {
    Map<String, String> getAbParams(long userId, String scene);
}
