package com.vladmihalcea.phd.pool.workload.tpcc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The five TPC-C transactions (TPC-C specification, clause 2) over a {@link TpccSchema}, in the standard
 * mix: 45% New-Order, 43% Payment, 4% Order-Status, 4% Delivery and 4% Stock-Level. The inputs follow the
 * specification: {@code NURand} customer and item ids, 60% of Payment and Order-Status customers chosen by
 * last name, 1% of New-Orders rolled back on an unused item id, 1% remote order lines and 15% remote
 * Payment customers when there is more than one warehouse.
 *
 * <p>Like BenchBase, it runs as a closed loop without keying and think times, and each transaction picks
 * its home warehouse at random instead of binding a terminal to one, so it measures the database under a
 * TPC-C-shaped load rather than producing a reportable tpmC. Delivery runs inline rather than deferred.
 *
 * <p>Locks are taken in one global order so the mix does not deadlock under PostgreSQL's or MySQL's
 * default isolation: New-Order locks its district and then the stock rows sorted by (supply warehouse,
 * item); Payment locks warehouse, district, then customer; Delivery walks the districts in order.
 *
 * @author Vlad Mihalcea
 */
public final class TpccWorkload {

    /** The transaction types, with their share of the standard mix in percent. */
    public enum Type {
        NEW_ORDER(45), PAYMENT(43), ORDER_STATUS(4), DELIVERY(4), STOCK_LEVEL(4);

        private final int percent;

        Type(int percent) {
            this.percent = percent;
        }

        public int percent() {
            return percent;
        }
    }

    private final int warehouses;

    public TpccWorkload(int warehouses) {
        this.warehouses = warehouses;
    }

    public String name() {
        return "tpcc";
    }

    /** Draws the next transaction type from the standard mix. */
    public static Type nextType(SplittableRandom random) {
        int draw = random.nextInt(100);
        int cumulative = 0;
        for (Type type : Type.values()) {
            cumulative += type.percent();
            if (draw < cumulative) {
                return type;
            }
        }
        throw new IllegalStateException("The mix does not add up to 100%");
    }

