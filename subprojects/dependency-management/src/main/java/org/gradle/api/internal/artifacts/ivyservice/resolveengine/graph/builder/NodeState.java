/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import com.google.common.collect.Lists;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.internal.artifacts.DependencySubstitutionInternal;
import org.gradle.api.internal.artifacts.ResolvedConfigurationIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionApplicator;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusion;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusions;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphNode;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.specs.Spec;
import org.gradle.internal.component.local.model.LocalConfigurationMetadata;
import org.gradle.internal.component.local.model.LocalFileDependencyMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Represents a node in the dependency graph.
 */
class NodeState implements DependencyGraphNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyGraphBuilder.class);
    private static final Spec<EdgeState> TRANSITIVE_EDGES = new Spec<EdgeState>() {
        @Override
        public boolean isSatisfiedBy(EdgeState edge) {
            return edge.isTransitive();
        }
    };

    private final Long resultId;
    private final ComponentState component;
    private final List<EdgeState> incomingEdges = Lists.newArrayList();
    private final List<EdgeState> outgoingEdges = Lists.newArrayList();
    private final ResolvedConfigurationIdentifier id;

    private final ConfigurationMetadata metaData;
    private final ResolveState resolveState;
    private final boolean isTransitive;
    private ModuleExclusion previousTraversalExclusions;

    NodeState(Long resultId, ResolvedConfigurationIdentifier id, ComponentState component, ResolveState resolveState, ConfigurationMetadata md) {
        this.resultId = resultId;
        this.id = id;
        this.component = component;
        this.resolveState = resolveState;
        this.metaData = md;
        this.isTransitive = metaData.isTransitive();
        component.addConfiguration(this);
    }

    ComponentState getComponent() {
        return component;
    }

    @Override
    public Long getNodeId() {
        return resultId;
    }

    @Override
    public boolean isRoot() {
        return false;
    }

    @Override
    public ResolvedConfigurationIdentifier getResolvedConfigurationId() {
        return id;
    }

    @Override
    public ComponentState getOwner() {
        return component;
    }

    @Override
    public List<EdgeState> getIncomingEdges() {
        return incomingEdges;
    }

    @Override
    public List<EdgeState> getOutgoingEdges() {
        return outgoingEdges;
    }

    @Override
    public ConfigurationMetadata getMetadata() {
        return metaData;
    }

    @Override
    public Set<? extends LocalFileDependencyMetadata> getOutgoingFileEdges() {
        if (metaData instanceof LocalConfigurationMetadata) {
            // Only when this node has a transitive incoming edge
            for (EdgeState incomingEdge : incomingEdges) {
                if (incomingEdge.isTransitive()) {
                    return ((LocalConfigurationMetadata) metaData).getFiles();
                }
            }
        }
        return Collections.emptySet();
    }

    @Override
    public String toString() {
        return String.format("%s(%s)", component, id.getConfiguration());
    }

    public boolean isTransitive() {
        return isTransitive;
    }

    /**
     * Visits all of the dependencies that originate on this node, adding them as outgoing edges.
     * The {@link #outgoingEdges} collection is populated, as is the `discoveredEdges` parameter.
     * @param discoveredEdges A collector for visited edges.
     * @param pendingDependenciesHandler Handler for pending dependencies.
     */
    public void visitOutgoingDependencies(Collection<EdgeState> discoveredEdges, PendingDependenciesHandler pendingDependenciesHandler) {
        // If this configuration's version is in conflict, do not traverse.
        // If none of the incoming edges are transitive, remove previous state and do not traverse.
        // If not traversed before, simply add all selected outgoing edges (either hard or pending edges)
        // If traversed before:
        //      If net exclusions for this node have not changed, ignore
        //      If net exclusions for this node not changed, remove previous state and traverse outgoing edges again.

        if (!component.isSelected()) {
            LOGGER.debug("version for {} is not selected. ignoring.", this);
            return;
        }

        // Check if this node is still included in the graph, by looking at incoming edges.
        boolean hasIncomingEdges = !incomingEdges.isEmpty();
        List<EdgeState> transitiveIncoming = getTransitiveIncomingEdges();

        // Check if there are any transitive incoming edges at all. Don't traverse if not.
        if (transitiveIncoming.isEmpty() && !isRoot()) {
            // If node was previously traversed, need to remove outgoing edges.
            if (previousTraversalExclusions != null) {
                removeOutgoingEdges();
            }
            if (hasIncomingEdges) {
                LOGGER.debug("{} has no transitive incoming edges. ignoring outgoing edges.", this);
            } else {
                LOGGER.debug("{} has no incoming edges. ignoring.", this);
            }
            return;
        }

        // Determine the net exclusion for this node, by inspecting all transitive incoming edges
        ModuleExclusion resolutionFilter = getModuleResolutionFilter(transitiveIncoming);

        // Check if the was previously traversed with the same net exclusion
        if (previousTraversalExclusions != null && previousTraversalExclusions.excludesSameModulesAs(resolutionFilter)) {
            // Was previously traversed, and no change to the set of modules that are linked by outgoing edges.
            // Don't need to traverse again, but hang on to the new filter since it may change the set of excluded artifacts.
            LOGGER.debug("Changed edges for {} selects same versions as previous traversal. ignoring", this);
            previousTraversalExclusions = resolutionFilter;
            return;
        }

        // Clear previous traversal state, if any
        if (previousTraversalExclusions != null) {
            removeOutgoingEdges();
        }

        visitDependencies(resolutionFilter, pendingDependenciesHandler, discoveredEdges);
    }

    /**
     * Iterate over the dependencies originating in this node, adding them either as a 'pending' dependency
     * or adding them to the `discoveredEdges` collection (and `this.outgoingEdges`)
     */
    private void visitDependencies(ModuleExclusion resolutionFilter, PendingDependenciesHandler pendingDependenciesHandler, Collection<EdgeState> discoveredEdges) {
        PendingDependenciesHandler.Visitor pendingDepsVisitor =  pendingDependenciesHandler.start();
        try {
            for (DependencyMetadata dependency : metaData.getDependencies()) {
                DependencyState dependencyState = new DependencyState(dependency, resolveState.getComponentSelectorConverter());
                if (isExcluded(resolutionFilter, dependencyState)) {
                    continue;
                }
                dependencyState = maybeSubstitute(dependencyState);
                if (!pendingDepsVisitor.maybeAddAsPendingDependency(this, dependencyState)) {
                    EdgeState dependencyEdge = new EdgeState(this, dependencyState, resolutionFilter, resolveState);
                    outgoingEdges.add(dependencyEdge);
                    discoveredEdges.add(dependencyEdge);
                    dependencyEdge.getSelector().use();
                }
            }
            previousTraversalExclusions = resolutionFilter;
        } finally {
            // If there are 'pending' dependencies that share a target with any of these outgoing edges,
            // then reset the state of the node that owns those dependencies.
            // This way, all edges of the node will be re-processed.
            pendingDepsVisitor.complete();
        }
    }

    /**
     * Execute any dependency substitution rules that apply to this dependency.
     *
     * This may be better done as a decorator on ConfigurationMetadata.getDependencies()
     */
    private DependencyState maybeSubstitute(DependencyState dependencyState) {
        DependencySubstitutionApplicator.SubstitutionResult substitutionResult = resolveState.getDependencySubstitutionApplicator().apply(dependencyState.getDependency());
        if (substitutionResult.hasFailure()) {
            dependencyState.failure = new ModuleVersionResolveException(dependencyState.getRequested(), substitutionResult.getFailure());
            return dependencyState;
        }

        DependencySubstitutionInternal details = substitutionResult.getResult();
        if (details != null && details.isUpdated()) {
            return dependencyState.withTarget(details.getTarget(), details.getSelectionDescription());
        }
        return dependencyState;
    }

    /**
     * Returns the set of incoming edges that are transitive. Most edges are transitive, so the implementation is optimized for this case.
     */
    private List<EdgeState> getTransitiveIncomingEdges() {
        if (isRoot()) {
            return incomingEdges;
        }
        for (EdgeState incomingEdge : incomingEdges) {
            if (!incomingEdge.isTransitive()) {
                // Have a non-transitive edge: return a filtered list
                return CollectionUtils.filter(incomingEdges, TRANSITIVE_EDGES);
            }
        }
        // All edges are transitive, no need to construct a filtered list.
        return incomingEdges;
    }

    private boolean isExcluded(ModuleExclusion selector, DependencyState dependencyState) {
        DependencyMetadata dependency = dependencyState.getDependency();
        if (!resolveState.getEdgeFilter().isSatisfiedBy(dependency)) {
            LOGGER.debug("{} is filtered.", dependency);
            return true;
        }
        if (selector == ModuleExclusions.excludeNone()) {
            return false;
        }
        ModuleIdentifier targetModuleId = dependencyState.getModuleIdentifier();
        if (selector.excludeModule(targetModuleId)) {
            LOGGER.debug("{} is excluded from {}.", targetModuleId, this);
            return true;
        }

        return false;
    }

    public void addIncomingEdge(EdgeState dependencyEdge) {
        incomingEdges.add(dependencyEdge);
        resolveState.onMoreSelected(this);
    }

    public void removeIncomingEdge(EdgeState dependencyEdge) {
        incomingEdges.remove(dependencyEdge);
        resolveState.onFewerSelected(this);
    }

    public boolean isSelected() {
        return !incomingEdges.isEmpty();
    }

    private ModuleExclusion getModuleResolutionFilter(List<EdgeState> incomingEdges) {
        ModuleExclusions moduleExclusions = resolveState.getModuleExclusions();
        ModuleExclusion nodeExclusions = moduleExclusions.excludeAny(metaData.getExcludes());
        if (incomingEdges.isEmpty()) {
            return nodeExclusions;
        }
        ModuleExclusion edgeExclusions = incomingEdges.get(0).getExclusions();
        for (int i = 1; i < incomingEdges.size(); i++) {
            EdgeState dependencyEdge = incomingEdges.get(i);
            edgeExclusions = moduleExclusions.union(edgeExclusions, dependencyEdge.getExclusions());
        }
        return moduleExclusions.intersect(edgeExclusions, nodeExclusions);
    }

    private void removeOutgoingEdges() {
        if (!outgoingEdges.isEmpty()) {
            for (EdgeState outgoingDependency : outgoingEdges) {
                outgoingDependency.removeFromTargetConfigurations();
                outgoingDependency.getSelector().release();
            }
        }
        outgoingEdges.clear();
        previousTraversalExclusions = null;
    }

    public void restart(ComponentState selected) {
        // Restarting this configuration after conflict resolution.
        // If this configuration belongs to the select version, queue ourselves up for traversal.
        // If not, then remove our incoming edges, which triggers them to be moved across to the selected configuration
        if (component == selected) {
            resolveState.onMoreSelected(this);
        } else {
            if (!incomingEdges.isEmpty()) {
                restartIncomingEdges();
            }
        }
    }

    private void restartIncomingEdges() {
        if (incomingEdges.size() == 1) {
            EdgeState singleEdge = incomingEdges.iterator().next();
            singleEdge.restart();
        } else {
            for (EdgeState dependency : new ArrayList<EdgeState>(incomingEdges)) {
                dependency.restart();
            }
        }
        incomingEdges.clear();
    }

    public void deselect() {
        removeOutgoingEdges();
    }

    void resetSelectionState() {
        previousTraversalExclusions = null;
        outgoingEdges.clear();
        resolveState.onMoreSelected(this);
    }

    public ImmutableAttributesFactory getAttributesFactory() {
        return resolveState.getAttributesFactory();
    }
}
