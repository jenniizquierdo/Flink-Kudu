package es.accenture.flink.Sink;

import es.accenture.flink.Utils.RowSerializable;
import org.apache.flink.api.common.io.RichOutputFormat;
import org.apache.flink.configuration.Configuration;
import org.apache.kudu.client.*;
import org.apache.log4j.Logger;

import java.io.IOException;

/**
 * Created by sshvayka on 21/11/16.
 */
public class KuduOutputFormat extends RichOutputFormat<RowSerializable> {

    private String host, tableName, tableMode;
    private String [] fieldsNames;
    private transient Utils utils;

    //Kudu variables
    private transient KuduTable table;

    //LOG4J
    final static Logger logger = Logger.getLogger(KuduOutputFormat.class);

    /**
     * Builder to use when you want to create a new table
     *
     * @param host          Kudu host
     * @param tableName     Kudu table name
     * @param fieldsNames   List of column names in the table to be created
     * @param tableMode     Way to operate with table(CREATE,APPEND,OVERRIDE)
     * @throws KuduException
     */
    public KuduOutputFormat(String host, String tableName, String [] fieldsNames, String tableMode) throws KuduException {
        if(!(tableMode.equals("CREATE") || tableMode.equals("APPEND") || tableMode.equals("OVERRIDE"))){
            logger.error("ERROR: table mode parameter not valid (must be CREATE, APPEND or OVERRIDE)");
            System.exit(1);
        }
        this.host = host;
        this.tableName = tableName;
        this.fieldsNames = fieldsNames;
        this.tableMode = tableMode;
    }

    /**
     * Builder to be used when using an existing table
     *
     * @param host          Kudu host
     * @param tableName     Kudu table name to be used
     * @param tableMode     Way to operate with table(CREATE,APPEND,OVERRIDE)
     * @throws KuduException
     */

    public KuduOutputFormat(String host, String tableName, String tableMode) throws KuduException {
        if(!(tableMode.equals("CREATE") || tableMode.equals("APPEND") || tableMode.equals("OVERRIDE"))){
            logger.error("ERROR: table mode parameter not valid (must be CREATE, APPEND or OVERRIDE)");
            System.exit(1);
        }
        if(tableMode.equals("CREATE")){
            logger.error("ERROR: missing fields parameter at the constructor method");
            System.exit(1);
        }
        this.host = host;
        this.tableName = tableName;
        this.tableMode = tableMode;
    }

    @Override
    public void configure(Configuration configuration) {

    }

    @Override
    public void open(int i, int i1) throws IOException {

    }

    @Override
    public void close() throws IOException {

    }

    /**
     * It's responsible to insert a row into the indicated table by the builder (Batch)
     *
     * @param row   Data of a row to insert
     * @throws IOException
     */
    @Override
    public void writeRecord(RowSerializable row) throws IOException {
        this.utils = new Utils(host);
        //Look at the situation of the table (exist or not) depending of the mode, the table is created or opened
        try {
            this.table = utils.useTable(tableName, fieldsNames, row, tableMode);
        } catch (Exception e) {
            logger.error(e.getMessage());
            e.printStackTrace();
//            System.exit(1);
        }

        // Case APPEND(or OVERRIDE), with builder without column names , because otherwise it throws a NullPointerException
        if(fieldsNames == null){
            fieldsNames = utils.getNamesOfColumns(table);
        } else {
            // When column names provided, and table exists, must check if column names match
            try {
                utils.checkNamesOfColumns(utils.getNamesOfColumns(table), fieldsNames);
            } catch (Exception e){
                logger.error(e.getMessage());
//                e.printStackTrace();
//                System.exit(1);
            }
        }

        // Make the insert into the table
        utils.insert(table, row, fieldsNames);


        logger.info("Inserted the Row: " + utils.printRow(row));
    }
}
