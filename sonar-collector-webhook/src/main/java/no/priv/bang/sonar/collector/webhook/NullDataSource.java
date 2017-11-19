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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * This object is returned when the DataSourceFactory fails to connect to a database.
 *
 * <p>The {@link DataSource#getConnection()} method can be called without getting a
 * {@link NullPointerException} and the method's implementation returns a null,
 * which is safe to use in a try-with-resource.
 *
 * @author Steinar Bang
 *
 */
public class NullDataSource implements DataSource {

    private final static DataSource instance = new NullDataSource();

    public static DataSource getInstance() {
        return instance;
    }

    private PrintWriter logwriter = null;
    private int timeout = 0;

    private NullDataSource() {
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return logwriter;
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return timeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter log) throws SQLException {
        logwriter = log;
    }

    @Override
    public void setLoginTimeout(int timeout) throws SQLException {
        this.timeout = timeout;
    }

    @Override
    public boolean isWrapperFor(Class<?> clazz) throws SQLException {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> clazz) throws SQLException {
        return null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return null;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return null;
    }

}
