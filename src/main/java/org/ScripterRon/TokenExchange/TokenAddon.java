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
import nxt.Block;
import nxt.BlockchainProcessor;
import nxt.Currency;
import nxt.Nxt;
import nxt.addons.AddOn;
import nxt.crypto.Crypto;
import nxt.http.APIServlet;
import nxt.util.Convert;
import nxt.util.Listener;
import nxt.util.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.Properties;

/**
 * TokenExchange add-on
 *
 * TokenExchange listens for currency transfer transactions to the recipient account for the
 * specified currency.  For each transfer transaction, a token is created and stored in the
 * token exchange database.  When the required number of confirmations have been received,
 * Bitcoins are sent to the target Bitcoin address using the token exchange rate.
 *
 * TokenExchange also listens for Bitcoins received by an address associated with a Nxt
 * account.  For each Bitcoin transaction, an exchange transaction is created and stored
 * in the token exchange database.  When the required number of confirmation have been received,
 * token currency is sent to the associated Nxt account using the token exchange rate.
 *
 * TokenExchange includes a Bitcoin wallet which communicates with one or more Bitcoin
 * servers.
 */
public class TokenAddon implements AddOn, Listener<Block> {

    /** Minimum NRS version */
    private static final int[] MIN_VERSION = new int[] {1, 10, 2};

    /** Add-on initialized */
    private static volatile boolean initialized = false;

    /** Add-on initialization delayed */
    private static volatile boolean delayInitialization = false;

    /** Add-on processing suspended */
    private static volatile boolean suspended = false;

    /** Suspension reason */
    private static volatile String suspendReason;

    /** Application name */
    static String applicationName;

    /** Application version */
    static String applicationVersion;

    /** Token exchange rate */
    static BigDecimal exchangeRate;

    /** Token currency code */
    static String currencyCode;

    /** Token currency identifier */
    static long currencyId;

    /** Token currency decimals */
    static int currencyDecimals;

    /** Token account secret phrase */
    static String secretPhrase;

    /** Token account public key */
    static byte[] publicKey;

    /** Token account identifier */
    static long accountId;

    /** Token account RS identifier */
    static String accountIdRS;

    /** Number of Nxt confirmations */
    static int nxtConfirmations;

    /** Number of Bitcoin confirmations */
    static int bitcoinConfirmations;

    /** Bitcoin transaction fee */
    static BigDecimal bitcoinTxFee;

    /** Bitcoin server host and port */
    static String bitcoinServer;

