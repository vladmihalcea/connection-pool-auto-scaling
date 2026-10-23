package com.vladmihalcea.phd.pool.workload.tpcc;

import com.vladmihalcea.phd.pool.workload.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.SplittableRandom;

/**
 * The nine TPC-C tables and their initial population (TPC-C specification, clauses 1.3 and 4.3), at the
 * standard cardinalities per warehouse: 10 districts, 3,000 customers and orders per district, 100,000
 * stock rows, with 900 undelivered orders per district in {@code new_order}, plus the shared 100,000-row
 * {@code item} table. Table names follow BenchBase ({@code oorder} because {@code ORDER} is reserved).
 * The DDL is portable between PostgreSQL and MySQL; the secondary indexes are built after the load.
 *
 * <p>This is a TPC-C-<em>derived</em> fixture for the pool-sizing experiments, not an audited
 * implementation: the data generators follow the specification, but nothing here enforces its
 * measurement and reporting rules.
 *
 * @author Vlad Mihalcea
 */
public final class TpccSchema {

    private static final Logger LOGGER = LoggerFactory.getLogger(TpccSchema.class);

    public static final int ITEMS = 100_000;
    public static final int DISTRICTS_PER_WAREHOUSE = 10;
    public static final int CUSTOMERS_PER_DISTRICT = 3_000;
    public static final int ORDERS_PER_DISTRICT = 3_000;
    /** Orders {@code 2101..3000} of each district start undelivered (clause 4.3.3.1). */
    public static final int FIRST_UNDELIVERED_ORDER = 2_101;

    private static final int BATCH_SIZE = 1_000;

    private static final String[] TABLES =
            {"order_line", "new_order", "oorder", "history", "customer", "district", "stock", "item", "warehouse"};

    private final int warehouses;
    private final long seed;

    public TpccSchema(int warehouses) {
        this(warehouses, 42L);
    }

    public TpccSchema(int warehouses, long seed) {
        if (warehouses < 1) {
            throw new IllegalArgumentException("At least one warehouse is required: " + warehouses);
        }
        this.warehouses = warehouses;
        this.seed = seed;
    }

    public int warehouses() {
        return warehouses;
    }

