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

import nxt.Account;
import nxt.Appendix;
import nxt.Attachment;
import nxt.Block;
import nxt.Blockchain;
import nxt.BlockchainProcessor;
import nxt.Nxt;
import nxt.Transaction;
import nxt.crypto.EncryptedData;
import nxt.util.Convert;
import nxt.util.Logger;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;

/**
 * Listen for new blocks and process any token redemption transactions
 */
public class TokenListener implements Runnable {

    /** Pending blocks queue */
    private static final LinkedBlockingQueue<Long> blockQueue = new LinkedBlockingQueue<>();

    /** Bitcoin wallet initialization latch */
    private static final CountDownLatch walletLatch = new CountDownLatch(1);

    /** Listener thread */
    private static Thread listenerThread;

    /** Block chain */
    private static final Blockchain blockchain = Nxt.getBlockchain();

    /** Block chain processor */
    private static final BlockchainProcessor blockchainProcessor = Nxt.getBlockchainProcessor();

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
        listenerThread = new Thread(new TokenListener(), "TokenExchange Nxt Processor");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /**
     * Shutdown the token listener
     */
    static void shutdown() {
        if (listenerThread != null) {
            try {
                walletLatch.countDown();
                blockQueue.put(0L);
                listenerThread.join(5000);
            } catch (InterruptedException exc) {
                // Ignored since the queue is unbounded
            }
        }
    }

    /**
     * Release the wallet latch after the Bitcoin wallet has been initialized
     */
    static void walletInitialized() {
        walletLatch.countDown();
    }

