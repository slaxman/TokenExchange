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
import nxt.Transaction;
import nxt.util.Convert;
import nxt.util.Logger;

import java.math.BigDecimal;
import java.util.List;

/**
 * Issue Nxt currency after receiving Bitcoins
 */
public class TokenCurrency {

    /**
     * Process Bitcoin transactions after a new Bitcoin block has been received
     */
    static void processTransactions() {
        BitcoinProcessor.obtainLock();
        try {
            Account account = Account.getAccount(TokenAddon.accountId);
            long nxtBalance = account.getUnconfirmedBalanceNQT();
            Account.AccountCurrency currency = Account.getAccountCurrency(TokenAddon.accountId, TokenAddon.currencyId);
            long unitBalance = currency.getUnconfirmedUnits();
            List<BitcoinTransaction> txList = TokenDb.getTransactions(null, false);
            int chainHeight = BitcoinProcessor.getChainHeight();
            for (BitcoinTransaction tx : txList) {
                String bitcoinTxId = Convert.toHexString(tx.getBitcoinTxId());
                if (chainHeight - tx.getHeight() < TokenAddon.confirmations) {
                    continue;
                }
                long units = tx.getTokenAmount();
                if (units > unitBalance) {
                    Logger.logErrorMessage("Insufficient currency units available to process Bitcoin transaction " +
                            bitcoinTxId + ", processing suspended");
                    TokenAddon.suspend();
                    break;
                }
                Attachment attachment = new Attachment.MonetarySystemCurrencyTransfer(TokenAddon.currencyId, units);
                Transaction.Builder builder = Nxt.newTransactionBuilder(TokenAddon.publicKey,
                        0, 0, (short)1440, attachment);
                builder.recipientId(tx.getAccountId()).timestamp(Nxt.getEpochTime());
                Transaction transaction = builder.build(TokenAddon.secretPhrase);
                if (transaction.getFeeNQT() > nxtBalance) {
                    Logger.logErrorMessage("Insufficient NXT available to process Bitcoin transaction " +
                            bitcoinTxId + ", processing suspended");
                    TokenAddon.suspend();
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
            Logger.logErrorMessage("Unable to processing Bitcoin transactions, processing suspended", exc);
            TokenAddon.suspend();
        } finally {
            BitcoinProcessor.releaseLock();
        }
    }
}
