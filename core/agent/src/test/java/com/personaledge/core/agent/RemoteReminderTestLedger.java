package com.personaledge.core.agent;

import com.personaledge.core.tools.ActionExecutionState;
import com.personaledge.core.tools.PersistentActionLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlin.coroutines.Continuation;

/**
 * JVM-only contract double. Java can subclass the Kotlin-internal marker without widening its
 * production API. This fake is never shipped and provides no device/disk durability evidence.
 */
public final class RemoteReminderTestLedger extends PersistentActionLedger {
    public int claimAttempts;
    private final Map<String, ActionExecutionState> states = new LinkedHashMap<>();

    @Override
    public synchronized Object claim(String key, Continuation<? super Boolean> continuation) {
        claimAttempts++;
        if (states.containsKey(key)) return false;
        states.put(key, ActionExecutionState.CLAIMED);
        return true;
    }

    @Override
    public synchronized Object recordState(
            String key,
            ActionExecutionState state,
            Continuation<? super Boolean> continuation) {
        if (!states.containsKey(key)) return false;
        states.put(key, state);
        return true;
    }

    public synchronized Map<String, ActionExecutionState> snapshot() {
        return new LinkedHashMap<>(states);
    }
}
