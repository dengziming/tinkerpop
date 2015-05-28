/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.neo4j.structure;

import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationConverter;
import org.apache.tinkerpop.gremlin.neo4j.process.traversal.step.sideEffect.CypherStartStep;
import org.apache.tinkerpop.gremlin.neo4j.process.util.Neo4jCypherIterator;
import org.apache.tinkerpop.gremlin.neo4j.structure.full.FullNeo4jGraph;
import org.apache.tinkerpop.gremlin.neo4j.structure.simple.SimpleNeo4jGraph;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.AbstractTransaction;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.structure.util.wrapped.WrappedGraph;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.neo4j.tinkerpop.api.Neo4jFactory;
import org.neo4j.tinkerpop.api.Neo4jGraphAPI;
import org.neo4j.tinkerpop.api.Neo4jNode;
import org.neo4j.tinkerpop.api.Neo4jRelationship;
import org.neo4j.tinkerpop.api.Neo4jTx;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 * @author Pieter Martin
 */
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_INTEGRATE)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_PERFORMANCE)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_PERFORMANCE)
@Graph.OptIn(Graph.OptIn.SUITE_GROOVY_PROCESS_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_GROOVY_ENVIRONMENT)
@Graph.OptIn(Graph.OptIn.SUITE_GROOVY_ENVIRONMENT_INTEGRATE)
@Graph.OptIn(Graph.OptIn.SUITE_GROOVY_ENVIRONMENT_PERFORMANCE)
public abstract class Neo4jGraph implements Graph, WrappedGraph<Neo4jGraphAPI> {

    protected Features features = new Neo4jGraphFeatures();

    protected Neo4jGraphAPI baseGraph;
    protected BaseConfiguration configuration = new BaseConfiguration();

    public static final String CONFIG_DIRECTORY = "gremlin.neo4j.directory";
    public static final String CONFIG_CONF = "gremlin.neo4j.conf";
    public static final String CONFIG_META_PROPERTIES = "gremlin.neo4j.metaProperties";
    public static final String CONFIG_MULTI_PROPERTIES = "gremlin.neo4j.multiProperties";
    public static final String CONFIG_CHECK_ELEMENTS_IN_TRANSACTION = "gremlin.neo4j.checkElementsInTransaction";

    private final Neo4jTransaction neo4jTransaction = new Neo4jTransaction();
    private Neo4jGraphVariables neo4jGraphVariables;

    protected boolean checkElementsInTransaction = false;
    protected boolean supportsMetaProperties = false;
    protected boolean supportsMultiProperties = false;

    private void initialize(final Neo4jGraphAPI baseGraph, final Configuration configuration) {
        this.configuration.copy(configuration);
        this.baseGraph = baseGraph;
        this.checkElementsInTransaction = this.configuration.getBoolean(CONFIG_CHECK_ELEMENTS_IN_TRANSACTION, false);
        this.supportsMetaProperties = this.configuration.getBoolean(CONFIG_META_PROPERTIES, false);
        this.supportsMultiProperties = this.configuration.getBoolean(CONFIG_MULTI_PROPERTIES, false);
        if (this.supportsMultiProperties != this.supportsMetaProperties)
            throw new IllegalArgumentException(this.getClass().getSimpleName() + " currently supports either both meta-properties and multi-properties or neither");
        this.neo4jGraphVariables = new Neo4jGraphVariables(this);
    }

    protected Neo4jGraph(final Neo4jGraphAPI baseGraph, final Configuration configuration) {
        this.initialize(baseGraph, configuration);
    }

    protected Neo4jGraph(final Configuration configuration) {
        this.configuration.copy(configuration);
        final String directory = this.configuration.getString(CONFIG_DIRECTORY);
        final Map neo4jSpecificConfig = ConfigurationConverter.getMap(this.configuration.subset(CONFIG_CONF));
        this.baseGraph = Neo4jFactory.Builder.open(directory, neo4jSpecificConfig);
        this.initialize(this.baseGraph, configuration);
    }

