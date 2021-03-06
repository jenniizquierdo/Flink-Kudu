/**
 *
 * Created by lballestin, danicoto & AlvaroVadillo on 23/11/16.
 */

package es.accenture.flink.Sources;

import es.accenture.flink.Utils.RowSerializable;

import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.api.common.io.statistics.BaseStatistics;
import org.apache.flink.api.table.typeutils.RowSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.core.io.InputSplitAssigner;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Common;
import org.apache.kudu.Schema;
import org.apache.kudu.client.*;
import org.apache.kudu.client.shaded.com.google.common.base.Splitter;
import org.apache.kudu.client.shaded.com.google.common.collect.Lists;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link InputFormat} subclass that wraps the access for KuduTables.
 */
public class KuduInputFormat implements InputFormat<RowSerializable, KuduInputSplit> {

    private static String KUDU_MASTER;
    private static String TABLE_NAME;

    private transient KuduTable table = null;
    private transient KuduScanner scanner = null;
    private transient KuduClient client = null;

    private transient RowResultIterator results = null;
    private List<RowSerializable> rows = null;
    private boolean endReached = false;
    private int scannedRows = 0;

    private static final Logger LOG = Logger.getLogger(KuduInputFormat.class);

    List<String> projectColumns;
    //List<KuduPredicate> predicates;

    /**
     * Constructor of class KuduInputFormat
     * @param tableName Name of the Kudu table in which we are going to read
     * @param IP Kudu-master server's IP direction
     */
    public KuduInputFormat(String tableName, String IP){
        KUDU_MASTER = System.getProperty("kuduMaster", IP);
        TABLE_NAME = System.getProperty("tableName", tableName);
        this.client  = new KuduClient.KuduClientBuilder(KUDU_MASTER).build();
    }

    /**
     * Returns an instance of Scan that retrieves the required subset of records from the Kudu table.
     * @return The appropriate instance of Scan for this usecase.
     */
    private KuduScanner getScanner(){
        return this.scanner;
    }

    /**
     * What table is to be read.
     * Per instance of a TableInputFormat derivative only a single tablename is possible.
     * @return The name of the table
     */
    public String getTableName(){
        return TABLE_NAME;
    }

    /**
     * @return A list of rows ({@link RowSerializable}) from the Kudu table
     */
    public List<RowSerializable> getRows(){
        return this.rows;
    }

    /**
     * The output from Kudu is always an instance of {@link RowResult}.
     * This method is to copy the data in the RowResult instance into the required {@link RowSerializable}
     * @param rowResult The Result instance from Kudu that needs to be converted
     * @return The appropriate instance of {@link RowSerializable} that contains the needed information.
     */
    private RowSerializable RowResultToRowSerializable(RowResult rowResult) throws IllegalAccessException {
        RowSerializable row = new RowSerializable(rowResult.getColumnProjection().getColumnCount());
        for (int i=0; i<rowResult.getColumnProjection().getColumnCount(); i++){
            switch(rowResult.getColumnType(i).getDataType()){
                case INT8:
                    row.setField(i, rowResult.getByte(i));
                    break;
                case INT16:
                    row.setField(i, rowResult.getShort(i));
                    break;
                case INT32:
                    row.setField(i, rowResult.getInt(i));
                    break;
                case INT64:
                    row.setField(i, rowResult.getLong(i));
                    break;
                case FLOAT:
                    row.setField(i, rowResult.getFloat(i));
                    break;
                case DOUBLE:
                    row.setField(i, rowResult.getDouble(i));
                    break;
                case STRING:
                    row.setField(i, rowResult.getString(i));
                    break;
                case BOOL:
                    row.setField(i, rowResult.getBoolean(i));
                    break;
                case BINARY:
                    row.setField(i, rowResult.getBinary(i));
                    break;
                case TIMESTAMP:
                    row.setField(i, rowResult.getLong(i));
                    break;
            }
        }
        return row;
    }

    /**
     * Creates a object and opens the {@link KuduTable} connection.
     * These are opened here because they are needed in the createInputSplits
     * which is called before the openInputFormat method.
     *
     * @param parameters The configuration that is to be used
     * @see Configuration
     */

    @Override
    public void configure(Configuration parameters) {

        //this.predicates = new ArrayList<>();
    }

    public void openTable (String TABLE_NAME) throws Exception {
        LOG.info("Initializing KUDUConfiguration");
        try {

            if (client.tableExists(TABLE_NAME)) {
                table = client.openTable(TABLE_NAME);

                projectColumns = new ArrayList<>();
                for(int i=0; i<table.getSchema().getColumnCount(); i++){
                    projectColumns.add(this.table.getSchema().getColumnByIndex(i).getName());
                }

            } else {
                LOG.error("Table does not exist");
                client.close();
                return;
            }
        }catch (Exception e){
            throw new RuntimeException("Could not obtain table");
        }
        if (table != null) {
            scanner = getScanner();
        }
    }

    /**
     * Create an {@link KuduTable} instance and set it into this format
     */

