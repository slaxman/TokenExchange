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
import nxt.Nxt;
import nxt.http.APIServlet;
import nxt.http.APITag;
import nxt.http.ParameterException;
import nxt.util.Convert;
import nxt.util.JSON;
import nxt.util.Logger;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.crypto.DeterministicKey;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

/**
 * <p>TokenExchange API
 *
 * <p>The following functions are provided:
 * <ul>
 * <li>deleteToken - Delete a token from the database.  The 'id' parameter specifies the
 * token to be deleted.
 *
 * <li>emptyWallet - Empty the Bitcoin wallet and send all of the coins to the target
 * address specified by the 'address' parameter.
 *
 * <li>getAccounts - Returns a list of Bitcoin addresses associated with Nxt accounts.
 * The 'account' parameter returns the address associated with that account.  The
 * 'address' parameter returns the accounts associated with that address.  All accounts
 * are returned if neither parameter is specified.
 *
 * <li>getAddress - Get a new Bitcoin address and associate it with a Nxt account.
 * The 'account' parameter identifies the Nxt account.  The 'publicKey' parameter
 * can be specified to further identify the Nxt account and should be specified for
 * a new Nxt account.
 *
 * <li>getStatus - Returns the current status of the TokenExchange add-on.
 *
 * <li>getTokens - Returns a list of NXT currency transactions.  The 'height' parameter
 * can be used to specify the starting height, otherwise a height of 0 is used.
 * Transaction tokens at a height greater than the specified height
 * will be returned.  The 'includeExchanged' parameter can be used to return processed
 * tokens in addition to pending tokens.
 *
 * <li>getTransactions - Return a list of transactions received by the Bitcoin wallet for
 * addresses associated with NXT accounts.  Specify the 'address' parameter to limit the
 * list to transactions for that address.  Otherwise, all transactions are returned.  Specify
 * the 'includeExchanged' parameter to return transactions that have been processed as well
 * as pending transactions.
 *
 * <li>resume - Resume sending Bitcoins for redeemed tokens and issuing tokens for received
 * Bitcoins.
 *
 * <li>SendBitcoins - Send Bitcoins to the specified address.
 *
 * <li>setExchangeRate - Set the token exchange rate.
 *
 * <li>suspend - Stop sending Bitcoins for redeemed tokens and issuing tokens for received
 * Bitcoins.
 * </ul>
 */
public class TokenAPI extends APIServlet.APIRequestHandler {

    /**
     * Create the API request handler
     */
    public TokenAPI() {
        super(new APITag[] {APITag.ADDONS},
                "function", "id", "includeExchanged", "height", "account", "publicKey", "address", "rate", "amount");
    }

