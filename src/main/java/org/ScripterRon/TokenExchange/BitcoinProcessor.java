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

import nxt.Db;
import nxt.util.Convert;
import nxt.util.Logger;
import nxt.util.ThreadPool;

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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interface between NRS and the Bitcoin server
 */
public class BitcoinProcessor implements Runnable {

    /** Minimum wallet version */
    private static final long MIN_VERSION = 130000L;

    /** Connect timeout (milliseconds) */
    private static final int nodeConnectTimeout = 2000;

    /** Read timeout (milliseconds) */
    private static final int nodeReadTimeout = 5000;

    /** Request identifier */
    private static final AtomicLong requestId = new AtomicLong(0);

    /** Processing thread */
    private static Thread processingThread;

    /** Processing lock */
    private static final ReentrantLock processingLock = new ReentrantLock();

    /** Processing queue */
    private static final LinkedBlockingQueue<Boolean> processingQueue = new LinkedBlockingQueue<>();

    /** Current block chain height */
    private static int chainHeight = 0;

    /** Current best block hash */
    private static String bestBlockHash = "";

    /** Basic authentication */
    private static String encodedAuthentication;
    static {
        try {
            String userpass = TokenAddon.bitcoindUser + ":" + TokenAddon.bitcoindPassword;
            encodedAuthentication = Base64.getEncoder().withoutPadding().encodeToString(userpass.getBytes("UTF-8"));
        } catch (Exception exc) {
            encodedAuthentication = "";
        }
    }

    /** Bitcoin sendtoaddress transaction size (one input, two outputs) */
    private static final BigDecimal sendTxSize = BigDecimal.valueOf(0.226);

    /**
     * Initialize the Bitcoin processor
     *
     * @throws  IllegalArgumentException    Processing error occurred
     * @throws  IOException                 I/O error occurred
     */
    static void init() throws IllegalArgumentException, IOException {
        //
        // Validate the Bitcoin server
        //
        JSONObject response = issueRequest("getnetworkinfo", "[]");
        response = (JSONObject)response.get("result");
        long version = (Long)response.get("version");
        String subversion = (String)response.get("subversion");
        if (version < MIN_VERSION) {
            throw new IllegalArgumentException("Bitcoin server version " + version + " is not supported");
        }
        //
        // Get block chain height and best block hash
        //
        response = issueRequest("getblockcount", "[]");
        int currentHeight = ((Long)response.get("result")).intValue();
        chainHeight = TokenDb.getChainHeight();
        if (chainHeight > 0) {
            byte[] blockId = TokenDb.getChainBlock(chainHeight);
            if (blockId != null) {
                bestBlockHash = Convert.toHexString(blockId);
            }
        } else if (chainHeight < 0) {
            throw new IllegalArgumentException("Unable to get Bitcoin chain height from TokenExchange database");
        }
        Logger.logInfoMessage("Bitcoin server: Version " + version + " (" + subversion + "), Chain height " + currentHeight);
        //
        // Get the current wallet balance
        //
        response = issueRequest("getbalance", "[]");
        BigDecimal balance = getNumber(response.get("result"));
        Logger.logInfoMessage("Bitcoin wallet: Balance " + balance.toPlainString() + " BTC");
        //
        // Start our processing thread after NRS initialization is complete
        //
        ThreadPool.runAfterStart(() -> {
            processingThread = new Thread(new BitcoinProcessor(), "TokenExchange Bitcoin processor");
            processingThread.setDaemon(true);
            processingThread.start();
            try {
                processingQueue.put(true);
            } catch (InterruptedException exc) {
                // Ignored since the queue is unbounded
            }
        });
    }

