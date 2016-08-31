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
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * TokenExchange database support
 */
public class TokenDb {

    /** Current database version */
    private static final int dbVersion = 3;

    /** Token table definitions */
    private static final String tokenTableDefinition = "CREATE TABLE IF NOT EXISTS token_exchange ("
            + "db_id IDENTITY,"
            + "nxt_txid BIGINT NOT NULL,"           // Nxt transaction identifier
            + "sender BIGINT NOT NULL,"             // Nxt transaction sender identifier
            + "height INT NOT NULL,"                // Nxt transaction height
            + "exchanged BOOLEAN NOT NULL,"         // TRUE if currency exchanged for Bitcoin
            + "token_amount BIGINT NOT NULL,"       // Number of units redeemed / Database version
            + "bitcoin_amount BIGINT NOT NULL,"     // Bitcoin amount
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address
            + "bitcoin_txid BINARY(32))";           // Bitcoin transaction identifier
    private static final String tokenIndexDefinition1 = "CREATE UNIQUE INDEX IF NOT EXISTS token_exchange_idx1 "
            + "ON token_exchange(nxt_txid)";
    private static final String tokenIndexDefinition2 = "CREATE INDEX IF NOT EXISTS token_exchange_idx2 "
            + "ON token_exchange(exchanged)";

    /** Address table definitions */
    private static final String accountTableDefinition = "CREATE TABLE IF NOT EXISTS token_exchange_account ("
            + "db_id IDENTITY,"
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address
            + "account_id BIGINT NOT NULL,"         // Nxt account identifier
            + "public_key BINARY(32))";             // Nxt account public key
    private static final String accountIndexDefinition1 = "CREATE UNIQUE INDEX IF NOT EXISTS token_exchange_account_idx1 "
            + "ON token_exchange_account(bitcoin_address)";
    private static final String accountIndexDefinition2 = "CREATE UNIQUE INDEX IF NOT EXISTS token_exchange_account_idx2 "
            + "ON token_exchange_account(account_id)";

    /** Transaction table definitions */
    private static final String transactionTableDefinition = "CREATE TABLE IF NOT EXISTS token_exchange_transaction ("
            + "db_id IDENTITY,"
            + "height INT NOT NULL,"                // Bitcoin block chain height
            + "bitcoin_txid BINARY(32) NOT NULL,"   // Bitcoin transaction identifier
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address
            + "account_id BIGINT NOT NULL,"         // Nxt account identifier
            + "bitcoin_amount BIGINT NOT NULL,"     // Bitcoin amount
            + "token_amount BIGINT NOT NULL,"       // Number of units issued
            + "exchanged BOOLEAN NOT NULL,"         // TRUE if currency has been issued
            + "nxt_txid BIGINT NOT NULL)";          // Nxt transaction identifier
    private static final String transactionIndexDefinition1 = "CREATE INDEX IF NOT EXISTS token_exchange_transaction_idx1 "
            + "ON token_exchange_transaction(bitcoin_txid)";
    private static final String transactionIndexDefinition2 = "CREATE INDEX IF NOT EXISTS token_exchange_transaction_idx2 "
            + "ON token_exchange_transaction(exchanged)";
    private static final String transactionIndexDefinition3 = "CREATE INDEX IF NOT EXISTS token_exchange_transaction_idx3 "
            + "ON token_exchange_transaction(height)";

    /** Bitcoin chain block table definitions */
    private static final String blockTableDefinition = "CREATE TABLE IF NOT EXISTS token_exchange_block ("
            + "db_id IDENTITY,"
            + "height INT NOT NULL,"                // Block height
            + "block_id BINARY(32) NOT NULL)";      // Block identifier
    private static final String blockIndexDefinition1 = "CREATE UNIQUE INDEX IF NOT EXISTS token_exchange_block_idx1 "
            + "ON token_exchange_block(height)";

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

    /** TokenExchange tables */
    private static TokenExchangeTable tokenTable;

