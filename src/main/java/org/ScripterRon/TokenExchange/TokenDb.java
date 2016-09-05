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
import nxt.Nxt;
import nxt.db.DerivedDbTable;
import nxt.util.Logger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * TokenExchange database support
 */
public class TokenDb {

    /** Database schema name */
    private static final String DB_SCHEMA = "TOKEN_EXCHANGE_2";

    /** Current database version */
    private static final int DB_VERSION = 2;

    /** Schema definition */
    private static final String schemaDefinition = "CREATE SCHEMA IF NOT EXISTS " + DB_SCHEMA;

    /** Nxt token table definitions */
    private static final String tokenTableDefinition = "CREATE TABLE IF NOT EXISTS " + DB_SCHEMA + ".token ("
            + "db_id IDENTITY,"
            + "nxt_txid BIGINT NOT NULL,"           // Nxt transaction identifier
            + "sender BIGINT NOT NULL,"             // Nxt transaction sender identifier
            + "height INT NOT NULL,"                // Nxt transaction height
            + "timestamp INT NOT NULL,"             // Nxt transaction timestamp
            + "exchanged BOOLEAN NOT NULL,"         // TRUE if currency exchanged for Bitcoin
            + "token_amount BIGINT NOT NULL,"       // Number of units redeemed / Database version
            + "bitcoin_amount BIGINT NOT NULL,"     // Bitcoin amount / Current exchange rate
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address / Wallet bitcoin address
            + "bitcoin_txid BINARY(32))";           // Bitcoin transaction identifier
    private static final String tokenIndexDefinition1 = "CREATE UNIQUE INDEX IF NOT EXISTS " + DB_SCHEMA + ".token_idx1 "
            + "ON " + DB_SCHEMA + ".token(nxt_txid)";
    private static final String tokenIndexDefinition2 = "CREATE INDEX IF NOT EXISTS " + DB_SCHEMA + ".token_idx2 "
            + "ON " + DB_SCHEMA + ".token(exchanged)";
    private static final String tokenIndexDefinition3 = "CREATE INDEX IF NOT EXISTS " + DB_SCHEMA + ".token_idx3 "
            + "ON " + DB_SCHEMA + ".token(height)";

    /** Bitcoin address table definitions */
    private static final String accountTableDefinition = "CREATE TABLE IF NOT EXISTS " + DB_SCHEMA + ".account ("
            + "db_id IDENTITY,"
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address
            + "account_id BIGINT NOT NULL,"         // Nxt account identifier
            + "public_key BINARY(32))";             // Nxt account public key
    private static final String accountIndexDefinition1 = "CREATE UNIQUE INDEX IF NOT EXISTS " + DB_SCHEMA + ".account_idx1 "
            + "ON " + DB_SCHEMA + ".account(bitcoin_address)";
    private static final String accountIndexDefinition2 = "CREATE INDEX IF NOT EXISTS " + DB_SCHEMA + ".account_idx2 "
            + "ON " + DB_SCHEMA + ".account(account_id)";

    /** Bitcoin transaction table definitions */
    private static final String transactionTableDefinition = "CREATE TABLE IF NOT EXISTS " + DB_SCHEMA + ".transaction ("
            + "db_id IDENTITY,"
            + "height INT NOT NULL,"                // Bitcoin block chain height (0 if not in chain yet)
            + "timestamp INT NOT NULL,"             // Bitcoin transaction timestamp (0 if not in chain yet)
            + "bitcoin_txid BINARY(32) NOT NULL,"   // Bitcoin transaction identifier
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address
            + "account_id BIGINT NOT NULL,"         // Nxt account identifier
            + "bitcoin_amount BIGINT NOT NULL,"     // Bitcoin amount
            + "token_amount BIGINT NOT NULL,"       // Number of units issued
            + "exchanged BOOLEAN NOT NULL,"         // TRUE if currency has been issued
            + "nxt_txid BIGINT NOT NULL)";          // Nxt transaction identifier
    private static final String transactionIndexDefinition1 = "CREATE INDEX IF NOT EXISTS " + DB_SCHEMA + ".transaction_idx1 "
            + "ON " + DB_SCHEMA + ".transaction(bitcoin_txid)";
    private static final String transactionIndexDefinition2 = "CREATE INDEX IF NOT EXISTS " + DB_SCHEMA + ".transaction_idx2 "
            + "ON " + DB_SCHEMA + ".transaction(exchanged)";
    private static final String transactionIndexDefinition3 = "CREATE INDEX IF NOT EXISTS " + DB_SCHEMA + ".transaction_idx3 "
            + "ON " + DB_SCHEMA + ".transaction(height)";

