/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.scheduler

import spock.lang.Specification
import spock.lang.Unroll

import static org.gradle.internal.scheduler.EdgeType.AVOID_STARTING_BEFORE
import static org.gradle.internal.scheduler.EdgeType.DEPENDENT
import static org.gradle.internal.scheduler.EdgeType.FINALIZER
import static org.gradle.internal.scheduler.EdgeType.MUST_RUN_AFTER

abstract class AbstractSchedulerTest extends Specification {
    def graph = new Graph()
    def executedNodes = []
    def executionTracker = new NodeExecutionTracker() {
        @Override
        synchronized void nodeExecuted(Node node) {
            executedNodes.add(node)
        }
    }

    abstract Scheduler getScheduler()

    def "schedules tasks in dependency order"() {
        given:
        def a = task("a")
        def b = task("b", dependsOn: [a])
        def c = task("c", dependsOn: [b, a])
        def d = task("d", dependsOn: [c])

        expect:
        executes a, b, c, d
    }

    def "schedules task dependencies in name order when there are no dependencies between them"() {
        given:
        def a = task("a")
        def b = task("b")
        def c = task("c")
        def d = task("d", dependsOn: [b, a, c])

        expect:
        executes a, b, c, d
    }

    @Unroll
    def "schedules #orderingRule task dependencies in name order"() {
        given:
        def a = task("a")
        def b = task("b")
        def c = task("c", (orderingRule): [b, a])
        def d = task("d", dependsOn: [b, a])

        expect:
        executes a, b, c, d

        where:
        orderingRule << ['mustRunAfter', 'shouldRunAfter']
    }

    def "all dependencies scheduled when adding tasks"() {
        def a = task("a")
        def b = task("b", dependsOn: [a])
        def c = task("c", dependsOn: [b, a])
        def d = task("d", dependsOn: [c])

        expect:
        executes a, b, c, d
    }

    @Unroll
    def "#orderingRule ordering is honoured for dependencies"() {
        def b = task("b")
        def a = task("a", (orderingRule): [b])
        def c = task("c", dependsOn: [a, b])

        expect:
        executes b, a, c

        where:
        orderingRule << ['mustRunAfter', 'shouldRunAfter']
    }

    protected void executeGraph() {
        def scheduler = getScheduler()
        try {
            scheduler.executeGraph()
        } finally {
            scheduler.close()
        }
    }

    protected void executes(Node... nodes) {
        executeGraph()
        assert executedNodes == (nodes as List)
    }

    protected TaskNode task(String name) {
        task([:], name)
    }

    protected TaskNode task(Map options, String name) {
        String project = options.project ?: "root"
        boolean failure = options.failure ?: false
        def task = new TaskNode(project, name, executionTracker, failure)
        graph.addNode(task)
        options.dependsOn?.each { TaskNode dependency ->
            graph.addEdge(new Edge(dependency, task, DEPENDENT))
        }
        options.mustRunAfter?.each { TaskNode predecessor ->
            graph.addEdge(new Edge(predecessor, task, MUST_RUN_AFTER))
        }
        options.shouldRunAfter?.each { TaskNode predecessor ->
            graph.addEdge(new Edge(predecessor, task, AVOID_STARTING_BEFORE))
        }
        options.finalizedBy?.each { TaskNode finalizer ->
            graph.addEdge(new Edge(task, finalizer, FINALIZER))
        }
        return task
    }
}