    /**
     * Open a new {@link Neo4jGraph} instance.
     *
     * @param configuration the configuration for the instance
     * @return a newly opened {@link org.apache.tinkerpop.gremlin.structure.Graph}
     */
    public static Neo4jGraph open(final Configuration configuration) {
        if (null == configuration) throw Graph.Exceptions.argumentCanNotBeNull("configuration");
        if (!configuration.containsKey(CONFIG_DIRECTORY))
            throw new IllegalArgumentException(String.format("Neo4j configuration requires that the %s be set", CONFIG_DIRECTORY));

        final boolean supportsMetaProperties = configuration.getBoolean(CONFIG_META_PROPERTIES, false);
        final boolean supportsMultiProperties = configuration.getBoolean(CONFIG_MULTI_PROPERTIES, false);
        if (supportsMultiProperties != supportsMetaProperties)
            throw new IllegalArgumentException(Neo4jGraph.class.getSimpleName() + " currently supports either both meta-properties and multi-properties or neither");
        if (supportsMetaProperties) {
            return new FullNeo4jGraph(configuration);
        } else {
            return new SimpleNeo4jGraph(configuration);
        }
    }

    /**
     * Construct a Neo4jGraph instance by specifying the directory to create the database in..
     */
    public static Neo4jGraph open(final String directory) {
        final Configuration config = new BaseConfiguration();
        config.setProperty(CONFIG_DIRECTORY, directory);
        return open(config);
    }

    public abstract Neo4jVertex createVertex(final Neo4jNode node);

    public abstract Neo4jEdge createEdge(final Neo4jRelationship relationship);

    public abstract Predicate<Neo4jNode> getNodePredicate();

    public abstract Predicate<Neo4jRelationship> getRelationshipPredicate();

    @Override
    public Vertex addVertex(final Object... keyValues) {
        ElementHelper.legalPropertyKeyValueArray(keyValues);
        if (ElementHelper.getIdValue(keyValues).isPresent())
            throw Vertex.Exceptions.userSuppliedIdsNotSupported();
        this.tx().readWrite();
        final Neo4jVertex vertex = this.createVertex(this.baseGraph.createNode(ElementHelper.getLabelValue(keyValues).orElse(Vertex.DEFAULT_LABEL).split(Neo4jVertex.LABEL_DELIMINATOR)));
        ElementHelper.attachProperties(vertex, keyValues);
        return vertex;
    }

    @Override
    public Iterator<Vertex> vertices(final Object... vertexIds) {
        this.tx().readWrite();
        if (0 == vertexIds.length) {
            final Predicate<Neo4jNode> nodePredicate = this.getNodePredicate();
            return IteratorUtils.stream(this.getBaseGraph().allNodes())
                    .filter(node -> !this.checkElementsInTransaction || !Neo4jHelper.isDeleted(node))
                    .filter(nodePredicate)
                    .map(node -> (Vertex) this.createVertex(node)).iterator();
        } else {
            ElementHelper.validateMixedElementIds(Vertex.class, vertexIds);
            return Stream.of(vertexIds)
                    .map(id -> {
                        if (id instanceof Number)
                            return ((Number) id).longValue();
                        else if (id instanceof String)
                            return Long.valueOf(id.toString());
                        else if (id instanceof Neo4jVertex) {
                            return (Long) ((Neo4jVertex) id).id();
                        } else
                            throw new IllegalArgumentException("Unknown vertex id type: " + id);
                    })
                    .flatMap(id -> {
                        try {
                            return Stream.of((Vertex) this.createVertex(this.getBaseGraph().getNodeById(id)));
                        } catch (final RuntimeException e) {
                            if (Neo4jHelper.isNotFound(e)) return Stream.empty();
                            throw e;
                        }
                    }).iterator();
        }
    }

    @Override
    public Iterator<Edge> edges(final Object... edgeIds) {
        this.tx().readWrite();
        if (0 == edgeIds.length) {
            final Predicate<Neo4jRelationship> relationshipPredicate = this.getRelationshipPredicate();
            return IteratorUtils.stream(this.getBaseGraph().allRelationships())
                    .filter(relationship -> !this.checkElementsInTransaction || !Neo4jHelper.isDeleted(relationship))
                    .filter(relationshipPredicate)
                    .map(relationship -> (Edge) this.createEdge(relationship)).iterator();
        } else {
            ElementHelper.validateMixedElementIds(Edge.class, edgeIds);
            return Stream.of(edgeIds)
                    .map(id -> {
                        if (id instanceof Number)
                            return ((Number) id).longValue();
                        else if (id instanceof String)
                            return Long.valueOf(id.toString());
                        else if (id instanceof Neo4jEdge) {
                            return (Long) ((Neo4jEdge) id).id();
                        } else
                            throw new IllegalArgumentException("Unknown vertex id type: " + id);
                    })
                    .flatMap(id -> {
                        try {
                            return Stream.of((Edge) this.createEdge(this.getBaseGraph().getRelationshipById(id)));
                        } catch (final RuntimeException e) {
                            if (Neo4jHelper.isNotFound(e)) return Stream.empty();
                            throw e;
                        }
                    }).iterator();
        }
    }