    /**
     * Token table
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
                        "DELETE FROM " + table + " WHERE exchanged=false AND height > ?")) {
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

    /** TokenExchange tables */
    private static TokenExchangeTable tokenTable;

    /**
     * Initialize the database support
     *
     * We use the nxt_txid=0 row in the token_exchange table to save status information:
     *   - token_amount is the database version
     *   - bitcoin_amount is the current token exchange rate
     *   - bitcoin_address contains descriptive text
     *
     * @throws  SQLException    SQL error occurred
     */
    static void init() throws SQLException {
        tokenTable = new TokenExchangeTable(DB_SCHEMA + ".token");
        try (Connection conn = Db.db.getConnection();
                Statement stmt = conn.createStatement()) {
            //
            // Update the table definitions if necessary
            //
            int version = 0;
            try {
                try (ResultSet rs = stmt.executeQuery("SELECT token_amount FROM " + DB_SCHEMA + ".token WHERE nxt_txid=0")) {
                    if (rs.next()) {
                        version = rs.getInt("token_amount");
                        if (version > DB_VERSION) {
                            throw new RuntimeException("Version " + version + " TokenExchange database is not supported");
                        }
                    }
                }
            } catch (SQLException exc) {
                // Create a new database
            }
            switch (version) {
                case 0:
                    Logger.logInfoMessage("Creating new TokenExchange database");
                    stmt.execute(schemaDefinition);
                    stmt.execute(tokenTableDefinition);
                    stmt.execute(tokenIndexDefinition1);
                    stmt.execute(tokenIndexDefinition2);
                    stmt.executeUpdate("INSERT INTO " + DB_SCHEMA + ".token "
                        + "(nxt_txid,sender,height,timestamp,exchanged,token_amount,bitcoin_amount,bitcoin_address) "
                        + "VALUES(0,0,0,0,false,1,"
                        + TokenAddon.exchangeRate.movePointRight(8).toPlainString()
                        + ",'')");
                    stmt.execute(accountTableDefinition);
                    stmt.execute(accountIndexDefinition1);
                    stmt.execute(accountIndexDefinition2);
                    stmt.execute(transactionTableDefinition);
                    stmt.execute(transactionIndexDefinition1);
                    stmt.execute(transactionIndexDefinition2);
                case 1:
                    if (version == 1) {
                        int timestamp = Nxt.getEpochTime();
                        stmt.execute("ALTER TABLE " + DB_SCHEMA + ".token ADD COLUMN IF NOT EXISTS "
                                + "timestamp INT AFTER height");
                        stmt.execute("UPDATE " + DB_SCHEMA + ".token SET timestamp=" + timestamp);
                        stmt.execute("ALTER TABLE " + DB_SCHEMA + ".token ALTER COLUMN timestamp SET NOT NULL");
                        stmt.execute("ALTER TABLE " + DB_SCHEMA + ".transaction ADD COLUMN IF NOT EXISTS "
                                + "timestamp INT AFTER height");
                        stmt.execute("UPDATE " + DB_SCHEMA + ".transaction SET timestamp=" + timestamp);
                        stmt.execute("ALTER TABLE " + DB_SCHEMA + ".transaction ALTER COLUMN timestamp SET NOT NULL");
                        stmt.execute("DROP INDEX " + DB_SCHEMA + ".account_idx2");
                        stmt.execute(accountIndexDefinition2);
                    }
                    stmt.execute(tokenIndexDefinition3);
                    stmt.execute(transactionIndexDefinition3);
                    // Add new database version processing here
                    stmt.executeUpdate("UPDATE " + DB_SCHEMA + ".token SET token_amount=" + DB_VERSION + " WHERE nxt_txid=0");
                default:
                    Logger.logInfoMessage("Using Version " + DB_VERSION + " TokenExchange database");
            }
            //
            // Get the current token exchange rate
            //
            try (ResultSet rs = stmt.executeQuery("SELECT bitcoin_amount FROM "
                        + DB_SCHEMA + ".token WHERE nxt_txid=0")) {
                if (rs.next()) {
                    long exchangeRate = rs.getLong("bitcoin_amount");
                    if (exchangeRate != 0) {
                        TokenAddon.exchangeRate = BigDecimal.valueOf(exchangeRate, 8).stripTrailingZeros();
                    }
                }
            }
        }
    }

