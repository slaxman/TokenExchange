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

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Token transaction
 */
class TokenTransaction {

    /** Transaction identifier */
    private final long nxtTxId;

    /** Sender account id */
    private final long senderId;

    /** Bitcoin transaction id */
    private byte[] bitcoinTxId;

    /** Block height */
    private final int height;

    /** Transaction timestamp */
    private final int timestamp;

    /** Tokens have been exchanged */
    private boolean exchanged;

    /** Token amount */
    private final long tokenAmount;

    /** Bitcoin amount */
    private final long bitcoinAmount;

    /** Bitcoin address */
    private final String bitcoinAddress;

    /**
     * Create a token transaction
     *
     * @param   nxtTxId         Transaction identifier
     * @param   senderId        Sender identifier
     * @param   height          Block height
     * @param   timestamp       Transaction timestamp
     * @param   tokenAmount     Token amount
     * @param   bitcoinAmount   Bitcoin amount
     * @param   bitcoinAddress  Bitcoin address
     */
    TokenTransaction(long nxtTxId, long senderId, int height, int timestamp, long tokenAmount,
                                long bitcoinAmount, String bitcoinAddress) {
        this.nxtTxId = nxtTxId;
        this.senderId = senderId;
        this.height = height;
        this.timestamp = timestamp;
        this.tokenAmount = tokenAmount;
        this.bitcoinAmount = bitcoinAmount;
        this.bitcoinAddress = bitcoinAddress;
        this.exchanged = false;
        this.bitcoinTxId = null;
    }

    /**
     * Create a token transaction
     *
     * @param   rs              Result set
     * @throws  SQLException    SQL error
     */
    TokenTransaction(ResultSet rs) throws SQLException {
        this.nxtTxId = rs.getLong("nxt_txid");
        this.senderId = rs.getLong("sender");
        this.height = rs.getInt("height");
        this.timestamp = rs.getInt("timestamp");
        this.tokenAmount = rs.getLong("token_amount");
        this.bitcoinAmount = rs.getLong("bitcoin_amount");
        this.bitcoinAddress = rs.getString("bitcoin_address");
        if (rs.getBoolean("exchanged")) {
            this.exchanged = true;
            this.bitcoinTxId = rs.getBytes("bitcoin_txid");
        } else {
            this.exchanged = false;
            this.bitcoinTxId = null;
        }
    }

    /**
     * Return the Nxt transaction identifier
     *
     * @return                  Transaction identifier
     */
    long getNxtTxId() {
        return nxtTxId;
    }

    /**
     * Return the sender identifier
     *
     * @return                  Sender identifier
     */
    long getSenderId() {
        return senderId;
    }

    /**
     * Return the sender identifier as a string
     *
     * @return                  RS-encoded sender identifier
     */
    String getSenderIdRS() {
        return Convert.rsAccount(senderId);
    }

    /**
     * Return the block height
     *
     * @return                  Block height
     */
    int getHeight() {
        return height;
    }

    /**
     * Return the transaction timestamp
     *
     * @return                  Transaction timestamp
     */
    int getTimestamp() {
        return timestamp;
    }

    /**
     * Return the token amount
     *
     * @return                  Token amount
     */
    long getTokenAmount() {
        return tokenAmount;
    }

    /**
     * Return the Bitcoin amount
     *
     * @return                  Bitcoin amount
     */
    long getBitcoinAmount() {
        return bitcoinAmount;
    }

    /**
     * Return the Bitcoin address
     *
     * @return                  Bitcoin address
     */
    String getBitcoinAddress() {
        return bitcoinAddress;
    }

    /**
     * Check if the token has been changed
     *
     * @return                  TRUE if the token has been exchanged
     */
    boolean isExchanged() {
        return exchanged;
    }

    /**
     * Set Bitcoin exchanged
     *
     * @param   bitcoinTxId     Bitcoin transaction identifier
     */
    void setExchanged(byte[] bitcoinTxId) {
        this.bitcoinTxId = bitcoinTxId;
        this.exchanged = true;
    }

    /**
     * Return the Bitcoin transaction identifier
     *
     * @return                  Bitcoin transaction identifier or null
     */
    byte[] getBitcoinTxId() {
        return bitcoinTxId;
    }

    /**
     * Return the Bitcoin transaction identifier as a string
     *
     * @return                  Transaction identifier string
     */
    String getBitcoinTxIdString() {
        return bitcoinTxId != null ? Convert.toHexString(bitcoinTxId) : "";
    }
}
