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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.sql.Driver;
import java.sql.SQLException;

import javax.sql.ConnectionPoolDataSource;
import javax.sql.XADataSource;

import org.junit.Test;
import org.osgi.service.jdbc.DataSourceFactory;

public class DataSourceFactoryAdapterTest {

    @Test
    public void testCreateDataSourceNoInjection() throws SQLException {
        DataSourceFactoryAdapter adapter = new DataSourceFactoryAdapter();

        assertEquals(NullDataSource.getInstance(), adapter.createDataSource(null));
    }

    @Test
    public void testCreateDataSource() throws SQLException {
        DataSourceFactory factory = mock(DataSourceFactory.class);

        DataSourceFactoryAdapter adapter = new DataSourceFactoryAdapter();
        adapter.setFactory(factory);

        assertNull(adapter.createDataSource(null));
    }

    @Test
    public void testCreateConnectionPoolDataSourceNoInjection() throws SQLException {
        DataSourceFactoryAdapter adapter = new DataSourceFactoryAdapter();

        assertNull(adapter.createConnectionPoolDataSource(null));
    }

    @Test
    public void testCreateConnectionPoolDataSource() throws SQLException {
        DataSourceFactory factory = mock(DataSourceFactory.class);
        ConnectionPoolDataSource datasource = mock(ConnectionPoolDataSource.class);
        when(factory.createConnectionPoolDataSource(any())).thenReturn(datasource);

        DataSourceFactoryAdapter adapter = new DataSourceFactoryAdapter();
        adapter.setFactory(factory);

        assertNotNull(adapter.createConnectionPoolDataSource(null));
    }

    @Test
    public void testCreateXADataSourceNoInjection() throws SQLException {
        DataSourceFactoryAdapter adapter = new DataSourceFactoryAdapter();

        assertNull(adapter.createXADataSource(null));
    }

    @Test
    public void testCreateXADataSource() throws SQLException {
        DataSourceFactory factory = mock(DataSourceFactory.class);
        XADataSource datasource = mock(XADataSource.class);
        when(factory.createXADataSource(any())).thenReturn(datasource);

        DataSourceFactoryAdapter adapter = new DataSourceFactoryAdapter();
        adapter.setFactory(factory);

        assertNotNull(adapter.createXADataSource(null));
    }

    @Test
    public void testCreateDriverNoInjection() throws SQLException {
        DataSourceFactoryAdapter adapter = new DataSourceFactoryAdapter();

        assertNull(adapter.createDriver(null));
    }

    @Test
    public void testCreateDriver() throws SQLException {
        DataSourceFactory factory = mock(DataSourceFactory.class);
        Driver driver = mock(Driver.class);
        when(factory.createDriver(any())).thenReturn(driver);

        DataSourceFactoryAdapter adapter = new DataSourceFactoryAdapter();
        adapter.setFactory(factory);

        assertNotNull(adapter.createDriver(null));
    }

}
