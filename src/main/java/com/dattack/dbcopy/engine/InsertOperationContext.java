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

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dattack.dbcopy.beans.InsertOperationBean;
import com.dattack.jtoolbox.jdbc.JDBCUtils;
import com.dattack.jtoolbox.jdbc.JNDIDataSource;
import com.dattack.jtoolbox.jdbc.NamedParameterPreparedStatement;

/**
 * Executes the INSERT operations.
 *
 * @author cvarela
 * @since 0.1
 */
class InsertOperationContext {

    private final static Logger LOGGER = LoggerFactory.getLogger(InsertOperationContext.class);

    private final InsertOperationBean bean;
    private Connection connection;
    private NamedParameterPreparedStatement preparedStatement;
    private int row;

    public InsertOperationContext(final InsertOperationBean bean) {
        this.bean = bean;
        this.row = 0;
    }

    private int addBatch() throws SQLException {
        getPreparedStatement().addBatch();
        row++;
        int insertedRows = 0;
        if (row % bean.getBatchSize() == 0) {
            LOGGER.info("Inserted rows: {}", row);
            insertedRows = executeBatch();
        }
        return insertedRows;
    }

    private int executeBatch() throws SQLException {

        int insertedRows = 0;
        try {
            final int[] batchResult = getPreparedStatement().executeBatch();

            for (int i = 0; i < batchResult.length; i++) {
                insertedRows += batchResult[i] > 0 ? batchResult[i] : 0;
            }

        } catch (final BatchUpdateException e) {
            LOGGER.warn("Batch operation failed: {} (SQLSTATE: {}, Error code: {}, Executed statements: {})",
                    e.getMessage(), e.getSQLState(), e.getErrorCode(), e.getUpdateCounts().length);
        }

        getConnection().commit();
        return insertedRows;
    }

    public int flush() throws SQLException {

        int insertedRows = 0;
        if (row % bean.getBatchSize() != 0) {
            insertedRows = executeBatch();
        }

        JDBCUtils.closeQuietly(preparedStatement);
        JDBCUtils.closeQuietly(connection);

        return insertedRows;
    }

    private synchronized Connection getConnection() throws SQLException {
        if (connection == null) {
            connection = new JNDIDataSource(bean.getDatasource()).getConnection();
            if (bean.getBatchSize() > 0) {
                connection.setAutoCommit(false);
            }
        }
        return connection;
    }

    private synchronized NamedParameterPreparedStatement getPreparedStatement() throws SQLException {
        if (preparedStatement == null) {
            preparedStatement = NamedParameterPreparedStatement.build(getConnection(), bean.getSql());
        }
        return preparedStatement;
    }

    public int insert(final ResultSet resultSet) throws SQLException {

        populateStatement(resultSet);

        if (bean.getBatchSize() > 0) {
            return addBatch();
        }
        return getPreparedStatement().executeUpdate();
    }

    private void populateStatement(final ResultSet resultSet) throws SQLException {
        for (int columnIndex = 1; columnIndex <= resultSet.getMetaData().getColumnCount(); columnIndex++) {
            if (getPreparedStatement().hasNamedParameter(resultSet.getMetaData().getColumnName(columnIndex))) {
                final Object value = resultSet.getObject(columnIndex);
                if (resultSet.wasNull()) {
                    getPreparedStatement().setNull(resultSet.getMetaData().getColumnName(columnIndex),
                            resultSet.getMetaData().getColumnType(columnIndex));
                } else {
                    getPreparedStatement().setObject(resultSet.getMetaData().getColumnName(columnIndex), value);
                }
            } else {
                LOGGER.warn("Unknown parameter {}", resultSet.getMetaData().getColumnName(columnIndex));
            }
        }
    }
}
