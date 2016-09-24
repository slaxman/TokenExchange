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

import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.TransactionOutputChanges;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

import java.util.ArrayList;

/**
 * This block store supports the Simplified Payment Verification (SPV) mode
 * where individual blocks are verified but transaction inputs are not connected
 * to transaction outputs.  We verify the Merkle root in each block to make sure
 * that transactions have not been substituted but rely on the Bitcoin node to
 * verify connected outputs.  For this reason, transaction outputs should not be
 * spent until the block containing the transaction has been confirmed by the network.
 */
public class BitcoinBlockChain extends AbstractBlockChain {

    /** Associated block store */
    private final BlockStore blockStore;

    /** Fast catch-up time */
    private final long fastCatchUpTime;

    /**
     * Construct a BlockChain without a wallet
     *
     * @param   context                 Current context
     * @param   blockStore              Block store for use by this block chain
     * @param   fastCatchUpTime         Fast catch-up time
     * @throws  BlockStoreException     Error occurred
     */
    public BitcoinBlockChain(Context context, BlockStore blockStore, long fastCatchUpTime)
            throws BlockStoreException {
        super(context, new ArrayList<>(), blockStore);
        this.blockStore = blockStore;
        this.fastCatchUpTime = fastCatchUpTime;
    }

    /**
     * Add block to block store
     *
     * We don't connect transaction outputs, so transaction output changes are ignored
     *
     * @param   storedPrev              Previous block
     * @param   blockHeader             Current block header
     * @param   txOutChanges            Transaction output changes
     * @return                          New stored block
     * @throws  BlockStoreException     Unable to store the block
     * @throws  VerificationException   Block verification failed
     */
    @Override
    protected StoredBlock addToBlockStore(StoredBlock storedPrev, Block blockHeader,
            TransactionOutputChanges txOutChanges) throws BlockStoreException, VerificationException {
        StoredBlock newBlock = storedPrev.build(blockHeader);
        blockStore.put(newBlock);
        return newBlock;
    }

    /**
     * Add block to block store
     *
     * @param   storedPrev              Previous block
     * @param   blockHeader             Current block header
     * @return                          New stored block
     * @throws  BlockStoreException     Unable to store the block
     * @throws  VerificationException   Block verification failed
     */
    @Override
    protected StoredBlock addToBlockStore(StoredBlock storedPrev, Block blockHeader)
            throws BlockStoreException, VerificationException {
        StoredBlock newBlock = storedPrev.build(blockHeader);
        blockStore.put(newBlock);
        return newBlock;
    }

    /**
     * Roll back the block store to the specified height
     *
     * @param   height                  New block chain height
     * @throws  BlockStoreException     Unable to set new chain head
     */
    @Override
    protected void rollbackBlockStore(int height) throws BlockStoreException {
        lock.lock();
        try {
            int currentHeight = getBestChainHeight();
            if (height < 0 || height > currentHeight) {
                throw new IllegalArgumentException("Bad height: " + height);
            }
            if (height == currentHeight) {
                return;
            }
            StoredBlock newChainHead = blockStore.getChainHead();
            while (newChainHead.getHeight() > height) {
                newChainHead = newChainHead.getPrev(blockStore);
                if (newChainHead == null) {
                    throw new BlockStoreException("Unreachable height");
                }
            }
            blockStore.put(newChainHead);
            this.setChainHead(newChainHead);
        } finally {
            lock.unlock();
        }
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

    /**
     * Indicate we want to verify transactions
     *
     * We will skip transaction verification until we reach our database creation time
     * since we don't care about those transactions
     *
     * @return                          TRUE to verify transactions
     */
    @Override
    protected boolean shouldVerifyTransactions() {
        return (chainHead.getHeader().getTimeSeconds() >= fastCatchUpTime);
    }

    /**
     * Connect transactions
     *
     * We don't keep the transactions in the block store, so we can't connect
     * transaction inputs to transaction outputs.  We have to rely on the Bitcoin
     * node to do this for us.
     *
     * @param   height                  Block height
     * @param   block                   Block being added to the chain
     * @return                          Transaction output changes
     */
    @Override
    protected TransactionOutputChanges connectTransactions(int height, Block block) {
        return null;
    }

    /**
     * Connect transactions
     *
     * We don't keep the transactions in the block store, so we can't connect
     * transaction inputs to transaction outputs.  We have to rely on the Bitcoin
     * node to do this for us.
     *
     * @param   newBlock                Block being added to the chain
     * @return                          Transaction output changes
     */
    @Override
    protected TransactionOutputChanges connectTransactions(StoredBlock newBlock) {
        return null;
    }

    /**
     * Disconnect transactions
     *
     * We don't need to disconnect transactions since we don't connect them.
     *
     * @param block                     Block being removed from the chain
     */
    @Override
    protected void disconnectTransactions(StoredBlock block) {
    }

    /**
     * Set the chain head
     *
     * @param   chainHead               New block chain head
     * @throws  BlockStoreException     Unable to set the chain head
     */
    @Override
    protected void doSetChainHead(StoredBlock chainHead) throws BlockStoreException {
        blockStore.setChainHead(chainHead);
    }

    /**
     * Cancel setting a new chain head
     *
     * We don't connect transactions so there is no need to do anything
     *
     * @throws  BlockStoreException     Unable to cancel chain head update
     */
    @Override
    protected void notSettingChainHead() throws BlockStoreException {
    }

    /**
     * Get a block from the block store
     *
     * @param   hash                    Block identifier
     * @return                          Stored block or null if not found
     * @throws  BlockStoreException     Unable to get stored block
     */
    @Override
    protected StoredBlock getStoredBlockInCurrentScope(Sha256Hash hash) throws BlockStoreException {
        return blockStore.get(hash);
    }
}
