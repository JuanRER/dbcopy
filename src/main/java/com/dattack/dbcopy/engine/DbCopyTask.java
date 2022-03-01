/*
 * Copyright (c) 2017, The Dattack team (http://www.dattack.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dattack.dbcopy.engine;

import com.dattack.dbcopy.beans.DbcopyJobBean;
import com.dattack.dbcopy.engine.export.ExportOperationFactory;
import com.dattack.dbcopy.engine.export.ExportOperationFactoryProducer;
import com.dattack.jtoolbox.commons.configuration.ConfigurationUtil;
import com.dattack.jtoolbox.jdbc.JNDIDataSource;
import com.dattack.jtoolbox.jdbc.internal.ProxyStatement;
import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.sql.DataSource;

/**
 * Represents a task that is part of an executed job.
 *
 * @author cvarela
 * @since 0.1
 */
class DbCopyTask implements Callable<DbCopyTaskResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DbCopyTask.class);

    private final transient AbstractConfiguration configuration;
    private final transient DbcopyJobBean dbcopyJobBean;
    private final transient DbCopyTaskResult taskResult;

    public DbCopyTask(final DbcopyJobBean dbcopyJobBean, final AbstractConfiguration configuration,
            final DbCopyTaskResult taskResult) {
        this.dbcopyJobBean = dbcopyJobBean;
        this.configuration = configuration;
        this.taskResult = taskResult;
    }

    @Override
    public DbCopyTaskResult call() {

        taskResult.start();

        LOGGER.info("DBCopy task started {} (Thread: {})", taskResult.getTaskName(), Thread.currentThread().getName());

        try (Connection selectConn = getDataSource().getConnection(); //
             Statement selectStmt = createStatement(selectConn); //
             ResultSet resultSet = selectStmt.executeQuery(compileSql()) //
        ) {

            final DataTransfer dataTransfer = new DataTransfer(resultSet, taskResult, //
                                                               dbcopyJobBean.getSelectBean().getFetchSize());

            final List<Future<?>> futureList = new ArrayList<>();

            try (ExecutionController insertController = createInsertController(); //
                 ExecutionController exportController = createExportController()) {

                futureList.addAll(createInsertFutures(dataTransfer, insertController));
                futureList.addAll(createExportFutures(dataTransfer, exportController));

                showFutures(futureList);
            }
            LOGGER.info("DBCopy task finished {}", taskResult.getTaskName());

        } catch (final SQLException | URISyntaxException | IOException e) {
            LOGGER.error("DBCopy task failed {}: {}", taskResult.getTaskName(), e);
            taskResult.setException(e);
        }

        taskResult.end();
        return taskResult;
    }

    private ExecutionController createInsertController() {

        ExecutionController controller = null;
        if (dbcopyJobBean.getInsertBean() != null) {
            controller = new ExecutionController(taskResult.getTaskName() + "-Insert",
                                                 dbcopyJobBean.getInsertBean().getParallel());
        }
        return controller;
    }

    private ExecutionController createExportController() {

        ExecutionController controller = null;
        if (dbcopyJobBean.getExportBean() != null) {
            controller = new ExecutionController(taskResult.getTaskName() + "-Export",
                                                 dbcopyJobBean.getExportBean().getParallel());
        }
        return controller;
    }

    private DataSource getDataSource() {
        return new JNDIDataSource(
                ConfigurationUtil.interpolate(dbcopyJobBean.getSelectBean().getDatasource(), configuration));
    }

    private Statement createStatement(final Connection connection) throws SQLException {
        final Statement stmt = connection.createStatement();
        if (dbcopyJobBean.getSelectBean().getFetchSize() > 0) {
            stmt.setFetchSize(dbcopyJobBean.getSelectBean().getFetchSize());
        }

        if (stmt.isWrapperFor(ProxyStatement.class)) {
            ProxyStatement<?> proxyStatement = stmt.unwrap(ProxyStatement.class);
            proxyStatement.setLobPrefetchSize(16_000);
            LOGGER.info("{}.LobPrefetchSize: {}", proxyStatement.getClass().getName(),
                        proxyStatement.getLobPrefetchSize());
        }

        return stmt;
    }

    private String compileSql() throws URISyntaxException, IOException {

        String sql = StringUtils.trimToEmpty(dbcopyJobBean.getSelectBean().getSql());
        if (StringUtils.startsWithIgnoreCase(sql, "file://")) {
            sql = new String(Files.readAllBytes(Paths.get(new URI(sql))), StandardCharsets.UTF_8);
        }

        final String compiledSql = ConfigurationUtil.interpolate(sql, configuration);
        LOGGER.info("Executing SQL: {}", compiledSql);
        return compiledSql;
    }

    private List<Future<?>> createInsertFutures(final DataTransfer dataTransfer, ExecutionController controller) {

        final List<Future<?>> futureList = new ArrayList<>();

        if (controller != null) {

            for (int i = 0; i < dbcopyJobBean.getInsertBean().getParallel(); i++) {
                futureList.add(controller.submit(new InsertOperation(dbcopyJobBean.getInsertBean(), //NOPMD
                                                                     dataTransfer, configuration, taskResult)));
            }

            controller.shutdown();
        }

        return futureList;
    }

    private List<Future<?>> createExportFutures(final DataTransfer dataTransfer, ExecutionController controller) {

        final List<Future<?>> futureList = new ArrayList<>();

        if (controller != null) {

            final ExportOperationFactory factory =
                    ExportOperationFactoryProducer.getFactory(dbcopyJobBean.getExportBean(), configuration);

            for (int i = 0; i < dbcopyJobBean.getExportBean().getParallel(); i++) {
                futureList.add(controller.submit(factory.createTask(dataTransfer, taskResult)));
            }

            controller.shutdown();
        }

        return futureList;
    }

    private void showFutures(final List<Future<?>> futureList) {

        for (final Future<?> future : futureList) {
            try {
                LOGGER.info("Future result: {}", future.get());
            } catch (final InterruptedException | ExecutionException e) {
                LOGGER.warn("Error getting computed result from Future object", e);
                taskResult.setException(e);
            }
        }
    }
}
