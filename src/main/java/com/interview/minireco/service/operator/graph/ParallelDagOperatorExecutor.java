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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ParallelDagOperatorExecutor implements ExecutionEngine {
    private static final StructuredLogger LOGGER = StructuredLogger.getLogger(ParallelDagOperatorExecutor.class);

    private final DagGraph graph;
    private final Map<String, OperatorConfig> configByName;
    private final ExecutorService executorService;
    private final MetricsRegistry metricsRegistry;

    public ParallelDagOperatorExecutor(DagGraph graph, List<OperatorConfig> configs, int parallelism) {
        this(graph, configs, parallelism, MetricsRegistry.global());
    }

    public ParallelDagOperatorExecutor(
            DagGraph graph,
            List<OperatorConfig> configs,
            int parallelism,
            MetricsRegistry metricsRegistry
    ) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        this.graph = graph;
        validateAcyclic(graph);
        this.metricsRegistry = metricsRegistry;
        this.configByName = new LinkedHashMap<>();
        for (OperatorConfig config : configs) {
            this.configByName.put(config.getName(), config);
        }
        this.executorService = Executors.newFixedThreadPool(parallelism, daemonThreadFactory());
    }

    @Override
    public void execute(RecommendContext context) {
        Map<String, Integer> remainingDependencies = new LinkedHashMap<>();
        Map<String, List<String>> dependents = buildDependents(graph, remainingDependencies);

        Queue<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : remainingDependencies.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        CompletionService<String> completionService = new ExecutorCompletionService<>(executorService);
        List<Future<String>> submittedFutures = new ArrayList<>();
        int submitted = 0;
        int completed = 0;

        while (!ready.isEmpty()) {
            submittedFutures.add(submit(completionService, graph.get(ready.remove()), context));
            submitted++;
        }

        while (completed < graph.nodes().size()) {
            if (submitted == completed) {
                throw new IllegalStateException("DAG executor has no runnable node");
            }

            String completedNodeName;
            try {
                completedNodeName = completionService.take().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelSubmitted(submittedFutures);
                throw new IllegalStateException("parallel DAG execution interrupted", e);
            } catch (ExecutionException e) {
                cancelSubmitted(submittedFutures);
                throw new IllegalStateException("parallel DAG node failed", e.getCause());
            }

            completed++;
            for (String dependent : dependents.get(completedNodeName)) {
                int next = remainingDependencies.get(dependent) - 1;
                remainingDependencies.put(dependent, next);
                if (next == 0) {
                    submittedFutures.add(submit(completionService, graph.get(dependent), context));
                    submitted++;
                }
            }
        }
    }

    private Future<String> submit(CompletionService<String> completionService, DagNode node, RecommendContext context) {
        return completionService.submit(() -> {
            OperatorConfig config = configByName.getOrDefault(node.name(), OperatorConfig.enabled(node.name()));
            if (!config.isEnabled()) {
                context.addStageCostMs(node.name(), 0);
                context.putDebug(node.name() + "Skipped", true);
                metricsRegistry.increment("operator.skipped", Map.of("operator", node.name()));
                return node.name();
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
            return node.name();
        });
    }

    private Map<String, List<String>> buildDependents(DagGraph graph, Map<String, Integer> remainingDependencies) {
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (DagNode node : graph.nodes()) {
            remainingDependencies.put(node.name(), node.dependencies().size());
            dependents.putIfAbsent(node.name(), new ArrayList<>());
        }
        for (DagNode node : graph.nodes()) {
            for (String dependency : node.dependencies()) {
                dependents.get(dependency).add(node.name());
            }
        }
        return dependents;
    }

    private void validateAcyclic(DagGraph graph) {
        Map<String, Integer> remainingDependencies = new LinkedHashMap<>();
        Map<String, List<String>> dependents = buildDependents(graph, remainingDependencies);
        Queue<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : remainingDependencies.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        int visited = 0;
        while (!ready.isEmpty()) {
            String current = ready.remove();
            visited++;
            for (String dependent : dependents.get(current)) {
                int next = remainingDependencies.get(dependent) - 1;
                remainingDependencies.put(dependent, next);
                if (next == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (visited != graph.nodes().size()) {
            throw new IllegalArgumentException("DAG contains cycle");
        }
    }

    private void cancelSubmitted(List<Future<String>> submittedFutures) {
        for (Future<String> future : submittedFutures) {
            future.cancel(true);
        }
    }

    private ThreadFactory daemonThreadFactory() {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "parallel-dag-operator-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private long toMs(long nanos) {
        return nanos / 1_000_000;
    }
}
