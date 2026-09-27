package com.aisocialgame.service;

import java.util.List;

public interface WriteRateLimiter {
    record Limit(String scope, int maximum) { }
    void require(String userId, List<Limit> limits);
}