    /**
     * Initialize the TokenExchange add-on
     */
    @Override
    public void init() {
        try {
            //
            // Get our application build properties
            //
            Class<?> mainClass = Class.forName("org.ScripterRon.TokenExchange.TokenAddon");
            try (InputStream classStream = mainClass.getClassLoader().getResourceAsStream("META-INF/token-exchange-application.properties")) {
                if (classStream == null) {
                    throw new IllegalArgumentException("Application build properties not found");
                }
                Properties applicationProperties = new Properties();
                applicationProperties.load(classStream);
                applicationName = applicationProperties.getProperty("application.name");
                applicationVersion = applicationProperties.getProperty("application.version");
            }
            Logger.logInfoMessage(String.format("Initializing %s Version %s", applicationName, applicationVersion));
            //
            // Verify the NRS version
            //
            String[] nrsVersion = Nxt.VERSION.split("\\.");
            if (nrsVersion.length != MIN_VERSION.length) {
                throw new IllegalArgumentException("NRS version " + Nxt.VERSION + " is too short");
            }
            for (int i=0; i<nrsVersion.length; i++) {
                int v = Integer.valueOf(nrsVersion[i]);
                if (v > MIN_VERSION[i]) {
                    break;
                }
                if (v < MIN_VERSION[i]) {
                    throw new IllegalArgumentException("NRS version " + Nxt.VERSION + " is not supported");
                }
            }
            //
            // Process the token-exchange.properties configuration file
            //
            Properties properties = new Properties();
            Nxt.loadProperties(properties, "token-exchange.properties", false);
            nxtConfirmations = getIntegerProperty(properties, "nxtConfirmations", true);
            bitcoinConfirmations = getIntegerProperty(properties, "bitcoinConfirmations", true);
            exchangeRate = getDecimalProperty(properties, "exchangeRate", true)
                    .movePointRight(8)
                    .divideToIntegralValue(BigDecimal.ONE)
                    .movePointLeft(8)
                    .stripTrailingZeros();
            bitcoinTxFee = getDecimalProperty(properties, "bitcoinTxFee", true);
            bitcoinServer = getStringProperty(properties, "bitcoinServer", false);
            secretPhrase = getStringProperty(properties, "secretPhrase", true);
            publicKey = Crypto.getPublicKey(secretPhrase);
            accountId = Account.getId(publicKey);
            accountIdRS = Convert.rsAccount(accountId);
            currencyCode = getStringProperty(properties, "currency", true);
            Currency currency = Currency.getCurrencyByCode(currencyCode);
            if (currency == null) {
                Logger.logWarningMessage("TokenExchange 'currency' property is not a valid Nxt currency");
                delayInitialization = true;
            } else {
                currencyId = currency.getId();
                currencyDecimals = currency.getDecimals();
            }
            Account account = Account.getAccount(accountId);
            if (account == null) {
                Logger.logWarningMessage("TokenExchange account " + accountIdRS + " does not exist");
                delayInitialization = true;
            }
            //
            // Initialize the token exchange database
            //
            TokenDb.init();
            //
            // Delay TokenExchange initialization if the Nxt account or currency does not exist yet
            //
            if (delayInitialization) {
                Logger.logWarningMessage("TokenExchange initialization suspended until Nxt account and currency created");
                Nxt.getBlockchainProcessor().addListener(this, BlockchainProcessor.Event.BLOCK_PUSHED);
            } else {
                finishInitialization(false);
            }
        } catch (IllegalArgumentException exc) {
            Logger.logErrorMessage(exc.getMessage());
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to intialize the TokenExchange database", exc);
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to initialize TokenExchange", exc);
        }
    }

