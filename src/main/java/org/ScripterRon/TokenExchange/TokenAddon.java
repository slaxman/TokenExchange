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
import nxt.Currency;
import nxt.Nxt;
import nxt.addons.AddOn;
import nxt.crypto.Crypto;
import nxt.http.APIServlet;
import nxt.util.Convert;
import nxt.util.Logger;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Properties;

/**
 * TokenExchange add-on
 *
 * TokenExchange listens for currency transfer transactions to the recipient account for the
 * specified currency.  For each transfer transaction, a token is created and stored in the
 * token exchange database.  When the required number of confirmations have been received,
 * Bitcoins are sent to the target Bitcoin address using the token exchange rate.
 */
public class TokenAddon implements AddOn {

    /** Minimum NRS version */
    private static final int[] MIN_VERSION = new int[] {1, 10, 2};

    /** Add-on initialized */
    private static boolean initialized = false;

    /** Add-on processing suspended */
    private static volatile boolean suspended = false;

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

    /** Number of confirmations */
    static int confirmations;

    /** Bitcoind address */
    static String bitcoindAddress;

    /** Bitcoind user */
    static String bitcoindUser;

    /** Bitcoind password */
    static String bitcoindPassword;

    /** Bitcoind wallet passphrase */
    static String bitcoindWalletPassphrase;

    /** Bitcoind transaction fee */
    static BigDecimal bitcoindTxFee;

    /** Bitcoind RPC logging */
    static boolean bitcoindLogging;

    /**
     * Initialize the TokenExchange add-on
     */
    @Override
    public void init() {
        try {
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
            confirmations = getIntegerProperty(properties, "confirmations", true);
            exchangeRate = getDecimalProperty(properties, "exchangeRate", true);
            bitcoindTxFee = getDecimalProperty(properties, "bitcoindTxFee", true);
            bitcoindAddress = getStringProperty(properties, "bitcoindAddress", true);
            bitcoindUser = getStringProperty(properties, "bitcoindUser", true);
            bitcoindPassword = getStringProperty(properties, "bitcoindPassword", true);
            bitcoindWalletPassphrase = getStringProperty(properties, "bitcoindWalletPassphrase", false);
            bitcoindLogging = getBooleanProperty(properties, "bitcoindLogging", false);
            secretPhrase = getStringProperty(properties, "secretPhrase", true);
            publicKey = Crypto.getPublicKey(secretPhrase);
            accountId = Account.getId(publicKey);
            currencyCode = getStringProperty(properties, "currency", true);
            Currency currency = Currency.getCurrencyByCode(currencyCode);
            if (currency == null) {
                throw new IllegalArgumentException("TokenExchange 'currency' property is not a valid currency");
            }
            currencyId = currency.getId();
            currencyDecimals = currency.getDecimals();
            Account account = Account.getAccount(accountId);
            Logger.logInfoMessage("TokenExchange account " + Convert.rsAccount(accountId) + " has "
                    + BigDecimal.valueOf(account.getUnconfirmedBalanceNQT(), 8).toPlainString()
                    + " NXT");
            Logger.logInfoMessage("TokenExchange account " + Convert.rsAccount(accountId) + " has "
                    + BigDecimal.valueOf(Account.getUnconfirmedCurrencyUnits(accountId, currencyId), currencyDecimals).toPlainString()
                    + " units of currency " + currencyCode);
            //
            // Initialize the token database
            //
            TokenDb.init();
            //
            // Initialize the Bitcoin processor
            //
            BitcoinProcessor.init();
            //
            // Initialize the token listener
            //
            TokenListener.init();
            //
            // Add-on initialization completed
            //
            initialized = true;
        } catch (IllegalArgumentException exc) {
            Logger.logErrorMessage(exc.getMessage());
        } catch (SQLException exc) {
            Logger.logErrorMessage("Unable to intialize the TokenExchange database", exc);
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to initialize the TokenExchange add-on", exc);
        }
        if (initialized) {
            Logger.logInfoMessage("TokenExchange add-on initialized");
        } else {
            Logger.logInfoMessage("TokenExchange add-on initialization failed");
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
     * Process a boolean property
     *
     * @param   properties      Properties
     * @param   name            Property name
     * @param   required        TRUE if this is a required property
     * @return                  Property value or 'false' if not specified
     */
    private boolean getBooleanProperty(Properties properties, String name, boolean required) {
        String value = getStringProperty(properties, name, required);
        if (value == null) {
            return false;
        }
        return Boolean.valueOf(value);
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
     * Shutdown the TokenExchange add-on
     */
    @Override
    public void shutdown() {
        TokenListener.shutdown();
        BitcoinProcessor.shutdown();
        initialized = false;
        Logger.logInfoMessage("TokenExchange add-on shutdown");
    }

    /**
     * Get our API request handler
     *
     * @return                  API request handler
     */
    @Override
    public APIServlet.APIRequestHandler getAPIRequestHandler() {
        return initialized ? new TokenAPI() : null;
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
     * Suspend processing
     */
    static void suspend() {
        suspended = true;
        Logger.logWarningMessage("TokenExchange processing suspended");
    }

    /**
     * Resume processing
     */
    static void resume() {
        suspended = false;
        Logger.logInfoMessage("TokenExchange processing resumed");
    }
}
