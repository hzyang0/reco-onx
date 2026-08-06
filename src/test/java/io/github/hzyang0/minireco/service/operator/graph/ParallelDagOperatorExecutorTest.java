package io.github.hzyang0.minireco.service.operator.graph;

import io.github.hzyang0.minireco.domain.RecommendRequest;
import io.github.hzyang0.minireco.service.context.RecommendContext;
import io.github.hzyang0.minireco.service.operator.Operator;
import io.github.hzyang0.minireco.observability.MetricsRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelDagOperatorExecutorTest {
    @Test
    void siblingNodesShouldRunInParallelAfterDependencyIsReady() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<String> completed = new ArrayList<>();

        Operator prepare = namedOperator("prepare", completed);
        Operator onlineFeature = blockingOperator("onlineFeature", bothStarted, release, completed);
        Operator mixRank = blockingOperator("mixRank", bothStarted, release, completed);
        Operator postProcess = namedOperator("postProcess", completed);

        DagGraph graph = new DagGraph(List.of(
                DagNode.of(prepare),
                DagNode.of(onlineFeature, "prepare"),
                DagNode.of(mixRank, "prepare"),
                DagNode.of(postProcess, "onlineFeature", "mixRank")
        ));
        ParallelDagOperatorExecutor executor = new ParallelDagOperatorExecutor(graph, List.of(), 2);
        RecommendContext context = new RecommendContext("request-1", new RecommendRequest(1L, "mall", 1));

        Thread executionThread = new Thread(() -> executor.execute(context));
        executionThread.start();

        assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
        release.countDown();
        executionThread.join(1_000);

        assertEquals(4, completed.size());
        assertEquals("prepare", completed.get(0));
        assertTrue(completed.contains("onlineFeature"));
        assertTrue(completed.contains("mixRank"));
        assertEquals("postProcess", completed.get(3));
        executor.close();
    }

    @Test
    void requestDeadlineShouldCancelSlowGraph() {
        Operator slow = new Operator() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public void execute(RecommendContext context) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ParallelDagOperatorExecutor executor = new ParallelDagOperatorExecutor(
                new DagGraph(List.of(DagNode.of(slow))),
                List.of(),
                1,
                new MetricsRegistry(),
                20
        );
        RecommendContext context = new RecommendContext("timeout", new RecommendRequest(1L, "mall", 1));

        assertThrows(RequestTimeoutException.class, () -> executor.execute(context));
        executor.close();
    }

    private Operator namedOperator(String name, List<String> completed) {
        return new Operator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(RecommendContext context) {
                synchronized (completed) {
                    completed.add(name);
                }
            }
        };
    }

    private Operator blockingOperator(
            String name,
            CountDownLatch bothStarted,
            CountDownLatch release,
            List<String> completed
    ) {
        return new Operator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(RecommendContext context) {
                bothStarted.countDown();
                try {
                    release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                synchronized (completed) {
                    completed.add(name);
                }
            }
        };
    }
}