    /** Drops and recreates the tables, loads the initial population, then builds the secondary indexes. */
    public void create(DataSource dataSource) {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean mySql = Schema.isMySql(connection);
            connection.setAutoCommit(false);
            createTables(connection, mySql);
            SplittableRandom random = new SplittableRandom(seed);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            loadItems(connection, random);
            for (int w = 1; w <= warehouses; w++) {
                loadWarehouse(connection, random, w, now);
                LOGGER.info("Loaded TPC-C warehouse {}/{}", w, warehouses);
            }
            try (Statement ddl = connection.createStatement()) {
                ddl.execute("CREATE INDEX idx_customer_name ON customer (c_w_id, c_d_id, c_last, c_first)");
                ddl.execute("CREATE INDEX idx_order_customer ON oorder (o_w_id, o_d_id, o_c_id, o_id)");
                for (String table : TABLES) {
                    ddl.execute((mySql ? "ANALYZE TABLE " : "ANALYZE ") + table);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the TPC-C schema", e);
        }
        LOGGER.info("Created TPC-C schema with {} warehouse(s) in {} ms",
                warehouses, (System.nanoTime() - start) / 1_000_000);
    }

    private static void createTables(Connection connection, boolean mySql) throws SQLException {
        // MySQL only allows NULL in a TIMESTAMP column when it is declared explicitly.
        String nullableTimestamp = mySql ? "TIMESTAMP NULL" : "TIMESTAMP";
        try (Statement ddl = connection.createStatement()) {
            for (String table : TABLES) {
                ddl.execute("DROP TABLE IF EXISTS " + table);
            }
            ddl.execute("""
                    CREATE TABLE warehouse (
                        w_id INT NOT NULL, w_name VARCHAR(10) NOT NULL,
                        w_street_1 VARCHAR(20) NOT NULL, w_street_2 VARCHAR(20) NOT NULL,
                        w_city VARCHAR(20) NOT NULL, w_state CHAR(2) NOT NULL, w_zip CHAR(9) NOT NULL,
                        w_tax DECIMAL(4,4) NOT NULL, w_ytd DECIMAL(12,2) NOT NULL,
                        PRIMARY KEY (w_id))""");
            ddl.execute("""
                    CREATE TABLE district (
                        d_w_id INT NOT NULL, d_id INT NOT NULL, d_name VARCHAR(10) NOT NULL,
                        d_street_1 VARCHAR(20) NOT NULL, d_street_2 VARCHAR(20) NOT NULL,
                        d_city VARCHAR(20) NOT NULL, d_state CHAR(2) NOT NULL, d_zip CHAR(9) NOT NULL,
                        d_tax DECIMAL(4,4) NOT NULL, d_ytd DECIMAL(12,2) NOT NULL, d_next_o_id INT NOT NULL,
                        PRIMARY KEY (d_w_id, d_id))""");
            ddl.execute("""
                    CREATE TABLE customer (
                        c_w_id INT NOT NULL, c_d_id INT NOT NULL, c_id INT NOT NULL,
                        c_first VARCHAR(16) NOT NULL, c_middle CHAR(2) NOT NULL, c_last VARCHAR(16) NOT NULL,
                        c_street_1 VARCHAR(20) NOT NULL, c_street_2 VARCHAR(20) NOT NULL,
                        c_city VARCHAR(20) NOT NULL, c_state CHAR(2) NOT NULL, c_zip CHAR(9) NOT NULL,
                        c_phone CHAR(16) NOT NULL, c_since TIMESTAMP NOT NULL, c_credit CHAR(2) NOT NULL,
                        c_credit_lim DECIMAL(12,2) NOT NULL, c_discount DECIMAL(4,4) NOT NULL,
                        c_balance DECIMAL(12,2) NOT NULL, c_ytd_payment DECIMAL(12,2) NOT NULL,
                        c_payment_cnt INT NOT NULL, c_delivery_cnt INT NOT NULL, c_data VARCHAR(500) NOT NULL,
                        PRIMARY KEY (c_w_id, c_d_id, c_id))""");
            ddl.execute("""
                    CREATE TABLE history (
                        h_c_id INT NOT NULL, h_c_d_id INT NOT NULL, h_c_w_id INT NOT NULL,
                        h_d_id INT NOT NULL, h_w_id INT NOT NULL, h_date TIMESTAMP NOT NULL,
                        h_amount DECIMAL(6,2) NOT NULL, h_data VARCHAR(24) NOT NULL)""");
            ddl.execute("""
                    CREATE TABLE oorder (
                        o_w_id INT NOT NULL, o_d_id INT NOT NULL, o_id INT NOT NULL, o_c_id INT NOT NULL,
                        o_entry_d TIMESTAMP NOT NULL, o_carrier_id INT NULL, o_ol_cnt INT NOT NULL,
                        o_all_local INT NOT NULL,
                        PRIMARY KEY (o_w_id, o_d_id, o_id))""");
            ddl.execute("""
                    CREATE TABLE new_order (
                        no_w_id INT NOT NULL, no_d_id INT NOT NULL, no_o_id INT NOT NULL,
                        PRIMARY KEY (no_w_id, no_d_id, no_o_id))""");
            ddl.execute("""
                    CREATE TABLE order_line (
                        ol_w_id INT NOT NULL, ol_d_id INT NOT NULL, ol_o_id INT NOT NULL, ol_number INT NOT NULL,
                        ol_i_id INT NOT NULL, ol_supply_w_id INT NOT NULL, ol_delivery_d %s,
                        ol_quantity INT NOT NULL, ol_amount DECIMAL(6,2) NOT NULL, ol_dist_info CHAR(24) NOT NULL,
                        PRIMARY KEY (ol_w_id, ol_d_id, ol_o_id, ol_number))""".formatted(nullableTimestamp));
            ddl.execute("""
                    CREATE TABLE item (
                        i_id INT NOT NULL, i_im_id INT NOT NULL, i_name VARCHAR(24) NOT NULL,
                        i_price DECIMAL(5,2) NOT NULL, i_data VARCHAR(50) NOT NULL,
                        PRIMARY KEY (i_id))""");
            ddl.execute("""
                    CREATE TABLE stock (
                        s_w_id INT NOT NULL, s_i_id INT NOT NULL, s_quantity INT NOT NULL,
                        s_dist_01 CHAR(24) NOT NULL, s_dist_02 CHAR(24) NOT NULL, s_dist_03 CHAR(24) NOT NULL,
                        s_dist_04 CHAR(24) NOT NULL, s_dist_05 CHAR(24) NOT NULL, s_dist_06 CHAR(24) NOT NULL,
                        s_dist_07 CHAR(24) NOT NULL, s_dist_08 CHAR(24) NOT NULL, s_dist_09 CHAR(24) NOT NULL,
                        s_dist_10 CHAR(24) NOT NULL, s_ytd INT NOT NULL, s_order_cnt INT NOT NULL,
                        s_remote_cnt INT NOT NULL, s_data VARCHAR(50) NOT NULL,
                        PRIMARY KEY (s_w_id, s_i_id))""");
        }
        connection.commit();
    }

    private static void loadItems(Connection connection, SplittableRandom random) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO item (i_id, i_im_id, i_name, i_price, i_data) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 1; i <= ITEMS; i++) {
                insert.setInt(1, i);
                insert.setInt(2, TpccRandom.uniform(random, 1, 10_000));
                insert.setString(3, TpccRandom.alphanumeric(random, 14, 24));
                insert.setBigDecimal(4, cents(TpccRandom.uniform(random, 100, 10_000)));
                insert.setString(5, TpccRandom.data(random, 26, 50));
                addBatch(connection, insert, i);
            }
            flush(connection, insert);
        }
    }

    private static void loadWarehouse(Connection connection, SplittableRandom random, int w, Timestamp now)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO warehouse VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setInt(1, w);
            setNamedAddress(insert, 2, random);
            insert.setBigDecimal(8, tax(random));
            insert.setBigDecimal(9, new BigDecimal("300000.00"));
            insert.executeUpdate();
        }
        loadStock(connection, random, w);
        for (int d = 1; d <= DISTRICTS_PER_WAREHOUSE; d++) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO district VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setInt(1, w);
                insert.setInt(2, d);
                setNamedAddress(insert, 3, random);
                insert.setBigDecimal(9, tax(random));
                insert.setBigDecimal(10, new BigDecimal("30000.00"));
                insert.setInt(11, ORDERS_PER_DISTRICT + 1);
                insert.executeUpdate();
            }
            loadCustomers(connection, random, w, d, now);
            loadOrders(connection, random, w, d, now);
        }
        connection.commit();
    }

    private static void loadStock(Connection connection, SplittableRandom random, int w) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stock VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 1; i <= ITEMS; i++) {
                insert.setInt(1, w);
                insert.setInt(2, i);
                insert.setInt(3, TpccRandom.uniform(random, 10, 100));
                for (int d = 0; d < 10; d++) {
                    insert.setString(4 + d, TpccRandom.alphanumeric(random, 24, 24));
                }
                insert.setInt(14, 0);
                insert.setInt(15, 0);
                insert.setInt(16, 0);
                insert.setString(17, TpccRandom.data(random, 26, 50));
                addBatch(connection, insert, i);
            }
            flush(connection, insert);
        }
    }

    private static void loadCustomers(Connection connection, SplittableRandom random, int w, int d,
                                      Timestamp now) throws SQLException {
        try (PreparedStatement customer = connection.prepareStatement(
                "INSERT INTO customer VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement history = connection.prepareStatement(
                     "INSERT INTO history VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int c = 1; c <= CUSTOMERS_PER_DISTRICT; c++) {
                customer.setInt(1, w);
                customer.setInt(2, d);
                customer.setInt(3, c);
                customer.setString(4, TpccRandom.alphanumeric(random, 8, 16));
                customer.setString(5, "OE");
                // The first 1,000 customers cover every last name once (clause 4.3.3.1).
                customer.setString(6, TpccRandom.lastName(
                        c <= 1_000 ? c - 1 : TpccRandom.nuRand(random, 255, TpccRandom.C_LAST, 0, 999)));
                setAddress(customer, 7, random);
                customer.setString(12, TpccRandom.numeric(random, 16));
                customer.setTimestamp(13, now);
                customer.setString(14, random.nextInt(10) == 0 ? "BC" : "GC");
                customer.setBigDecimal(15, new BigDecimal("50000.00"));
                customer.setBigDecimal(16, BigDecimal.valueOf(TpccRandom.uniform(random, 0, 5_000), 4));
                customer.setBigDecimal(17, new BigDecimal("-10.00"));
                customer.setBigDecimal(18, new BigDecimal("10.00"));
                customer.setInt(19, 1);
                customer.setInt(20, 0);
                customer.setString(21, TpccRandom.alphanumeric(random, 300, 500));
                customer.addBatch();

                history.setInt(1, c);
                history.setInt(2, d);
                history.setInt(3, w);
                history.setInt(4, d);
                history.setInt(5, w);
                history.setTimestamp(6, now);
                history.setBigDecimal(7, new BigDecimal("10.00"));
                history.setString(8, TpccRandom.alphanumeric(random, 12, 24));
                history.addBatch();
                if (c % BATCH_SIZE == 0) {
                    customer.executeBatch();
                    history.executeBatch();
                }
            }
            customer.executeBatch();
            history.executeBatch();
        }
    }

    private static void loadOrders(Connection connection, SplittableRandom random, int w, int d, Timestamp now)
            throws SQLException {
        // Orders are placed by a random permutation of the customers (clause 4.3.3.1).
        int[] customers = new int[ORDERS_PER_DISTRICT];
        for (int i = 0; i < customers.length; i++) {
            customers[i] = i + 1;
        }
        for (int i = customers.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = customers[i];
            customers[i] = customers[j];
            customers[j] = swap;
        }
        try (PreparedStatement order = connection.prepareStatement(
                "INSERT INTO oorder VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement newOrder = connection.prepareStatement(
                     "INSERT INTO new_order VALUES (?, ?, ?)");
             PreparedStatement orderLine = connection.prepareStatement(
                     "INSERT INTO order_line VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int o = 1; o <= ORDERS_PER_DISTRICT; o++) {
                boolean delivered = o < FIRST_UNDELIVERED_ORDER;
                int lines = TpccRandom.uniform(random, 5, 15);
                order.setInt(1, w);
                order.setInt(2, d);
                order.setInt(3, o);
                order.setInt(4, customers[o - 1]);
                order.setTimestamp(5, now);
                if (delivered) {
                    order.setInt(6, TpccRandom.uniform(random, 1, 10));
                } else {
                    order.setNull(6, java.sql.Types.INTEGER);
                }
                order.setInt(7, lines);
                order.setInt(8, 1);
                order.addBatch();
                if (!delivered) {
                    newOrder.setInt(1, w);
                    newOrder.setInt(2, d);
                    newOrder.setInt(3, o);
                    newOrder.addBatch();
                }
                for (int l = 1; l <= lines; l++) {
                    orderLine.setInt(1, w);
                    orderLine.setInt(2, d);
                    orderLine.setInt(3, o);
                    orderLine.setInt(4, l);
                    orderLine.setInt(5, TpccRandom.uniform(random, 1, ITEMS));
                    orderLine.setInt(6, w);
                    if (delivered) {
                        orderLine.setTimestamp(7, now);
                    } else {
                        orderLine.setNull(7, java.sql.Types.TIMESTAMP);
                    }
                    orderLine.setInt(8, 5);
                    orderLine.setBigDecimal(9, delivered ? BigDecimal.ZERO.setScale(2)
                            : cents(TpccRandom.uniform(random, 1, 999_999)));
                    orderLine.setString(10, TpccRandom.alphanumeric(random, 24, 24));
                    orderLine.addBatch();
                }
                if (o % 100 == 0) {
                    order.executeBatch();
                    newOrder.executeBatch();
                    orderLine.executeBatch();
                }
            }
            order.executeBatch();
            newOrder.executeBatch();
            orderLine.executeBatch();
        }
    }

    /** Sets name, two streets, city, state and zip at {@code from..from+5}, the warehouse/district layout. */
    private static void setNamedAddress(PreparedStatement insert, int from, SplittableRandom random)
            throws SQLException {
        insert.setString(from, TpccRandom.alphanumeric(random, 6, 10));
        setAddress(insert, from + 1, random);
    }

    /** Sets two streets, city, state and zip at {@code from..from+4}. */
    private static void setAddress(PreparedStatement insert, int from, SplittableRandom random)
            throws SQLException {
        insert.setString(from, TpccRandom.alphanumeric(random, 10, 20));
        insert.setString(from + 1, TpccRandom.alphanumeric(random, 10, 20));
        insert.setString(from + 2, TpccRandom.alphanumeric(random, 10, 20));
        insert.setString(from + 3, TpccRandom.alphanumeric(random, 2, 2).toUpperCase());
        insert.setString(from + 4, TpccRandom.numeric(random, 4) + "11111");
    }

    private static BigDecimal tax(SplittableRandom random) {
        return BigDecimal.valueOf(TpccRandom.uniform(random, 0, 2_000), 4);
    }

    private static BigDecimal cents(int cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private static void addBatch(Connection connection, PreparedStatement insert, int row) throws SQLException {
        insert.addBatch();
        if (row % BATCH_SIZE == 0) {
            insert.executeBatch();
        }
        if (row % (BATCH_SIZE * 10) == 0) {
            connection.commit();
        }
    }

    private static void flush(Connection connection, PreparedStatement insert) throws SQLException {
        insert.executeBatch();
        connection.commit();
    }
}
