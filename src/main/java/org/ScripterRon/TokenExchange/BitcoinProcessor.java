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
import nxt.Attachment;
import nxt.Nxt;
import nxt.util.Convert;
import nxt.util.Logger;
import nxt.util.ThreadPool;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionOutput;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Interface between NRS and the Bitcoin server
 */
public class BitcoinProcessor implements Runnable {

    /** Processing thread */
    private static Thread processingThread;

    /** Processing lock */
    private static final ReentrantLock processingLock = new ReentrantLock();

    /** Processing queue */
    private static final LinkedBlockingQueue<Boolean> processingQueue = new LinkedBlockingQueue<>();

    /** Last seen chain height */
    private static volatile int lastSeenHeight = 0;

    /**
     * Initialize the Bitcoin processor
     *
     * @throws  IllegalArgumentException    Processing error occurred
     */
    static void init() throws IllegalArgumentException {
        //
        // Run our Bitcoin processing thread after NRS initialization is completed.
        // Note that the Nxt processing thread will wait until the Bitcoin wallet
        // has been initialized.
        //
        ThreadPool.runAfterStart(() -> {
            processingThread = new Thread(new BitcoinProcessor(), "TokenExchange Bitcoin Processor");
            processingThread.setDaemon(true);
            processingThread.start();
        });
    }

    /**
     * Shutdown the Bitcoin processor
     */
    static void shutdown() {
        if (processingThread != null) {
            try {
                processingQueue.put(false);
                processingThread.join(5000);
            } catch (InterruptedException exc) {
                // Ignored since the queue is unbounded
            }
        }
    }

    /**
     * Obtain the Bitcoin processor lock
     */
    static void obtainLock() {
        processingLock.lock();
    }

    /**
     * Release the Bitcoin processor lock
     */
    static void releaseLock() {
        processingLock.unlock();
    }

