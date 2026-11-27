package com.suarez.coder.decision

import com.suarez.coder.agent.ReadOnlyMismatchRecovery

/**
 * Pure access decision for one request. The architect model id is copied through unchanged: task routing never
 * swaps the user's architect. A write-intent task that was classified as a question still starts READ_WRITE.
 */
object RouterAccessPolicy {
    data class Outcome(val route: Route, val phase: String, val architectModel: String, val implementerModel: String)

    fun decide(request: String, architectModel: String, implementerModel: String, forcedRoute: Route? = null): Outcome {
        val route = forcedRoute ?: LocalRouter.classify(request, pinnedFiles = 0)?.route ?: Route.COMPLEX_ARCHITECT
        val readOnly = ReadOnlyMismatchRecovery.readOnlyForRoute(route, request)
        return Outcome(route, if (readOnly) "READ_ONLY" else "READ_WRITE", architectModel, implementerModel)
    }
}
