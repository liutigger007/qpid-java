/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.store.jdbc.bonecp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import org.apache.qpid.server.store.jdbc.ConnectionProvider;
import org.apache.qpid.server.util.MapValueConverter;

import com.jolbox.bonecp.BoneCP;
import com.jolbox.bonecp.BoneCPConfig;

public class BoneCPConnectionProvider implements ConnectionProvider
{
    public static final String PARTITION_COUNT = "partitionCount";
    public static final String MAX_CONNECTIONS_PER_PARTITION = "maxConnectionsPerPartition";
    public static final String MIN_CONNECTIONS_PER_PARTITION = "minConnectionsPerPartition";

    public static final int DEFAULT_MIN_CONNECTIONS_PER_PARTITION = 5;
    public static final int DEFAULT_MAX_CONNECTIONS_PER_PARTITION = 10;
    public static final int DEFAULT_PARTITION_COUNT = 4;

    private final BoneCP _connectionPool;

    public BoneCPConnectionProvider(String connectionUrl, Map<String, Object> storeSettings) throws SQLException
    {
        BoneCPConfig config = new BoneCPConfig();
        config.setJdbcUrl(connectionUrl);
        config.setMinConnectionsPerPartition(MapValueConverter.getIntegerAttribute(MIN_CONNECTIONS_PER_PARTITION, storeSettings, DEFAULT_MIN_CONNECTIONS_PER_PARTITION));
        config.setMaxConnectionsPerPartition(MapValueConverter.getIntegerAttribute(MAX_CONNECTIONS_PER_PARTITION, storeSettings, DEFAULT_MAX_CONNECTIONS_PER_PARTITION));
        config.setPartitionCount(MapValueConverter.getIntegerAttribute(PARTITION_COUNT, storeSettings, DEFAULT_PARTITION_COUNT));
        _connectionPool = new BoneCP(config);
    }

    @Override
    public Connection getConnection() throws SQLException
    {
        return _connectionPool.getConnection();
    }

    @Override
    public void close() throws SQLException
    {
        _connectionPool.shutdown();
    }
}
