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

import org.bitcoinj.core.Context;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.DeterministicSeed;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
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

    /** UTF-8 character set */
    private static Charset UTF_8;
    static {
        try {
            UTF_8 = Charset.forName("UTF-8");
        } catch (Exception exc) {
            // UTF-8 is always defined
        }
    }

    /** Database schema name */
    private static final String DB_SCHEMA = "TOKEN_EXCHANGE_4";

    /** Control table name */
    private static final String CONTROL_TABLE = DB_SCHEMA + ".control";

    /** Account table name */
    private static final String ACCOUNT_TABLE = DB_SCHEMA + ".account";

    /** Unspent table name */
    private static final String UNSPENT_TABLE = DB_SCHEMA + ".unspent";

    /** Broadcast table name */
    private static final String BROADCAST_TABLE = DB_SCHEMA + ".broadcast";

    /** Nxt transaction table name */
    private static final String NXT_TABLE = DB_SCHEMA + ".nxt";

    /** Bitcoin transaction table name */
    private static final String BITCOIN_TABLE = DB_SCHEMA + ".bitcoin";

    /** Current database version */
    private static final int DB_VERSION = 1;

    /** Schema definition */
    private static final String schemaDefinition = "CREATE SCHEMA IF NOT EXISTS " + DB_SCHEMA;

    /** Control table definitions */
    private static final String controlTableDefinition = "CREATE TABLE IF NOT EXISTS " + CONTROL_TABLE  + " ("
            + "db_version INT NOT NULL,"            // Database version
            + "creation_time LONG NOT NULL,"        // Database creation time (seconds since Unix epoch)
            + "seed BINARY NOT NULL,"               // Encoded deterministic seed
            + "exchange_rate BIGINT NOT NULL,"      // Exchange rate
            + "wallet_key INT NOT NULL,"            // Wallet key identifier
            + "external_key INT NOT NULL,"          // Next external key identifier
            + "internal_key INT NOT NULL)";         // Next internal key identifier

    /** Account table definitions */
    private static final String accountTableDefinition = "CREATE TABLE IF NOT EXISTS " + ACCOUNT_TABLE + " ("
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address
            + "child_number INT NOT NULL,"          // External key child number
            + "account_id BIGINT NOT NULL,"         // Nxt account identifier
            + "public_key BINARY(32),"              // Nxt account public key
            + "timestamp INT NOT NULL)";            // Timestamp
    private static final String accountIndexDefinition1 = "CREATE UNIQUE INDEX IF NOT EXISTS "
                    + ACCOUNT_TABLE + "_idx1 ON " + ACCOUNT_TABLE + "(bitcoin_address)";
    private static final String accountIndexDefinition2 = "CREATE INDEX IF NOT EXISTS "
                    + ACCOUNT_TABLE + "_idx2 ON " + ACCOUNT_TABLE + "(account_id)";

    /** Transaction broadcast table definitions */
    private static final String broadcastTableDefinition = "CREATE TABLE IF NOT EXISTS " + BROADCAST_TABLE + " ("
            + "txid BINARY(32) NOT NULL,"           // Transaction identifier
            + "payload BINARY NOT NULL)";           // Transaction payload

    /** Unspent Bitcoin transaction outputs */
    private static final String unspentTableDefinition = "CREATE TABLE IF NOT EXISTS " + UNSPENT_TABLE + " ("
            + "txid BINARY(32) NOT NULL,"           // Transaction identifier
            + "index INT NOT NULL,"                 // Output index
            + "blkid BINARY(32) NOT NULL,"          // Block identifier
            + "amount BIGINT NOT NULL,"             // Bitcoin amount
            + "child_number INT NOT NULL,"          // Determinstic key child number
            + "parent_number INT NOT NULL,"         // Deterministic key parent number
            + "spent BOOLEAN NOT NULL,"             // Output has been spent
            + "height INT NOT NULL)";               // Block chain height
    private static final String unspentIndexDefinition1 = "CREATE INDEX IF NOT EXISTS "
                    + UNSPENT_TABLE + "_idx1 ON " + UNSPENT_TABLE + "(spent,height)";
    private static final String unspentIndexDefinition2 = "CREATE INDEX IF NOT EXISTS "
                    + UNSPENT_TABLE + "_idx2 ON " + UNSPENT_TABLE + "(txid)";

    /** Nxt transaction table definitions */
    private static final String nxtTableDefinition = "CREATE TABLE IF NOT EXISTS " + NXT_TABLE + " ("
            + "nxt_txid BIGINT NOT NULL,"           // Nxt transaction identifier
            + "sender BIGINT NOT NULL,"             // Nxt transaction sender identifier
            + "height INT NOT NULL,"                // Nxt transaction height
            + "timestamp INT NOT NULL,"             // Nxt transaction timestamp
            + "exchanged BOOLEAN NOT NULL,"         // TRUE if currency exchanged for Bitcoin
            + "token_amount BIGINT NOT NULL,"       // Number of units redeemed / Database version
            + "bitcoin_amount BIGINT NOT NULL,"     // Bitcoin amount
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address
            + "bitcoin_txid BINARY(32))";           // Bitcoin transaction identifier
    private static final String nxtIndexDefinition1 = "CREATE UNIQUE INDEX IF NOT EXISTS "
                    + NXT_TABLE + "_idx1 ON " + NXT_TABLE + "(nxt_txid)";
    private static final String nxtIndexDefinition2 = "CREATE INDEX IF NOT EXISTS "
                    + NXT_TABLE + "_idx2 ON " + NXT_TABLE + "(exchanged)";

    /** Bitcoin transaction table definitions */
    private static final String bitcoinTableDefinition = "CREATE TABLE IF NOT EXISTS " + BITCOIN_TABLE + " ("
            + "height INT NOT NULL,"                // Bitcoin block chain height (0 if not in chain yet)
            + "timestamp INT NOT NULL,"             // Bitcoin transaction timestamp (0 if not in chain yet)
            + "bitcoin_txid BINARY(32) NOT NULL,"   // Bitcoin transaction identifier
            + "bitcoin_blkid BINARY(32) NOT NULL,"  // Bitcoin block identifier
            + "bitcoin_address VARCHAR NOT NULL,"   // Bitcoin address
            + "account_id BIGINT NOT NULL,"         // Nxt account identifier
            + "bitcoin_amount BIGINT NOT NULL,"     // Bitcoin amount
            + "token_amount BIGINT NOT NULL,"       // Number of units issued
            + "exchanged BOOLEAN NOT NULL,"         // TRUE if currency has been issued
            + "nxt_txid BIGINT NOT NULL)";          // Nxt transaction identifier
    private static final String bitcoinIndexDefinition1 = "CREATE INDEX IF NOT EXISTS "
                    + BITCOIN_TABLE + "_idx1 ON " + BITCOIN_TABLE + "(bitcoin_txid)";
    private static final String bitcoinIndexDefinition2 = "CREATE INDEX IF NOT EXISTS "
                    + BITCOIN_TABLE + "_idx2 ON " + BITCOIN_TABLE + "(exchanged)";

    /**
     * Nxt transaction table
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
     * @throws  SQLException    SQL error occurred
     */
    static void init() throws SQLException {
        tokenTable = new TokenExchangeTable(NXT_TABLE);
        try (Connection conn = Db.db.getConnection();
                Statement stmt = conn.createStatement()) {
            //
            // Update the table definitions if necessary
            //
            int version = 0;
            try {
                try (ResultSet rs = stmt.executeQuery("SELECT db_version FROM " + CONTROL_TABLE)) {
                    if (rs.next()) {
                        version = rs.getInt("db_version");
                        if (version > DB_VERSION) {
                            throw new IllegalStateException("Version " + version + " TokenExchange database is not supported");
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
                    stmt.execute(controlTableDefinition);
                    stmt.executeUpdate("INSERT INTO " + CONTROL_TABLE
                        + " (db_version,creation_time,seed,exchange_rate,wallet_key,external_key,internal_key)"
                        + " VALUES(1," + System.currentTimeMillis()/1000 + ",x'0000',"
                        + TokenAddon.exchangeRate.movePointRight(8).toPlainString() + ",0,1,0)");
                    stmt.execute(unspentTableDefinition);
                    stmt.execute(unspentIndexDefinition1);
                    stmt.execute(unspentIndexDefinition2);
                    stmt.execute(broadcastTableDefinition);
                    stmt.execute(accountTableDefinition);
                    stmt.execute(accountIndexDefinition1);
                    stmt.execute(accountIndexDefinition2);
                    stmt.execute(nxtTableDefinition);
                    stmt.execute(nxtIndexDefinition1);
                    stmt.execute(nxtIndexDefinition2);
                    stmt.execute(bitcoinTableDefinition);
                    stmt.execute(bitcoinIndexDefinition1);
                    stmt.execute(bitcoinIndexDefinition2);
                    //
                    // Add new database version processing here
                    //
                    stmt.executeUpdate("UPDATE " + CONTROL_TABLE + " SET db_version=" + DB_VERSION);
                default:
                    Logger.logInfoMessage("Using Version " + DB_VERSION + " TokenExchange database");
            }
            //
            // Get the current token exchange rate
            //
            try (ResultSet rs = stmt.executeQuery("SELECT exchange_rate FROM " + CONTROL_TABLE)) {
                if (rs.next()) {
                    long exchangeRate = rs.getLong("exchange_rate");
                    if (exchangeRate != 0) {
                        TokenAddon.exchangeRate = BigDecimal.valueOf(exchangeRate, 8).stripTrailingZeros();
                    }
                }
            }
        }
    }

    /**
     * Get the database creation time
     *
     * @return                  Database creation time (seconds since Unix epoch)
     */
    static long getCreationTime() {
        long creationTime = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT creation_time FROM " + CONTROL_TABLE)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    creationTime = rs.getLong("creation_time");
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get creation time from TokenExchange table", exc);
        }
        return creationTime;
    }

    /**
     * Store the deterministic key seed
     *
     * @param   seed            Deterministic key seed
     */
    static boolean storeSeed(DeterministicSeed seed) {
        int count = 0;
        //
        // Encode the deterministic seed
        //
        byte[] seedBytes = seed.getSeedBytes();
        List<String> codes = seed.getMnemonicCode();
        if (seedBytes == null || codes == null) {
            throw new IllegalArgumentException("Deterministic seed is not valid");
        }
        long creationTime = seed.getCreationTimeSeconds();
        int codeLength = 2;
        byte[][] codeBytes = new byte[codes.size()][];
        int index = 0;
        for (String code : codes) {
            codeBytes[index] = code.getBytes(UTF_8);
            codeLength += 2 + codeBytes[index].length;
            index++;
        }
        int length = 2 + seedBytes.length + codeLength + 8;
        byte[] encodedSeed = new byte[length];
        ByteBuffer buf = ByteBuffer.wrap(encodedSeed);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short)seedBytes.length).put(seedBytes).putShort((short)codes.size());
        for (byte[] bytes : codeBytes) {
            buf.putShort((short)bytes.length).put(bytes);
        }
        buf.putLong(creationTime);
        //
        // Store the encoded seed
        //
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + CONTROL_TABLE + " SET seed=?")) {
            stmt.setBytes(1, encodedSeed);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store deterministic seed in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Get the deterministic seed
     *
     * @return                  Deterministic seed or null
     */
    static DeterministicSeed getSeed() {
        DeterministicSeed seed = null;
        byte[] encodedSeed = null;
        //
        // Get the encoded seed
        //
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT seed FROM " + CONTROL_TABLE)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    encodedSeed = rs.getBytes("seed");
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get deterministic seed from TokenExchange table", exc);
        }
        //
        // Decode the seed
        //
        if (encodedSeed != null && encodedSeed.length > 2) {
            ByteBuffer buf = ByteBuffer.wrap(encodedSeed);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            try {
                int length = buf.getShort();
                byte[] seedBytes = new byte[length];
                buf.get(seedBytes);
                int count = buf.getShort();
                List<String> codes = new ArrayList<>(count);
                for (int i=0; i<count; i++) {
                    length = buf.getShort();
                    byte[] bytes = new byte[length];
                    buf.get(bytes);
                    codes.add(new String(bytes, UTF_8));
                }
                long creationTime = buf.getLong();
                seed = new DeterministicSeed(seedBytes, codes, creationTime);
            } catch (IndexOutOfBoundsException exc) {
                Logger.logErrorMessage("Encoded seed is not valid", exc);
            }
        }
        return seed;
    }

    /**
     * Get the next child for the specified parent
     *
     * @param   parent          Parent (0 = external, 1 = internal)
     * @return                  Next child or null
     */
    static ChildNumber getNewChild(ChildNumber parent) {
        ChildNumber child = null;
        String column = (parent.getI() == 0 ? "external_key" : "internal_key");
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt1 = conn.prepareStatement("SELECT " + column + " FROM " + CONTROL_TABLE);
                PreparedStatement stmt2 = conn.prepareStatement("UPDATE " + CONTROL_TABLE + " SET " + column + "=?")) {
            try (ResultSet rs = stmt1.executeQuery()) {
                if (rs.next()) {
                    child = new ChildNumber(rs.getInt(column));
                }
            }
            if (child != null) {
                stmt2.setInt(1, child.getI() + 1);
                stmt2.executeUpdate();
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update child number in TokenExchange table", exc);
        }
        return child;
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
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + CONTROL_TABLE + " SET exchange_rate=?")) {
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
     * Get an unspent output
     *
     * @param   txid            Transaction identifier
     * @param   index           Transaction output index
     * @param   blkid           Block identifier
     * @return                  Unspent output or null
     */
    static BitcoinUnspent getUnspentOutput(byte[] txid, int index, byte[] blkid) {
        BitcoinUnspent unspent = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + UNSPENT_TABLE
                        + " WHERE txid=? AND index=? AND blkid=?")) {
            stmt.setBytes(1, txid);
            stmt.setInt(2, index);
            stmt.setBytes(3, blkid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    unspent = new BitcoinUnspent(rs);
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get unspent output from Token Exchange table", exc);
        }
        return unspent;
    }

    /**
     * Get all active unspent outputs ordered by amount
     *
     * @return                  List of unspent outputs
     */
    static List<BitcoinUnspent> getUnspentOutputs() {
        List<BitcoinUnspent> unspentList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + UNSPENT_TABLE
                        + " WHERE spent=false AND height>0 ORDER BY amount ASC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    unspentList.add(new BitcoinUnspent(rs));
                }
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get unspent outputs from Token Exchange table", exc);
        }
        return unspentList;
    }

    /**
     * Store a new unspent output
     *
     * @param   unspent         Unspent output
     * @return                  TRUE if the output was stored
     */
    static boolean storeUnspentOutput(BitcoinUnspent unspent) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + UNSPENT_TABLE
                        + " (txid,index,blkid,amount,spent,height,child_number,parent_number)"
                        + " VALUES(?,?,?,?,false,?,?,?)")) {
            stmt.setBytes(1, unspent.getId());
            stmt.setInt(2, unspent.getIndex());
            stmt.setBytes(3, unspent.getBlockId());
            stmt.setLong(4, unspent.getAmount());
            stmt.setInt(5, unspent.getHeight());
            stmt.setInt(6, unspent.getChildNumber().getI());
            stmt.setInt(7, unspent.getParentNumber().getI());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store unspent output in Token Exchange table", exc);
        }
        return count != 0;
    }

    /**
     * Update an unspent output
     *
     * @param   unspent         Unspent output
     * @return                  TRUE if the output was updated
     */
    static boolean updateUnspentOutput(BitcoinUnspent unspent) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + UNSPENT_TABLE
                        + " SET spent=?,height=? WHERE txid=? AND index=? AND blkid=?")) {
            stmt.setBoolean(1, unspent.isSpent());
            stmt.setInt(2, unspent.getHeight());
            stmt.setBytes(3, unspent.getId());
            stmt.setInt(4, unspent.getIndex());
            stmt.setBytes(5, unspent.getBlockId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update unspent output in Token Exchange table", exc);
        }
        return count != 0;
    }

    /**
     * Mark all versions of a transaction output as spent
     *
     * @param   txid            Transaction identifier
     * @param   index           Transaction output index
     * @return                  TRUE if the output was marked as spent
     */
    static boolean spendOutput(byte[] txid, int index) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + UNSPENT_TABLE
                        + " SET spent=true WHERE txid=? AND index=?")) {
            stmt.setBytes(1, txid);
            stmt.setInt(2, index);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update unspent output in Token Exchange table", exc);
        }
        return count != 0;
    }

    /**
     * Deactivate external unspent outputs above the specified block chain height
     *
     * @param   height          Block chain height
     * @return                  Unspent amount deactivated
     */
    static long deactivateUnspentOutputs(int height) {
        long amount = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt1 = conn.prepareStatement("SELECT SUM(amount) AS amount FROM " + UNSPENT_TABLE
                        + " WHERE spent=false AND height>? AND parent_number=0");
                PreparedStatement stmt2 = conn.prepareStatement("UPDATE " + UNSPENT_TABLE
                        + " SET height=0 WHERE spent=false AND height>? AND parent_number=0")) {
            stmt1.setInt(1, height);
            try (ResultSet rs = stmt1.executeQuery()) {
                if (rs.next()) {
                    amount = rs.getLong("amount");
                }
            }
            if (amount != 0) {
                stmt2.setInt(1, height);
                stmt2.executeUpdate();
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to deactivate unspent outputs in Token Exchange table", exc);
        }
        return amount;
    }

    /**
     * Activate external unspent outputs for transactions in the specified block
     *
     * @param   blkid           Block identifier
     * @param   height          Activation height
     * @return                  Unspent amount activated
     */
    static long activateUnspentOutputs(byte[] blkid, int height) {
        long amount = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt1 = conn.prepareStatement("SELECT SUM(amount) AS amount FROM " + UNSPENT_TABLE
                        + " WHERE spent=false AND blkid=?");
                PreparedStatement stmt2 = conn.prepareStatement("UPDATE " + UNSPENT_TABLE
                        + " SET height=? WHERE spent=false AND blkid=?")) {
            stmt1.setBytes(1, blkid);
            try (ResultSet rs = stmt1.executeQuery()) {
                if (rs.next()) {
                    amount = rs.getLong("amount");
                }
            }
            if (amount != 0) {
                stmt2.setInt(1, height);
                stmt2.setBytes(2, blkid);
                stmt2.executeUpdate();
            }
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to activate unspent outputs ind Token Exchange table", exc);
        }
        return amount;
    }

    /**
     * Get a broadcast transaction
     *
     * @param   txid            Transaction identifier
     * @return                  Transaction or null
     */
    static Transaction getBroadcastTransaction(byte[] txid) {
        Transaction tx = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT payload FROM " + BROADCAST_TABLE
                        + " WHERE txid=?")) {
            stmt.setBytes(1, txid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    tx = new Transaction(Context.get().getParams(), rs.getBytes("payload"));
                    tx.getConfidence().setSource(TransactionConfidence.Source.SELF);
                }
            }
        } catch (ProtocolException exc) {
            Logger.logErrorMessage("Unable to create Bitcoin transaction", exc);
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get a broadcast transaction from the Token Exchange table", exc);
        }
        return tx;
    }

    /**
     * Get all broadcast transactions
     *
     * @return                  List of broadcast transactions
     */
    static List<Transaction> getBroadcastTransactions() {
        List<Transaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT payload FROM " + BROADCAST_TABLE)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Transaction tx = new Transaction(Context.get().getParams(), rs.getBytes("payload"));
                    tx.getConfidence().setSource(TransactionConfidence.Source.SELF);
                    txList.add(tx);
                }
            }
        } catch (ProtocolException exc) {
            Logger.logErrorMessage("Unable to create Bitcoin transaction", exc);
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to get broadcast transactions from the Token Exchange table", exc);
        }
        return txList;
    }

    /**
     * Store a broadcast transaction
     *
     * @param   tx              Transaction
     * @return                  TRUE if the transaction was stored
     */
    static boolean storeBroadcastTransaction(Transaction tx) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + BROADCAST_TABLE
                        + " (txid,payload) VALUES(?,?)")) {
            byte[] payload = tx.bitcoinSerialize();
            stmt.setBytes(1, tx.getHash().getBytes());
            stmt.setBytes(2, payload);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store broadcast transaction in Token Exchange table", exc);
        }
        return count != 0;
    }

    /**
     * Delete a broadcast transaction
     *
     * @param   txId            Transaction identifier
     * @return                  TRUE if the transaction was deleted
     */
    static boolean deleteBroadcastTransaction(byte[] txId) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + BROADCAST_TABLE
                        + " WHERE txid=?")) {
            stmt.setBytes(1, txId);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to delete broadcast transaction from Token Exchange table", exc);
        }
        return count != 0;
    }

    /**
     * See if a token transaction exists
     *
     * @param   id              Transaction identifier
     * @return                  TRUE if the transaction token exists
     */
    static boolean tokenExists(long id) {
        boolean exists = false;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + NXT_TABLE
                        + " WHERE nxt_txid=?")) {
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
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + NXT_TABLE
                        + " WHERE nxt_txid=?")) {
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
     * Get token transactions at or above the specified height
     *
     * @param   height          Block height
     * @param   exchanged       TRUE to return exchanged tokens
     * @return                  List of token transactions
     */
    static List<TokenTransaction> getTokens(int height, boolean exchanged) {
        List<TokenTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + NXT_TABLE
                        + " WHERE height>=? " + (exchanged ? "" : "AND exchanged=false ")
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
     * Get pending token transaction at or below the specified height
     *
     * @param   height          Block height
     * @return                  List of transaction tokens
     */
    static List<TokenTransaction> getPendingTokens(int height) {
        List<TokenTransaction> txList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + NXT_TABLE
                        + " WHERE exchanged=false AND height<=? ORDER BY height ASC")) {
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
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + NXT_TABLE
                        + " (nxt_txid,sender,height,timestamp,exchanged,token_amount,bitcoin_amount,bitcoin_address)"
                        + " VALUES(?,?,?,?,false,?,?,?)")) {
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
     * Update the token transaction status
     *
     * @param   tx              Token transaction
     * @return                  TRUE if the token was updated
     */
    static boolean updateToken(TokenTransaction tx) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + NXT_TABLE
                        + " SET exchanged=true,bitcoin_txid=? WHERE nxt_txid=?")) {
            stmt.setBytes(1, tx.getBitcoinTxId());
            stmt.setLong(2, tx.getNxtTxId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update Nxt transaction in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Delete a token transaction
     *
     * @param   id              Token identifier
     * @return                  TRUE if the token was deleted
     */
    static boolean deleteToken(long id) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + NXT_TABLE
                        + " WHERE nxt_txid=?")) {
            stmt.setLong(1, id);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to delete Nxt transaction from TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Store an account
     *
     * @param   account         Account
     * @return                  TRUE if the address was stored
     */
    static boolean storeAccount(BitcoinAccount account) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + ACCOUNT_TABLE
                        + " (bitcoin_address,child_number,account_id,public_key,timestamp)"
                        + " VALUES(?,?,?,?,?)")) {
            stmt.setString(1, account.getBitcoinAddress());
            stmt.setInt(2, account.getChildNumber());
            stmt.setLong(3, account.getAccountId());
            if (account.getPublicKey() != null) {
                stmt.setBytes(4, account.getPublicKey());
            } else {
                stmt.setNull(4, Types.BINARY);
            }
            stmt.setInt(5, account.getTimestamp());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to store account in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Get the accounts associated with a Nxt account identifier ordered by timestamp
     *
     * @param   account_id      Account identifier
     * @return                  Account list
     */
    static List<BitcoinAccount> getAccount(long accountId) {
        List<BitcoinAccount> accountList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + ACCOUNT_TABLE
                        + " WHERE account_id=? ORDER BY timestamp ASC")) {
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
     * Get the account associated with a Bitcoin address
     *
     * @param   address         Bitcoin address
     * @return                  Nxt account or null
     */
    static BitcoinAccount getAccount(String address) {
        BitcoinAccount account = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + ACCOUNT_TABLE
                        + " WHERE bitcoin_address=?")) {
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
     * Get all of the accounts ordered by account identifier and timestamp
     *
     * @return                  Account list
     */
    static List<BitcoinAccount> getAccounts() {
        List<BitcoinAccount> accountList = new ArrayList<>();
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + ACCOUNT_TABLE
                        + " ORDER BY account_id ASC,timestamp ASC")) {
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
     * Delete an account address
     *
     * @param   address         Bitcoin address associated with an account
     * @return                  TRUE if the address was deleted
     */
    static boolean deleteAccountAddress(String address) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + ACCOUNT_TABLE
                        + " WHERE bitcoin_address=?")) {
            stmt.setString(1, address);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to delete account address from TokenExchange table", exc);
        }
        return count != 0;
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
                PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + BITCOIN_TABLE
                        + " (bitcoin_txid,bitcoin_blkid,height,timestamp,bitcoin_address,bitcoin_amount,"
                        + "  token_amount,account_id,exchanged,nxt_txid)"
                        + " VALUES(?,?,?,?,?,?,?,?,false,0)")) {
            stmt.setBytes(1, tx.getBitcoinTxId());
            stmt.setBytes(2, tx.getBitcoinBlockId());
            stmt.setInt(3, tx.getHeight());
            stmt.setInt(4, tx.getTimestamp());
            stmt.setString(5, tx.getBitcoinAddress());
            stmt.setLong(6, tx.getBitcoinAmount());
            stmt.setLong(7, tx.getTokenAmount());
            stmt.setLong(8, tx.getAccountId());
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
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + BITCOIN_TABLE
                        + " SET exchanged=?,nxt_txid=?"
                        + " WHERE bitcoin_txid=? AND bitcoin_blkid=?")) {
            stmt.setBoolean(1, tx.isExchanged());
            stmt.setLong(2, tx.getNxtTxId());
            stmt.setBytes(3, tx.getBitcoinTxId());
            stmt.setBytes(4, tx.getBitcoinBlockId());
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to update Bitcoin transaction in TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * See if a Bitcoin transaction exists for the specified Bitcoin address
     *
     * @param   address         Bitcoin address
     * @return                  TRUE if a Bitcoin transaction exists
     */
    static boolean transactionExists(String address) {
        boolean exists = false;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + BITCOIN_TABLE
                        + " WHERE bitcoin_address=?")) {
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
     * See if a Bitcoin transaction exists
     *
     * @param   txid            Transaction identifier
     * @param   blkid           Block identifier
     * @return                  TRUE if a Bitcoin transaction exists
     */
    static boolean transactionExists(byte[] txid, byte[] blkid) {
        boolean exists = false;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + BITCOIN_TABLE
                        + " WHERE bitcoin_address=? AND bitcoin_blkid=?")) {
            stmt.setBytes(1, txid);
            stmt.setBytes(2, blkid);
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
     * @param   blkid           Bitcoin block identifier
     * @return                  Bitcoin transaction or null
     */
    static BitcoinTransaction getTransaction(byte[] txid, byte[] blkid) {
        BitcoinTransaction tx = null;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + BITCOIN_TABLE
                        + " WHERE bitcoin_txid=? AND bitcoin_blkid=?")) {
            stmt.setBytes(1, txid);
            stmt.setBytes(2, blkid);
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
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + BITCOIN_TABLE
                        + " WHERE height>=? "
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
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + BITCOIN_TABLE
                        + " WHERE exchanged=false AND height<=? AND height>0 ORDER BY height ASC")) {
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
                PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + BITCOIN_TABLE
                        + " WHERE bitcoin_txid=?")) {
            stmt.setBytes(1, id);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to delete Bitcoin transaction from TokenExchange table", exc);
        }
        return count != 0;
    }

    /**
     * Deactivate Bitcoin transactions
     *
     * @param   height          Deactivate all transactions above this height
     * @return                  Number of transactions updated
     */
    static int deactivateTransactions(int height) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + BITCOIN_TABLE
                        + " SET height=0 WHERE exchanged=false AND height>?")) {
            stmt.setInt(1, height);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to deactivate Bitcoin transactions in Token Exchange table", exc);
        }
        return count;
    }

    /**
     * Activate Bitcoin transactions
     *
     * @param   blkid           Activate all transactions for this block
     * @param   height          Activation height
     * @return                  Number of transactions updated
     */
    static int activateTransactions(byte[] blkid, int height) {
        int count = 0;
        try (Connection conn = Db.db.getConnection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE " + BITCOIN_TABLE
                        + " SET height=? WHERE exchanged=false AND bitcoin_blkid=?")) {
            stmt.setInt(1, height);
            stmt.setBytes(2, blkid);
            count = stmt.executeUpdate();
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to activate Bitcoin transactions in Token Exchange table", exc);
        }
        return count;
    }
}
