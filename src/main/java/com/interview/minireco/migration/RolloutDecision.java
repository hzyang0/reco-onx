package com.interview.minireco.migration;

public record RolloutDecision(
        PipelineVersion primaryVersion,
        int userBucket,
        boolean shadowSelected,
        int shadowBucket,
        int newPipelinePercent,
        int shadowPercent
) {
    public PipelineVersion shadowVersion() {
        return primaryVersion.opposite();
    }
}
