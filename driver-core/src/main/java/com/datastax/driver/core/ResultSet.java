package com.datastax.driver.core;

import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.cql3.ColumnSpecification;
import org.apache.cassandra.transport.Message;
import org.apache.cassandra.transport.ProtocolException;
import org.apache.cassandra.transport.ServerError;
import org.apache.cassandra.transport.messages.ErrorMessage;
import org.apache.cassandra.transport.messages.ResultMessage;

import com.datastax.driver.core.transport.Connection;
import com.datastax.driver.core.utils.SimpleFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The result of a query.
 *
 * Note that this class is not thread-safe.
 */
public class ResultSet implements Iterable<CQLRow> {

    private static final Logger logger = LoggerFactory.getLogger(ResultSet.class);

    private static final ResultSet EMPTY = new ResultSet(Columns.EMPTY, new ArrayDeque(0));

    private final Columns metadata;
    private final Queue<List<ByteBuffer>> rows;

    private ResultSet(Columns metadata, Queue<List<ByteBuffer>> rows) {

        this.metadata = metadata;
        this.rows = rows;
    }

    static ResultSet fromMessage(ResultMessage msg) {
        switch (msg.kind) {
            case VOID:
                return EMPTY;
            case ROWS:
                ResultMessage.Rows r = (ResultMessage.Rows)msg;
                Columns.Definition[] defs = new Columns.Definition[r.result.metadata.names.size()];
                for (int i = 0; i < defs.length; i++)
                    defs[i] = Columns.Definition.fromTransportSpecification(r.result.metadata.names.get(i));

                return new ResultSet(new Columns(defs), new ArrayDeque(r.result.rows));
            case SET_KEYSPACE:
                // TODO: we might want to do more with such result
                return EMPTY;
            case PREPARED:
                throw new RuntimeException("Prepared statement received when a ResultSet was expected");
            default:
                throw new AssertionError();
        }
    }

    /**
     * The columns returned in this ResultSet.
     *
     * @return the columns returned in this ResultSet.
     */
    public Columns columns() {
        return metadata;
    }

    /**
     * Test whether this ResultSet has more results.
     *
     * @return whether this ResultSet has more results.
     */
    public boolean isExhausted() {
        return rows.isEmpty();
    }

    /**
     * Returns the the next result from this ResultSet.
     *
     * @return the next row in this resultSet or null if this ResultSet is
     * exhausted.
     */
    public CQLRow fetchOne() {
        return CQLRow.fromData(metadata, rows.poll());
    }

    /**
     * Returns all the remaining rows in this ResultSet as a list.
     *
     * @return a list containing the remaining results of this ResultSet. The
     * returned list is empty if and only the ResultSet is exhausted.
     */
    public List<CQLRow> fetchAll() {
        if (isExhausted())
            return Collections.emptyList();

        List<CQLRow> result = new ArrayList<CQLRow>(rows.size());
        for (CQLRow row : this)
            result.add(row);
        return result;
    }

    /**
     * An iterator over the rows contained in this ResultSet.
     *
     * The {@link Iterator#next} method is equivalent to calling {@link #fetchOne}.
     * So this iterator will consume results from this ResultSet and after a
     * full iteration, the ResultSet will be empty.
     *
     * The returned iterator does not support the {@link Iterator#remove} method.
     *
     * @return an iterator that will consume and return the remaining rows of
     * this ResultSet.
     */
    public Iterator<CQLRow> iterator() {

        return new Iterator<CQLRow>() {

            public boolean hasNext() {
                return !rows.isEmpty();
            }

            public CQLRow next() {
                return CQLRow.fromData(metadata, rows.poll());
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ResultSet[ exhausted: ").append(isExhausted());
        sb.append(", ").append(metadata).append("]");
        return sb.toString();
    }

    public static class Future extends SimpleFuture<ResultSet> implements Connection.ResponseCallback
    {
        Future() {}

        @Override
        public void onSet(Message.Response response) {
            try {
                switch (response.type) {
                    case RESULT:
                        super.set(ResultSet.fromMessage((ResultMessage)response));
                        break;
                    case ERROR:
                        super.setException(convertException(((ErrorMessage)response).error));
                        break;
                    default:
                        // TODO: handle errors (set the connection to defunct as this mean it is in a bad state)
                        logger.info("Got " + response);
                        throw new RuntimeException();
                }
            } catch (Exception e) {
                // TODO: do better
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onException(Exception exception) {
            super.setException(exception);
        }

        // TODO: Convert to some internal exception
        private Exception convertException(org.apache.cassandra.exceptions.TransportException te) {

            if (te instanceof ServerError) {
                return new RuntimeException("An unexpected error occured server side: " + te.getMessage());
            } else if (te instanceof ProtocolException) {
                return new RuntimeException("An unexpected protocol error occured. This is a bug in this library, please report: " + te.getMessage());
            } else {
                return new RuntimeException(te.getMessage());
            }
        }
    }
}