    /**
     * Process new blocks
     */
    @Override
    public void run() {
        Logger.logInfoMessage("TokenExchange Nxt processor started");
        try {
            //
            // Wait until the Bitcoin wallet is initialized
            //
            try {
                walletLatch.await();
            } catch (InterruptedException exc) {
                Logger.logErrorMessage("TokenExchange Nxt processor interrupted, processing terminated");
                return;
            }
            BitcoinWallet.propagateContext();
            //
            // Process Nxt transactions until stopped
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
                    //
                    // Process pending Nxt transactions
                    //
                    for (Transaction tx : txList) {
                        long txId = tx.getId();
                        String txIdString = Long.toUnsignedString(txId);
                        Attachment.MonetarySystemCurrencyTransfer transfer = null;
                        Appendix.PrunablePlainMessage plainMsg = null;
                        Appendix.PrunableEncryptedMessage encryptedMsg = null;
                        for (Appendix appendix : tx.getAppendages()) {
                            if (appendix instanceof Attachment.MonetarySystemCurrencyTransfer) {
                                transfer = (Attachment.MonetarySystemCurrencyTransfer)appendix;
                            } else if (appendix instanceof Appendix.PrunablePlainMessage) {
                                plainMsg = (Appendix.PrunablePlainMessage)appendix;
                            } else if (appendix instanceof Appendix.PrunableEncryptedMessage) {
                                encryptedMsg = (Appendix.PrunableEncryptedMessage)appendix;
                            }
                        }
                        if (transfer == null || transfer.getCurrencyId() != TokenAddon.currencyId ||
                                tx.getRecipientId() != TokenAddon.accountId) {
                            continue;
                        }
                        if (TokenDb.tokenExists(tx.getId())) {
                            Logger.logDebugMessage("Token transaction " + txIdString + " is already in the database");
                            continue;
                        }
                        byte[] msg;
                        if (plainMsg != null) {
                            if (!plainMsg.isText()) {
                                Logger.logErrorMessage("Token redemption transaction " + txIdString +
                                        " does not have a text message, transaction ignored");
                                continue;
                            }
                            msg = plainMsg.getMessage();
                            if (msg == null) {
                                Logger.logErrorMessage("Token redemption transaction " + txIdString +
                                        " attached message is not available, transaction ignored");
                                continue;
                            }
                        } else if (encryptedMsg != null) {
                            if (!encryptedMsg.isText()) {
                                Logger.logErrorMessage("Token redemption transaction " + txIdString +
                                        " does not have a text message, transaction ignored");
                                continue;
                            }
                            byte[] senderPublicKey = Account.getPublicKey(tx.getSenderId());
                            if (senderPublicKey == null) {
                                Logger.logErrorMessage("Token redemption transaction " + txIdString +
                                        " sender " + Convert.rsAccount(tx.getSenderId())
                                        + " does not have a public key,, transaction ignored");
                                continue;
                            }
                            EncryptedData encryptedData = encryptedMsg.getEncryptedData();
                            if (encryptedData == null) {
                                Logger.logErrorMessage("Token redemption transaction " + txIdString +
                                        " attached message is not available, transaction ignored");
                                continue;
                            }
                            msg = Account.decryptFrom(senderPublicKey, encryptedData, TokenAddon.secretPhrase,
                                    encryptedMsg.isCompressed());
                        } else {
                            Logger.logErrorMessage("Token redemption transaction " + txIdString +
                                    " does not have an attached message, transaction ignored");
                            continue;
                        }
                        String bitcoinAddress = Convert.toString(msg);
                        if (!BitcoinWallet.validateAddress(bitcoinAddress)) {
                            Logger.logErrorMessage("Token redemption transaction " + txIdString +
                                    " does not have a valid bitcoin address, transaction ignored");
                            continue;
                        }
                        long units = transfer.getUnits();
                        BigDecimal tokenAmount = BigDecimal.valueOf(units, TokenAddon.currencyDecimals);
                        BigDecimal bitcoinAmount = tokenAmount.multiply(TokenAddon.exchangeRate);
                        TokenTransaction token = new TokenTransaction(tx.getId(), tx.getSenderId(),
                                block.getHeight(), tx.getTimestamp(), units,
                                bitcoinAmount.movePointRight(8).longValue(), bitcoinAddress);
                        if (!TokenDb.storeToken(token)) {
                            throw new RuntimeException("Unable to store token transaction in TokenExchange database");
                        }
                        Logger.logDebugMessage("Redeeming " + tokenAmount.toPlainString() + " units for "
                                    + bitcoinAmount.toPlainString() + " BTC to " + bitcoinAddress);
                    }
                } finally {
                    blockchain.readUnlock();
                }
                //
                // Process pending redemptions that are now confirmed.  We will stop sending bitcoins
                // if we are unable to communicate with the bitcoin server.  We also won't send
                // bitcoins while scanning the block chain.
                //
                if (!TokenAddon.isSuspended() && !blockchainProcessor.isScanning()) {
                    blockchain.readLock();
                    try {
                        List<TokenTransaction> tokenList = TokenDb.getPendingTokens(blockchain.getHeight() - TokenAddon.nxtConfirmations);
                        for (TokenTransaction token : tokenList) {
                            String address = token.getBitcoinAddress();
                            BigDecimal amount = BigDecimal.valueOf(token.getBitcoinAmount(), 8);
                            String txString = BitcoinWallet.sendCoins(address, amount);
                            token.setExchanged(Convert.parseHexString(txString));
                            TokenDb.updateToken(token);
                            Logger.logInfoMessage("Sent " + amount.toPlainString() + " BTC to " + address);
                        }
                    } catch (Exception exc) {
                        Logger.logErrorMessage("Unable to send Bitcoins", exc);
                        TokenAddon.suspend("Unable to send Bitcoins");
                    } finally {
                        blockchain.readUnlock();
                    }
                }
            }
            Logger.logInfoMessage("TokenExchange Nxt processor stopped");
        } catch (Throwable exc) {
            Logger.logErrorMessage("TokenExchange Nxt processor encountered fatal exception", exc);
            TokenAddon.suspend("TokenExchange Nxt processor encountered fatal exception");
        }
    }
}