    /**
     * Get the wallet address
     *
     * @return                  Wallet address or null
     */
    static String getWalletAddress() {
        String address = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT bitcoin_address FROM "
                        + DB_SCHEMA + ".token WHERE nxt_txid=0")) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    address = rs.getString("bitcoin_address");
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get wallet address from TokenExchange table", exc);
        }
        return address;
    }

    /**
     * Set the wallet address
     *
     * @param   address         Wallet address
     * @return                  TRUE if the wallet address was set
     */
    static boolean setWalletAddress(String address) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + DB_SCHEMA + ".token "
                        + "SET bitcoin_address=? WHERE nxt_txid=0")) {
            stmt.setString(1, address);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to set wallet address in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Set the token exchange rate
     *
     * @param   rate            Exchange rate
     * @return                  TRUE if the exchange rate was set
     */
    static boolean setExchangeRate(BigDecimal rate) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + DB_SCHEMA + ".token "
                        + "SET bitcoin_amount=? WHERE nxt_txid=0")) {
            stmt.setLong(1, rate.movePointRight(8).longValue());
            count = stmt.executeUpdate();
            if (count > 0) {
                TokenAddon.exchangeRate = rate;
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to set token exchange rate in TokenExchange table", exc);
        }
        return count != 0;
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
                PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + DB_SCHEMA + ".token WHERE nxt_txid=?")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to check Nxt transaction in TokenExchange table", exc);
        }
        return exists;
    }

    /**
     * Get a token transaction
     *
     * @param   id              Transaction identifier
     * @return                  Transaction token or null if not found
     */
    static TokenTransaction getToken(long id) {
        TokenTransaction tx = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".token WHERE nxt_txid=?")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    tx = new TokenTransaction(rs);
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to load Nxt transaction from TokenExchange table", exc);
        }
        return tx;
    }

    /**
     * Get tokens at or above the specified height
     *
     * @param   height          Block height
     * @param   exchanged       TRUE to return exchanged tokens
     * @return                  List of transaction tokens
     */
    static List<TokenTransaction> getTokens(int height, boolean exchanged) {
        List<TokenTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".token "
                        + "WHERE height>=? " + (exchanged ? "" : "AND exchanged=false ")
                        + "ORDER BY height ASC,timestamp ASC")) {
            stmt.setInt(1, Math.max(1, Math.max(0, height)));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    txList.add(new TokenTransaction(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get list of pending Nxt transactions from TokenExchange table", exc);
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
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".token "
                        + "WHERE exchanged=false AND height>0 AND height<=? ORDER BY height ASC")) {
            stmt.setInt(1, height);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    txList.add(new TokenTransaction(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get list of pending Nxt transactions from TokenExchange table", exc);
        }
        return txList;
    }

    /**
     * Store a new token transaction
     *
     * @param   tx              Token transaction
     * @return                  TRUE if the token was stored
     */
    static boolean storeToken(TokenTransaction tx) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + DB_SCHEMA + ".token "
                        + "(nxt_txid,sender,height,timestamp,exchanged,token_amount,bitcoin_amount,bitcoin_address) "
                        + "VALUES(?,?,?,?,false,?,?,?)")) {
            stmt.setLong(1, tx.getNxtTxId());
            stmt.setLong(2, tx.getSenderId());
            stmt.setInt(3, tx.getHeight());
            stmt.setInt(4, tx.getTimestamp());
            stmt.setLong(5, tx.getTokenAmount());
            stmt.setLong(6, tx.getBitcoinAmount());
            stmt.setString(7, tx.getBitcoinAddress());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store Nxt transaction in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Update the token status
     *
     * @param   tx              Token transaction
     * @return                  TRUE if the token was updated
     */
    static boolean updateToken(TokenTransaction tx) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + DB_SCHEMA + ".token "
                        + "SET exchanged=true,bitcoin_txid=? WHERE nxt_txid=?")) {
            stmt.setBytes(1, tx.getBitcoinTxId());
            stmt.setLong(2, tx.getNxtTxId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update Nxt transaction in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Delete a token
     *
     * @param   id              Token identifier
     * @return                  TRUE if the token was deleted
     */
    static boolean deleteToken(long id) {
        if (id == 0) {
            return false;
        }
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + DB_SCHEMA + ".token WHERE nxt_txid=?")) {
            stmt.setLong(1, id);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to delete Nxt transaction from TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Store a Bitcoin account
     *
     * @param   account         Bitcoin account
     * @return                  TRUE if the address was stored
     */
    static boolean storeAccount(BitcoinAccount account) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + DB_SCHEMA + ".account "
                        + "(bitcoin_address,account_id,public_key) "
                        + "VALUES(?,?,?)")) {
            stmt.setString(1, account.getBitcoinAddress());
            stmt.setLong(2, account.getAccountId());
            if (account.getPublicKey() != null) {
                stmt.setBytes(3, account.getPublicKey());
            } else {
                stmt.setNull(3, Types.BINARY);
            }
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store account in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Get the Bitcoin accounts associated with a Nxt account identifier
     *
     * @param   account_id      Account identifier
     * @return                  Bitcoin account list
     */
    static List<BitcoinAccount> getAccount(long accountId) {
        List<BitcoinAccount> accountList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".account "
                        + "WHERE account_id=?")) {
            stmt.setLong(1, accountId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    accountList.add(new BitcoinAccount(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get account from TokenExchange table", exc);
        }
        return accountList;
    }

    /**
     * Get the Nxt account associated with a Bitcoin address
     *
     * @param   address         Bitcoin address
     * @return                  Nxt account or null
     */
    static BitcoinAccount getAccount(String address) {
        BitcoinAccount account = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".account "
                        + "WHERE bitcoin_address=?")) {
            stmt.setString(1, address);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    account = new BitcoinAccount(rs);
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get account from TokenExchange table", exc);
        }
        return account;
    }

    /**
     * Get the Nxt accounts associated with Bitcoin addresses
     *
     * @return                  Account list
     */
    static List<BitcoinAccount> getAccounts() {
        List<BitcoinAccount> accountList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".account "
                        + "ORDER BY account_id")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    accountList.add(new BitcoinAccount(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get accounts from TokenExchange table", exc);
        }
        return accountList;
    }

    /**
     * Store a Bitcoin transaction
     *
     * @param   tx              Bitcoin transaction
     * @return                  TRUE if the transaction was stored
     */
    static boolean storeTransaction(BitcoinTransaction tx) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + DB_SCHEMA + ".transaction "
                        + "(bitcoin_txid,height,timestamp,bitcoin_address,bitcoin_amount,"
                        + "token_amount,account_id,exchanged,nxt_txid) "
                        + "VALUES(?,?,?,?,?,?,?,false,0)")) {
            stmt.setBytes(1, tx.getBitcoinTxId());
            stmt.setInt(2, tx.getHeight());
            stmt.setInt(3, tx.getTimestamp());
            stmt.setString(4, tx.getBitcoinAddress());
            stmt.setLong(5, tx.getBitcoinAmount());
            stmt.setLong(6, tx.getTokenAmount());
            stmt.setLong(7, tx.getAccountId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store Bitcoin transaction in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Update a Bitcoin transaction
     *
     * @param   tx              Bitcoin transaction
     * @return                  TRUE if the transaction was updated
     */
    static boolean updateTransaction(BitcoinTransaction tx) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + DB_SCHEMA + ".transaction "
                        + "SET exchanged=?,nxt_txid=?,height=?,timestamp=? WHERE bitcoin_txid=?")) {
            stmt.setBoolean(1, tx.isExchanged());
            stmt.setLong(2, tx.getNxtTxId());
            stmt.setInt(3, tx.getHeight());
            stmt.setInt(4, tx.getTimestamp());
            stmt.setBytes(5, tx.getBitcoinTxId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update Bitcoin transaction in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * See if a Bitcoin transaction exists
     *
     * @param   id              Transaction identifier
     * @return                  TRUE if the Bitcoin transaction exists
     */
    static boolean transactionExists(byte[] id) {
        boolean exists = false;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + DB_SCHEMA + ".transaction "
                        + "WHERE bitcoin_txid=?")) {
            stmt.setBytes(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to check Bitcoin transaction in TokenExchange table", exc);
        }
        return exists;
    }

    /**
     * See if a Bitcoin transaction exists for the specified address
     *
     * @param   address         Bitcoin address
     * @return                  TRUE if a Bitcoin transaction exists
     */
    static boolean transactionExists(String address) {
        boolean exists = false;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + DB_SCHEMA + ".transaction "
                        + "WHERE bitcoin_address=?")) {
            stmt.setString(1, address);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to check Bitcoin transaction in TokenExchange table", exc);
        }
        return exists;
    }

    /**
     * Get a Bitcoin transaction
     *
     * @param   txid            Bitcoin transaction identifier
     * @return                  Bitcoin transaction or null
     */
    static BitcoinTransaction getTransaction(byte[] txid) {
        BitcoinTransaction tx = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".transaction "
                        + "WHERE bitcoin_txid=?")) {
            stmt.setBytes(1, txid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    tx = new BitcoinTransaction(rs);
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get Bitcoin transaction from TokenExchange table", exc);
        }
        return tx;
    }

    /**
     * Get the Bitcoin transactions with a block height at or above the specified height
     *
     * @param   height          Bitcoin block height
     * @param   address         Bitcoin address or null for all addresses
     * @param   exchanged       Include processed transactions
     * @return                  Bitcoin transaction list
     */
    static List<BitcoinTransaction> getTransactions(int height, String address, boolean exchanged) {
        List<BitcoinTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".transaction "
                        + "WHERE height>=? "
                        + (address!=null ? (exchanged ? "AND bitcoin_address=? " :
                                                        "AND bitcoin_address=? AND exchanged=false ") :
                                           (exchanged ? "" : "AND exchanged=false "))
                        + "ORDER BY height ASC")) {
            stmt.setInt(1, Math.max(0, height));
            if (address != null) {
                stmt.setString(2, address);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    txList.add(new BitcoinTransaction(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get Bitcoin transaction from TokenExchange table", exc);
        }
        return txList;
    }

    /**
     * Get pending Bitcoin transactions at or below the specified height
     *
     * @param   height          Block height
     * @return                  List of transaction
     */
    static List<BitcoinTransaction> getPendingTransactions(int height) {
        List<BitcoinTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + DB_SCHEMA + ".transaction "
                        + "WHERE exchanged=false AND height<=? ORDER BY height ASC")) {
            stmt.setInt(1, height);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    txList.add(new BitcoinTransaction(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get list of pending Bitcoin transactions from TokenExchange table", exc);
        }
        return txList;
    }

    /**
     * Delete a Bitcoin transaction
     *
     * @param   id              Transaction identifier
     * @return                  TRUE if the transaction was deleted
     */
    static boolean deleteTransaction(byte[] id) {
        if (id == null) {
            return false;
        }
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + DB_SCHEMA + ".transaction "
                        + "WHERE bitcoin_txid=?")) {
            stmt.setBytes(1, id);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to delete Bitcoin transaction from TokenExchange table", exc);
        }
        return count != 0;
    }
}
