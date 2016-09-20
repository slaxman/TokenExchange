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
import org.bitcoinj.core.AddressMessage;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.WrongNetworkException;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.HDUtils;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.core.listeners.TransactionReceivedInBlockListener;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicSeed;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.bitcoinj.core.TransactionConfidence;

/**
 *  Bitcoin SPV wallet
 */
public class BitcoinWallet {

    /** Maximum number of peer connections */
    private static final int MAX_CONNECTIONS = 8;

    /** Dummy block used for change outputs */
    private static final byte[] dummyBlock = new byte[32];

    /** Wallet initialized */
    private static volatile boolean walletInitialized = false;

    /** Wallet lock */
    private static final ReentrantLock walletLock = new ReentrantLock();

    /** Network parameters */
    private static NetworkParameters params;

    /** Wallet context */
    private static Context context;

    /** Root key */
    private static DeterministicKey rootKey;

    /** External key */
    private static DeterministicKey externalParentKey;

    /** Internal key */
    private static DeterministicKey internalParentKey;

    /** Deterministic hierarchy */
    private static DeterministicHierarchy hierarchy;

    /** Primary account path */
    private static final List<ChildNumber> ACCOUNT_ZERO_PATH = new ArrayList<>(1);
    static {
        ACCOUNT_ZERO_PATH.add(ChildNumber.ZERO_HARDENED);
    }

    /** Wallet directory */
    private static File walletDirectory;

    /** Block store */
    private static BlockStore blockStore;

    /** Block chain */
    private static RollbackBlockChain blockChain;

    /** Wallet peer group */
    private static PeerGroup peerGroup;

    /** Wallet key */
    private static DeterministicKey walletKey;

    /** Wallet address */
    private static String walletAddress;

    /** Wallet balance */
    private static long walletBalance;

    /** Receive addresses */
    private static final Map<Address, ReceiveAddress> receiveAddresses = new HashMap<>();

    /** Peer discovery */
    private static final BitcoinDiscovery peerDiscovery = new BitcoinDiscovery();

    /**
     * Initialize the Bitcoin wallet
     *
     * Wallet initialization is performed on the Bitcoin processor thread while the
     * Nxt processor thread is blocked waiting for the completion of wallet initialization.
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
        walletDirectory = new File(dbPath, "TokenExchange");
        Logger.logInfoMessage("TokenExchange files stored in " + walletDirectory.toString());
        if (!walletDirectory.exists()) {
            if (!walletDirectory.mkdirs()) {
                Logger.logErrorMessage("Unable to create TokenExchange directory");
                return false;
            }
        }
        //
        // Initialize BitcoinJ for use on the main Bitcoin network
        //
        params = MainNetParams.get();
        context = new Context(params);
        try {
            //
            // Initialize the deterministic key hierarchy
            //
            initKeys();
            //
            // Load the saved peers for use during peer discovery
            //
            peerDiscovery.loadPeers();
            //
            // Create the block store
            //
            File storeFile = new File(walletDirectory, "spvchain.dat");
            blockStore = new SPVBlockStore(params, storeFile);
            //
            // Create the block chain
            //
            blockChain = new RollbackBlockChain(context, blockStore);
            blockChain.addNewBestBlockListener(Threading.SAME_THREAD, (block) -> {
                propagateContext();
                if (!TokenAddon.isSuspended()) {
                    BitcoinProcessor.processTransactions(block);
                }
            });
            blockChain.addTransactionReceivedListener(Threading.SAME_THREAD, new TransactionReceivedInBlockListener() {
                @Override
                public boolean notifyTransactionIsInBlock(Sha256Hash txHash, StoredBlock block,
                                BlockChain.NewBlockType blockType, int relativeOffset) {
                    // Ignore since we aren't using filtered blocks
                    return false;
                }
                @Override
                public void receiveFromBlock(Transaction tx, StoredBlock block,
                                BlockChain.NewBlockType blockType, int relativeOffset) throws VerificationException {
                    propagateContext();
                    processTransaction(tx, block, blockType, relativeOffset);
                }
            });
            blockChain.addReorganizeListener(Threading.SAME_THREAD, (splitPoint, oldBlocks, newBlocks) -> {
                propagateContext();
                Logger.logInfoMessage("Processing Bitcoin block chain fork at height " + splitPoint.getHeight());
                processReorganization(splitPoint, newBlocks);
            });
            //
            // Initialize the peer network
            //
            initNetwork();
            //
            // Start the peer group
            //
            peerGroup.start();
            Logger.logInfoMessage("Token Exchange peer group started");
            //
            // Download the block chain (this can add transactions to our tables)
            //
            Logger.logInfoMessage("Downloading the block chain");
            peerGroup.downloadBlockChain();
            Logger.logInfoMessage("Block chain download completed");
            //
            // Broadcast pending transactions (we won't wait for completion
            // and will instead remove the transaction from the table when
            // it is received in a block)
            //
            List<Transaction> pendingList = TokenDb.getBroadcastTransactions();
            for (Transaction tx : pendingList) {
                peerGroup.broadcastTransaction(tx);
            }
            //
            // Wallet initialization completed.  The Nxt transaction listener is
            // blocked waiting on us, so let it start running now.  The TokenExchange
            // API is now enabled as well.
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
     * Block chain with rollback support
     */
    private static class RollbackBlockChain extends BlockChain {

