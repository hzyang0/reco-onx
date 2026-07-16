package com.interview.minireco.service.operator.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DagGraph {
    private final Map<String, DagNode> nodesByName;

    public DagGraph(List<DagNode> nodes) {
        this.nodesByName = new LinkedHashMap<>();
        for (DagNode node : nodes) {
            DagNode previous = nodesByName.put(node.name(), node);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate DAG node: " + node.name());
            }
        }
        validateDependencies();
    }

    public List<DagNode> nodes() {
        return List.copyOf(nodesByName.values());
    }

    public DagNode get(String name) {
        return nodesByName.get(name);
    }

    public boolean contains(String name) {
        return nodesByName.containsKey(name);
    }

    private void validateDependencies() {
        for (DagNode node : nodesByName.values()) {
            for (String dependency : node.dependencies()) {
                if (!nodesByName.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "DAG node " + node.name() + " depends on missing node: " + dependency
                    );
                }
            }
        }
    }
}
