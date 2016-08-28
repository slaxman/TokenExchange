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
 * Bitcoin account
 */
class BitcoinAccount {

    /** Bitcoin address */
    private final String address;

    /** Nxt account identifier */
    private final long accountId;

    /** Nxt account public key */
    private final byte[] publicKey;

    /**
     * Create a Bitcoin account
     *
     * @param   address         Bitcoin address
     * @param   accountId       Nxt account identifier
     * @param   publicKey       Nxt account public key (may be null)
     */
    BitcoinAccount(String address, long accountId, byte[] publicKey) {
        this.address = address;
        this.accountId = accountId;
        this.publicKey = publicKey;
    }

    /**
     * Create a Bitcoin account
     *
     * @param   rs              Result set
     * @throws  SQLException    SQL error occurred
     */
    BitcoinAccount(ResultSet rs) throws SQLException {
        this.address = rs.getString("address");
        this.accountId = rs.getLong("account_id");
        this.publicKey = rs.getBytes("public_key");
    }

    /**
     * Get the account address
     *
     * @return                  Bitcoin address
     */
    String getAddress() {
        return address;
    }

    /**
     * Get the account identifier
     *
     * @return                  Nxt account identifier
     */
    long getAccountId() {
        return accountId;
    }

    /**
     * Get the account public key
     *
     * @return                  Nxt account public key or null
     */
    byte[] getPublicKey() {
        return publicKey;
    }
}
