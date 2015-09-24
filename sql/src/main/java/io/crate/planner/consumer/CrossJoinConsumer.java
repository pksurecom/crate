/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.planner.consumer;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ObjectArrays;
import io.crate.Constants;
import io.crate.analyze.*;
import io.crate.analyze.relations.*;
import io.crate.exceptions.ValidationException;
import io.crate.metadata.OutputName;
import io.crate.operation.projectors.TopN;
import io.crate.planner.node.NoopPlannedAnalyzedRelation;
import io.crate.planner.node.dql.DQLPlanNode;
import io.crate.planner.node.dql.MergePhase;
import io.crate.planner.node.dql.join.NestedLoop;
import io.crate.planner.node.dql.join.NestedLoopPhase;
import io.crate.planner.projection.Projection;
import io.crate.planner.projection.TopNProjection;
import io.crate.planner.symbol.*;
import io.crate.sql.tree.QualifiedName;
import io.crate.types.DataType;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.Nullable;

import java.util.*;


public class CrossJoinConsumer implements Consumer {

    private final static InputColumnProducer INPUT_COLUMN_PRODUCER = new InputColumnProducer();

    private final Visitor visitor;

    public CrossJoinConsumer(ClusterService clusterService,
                             AnalysisMetaData analysisMetaData,
                             ConsumingPlanner consumingPlanner) {
        visitor = new Visitor(clusterService, analysisMetaData, consumingPlanner);
    }

    @Override
    public PlannedAnalyzedRelation consume(AnalyzedRelation rootRelation, ConsumerContext context) {
        return visitor.process(rootRelation, context);
    }

    private static class Visitor extends AnalyzedRelationVisitor<ConsumerContext, PlannedAnalyzedRelation> {

        private final ClusterService clusterService;
        private final AnalysisMetaData analysisMetaData;
        private final ConsumingPlanner consumingPlanner;
        private final SubRelationConverter subRelationConverter;

        public Visitor(ClusterService clusterService, AnalysisMetaData analysisMetaData, ConsumingPlanner consumingPlanner) {
            this.clusterService = clusterService;
            this.analysisMetaData = analysisMetaData;
            this.consumingPlanner = consumingPlanner;
            subRelationConverter = new SubRelationConverter(analysisMetaData);
        }

        @Override
        protected PlannedAnalyzedRelation visitAnalyzedRelation(AnalyzedRelation relation, ConsumerContext context) {
            return null;
        }