    /**
     * Shutdown the Bitcoin processor
     */
    static void shutdown() {
        if (processingThread != null) {
            try {
                processingQueue.put(false);
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
     * Get the current Bitcoin block chain height
     *
     * @return                  Block chain height
     */
    static int getChainHeight() {
        return chainHeight;
    }

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
            // Unlock the wallet - it will lock automatically in 15 seconds
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
            Logger.logInfoMessage("Sent " + bitcoinAmount.toPlainString() + " BTC to " + token.getBitcoinAddress()
                    + ", Transaction " + bitcoindTxId);
            //
            // Mark the token as exchanged
            //
            token.setExchanged(Convert.parseHexString(bitcoindTxId));
            if (TokenDb.updateToken(token)) {
                result = true;
            } else {
                Logger.logErrorMessage("BTC was sent but the TokenExchange database was not updated\n "
                        + "  Repair database before restarting the TokenExchange");
            }
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
            // Unlock the wallet - it will lock automatically in 15 seconds
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
            String request = String.format("{\"jsonrpc\": \"2.0\", \"method\": \"%s\", \"params\": %s, \"id\": %d}",
                                           requestType, requestParams, id);
            if (TokenAddon.bitcoindLogging) {
                Logger.logDebugMessage(String.format("Issuing HTTP request to %s: %s", TokenAddon.bitcoindAddress, request));
            }
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
            if (TokenAddon.bitcoindLogging) {
                Logger.logDebugMessage(String.format("Request complete\n%s", nxt.util.JSON.toJSONString(response)));
            }
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

    /**
     * New Bitcoin block received
     */
    static void blockReceived() {
        if (!TokenAddon.isSuspended()) {
            try {
                processingQueue.put(true);
            } catch (InterruptedException exc) {
                // Ignore since the queue is unbounded
            }
        }
    }

    /**
     * Process Bitcoin blocks
     */
    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        Logger.logInfoMessage("TokenExchange Bitcoin processor started");
        try {
            while (processingQueue.take()) {
                if (TokenAddon.isSuspended()) {
                    continue;
                }
                Db.db.beginTransaction();
                obtainLock();
                try {
                    JSONObject response;
                    String blockHash;
                    byte[] blockId;
                    //
                    // Get the current chain height and best block
                    //
                    response = issueRequest("getbestblockhash", "[]");
                    String currentBlockHash = (String)response.get("result");
                    if (currentBlockHash.equals(bestBlockHash)) {
                        Logger.logDebugMessage("Best block already in database");
                        continue;
                    }
                    response = issueRequest("getblockcount", "[]");
                    int currentHeight = ((Long)response.get("result")).intValue();
                    response = issueRequest("getblockhash", "[" + currentHeight + "]");
                    blockHash = (String)response.get("result");
                    //
                    // Locate the junction point between our chain and the current best chain
                    //
                    if (currentHeight != chainHeight + 1 || !blockHash.equals(bestBlockHash)) {
                        while (chainHeight > 0) {
                            blockId = TokenDb.getChainBlock(chainHeight);
                            if (blockId == null) {
                                bestBlockHash = "";
                                break;
                            }
                            bestBlockHash = Convert.toHexString(blockId);
                            response = issueRequest("getblockhash", "[" + chainHeight + "]");
                            blockHash = (String)response.get("result");
                            if (blockHash.equals(bestBlockHash)) {
                                break;
                            }
                            Logger.logDebugMessage("Popping Bitcoin block at height " + chainHeight);
                            if (!TokenDb.popChainBlock(chainHeight)) {
                                throw new RuntimeException("Unable to pop chain block");
                            }
                            chainHeight--;
                        }
                    }
                    if (chainHeight == 0) {
                        chainHeight = Math.max(0, currentHeight - TokenAddon.bitcoinConfirmations - 1);
                        bestBlockHash = "";
                    }
                    String junctionBlockHash = bestBlockHash;
                    //
                    // Update the Bitcoin block chain
                    //
                    Map<String, Integer> blockMap = new HashMap<>();
                    for (int height = chainHeight + 1; height < currentHeight; height++) {
                        response = issueRequest("getblockhash", "[" + height + "]");
                        blockHash = (String)response.get("result");
                        if (!TokenDb.storeChainBlock(height, Convert.parseHexString(blockHash))) {
                            throw new RuntimeException("Unable to store chain block");
                        }
                        blockMap.put(blockHash, height);
                    }
                    if (!TokenDb.storeChainBlock(currentHeight, Convert.parseHexString(currentBlockHash))) {
                        throw new RuntimeException("Unable to store chain block");
                    }
                    blockMap.put(currentBlockHash, currentHeight);
                    chainHeight = currentHeight;
                    bestBlockHash = currentBlockHash;
                    //
                    // Add transactions received since the junction block
                    //
                    response = issueRequest("listsinceblock", "[\"" + junctionBlockHash + "\"]");
                    response = (JSONObject)response.get("result");
                    List<JSONObject> txList = (List)response.get("transactions");
                    for (JSONObject txJSON : txList) {
                        String txHash = (String)txJSON.get("txid");
                        byte[] txId = Convert.parseHexString(txHash);
                        if (TokenDb.transactionExists(txId)) {
                            Logger.logDebugMessage("Bitcoin transaction " + txHash + " is already in the database");
                            continue;
                        }
                        String category = (String)txJSON.get("category");
                        int confirmations = ((Long)txJSON.get("confirmations")).intValue();
                        if (!category.equals("receive") || confirmations < 1) {
                            continue;
                        }
                        String address = (String)txJSON.get("address");
                        BigDecimal bitcoinAmount = getNumber(txJSON.get("amount"));
                        blockHash = (String)txJSON.get("blockhash");
                        Integer blockHeight = blockMap.get(blockHash);
                        int height;
                        if (blockHeight != null) {
                            height = blockHeight;
                        } else {
                            if (!junctionBlockHash.isEmpty()) {
                                Logger.logDebugMessage("Block " + blockHash + " not found in block map");
                            }
                            height = chainHeight - TokenAddon.bitcoinConfirmations;
                        }
                        BitcoinAccount account = TokenDb.getAccount(address);
                        if (account != null) {
                            BigDecimal tokenAmount = bitcoinAmount.divide(TokenAddon.exchangeRate);
                            BitcoinTransaction tx = new BitcoinTransaction(txId, height, address,
                                    account.getAccountId(),
                                    bitcoinAmount.movePointRight(8).longValue(),
                                    tokenAmount.movePointRight(TokenAddon.currencyDecimals).longValue());
                            if (!TokenDb.storeTransaction(tx)) {
                                throw new RuntimeException("Unable to store transaction");
                            }
                        }
                    }
                    Db.db.commitTransaction();
                } catch (Exception exc) {
                    Logger.logErrorMessage("Error while processing Bitcoin blocks, processing suspended", exc);
                    TokenAddon.suspend();
                    Db.db.rollbackTransaction();
                } finally {
                    releaseLock();
                    Db.db.endTransaction();
                }
                //
                // Process pending transactions
                //
                TokenCurrency.processTransactions();
            }
            Logger.logInfoMessage("TokenExchange Bitcoin processor stopped");
        } catch (Throwable exc) {
            Logger.logErrorMessage("TokenExchange Bitcoin processor encountered fatal exception", exc);
            TokenAddon.suspend();
        }
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

}