    /**
     * Construct a Neo4jGraph instance using an existing Neo4j raw instance.
     */
    /*public static Neo4jGraph open(final Neo4jGraphAPI baseGraph) {
        return new Neo4jGraph(Optional.ofNullable(baseGraph).orElseThrow(() -> Graph.Exceptions.argumentCanNotBeNull("baseGraph")));
    }*/
    @Override
    public <C extends GraphComputer> C compute(final Class<C> graphComputerClass) {
        throw Graph.Exceptions.graphComputerNotSupported();
    }

    @Override
    public GraphComputer compute() {
        throw Graph.Exceptions.graphComputerNotSupported();
    }

    @Override
    public Transaction tx() {
        return this.neo4jTransaction;
    }

    @Override
    public Variables variables() {
        return this.neo4jGraphVariables;
    }

    @Override
    public Configuration configuration() {
        return this.configuration;
    }


    /**
     * This implementation of {@code close} will also close the current transaction on the the thread, but it
     * is up to the caller to deal with dangling transactions in other threads prior to calling this method.
     */
    @Override
    public void close() throws Exception {
        this.tx().close();
        if (this.baseGraph != null) this.baseGraph.shutdown();
    }

    public String toString() {
        return StringFactory.graphString(this, baseGraph.toString());
    }

    @Override
    public Features features() {
        return features;
    }

    @Override
    public Neo4jGraphAPI getBaseGraph() {
        return this.baseGraph;
    }

    /**
     * Neo4j's transactions are not consistent between the graph and the graph
     * indices. Moreover, global graph operations are not consistent. For
     * example, if a vertex is removed and then an index is queried in the same
     * transaction, the removed vertex can be returned. This method allows the
     * developer to turn on/off a Neo4jGraph 'hack' that ensures transactional
     * consistency. The default behavior for Neo4jGraph is {@code true}.
     *
     * @param checkElementsInTransaction check whether an element is in the transaction between
     *                                   returning it
     */
    public void checkElementsInTransaction(final boolean checkElementsInTransaction) {
        this.checkElementsInTransaction = checkElementsInTransaction;
    }

    /**
     * Execute the Cypher query and get the result set as a {@link GraphTraversal}.
     *
     * @param query the Cypher query to execute
     * @return a fluent Gremlin traversal
     */
    public <S, E> GraphTraversal<S, E> cypher(final String query) {
        return cypher(query, Collections.emptyMap());
    }

    /**
     * Execute the Cypher query with provided parameters and get the result set as a {@link GraphTraversal}.
     *
     * @param query      the Cypher query to execute
     * @param parameters the parameters of the Cypher query
     * @return a fluent Gremlin traversal
     */
    public <S, E> GraphTraversal<S, E> cypher(final String query, final Map<String, Object> parameters) {
        this.tx().readWrite();
        final GraphTraversal.Admin<S, E> traversal = new DefaultGraphTraversal<>(this);
        Iterator result = this.baseGraph.execute(query, parameters);
        traversal.addStep(new CypherStartStep(traversal, query, new Neo4jCypherIterator<S>(result, this)));
        return traversal;
    }

    public Iterator<Map<String, Object>> execute(String query, Map<String, Object> params) {
        return new Neo4jCypherIterator(baseGraph.execute(query, params), this);
    }

    class Neo4jTransaction extends AbstractTransaction {

        protected final ThreadLocal<Neo4jTx> threadLocalTx = ThreadLocal.withInitial(() -> null);

        public Neo4jTransaction() {
            super(Neo4jGraph.this);
        }

        @Override
        public void doOpen() {
            threadLocalTx.set(getBaseGraph().tx());
        }

        @Override
        public void doCommit() throws TransactionException {
            try {
                threadLocalTx.get().success();
            } catch (Exception ex) {
                throw new TransactionException(ex);
            } finally {
                threadLocalTx.get().close();
                threadLocalTx.remove();
            }
        }

