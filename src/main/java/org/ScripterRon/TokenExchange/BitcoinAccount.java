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

import nxt.Nxt;
import nxt.util.Convert;

import org.bitcoinj.core.Context;
import org.bitcoinj.crypto.DeterministicKey;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Bitcoin account
 */
class BitcoinAccount {

    /** Bitcoin address */
    private final String bitcoinAddress;

    /** Deterministic key child number */
    private final int childNumber;

    /** Nxt account identifier */
    private final long accountId;

    /** Nxt account public key */
    private final byte[] publicKey;

    /** Account timestamp (Nxt epoch) */
    private final int timestamp;

    /**
     * Create a Bitcoin account
     *
     * @param   key             Bitcoin key
     * @param   accountId       Nxt account identifier
     * @param   publicKey       Nxt account public key or null
     */
    BitcoinAccount(DeterministicKey key, long accountId, byte[] publicKey) {
        this(key.toAddress(Context.get().getParams()).toBase58(), key.getChildNumber().getI(),
                accountId, publicKey, Nxt.getEpochTime());
    }

    /**
     * Create a Bitcoin account
     *
     * @param   address         Bitcoin address
     * @param   childNumber     Deterministic key child number
     * @param   accountId       Nxt account identifier
     * @param   publicKey       Nxt account public key (null if no public key)
     * @param   timestamp       Account creation timestamp (Nxt epoch)
     */
    BitcoinAccount(String address, int childNumber, long accountId, byte[] publicKey, int timestamp) {
        this.bitcoinAddress = address;
        this.childNumber = childNumber;
        this.accountId = accountId;
        this.publicKey = publicKey;
        this.timestamp = timestamp;
    }

    /**
     * Create a Bitcoin account
     *
     * @param   rs              Result set
     * @throws  SQLException    SQL error occurred
     */
    BitcoinAccount(ResultSet rs) throws SQLException {
        this.bitcoinAddress = rs.getString("bitcoin_address");
        this.childNumber = rs.getInt("child_number");
        this.accountId = rs.getLong("account_id");
        this.publicKey = rs.getBytes("public_key");
        this.timestamp = rs.getInt("timestamp");
    }

    /**
     * Get the Bitcoin address
     *
     * @return                  Bitcoin address
     */
    String getBitcoinAddress() {
        return bitcoinAddress;
    }

    /**
     * Get the deterministic key child number
     *
     * @return                  Child number
     */
    int getChildNumber() {
        return childNumber;
    }

    /**
     * Get the Nxt account identifier
     *
     * @return                  Account identifier
     */
    long getAccountId() {
        return accountId;
    }

    /**
     * Get the Nxt account identifier as a string
     *
     * @return                  RS-encoded account identifier
     */
    String getAccountIdRS() {
        return Convert.rsAccount(accountId);
    }

    /**
     * Get the account public key
     *
     * @return                  Nxt account public key or null
     */
    byte[] getPublicKey() {
        return publicKey;
    }

    /**
     * Get the account creation timestamp
     *
     * @return                  Timestamp (Nxt epoch)
     */
    int getTimestamp() {
        return timestamp;
    }
}
