package com.interview.minireco.service.operator.graph;

import com.interview.minireco.domain.RecommendRequest;
import com.interview.minireco.service.context.RecommendContext;
import com.interview.minireco.service.operator.Operator;
import com.interview.minireco.service.operator.OperatorConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DagOperatorExecutorTest {
    @Test
    void dagExecutorShouldRunOperatorsInTopologicalOrder() {
        List<String> executed = new ArrayList<>();
        Operator prepare = namedOperator("prepare", executed);
        Operator recall = namedOperator("recall", executed);
        Operator rank = namedOperator("rank", executed);

        DagGraph graph = new DagGraph(List.of(
                DagNode.of(rank, "recall"),
                DagNode.of(prepare),
                DagNode.of(recall, "prepare")
        ));
        DagOperatorExecutor executor = new DagOperatorExecutor(graph, List.of());

        executor.execute(new RecommendContext("request-1", new RecommendRequest(1L, "mall", 1)));

        assertEquals(List.of("prepare", "recall", "rank"), executed);
        assertEquals(List.of("prepare", "recall", "rank"), executor.executionOrderNames());
    }

    @Test
    void dagGraphShouldRejectMissingDependency() {
        Operator rank = namedOperator("rank", new ArrayList<>());

        assertThrows(IllegalArgumentException.class, () -> new DagGraph(List.of(
                DagNode.of(rank, "missing")
        )));
    }

    @Test
    void dagExecutorShouldRejectCycle() {
        Operator first = namedOperator("first", new ArrayList<>());
        Operator second = namedOperator("second", new ArrayList<>());

        DagGraph graph = new DagGraph(List.of(
                DagNode.of(first, "second"),
                DagNode.of(second, "first")
        ));

        assertThrows(IllegalArgumentException.class, () -> new DagOperatorExecutor(graph, List.of()));
    }

    @Test
    void disabledDagNodeShouldBeSkipped() {
        List<String> executed = new ArrayList<>();
        Operator prepare = namedOperator("prepare", executed);
        Operator recall = namedOperator("recall", executed);

        DagGraph graph = new DagGraph(List.of(
                DagNode.of(prepare),
                DagNode.of(recall, "prepare")
        ));
        DagOperatorExecutor executor = new DagOperatorExecutor(
                graph,
                List.of(OperatorConfig.disabled("recall"))
        );
        RecommendContext context = new RecommendContext("request-1", new RecommendRequest(1L, "mall", 1));

        executor.execute(context);

        assertEquals(List.of("prepare"), executed);
        assertEquals(true, context.buildDebugSnapshot().get("recallSkipped"));
    }

    private Operator namedOperator(String name, List<String> executed) {
        return new Operator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(RecommendContext context) {
                executed.add(name);
            }
        };
    }
}
