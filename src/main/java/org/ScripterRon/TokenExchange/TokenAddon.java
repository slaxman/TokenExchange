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

import nxt.Currency;
import nxt.Nxt;
import nxt.addons.AddOn;
import nxt.http.APIServlet;
import nxt.util.Convert;
import nxt.util.Logger;

import java.math.BigDecimal;
import java.util.Properties;

/**
 * TokenExchange add-on
 *
 * TokenExchange listens for currency transfer transactions to the recipient account for the
 * specified currency.  For each transfer transaction, a token is created and stored in the
 * token exchange database.  When the required number of confirmations have been received,
 * bitcoins are sent to the target bitcoin address using the token exchange rate.
 */
public class TokenAddon implements AddOn {

    /** Add-on initialized */
    private static boolean initialized = false;

    /** Token exchange rate */
    static BigDecimal exchangeRate;

    /** Token currency code */
    static String currencyCode;

    /** Token currency identifier */
    static long currencyId;

    /** Token currency decimals */
    static int currencyDecimals;

    /** Token redemption account */
    static long redemptionAccount;

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

    /**
     * Initialize the TokenExchange add-on
     */
    @Override
    public void init() {
        try {
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
            String value = getStringProperty(properties, "redemptionAccount", true);
            try {
                redemptionAccount = Convert.parseAccountId(value);
            } catch (RuntimeException exc) {
                throw new IllegalArgumentException("TokenExchange 'redemptionAccount' property is not a valid account identifier");
            }
            currencyCode = getStringProperty(properties, "currency", true);
            Currency currency = Currency.getCurrencyByCode(currencyCode);
            if (currency == null) {
                throw new IllegalArgumentException("TokenExchange 'currency' property is not a valid currency");
            }
            currencyId = currency.getId();
            currencyDecimals = currency.getDecimals();
            //
            // Initialize the token database
            //
            TokenDb.init();
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

}
