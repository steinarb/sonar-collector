package no.priv.bang.sonar.collector.webhook;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

public class DataSourceAdapter implements DataSource {

    DataSource datasource = NullDataSource.getInstance();

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return datasource.getLogWriter();
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return datasource.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return datasource.getParentLogger();
    }

    @Override
    public void setLogWriter(PrintWriter log) throws SQLException {
        datasource.setLogWriter(log);
    }

    @Override
    public void setLoginTimeout(int timeout) throws SQLException {
        datasource.setLoginTimeout(timeout);
    }

    @Override
    public boolean isWrapperFor(Class<?> clazz) throws SQLException {
        return datasource.isWrapperFor(clazz);
    }

    @Override
    public <T> T unwrap(Class<T> clazz) throws SQLException {
        return datasource.unwrap(clazz);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return datasource.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return datasource.getConnection(username, password);
    }

    public void setDatasource(DataSource db) {
        if (db == null) {
            datasource = NullDataSource.getInstance();
        } else {
            datasource = db;
        }
    }

}
