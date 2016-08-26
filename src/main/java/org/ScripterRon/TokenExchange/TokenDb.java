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
            + "height INT NOT NULL,"
            + "exchanged BOOLEAN NOT NULL,"
            + "token_amount BIGINT NOT NULL,"
            + "bitcoin_amount BIGINT NOT NULL,"
            + "bitcoin_address VARCHAR NOT NULL,"
            + "bitcoin_id VARCHAR)";

    /** Database index definition */
    private static final String indexDefinition = "CREATE UNIQUE INDEX IF NOT EXISTS token_exchange_idx ON token_exchange(id)";

    /** Database table */
    private static class TokenExchangeTable extends DerivedDbTable {

        /**
         * Initialize the table
         */
        private TokenExchangeTable() {
            super("token_exchange");
        }

        /**
         * Load a token
         *
         * @param   id          Transaction identifier
         * @return              Token or null
         */
        private TokenTransaction load(long id) {
            TokenTransaction tx = null;
            try (Connection conn = db.getConnection();
                    PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange WHERE id=?")) {
                stmt.setLong(1, id);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    tx = new TokenTransaction(rs.getLong("id"), rs.getInt("height"), rs.getLong("token_amount"),
                                              rs.getLong("bitcoin_amount"), rs.getString("bitcoin_address"));
                    if (rs.getBoolean("exchanged")) {
                        tx.setExchanged(rs.getString("bitcoin_id"));
                    }
                }
            } catch (SQLException exc) {
                Logger.logErrorMessage("Unable to load transaction from TokenExchange table", exc);
            }
            return tx;
        }

        /**
         * Store a token
         *
         * @param   token       Token
         */
        private void store(TokenTransaction tx) {
            try (Connection conn = db.getConnection();
                    PreparedStatement stmt = conn.prepareStatement("INSERT INTO token_exchange "
                            + "(id,height,exchanged,token_amount,bitcoin_amount,bitcoin_address) "
                            + "VALUES(?,?,false,?,?,?)")) {
                stmt.setLong(1, tx.getId());
                stmt.setInt(2, tx.getHeight());
                stmt.setLong(3, tx.getTokenAmount());
                stmt.setLong(4, tx.getBitcoinAmount());
                stmt.setString(5, tx.getBitcoinAddress());
                stmt.executeUpdate();
            } catch (SQLException exc) {
                Logger.logErrorMessage("Unable to store transaction in TokenExchange table", exc);
            }
        }

        /**
         * Update the token exchange status
         *
         * @param   token       Token
         */
        private void update(TokenTransaction tx) {
            try (Connection conn = db.getConnection();
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

    /** TokenExchange table */
    private static TokenExchangeTable table;

    /**
     * Initialize the database support
     */
    static void init() {
        try {
            table = new TokenExchangeTable();
            try (Connection conn = Db.db.getConnection();
                    Statement stmt = conn.createStatement()) {
                try {
                    ResultSet rs = stmt.executeQuery("SELECT token_amount FROM token_exchange WHERE id=0");
                    if (!rs.next()) {
                        throw new SQLException("TokenExchange table is corrupted - recreating");
                    }
                    int version = rs.getInt("token_amount");
                    if (version != dbVersion) {
                        throw new RuntimeException("Version " + version + " TokenExchange database is not supported");
                    }
                    Logger.logInfoMessage("Using Version " + version + " TokenExchange database");
                } catch (SQLException exc) {
                    stmt.execute(tableDefinition);
                    stmt.execute(indexDefinition);
                    stmt.executeUpdate("INSERT INTO token_exchange "
                            + "(id,height,exchanged,token_amount,bitcoin_amount,bitcoin_address) "
                            + "VALUES(0,0,false,"+dbVersion+",0,'Database version')");
                    Logger.logInfoMessage("Version "+dbVersion+" TokenExchange database created");
                }
            }
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to initialize TokenExchange database", exc);
            throw new RuntimeException("Unable to initialize TokenExchange database", exc);
        }
    }

    /**
     * Get a token transaction
     *
     * @param   id              Transaction identifier
     */
    static TokenTransaction getToken(long id) {
        return table.load(id);
    }

    /**
     * Store a new token transaction
     *
     * @param   token           Token transaction
     */
    static void storeToken(TokenTransaction token) {
        table.store(token);
    }

    /**
     * Update the token exchange status
     *
     * @param   token           Token transaction
     */
    static void updateToken(TokenTransaction token) {
        table.update(token);
    }
}
