package com.interview.minireco.migration;

public enum PipelineVersion {
    LEGACY,
    NEW;

    public PipelineVersion opposite() {
        return this == LEGACY ? NEW : LEGACY;
    }
}
