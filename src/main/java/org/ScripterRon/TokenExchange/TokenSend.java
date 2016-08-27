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

/**
 * Send bitcoins
 */
public class TokenSend {

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

    /**
     * Send bitcoins
     *
     * @param   token           Transaction token
     * @return                  TRUE if the coins were sent
     */
    static boolean sendBitcoins(TokenTransaction token) {
        boolean result = false;
        try {
            String params = String.format("[%s]", TokenAddon.bitcoindTxFee.toPlainString());
            issueRequest("settxfee", params);
            params = String.format("[\"%s\",15]", TokenAddon.bitcoindWalletPassphrase);
            issueRequest("walletpassphrase", params);
            BigDecimal bitcoinAmount = BigDecimal.valueOf(token.getBitcoinAmount(), 8);
            params = String.format("[\"%s\",%s]", token.getBitcoinAddress(), bitcoinAmount.toPlainString());
            JSONObject response = issueRequest("sendtoaddress", params);
            String bitcoindTxId = (String)response.get("result");
            issueRequest("walletlock", "[]");
            token.setExchanged(bitcoindTxId);
            TokenDb.updateToken(token);
            result = true;
        } catch (IOException exc) {
            Logger.logErrorMessage("Unable to send bitcoins", exc);
        }
        return result;
    }

    /**
     * Issue the Bitcoin RPC request and return the parsed JSON response
     *
     * @param       requestType             Request type
     * @param       requestParams           Request parameters in JSON format or null if no parameters
     * @return                              Parsed JSON response
     * @throws      IOException             Unable to issue Bitcoin RPC request
     * @throws      ParseException          Unable to parse the Bitcoin RPC response
     */
    private static JSONObject issueRequest(String requestType, String requestParams) throws IOException {
        long id = requestId.incrementAndGet();
        JSONObject response = null;
        try {
            URL url = new URL(String.format("http://%s/", TokenAddon.bitcoindAddress));
            String request;
            if (requestParams != null) {
                request = String.format("{\"jsonrpc\": \"2.0\", \"method\": \"%s\", \"params\": %s, \"id\": %d}",
                                        requestType, requestParams, id);
            } else {
                request = String.format("{\"jsonrpc\": \"2.0\", \"method\": \"%s\", \"id\": %d}",
                                        requestType, id);
            }
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
                    String errorText = String.format("Response code %d for %s request: %s",
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
                    String errorText = String.format("Error %d returned for %s request: %s",
                                errorResponse.get("code"), requestType, errorResponse.get("message"));
                    throw new IOException(errorText);
                }
            }
            //Logger.logDebugMessage(String.format("Request complete\n%s", nxt.util.JSON.toString(response)));
        } catch (MalformedURLException exc) {
            throw new IOException("Malformed Bitcoin RPC URL", exc);
        } catch (ParseException exc) {
            throw new IOException("JSON parse exception for " + requestType + " request: " + exc.getMessage());
        } catch (IOException exc) {
            String errorText = String.format("I/O error on %s request: %s", requestType, exc.getMessage());
            throw new IOException(errorText);
        }
        return response;
    }
}
