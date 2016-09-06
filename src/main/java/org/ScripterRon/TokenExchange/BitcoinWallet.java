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

import nxt.Constants;
import nxt.Nxt;
import nxt.util.Logger;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.WrongNetworkException;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import org.bitcoinj.core.Sha256Hash;

/**
 *  Bitcoin SPV wallet
 */
public class BitcoinWallet {

    /** Wallet initialized */
    private static volatile boolean walletInitialized = false;

    /** Wallet context */
    private static Context context;

    /** Wallet kit */
    private static WalletAppKit walletKit;

    /** Wallet */
    private static Wallet wallet;

    /** Wallet peer group */
    private static PeerGroup peerGroup;

    /** Wallet address */
    private static String walletAddress;

    /**
     * Initialize the Bitcoin wallet
     *
     * @return                  TRUE if initialization was successful
     */
    static boolean init() {
        Logger.logInfoMessage("Initializing the Bitcoin wallet");
        //
        // We will store the wallet files in the TokenExchange subdirectory
        // of the NRS database directory
        //
        String dbPath;
        String dbPrefix = Constants.isTestnet ? "nxt.testDb" : "nxt.db";
        String dbUrl = Nxt.getStringProperty(dbPrefix + "Url");
        if (dbUrl == null) {
            dbPath = Nxt.getDbDir(Nxt.getStringProperty(dbPrefix + "Dir"));
        } else {
            String[] urlParts = dbUrl.split(":");
            if (urlParts.length < 3) {
                Logger.logErrorMessage("Malformed Nxt database URL, processing suspended");
                return false;
            }
            dbPath = Nxt.getDbDir(urlParts[2].split(";")[0]);
        }
        int index1 = dbPath.lastIndexOf('/');
        int index2 = dbPath.lastIndexOf('\\');
        dbPath = dbPath.substring(0, Math.max(index1, index2));
        File walletDirectory = new File(dbPath, "TokenExchange");
        Logger.logInfoMessage("TokenExchange wallet files stored in " + walletDirectory.toString());
        //
        // Get the wallet context
        //
        Coin txFee = Coin.valueOf(TokenAddon.bitcoinTxFee.movePointRight(8).longValue());
        NetworkParameters params = MainNetParams.get();
        context = new Context(params, 100, txFee, false);
        //
        // Create the wallet
        //
        try {
            walletKit = new WalletAppKit(context, walletDirectory, "wallet") {
                @Override
                protected void onSetupCompleted() {
                    peerGroup().setUseLocalhostPeerWhenPossible(false);
                    peerGroup().addConnectedEventListener((peer, count) ->
                        Logger.logInfoMessage("Bitcoin peer "
                                + TokenAddon.formatAddress(peer.getAddress().getAddr(), peer.getAddress().getPort())
                                + " connected, peer count " + count));
                    peerGroup().addDisconnectedEventListener((peer, count) ->
                        Logger.logInfoMessage("Bitcoin peer "
                                + TokenAddon.formatAddress(peer.getAddress().getAddr(), peer.getAddress().getPort())
                                + " disconnected, peer count " + count));
                    wallet().allowSpendingUnconfirmedTransactions();
                    wallet().addChangeEventListener((eventWallet) -> {
                        propagateContext();
                        if (!TokenAddon.isSuspended()) {
                            BitcoinProcessor.processTransactions();
                        }
                    });
                    wallet().addCoinsReceivedEventListener((eventWallet, tx, oldBalance, newBalance) -> {
                        propagateContext();
                        if (!TokenAddon.isSuspended()) {
                            BitcoinProcessor.addTransaction(tx);
                        }
                    });
                }
            };
            walletKit.setUserAgent(TokenAddon.applicationName, TokenAddon.applicationVersion);
            walletKit.setAutoSave(true);
            walletKit.setAutoStop(false);
            walletKit.setBlockingStartup(false);
            //
            // Use a single Bitcoin server if the 'bitcoinServer' configuration option is specified.
            // Otherwise, we will select 4 servers from the DNS discovery peers.
            //
            if (TokenAddon.bitcoinServer != null) {
                String[] addressParts = TokenAddon.bitcoinServer.split(":");
                String host = addressParts[0];
                int port;
                if (addressParts.length > 1) {
                    port = Integer.valueOf(addressParts[1]);
                } else {
                    port = 8333;
                }
                try {
                    InetAddress inetAddress = InetAddress.getByName(host);
                    walletKit.setPeerNodes(new PeerAddress(params, inetAddress, port));
                } catch (UnknownHostException exc) {
                    Logger.logErrorMessage("Unable to resolve Bitcoin server address '" + host + "'", exc);
                }
            }
            //
            // Start the wallet and wait until it is up and running
            //
            walletKit.startAsync();
            Logger.logInfoMessage("Waiting for Bitcoin wallet to start ...");
            walletKit.awaitRunning();
            Logger.logInfoMessage("Bitcoin wallet started");
            wallet = walletKit.wallet();
            peerGroup = walletKit.peerGroup();
            if (TokenAddon.bitcoinServer == null) {
                peerGroup.setMaxConnections(6);
            } else {
                peerGroup.setMaxConnections(1);
            }
            //
            // Get the wallet address.  This is the address used to fund the wallet and
            // receive change from send requests.
            //
            walletAddress = TokenDb.getWalletAddress();
            if (walletAddress == null) {
                throw new RuntimeException("Unable to get wallet address from database, processing terminated");
            }
            if (walletAddress.isEmpty()) {
                walletAddress = getNewAddress();
                TokenDb.setWalletAddress(walletAddress);
            }
            Logger.logInfoMessage("Wallet address " + walletAddress
                        + ", Balance " + BitcoinWallet.getBalance().toPlainString() + " BTC");
            //
            // Wallet initialization completed.  The Nxt transaction listener is
            // blocked waiting on us, so let it start running now.
            //
            walletInitialized = true;
            Logger.logInfoMessage("Bitcoin wallet initialization completed");
            TokenListener.walletInitialized();
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to initialize the Bitcoin wallet", exc);
        }
        return walletInitialized;
    }

