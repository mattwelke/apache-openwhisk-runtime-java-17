package com.owextendedruntimes.actiontestimpl;

import com.owextendedruntimes.actiontest.Action;

import java.util.Map;

// A test of implementing the new action contract as a user would in their own Java project.
public class ActionImpl extends Action {
    @Override
    public Map<String, Object> invoke(Map<String, Object> input) {
        if (clusterContext.containsKey("clusterName")) {
            return Map.of("clusterName", clusterContext.get("clusterName"));
        }
        return Map.of("hello", "world");
    }
}
