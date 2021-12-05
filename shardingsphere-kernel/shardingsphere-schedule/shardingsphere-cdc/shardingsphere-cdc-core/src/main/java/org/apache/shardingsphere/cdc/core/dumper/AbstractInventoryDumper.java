/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.cdc.core.dumper;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.cdc.core.CDCDataChangeType;
import org.apache.shardingsphere.cdc.core.channel.Channel;
import org.apache.shardingsphere.cdc.core.config.InventoryDumperConfiguration;
import org.apache.shardingsphere.cdc.core.datasource.DataSourceManager;
import org.apache.shardingsphere.cdc.core.datasource.MetaDataManager;
import org.apache.shardingsphere.cdc.core.exception.CDCException;
import org.apache.shardingsphere.cdc.core.position.CDCPosition;
import org.apache.shardingsphere.cdc.core.position.FinishedPosition;
import org.apache.shardingsphere.cdc.core.position.PlaceholderPosition;
import org.apache.shardingsphere.cdc.core.position.PrimaryKeyPosition;
import org.apache.shardingsphere.cdc.core.record.Column;
import org.apache.shardingsphere.cdc.core.record.DataRecord;
import org.apache.shardingsphere.cdc.core.record.FinishedRecord;
import org.apache.shardingsphere.cdc.core.record.Record;
import org.apache.shardingsphere.infra.config.datasource.typed.StandardJDBCDataSourceConfiguration;
import org.apache.shardingsphere.infra.config.datasource.typed.TypedDataSourceConfiguration;
import org.apache.shardingsphere.infra.metadata.schema.model.TableMetaData;
import org.apache.shardingsphere.schedule.core.executor.AbstractLifecycleExecutor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Abstract JDBC dumper implement.
 */
@Slf4j
public abstract class AbstractInventoryDumper extends AbstractLifecycleExecutor implements InventoryDumper {
    
    @Getter(AccessLevel.PROTECTED)
    private final InventoryDumperConfiguration inventoryDumperConfig;
    
    private final DataSourceManager dataSourceManager;
    
    private final TableMetaData tableMetaData;
    
    @Setter
    private Channel channel;
    
    protected AbstractInventoryDumper(final InventoryDumperConfiguration inventoryDumperConfig, final DataSourceManager dataSourceManager) {
        if (!StandardJDBCDataSourceConfiguration.class.equals(inventoryDumperConfig.getDataSourceConfig().getClass())) {
            throw new UnsupportedOperationException("AbstractInventoryDumper only support StandardJDBCDataSourceConfiguration");
        }
        this.inventoryDumperConfig = inventoryDumperConfig;
        this.dataSourceManager = dataSourceManager;
        tableMetaData = createTableMetaData();
    }
    
    private TableMetaData createTableMetaData() {
        TypedDataSourceConfiguration dataSourceConfig = inventoryDumperConfig.getDataSourceConfig();
        MetaDataManager metaDataManager = new MetaDataManager(dataSourceManager.getDataSource(dataSourceConfig));
        return metaDataManager.getTableMetaData(inventoryDumperConfig.getTableName(), dataSourceConfig.getDatabaseType());
    }
    
    @Override
    public final void start() {
        super.start();
        dump();
    }
    
    private void dump() {
        try (Connection conn = dataSourceManager.getDataSource(inventoryDumperConfig.getDataSourceConfig()).getConnection()) {
            String sql = String.format("SELECT * FROM %s %s", inventoryDumperConfig.getTableName(), getWhereCondition(inventoryDumperConfig.getPrimaryKey(), inventoryDumperConfig.getPosition()));
            log.info("inventory dump, sql={}", sql);
            PreparedStatement ps = createPreparedStatement(conn, sql);
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData metaData = rs.getMetaData();
            int rowCount = 0;
            while (isRunning() && rs.next()) {
                DataRecord record = new DataRecord(newPosition(rs), metaData.getColumnCount());
                record.setType(CDCDataChangeType.INSERT);
                record.setTableName(inventoryDumperConfig.getTableNameMap().get(inventoryDumperConfig.getTableName()));
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    record.addColumn(new Column(metaData.getColumnName(i), readValue(rs, i), true, tableMetaData.isPrimaryKey(i - 1)));
                }
                pushRecord(record);
                rowCount++;
            }
            log.info("dump, rowCount={}", rowCount);
            pushRecord(new FinishedRecord(new FinishedPosition()));
        } catch (final SQLException ex) {
            stop();
            channel.close();
            throw new CDCException(ex);
        } finally {
            pushRecord(new FinishedRecord(new PlaceholderPosition()));
        }
    }
    
    private String getWhereCondition(final String primaryKey, final CDCPosition<?> position) {
        if (null == primaryKey || null == position) {
            return "";
        }
        PrimaryKeyPosition primaryKeyPosition = (PrimaryKeyPosition) position;
        return String.format("WHERE %s BETWEEN %d AND %d", primaryKey, primaryKeyPosition.getBeginValue(), primaryKeyPosition.getEndValue());
    }
    
    private CDCPosition<?> newPosition(final ResultSet rs) throws SQLException {
        if (null == inventoryDumperConfig.getPrimaryKey()) {
            return new PlaceholderPosition();
        }
        return new PrimaryKeyPosition(rs.getLong(inventoryDumperConfig.getPrimaryKey()), ((PrimaryKeyPosition) inventoryDumperConfig.getPosition()).getEndValue());
    }
    
    protected abstract PreparedStatement createPreparedStatement(Connection connection, String sql) throws SQLException;
    
    protected Object readValue(final ResultSet resultSet, final int index) throws SQLException {
        return resultSet.getObject(index);
    }
    
    private void pushRecord(final Record record) {
        try {
            channel.pushRecord(record);
        } catch (final InterruptedException ignored) {
        }
    }
}
