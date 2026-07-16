package com.interview.minireco.service.operator.graph;

import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.observability.MetricsRegistry;
import com.interview.minireco.observability.StructuredLogger;
import com.interview.minireco.service.operator.ExecutionEngine;
import com.interview.minireco.service.operator.OperatorConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class DagOperatorExecutor implements ExecutionEngine {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(DagOperatorExecutor.class);

    private final List<DagNode> executionOrder;
    private final Map<String, OperatorConfig> configByName;
    private final MetricsRegistry metricsRegistry;

    public DagOperatorExecutor(DagGraph graph, List<OperatorConfig> configs) {
        this(graph, configs, MetricsRegistry.global());
    }

    public DagOperatorExecutor(DagGraph graph, List<OperatorConfig> configs, MetricsRegistry metricsRegistry) {
        this.executionOrder = topologicalSort(graph);
        this.metricsRegistry = metricsRegistry;
        this.configByName = new LinkedHashMap<>();
        for (OperatorConfig config : configs) {
            this.configByName.put(config.getName(), config);
        }
    }

    @Override
    public void execute(RecommendContext context) {
        for (DagNode node : executionOrder) {
            OperatorConfig config = configByName.getOrDefault(node.name(), OperatorConfig.enabled(node.name()));
            if (!config.isEnabled()) {
                context.addStageCostMs(node.name(), 0);
                context.putDebug(node.name() + "Skipped", true);
                metricsRegistry.increment("operator.skipped", Map.of("operator", node.name()));
                continue;
            }

            long start = System.nanoTime();
            try {
                node.operator().execute(context);
                long costMs = toMs(System.nanoTime() - start);
                context.addStageCostMs(node.name(), costMs);
                metricsRegistry.recordTimer("operator.cost", Map.of("operator", node.name(), "status", "success"), costMs);
                metricsRegistry.increment("operator.success", Map.of("operator", node.name()));
                LOGGER.debug(context.getRequestId(), "operator_success", () -> Map.of("operator", node.name(), "costMs", costMs));
            } catch (RuntimeException e) {
                long costMs = toMs(System.nanoTime() - start);
                context.addStageCostMs(node.name(), costMs);
                metricsRegistry.recordTimer("operator.cost", Map.of("operator", node.name(), "status", "error"), costMs);
                metricsRegistry.increment("operator.error", Map.of("operator", node.name()));
                LOGGER.error(context.getRequestId(), "operator_error", () -> Map.of("operator", node.name(), "costMs", costMs), e);
                throw e;
            }
        }
    }

    public List<String> executionOrderNames() {
        return executionOrder.stream()
                .map(DagNode::name)
                .toList();
    }

    private List<DagNode> topologicalSort(DagGraph graph) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();

        for (DagNode node : graph.nodes()) {
            indegree.put(node.name(), node.dependencies().size());
            dependents.putIfAbsent(node.name(), new ArrayList<>());
        }

        for (DagNode node : graph.nodes()) {
            for (String dependency : node.dependencies()) {
                dependents.get(dependency).add(node.name());
            }
        }

        Queue<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<DagNode> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.remove();
            sorted.add(graph.get(current));

            for (String dependent : dependents.get(current)) {
                int nextIndegree = indegree.get(dependent) - 1;
                indegree.put(dependent, nextIndegree);
                if (nextIndegree == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (sorted.size() != graph.nodes().size()) {
            throw new IllegalArgumentException("DAG contains cycle");
        }
        return sorted;
    }

    private long toMs(long nanos) {
        return nanos / 1_000_000;
    }
}