        @Override
        public void doRollback() throws TransactionException {
            try {
//                javax.transaction.Transaction t = transactionManager.getTransaction();
//                if (null == t || t.getStatus() == javax.transaction.Status.STATUS_ROLLEDBACK)
//                    return;

                threadLocalTx.get().failure();
            } catch (Exception e) {
                throw new TransactionException(e);
            } finally {
                threadLocalTx.get().close();
                threadLocalTx.remove();
            }
        }

        @Override
        public boolean isOpen() {
            return (threadLocalTx.get() != null);
        }
    }

    public class Neo4jGraphFeatures implements Features {
        protected GraphFeatures graphFeatures = new Neo4jGraphGraphFeatures();
        protected VertexFeatures vertexFeatures = new Neo4jVertexFeatures();
        protected EdgeFeatures edgeFeatures = new Neo4jEdgeFeatures();

        @Override
        public GraphFeatures graph() {
            return graphFeatures;
        }

        @Override
        public VertexFeatures vertex() {
            return vertexFeatures;
        }

        @Override
        public EdgeFeatures edge() {
            return edgeFeatures;
        }

        @Override
        public String toString() {
            return StringFactory.featureString(this);
        }

        public class Neo4jGraphGraphFeatures implements GraphFeatures {

            private VariableFeatures variableFeatures = new Neo4jGraphVariables.Neo4jVariableFeatures();

            Neo4jGraphGraphFeatures() {
            }

            @Override
            public boolean supportsComputer() {
                return false;
            }

            @Override
            public VariableFeatures variables() {
                return variableFeatures;
            }

            @Override
            public boolean supportsThreadedTransactions() {
                return false;
            }
        }

        public class Neo4jVertexFeatures extends Neo4jElementFeatures implements VertexFeatures {

            private final VertexPropertyFeatures vertexPropertyFeatures = new Neo4jVertexPropertyFeatures();

            protected Neo4jVertexFeatures() {
            }

            @Override
            public VertexPropertyFeatures properties() {
                return vertexPropertyFeatures;
            }

            @Override
            public boolean supportsMetaProperties() {
                return Neo4jGraph.this.supportsMetaProperties;
            }

            @Override
            public boolean supportsMultiProperties() {
                return Neo4jGraph.this.supportsMultiProperties;
            }

            @Override
            public boolean supportsUserSuppliedIds() {
                return false;
            }
        }

        public class Neo4jEdgeFeatures extends Neo4jElementFeatures implements EdgeFeatures {

            private final EdgePropertyFeatures edgePropertyFeatures = new Neo4jEdgePropertyFeatures();

            Neo4jEdgeFeatures() {
            }

            @Override
            public EdgePropertyFeatures properties() {
                return edgePropertyFeatures;
            }
        }

        public class Neo4jElementFeatures implements ElementFeatures {

            Neo4jElementFeatures() {
            }

            @Override
            public boolean supportsUserSuppliedIds() {
                return false;
            }

            @Override
            public boolean supportsStringIds() {
                return false;
            }

            @Override
            public boolean supportsUuidIds() {
                return false;
            }

            @Override
            public boolean supportsAnyIds() {
                return false;
            }

            @Override
            public boolean supportsCustomIds() {
                return false;
            }
        }

        public class Neo4jVertexPropertyFeatures implements VertexPropertyFeatures {

            Neo4jVertexPropertyFeatures() {
            }

            @Override
            public boolean supportsMapValues() {
                return false;
            }

            @Override
            public boolean supportsMixedListValues() {
                return false;
            }

            @Override
            public boolean supportsSerializableValues() {
                return false;
            }

            @Override
            public boolean supportsUniformListValues() {
                return false;
            }

            @Override
            public boolean supportsUserSuppliedIds() {
                return false;
            }

            @Override
            public boolean supportsAnyIds() {
                return false;
            }
        }

        public class Neo4jEdgePropertyFeatures implements EdgePropertyFeatures {

            Neo4jEdgePropertyFeatures() {
            }

            @Override
            public boolean supportsMapValues() {
                return false;
            }

            @Override
            public boolean supportsMixedListValues() {
                return false;
            }

            @Override
            public boolean supportsSerializableValues() {
                return false;
            }

            @Override
            public boolean supportsUniformListValues() {
                return false;
            }
        }
    }
}