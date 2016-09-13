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

import nxt.util.Convert;

import org.bitcoinj.crypto.ChildNumber;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Unspent transaction output (UTXO)
 */
class BitcoinUnspent {

    /** Transaction identifier */
    private final byte[] txid;

    /** Transaction output index */
    private final int index;

    /** Bitcoin amount */
    private final long amount;

    /** Block chain height */
    private int height;

    /** Receiving address child number */
    private final ChildNumber childNumber;

    /** Receiving address parent number */
    private final ChildNumber parentNumber;

    /**
     * Create an unspent output
     *
     * @param   txid            Transaction identifier
     * @param   index           Transaction output index
     * @param   amount          Bitcoin amount
     * @param   height          Block chain height
     * @param   childNumber     Receiving address child number
     * @param   parentNumber    Receiving address parent number
     */
    BitcoinUnspent(byte[] txid, int index, long amount, int height, ChildNumber childNumber, ChildNumber parentNumber) {
        this.txid = txid;
        this.index = index;
        this.amount = amount;
        this.height = height;
        this.childNumber = childNumber;
        this.parentNumber = parentNumber;
    }

    /**
     * Create an unspent output
     *
     * @param   rs              SQL result set
     * @throws  SQLException    Unable to process result set
     */
    BitcoinUnspent(ResultSet rs) throws SQLException {
        this.txid = rs.getBytes("txid");
        this.index = rs.getInt("index");
        this.amount = rs.getLong("amount");
        this.height = rs.getInt("height");
        this.childNumber = new ChildNumber(rs.getInt("child_number"));
        this.parentNumber = new ChildNumber(rs.getInt("parent_number"));
    }

    /**
     * Get the transaction identifier
     *
     * @return                  Transaction identifier
     */
    byte[] getId() {
        return txid;
    }

    /**
     * Get the transaction identifier as a string
     *
     * @return                  Transaction identifier string
     */
    String getIdString() {
        return Convert.toHexString(txid);
    }

    /**
     * Get the transaction output index
     *
     * @return                  Transaction output index
     */
    int getIndex() {
        return index;
    }

    /**
     * Get the transaction amount
     *
     * @return                  Transaction amount (Satoshis)
     */
    long getAmount() {
        return amount;
    }

    /**
     * Get the block chain height
     *
     * @return                  Block chain height (0 if not in a block)
     */
    int getHeight() {
        return height;
    }

    /**
     * Set the block chain height
     *
     * @param   height          Block chain height
     */
    void setHeight(int height) {
        this.height = height;
    }

    /**
     * Get the receive address child number
     *
     * @return                  Child number
     */
    ChildNumber getChildNumber() {
        return childNumber;
    }

    /**
     * Get the receive address parent number
     *
     * @return                  Parent number
     */
    ChildNumber getParentNumber() {
        return parentNumber;
    }
}