    /**
     * Executes one transaction drawn from the mix. The connection is assumed to be in auto-commit mode on
     * entry and is restored to it on exit. The intentional New-Order rollback (1%) completes normally.
     *
     * @return the type of the transaction that ran
     * @throws SQLException if the transaction fails (for example, on a lock timeout or a deadlock)
     */
    public Type runTransaction(Connection connection, SplittableRandom random) throws SQLException {
        Type type = nextType(random);
        int w = TpccRandom.uniform(random, 1, warehouses);
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            boolean commit = switch (type) {
                case NEW_ORDER -> newOrder(connection, random, w);
                case PAYMENT -> payment(connection, random, w);
                case ORDER_STATUS -> orderStatus(connection, random, w);
                case DELIVERY -> delivery(connection, random, w);
                case STOCK_LEVEL -> stockLevel(connection, random, w);
            };
            if (commit) {
                connection.commit();
            } else {
                connection.rollback();
            }
            return type;
        } catch (SQLException e) {
            safeRollback(connection);
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    // --- New-Order (clause 2.4) ---------------------------------------------------------------------

    private record OrderLine(int itemId, int supplyWarehouse, int quantity) {
    }

    private boolean newOrder(Connection connection, SplittableRandom random, int w) throws SQLException {
        int d = TpccRandom.uniform(random, 1, TpccSchema.DISTRICTS_PER_WAREHOUSE);
        int c = TpccRandom.customerId(random);
        int lineCount = TpccRandom.uniform(random, 5, 15);
        boolean rollback = TpccRandom.uniform(random, 1, 100) == 1;
        List<OrderLine> lines = new ArrayList<>(lineCount);
        boolean allLocal = true;
        for (int i = 0; i < lineCount; i++) {
            int itemId = rollback && i == lineCount - 1 ? TpccSchema.ITEMS + 1 : TpccRandom.itemId(random);
            int supply = w;
            if (warehouses > 1 && TpccRandom.uniform(random, 1, 100) == 1) {
                supply = otherWarehouse(random, w);
                allLocal = false;
            }
            lines.add(new OrderLine(itemId, supply, TpccRandom.uniform(random, 1, 10)));
        }
        lines.sort(Comparator.comparingInt(OrderLine::supplyWarehouse).thenComparingInt(OrderLine::itemId));

        BigDecimal warehouseTax = queryDecimal(connection, "SELECT w_tax FROM warehouse WHERE w_id = ?", w);
        BigDecimal districtTax;
        int orderId;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT d_tax, d_next_o_id FROM district WHERE d_w_id = ? AND d_id = ? FOR UPDATE")) {
            select.setInt(1, w);
            select.setInt(2, d);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                districtTax = rs.getBigDecimal(1);
                orderId = rs.getInt(2);
            }
        }
        update(connection, "UPDATE district SET d_next_o_id = d_next_o_id + 1 WHERE d_w_id = ? AND d_id = ?", w, d);
        BigDecimal discount;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT c_discount, c_last, c_credit FROM customer WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?")) {
            select.setInt(1, w);
            select.setInt(2, d);
            select.setInt(3, c);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                discount = rs.getBigDecimal(1);
            }
        }
        update(connection, "INSERT INTO oorder (o_w_id, o_d_id, o_id, o_c_id, o_entry_d, o_carrier_id, o_ol_cnt, "
                + "o_all_local) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, NULL, ?, ?)",
                w, d, orderId, c, lineCount, allLocal ? 1 : 0);
        update(connection, "INSERT INTO new_order (no_w_id, no_d_id, no_o_id) VALUES (?, ?, ?)", w, d, orderId);

        String distColumn = String.format("s_dist_%02d", d);
        try (PreparedStatement item = connection.prepareStatement(
                "SELECT i_price, i_name, i_data FROM item WHERE i_id = ?");
             PreparedStatement stock = connection.prepareStatement(
                     "SELECT s_quantity, s_data, " + distColumn
                             + " FROM stock WHERE s_w_id = ? AND s_i_id = ? FOR UPDATE");
             PreparedStatement updateStock = connection.prepareStatement(
                     "UPDATE stock SET s_quantity = ?, s_ytd = s_ytd + ?, s_order_cnt = s_order_cnt + 1, "
                             + "s_remote_cnt = s_remote_cnt + ? WHERE s_w_id = ? AND s_i_id = ?");
             PreparedStatement insertLine = connection.prepareStatement(
                     "INSERT INTO order_line (ol_w_id, ol_d_id, ol_o_id, ol_number, ol_i_id, ol_supply_w_id, "
                             + "ol_delivery_d, ol_quantity, ol_amount, ol_dist_info) "
                             + "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)")) {
            int number = 1;
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (OrderLine line : lines) {
                BigDecimal price;
                item.setInt(1, line.itemId());
                try (ResultSet rs = item.executeQuery()) {
                    if (!rs.next()) {
                        return false; // the unused item id: the specified 1% rollback
                    }
                    price = rs.getBigDecimal(1);
                }
                int quantity;
                String distInfo;
                stock.setInt(1, line.supplyWarehouse());
                stock.setInt(2, line.itemId());
                try (ResultSet rs = stock.executeQuery()) {
                    rs.next();
                    quantity = rs.getInt(1);
                    distInfo = rs.getString(3);
                }
                int newQuantity = quantity >= line.quantity() + 10
                        ? quantity - line.quantity() : quantity - line.quantity() + 91;
                updateStock.setInt(1, newQuantity);
                updateStock.setInt(2, line.quantity());
                updateStock.setInt(3, line.supplyWarehouse() == w ? 0 : 1);
                updateStock.setInt(4, line.supplyWarehouse());
                updateStock.setInt(5, line.itemId());
                updateStock.executeUpdate();

                insertLine.setInt(1, w);
                insertLine.setInt(2, d);
                insertLine.setInt(3, orderId);
                insertLine.setInt(4, number++);
                insertLine.setInt(5, line.itemId());
                insertLine.setInt(6, line.supplyWarehouse());
                insertLine.setInt(7, line.quantity());
                BigDecimal amount = price.multiply(BigDecimal.valueOf(line.quantity()));
                totalAmount = totalAmount.add(amount);
                insertLine.setBigDecimal(8, amount);
                insertLine.setString(9, distInfo);
                insertLine.executeUpdate();
            }
            // The order total the specification returns to the terminal (clause 2.4.2.2).
            totalAmount.multiply(BigDecimal.ONE.subtract(discount))
                    .multiply(BigDecimal.ONE.add(warehouseTax).add(districtTax));
        }
        return true;
    }

    // --- Payment (clause 2.5) -----------------------------------------------------------------------

    private boolean payment(Connection connection, SplittableRandom random, int w) throws SQLException {
        int d = TpccRandom.uniform(random, 1, TpccSchema.DISTRICTS_PER_WAREHOUSE);
        int customerWarehouse = w;
        int customerDistrict = d;
        if (warehouses > 1 && TpccRandom.uniform(random, 1, 100) > 85) {
            customerWarehouse = otherWarehouse(random, w);
            customerDistrict = TpccRandom.uniform(random, 1, TpccSchema.DISTRICTS_PER_WAREHOUSE);
        }
        BigDecimal amount = BigDecimal.valueOf(TpccRandom.uniform(random, 100, 500_000), 2);

        update(connection, "UPDATE warehouse SET w_ytd = w_ytd + ? WHERE w_id = ?", amount, w);
        String warehouseName = queryString(connection,
                "SELECT w_name, w_street_1, w_street_2, w_city, w_state, w_zip FROM warehouse WHERE w_id = ?", w);
        update(connection, "UPDATE district SET d_ytd = d_ytd + ? WHERE d_w_id = ? AND d_id = ?", amount, w, d);
        String districtName = queryString(connection,
                "SELECT d_name, d_street_1, d_street_2, d_city, d_state, d_zip FROM district "
                        + "WHERE d_w_id = ? AND d_id = ?", w, d);

        int c = selectCustomer(connection, random, customerWarehouse, customerDistrict);
        String credit;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT c_first, c_middle, c_last, c_street_1, c_street_2, c_city, c_state, c_zip, c_phone, "
                        + "c_credit, c_credit_lim, c_discount, c_balance, c_since FROM customer "
                        + "WHERE c_w_id = ? AND c_d_id = ? AND c_id = ? FOR UPDATE")) {
            select.setInt(1, customerWarehouse);
            select.setInt(2, customerDistrict);
            select.setInt(3, c);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                credit = rs.getString(10);
            }
        }
        if ("BC".equals(credit)) {
            String data = queryString(connection,
                    "SELECT c_data FROM customer WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?",
                    customerWarehouse, customerDistrict, c);
            String newData = c + " " + customerDistrict + " " + customerWarehouse + " " + d + " " + w + " "
                    + amount + " | " + data;
            update(connection, "UPDATE customer SET c_balance = c_balance - ?, c_ytd_payment = c_ytd_payment + ?, "
                            + "c_payment_cnt = c_payment_cnt + 1, c_data = ? "
                            + "WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?",
                    amount, amount, newData.length() > 500 ? newData.substring(0, 500) : newData,
                    customerWarehouse, customerDistrict, c);
        } else {
            update(connection, "UPDATE customer SET c_balance = c_balance - ?, c_ytd_payment = c_ytd_payment + ?, "
                            + "c_payment_cnt = c_payment_cnt + 1 WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?",
                    amount, amount, customerWarehouse, customerDistrict, c);
        }
        String historyData = warehouseName + "    " + districtName;
        update(connection, "INSERT INTO history (h_c_id, h_c_d_id, h_c_w_id, h_d_id, h_w_id, h_date, h_amount, "
                        + "h_data) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)",
                c, customerDistrict, customerWarehouse, d, w, amount,
                historyData.length() > 24 ? historyData.substring(0, 24) : historyData);
        return true;
    }

    // --- Order-Status (clause 2.6) ------------------------------------------------------------------

    private boolean orderStatus(Connection connection, SplittableRandom random, int w) throws SQLException {
        int d = TpccRandom.uniform(random, 1, TpccSchema.DISTRICTS_PER_WAREHOUSE);
        int c = selectCustomer(connection, random, w, d);
        queryString(connection, "SELECT c_balance, c_first, c_middle, c_last FROM customer "
                + "WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?", w, d, c);
        Integer orderId = null;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT o_id, o_carrier_id, o_entry_d FROM oorder WHERE o_w_id = ? AND o_d_id = ? AND o_c_id = ? "
                        + "ORDER BY o_id DESC LIMIT 1")) {
            select.setInt(1, w);
            select.setInt(2, d);
            select.setInt(3, c);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    orderId = rs.getInt(1);
                }
            }
        }
        if (orderId != null) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT ol_i_id, ol_supply_w_id, ol_quantity, ol_amount, ol_delivery_d FROM order_line "
                            + "WHERE ol_w_id = ? AND ol_d_id = ? AND ol_o_id = ?")) {
                select.setInt(1, w);
                select.setInt(2, d);
                select.setInt(3, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        rs.getInt(1);
                    }
                }
            }
        }
        return true;
    }

    // --- Delivery (clause 2.7) ----------------------------------------------------------------------

    private boolean delivery(Connection connection, SplittableRandom random, int w) throws SQLException {
        int carrier = TpccRandom.uniform(random, 1, 10);
        try (PreparedStatement oldest = connection.prepareStatement(
                "SELECT no_o_id FROM new_order WHERE no_w_id = ? AND no_d_id = ? ORDER BY no_o_id LIMIT 1 FOR UPDATE");
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM new_order WHERE no_w_id = ? AND no_d_id = ? AND no_o_id = ?");
             PreparedStatement customer = connection.prepareStatement(
                     "SELECT o_c_id FROM oorder WHERE o_w_id = ? AND o_d_id = ? AND o_id = ?");
             PreparedStatement sum = connection.prepareStatement(
                     "SELECT SUM(ol_amount) FROM order_line WHERE ol_w_id = ? AND ol_d_id = ? AND ol_o_id = ?")) {
            for (int d = 1; d <= TpccSchema.DISTRICTS_PER_WAREHOUSE; d++) {
                int orderId;
                oldest.setInt(1, w);
                oldest.setInt(2, d);
                try (ResultSet rs = oldest.executeQuery()) {
                    if (!rs.next()) {
                        continue; // no undelivered order in this district: skipped, as the specification allows
                    }
                    orderId = rs.getInt(1);
                }
                delete.setInt(1, w);
                delete.setInt(2, d);
                delete.setInt(3, orderId);
                if (delete.executeUpdate() == 0) {
                    continue;
                }
                int c;
                customer.setInt(1, w);
                customer.setInt(2, d);
                customer.setInt(3, orderId);
                try (ResultSet rs = customer.executeQuery()) {
                    rs.next();
                    c = rs.getInt(1);
                }
                update(connection, "UPDATE oorder SET o_carrier_id = ? WHERE o_w_id = ? AND o_d_id = ? AND o_id = ?",
                        carrier, w, d, orderId);
                update(connection, "UPDATE order_line SET ol_delivery_d = CURRENT_TIMESTAMP "
                        + "WHERE ol_w_id = ? AND ol_d_id = ? AND ol_o_id = ?", w, d, orderId);
                BigDecimal total;
                sum.setInt(1, w);
                sum.setInt(2, d);
                sum.setInt(3, orderId);
                try (ResultSet rs = sum.executeQuery()) {
                    rs.next();
                    total = rs.getBigDecimal(1);
                }
                update(connection, "UPDATE customer SET c_balance = c_balance + ?, c_delivery_cnt = c_delivery_cnt + 1 "
                                + "WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?",
                        total == null ? BigDecimal.ZERO : total, w, d, c);
            }
        }
        return true;
    }

    // --- Stock-Level (clause 2.8) -------------------------------------------------------------------

    private boolean stockLevel(Connection connection, SplittableRandom random, int w) throws SQLException {
        int d = TpccRandom.uniform(random, 1, TpccSchema.DISTRICTS_PER_WAREHOUSE);
        int threshold = TpccRandom.uniform(random, 10, 20);
        int nextOrderId;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT d_next_o_id FROM district WHERE d_w_id = ? AND d_id = ?")) {
            select.setInt(1, w);
            select.setInt(2, d);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                nextOrderId = rs.getInt(1);
            }
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol JOIN stock s "
                        + "ON s.s_w_id = ol.ol_w_id AND s.s_i_id = ol.ol_i_id "
                        + "WHERE ol.ol_w_id = ? AND ol.ol_d_id = ? AND ol.ol_o_id >= ? AND ol.ol_o_id < ? "
                        + "AND s.s_quantity < ?")) {
            select.setInt(1, w);
            select.setInt(2, d);
            select.setInt(3, nextOrderId - 20);
            select.setInt(4, nextOrderId);
            select.setInt(5, threshold);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
            }
        }
        return true;
    }

    // --- helpers ------------------------------------------------------------------------------------

    /**
     * Picks a customer by last name (60%) or by id (40%). By last name, the customer is the one in the
     * middle of the matching rows ordered by first name (clause 2.5.2.2).
     */
    private static int selectCustomer(Connection connection, SplittableRandom random, int w, int d)
            throws SQLException {
        if (TpccRandom.uniform(random, 1, 100) > 60) {
            return TpccRandom.customerId(random);
        }
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT c_id FROM customer WHERE c_w_id = ? AND c_d_id = ? AND c_last = ? ORDER BY c_first")) {
            select.setInt(1, w);
            select.setInt(2, d);
            select.setString(3, TpccRandom.randomLastName(random));
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            }
        }
        // Every last name exists in every district (the first 1,000 customers cover them all).
        return ids.isEmpty() ? TpccRandom.customerId(random) : ids.get((ids.size() + 1) / 2 - 1);
    }

    private int otherWarehouse(SplittableRandom random, int w) {
        int other = TpccRandom.uniform(random, 1, warehouses - 1);
        return other >= w ? other + 1 : other;
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private static BigDecimal queryDecimal(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private static String queryString(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignore) {
            // best-effort rollback; the original exception is propagated
        }
    }

    @Override
    public String toString() {
        return "TpccWorkload{warehouses=" + warehouses + ", mix=" + Arrays.toString(Type.values()) + '}';
    }
}
