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

package io.crate.analyze;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.crate.exceptions.PartitionUnknownException;
import io.crate.exceptions.SchemaUnknownException;
import io.crate.exceptions.TableUnknownException;
import io.crate.metadata.PartitionName;
import io.crate.metadata.Schemas;
import io.crate.metadata.TableIdent;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.settings.BoolSetting;
import io.crate.metadata.table.TableInfo;
import io.crate.sql.tree.CreateSnapshot;
import io.crate.sql.tree.Expression;
import io.crate.sql.tree.QualifiedName;
import io.crate.sql.tree.Table;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.SnapshotId;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;

import java.util.*;

@Singleton
public class CreateSnapshotStatementAnalyzer extends AbstractRepositoryDDLAnalyzer<CreateSnapshotAnalyzedStatement, CreateSnapshot> {

    private static final ESLogger LOGGER = Loggers.getLogger(CreateSnapshotStatementAnalyzer.class);
    private final Schemas schemas;

    public static final BoolSetting PARTIAL = new BoolSetting() {
        @Override
        public String name() {
            return "partial";
        }

        @Override
        public Boolean defaultValue() {
            return Boolean.FALSE;
        }

        @Override
        public boolean isRuntime() {
            return false;
        }
    };
    public static final BoolSetting IGNORE_UNAVAILABLE = new BoolSetting() {
        @Override
        public String name() {
            return "ignore_unavailable";
        }

        @Override
        public Boolean defaultValue() {
            return Boolean.FALSE;
        }

        @Override
        public boolean isRuntime() {
            return false;
        }
    };
    public static final BoolSetting WAIT_FOR_COMPLETION = new BoolSetting() {
        @Override
        public String name() {
            return "wait_for_completion";
        }

        @Override
        public Boolean defaultValue() {
            return Boolean.FALSE;
        }

        @Override
        public boolean isRuntime() {
            return false;
        }
    };


    private static final ImmutableMap<String, SettingsApplier> SETTINGS = ImmutableMap.<String, SettingsApplier>builder()
            .put(PARTIAL.name(), new SettingsAppliers.BooleanSettingsApplier(PARTIAL))
            .put(IGNORE_UNAVAILABLE.name(), new SettingsAppliers.BooleanSettingsApplier(IGNORE_UNAVAILABLE))
            .put(WAIT_FOR_COMPLETION.name(), new SettingsAppliers.BooleanSettingsApplier(WAIT_FOR_COMPLETION))
            .build();



    @Inject
    public CreateSnapshotStatementAnalyzer(ClusterService clusterService, Schemas schemas) {
        super(clusterService);
        this.schemas = schemas;
    }

    @Override
    public CreateSnapshotAnalyzedStatement visitCreateSnapshot(CreateSnapshot node, Analysis analysis) {
        Optional<QualifiedName> repositoryName = node.name().getPrefix();
        // validate repository
        Preconditions.checkArgument(repositoryName.isPresent(), "Snapshot must be specified by \"<repository_name>\".\"<snapshot_name>\"");
        Preconditions.checkArgument(repositoryName.get().getParts().size() == 1,
                String.format(Locale.ENGLISH, "Invalid repository name '%s'", repositoryName.get()));
        failIfRepositoryDoesNotExist(repositoryName.get().toString());

        // snapshot existence in repo is validated upon execution
        String snapshotName = node.name().getSuffix();
        SnapshotId snapshotId = new SnapshotId(repositoryName.get().toString(), snapshotName);

        // validate and extract settings
        Settings settings = ImmutableSettings.EMPTY;
        if (node.properties().isPresent()) {
            ImmutableSettings.Builder builder = ImmutableSettings.builder();

            // apply defaults
            for (Map.Entry<String, SettingsApplier> entry : SETTINGS.entrySet()) {
                builder.put(entry.getValue().getDefault());
            }
            // apply given config
            for (Map.Entry<String, Expression> entry : node.properties().get().properties().entrySet()) {
                SettingsApplier settingsApplier = SETTINGS.get(entry.getKey());
                settingsApplier.apply(builder, analysis.parameterContext().parameters(), entry.getValue());
            }
            settings = builder.build();
        }

        boolean ignoreUnavailable = settings.getAsBoolean(IGNORE_UNAVAILABLE.name(), IGNORE_UNAVAILABLE.defaultValue());

        // iterate tables
        if (node.tableList().isPresent()) {
            List<Table> tableList = node.tableList().get();
            Set<String> snapshotIndices = new HashSet<>(tableList.size());
            boolean includeMetadata = false;
            for (Table table : tableList) {
                TableInfo tableInfo;
                try {
                    tableInfo = schemas.getTableInfo(TableIdent.of(table, analysis.parameterContext().defaultSchema()));
                } catch (SchemaUnknownException|TableUnknownException e) {
                    if (ignoreUnavailable) {
                        LOGGER.info("ignoring: {}", e.getMessage());
                        continue;
                    } else {
                        throw e;
                    }
                }
                if (!(tableInfo instanceof DocTableInfo)) {
                    throw new IllegalArgumentException(
                            String.format(Locale.ENGLISH, "Cannot create snapshot of tables in schema '%s'", tableInfo.ident().schema()));
                }
                DocTableInfo docTableInfo = (DocTableInfo)tableInfo;
                if (table.partitionProperties().isEmpty()) {
                    if (docTableInfo.isPartitioned()) {
                        includeMetadata = true;
                    }
                    snapshotIndices.addAll(Arrays.asList(docTableInfo.concreteIndices()));
                } else {
                    PartitionName partitionName = PartitionPropertiesAnalyzer.toPartitionName(
                            docTableInfo,
                            table.partitionProperties(),
                            analysis.parameterContext().parameters()
                    );
                    if (!docTableInfo.partitions().contains(partitionName)) {
                        if (!ignoreUnavailable) {
                            throw new PartitionUnknownException(tableInfo.ident().fqn(), partitionName.ident());
                        } else {
                            LOGGER.info("ignoring unknown partition of table '{}' with ident '{}'", partitionName.tableIdent(), partitionName.ident());
                        }
                    } else {
                        // we don't include metadata when snapshotting partitions
                        // thus, they cant be recreated without a partitioned table or
                        // turned into a partitioned table with itself as single partition again
                        snapshotIndices.add(partitionName.asIndexName());
                    }
                }
            }
            return CreateSnapshotAnalyzedStatement.forTables(snapshotId, settings, ImmutableList.copyOf(snapshotIndices), includeMetadata);
        } else {
            return CreateSnapshotAnalyzedStatement.all(snapshotId, settings);
        }
    }
}