    /**
     * Shutdown the Bitcoin wallet
     */
    static void shutdown() {
        if (walletInitialized) {
            walletKit.stopAsync();
            try {
                Logger.logInfoMessage("Waiting for Bitcoin wallet to stop ...");
                walletKit.awaitTerminated(5000, TimeUnit.MILLISECONDS);
                Logger.logInfoMessage("Bitcoin wallet has stopped");
            } catch (TimeoutException exc) {
                // Ignored
            }
        }
        Logger.logInfoMessage("Bitcoin wallet stopped");
    }

    /**
     * Propagate the wallet context to the current thread
     */
    static void propagateContext() {
        Context.propagate(context);
    }

    /**
     * Get the network parameters
     *
     * @return                  Network parameters
     */
    static NetworkParameters getNetworkParameters() {
        return context.getParams();
    }

    /**
     * Check if the wallet is initialized
     *
     * @return                  TRUE if the wallet is initialized
     */
    static boolean isWalletInitialized() {
        return walletInitialized;
    }

    /**
     * Get the current block chain height
     *
     * @return                  Block chain height
     */
    static int getChainHeight() {
        return wallet.getLastBlockSeenHeight();
    }

    /**
     * Get the wallet balance
     *
     * @return                  Wallet balance
     */
    static BigDecimal getBalance() {
        Coin coin = wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE);
        return BigDecimal.valueOf(coin.getValue()).movePointLeft(8).stripTrailingZeros();
    }

    /**
     * Get the wallet address
     *
     * @return                  Wallet address
     */
    static String getWalletAddress() {
        return walletAddress;
    }

    /**
     * Get a new Bitcoin address
     *
     * @return                  Bitcoin address
     */
    static String getNewAddress() {
        return wallet.freshReceiveAddress().toString();
    }

    /**
     * Validate a Bitcoin address
     *
     * @param   address         Bitcoin address
     * @return                  TRUE if the address is valid
     */
    static boolean validateAddress(String address) {
        boolean isValid = false;
        try {
            Address.fromBase58(getNetworkParameters(), address);
            isValid = true;
        } catch (WrongNetworkException exc ) {
            Logger.logInfoMessage("Bitcoin address " + address + " is for the wrong network");
        } catch (AddressFormatException exc) {
            Logger.logInfoMessage("Bitcoin address " + address + " is not a valid address");
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to validate Bitcoin address " + address, exc);
        }
        return isValid;
    }

    /**
     * Get the current download peer address
     *
     * @return                  Download peer address or null
     */
    static String getDownloadPeer() {
        String address;
        Peer peer = peerGroup.getDownloadPeer();
        if (peer != null) {
            PeerAddress peerAddress = peer.getAddress();
            address = peerAddress.getAddr().toString() + ":" + peerAddress.getPort();
        } else {
            address = null;
        }
        return address;
    }

    /**
     * Get the connected peers
     *
     * @return                  List of connected peers
     */
    static List<Peer> getConnectedPeers() {
        return peerGroup.getConnectedPeers();
    }

    /**
     * Get a wallet transactions
     *
     * @param   hash            Transaction hash
     * @return                  Transaction or null if not found
     */
    static Transaction getTransaction(String hash) {
        return wallet.getTransaction(Sha256Hash.wrap(hash));
    }

    /**
     * Send coins to the target address
     *
     * @param   address                     Target Bitcoin address
     * @param   amount                      Amount to send
     * @return                              Transaction identifier
     * @throws  IllegalArgumentException    Illegal argument specified
     */
    static String sendCoins(String address, BigDecimal amount) throws IllegalArgumentException {
        Transaction tx = null;
        Coin sendAmount = Coin.valueOf(amount.movePointRight(8).longValue());
        Address sendAddress = Address.fromBase58(context.getParams(), address);
        Address changeAddress = Address.fromBase58(context.getParams(), walletAddress);
        SendRequest sendRequest = SendRequest.to(sendAddress, sendAmount);
        sendRequest.changeAddress = changeAddress;
        sendRequest.ensureMinRequiredFee = true;
        try {
            Wallet.SendResult sendResult = wallet.sendCoins(sendRequest);
            tx = sendResult.tx;
        } catch (Wallet.DustySendRequested exc) {
            throw new IllegalArgumentException("Send request for " + amount.toPlainString()
                    + " BTC results in a dust output");
        } catch (InsufficientMoneyException exc) {
            throw new IllegalArgumentException("Insufficient funds to send " + amount.toPlainString()
                    + " BTC to " + address);
        }
        return tx.getHashAsString();
    }

    /**
     * Empty the wallet
     *
     * @param   address                     Target Bitcoin address
     * @return                              Transaction identifier
     * @throws  IllegalArgumentException    Illegal argument specified
     */
    static String emptyWallet(String address) throws IllegalArgumentException {
        Transaction tx = null;
        Address sendAddress = Address.fromBase58(context.getParams(), address);
        Coin coin = wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE);
        SendRequest sendRequest = SendRequest.to(sendAddress, coin);
        sendRequest.emptyWallet = true;
        sendRequest.ensureMinRequiredFee = true;
        try {
            Wallet.SendResult sendResult = wallet.sendCoins(sendRequest);
            tx = sendResult.tx;
        } catch (Wallet.DustySendRequested exc) {
            throw new IllegalArgumentException("Send request to empty the wallet results in a dust output");
        } catch (InsufficientMoneyException exc) {
            throw new IllegalArgumentException("Insufficient funds available to empty the wallet");
        }
        return tx.getHashAsString();
    }
}