        @Override
        public PlannedAnalyzedRelation visitMultiSourceSelect(MultiSourceSelect statement, ConsumerContext context) {
            if (isUnsupportedStatement(statement, context)) return null;
            if (statement.querySpec().where().noMatch()) {
                return new NoopPlannedAnalyzedRelation(statement, context.plannerContext().jobId());
            }

            final Map<Object, Integer> relationOrder = getRelationOrder(statement);
            List<QueriedTableRelation> queriedTables = new ArrayList<>();
            for (Map.Entry<QualifiedName, AnalyzedRelation> entry : statement.sources().entrySet()) {
                AnalyzedRelation analyzedRelation = entry.getValue();
                QueriedTableRelation queriedTable;
                try {
                    queriedTable = subRelationConverter.process(analyzedRelation, statement);
                } catch (ValidationException e) {
                    context.validationException(e);
                    return null;
                }
                queriedTables.add(queriedTable);
            }

            WhereClause where = statement.querySpec().where();
            OrderBy orderBy = statement.querySpec().orderBy();
            if (where.hasQuery() && !(where.query() instanceof Literal)) {
                throw new UnsupportedOperationException("JOIN condition in the WHERE clause is not supported");
            }

            boolean hasRemainingOrderBy = orderBy != null && orderBy.isSorted();
            if (hasRemainingOrderBy) {
                for (QueriedTableRelation queriedTable : queriedTables) {
                    queriedTable.querySpec().limit(null);
                    queriedTable.querySpec().offset(TopN.NO_OFFSET);
                }
            }
            sortQueriedTables(relationOrder, queriedTables);

            // TODO: replace references with docIds.. and add fetch projection

            NestedLoop nl = toNestedLoop(queriedTables, context);
            List<Symbol> queriedTablesOutputs = getAllOutputs(queriedTables);

            /**
             * TopN for:
             *
             * #1 Reorder
             *      need to always use topN to re-order outputs,
             *
             *      e.g. select t1.name, t2.name, t1.id
             *
             *      left outputs:
             *          [ t1.name, t1.id ]
             *
             *      right outputs:
             *          [ t2.name ]
             *
             *      left + right outputs:
             *          [ t1.name, t1.id, t2.name]
             *
             *      final outputs (topN):
             *          [ in(0), in(2), in(1)]
             *
             * #2 Execute functions that reference more than 1 relations
             *
             *      select t1.x + t2.x
             *
             *      left: x
             *      right: x
             *
             *      topN:  add(in(0), in(1))
             *
             * #3 Apply Limit and remaining Order by
             */
            List<Symbol> postOutputs = replaceFieldsWithInputColumns(statement.querySpec().outputs(), queriedTablesOutputs);
            TopNProjection topNProjection;

            int rootLimit = MoreObjects.firstNonNull(statement.querySpec().limit(), Constants.DEFAULT_SELECT_LIMIT);
            if (orderBy != null && orderBy.isSorted()) {
                 topNProjection = new TopNProjection(
                         rootLimit,
                         statement.querySpec().offset(),
                         replaceFieldsWithInputColumns(orderBy.orderBySymbols(), queriedTablesOutputs),
                         orderBy.reverseFlags(),
                         orderBy.nullsFirst()
                 );
            } else {
                topNProjection = new TopNProjection(rootLimit, statement.querySpec().offset());
            }
            topNProjection.outputs(postOutputs);
            nl.addProjection(topNProjection);
            return nl;
        }

        private List<Symbol> getAllOutputs(Collection<QueriedTableRelation> queriedTables) {
            ImmutableList.Builder<Symbol> builder = ImmutableList.builder();
            for (QueriedTableRelation table : queriedTables) {
                builder.addAll(table.fields());
            }
            return builder.build();
        }
        /**
         * generates new symbols that will use InputColumn symbols to point to the output of the given relations
         *
         * @param statementOutputs: [ u1.id,  add(u1.id, u2.id) ]
         * @param inputSymbols:
         * {
         *     [ u1.id, u2.id ],
         * }
         *
         * @return [ in(0), add( in(0), in(1) ) ]
         */
        private List<Symbol> replaceFieldsWithInputColumns(Collection<? extends Symbol> statementOutputs,
                                                           List<Symbol> inputSymbols) {
            List<Symbol> result = new ArrayList<>();
            for (Symbol statementOutput : statementOutputs) {
                result.add(replaceFieldsWithInputColumns(statementOutput, inputSymbols));
            }
            return result;
        }

        private Symbol replaceFieldsWithInputColumns(Symbol symbol, List<Symbol> inputSymbols) {
            return INPUT_COLUMN_PRODUCER.process(symbol, new InputColumnProducerContext(inputSymbols));
        }

