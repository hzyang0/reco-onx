package com.interview.minireco.service.operator.graph;

import com.interview.minireco.service.operator.Operator;

import java.util.Arrays;
import java.util.List;

public class DagNode {
    private final Operator operator;
    private final List<String> dependencies;

    private DagNode(Operator operator, List<String> dependencies) {
        this.operator = operator;
        this.dependencies = List.copyOf(dependencies);
    }

    public static DagNode of(Operator operator, String... dependencies) {
        return new DagNode(operator, Arrays.asList(dependencies));
    }

    public String name() {
        return operator.name();
    }

    public Operator operator() {
        return operator;
    }

    public List<String> dependencies() {
        return dependencies;
    }
}
