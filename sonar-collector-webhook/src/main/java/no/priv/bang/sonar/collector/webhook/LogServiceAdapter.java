package no.priv.bang.sonar.collector.webhook;

import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogService;

/**
 * This is an adaptor for an injected {@link LogService}, that can be created before
 * a {@link LogService} is available and used without risk of {@link NullPointerException}.
 *
 * When an injected {@link LogService} appear, it can be added to the adapter using
 * {@link #setService(LogService)}.
 *
 * The adapter will preserve a single log message and log this message to the injected
 * {@link LogService} when {@link #setService(LogService)} is called.
 *
 * @author Steinar Bang
 *
 */
@SuppressWarnings("rawtypes")
public class LogServiceAdapter implements LogService {

    private LogService logservice;
    private int savedLevel = -1;
    private String savedLogMessage = null;
    private Throwable savedLogException = null;
    private ServiceReference savedServiceReference = null;

    @Override
    public void log(int level, String message) {
        if (logservice != null) {
            logservice.log(level, message);
        } else {
            resetSavedLog();
            savedLevel = level;
            savedLogMessage = message;
        }
    }

    @Override
    public void log(int level, String message, Throwable exception) {
        if (logservice != null) {
            logservice.log(level, message, exception);
        } else {
            resetSavedLog();
            savedLevel = level;
            savedLogMessage = message;
            savedLogException = exception;
        }
    }

    @Override
    public void log(ServiceReference sr, int level, String message) {
        if (logservice != null) {
            logservice.log(sr, level, message);
        } else {
            resetSavedLog();
            savedServiceReference = sr;
            savedLevel = level;
            savedLogMessage = message;
        }
    }

    @Override
    public void log(ServiceReference sr, int level, String message, Throwable exception) {
        if (logservice != null) {
            logservice.log(sr, level, message, exception);
        } else {
            savedServiceReference = sr;
            savedLevel = level;
            savedLogMessage = message;
            savedLogException = exception;
        }
    }

    public void setService(LogService logservice) {
        this.logservice = logservice;
        logSavedLogMessage(logservice);
    }

    private void logSavedLogMessage(LogService logservice) {
        if (logservice != null) {
            if (savedServiceReference != null) {
                logSavedLogMessageWithServiceReference(logservice);
            } else {
                logSavedLogMessageWithoutServiceReference(logservice);
            }
        }
    }

    private void logSavedLogMessageWithServiceReference(LogService logservice) {
        if (savedLevel != -1 && savedLogMessage != null && savedLogException != null) {
            logservice.log(savedServiceReference, savedLevel, savedLogMessage, savedLogException);
            resetSavedLog();
        } else if (savedLevel != -1 && savedLogMessage != null && savedLogException == null) {
            logservice.log(savedServiceReference, savedLevel, savedLogMessage);
            resetSavedLog();
        }
    }

    private void logSavedLogMessageWithoutServiceReference(LogService logservice) {
        if (savedLevel != -1 && savedLogMessage != null && savedLogException != null) {
            logservice.log(savedLevel, savedLogMessage, savedLogException);
            resetSavedLog();
        } else if (savedLevel != -1 && savedLogMessage != null && savedLogException == null) {
            logservice.log(savedLevel, savedLogMessage);
            resetSavedLog();
        }
    }

    private void resetSavedLog() {
        savedLevel = -1;
        savedLogMessage = null;
        savedLogException = null;
        savedServiceReference = null;
    }

}
