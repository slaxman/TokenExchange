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

/**
 * Token transaction
 */
class TokenTransaction {

    /** Transaction identifier */
    private final long id;

    /** Bitcoin transaction id */
    private String bitcoinTxId;

    /** Block height */
    private final int height;

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
     * @param   id              Transaction identifier
     * @param   height          Block height
     * @param   tokenAmount     Token amount
     * @param   bitcoinAmount   Bitcoin amount
     * @param   bitcoinAddress  Bitcoin address
     */
    TokenTransaction(long id, int height, long tokenAmount, long bitcoinAmount, String bitcoinAddress) {
        this.id = id;
        this.height = height;
        this.tokenAmount = tokenAmount;
        this.bitcoinAmount = bitcoinAmount;
        this.bitcoinAddress = bitcoinAddress;
        this.exchanged = false;
    }

    /**
     * Return the transaction identifier
     *
     * @return                  Transaction identifier
     */
    long getId() {
        return id;
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
     * Return the token amount
     *
     * @return                  Token amount
     */
    long getTokenAmount() {
        return tokenAmount;
    }

    /**
     * Return the bitcoin amount
     *
     * @return                  Bitcoin amount
     */
    long getBitcoinAmount() {
        return bitcoinAmount;
    }

    /**
     * Return the bitcoin address
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
     * Set bitcoin exchanged
     *
     * @param   bitcoinTxId     Bitcoin transaction identifier
     */
    void setExchanged(String bitcoinTxId) {
        this.bitcoinTxId = bitcoinTxId;
        exchanged = true;
    }

    /**
     * Return the Bitcoin transaction identifier
     *
     * @return                  Bitcoin transaction identifier
     */
    String getBitcoinTxId() {
        return bitcoinTxId;
    }
}
