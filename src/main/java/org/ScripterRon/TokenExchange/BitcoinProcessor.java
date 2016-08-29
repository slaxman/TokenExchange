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
import nxt.util.Logger;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import java.io.FilterOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

/**
 * Interface between NRS and the Bitcoin server
 */
public class BitcoinProcessor {

    /** Connect timeout (milliseconds) */
    private static final int nodeConnectTimeout = 5000;

    /** Read timeout (milliseconds) */
    private static final int nodeReadTimeout = 30000;

    /** Request identifier */
    private static final AtomicLong requestId = new AtomicLong(0);

    /** Basic authentication string */
    private static String encodedAuthentication;
    static {
        try {
            String userpass = TokenAddon.bitcoindUser + ":" + TokenAddon.bitcoindPassword;
            encodedAuthentication = Base64.getEncoder().withoutPadding().encodeToString(userpass.getBytes("UTF-8"));
        } catch (Exception exc) {
            encodedAuthentication = "";
        }
    }

    /** Bitcoind sendtoaddress transaction size */
    private static final BigDecimal sendTxSize = BigDecimal.valueOf(0.226);

    /**
     * Send Bitcoins
     *
     * @param   token           Transaction token
     * @return                  TRUE if the coins were sent
     */
    static boolean sendBitcoins(TokenTransaction token) {
        boolean result = false;
        try {
            JSONObject response;
            //
            // Ensure the necessary funds are available
            //
            BigDecimal bitcoinAmount = BigDecimal.valueOf(token.getBitcoinAmount(), 8);
            BigDecimal bitcoinFee = TokenAddon.bitcoindTxFee.multiply(sendTxSize);
            response = issueRequest("getbalance", "[]");
            BigDecimal bitcoinBalance = getNumber(response.get("result"));
            if (bitcoinAmount.add(bitcoinFee).compareTo(bitcoinBalance) > 0) {
                throw new IOException("Unable to send " + bitcoinAmount.toPlainString() +
                        " BTC: Insufficient funds in wallet");
            }
            //
            // Set the desired transaction fee in BTC/KB
            //
            String params = String.format("[%s]", TokenAddon.bitcoindTxFee.toPlainString());
            issueRequest("settxfee", params);
            //
            // Unlock the wallet - it will lock automatically in 15
            //
            if (TokenAddon.bitcoindWalletPassphrase != null) {
                params = String.format("[\"%s\",15]", TokenAddon.bitcoindWalletPassphrase);
                issueRequest("walletpassphrase", params);
            }
            //
            // Send the bitcoins
            //
            params = String.format("[\"%s\",%s]", token.getBitcoinAddress(), bitcoinAmount.toPlainString());
            response = issueRequest("sendtoaddress", params);
            String bitcoindTxId = (String)response.get("result");
            //
            // Mark the token as exchanged
            //
            token.setExchanged(Convert.parseHexString(bitcoindTxId));
            TokenDb.updateToken(token);
            Logger.logInfoMessage("Sent " + bitcoinAmount.toPlainString() + " BTC to " + token.getBitcoinAddress());
            result = true;
            //
            // Lock the wallet once more
            //
            if (TokenAddon.bitcoindWalletPassphrase != null) {
                issueRequest("walletlock", "[]");
            }
        } catch (NumberFormatException exc) {
            Logger.logErrorMessage("Invalid numeric data returned by server", exc);
        } catch (IOException exc) {
            Logger.logErrorMessage("Unable to send bitcoins", exc);
        }
        return result;
    }

    /**
     * Get a confirmed transaction
     *
     * @param   txid            Bitcoin transaction identifier
     * @return                  Bitcoin transaction or null if not found or not confirmed
     */
    @SuppressWarnings("unchecked")
    static BitcoinTransaction getTransaction(byte[] txid) {
        BitcoinTransaction tx = null;
        try{
            String params = String.format("[\"%s\",false]", Convert.toHexString(txid));
            JSONObject response = issueRequest("gettransaction", params);
            response = (JSONObject)response.get("result");
            int confirmations = ((Long)response.get("confirmations")).intValue();
            if (confirmations > 0) {
                List<JSONObject> detailList = (List<JSONObject>)response.get("details");
                for (JSONObject detail : detailList) {
                    if (((String)detail.get("category")).equals("receive")) {
                        String address = (String)detail.get("address");
                        BitcoinAccount account = TokenDb.getAccount(address);
                        if (account != null) {
                            BigDecimal bitcoinAmount = getNumber(detail.get("amount"));
                            BigDecimal tokenAmount = bitcoinAmount.divide(TokenAddon.exchangeRate);
                            tx = new BitcoinTransaction(txid, address, account.getAccountId(),
                                    bitcoinAmount.movePointRight(8).longValue(),
                                    tokenAmount.movePointRight(TokenAddon.currencyDecimals).longValue(),
                                    confirmations);
                            break;
                        }
                    }
                }
            }
        } catch (IOException exc) {
            Logger.logErrorMessage("Unable to get transaction", exc);
        }
        return tx;
    }