    /**
     * Process the API request
     *
     * @param   req                 HTTP request
     * @return                      HTTP response
     * @throws  ParameterException  Parameter error detected
     */
    @SuppressWarnings("unchecked")
    @Override
    protected JSONStreamAware processRequest(HttpServletRequest req) throws ParameterException {
        JSONObject response = new JSONObject();
        String function = Convert.emptyToNull(req.getParameter("function"));
        if (function == null) {
            return missing("function");
        }
        if (!BitcoinWallet.isWalletInitialized()) {
            return failure("Bitcoin wallet is not initialized yet");
        }
        BitcoinWallet.propagateContext();
        String heightString;
        String idString;
        String includeExchangedString;
        String accountString;
        String publicKeyString;
        String addressString;
        String amountString;
        String rateString;
        String txString;
        boolean includeExchanged;
        long accountId;
        int height;
        List<BitcoinAccount> accountList;
        BitcoinAccount account;
        switch (function) {
            case "getStatus":
                response.put("applicationName", TokenAddon.applicationName);
                response.put("applicationVersion", TokenAddon.applicationVersion);
                response.put("exchangeRate", TokenAddon.exchangeRate.toPlainString());
                response.put("currencyCode", TokenAddon.currencyCode);
                response.put("currencyId", Long.toUnsignedString(TokenAddon.currencyId));
                response.put("tokenAccount", Long.toUnsignedString(TokenAddon.accountId));
                response.put("tokenAccountRS", Convert.rsAccount(TokenAddon.accountId));
                List<Peer> peers = BitcoinWallet.getConnectedPeers();
                JSONArray connectedPeers = new JSONArray();
                peers.forEach((peer) -> {
                    JSONObject JSONpeer = new JSONObject();
                    PeerAddress peerAddress = peer.getAddress();
                    VersionMessage versionMessage = peer.getPeerVersionMessage();
                    JSONpeer.put("address", TokenAddon.formatAddress(peerAddress.getAddr(), peerAddress.getPort()));
                    JSONpeer.put("version", versionMessage.clientVersion);
                    JSONpeer.put("subVersion", versionMessage.subVer);
                    connectedPeers.add(JSONpeer);
                });
                response.put("connectedPeers", connectedPeers);
                String downloadPeer = BitcoinWallet.getDownloadPeer();
                if (downloadPeer != null) {
                    response.put("downloadPeer", downloadPeer);
                }
                response.put("nxtConfirmations", TokenAddon.nxtConfirmations);
                response.put("bitcoinConfirmations", TokenAddon.bitcoinConfirmations);
                response.put("walletAddress", BitcoinWallet.getWalletAddress());
                response.put("walletBalance", BitcoinWallet.getBalance().toPlainString());
                response.put("bitcoinTxFee", TokenAddon.bitcoinTxFee.toPlainString());
                response.put("bitcoinChainHeight", BitcoinWallet.getChainHeight());
                response.put("nxtChainHeight", Nxt.getBlockchain().getHeight());
                response.put("suspended", TokenAddon.isSuspended());
                if (TokenAddon.isSuspended()) {
                    response.put("suspendReason", TokenAddon.getSuspendReason());
                }
                break;
            case "setExchangeRate":
                rateString = Convert.emptyToNull(req.getParameter("rate"));
                if (rateString == null) {
                    return missing("rate");
                }
                try {
                    BigDecimal rate = new BigDecimal(rateString)
                        .movePointRight(8)
                        .divideToIntegralValue(BigDecimal.ONE)
                        .movePointLeft(8)
                        .stripTrailingZeros();
                    TokenDb.setExchangeRate(rate);
                    response.put("processed", true);
                } catch (NumberFormatException exc) {
                    return incorrect("rate", exc.getMessage());
                } catch (Exception exc) {
                    Logger.logErrorMessage("SetExchangeRate failed", exc);
                    return failure("Unable to set exchange rate: " + exc.getMessage());
                }
                break;
            case "getNxtTransactions":
                heightString = Convert.emptyToNull(req.getParameter("height"));
                if (heightString == null) {
                    height = 0;
                } else {
                    try {
                        height = Integer.valueOf(heightString);
                    } catch (NumberFormatException exc) {
                        return incorrect("height", exc.getMessage());
                    }
                }
                includeExchangedString = Convert.emptyToNull(req.getParameter("includeExchanged"));
                if (includeExchangedString == null) {
                    includeExchanged = false;
                } else {
                    includeExchanged = Boolean.valueOf(includeExchangedString);
                }
                try {
                    List<TokenTransaction> tokenList = TokenDb.getTokens(height, includeExchanged);
                    JSONArray tokenArray = new JSONArray();
                    tokenList.forEach((token) -> {
                        JSONObject tokenObject = new JSONObject();
                        tokenObject.put("nxtTxId", Long.toUnsignedString(token.getNxtTxId()));
                        tokenObject.put("sender", Long.toUnsignedString(token.getSenderId()));
                        tokenObject.put("senderRS", token.getSenderIdRS());
                        tokenObject.put("nxtChainHeight", token.getHeight());
                        tokenObject.put("timestamp", token.getTimestamp());
                        tokenObject.put("exchanged", token.isExchanged());
                        tokenObject.put("tokenAmount",
                                BigDecimal.valueOf(token.getTokenAmount(), TokenAddon.currencyDecimals).toPlainString());
                        tokenObject.put("bitcoinAmount",
                                BigDecimal.valueOf(token.getBitcoinAmount(), 8).toPlainString());
                        tokenObject.put("address", token.getBitcoinAddress());
                        if (token.getBitcoinTxId() != null) {
                            tokenObject.put("bitcoinTxId", token.getBitcoinTxIdString());
                        }
                        tokenArray.add(tokenObject);
                    });
                    response.put("transactions", tokenArray);
                } catch (Exception exc) {
                    Logger.logErrorMessage("GetNxtTransactions failed", exc);
                    return failure("Unable to get Nxt transactions: " + exc.getMessage());
                }
                break;
            case "suspend":
                TokenAddon.suspend("Suspended by the TokenExchange administrator");
                response.put("suspended", TokenAddon.isSuspended());
                break;
            case "resume":
                TokenAddon.resume();
                response.put("suspended", TokenAddon.isSuspended());
                break;
            case "getAddress":
                accountString = Convert.emptyToNull(req.getParameter("account"));
                if (accountString == null) {
                    return missing("account");
                }
                try {
                    accountId = Convert.parseAccountId(accountString);
                } catch (Exception exc) {
                    return incorrect("account", exc.getMessage());
                }
                publicKeyString = Convert.emptyToNull(req.getParameter("publicKey"));
                byte[] publicKey;
                if (publicKeyString != null) {
                    try {
                        publicKey = Convert.parseHexString(publicKeyString);
                    } catch (Exception exc) {
                        return incorrect("publicKey", exc.getMessage());
                    }
                    if (publicKey.length != 32) {
                        return incorrect("publicKey", "public key is not 32 bytes");
                    }
                    byte[] accountPublicKey = Account.getPublicKey(accountId);
                    if (accountPublicKey != null && !Arrays.equals(accountPublicKey, publicKey)) {
                        return incorrect("publicKey", "public key does not match account public key");
                    }
                } else {
                    publicKey = null;
                }
                try {
                    accountList = TokenDb.getAccount(accountId);
                    if (!accountList.isEmpty()) {
                        account = accountList.get(accountList.size() - 1);
                        if (TokenDb.transactionExists(account.getBitcoinAddress())) {
                            account = null;
                        }
                    } else {
                        account = null;
                    }
                    if (account == null) {
                        DeterministicKey key = BitcoinWallet.getNewKey();
                        account = new BitcoinAccount(key, accountId, publicKey);
                        TokenDb.storeAccount(account);
                    }
                    formatAccount(account, response);
                } catch (Exception exc) {
                    Logger.logErrorMessage("GetAddress failed", exc);
                    return failure("Unable to get new Bitcoin address: " + exc.getMessage());
                }
                break;
            case "getAccounts":
                JSONArray accountArray = new JSONArray();
                accountString = Convert.emptyToNull(req.getParameter("account"));
                addressString = Convert.emptyToNull(req.getParameter("address"));
                try {
                    if (accountString != null) {
                        try {
                            accountId = Convert.parseAccountId(accountString);
                        } catch (Exception exc) {
                            return incorrect("account", exc.getMessage());
                        }
                        accountList = TokenDb.getAccount(accountId);
                        accountList.forEach((a) -> accountArray.add(formatAccount(a, new JSONObject())));
                    } else if (addressString != null) {
                        account = TokenDb.getAccount(addressString);
                        if (account != null) {
                            accountArray.add(formatAccount(account, new JSONObject()));
                        }
                    } else {
                        accountList = TokenDb.getAccounts();
                        accountList.forEach((a) -> accountArray.add(formatAccount(a, new JSONObject())));
                    }
                    response.put("accounts", accountArray);
                } catch (Exception exc) {
                    Logger.logErrorMessage("GetAccounts failed", exc);
                    return failure("Unable to get accounts: " + exc.getMessage());
                }
                break;
            case "deleteAddress":
                addressString = Convert.emptyToNull(req.getParameter("address"));
                if (addressString == null) {
                    return missing("address");
                }
                try {
                    boolean deleted = TokenDb.deleteAccountAddress(addressString);
                    if (deleted) {
                        BitcoinWallet.removeAddress(addressString);
                    }
                    response.put("deleted", deleted);
                } catch (Exception exc) {
                    Logger.logErrorMessage("DeleteAddress failed", exc);
                    return failure("Unable to delete account: " + exc.getMessage());
                }
                break;
            case "getBitcoinTransactions":
                JSONArray txArray = new JSONArray();
                addressString = Convert.emptyToNull(req.getParameter("address"));
                heightString = Convert.emptyToNull(req.getParameter("height"));
                if (heightString == null) {
                    height = 0;
                } else {
                    try {
                        height = Integer.valueOf(heightString);
                    } catch (NumberFormatException exc) {
                        return incorrect("height", exc.getMessage());
                    }
                }
                includeExchangedString = Convert.emptyToNull(req.getParameter("includeExchanged"));
                if (includeExchangedString == null) {
                    includeExchanged = false;
                } else {
                    includeExchanged = Boolean.valueOf(includeExchangedString);
                }
                try {
                    List<BitcoinTransaction> txList = TokenDb.getTransactions(height, addressString, includeExchanged);
                    txList.forEach((tx) -> {
                        JSONObject txJSON = new JSONObject();
                        txJSON.put("bitcoinTxId", tx.getBitcoinTxIdString());
                        txJSON.put("bitcoinBlockId", tx.getBitcoinBlockIdString());
                        txJSON.put("bitcoinChainHeight", tx.getHeight());
                        txJSON.put("timestamp", tx.getTimestamp());
                        txJSON.put("address", tx.getBitcoinAddress());
                        txJSON.put("bitcoinAmount", BigDecimal.valueOf(tx.getBitcoinAmount(), 8).toPlainString());
                        txJSON.put("tokenAmount", BigDecimal.valueOf(tx.getTokenAmount(), TokenAddon.currencyDecimals).toPlainString());
                        txJSON.put("account", Long.toUnsignedString(tx.getAccountId()));
                        txJSON.put("accountRS", tx.getAccountIdRS());
                        txJSON.put("exchanged", tx.isExchanged());
                        if (tx.getNxtTxId() != 0) {
                            txJSON.put("nxtTxId", Long.toUnsignedString(tx.getNxtTxId()));
                        }
                        txArray.add(txJSON);
                    });
                    response.put("transactions", txArray);
                } catch (Exception exc) {
                    Logger.logErrorMessage("GetBitcoinTransactions failed", exc);
                    return failure("Unable to get Bitcoin transactions: " + exc.getMessage());
                }
                break;
            case "sendBitcoins" :
                BitcoinWallet.propagateContext();
                addressString = Convert.emptyToNull(req.getParameter("address"));
                amountString = Convert.emptyToNull(req.getParameter("amount"));
                if (addressString == null) {
                    return missing("address");
                }
                if (amountString == null) {
                    return missing("amount");
                }
                try {
                    Address toAddress = Address.fromBase58(BitcoinWallet.getNetworkParameters(), addressString);
                    BigDecimal amount = new BigDecimal(amountString)
                            .movePointRight(8)
                            .divideToIntegralValue(BigDecimal.ONE)
                            .movePointLeft(8)
                            .stripTrailingZeros();
                    txString = BitcoinWallet.sendCoins(toAddress, amount);
                    response.put("transaction", txString);
                } catch (NumberFormatException exc) {
                    return incorrect("amount", exc.getMessage());
                } catch (AddressFormatException exc) {
                    return incorrect("address", exc.getMessage());
                } catch (Exception exc) {
                    Logger.logErrorMessage("SendBitcoins failed", exc);
                    return failure("Unable to send coins: " + exc.getMessage());
                }
                break;
            case "emptyWallet" :
                BitcoinWallet.propagateContext();
                addressString = Convert.emptyToNull(req.getParameter("address"));
                if (addressString == null) {
                    return missing("address");
                }
                try {
                    Address toAddress = Address.fromBase58(BitcoinWallet.getNetworkParameters(), addressString);
                    txString = BitcoinWallet.emptyWallet(toAddress);
                    response.put("transaction", txString);
                } catch (AddressFormatException exc) {
                    return incorrect("address", exc.getMessage());
                } catch (Exception exc) {
                    Logger.logErrorMessage("EmptyWallet failed", exc);
                    return failure("Unable to empty wallet: " + exc.getMessage());
                }
                break;
            case "rollbackChain":
                heightString = Convert.emptyToNull(req.getParameter("height"));
                if (heightString == null) {
                    return missing("height");
                }
                try {
                    height = Integer.valueOf(heightString);
                    if (BitcoinWallet.getChainHeight() - height > BitcoinWallet.getMaxRollback()) {
                        return incorrect("height", "The requested height is not in the block chain cache");
                    }
                    BitcoinWallet.rollbackChain(height);
                    response.put("completed", true);
                } catch (NumberFormatException exc) {
                    return incorrect("height", exc.getMessage());
                } catch (Exception exc) {
                    Logger.logErrorMessage("RollbackChain failed", exc);
                    return failure("Unable to rollback chain: " + exc.getMessage());
                }
                break;
            default:
                return unknown(function);
        }
        return response;
    }