        /**
         * Create the block chain
         *
         * @param   context                 BitcoinJ context
         * @param   blockStore              Associated block store
         * @throws  BlockStoreException     Error occurred creating the block chain
         */
        RollbackBlockChain(Context context, BlockStore blockStore) throws BlockStoreException {
            super(context, blockStore);
        }

        /**
         * Roll back the block chain
         *
         * @param   height                  Desired height
         * @throws  BlockStoreException     Unable to rollback the block chain
         */
        public void rollback(int height) throws BlockStoreException {
            rollbackBlockStore(height);
            Logger.logInfoMessage("Bitcoin block chain rollback to height " + height + " completed");
        }
    }

    /**
     * Receive address
     */
    private static class ReceiveAddress {

        /** Bitcoin address hash */
        private final byte[] hash;

        /** Bitcoin address */
        private final String address;

        /** Address child */
        private final ChildNumber childNumber;

        /** Address parent */
        private final ChildNumber parentNumber;

        /**
         * Create a receive address
         *
         * @param   address         Bitcoin address
         * @param   childNumber     Child number
         * @param   parentNumber    Parent number
         */
        public ReceiveAddress(Address address, ChildNumber childNumber, ChildNumber parentNumber) {
            this.hash = address.getHash160();
            this.address = address.toBase58();
            this.childNumber = childNumber;
            this.parentNumber = parentNumber;
        }

        /**
         * Return the Bitcoin address
         *
         * @return              Bitcoin address
         */
        public String getAddress() {
            return address;
        }

        /**
         * Return the child number
         *
         * @return              Child number
         */
        public ChildNumber getChildNumber() {
            return childNumber;
        }

        /**
         * Return the parent number
         *
         * @return              Parent number
         */
        public ChildNumber getParentNumber() {
            return parentNumber;
        }

        /**
         * Get the hash code
         *
         * @return              Hash code
         */
        @Override
        public int hashCode() {
            return Arrays.hashCode(hash);
        }

        /**
         * Check if the supplied object is equal to this object
         *
         * @param   obj         Object to check
         * @return              TRUE if the objects are equal
         */
        @Override
        public boolean equals(Object obj) {
            return (obj instanceof ReceiveAddress) && Arrays.equals(((ReceiveAddress)obj).hash, hash);
        }
    }