    /**
     * Add a new Bitcoin transaction to the database
     *
     * @param   tx              Transaction
     */
    static void addTransaction(Transaction tx) {
        Sha256Hash hash = tx.getHash();
        obtainLock();
        try {
            int height;
            int timestamp = Nxt.getEpochTime();
            if (TokenDb.transactionExists(hash.getBytes())) {
                Logger.logDebugMessage("Bitcoin transaction " + hash + " already in database");
                return;
            }
            TransactionConfidence confidence = tx.getConfidence();
            if (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING) {
                height = confidence.getAppearedAtChainHeight();
                Date txDate = tx.getUpdateTime();
                if (txDate != null) {
                    timestamp = Convert.toEpochTime(txDate.getTime());
                }
            } else if (confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING) {
                height = 0;
            } else {
                Logger.logErrorMessage("Bitcoin transaction " + hash + " is not PENDING or BUILDING, "
                        + "transaction ignored");
                return;
            }
            List<TransactionOutput> outputs = tx.getOutputs();
            for (TransactionOutput output : outputs) {
                Address address = output.getAddressFromP2PKHScript(BitcoinWallet.getNetworkParameters());
                if (address == null) {
                    Logger.logErrorMessage("Bitcoin transaction " + hash + " is not P2PKH, "
                            + "transaction ignored");
                    return;
                }
                String bitcoinAddress = address.toBase58();
                BitcoinAccount account = TokenDb.getAccount(bitcoinAddress);
                if (account == null) {
                    continue;
                }
                BigDecimal bitcoinAmount = BigDecimal.valueOf(output.getValue().getValue(), 8);
                BigDecimal tokenAmount = bitcoinAmount.divide(TokenAddon.exchangeRate);
                BitcoinTransaction btx = new BitcoinTransaction(hash.getBytes(), height, timestamp,
                        bitcoinAddress, account.getAccountId(),
                        bitcoinAmount.movePointRight(8).longValue(),
                        tokenAmount.movePointRight(TokenAddon.currencyDecimals).longValue());
                if (!TokenDb.storeTransaction(btx)) {
                    TokenAddon.suspend("Unable to store Bitcoin transaction in database");
                    break;
                }
                Logger.logDebugMessage("Received Bitcoin transaction " + hash +
                        " to " + bitcoinAddress + " for " +
                        bitcoinAmount.stripTrailingZeros().toPlainString() + " BTC");
            }
        } catch (ScriptException exc) {
            Logger.logErrorMessage("Script exception while processing Bitcoin transaction " + hash
                    + ", transaction ignored", exc);
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to process Bitcoin transaction " + hash
                    + ", transaction ignored", exc);
        } finally {
            releaseLock();
        }
    }

    /**
     * Process pending Bitcoin transactions
     */
    static void processTransactions() {
        try {
            int chainHeight = BitcoinWallet.getChainHeight();
            if (chainHeight == lastSeenHeight) {
                return;
            }
            lastSeenHeight = chainHeight;
            processingQueue.put(true);
        } catch (InterruptedException exc) {
            // Ignored since the queue is unbounded
        }
    }

    /**
     * Process Bitcoin transactions
     */
    @Override
    public void run() {
        Logger.logInfoMessage("TokenExchange Bitcoin processor started");
        //
        // Initialize the Bitcoin wallet
        //
        if (!BitcoinWallet.init()) {
            TokenAddon.suspend("Unable to initialize the Bitcoin wallet");
            return;
        }
        //
        // Process Bitcoin transactions until stopped
        //
        try {
            while (processingQueue.take()) {
                if (TokenAddon.isSuspended()) {
                    continue;
                }
                obtainLock();
                try {
                    Account account = Account.getAccount(TokenAddon.accountId);
                    long nxtBalance = account.getUnconfirmedBalanceNQT();
                    Account.AccountCurrency currency = Account.getAccountCurrency(TokenAddon.accountId, TokenAddon.currencyId);
                    if (currency == null) {
                        TokenAddon.suspend("TokenExchange account " + Convert.rsAccount(TokenAddon.accountId) +
                                " does not have any " + TokenAddon.currencyCode + " currency");
                        return;
                    }
                    long unitBalance = currency.getUnconfirmedUnits();
                    List<BitcoinTransaction> txList = TokenDb.getPendingTransactions(lastSeenHeight - TokenAddon.bitcoinConfirmations);
                    //
                    // Processing pending Bitcoin transactions
                    //
                    for (BitcoinTransaction tx : txList) {
                        String bitcoinTxId = Convert.toHexString(tx.getBitcoinTxId());
                        int txHeight = tx.getHeight();
                        if (txHeight == 0) {
                            Transaction btx = BitcoinWallet.getTransaction(bitcoinTxId);
                            if (btx == null) {
                                TokenAddon.suspend("Bitcoin transaction " + bitcoinTxId
                                        + " is no longer in the wallet");
                                break;
                            }
                            TransactionConfidence confidence = btx.getConfidence();
                            if (confidence.getConfidenceType() != TransactionConfidence.ConfidenceType.BUILDING) {
                                continue;
                            }
                            txHeight = confidence.getAppearedAtChainHeight();
                            tx.setHeight(txHeight);
                            Date txDate = btx.getUpdateTime();
                            if (txDate != null) {
                                int txTimestamp = Convert.toEpochTime(txDate.getTime());
                                if (txTimestamp < tx.getTimestamp()) {
                                    tx.setTimestamp(txTimestamp);
                                }
                            }
                            if (!TokenDb.updateTransaction(tx)) {
                                throw new RuntimeException("Unable to update transaction in TokenExchange database");
                            }
                        }
                        long units = tx.getTokenAmount();
                        if (units > unitBalance) {
                            TokenAddon.suspend("Insufficient " + TokenAddon.currencyCode + " currency available "
                                    + "to process Bitcoin transaction " + bitcoinTxId);
                            break;
                        }
                        Attachment attachment = new Attachment.MonetarySystemCurrencyTransfer(TokenAddon.currencyId, units);
                        nxt.Transaction.Builder builder = Nxt.newTransactionBuilder(TokenAddon.publicKey,
                                0, 0, (short)1440, attachment);
                        builder.recipientId(tx.getAccountId()).timestamp(Nxt.getEpochTime());
                        nxt.Transaction transaction = builder.build(TokenAddon.secretPhrase);
                        if (transaction.getFeeNQT() > nxtBalance) {
                            TokenAddon.suspend("Insufficient NXT available to process Bitcoin transaction " +
                                    bitcoinTxId);
                            break;
                        }
                        Nxt.getTransactionProcessor().broadcast(transaction);
                        tx.setExchanged(transaction.getId());
                        if (!TokenDb.updateTransaction(tx)) {
                            throw new RuntimeException("Unable to update transaction in TokenExchange database");
                        }
                        nxtBalance -= transaction.getFeeNQT();
                        unitBalance -= units;
                        Logger.logInfoMessage("Issued " + BigDecimal.valueOf(units, TokenAddon.currencyDecimals).toPlainString()
                                + " units of " + TokenAddon.currencyCode + " to " + Convert.rsAccount(tx.getAccountId())
                                + ", Transaction " + Long.toUnsignedString(transaction.getId()));
                    }
                } catch (Exception exc) {
                    Logger.logErrorMessage("Unable to process Bitcoin transactions", exc);
                    TokenAddon.suspend("Unable to process Bitcoin transactions");
                } finally {
                    releaseLock();
                }
            }
            Logger.logInfoMessage("TokenExchange Bitcoin processor stopped");
        } catch (Throwable exc) {
            Logger.logErrorMessage("TokenExchange Bitcoin processor encountered fatal exception", exc);
            TokenAddon.suspend("TokenExchange Bitcoin processor encountered fatal error");
        }
    }
}