    /**
     * Get a new Bitcoin address
     *
     * @param   label           Address label
     * @return                  Bitcoin address or null if an error occurred
     */
    static String getNewAddress(String label) {
        String address = null;
        String params;
        try {
            //
            // Unlock the wallet - it will lock automatically in 15
            //
            if (TokenAddon.bitcoindWalletPassphrase != null) {
                params = String.format("[\"%s\",15]", TokenAddon.bitcoindWalletPassphrase);
                issueRequest("walletpassphrase", params);
            }
            //
            // Get a new address
            //
            params = String.format("[\"%s\"]", label);
            JSONObject response = issueRequest("getnewaddress", params);
            address = (String)response.get("result");
            //
            // Lock the wallet once more
            //
            if (TokenAddon.bitcoindWalletPassphrase != null) {
                issueRequest("walletlock", "[]");
            }
        } catch (IOException exc) {
            Logger.logErrorMessage("Unable to get new Bitcoin address", exc);
        }
        return address;
    }

    /**
     * Get a numeric value based on the object type
     *
     * @param   obj             Parsed object
     * @return                  Numeric value
     * @throws  IOException     Invalid numeric object
     */
    private static BigDecimal getNumber(Object obj) throws IOException {
        BigDecimal result;
        if (obj instanceof Double) {
            result = BigDecimal.valueOf((Double)obj);
        } else if (obj instanceof Long) {
            result = BigDecimal.valueOf((Long)obj);
        } else if (obj instanceof String) {
            result = new BigDecimal((String)obj);
        } else {
            throw new IOException("Unrecognized numeric result type");
        }
        return result;
    }

    /**
     * Issue the Bitcoin RPC request and return the parsed JSON response
     *
     * @param   requestType     Request type
     * @param   requestParams   Request parameters in JSON format
     * @return                  Parsed JSON response
     * @throws  IOException     Unable to issue Bitcoin RPC request
     * @throws  ParseException  Unable to parse the Bitcoin RPC response
     */
    private static JSONObject issueRequest(String requestType, String requestParams) throws IOException {
        long id = requestId.incrementAndGet();
        JSONObject response = null;
        try {
            URL url = new URL(String.format("http://%s/", TokenAddon.bitcoindAddress));
            String request;
            request = String.format("{\"jsonrpc\": \"2.0\", \"method\": \"%s\", \"params\": %s, \"id\": %d}",
                                    requestType, requestParams, id);
            //Logger.logDebugMessage(String.format("Issue HTTP request to %s: %s", TokenAddon.bitcoindAddress, request));
            byte[] requestBytes = request.getBytes("UTF-8");
            //
            // Issue the request
            //
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json-rpc");
            conn.setRequestProperty("Cache-Control", "no-cache, no-store");
            conn.setRequestProperty("Content-Length", String.format("%d", requestBytes.length));
            conn.setRequestProperty("Accept", "application/json-rpc");
            conn.setRequestProperty("Authorization", "Basic " + encodedAuthentication);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(nodeConnectTimeout);
            conn.setReadTimeout(nodeReadTimeout);
            conn.connect();
            try (FilterOutputStream out = new FilterOutputStream(conn.getOutputStream())) {
                out.write(requestBytes);
                out.flush();
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    String errorText = String.format("Response code %d for '%s' request: %s",
                                                     code, requestType, conn.getResponseMessage());
                    throw new IOException(errorText);
                }
            }
            //
            // Parse the response
            //
            try (InputStreamReader in = new InputStreamReader(conn.getInputStream(), "UTF-8")) {
                response = (JSONObject)JSONValue.parseWithException(in);
                JSONObject errorResponse = (JSONObject)response.get("error");
                if (errorResponse != null) {
                    String errorText = String.format("Error %d returned for '%s' request: %s",
                                errorResponse.get("code"), requestType, errorResponse.get("message"));
                    throw new IOException(errorText);
                }
            }
            //Logger.logDebugMessage(String.format("Request complete\n%s", nxt.util.JSON.toString(response)));
        } catch (MalformedURLException exc) {
            throw new IOException("Malformed Bitcoin RPC URL", exc);
        } catch (ParseException exc) {
            throw new IOException("JSON parse exception for '" + requestType + "' request: " + exc.getMessage());
        } catch (IOException exc) {
            String errorText = String.format("I/O error on '%s' request: %s", requestType, exc.getMessage());
            throw new IOException(errorText);
        }
        return response;
    }
}