        /**
         * build a nested loop tree:
         *
         * E.g.:
         *
         * t1, t2, t3
         *  \  /   /
         *  \ /   /
         *  NL   /
         *   \  /
         *    NL
         */
        private NestedLoop toNestedLoop(List<QueriedTableRelation> queriedTables, ConsumerContext context) {
            assert queriedTables.size() > 1 : "must have at least 2 tables to create a nestedLoop";

            Iterator<QueriedTableRelation> iterator = queriedTables.iterator();


            Set<String> localExecutionNodes = ImmutableSet.of(clusterService.localNode().id());
            UUID jobId = context.plannerContext().jobId();

            NestedLoop nl = null;
            QueriedTableRelation leftRelation = null;
            QueriedTableRelation rightRelation = null;

            while (iterator.hasNext()) {
                PlannedAnalyzedRelation leftPlan;
                List<Symbol> leftOutputs;
                OrderBy leftOrderBy;

                if (nl == null) {
                    leftRelation = iterator.next();
                    leftOutputs = leftRelation.querySpec().outputs();
                    leftOrderBy = leftRelation.querySpec().orderBy();
                    leftPlan = consumingPlanner.plan(leftRelation, context);
                    assert leftPlan != null;
                } else {
                    leftOutputs = new ArrayList<>(leftRelation.querySpec().outputs());
                    leftOutputs.addAll(rightRelation.querySpec().outputs());
                    leftOrderBy = mergedOrderBy(leftRelation, rightRelation);
                    leftPlan = nl;
                }
                MergePhase leftMerge = mergePhase(
                        context,
                        localExecutionNodes,
                        leftPlan.resultNode(),
                        leftOutputs,
                        leftOrderBy
                );
                rightRelation = iterator.next();
                PlannedAnalyzedRelation rightPlan = consumingPlanner.plan(rightRelation, context);
                assert rightPlan != null;
                MergePhase rightMerge = mergePhase(
                        context,
                        localExecutionNodes,
                        rightPlan.resultNode(),
                        rightRelation.querySpec().outputs(),
                        rightRelation.querySpec().orderBy()
                );
                NestedLoopPhase nestedLoopPhase = new NestedLoopPhase(
                        jobId,
                        context.plannerContext().nextExecutionPhaseId(),
                        "nested-loop",
                        ImmutableList.<Projection>of(),
                        leftMerge,
                        getOutputTypes(leftMerge, leftOutputs),
                        rightMerge,
                        getOutputTypes(rightMerge, rightRelation.querySpec().outputs()),
                        localExecutionNodes
                );
                nl = new NestedLoop(jobId, leftPlan, rightPlan, nestedLoopPhase, true);
            }
            return nl;
        }

        /**
         * get the output types from either the mergePhase if not-null or from the symbols
         */
        private List<DataType> getOutputTypes(@Nullable MergePhase mergePhase, List<Symbol> symbols) {
            if (mergePhase == null) {
                return Symbols.extractTypes(symbols);
            } else {
                return mergePhase.outputTypes();
            }
        }

        @Nullable
        private MergePhase mergePhase(ConsumerContext context,
                                      Set<String> localExecutionNodes,
                                      DQLPlanNode previousPhase,
                                      List<Symbol> previousOutputs,
                                      @Nullable OrderBy orderBy) {
            if (previousPhase.executionNodes().isEmpty()
                || previousPhase.executionNodes().equals(localExecutionNodes)) {
                // if the nested loop is on the same node we don't need a mergePhase to receive requests
                // but can access the RowReceiver of the nestedLoop directly
                return null;
            }

            if (previousPhase instanceof MergePhase && previousPhase.executionNodes().isEmpty()) {
                ((MergePhase) previousPhase).executionNodes(localExecutionNodes);
            }
            MergePhase mergePhase;
            if (OrderBy.isSorted(orderBy)) {
                mergePhase = MergePhase.sortedMerge(
                        context.plannerContext().jobId(),
                        context.plannerContext().nextExecutionPhaseId(),
                        orderBy,
                        previousOutputs,
                        orderBy.orderBySymbols(),
                        ImmutableList.<Projection>of(),
                        previousPhase
                );
            } else {
                mergePhase = MergePhase.localMerge(
                        context.plannerContext().jobId(),
                        context.plannerContext().nextExecutionPhaseId(),
                        ImmutableList.<Projection>of(),
                        previousPhase
                );
            }
            mergePhase.executionNodes(localExecutionNodes);
            return mergePhase;
        }

        private void sortQueriedTables(final Map<Object, Integer> relationOrder, List<QueriedTableRelation> queriedTables) {
            Collections.sort(queriedTables, new Comparator<QueriedTableRelation>() {
                @Override
                public int compare(QueriedTableRelation o1, QueriedTableRelation o2) {
                    return Integer.compare(
                            MoreObjects.firstNonNull(relationOrder.get(o1.tableRelation()), Integer.MAX_VALUE),
                            MoreObjects.firstNonNull(relationOrder.get(o2.tableRelation()), Integer.MAX_VALUE));
                }
            });
        }

