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

import nxt.http.APIServlet;
import nxt.http.APITag;
import nxt.http.ParameterException;
import nxt.util.Convert;
import nxt.util.JSON;

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
 * <li>blockReceived - Notification that a new Bitcoin block has been received.  The
 * 'id' parameter specifies the block identifier.
 * <li>deleteToken - Delete a token from the database.  The 'id' parameter specifies the
 * token to be deleted.
 * <li>getAccounts - Returns a list of Bitcoin addresses associated with Nxt accounts.
 * The 'account' parameter returns the address associated with that account.  The
 * 'address' parameter returns the accounts associated with that address.  All accounts
 * are returned if neither parameter is specified.
 * <li>getAddress - Get a new Bitcoin address and associate it with a Nxt account.
 * The 'account' parameter identifies the Nxt account.  The 'publicKey' parameter
 * can be specified to further identify the Nxt account and should be specified for
 * a new Nxt account.
 * <li>getStatus - Returns the current status of the TokenExchange add-on.
 * <li>getTokens - Returns a list of NXT currency transactions.  The 'height' parameter
 * can be used to specify the starting height, otherwise a height of 0 is used.
 * Transaction tokens at a height greater than the specified height
 * will be returned.  The 'includeExchanged' parameter can be used to return processed
 * tokens in addition to pending tokens.
 * <li>getTransactions - Return a list of transactions received by the Bitcoin wallet for
 * addresses associated with NXT accounts.  Specify the 'address' parameter to limit the
 * list to transactions for that address.  Otherwise, all transactions are returned.  Specify
 * the 'includeExchanged' parameter to return transactions that have been processed as well
 * as pending transactions.
 * <li>resume - Resume sending Bitcoins for redeemed tokens and issuing tokens for received
 * Bitcoins.
 * <li>suspend - Stop sending Bitcoins for redeemed tokens and issuing tokens for received
 * Bitcoins.
 * <li>transactionReceived -Notification that a new bitcoin transaction has been received.  The
 * 'id' parameter specifies the transaction identifier.
 * </ul>
 */
public class TokenAPI extends APIServlet.APIRequestHandler {

