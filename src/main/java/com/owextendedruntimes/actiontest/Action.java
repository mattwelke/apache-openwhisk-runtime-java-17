package com.owextendedruntimes.actiontest;

import java.util.Map;

/**
 * Parent class of all OpenWhisk Java action implementations. OpenWhisk users extend this class and override the invoke
 * method to implement their actions.
 */
public abstract class Action {
    /**
     * The cluster context, aka "OpenWhisk variables", implemented in official runtimes by mutating the environment
     * variables at runtime (no longer allowed by Java 9+). In this runtime, implemented as state contained within the
     * parent class of the user's action.
     */
    protected Map<String, Object> clusterContext;

    /**
     * Sets the cluster context of the action so that it can be referenced by all invocations of the action if the
     * OpenWhisk user wants to use it. This function should not be called by the OpenWhisk user.
     * @param clusterContext The cluster context.
     */
    public void setClusterContext(Map<String, Object> clusterContext) {
        this.clusterContext = clusterContext;
    }

    /**
     * The method invoked by the OpenWhisk runtime in response to the action being invoked.
     * @param input The input to the action during its invocation.
     * @return The output of the action invocation.
     */
    public abstract Map<String, Object> invoke(Map<String, Object> input);
}