        /**
         * returns a map with the relation as keys and the values are their order in occurrence in the order by clause.
         *
         * e.g. select * from t1, t2, t3 order by t2.x, t3.y
         *
         * returns: {
         *     t2: 0                 (first)
         *     t3: 1                 (second)
         * }
         */
        private Map<Object, Integer> getRelationOrder(MultiSourceSelect statement) {
            OrderBy orderBy = statement.querySpec().orderBy();
            if (orderBy == null || !orderBy.isSorted()) {
                return Collections.emptyMap();
            }

            final Map<Object, Integer> orderByOrder = new IdentityHashMap<>();
            int idx = 0;
            for (Symbol orderBySymbol : orderBy.orderBySymbols()) {
                for (AnalyzedRelation analyzedRelation : statement.sources().values()) {
                    QuerySplitter.RelationCount relationCount = QuerySplitter.getRelationCount(analyzedRelation, orderBySymbol);
                    if (relationCount != null && relationCount.numOther == 0 && relationCount.numThis > 0 && !orderByOrder.containsKey(analyzedRelation)) {
                        orderByOrder.put(analyzedRelation, idx);
                    }
                }
                idx++;
            }
            return orderByOrder;
        }

        private boolean isUnsupportedStatement(MultiSourceSelect statement, ConsumerContext context) {
            if (statement.sources().size() < 2) {
                context.validationException(new ValidationException("At least 2 relations are required for a CROSS JOIN"));
                return true;
            }

            List<Symbol> groupBy = statement.querySpec().groupBy();
            if (groupBy != null && !groupBy.isEmpty()) {
                context.validationException(new ValidationException("GROUP BY on CROSS JOIN is not supported"));
                return true;
            }
            if (statement.querySpec().hasAggregates()) {
                context.validationException(new ValidationException("AGGREGATIONS on CROSS JOIN is not supported"));
                return true;
            }
            return false;
        }

        @Nullable
        private OrderBy mergedOrderBy(QueriedTableRelation left, QueriedTableRelation right) {
            OrderBy leftOrderBy = left.querySpec().orderBy();
            OrderBy rightOrderBy = right.querySpec().orderBy();

            if (leftOrderBy == null && rightOrderBy == null) {
                return null;
            } else if (leftOrderBy == null) {
                return rightOrderBy;
            } else if (rightOrderBy == null) {
                return leftOrderBy;
            }

            int orderBySize = leftOrderBy.orderBySymbols().size() + rightOrderBy.orderBySymbols().size();
            List<Symbol> orderBySymbols = new ArrayList<>(orderBySize);
            orderBySymbols.addAll(leftOrderBy.orderBySymbols());
            orderBySymbols.addAll(rightOrderBy.orderBySymbols());

            boolean[] reverseFlags = new boolean[orderBySize];
            System.arraycopy(leftOrderBy.reverseFlags(), 0, reverseFlags, 0, leftOrderBy.reverseFlags().length);
            System.arraycopy(rightOrderBy.reverseFlags(), 0, reverseFlags, leftOrderBy.reverseFlags().length, rightOrderBy.reverseFlags().length);
            Boolean[] nullsFirst = ObjectArrays.concat(leftOrderBy.nullsFirst(), rightOrderBy.nullsFirst(), Boolean.class);

            return new OrderBy(orderBySymbols, reverseFlags, nullsFirst);
        }

    }

    private static class InputColumnProducerContext {

        private List<Symbol> inputs;

        public InputColumnProducerContext(List<Symbol> inputs) {
            this.inputs = inputs;
        }
    }

    private static class InputColumnProducer extends SymbolVisitor<InputColumnProducerContext, Symbol> {