    /**
     * Create the API request handler
     */
    public TokenAPI() {
        super(new APITag[] {APITag.ADDONS},
                "function", "id", "includeExchanged", "height", "account", "publicKey", "address");
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
        String heightString;
        String idString;
        String includeExchangedString;
        String accountString;
        String publicKeyString;
        String addressString;
        boolean includeExchanged;
        BitcoinAccount account;
        long accountId;
        switch (function) {
            case "getStatus":
                response.put("exchangeRate", TokenAddon.exchangeRate.toPlainString());
                response.put("currencyCode", TokenAddon.currencyCode);
                response.put("currencyId", Long.toUnsignedString(TokenAddon.currencyId));
                response.put("tokenAccount", Long.toUnsignedString(TokenAddon.accountId));
                response.put("tokenAccountRS", Convert.rsAccount(TokenAddon.accountId));
                response.put("confirmations", TokenAddon.confirmations);
                response.put("bitcoindAddress", TokenAddon.bitcoindAddress);
                response.put("bitcoindTxFee", TokenAddon.bitcoindTxFee.toPlainString());
                response.put("bitcoinChainHeight", BitcoinProcessor.getChainHeight());
                response.put("suspended", TokenAddon.isSuspended());
                break;
            case "getTokens":
                int height;
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
                List<TokenTransaction> tokenList = TokenDb.getTokens(height, includeExchanged);
                JSONArray tokenArray = new JSONArray();
                tokenList.forEach((token) -> {
                    JSONObject tokenObject = new JSONObject();
                    tokenObject.put("id", Long.toUnsignedString(token.getNxtTxId()));
                    tokenObject.put("sender", Long.toUnsignedString(token.getSenderId()));
                    tokenObject.put("senderRS", Convert.rsAccount(token.getSenderId()));
                    tokenObject.put("height", token.getHeight());
                    tokenObject.put("exchanged", token.isExchanged());
                    tokenObject.put("tokenAmount",
                            BigDecimal.valueOf(token.getTokenAmount(), TokenAddon.currencyDecimals).toPlainString());
                    tokenObject.put("bitcoinAmount",
                            BigDecimal.valueOf(token.getBitcoinAmount(), 8).toPlainString());
                    tokenObject.put("address", token.getBitcoinAddress());
                    if (token.getBitcoinTxId() != null) {
                        tokenObject.put("bitcoinTxId", Convert.toHexString(token.getBitcoinTxId()));
                    }
                    tokenArray.add(tokenObject);
                });
                response.put("tokens", tokenArray);
                break;
            case "deleteToken":
                idString = Convert.emptyToNull(req.getParameter("id"));
                if (idString == null) {
                    return missing("id");
                }
                long id = Long.parseUnsignedLong(idString);
                boolean deleted = TokenDb.deleteToken(id);
                response.put("deleted", deleted);
                break;
            case "suspend":
                TokenAddon.suspend();
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
                accountId = Convert.parseAccountId(accountString);
                publicKeyString = Convert.emptyToNull(req.getParameter("publicKey"));
                byte[] publicKey;
                if (publicKeyString != null) {
                    publicKey = Convert.parseHexString(publicKeyString);
                    if (publicKey.length != 32) {
                        return incorrect("publicKey", "public key is not 32 bytes");
                    }
                } else {
                    publicKey = null;
                }
                account = TokenDb.getAccount(accountId);
                if (account == null) {
                    String address = BitcoinProcessor.getNewAddress(Convert.rsAccount(accountId));
                    if (address == null) {
                        return failure("Unable to get new Bitcoin address from server");
                    }
                    account = new BitcoinAccount(address, accountId, publicKey);
                    if (!TokenDb.storeAccount(account)) {
                        return failure("Unable to create Bitcoin account");
                    }
                }
                formatAccount(account, response);
                break;
            case "getAccounts":
                JSONArray accountArray = new JSONArray();
                accountString = Convert.emptyToNull(req.getParameter("account"));
                addressString = Convert.emptyToNull(req.getParameter("address"));
                if (accountString != null) {
                    accountId = Convert.parseAccountId(accountString);
                    account = TokenDb.getAccount(accountId);
                    if (account != null) {
                        accountArray.add(formatAccount(account, new JSONObject()));
                    }
                }
                if (addressString != null) {
                    account = TokenDb.getAccount(addressString);
                    if (account != null) {
                        accountArray.add(formatAccount(account, new JSONObject()));
                    }
                }
                if (accountString == null && addressString == null) {
                    List<BitcoinAccount> accountList = TokenDb.getAccounts();
                    accountList.forEach((a) -> accountArray.add(formatAccount(a, new JSONObject())));
                }
                response.put("accounts", accountArray);
                break;
            case "getTransactions":
                JSONArray txArray = new JSONArray();
                addressString = Convert.emptyToNull(req.getParameter("address"));
                includeExchangedString = Convert.emptyToNull(req.getParameter("includeExchanged"));
                if (includeExchangedString == null) {
                    includeExchanged = false;
                } else {
                    includeExchanged = Boolean.valueOf(includeExchangedString);
                }
                List<BitcoinTransaction> txList = TokenDb.getTransactions(addressString, includeExchanged);
                txList.forEach((tx) -> {
                    JSONObject txJSON = new JSONObject();
                    txJSON.put("bitcoinTxId", Convert.toHexString(tx.getBitcoinTxId()));
                    txJSON.put("address", tx.getBitcoinAddress());
                    txJSON.put("bitcoinAmount", BigDecimal.valueOf(tx.getBitcoinAmount(), 8).toPlainString());
                    txJSON.put("tokenAmount", BigDecimal.valueOf(tx.getTokenAmount(), TokenAddon.currencyDecimals).toPlainString());
                    txJSON.put("account", Long.toUnsignedString(tx.getAccountId()));
                    txJSON.put("accountRS", Convert.rsAccount(tx.getAccountId()));
                    txJSON.put("exchanged", tx.isExchanged());
                    if (tx.getNxtTxId() != 0) {
                        txJSON.put("nxtTxId", Long.toUnsignedString(tx.getNxtTxId()));
                    }
                    txArray.add(txJSON);
                });
                response.put("transactions", txArray);
                break;
            case "blockReceived":
                BitcoinProcessor.blockReceived();
                response.put("processed", true);
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
        response.put("accountRS", Convert.rsAccount(account.getAccountId()));
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
        response.put("errorCode", 4);
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