    /**
     * Initialize the deterministic key hierarchy
     *
     * @throws  SQLException    Database error occurred
     */
    private static void initKeys() throws SQLException {
        //
        // Create the deterministic hierarchy
        //
        DeterministicSeed seed = TokenDb.getSeed();
        if (seed == null) {
            seed = new DeterministicSeed(new SecureRandom(), DeterministicSeed.DEFAULT_SEED_ENTROPY_BITS,
                    "", System.currentTimeMillis()/1000);
            TokenDb.storeSeed(seed);
        }
        rootKey = HDKeyDerivation.createMasterPrivateKey(seed.getSeedBytes());
        rootKey.setCreationTimeSeconds(seed.getCreationTimeSeconds());
        hierarchy = new DeterministicHierarchy(rootKey);
        hierarchy.get(ACCOUNT_ZERO_PATH, false, true);
        externalParentKey = hierarchy.deriveChild(ACCOUNT_ZERO_PATH, false, false, ChildNumber.ZERO);
        internalParentKey = hierarchy.deriveChild(ACCOUNT_ZERO_PATH, false, false, ChildNumber.ONE);
        //
        // Get the wallet address (external child 0).  This is the address used to fund the wallet.
        //
        List<ChildNumber> path = HDUtils.append(externalParentKey.getPath(), ChildNumber.ZERO);
        walletKey = hierarchy.get(path, false, true);
        Address address = walletKey.toAddress(params);
        ReceiveAddress receiveAddress = new ReceiveAddress(address, ChildNumber.ZERO, externalParentKey.getChildNumber());
        walletAddress = receiveAddress.getAddress();
        receiveAddresses.put(address, receiveAddress);
        //
        // Get the current wallet balance
        //
        List<BitcoinUnspent> unspentList = TokenDb.getUnspentOutputs();
        unspentList.forEach((unspent) -> walletBalance += unspent.getAmount());
        //
        // Build the set of receive keys (change keys are not included since
        // change outputs are stored as soon as they are created)
        //
        List<BitcoinAccount> accounts = TokenDb.getAccounts();
        accounts.forEach((account) -> {
            Address addr = Address.fromBase58(params, account.getBitcoinAddress());
            receiveAddresses.put(addr, new ReceiveAddress(addr,
                    new ChildNumber(account.getChildNumber()), externalParentKey.getChildNumber()));
        });
        Logger.logInfoMessage("Wallet address " + walletAddress
                + ", Balance " + getBalance().toPlainString() + " BTC");
    }