    /**
     * Initialize the database support
     *
     * We use the nxt_txid=0 row in the token_exchange table to save status information:
     *   - token_amount is the database version
     *   - bitcoin_address contains descriptive text
     *
     * @throws  SQLException    SQL error occurred
     */
    static void init() throws SQLException {
        tokenTable = new TokenExchangeTable("token_exchange");
        try (Connection conn = Db.db.getConnection();
                Statement stmt = conn.createStatement()) {
            int version = 0;
            try {
                try (ResultSet rs = stmt.executeQuery("SELECT token_amount FROM token_exchange WHERE nxt_txid=0")) {
                    if (!rs.next()) {
                        throw new SQLException("TokenExchange database is corrupted - recreating");
                    }
                    version = rs.getInt("token_amount");
                    if (version > dbVersion) {
                        throw new RuntimeException("Version " + version + " TokenExchange database is not supported");
                    }
                }
            } catch (SQLException exc) {
                Logger.logInfoMessage("Creating new TokenExchange database");
                stmt.execute("DROP INDEX IF EXISTS token_exchange_idx1");
                stmt.execute("DROP INDEX IF EXISTS token_exchange_idx2");
                stmt.execute("DROP TABLE IF EXISTS token_exchange");
                stmt.execute("DROP INDEX IF EXISTS token_exchange_account_idx1");
                stmt.execute("DROP INDEX IF EXISTS token_exchange_account_idx2");
                stmt.execute("DROP TABLE IF EXISTS token_exchange_account");
                stmt.execute("DROP INDEX IF EXISTS token_exchange_transaction_idx1");
                stmt.execute("DROP INDEX IF EXISTS token_exchange_transaction_idx2");
                stmt.execute("DROP INDEX IF EXISTS token_exchange_transaction_idx3");
                stmt.execute("DROP TABLE IF EXISTS token_exchange_transaction");
                stmt.execute("DROP INDEX IF EXISTS token_exchange_block_idx1");
                stmt.execute("DROP TABLE IF EXISTS token_exchange_block");
            }
            switch (version) {
                case 0:
                    stmt.execute(tokenTableDefinition);
                    stmt.execute(tokenIndexDefinition1);
                    stmt.execute(tokenIndexDefinition2);
                    stmt.executeUpdate("INSERT INTO token_exchange "
                        + "(nxt_txid,sender,height,exchanged,token_amount,bitcoin_amount,bitcoin_address) "
                        + "VALUES(0,0,0,false,1,0,'Token Exchange Database')");
                case 1:
                    stmt.execute(accountTableDefinition);
                    stmt.execute(accountIndexDefinition1);
                    stmt.execute(accountIndexDefinition2);
                    stmt.execute(transactionTableDefinition);
                    stmt.execute(transactionIndexDefinition1);
                    stmt.execute(transactionIndexDefinition2);
                case 2:
                    stmt.execute(blockTableDefinition);
                    stmt.execute(blockIndexDefinition1);
                    stmt.execute("DROP INDEX token_exchange_transaction_idx1");
                    stmt.execute("ALTER TABLE token_exchange_transaction ADD COLUMN height INT");
                    stmt.execute("UPDATE token_exchange_transaction SET height=0");
                    stmt.execute("ALTER TABLE token_exchange_transaction ALTER COLUMN height SET NOT NULL");
                    stmt.execute(transactionIndexDefinition1);
                    stmt.execute(transactionIndexDefinition3);
                // Add new database version processing here
                    stmt.executeUpdate("UPDATE token_exchange SET token_amount=" + dbVersion + " WHERE nxt_txid=0");
                default:
                    Logger.logInfoMessage("Using Version " + dbVersion + " TokenExchange database");
            }
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
                PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM token_exchange WHERE nxt_txid=?")) {
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
     * @return                  Transaction token or null if not found
     */
    static TokenTransaction getToken(long id) {
        TokenTransaction tx = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange WHERE nxt_txid=?")) {
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
     * @param   exchanged       TRUE to return exchanged tokens
     * @return                  List of transaction tokens
     */
    static List<TokenTransaction> getTokens(int height, boolean exchanged) {
        List<TokenTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange "
                        + "WHERE " + (exchanged ? "" : "exchanged=false AND ")
                        + "height > ? ORDER BY height ASC")) {
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
                        + "WHERE exchanged=false AND height>0 AND height<=? ORDER BY HEIGHT ASC")) {
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
     * @return                  TRUE if the token was stored
     */
    static boolean storeToken(TokenTransaction tx) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO token_exchange "
                        + "(nxt_txid,sender,height,exchanged,token_amount,bitcoin_amount,bitcoin_address) "
                        + "VALUES(?,?,?,false,?,?,?)")) {
            stmt.setLong(1, tx.getNxtTxId());
            stmt.setLong(2, tx.getSenderId());
            stmt.setInt(3, tx.getHeight());
            stmt.setLong(4, tx.getTokenAmount());
            stmt.setLong(5, tx.getBitcoinAmount());
            stmt.setString(6, tx.getBitcoinAddress());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store transaction in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Update the token exchange status
     *
     * @param   tx              Token transaction
     * @return                  TRUE if the token was updated
     */
    static boolean updateToken(TokenTransaction tx) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE token_exchange "
                        + "SET exchanged=true,bitcoin_txid=? WHERE nxt_txid=?")) {
            stmt.setBytes(1, tx.getBitcoinTxId());
            stmt.setLong(2, tx.getNxtTxId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update transaction in TokenExchange table", exc);
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
                PreparedStatement stmt = conn.prepareStatement("DELETE FROM token_exchange WHERE nxt_txid=?")) {
            stmt.setLong(1, id);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to delete transaction from TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Get the current Bitcoin chain height
     *
     * @return                  Chain height, 0 if no blocks in table, or -1 if an error occurred
     */
    static int getChainHeight() {
        int height = -1;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT MAX(height) FROM token_exchange_block")) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    height = rs.getInt(1);
                } else {
                    height = 0;
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get bitcoin chain height from TokenExchange table", exc);
        }
        return height;
    }

    /**
     * Get a Bitcoin chain block
     *
     * @param   height          Block height
     * @return                  Block identifier or null
     */
    static byte[] getChainBlock(int height) {
        byte[] blockId = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT block_id FROM token_exchange_block "
                        + "WHERE height=?")) {
            stmt.setInt(1, height);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    blockId = rs.getBytes("block_id");
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get bitcoin block identifier from TokenExchange table", exc);
        }
        return blockId;
    }

    /**
     * Store new Bitcoin chain block
     *
     * @param   height          Chain height
     * @param   blockId         Block identifier
     * @return                  TRUE if the block was stored
     */
    static boolean storeChainBlock(int height, byte[] blockId) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO token_exchange_block "
                        + "(height,block_id) VALUES(?,?)")) {
            stmt.setInt(1, height);
            stmt.setBytes(2, blockId);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store bitcoin chain block in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Pop Bitcoin chain blocks at or above the specified height
     */
    static boolean popChainBlock(int height) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt1 = conn.prepareStatement("DELETE FROM token_exchange_block "
                        + "WHERE height>=?");
                 PreparedStatement stmt2 = conn.prepareStatement("DELETE FROM token_exchange_transaction "
                        + "WHERE height>=?")) {
            stmt2.setInt(1, height);
            stmt2.executeUpdate();
            stmt1.setInt(1, height);
            count = stmt1.executeUpdate();
        } catch (SQLException | RuntimeException exc) {
            Logger.logErrorMessage("Unable to pop bitcoin chain block from TokenExchange table", exc);
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
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO token_exchange_account "
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
     * Get the Bitcoin account associated with a Nxt account identifier
     *
     * @param   account_id      Account identifier
     * @return                  Bitcoin account or null
     */
    static BitcoinAccount getAccount(long accountId) {
        BitcoinAccount account = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange_account "
                        + "WHERE account_id=?")) {
            stmt.setLong(1, accountId);
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
     * Get the Nxt account associated with a Bitcoin address
     *
     * @param   address         Bitcoin address
     * @return                  Nxt account or null
     */
    static BitcoinAccount getAccount(String address) {
        BitcoinAccount account = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange_account "
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
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange_account "
                        + "ORDER BY db_id")) {
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
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO token_exchange_transaction "
                        + "(bitcoin_txid,height,bitcoin_address,bitcoin_amount,token_amount,account_id,exchanged,nxt_txid) "
                        + "VALUES(?,?,?,?,?,?,false,0)")) {
            stmt.setBytes(1, tx.getBitcoinTxId());
            stmt.setInt(2, tx.getHeight());
            stmt.setString(3, tx.getBitcoinAddress());
            stmt.setLong(4, tx.getBitcoinAmount());
            stmt.setLong(5, tx.getTokenAmount());
            stmt.setLong(6, tx.getAccountId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store transaction in TokenExchange table", exc);
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
                PreparedStatement stmt = conn.prepareStatement("UPDATE token_exchange_transaction "
                        + "SET exchanged=true,nxt_txid=? WHERE bitcoin_txid=?")) {
            stmt.setLong(1, tx.getNxtTxId());
            stmt.setBytes(2, tx.getBitcoinTxId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update transaction in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * See if a Bitcoin transation exists
     *
     * @param   id              Transaction identifier
     * @return                  TRUE if the Bitcoin transaction exists
     */
    static boolean transactionExists(byte[] id) {
        boolean exists = false;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM token_exchange_transaction "
                        + "WHERE bitcoin_txid=?")) {
            stmt.setBytes(1, id);
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
     * Get a Bitcoin transaction
     *
     * @param   txid            Bitcoin transaction identifier
     * @return                  Bitcoin transaction or null
     */
    static BitcoinTransaction getTransaction(byte[] txid) {
        BitcoinTransaction tx = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange_transaction "
                        + "WHERE bitcoin_txid=?")) {
            stmt.setBytes(1, txid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    tx = new BitcoinTransaction(rs);
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get transaction from TokenExchange table", exc);
        }
        return tx;
    }

    /**
     * Get the Bitcoin transactions
     *
     * @param   address         Bitcoin address or null for all addresses
     * @param   exchanged       Include processed transactions
     * @return                  Bitcoin transaction list
     */
    static List<BitcoinTransaction> getTransactions(String address, boolean exchanged) {
        List<BitcoinTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM token_exchange_transaction "
                        + (address!=null ? (exchanged ? "WHERE bitcoin_address=? " :
                                                        "WHERE bitcoin_address=? AND exchanged=false ") :
                                           (exchanged ? "" : "WHERE exchanged=false "))
                        + "ORDER BY db_id")) {
            if (address != null) {
                stmt.setString(1, address);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    txList.add(new BitcoinTransaction(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get transaction from TokenExchange table", exc);
        }
        return txList;
    }
}
