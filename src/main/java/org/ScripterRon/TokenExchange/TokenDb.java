/*
 * Copyright 2016 Ronald W Hoffman.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ScripterRon.TokenExchange;

import nxt.Db;
import nxt.db.DerivedDbTable;
import nxt.util.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * TokenExchange database support
 */
public class TokenDb {

    /** Database version */
    private static final int dbVersion = 1;

    /** Database table definition */
    private static final String tableDefinition = "CREATE TABLE IF NOT EXISTS token_exchange ("
            + "db_id IDENTITY,"
            + "id BIGINT NOT NULL,"
            + "sender BIGINT NOT NULL,"
            + "height INT NOT NULL,"
            + "exchanged BOOLEAN NOT NULL,"
            + "token_amount BIGINT NOT NULL,"
            + "bitcoin_amount BIGINT NOT NULL,"
            + "bitcoin_address VARCHAR NOT NULL,"
            + "bitcoin_id VARCHAR)";

    /** Database index definition */
    private static final String indexDefinition = "CREATE UNIQUE INDEX IF NOT EXISTS token_exchange_idx ON token_exchange(id)";

    /**
     * Database table
     *
     * A DerivedDbTable provides rollback() and truncate() methods which
     * a called by the block chain processor when blocks are popped off.
     * So we only need to worry about adding rows to the table as new
     * blocks are pushed.
     */
    private static class TokenExchangeTable extends DerivedDbTable {

        /**
         * Initialize the table
         *
         * @param   name        Table name
         */
        private TokenExchangeTable(String name) {
            super(name);
        }

        /**
         * Rollback to the specified height
         *
         * We need to override the default rollback() method because we
         * do not want to delete tokens that have been exchanged.
         *
         * @param   height      Rollback height
         */
        @Override
        public void rollback(int height) {
            if (!db.isInTransaction()) {
                throw new IllegalStateException("Not in transaction");
            }
            try (Connection conn = db.getConnection();
                    PreparedStatement pstmtDelete = conn.prepareStatement(
                        "DELETE FROM " + table + " WHERE height > ? AND exchanged=false")) {
                pstmtDelete.setInt(1, height);
                pstmtDelete.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e.toString(), e);
            }
        }

        /**
         * Truncate the table
         *
         * We need to treat this as a rollback to 0 since we do not want
         * to delete tokens that have been exchanged.
         */
        @Override
        public void truncate() {
            rollback(0);
        }
    }

    /** TokenExchange table */
    private static TokenExchangeTable table;

    /**
     * Initialize the database support
     */
    static void init() {
        try {
            table = new TokenExchangeTable("token_exchange");
            try (Connection conn = Db.db.getConnection();
                    Statement stmt = conn.createStatement()) {
                try {
                    try (ResultSet rs = stmt.executeQuery("SELECT token_amount FROM token_exchange WHERE id=0")) {
                        if (!rs.next()) {
                            throw new SQLException("TokenExchange table is corrupted - recreating");
                        }
                        int version = rs.getInt("token_amount");
                        if (version != dbVersion) {
                            throw new RuntimeException("Version " + version + " TokenExchange database is not supported");
                        }
                        Logger.logInfoMessage("Using Version " + version + " TokenExchange database");
                    }
                } catch (SQLException exc) {
                    stmt.execute(tableDefinition);
                    stmt.execute(indexDefinition);
                    stmt.executeUpdate("INSERT INTO token_exchange "
                            + "(id,sender,height,exchanged,token_amount,bitcoin_amount,bitcoin_address) "
                            + "VALUES(0,0,0,false,"+dbVersion+",0,'Database version')");
                    Logger.logInfoMessage("Version "+dbVersion+" TokenExchange database created");
                }
            }
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to initialize TokenExchange database", exc);
            throw new RuntimeException("Unable to initialize TokenExchange database", exc);
        }
    }

    /**
     * See if a transaction token exists
     *
     * @param   id              Transaction identifier
     * @return                  TRUE if the transaction token exists
     */
    static boolean tokenExists(long id) {
        boolean exists = false;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT db_id FROM token_exchange WHERE id=?")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to check transaction in TokenExchange table", exc);
        }
        return exists;
    }

    /**
     * Get a token transaction
     *
     * @param   id              Transaction identifier
     * @return                  Transaction token or null if an error occurred
     */
    static TokenTransaction getToken(long id) {
        TokenTransaction tx = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange WHERE id=?")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    tx = new TokenTransaction(rs);
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to load transaction from TokenExchange table", exc);
        }
        return tx;
    }

    /**
     * Get tokens above the specified height
     *
     * @param   height          Block height
     * @return                  List of transaction tokens
     */
    static List<TokenTransaction> getTokens(int height) {
        List<TokenTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange "
                        + "WHERE height>? ORDER BY HEIGHT ASC")) {
            stmt.setInt(1, Math.max(1, height));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    txList.add(new TokenTransaction(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get list of pending transactions from TokenExchange table", exc);
        }
        return txList;
    }

    /**
     * Get pending transaction tokens at or below the specified height
     *
     * @param   height          Block height
     * @return                  List of transaction tokens
     */
    static List<TokenTransaction> getPendingTokens(int height) {
        List<TokenTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange "
                        + "WHERE height>0 AND height<=? AND exchanged=false ORDER BY HEIGHT ASC")) {
            stmt.setInt(1, height);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    txList.add(new TokenTransaction(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get list of pending transactions from TokenExchange table", exc);
        }
        return txList;
    }

    /**
     * Store a new token transaction
     *
     * @param   tx              Token transaction
     */
    static void storeToken(TokenTransaction tx) {
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO token_exchange "
                        + "(id,sender,height,exchanged,token_amount,bitcoin_amount,bitcoin_address) "
                        + "VALUES(?,?,?,false,?,?,?)")) {
            stmt.setLong(1, tx.getId());
            stmt.setLong(2, tx.getSenderId());
            stmt.setInt(3, tx.getHeight());
            stmt.setLong(4, tx.getTokenAmount());
            stmt.setLong(5, tx.getBitcoinAmount());
            stmt.setString(6, tx.getBitcoinAddress());
            stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store transaction in TokenExchange table", exc);
        }
    }

    /**
     * Update the token exchange status
     *
     * @param   tx              Token transaction
     */
    static void updateToken(TokenTransaction tx) {
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE token_exchange "
                        + "SET exchanged=true,bitcoin_id=? WHERE id=?")) {
            stmt.setString(1, tx.getBitcoinTxId());
            stmt.setLong(2, tx.getId());
            stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update transaction in TokenExchange table", exc);
        }
    }
}
