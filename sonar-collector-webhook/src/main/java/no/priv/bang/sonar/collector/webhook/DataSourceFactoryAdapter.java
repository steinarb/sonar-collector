/*
 * Copyright 2017 Steinar Bang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package no.priv.bang.sonar.collector.webhook;

import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;
import javax.sql.XADataSource;

import org.osgi.service.jdbc.DataSourceFactory;

public class DataSourceFactoryAdapter implements DataSourceFactory {
    DataSourceFactory factory = null;

    public void setFactory(DataSourceFactory dataSourceFactory) {
        factory = dataSourceFactory;
    }

    @Override
    public DataSource createDataSource(Properties props) throws SQLException {
        if (factory == null) {
            return NullDataSource.getInstance();
        }

        return factory.createDataSource(props);
    }

    @Override
    public ConnectionPoolDataSource createConnectionPoolDataSource(Properties props) throws SQLException {
        if (factory == null) {
            return null;
        }

        return factory.createConnectionPoolDataSource(props);
    }

    @Override
    public XADataSource createXADataSource(Properties props) throws SQLException {
        if (factory == null) {
            return null;
        }

        return factory.createXADataSource(props);
    }

    @Override
    public Driver createDriver(Properties props) throws SQLException {
        if (factory == null) {
            return null;
        }

        return factory.createDriver(props);
    }

}