    @Override
    public void open(KuduInputSplit split) throws IOException {
        LOG.info("Opening split");
        try {
            table = client.openTable(TABLE_NAME);

            //KuduSession session = client.newSession();
            LOG.info("Session created");

            KuduInputSplit[] splits = createInputSplits(3);

            LOG.info(splits.length + " splits generated");

            endReached = false;
            scannedRows = 0;

            this.scanner = client.newScannerBuilder(table).build();
            this.results = scanner.nextRows();
            this.generateRows();
        } catch (IOException e) {
            LOG.error("Could not open Kudu Table named: " + TABLE_NAME);
            throw new IOException("The table doesn't exist");
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Generate a list which contains {@link RowSerializable}
     */
    private void generateRows() throws IllegalAccessException, IOException {
        List<RowSerializable> rows = new ArrayList<>();
        RowResult rowRes;
        RowSerializable row;
        try {
            rowRes=results.next();
            row=this.RowResultToRowSerializable(rowRes);
        } catch (Exception e){
            row=null;
            LOG.error("Empty table");
        }
        while(results.hasNext()) {
            rows.add(row);
            row=this.nextRecord(row);
        }
        rows.add(row);
        this.rows=rows;
    }

    /**
     *
     * @return True if has reached the end, false if not
     */
    @Override
    public boolean reachedEnd() throws IOException {
        return endReached;
    }

    /**
     * Receives the last Row {@link RowSerializable} returned by the iterator and returns the next one.
     * @param reuse; the last record returned by the iterator.
     * @return resRow; the next record from the iterator.
     */
    @Override
    public RowSerializable nextRecord(RowSerializable reuse) throws IOException {
        if (scanner == null) {
            throw new IOException("No table scanner provided!");
        }
        try {
            RowResult res = this.results.next();
            RowSerializable resRow= RowResultToRowSerializable(res);

            if (res != null) {
                scannedRows++;
                return resRow;
            }
        } catch (Exception e) {
            endReached = true;
            scanner.close();
            //workaround for timeout on scan
            LOG.warn("Error after scan of " + scannedRows + " rows. Retry with a new scanner...", e);
        }
        return null;
    }

    /**
     * Method that marks the end of the life-cycle of an input split.
     * It's used to close the Kudu Scanner.
     * After this method returns without an error, the input is assumed to be correctly read
     */
    @Override
    public void close() throws IOException {
        LOG.info("Closing split (scanned {} rows)" + scannedRows);

        try {
            if (scanner != null) {
                scanner.close();
            }
        } finally {
            scanner = null;
        }
    }

    /**
     * Creates the different splits of the KuduTable that can be processed in parallel.
     * @param minNumSplits; The minimum desired number of splits.
     *      If fewer are created, some parallel instances may remain idle.
     * @return inputs; The splits of this input that can be processed in parallel.
     */
    @Override
    public KuduInputSplit[] createInputSplits(final int minNumSplits) throws IOException {

        int cont = 0;
        try {
            KuduScanToken.KuduScanTokenBuilder builder = client.newScanTokenBuilder(this.table)
                    .setProjectedColumnNames(projectColumns);

            //for (KuduPredicate predicate : predicates) {
            //    builder.addPredicate(predicate);
            //}

            List<KuduScanToken> tokens = builder.build();
            KuduInputSplit[] splits = new KuduInputSplit[tokens.size()];

            String[] hostName = new String[] {KUDU_MASTER};

            for (KuduScanToken token : tokens){
                List<String> locations = new ArrayList<>(token.getTablet().getReplicas().size());
                for (LocatedTablet.Replica replica : token.getTablet().getReplicas()) {
                    locations.add(hostName[0]);
                }

                splits[cont] = new KuduInputSplit(token, locations.toArray(new String[locations.size()]));
                cont++;
                LOG.debug("Counter:" + cont);
            }
            return splits;

        } catch (IOException e) {
            throw new IOException(e);
        }
    }
/*
    private void logSplitInfo(String action, LocatableInputSplit split) {

        int splitId = split.getSplitNumber();
        String splitStart = Bytes.toString(split.getStartRow());
        String splitEnd = Bytes.toString(split.getEndRow());
        String splitStartKey = splitStart.isEmpty() ? "-" : splitStart;
        String splitStopKey = splitEnd.isEmpty() ? "-" : splitEnd;
        String[] hostnames = split.getHostnames();
        LOG.info("{} split (this={})[{}|{}|{}|{}]", action, this, splitId, hostnames, splitStartKey, splitStopKey);

    }*/

    /**
     * Test if the given region is to be included in the InputSplit while splitting the regions of a table.
     * <p>
     * This optimization is effective when there is a specific reasoning to exclude an entire region from the M-R job,
     * (and hence, not contributing to the InputSplit), given the start and end keys of the same. <br>
     * Useful when we need to remember the last-processed top record and revisit the [last, current) interval for M-R
     * processing, continuously. In addition to reducing InputSplits, reduces the load on the region server as well, due
     * to the ordering of the keys. <br>
     * <br>
     * Note: It is possible that <code>endKey.length() == 0 </code> , for the last (recent) region. <br>
     * Override this method, if you want to bulk exclude regions altogether from M-R. By default, no region is excluded(
     * i.e. all regions are included).
     *
     * @param startKey Start key of the region
     * @param endKey   End key of the region
     * @return true, if this region needs to be included as part of the input (default).
     */
    protected boolean includeRegionInSplit(final byte[] startKey, final byte[] endKey) {
        return true;
    }

    @Override
    public InputSplitAssigner getInputSplitAssigner(KuduInputSplit[] inputSplits) {
        return new InputSplitAssigner() {
            @Override
            public InputSplit getNextInputSplit(String s, int i) {
                return null;
            }
        };
    }


    @Override
    public BaseStatistics getStatistics(BaseStatistics cachedStatistics) {
        return null;
    }

}