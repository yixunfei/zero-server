package group.zn.zero.runtime.production;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeoutException;

/** Driver-independent classification of transport failures without reading exception messages. */
public final class RuntimeAdapterRemoteFailures {
    private RuntimeAdapterRemoteFailures() { }

    /** Returns true only for known remote transport, timeout, or availability failures. */
    public static boolean isRemoteIo(final Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            if (current instanceof IOException || current instanceof SQLTransientConnectionException
                    || current instanceof SQLNonTransientConnectionException
                    || current instanceof SQLTimeoutException
                    || current instanceof TimeoutException) return true;
            if (current instanceof SQLException sql && remoteSqlState(sql.getSQLState())) return true;
            String type = current.getClass().getName();
            if (type.endsWith("JedisConnectionException")
                    || type.endsWith("MongoSocketException")
                    || type.endsWith("MongoTimeoutException")
                    || type.endsWith("MongoNotPrimaryException")
                    || type.endsWith("MongoNodeIsRecoveringException")
                    || type.startsWith("org.apache.kafka.common.errors.")
                    && (type.endsWith("TimeoutException") || type.endsWith("NetworkException")
                    || type.endsWith("DisconnectException"))
                    || type.endsWith("NacosException")) return true;
        }
        return false;
    }

    private static boolean remoteSqlState(final String state) {
        return state != null && (state.startsWith("08") || state.startsWith("HYT")
                || state.equals("57P01") || state.equals("57P02") || state.equals("57P03"));
    }
}