    /**
     * Format a Bitcoin account
     *
     * @param   account         Bitcoin account
     * @param   response        Response object
     * @return                  Response object
     */
    @SuppressWarnings("unchecked")
    private static JSONObject formatAccount(BitcoinAccount account, JSONObject response) {
        response.put("address", account.getBitcoinAddress());
        response.put("account", Long.toUnsignedString(account.getAccountId()));
        response.put("accountRS", account.getAccountIdRS());
        response.put("timestamp", account.getTimestamp());
        return response;
    }

    /**
     * Create response for a failure
     *
     * @param   message         Error message
     * @return                  Response
     */
    @SuppressWarnings("unchecked")
    private static JSONStreamAware failure(String message) {
        JSONObject response = new JSONObject();
        response.put("errorCode", 6);
        response.put("errorDescription", message);
        return JSON.prepare(response);
    }

    /**
     * Create response for a missing parameter
     *
     * @param   paramNames      Parameter names
     * @return                  Response
     */
    @SuppressWarnings("unchecked")
    private static JSONStreamAware missing(String... paramNames) {
        JSONObject response = new JSONObject();
        response.put("errorCode", 3);
        if (paramNames.length == 1) {
            response.put("errorDescription", "\"" + paramNames[0] + "\"" + " not specified");
        } else {
            response.put("errorDescription", "At least one of " + Arrays.toString(paramNames) + " must be specified");
        }
        return JSON.prepare(response);
    }

