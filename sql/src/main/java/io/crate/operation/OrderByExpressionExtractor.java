/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation;

import io.crate.analyze.OrderBy;
import io.crate.analyze.symbol.Reference;
import io.crate.analyze.symbol.Symbol;
import io.crate.metadata.Functions;
import io.crate.operation.reference.doc.lucene.OrderByCollectorExpression;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class OrderByExpressionExtractor {

    private final InnerVisitor<Input<?>> visitor;

    @Inject
    public OrderByExpressionExtractor(Functions functions) {
        visitor = new InnerVisitor<>(functions);
    }

    public List<OrderByCollectorExpression> extractExpressions(OrderBy orderBy) {
        Context ctx = new Context(orderBy);
        for (Symbol symbol : orderBy.orderBySymbols()) {
            visitor.process(symbol, ctx);
        }
        return ctx.expressions;
    }

    public static class Context {
        final List<Symbol> orderBySymbols;
        final List<OrderByCollectorExpression> expressions = new ArrayList<>();
        final OrderBy orderBy;

        public Context(OrderBy orderBy) {
            orderBySymbols = orderBy.orderBySymbols();
            this.orderBy = orderBy;
        }
    }

    private static class InnerVisitor<T extends Input<?>> extends BaseImplementationSymbolVisitor<Context> {

        public InnerVisitor(Functions functions) {
            super(functions);
        }

        @Override
        public Input<?> visitReference(Reference symbol, Context context) {
            if (context.orderBySymbols.contains(symbol)) {
                OrderByCollectorExpression orderByCollectorExpression = new OrderByCollectorExpression(symbol, context.orderBy);
                context.expressions.add(orderByCollectorExpression);
                return orderByCollectorExpression;
            }
            return super.visitReference(symbol, context);
        }
    }
}
