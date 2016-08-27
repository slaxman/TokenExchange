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
import nxt.http.API;

/**
 * <p>TokenExchange API
 *
 * <p>The following functions are provided:
 * <ul>
 * <li>list - Returns a transaction token list.  The 'height' parameter
 * can be used to specify the starting height, otherwise a height of 0 is used.
 * Transaction tokens at a height greater than the specified height
 * will be returned.
 * <li>resume - Resume sending bitcoins for redeemed tokens.  The 'adminPassword'
 * parameter must be specified to supply the NRS administrator password.
 * <li>status - Returns the current status of the TokenExchange add-on
 * <li>suspend - Stop sending bitcoins for redeemed tokens.  The 'adminPassword'
 * parameter must be specified to supply the NRS administrator password.
 * </ul>
 */
public class TokenAPI extends APIServlet.APIRequestHandler {

    /**
     * Create the API request handler
     */
    public TokenAPI() {
        super(new APITag[] {APITag.ADDONS}, "function", "height", "adminPassword");
    }

    /**
     * Process the API request
     *
     * @param   req             HTTP request
     * @return                  HTTP response
     */
    @SuppressWarnings("unchecked")
    @Override
    protected JSONStreamAware processRequest(HttpServletRequest req) {
        JSONObject response = new JSONObject();
        String function = Convert.emptyToNull(req.getParameter("function"));
        String heightString = Convert.emptyToNull(req.getParameter("height"));
        String adminPassword = Convert.emptyToNull(req.getParameter("adminPassword"));
        if (function == null) {
            return missing("function");
        }
        switch (function) {
            case "status":
                response.put("exchangeRate", TokenAddon.exchangeRate.toPlainString());
                response.put("currencyCode", TokenAddon.currencyCode);
                response.put("currencyId", Long.toUnsignedString(TokenAddon.currencyId));
                response.put("redemptionAccount", Long.toUnsignedString(TokenAddon.redemptionAccount));
                response.put("redemptionAccountRS", Convert.rsAccount(TokenAddon.redemptionAccount));
                response.put("confirmations", TokenAddon.confirmations);
                response.put("bitcoindAddress", TokenAddon.bitcoindAddress);
                response.put("bitcoindTxFee", TokenAddon.bitcoindTxFee.toPlainString());
                response.put("suspended", TokenListener.isSuspended());
                break;

            case "list":
                int height;
                if (heightString == null) {
                    height = 0;
                } else {
                    try {
                        height = Integer.valueOf(heightString);
                    } catch (NumberFormatException exc) {
                        return incorrect("height", exc.getMessage());
                    }
                }
                List<TokenTransaction> tokenList = TokenDb.getTokens(height);
                JSONArray tokenArray = new JSONArray();
                tokenList.forEach((token) -> {
                   JSONObject tokenObject = new JSONObject();
                   tokenObject.put("id", Long.toUnsignedString(token.getId()));
                   tokenObject.put("sender", Long.toUnsignedString(token.getSenderId()));
                   tokenObject.put("senderRS", Convert.rsAccount(token.getSenderId()));
                   tokenObject.put("height", token.getHeight());
                   tokenObject.put("exchanged", token.isExchanged());
                   tokenObject.put("tokenAmount",
                           BigDecimal.valueOf(token.getTokenAmount(), TokenAddon.currencyDecimals).toPlainString());
                   tokenObject.put("bitcoinAmount",
                           BigDecimal.valueOf(token.getBitcoinAmount(), 8).toPlainString());
                   tokenObject.put("bitcoinAddress", token.getBitcoinAddress());
                   if (token.getBitcoinTxId() != null) {
                       tokenObject.put("bitcoinTxId", token.getBitcoinTxId());
                   }
                   tokenArray.add(tokenObject);
                });
                response.put("tokens", tokenArray);
                break;
            case "suspend":
                if (adminPassword == null) {
                    return missing("adminPassword");
                }
                try {
                    API.verifyPassword(req);
                } catch (ParameterException exc) {
                    return exc.getErrorResponse();
                }
                TokenListener.suspendSend();
                response.put("suspended", TokenListener.isSuspended());
                break;
            case "resume":
                if (adminPassword == null) {
                    return missing("adminPassword");
                }
                try {
                    API.verifyPassword(req);
                } catch (ParameterException exc) {
                    return exc.getErrorResponse();
                }
                TokenListener.resumeSend();
                response.put("suspended", TokenListener.isSuspended());
                break;
            default:
                return unknown(function);
        }
        return response;
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