        @Override
        public Symbol visitFunction(Function function, InputColumnProducerContext context) {
            int idx = 0;
            for (Symbol input : context.inputs) {
                if (input.equals(function)) {
                    return new InputColumn(idx, input.valueType());
                }
                idx++;
            }
            List<Symbol> newArgs = new ArrayList<>(function.arguments().size());
            for (Symbol argument : function.arguments()) {
                newArgs.add(process(argument, context));
            }
            return new Function(function.info(), newArgs);
        }

        @Override
        public Symbol visitField(Field field, InputColumnProducerContext context) {
            int idx = 0;
            for (Symbol input : context.inputs) {
                if (input.equals(field)) {
                    return new InputColumn(idx, input.valueType());
                }
                idx++;
            }
            return field;
        }

        @Override
        public Symbol visitLiteral(Literal literal, InputColumnProducerContext context) {
            return literal;
        }
    }

    private static class SubRelationConverter extends AnalyzedRelationVisitor<MultiSourceSelect, QueriedTableRelation> {

        private static final QueriedTableFactory<QueriedTable, TableRelation> QUERIED_TABLE_FACTORY =
                new QueriedTableFactory<QueriedTable, TableRelation>() {
                    @Override
                    public QueriedTable create(TableRelation tableRelation, List<OutputName> outputNames, QuerySpec querySpec) {
                        return new QueriedTable(tableRelation, outputNames, querySpec);
                    }
                };
        private static final QueriedTableFactory<QueriedDocTable, DocTableRelation> QUERIED_DOC_TABLE_FACTORY =
                new QueriedTableFactory<QueriedDocTable, DocTableRelation>() {
                    @Override
                    public QueriedDocTable create(DocTableRelation tableRelation, List<OutputName> outputNames, QuerySpec querySpec) {
                        return new QueriedDocTable(tableRelation, outputNames, querySpec);
                    }
                };

        private final AnalysisMetaData analysisMetaData;

        public SubRelationConverter(AnalysisMetaData analysisMetaData) {
            this.analysisMetaData = analysisMetaData;
        }

        @Override
        public QueriedTableRelation visitTableRelation(TableRelation tableRelation,
                                                       MultiSourceSelect statement) {
            return newSubRelation(tableRelation, statement.querySpec(), QUERIED_TABLE_FACTORY);
        }

        @Override
        public QueriedTableRelation visitDocTableRelation(DocTableRelation tableRelation,
                                                          MultiSourceSelect statement) {
            return newSubRelation(tableRelation, statement.querySpec(), QUERIED_DOC_TABLE_FACTORY);
        }

        @Override
        protected QueriedTableRelation visitAnalyzedRelation(AnalyzedRelation relation,
                                                             MultiSourceSelect statement) {
            throw new ValidationException("CROSS JOIN with sub queries is not supported");
        }


        /**
         * Create a new concrete QueriedTableRelation implementation for the given
         * AbstractTableRelation implementation
         *
         * It will walk through all symbols from QuerySpec and pull-down any symbols that the
         * new QueriedTable can handle. The symbols that are pulled down from the original
         * querySpec will be replaced with symbols that point to a output/field of the
         * QueriedTableRelation.
         */
        private <QT extends QueriedTableRelation, TR extends AbstractTableRelation> QT newSubRelation(TR tableRelation,
                                                                                                      QuerySpec querySpec,
                                                                                                      QueriedTableFactory<QT, TR> factory) {
            RelationSplitter.SplitQuerySpecContext context = RelationSplitter.splitQuerySpec(tableRelation, querySpec);
            QT queriedTable = factory.create(tableRelation, context.outputNames(), context.querySpec());
            RelationSplitter.replaceFields(queriedTable, querySpec, context.querySpec());
            queriedTable.normalize(analysisMetaData);
            return queriedTable;
        }

        private interface QueriedTableFactory<QT extends QueriedTableRelation, TR extends AbstractTableRelation> {
            public QT create(TR tableRelation, List<OutputName> outputNames, QuerySpec querySpec);
        }

    }
}
