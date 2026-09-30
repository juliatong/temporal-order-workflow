package com.example.orders.workflow;

import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;

/**
 * Orders already running (or closed and still queryable) when new workflow code ships are replayed by it,
 * so the new code must reproduce their recorded history exactly. Each history here was recorded by an
 * earlier version of the workflow; a change that isn't guarded by Workflow.getVersion fails to replay it.
 */
class ReplayTest {

    @Test
    void aRollbackRecordedBeforeTheRollingBackStatusStillReplays() throws Exception {
        // Recorded by 197f60c: a carrier-rejected address, rolled back with no ROLLING_BACK status update.
        WorkflowReplayer.replayWorkflowExecutionFromResource(
                "histories/rollback-before-rolling-back-status.json", OrderWorkflowImpl.class);
    }
}