    /**
     * Process a string property
     *
     * @param   properties      Properties
     * @param   name            Property name
     * @param   required        TRUE if this is a required property
     * @return                  Property value or null
     */
    private String getStringProperty(Properties properties, String name, boolean required) {
        String value = properties.getProperty(name);
        if (value == null || value.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("TokenExchange '" + name + "' property not specified");
            }
            value = null;
        }
        return value;
    }

    /**
     * Process an integer property
     *
     * @param   properties      Properties
     * @param   name            Property name
     * @param   required        TRUE if this is a required property
     * @return                  Property value or zero
     */
    private int getIntegerProperty(Properties properties, String name, boolean required) {
        int result;
        String value = getStringProperty(properties, name, required);
        if (value == null) {
            return 0;
        }
        try {
            result = Integer.valueOf(value);
        } catch (NumberFormatException exc) {
            throw new IllegalArgumentException("TokenExchange '" + name + "' property is not a valid integer");
        }
        return result;
    }

    /**
     * Process a decimal property
     *
     * @param   properties      Properties
     * @param   name            Property name
     * @param   required        TRUE if this is a required property
     * @return                  Property value or zero
     */
    private BigDecimal getDecimalProperty(Properties properties, String name, boolean required) {
        BigDecimal result;
        String value = getStringProperty(properties, name, required);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            result = new BigDecimal(value);
        } catch (NumberFormatException exc) {
            throw new IllegalArgumentException("TokenExchange '" + name + "' property is not a valid number");
        }
        return result;
    }

    /**
     * Wait until the Nxt account and currency have been created
     *
     * We will check after each block is processed.  Once the Nxt account
     * and currency have been created, we will finish the TokenExchange
     * initialization and start our processing tasks.
     *
     * @param   block           Block added to chain
     */
    @Override
    public void notify(Block block) {
        if (delayInitialization) {
            delayInitialization = false;
            boolean nxtSetup = false;
            Currency currency = Currency.getCurrencyByCode(currencyCode);
            if (currency != null) {
                Account account = Account.getAccount(accountId);
                if (account != null) {
                    Logger.logInfoMessage("TokenExchange initialization resuming");
                    nxtSetup = true;
                    currencyId = currency.getId();
                    currencyDecimals = currency.getDecimals();
                    Nxt.getBlockchainProcessor().removeListener(this, BlockchainProcessor.Event.BLOCK_PUSHED);
                    finishInitialization(true);
                }
            }
            if (!nxtSetup) {
                delayInitialization = true;
            }
        }
    }


    /**
     * Finish TokenExchange initialization
     *
     * @param   delayed         TRUE for delayed initialization
     */
    private void finishInitialization(boolean delayed) {
        try {
            Account account = Account.getAccount(accountId);
            Logger.logInfoMessage("TokenExchange account " + accountIdRS + " has "
                    + BigDecimal.valueOf(account.getUnconfirmedBalanceNQT(), 8).stripTrailingZeros().toPlainString()
                    + " NXT");
            Logger.logInfoMessage("TokenExchange account " + accountIdRS + " has "
                    + BigDecimal.valueOf(Account.getUnconfirmedCurrencyUnits(accountId, currencyId), currencyDecimals).stripTrailingZeros().toPlainString()
                    + " units of currency " + currencyCode);
            //
            // Initialize the Bitcoin processor
            //
            BitcoinProcessor.init(delayed);
            //
            // Initialize the Nxt token listener
            //
            TokenListener.init();
            //
            // Add-on initialization completed
            //
            initialized = true;
            Logger.logInfoMessage("TokenExchange initialized");
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to initialize TokenExchange", exc);
        }
    }

    /**
     * Shutdown the TokenExchange add-on
     */
    @Override
    public void shutdown() {
        delayInitialization = false;
        if (initialized) {
            TokenListener.shutdown();
            BitcoinProcessor.shutdown();
            BitcoinWallet.shutdown();
            initialized = false;
        }
        Logger.logInfoMessage("TokenExchange stopped");
    }

    /**
     * Get our API request handler
     *
     * @return                  API request handler
     */
    @Override
    public APIServlet.APIRequestHandler getAPIRequestHandler() {
        return new TokenAPI();
    }

    /**
     * Get our API request type
     *
     * @return                  API request type
     */
    @Override
    public String getAPIRequestType() {
        return "tokenExchange";
    }

    /**
     * Check if processing is suspended
     *
     * @return                  TRUE if send is suspended
     */
    static boolean isSuspended() {
        return suspended;
    }

    /**
     * Get the suspend reason
     *
     * @return                  Suspend reason or null
     */
    static String getSuspendReason() {
        return suspendReason;
    }

    /**
     * Suspend processing
     *
     * @param   reason          Reason for the suspension
     */
    static void suspend(String reason) {
        suspendReason = reason;
        suspended = true;
        Logger.logErrorMessage(reason + ": TokenExchange processing suspended");
    }

    /**
     * Resume processing
     */
    static void resume() {
        suspended = false;
        suspendReason = null;
        Logger.logInfoMessage("TokenExchange processing resumed");
    }

    /**
     * Format an address
     *
     * @param   address         INET address
     * @param   port            Port
     * @return                  Formatted string
     */
    static String formatAddress(InetAddress address, int port) {
        String hostString = address.toString();
        String hostName;
        String hostAddress;
        int index = hostString.indexOf("/");
        if (index == 0) {
            hostName = "";
            hostAddress = hostString.substring(1);
        } else if (index > 0) {
            hostName = hostString.substring(0, index);
            hostAddress = hostString.substring(index+1);
        } else {
            hostName = "";
            hostAddress = hostString;
        }
        if (hostAddress.contains(":")) {
            hostAddress = "[" + hostAddress + "]";
        }
        return (!hostName.isEmpty() ? hostName + "/" + hostAddress : hostAddress) + ":" + port;
    }
}
