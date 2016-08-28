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

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Bitcoin transaction
 */
class BitcoinTransaction {

    /** Bitcoin transaction identifier */
    private final byte[] bitcoinTxId;

    /** Bitcoin transaction amount */
    private final long amount;

    /** Nxt account identifier */
    private final long accountId;

    /** Nxt account public key */
    private final byte[] publicKey;

    /** Token issued */
    private boolean exchanged;

    /** Nxt transaction identifier */
    private long nxtTxId;

    /**
     * Create a bitcoin transaction
     *
     * @param   bitcoinTxId     Bitcoin transaction identifier
     * @param   amount          Bitcoin transaction amount
     * @param   accountId       Nxt account identifier
     * @param   publicKey       Nxt account public key (may be null)
     */
    BitcoinTransaction(byte[] bitcoinTxId, long amount, long accountId, byte[] publicKey) {
        this.bitcoinTxId = bitcoinTxId;
        this.amount = amount;
        this.accountId = accountId;
        this.publicKey = publicKey;
        this.exchanged = false;
    }

    /**
     * Create a bitcoin transaction
     *
     * @param   rs              Result set
     * @throws  SQLException    SQL error occurred
     */
    BitcoinTransaction(ResultSet rs) throws SQLException {
        this.bitcoinTxId = rs.getBytes("bitcoin_txid");
        this.amount = rs.getLong("amount");
        this.accountId = rs.getLong("account_id");
        this.publicKey = rs.getBytes("public_key");
        this.exchanged = rs.getBoolean("exchanged");
        this.nxtTxId = rs.getLong("nxt_txid");
    }

    /**
     * Get the bitcoin transaction identifier
     *
     * @return                  Transaction identifier
     */
    byte[] getBitcoinTxId() {
        return bitcoinTxId;
    }

    /**
     * Get the transaction amount
     *
     * @return                  Transaction amount
     */
    long getAmount() {
        return amount;
    }

    /**
     * Get the account identifier
     *
     * @return                  Account identifier
     */
    long getAccountId() {
        return accountId;
    }

    /**
     * Get the account public key
     *
     * @return                  Account public key or null
     */
    byte[] getPublicKey() {
        return publicKey;
    }
    /**
     * Check if the transaction has been process
     *
     * @return                  TRUE if the transaction has been processed
     */
    boolean isExchanged() {
        return exchanged;
    }

    /**
     * Set transaction processed
     *
     * @param   nxtTxId         Nxt transaction identifier
     */
    void setExchanged(long nxtTxId) {
        this.nxtTxId = nxtTxId;
        exchanged = true;
    }

    /**
     * Return the Nxt transaction identifier
     *
     * @return                  Nxt transaction identifier
     */
    long getNxtTxId() {
        return nxtTxId;
    }
}