    /**
     * Initialize the network
     *
     * @throws  SQLException    Database error occurred
     */
    private static void initNetwork() throws SQLException {
        //
        // Create the peer group
        //
        // Set the fast catch-up time to the database creation time - 2 hours.  We do
        // this to make sure we get some recent full blocks when we first start (only
        // the blocks headers are downloaded before the catch-up time)
        //
        peerGroup = new PeerGroup(context, blockChain);
        peerGroup.setUserAgent(TokenAddon.applicationName, TokenAddon.applicationVersion);
        peerGroup.setUseLocalhostPeerWhenPossible(true);
        peerGroup.setFastCatchupTimeSecs(TokenDb.getCreationTime() - 2 * 60 * 60);
        peerGroup.addPreMessageReceivedEventListener(Threading.SAME_THREAD, (peer, msg) -> {
            propagateContext();
            if (msg instanceof AddressMessage) {
                peerDiscovery.processAddressMessage(peer, (AddressMessage)msg);
            }
            return msg;
        });
        peerGroup.addConnectedEventListener((peer, count) ->
            Logger.logDebugMessage("Bitcoin peer "
                    + TokenAddon.formatAddress(peer.getAddress().getAddr(), peer.getAddress().getPort())
                    + " connected, peer count " + count));
        peerGroup.addDisconnectedEventListener((peer, count) ->
            Logger.logDebugMessage("Bitcoin peer "
                    + TokenAddon.formatAddress(peer.getAddress().getAddr(), peer.getAddress().getPort())
                    + " disconnected, peer count " + count));
        //
        // Use a single Bitcoin server if the 'bitcoinServer' configuration option is specified.
        // Otherwise, we will select servers using peer discovery.
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
                peerGroup.addAddress(new PeerAddress(params, inetAddress, port));
                peerGroup.setMaxConnections(1);
                peerGroup.setMinBroadcastConnections(1);
            } catch (UnknownHostException exc) {
                Logger.logErrorMessage("Unable to resolve Bitcoin server address '" + host + "'", exc);
                TokenAddon.bitcoinServer = null;
            }
        }
        if (TokenAddon.bitcoinServer == null) {
            peerGroup.addPeerDiscovery(peerDiscovery);
            peerGroup.setMaxConnections(MAX_CONNECTIONS);
            peerGroup.setMinBroadcastConnections(MAX_CONNECTIONS / 2);
        }
    }

    /**
     * Process block chain reorganization
     *
     * We have already been notified of the blocks in the side chain which is causing the reorganization.
     * So we just need to deactivate the transactions in the old blocks and then activate the
     * transactions in the new blocks.
     *
     * @param   splitPoint      Common block between the old and new chains
     * @param   newBlocks       List of blocks in the new fork (highest to lowest height)
     */
    private static void processReorganization(StoredBlock splitPoint, List<StoredBlock> newBlocks) {
        TokenDb.beginTransaction();
        obtainLock();
        try {
            //
            // Deactivate transactions in the old fork
            //
            int splitHeight = splitPoint.getHeight();
            walletBalance -= TokenDb.deactivateUnspentOutputs(splitHeight);
            TokenDb.deactivateTransactions(splitHeight);
            //
            // Activate transactions in the new fork
            //
            for (StoredBlock block : newBlocks) {
                Sha256Hash hash = block.getHeader().getHash();
                int height = block.getHeight();
                walletBalance += TokenDb.activateUnspentOutputs(hash.getBytes(), height);
                TokenDb.activateTransactions(hash.getBytes(), height);
            }
            //
            // Commit the database transaction
            //
            TokenDb.commitTransaction();;
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to process Bitcoin block chain fork at height "
                    + splitPoint.getHeight(), exc);
            try {
                TokenDb.rollbackTransaction();
            } catch (Exception exc1) {
                Logger.logErrorMessage(exc1.toString());
            }
        } finally {
            releaseLock();
            TokenDb.endTransaction();
        }
    }

    /**
     * Process a new transaction
     *
     * @param   tx              Transaction
     * @param   block           Block containing transaction
     * @param   blockType       Block type
     * @param   offset          Transaction offset in block transaction list
     */
    private static void processTransaction(Transaction tx, StoredBlock block,
                                BlockChain.NewBlockType blockType, int offset) {
        Sha256Hash txHash = tx.getHash();
        Sha256Hash blockHash = block.getHeader().getHash();
        TransactionConfidence confidence = tx.getConfidence();
        confidence.setSource(TransactionConfidence.Source.NETWORK);
        int height;
        if (blockType == BlockChain.NewBlockType.BEST_CHAIN) {
            height = block.getHeight();
            confidence.setConfidenceType(TransactionConfidence.ConfidenceType.BUILDING);
            confidence.setAppearedAtChainHeight(height);
        } else {
            height = 0;
            if (!confidence.getConfidenceType().equals(TransactionConfidence.ConfidenceType.BUILDING)) {
                confidence.setConfidenceType(TransactionConfidence.ConfidenceType.PENDING);
            }
        }
        //
        // Process transaction outputs
        //
        // We support just P2PKH (pay-to-public-key-hash) transactions.
        //
        // Our change outputs are ignored since they are already
        // in the unspent transaction output table.
        //
        TokenDb.beginTransaction();
        obtainLock();
        try {
            //
            // Remove transaction from broadcast table if it is one of ours
            //
            TokenDb.deleteBroadcastTransaction(txHash.getBytes());
            //
            // Process each transaction output
            //
            List<TransactionOutput> outputs = tx.getOutputs();
            int index = -1;
            long unspentBalance = 0;
            boolean isRelevant = false;
            for (TransactionOutput output : outputs) {
                index++;
                Address address = output.getAddressFromP2PKHScript(params);
                if (address == null) {
                    continue;
                }
                ReceiveAddress receiveAddress = receiveAddresses.get(address);
                if (receiveAddress == null) {
                    continue;
                }
                BitcoinUnspent unspent = TokenDb.getUnspentOutput(txHash.getBytes(), index, blockHash.getBytes());
                if (unspent != null) {
                    continue;
                }
                if (!receiveAddress.getAddress().equals(walletAddress)) {
                    isRelevant = true;
                }
                long amount = output.getValue().getValue();
                unspent = new BitcoinUnspent(txHash.getBytes(), index, blockHash.getBytes(), amount, height,
                        receiveAddress.getChildNumber(), externalParentKey.getChildNumber());
                TokenDb.storeUnspentOutput(unspent);
                if (height > 0) {
                    unspentBalance += amount;
                    Logger.logInfoMessage("Received Bitcoin transaction " + txHash
                        + " to " + receiveAddress.getAddress() + " for "
                        + BigDecimal.valueOf(amount, 8).stripTrailingZeros().toPlainString() + " BTC");
                }
            }
            //
            // Process a user transaction
            //
            if (isRelevant) {
                BitcoinProcessor.addTransaction(tx, block, height);
            }
            //
            // Commit the database transaction
            //
            TokenDb.commitTransaction();
            walletBalance += unspentBalance;
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to process Bitcoin transaction " + txHash + ", transaction ignored", exc);
            try {
                TokenDb.rollbackTransaction();
            } catch (Exception exc1) {
                Logger.logErrorMessage(exc1.toString());
            }
        } finally {
            releaseLock();
            TokenDb.endTransaction();
        }
    }

    /**
     * Shutdown the Bitcoin wallet
     */
    static void shutdown() {
        if (walletInitialized) {
            try {
                Logger.logInfoMessage("Waiting for peer group to stop ...");
                peerGroup.stop();
                Logger.logInfoMessage("Peer group stopped");
                blockStore.close();
                peerDiscovery.storePeers();
            } catch (IOException exc) {
                Logger.logErrorMessage("Unable to save TokenExchange peers", exc);
            } catch (Exception exc) {
                Logger.logErrorMessage("Unable to shutdown the Bitcoin wallet", exc);
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
     * Obtain the wallet lock
     */
    static void obtainLock() {
        walletLock.lock();
    }

    /**
     * Release the wallet lock
     */
    static void releaseLock() {
        walletLock.unlock();
    }

    /**
     * Get the wallet directory
     *
     * @return                  Wallet directory
     */
    static File getWalletDirectory() {
        return walletDirectory;
    }

    /**
     * Get the network parameters
     *
     * @return                  Network parameters
     */
    static NetworkParameters getNetworkParameters() {
        return params;
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
        return blockChain.getBestChainHeight();
    }

    /**
     * Get the wallet balance
     *
     * @return                  Wallet balance
     */
    static BigDecimal getBalance() {
        return BigDecimal.valueOf(walletBalance).movePointLeft(8).stripTrailingZeros();
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
     * Get a new external key
     *
     * @return                  New key
     * @throws  SQLException    Database exception occurred
     */
    static DeterministicKey getNewKey() throws SQLException {
        return getNewKey(externalParentKey);
    }

    /**
     * Get a new key
     *
     * @param   parentKey       Parent key
     * @return                  New key or null
     * @throws  SQLException    Database error occurred
     */
    static DeterministicKey getNewKey(DeterministicKey parentKey) throws SQLException {
        DeterministicKey key = null;
        ChildNumber child = TokenDb.getNewChild(parentKey.getChildNumber());
        List<ChildNumber> path = HDUtils.append(parentKey.getPath(), child);
        key = hierarchy.get(path, false, true);
        if (parentKey == externalParentKey) {
            Address address = key.toAddress(params);
            ReceiveAddress receiveAddress = new ReceiveAddress(address, child, parentKey.getChildNumber());
            receiveAddresses.put(address, receiveAddress);
        }
        return key;
    }

    /**
     * Get an existing key
     *
     * @param   parentKey       Parent key
     * @param   childNumber     Child number
     */
    static DeterministicKey getKey(DeterministicKey parentKey, ChildNumber childNumber) {
        List<ChildNumber> path = HDUtils.append(parentKey.getPath(), childNumber);
        return hierarchy.get(path, false, true);
    }

    /**
     * Remove an external address
     *
     * @param   address         Address string
     */
    static void removeAddress(String address) {
        Address addr = Address.fromBase58(params, address);
        receiveAddresses.remove(addr);
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
            Address.fromBase58(params, address);
            isValid = true;
        } catch (WrongNetworkException exc ) {
            Logger.logDebugMessage("Bitcoin address " + address + " is for the wrong network");
        } catch (AddressFormatException exc) {
            Logger.logDebugMessage("Bitcoin address " + address + " is not a valid address");
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
     * Get the maximum block chain rollback
     *
     * @return                  Maximum rollback
     */
    static int getMaxRollback() {
        return SPVBlockStore.DEFAULT_NUM_HEADERS;
    }

    /**
     * Roll back the block chain
     *
     * @param   height          Desired chain height
     */
    static boolean rollbackChain(int height) {
        boolean success = false;
        try {
            blockChain.rollback(height);
            success = true;
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to roll back the Bitcoin block chain", exc);
        }
        return success;
    }

    /**
     * Send coins to the target address
     *
     * @param   toAddress                   Target Bitcoin address
     * @param   coins                       Amount to send (BTC)
     * @return                              Transaction identifier
     */
    static String sendCoins(Address toAddress, BigDecimal coins) {
        return sendCoins(toAddress, coins, false);
    }

    /**
     * Send coins to the target address
     *
     * An existing database transaction will be committed or rolled back
     * before returning to the caller.
     *
     * @param   toAddress                   Target Bitcoin address
     * @param   coins                       Amount to send (BTC)
     * @param   emptyWallet                 TRUE if this is a request to empty the wallet
     * @return                              Transaction identifier
     */
    static String sendCoins(Address toAddress, BigDecimal coins, boolean emptyWallet) {
        String transactionId = null;
        TokenDb.beginTransaction();
        obtainLock();
        try {
            long amount = emptyWallet ? walletBalance : coins.movePointRight(8).longValue();
            if (amount < Transaction.MIN_NONDUST_OUTPUT.getValue()) {
                throw new IllegalArgumentException("Transaction amount is too small");
            }
            //
            // Create the base transaction
            //
            Transaction tx = new Transaction(params);
            TransactionConfidence conf = tx.getConfidence();
            conf.setSource(TransactionConfidence.Source.SELF);
            tx.setPurpose(Transaction.Purpose.USER_PAYMENT);
            tx.setLockTime(getChainHeight());
            //
            // Create the outputs
            //
            TransactionOutput output1 = new TransactionOutput(params, tx, Coin.valueOf(amount), toAddress);
            DeterministicKey changeKey = getNewKey(internalParentKey);
            Address changeAddress = changeKey.toAddress(params);
            TransactionOutput output2 = new TransactionOutput(params, tx, Coin.ZERO, changeAddress);
            //
            // Gather unspent outputs to form the inputs for this transaction
            //
            // The unspent outputs are ordered from smallest to largest.  The intent
            // is to reduce the number of unspent outputs as much as possible.  The
            // downside is that this can raise transaction fees since the fees are
            // based on the transaction size.
            //
            int length = 77;
            long fee = BigDecimal.valueOf(length, 3).multiply(TokenAddon.bitcoinTxFee).movePointRight(8).longValue();
            List<TransactionInput> inputs = new ArrayList<>();
            List<DeterministicKey> keys = new ArrayList<>();
            List<TransactionOutput> connectedOutputs = new ArrayList<>();
            List<Script> outScripts = new ArrayList<>();
            long inputAmount = 0;
            List<BitcoinUnspent> unspentList = TokenDb.getUnspentOutputs();
            for (BitcoinUnspent unspent : unspentList) {
                inputAmount += unspent.getAmount();
                DeterministicKey parentKey =
                        unspent.getParentNumber().getI() == 0 ? externalParentKey : internalParentKey;
                DeterministicKey key = getKey(parentKey, unspent.getChildNumber());
                keys.add(key);
                Script outScript = ScriptBuilder.createOutputScript(key.toAddress(params));
                outScripts.add(outScript);
                TransactionOutput output = new TransactionOutput(params, null, Coin.valueOf(inputAmount),
                        outScript.getProgram());
                connectedOutputs.add(output);
                Script inScript = ScriptBuilder.createInputScript(null, key);
                TransactionOutPoint outPoint = new TransactionOutPoint(params, unspent.getIndex(),
                        Sha256Hash.wrap(unspent.getId()));
                TransactionInput input = new TransactionInput(params, tx, inScript.getProgram(), outPoint);
                inputs.add(input);
                length += 148;
                fee = BigDecimal.valueOf(length, 3).multiply(TokenAddon.bitcoinTxFee).movePointRight(8).longValue();
                if (inputAmount >= amount + fee) {
                    break;
                }
            }
            //
            // We will deduct the fee from the amount if we are emptying the wallet.  Otherwise,
            // there must be sufficient funds to cover the requested amount plus the transaction fee.
            // We will add the change to the transaction fee if the change would result in a dust
            // output.
            //
            long change;
            if (emptyWallet) {
                amount -= fee;
                change = 0;
                if (amount < Transaction.MIN_NONDUST_OUTPUT.getValue()) {
                    throw new IllegalArgumentException("Insufficient funds to send " + coins.toPlainString() + " BTC");
                }
                output1.setValue(Coin.valueOf(amount));
            } else {
                change = inputAmount - amount - fee;
                if (change < 0) {
                    throw new IllegalArgumentException("Insufficient funds to send " + coins.toPlainString() + " BTC");
                }
                if (change < Transaction.MIN_NONDUST_OUTPUT.getValue()) {
                    change = 0;
                }
            }
            //
            // Add the inputs and outputs to the transaction
            //
            tx.addOutput(output1);
            if (change > 0) {
                output2.setValue(Coin.valueOf(change));
                tx.addOutput(output2);
            }
            for (TransactionInput input : inputs) {
                tx.addInput(input);
            }
            //
            // Sign the inputs and verify the generated script as a sanity check that everything
            // is correct
            //
            int index = 0;
            for (TransactionInput input : tx.getInputs()) {
                Script inScript = input.getScriptSig();
                Script outScript = outScripts.get(index);
                DeterministicKey key = keys.get(index);
                TransactionSignature signature = tx.calculateSignature(index, key, outScript,
                                Transaction.SigHash.ALL, false);
                inScript = outScript.getScriptSigWithSignature(inScript, signature.encodeToBitcoin(), 0);
                input.setScriptSig(inScript);
                input.verify(connectedOutputs.get(index));
                index++;
            }
            //
            // Update the outputs that we used for this transaction
            //
            List<BitcoinUnspent> usedOutputs = unspentList.subList(0, tx.getInputs().size());
            for (BitcoinUnspent unspent : usedOutputs) {
                TokenDb.spendOutput(unspent.getId(), unspent.getIndex());
            }
            //
            // Add the change output to the unspent outputs now so that it is available
            // for use by the next send request
            //
            if (change > 0) {
                BitcoinUnspent changeOutput = new BitcoinUnspent(tx.getHash().getBytes(), 1, dummyBlock,
                        change, getChainHeight(), changeKey.getChildNumber(), internalParentKey.getChildNumber());
                TokenDb.storeUnspentOutput(changeOutput);
            }
            //
            // Broadcast the transaction
            //
            TokenDb.storeBroadcastTransaction(tx);
            peerGroup.broadcastTransaction(tx);
            //
            // All is well - commit the database transaction
            //
            TokenDb.commitTransaction();
            walletBalance -= amount + fee - change;
            transactionId = tx.getHashAsString();
            Logger.logInfoMessage("Broadcast Bitcoin transaction " + transactionId + " for "
                    + BigDecimal.valueOf(amount, 8).stripTrailingZeros().toPlainString() + " BTC");
        } catch (Exception exc) {
            try {
                TokenDb.rollbackTransaction();
            } catch (Exception exc1) {
                Logger.logErrorMessage("Unable to rollback transaction", exc1);
            }
            throw new RuntimeException(exc.getMessage(), exc);
        } finally {
            releaseLock();
            TokenDb.endTransaction();
        }
        return transactionId;
    }

    /**
     * Empty the wallet
     *
     * @param   toAddress                   Target Bitcoin address
     * @return                              Transaction identifier
     */
    static String emptyWallet(Address toAddress) {
        return sendCoins(toAddress, BigDecimal.ZERO, true);
    }
}
