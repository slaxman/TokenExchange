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

import nxt.Appendix;
import nxt.Attachment;
import nxt.Block;
import nxt.Blockchain;
import nxt.BlockchainProcessor;
import nxt.Nxt;
import nxt.Transaction;
import nxt.util.Convert;
import nxt.util.Logger;

import java.math.BigDecimal;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;

/**
 * Listen for new blocks and process any token redemption transactions
 */
public class TokenListener implements Runnable {

    /** Pending blocks queue */
    private static final LinkedBlockingQueue<Long> blockQueue = new LinkedBlockingQueue<>();

    /** Listener thread */
    private static Thread listenerThread;

    /** Blockchain */
    private static final Blockchain blockchain = Nxt.getBlockchain();

    /** Blockchain processor */
    private static final BlockchainProcessor blockchainProcessor = Nxt.getBlockchainProcessor();

    /** Bitcoin send suspended */
    private static volatile boolean sendSuspended = false;

    /**
     * Initialize the token listener
     */
    static void init() {
        //
        // Add our block listeners
        //
        blockchainProcessor.addListener((block) -> {
            try {
                blockQueue.put(block.getId());
            } catch (InterruptedException exc) {
                // Ignored since the queue is unbounded
            }
        }, BlockchainProcessor.Event.BLOCK_PUSHED);
        blockchainProcessor.addListener((block) -> {
            try {
                blockQueue.put(block.getId());
            } catch (InterruptedException exc) {
                // Ignored since the queue is unbounded
            }
        }, BlockchainProcessor.Event.BLOCK_SCANNED);
        //
        // Start listener thread
        //
        listenerThread = new Thread(new TokenListener());
        listenerThread.start();
    }

    /**
     * Shutdown the token listener
     */
    static void shutdown() {
        if (listenerThread != null) {
            try {
                blockQueue.put(0L);
            } catch (InterruptedException exc) {
                // Ignored since the queue is unbounded
            }
        }
    }

    /**
     * Check if bitcoin send is suspended
     *
     * @return                  TRUE if send is suspended
     */
    static boolean isSuspended() {
        return sendSuspended;
    }

    /**
     * Suspend bitcoin send
     */
    static void suspendSend() {
        sendSuspended = true;
        Logger.logInfoMessage("Bitcoin send suspended");
    }

    /**
     * Resume bitcoin send
     */
    static void resumeSend() {
        sendSuspended = false;
        Logger.logInfoMessage("Bitcoin send resumed");
    }

    /**
     * Process new blocks
     */
    @Override
    public void run() {
        Logger.logInfoMessage("TokenExchange block listener started");
        try {
            //
            // Loop until 0 is pushed on to the stack
            //
            while (true) {
                long blockId = blockQueue.take();
                if (blockId == 0) {
                    break;
                }
                //
                // Process the transactions in the block
                //
                blockchain.readLock();
                try {
                    Block block = blockchain.getBlock(blockId);
                    if (block == null) {
                        continue;
                    }
                    List<? extends Transaction> txList = block.getTransactions();
                    for (Transaction tx : txList) {
                        Attachment.MonetarySystemCurrencyTransfer transfer = null;
                        Appendix.PrunablePlainMessage msg = null;
                        for (Appendix appendix : tx.getAppendages()) {
                            if (appendix instanceof Attachment.MonetarySystemCurrencyTransfer) {
                                transfer = (Attachment.MonetarySystemCurrencyTransfer)appendix;
                            } else if (appendix instanceof Appendix.PrunablePlainMessage) {
                                msg = (Appendix.PrunablePlainMessage)appendix;
                            }
                        }
                        if (transfer == null || transfer.getCurrencyId() != TokenAddon.currencyId ||
                                tx.getRecipientId() != TokenAddon.redemptionAccount) {
                            continue;
                        }
                        if (TokenDb.tokenExists(tx.getId())) {
                            continue;
                        }
                        Logger.logDebugMessage("Found token redemption transaction "
                                + Long.toUnsignedString(tx.getId()) + " from " + Convert.rsAccount(tx.getSenderId()));
                        if (msg == null || !msg.isText()) {
                            Logger.logErrorMessage("Token redemption transaction does not have a plain text message");
                            continue;
                        }
                        String bitcoinAddress = Convert.toString(msg.getMessage());
                        long units = transfer.getUnits();
                        BigDecimal tokenAmount = BigDecimal.valueOf(units, TokenAddon.currencyDecimals);
                        BigDecimal bitcoinAmount = tokenAmount.multiply(TokenAddon.exchangeRate);
                        TokenTransaction token = new TokenTransaction(tx.getId(), tx.getSenderId(),
                                block.getHeight(), units, bitcoinAmount.movePointRight(8).longValue(), bitcoinAddress);
                        TokenDb.storeToken(token);
                        Logger.logDebugMessage("Redeeming " + tokenAmount.toPlainString() + " units for "
                                    + bitcoinAmount.toPlainString() + " BTC to " + bitcoinAddress);
                    }
                } finally {
                    blockchain.readUnlock();
                }
                //
                // Process pending redemptions that are now confirmed.  We will stop sending bitcoins
                // if we are unable to communicate with the bitcoind server.  We also won't send
                // bitcoins while scanning the block chain.
                //
                if (!sendSuspended && !blockchainProcessor.isScanning()) {
                    blockchain.readLock();
                    try {
                        List<TokenTransaction> tokenList = TokenDb.getPendingTokens(blockchain.getHeight()-TokenAddon.confirmations);
                        for (TokenTransaction token : tokenList) {
                            if (!BitcoinProcessor.sendBitcoins(token)) {
                                Logger.logErrorMessage("Unable to send bitcoins; send suspended");
                                sendSuspended = true;
                                break;
                            }
                        }
                    } finally {
                        blockchain.readUnlock();
                    }
                }
            }
            Logger.logInfoMessage("TokenExchange block listened stopped");
        } catch (Throwable exc) {
            Logger.logErrorMessage("TokenExchange listener encountered fatal exception", exc);
        }
    }
}
