package com.interview.minireco.config;

@FunctionalInterface
public interface ConfigFetcher {
    RuntimeConfigSnapshot fetch() throws Exception;
}