    /**
     * Create response for an incorrect parameter
     *
     * @param   paramName           Parameter name
     * @param   details             Error details
     * @return                      Response
     */
    @SuppressWarnings("unchecked")
    private static JSONStreamAware incorrect(String paramName, String details) {
        JSONObject response = new JSONObject();
        response.put("errorCode", 4);
        response.put("errorDescription", "Incorrect \"" + paramName + (details != null ? "\": " + details : "\""));
        return JSON.prepare(response);
    }

    /**
     * Create response for an unknown parameter
     *
     * @param   objectName          Parameter name
     * @return                      Response
     */
    @SuppressWarnings("unchecked")
    private static JSONStreamAware unknown(String objectName) {
        JSONObject response = new JSONObject();
        response.put("errorCode", 5);
        response.put("errorDescription", "Unknown " + objectName);
        return JSON.prepare(response);
    }

    /**
     * Require POST since we use the administrator password
     *
     * @return                  TRUE if POST is required
     */
    @Override
    protected boolean requirePost() {
        return true;
    }

    /**
     * Require the administrator password
     *
     * @return                  TRUE if adminPassword is required
     */
    @Override
    protected boolean requirePassword() {
        return true;
    }

    /**
     * We don't use the required block parameters
     *
     * @return                  TRUE if required block parameters are needed
     */
    @Override
    protected boolean allowRequiredBlockParameters() {
        return false;
    }

    /**
     * We require the full client
     *
     * @return                  TRUE if full client is required
     */
    @Override
    protected boolean requireFullClient() {
        return true;
    }
}
