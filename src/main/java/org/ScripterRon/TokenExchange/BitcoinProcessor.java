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
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionOutput;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Interface between NRS and the Bitcoin server
 */
public class BitcoinProcessor implements Runnable {

    /** Processing thread */
    private static Thread processingThread;

    /** Bitcoin processing lock */
    private static final ReentrantLock processingLock = new ReentrantLock();

    /** Processing queue */
    private static final LinkedBlockingQueue<Boolean> processingQueue = new LinkedBlockingQueue<>();

    /** Last seen chain height */
    private static volatile int lastSeenHeight = 0;

    /**
     * Initialize the Bitcoin processor
     *
     * @param   delayed                     TRUE if this a delayed initialization
     * @throws  IllegalArgumentException    Processing error occurred
     */
    static void init(boolean delayed) throws IllegalArgumentException {
        //
        // Run our Bitcoin processing thread after NRS initialization is completed.
        // Note that the Nxt processing thread will wait until the Bitcoin wallet
        // has been initialized.
        //
        if (delayed) {
            processingThread = new Thread(new BitcoinProcessor(), "TokenExchange Bitcoin Processor");
            processingThread.setDaemon(true);
            processingThread.start();
        } else {
            ThreadPool.runAfterStart(() -> {
                processingThread = new Thread(new BitcoinProcessor(), "TokenExchange Bitcoin Processor");
                processingThread.setDaemon(true);
                processingThread.start();
            });
        }
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
     * Add a new Bitcoin transaction to the database or update an existing transaction
     *
     * This method is called each time a transaction is received that has at least one
     * output referencing one of our account addresses.  We will store the transaction
     * in the Bitcoin transaction table for later processing.
     *
     * @param   tx              Transaction
     * @param   block           Block containing the transaction
     * @param   height          Chain height or 0 if not in the chain
     */
    static void addTransaction(Transaction tx, StoredBlock block, int height) {
        Sha256Hash txHash = tx.getHash();
        Sha256Hash blockHash = block.getHeader().getHash();
        obtainLock();
        try {
            int timestamp = Nxt.getEpochTime();
            BitcoinTransaction btx = TokenDb.getTransaction(txHash.getBytes(), blockHash.getBytes());
            //
            // Update an existing transaction (this can happen if the block chain
            // is reorganized)
            //
            if (btx != null) {
                btx.setHeight(height);
                TokenDb.updateTransaction(btx);
                return;
            }
            //
            // Add a new transaction
            //
            List<TransactionOutput> outputs = tx.getOutputs();
            for (TransactionOutput output : outputs) {
                Address address = output.getAddressFromP2PKHScript(BitcoinWallet.getNetworkParameters());
                if (address == null) {
                    Logger.logErrorMessage("Bitcoin transaction " + txHash + " is not P2PKH, "
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
                btx = new BitcoinTransaction(txHash.getBytes(), blockHash.getBytes(), height,
                        timestamp, bitcoinAddress,
                        account.getAccountId(), bitcoinAmount.movePointRight(8).longValue(),
                        tokenAmount.movePointRight(TokenAddon.currencyDecimals).longValue());
                TokenDb.storeTransaction(btx);
            }
        } catch (ScriptException exc) {
            Logger.logErrorMessage("Script exception while processing Bitcoin transaction " + txHash
                    + ", transaction ignored", exc);
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to process Bitcoin transaction " + txHash
                    + ", transaction ignored", exc);
        } finally {
            releaseLock();
        }
    }

    /**
     * Process pending Bitcoin transactions
     *
     * This method is called each time a new best block (chain head)
     * is received.  It can be called multiple times for the same chain
     * height if the block chain is reorganized.
     *
     * @param   block           New best block
     */
    static void processTransactions(StoredBlock block) {
        try {
            int chainHeight = block.getHeight();
            if (chainHeight <= lastSeenHeight) {
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
        // Process Bitcoin transactions until stopped (FALSE is placed on the queue)
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
                    Account.AccountCurrency currency =
                            Account.getAccountCurrency(TokenAddon.accountId, TokenAddon.currencyId);
                    if (currency == null) {
                        TokenAddon.suspend("TokenExchange account " + Convert.rsAccount(TokenAddon.accountId) +
                                " does not have any " + TokenAddon.currencyCode + " currency");
                        continue;
                    }
                    long unitBalance = currency.getUnconfirmedUnits();
                    //
                    // Process pending Bitcoin transactions
                    //
                    List<BitcoinTransaction> txList =
                            TokenDb.getPendingTransactions(lastSeenHeight - TokenAddon.bitcoinConfirmations);
                    for (BitcoinTransaction tx : txList) {
                        String bitcoinTxId = tx.getBitcoinTxIdString();
                        long units = tx.getTokenAmount();
                        if (units > unitBalance) {
                            TokenAddon.suspend("Insufficient " + TokenAddon.currencyCode + " currency available "
                                    + "to process Bitcoin transaction " + bitcoinTxId);
                            break;
                        }
                        Attachment attachment =
                                new Attachment.MonetarySystemCurrencyTransfer(TokenAddon.currencyId, units);
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
                            TokenAddon.suspend("Unable to update transaction in TokenExchange database");
                            break;
                        }
                        nxtBalance -= transaction.getFeeNQT();
                        unitBalance -= units;
                        Logger.logInfoMessage("Issued "
                                + BigDecimal.valueOf(units, TokenAddon.currencyDecimals).toPlainString()
                                + " units of " + TokenAddon.currencyCode + " to "
                                + Convert.rsAccount(tx.getAccountId())
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
