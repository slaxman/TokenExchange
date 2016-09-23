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

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

/**
 * Bitcoin block store
 *
 * Blocks are stored in a SQL table
 */
public class BitcoinBlockStore implements BlockStore {

    /** Network parameters */
    private final NetworkParameters params;

    /**
     * Create the block store
     *
     * @param   params                  Network parameters
     * @throws  BlockStoreException     Error occurred
     */
    BitcoinBlockStore(NetworkParameters params) throws BlockStoreException {
        this.params = params;
        try {
            StoredBlock genesisBlock = getChainHead();
            if (genesisBlock == null) {
                Block genesis = params.getGenesisBlock().cloneAsHeader();
                genesisBlock = new StoredBlock(genesis, genesis.getWork(), 0);
                put(genesisBlock);
                setChainHead(genesisBlock);
            }
        } catch (Exception exc) {
            throw new BlockStoreException("Unable to initialize the Bitcoin block store", exc);
        }
    }

    /**
     * Saves the given block header+extra data
     *
     * @param   block                   Block to store
     * @throws  BlockStoreException     Error occurred
     */
    @Override
    public void put(StoredBlock block) throws BlockStoreException {
        try {
            TokenDb.storeBlock(block);
        } catch (Exception exc) {
            throw new BlockStoreException("Unable to store Bitcoin block", exc);
        }
    }

    /**
     * Returns the StoredBlock given a hash
     *
     * @param   hash                    Block identifier
     * @return                          Block or null if not found
     * @throws  BlockStoreException     Error occurred
     */
    @Override
    public StoredBlock get(Sha256Hash hash) throws BlockStoreException {
        StoredBlock block;
        try {
            block = TokenDb.getBlock(params, hash);
        } catch (Exception exc) {
            throw new BlockStoreException("Unable to get Bitcoin block", exc);
        }
        return block;
    }

    /**
     * Returns the block that represents the top of the chain of greatest total work
     *
     * @return                          Block chain head or null if chain not initialized
     * @throws  BlockStoreException     Error occurred
     */
    @Override
    public StoredBlock getChainHead() throws BlockStoreException {
        StoredBlock block;
        try {
            block = TokenDb.getChainHead(params);
        } catch (Exception exc) {
            throw new BlockStoreException("Unable to get Bitcoin block chain head", exc);
        }
        return block;
    }

    /**
     * Sets the block that represents the top of the chain of greatest total work
     *
     * @param   chainHead               Chain head
     * @throws  BlockStoreException     Error occurred
     */
    @Override
    public void setChainHead(StoredBlock chainHead) throws BlockStoreException {
        try {
            TokenDb.setChainHead(chainHead);
        } catch (Exception exc) {
            throw new BlockStoreException("Unable to set Bitcoin block chain head", exc);
        }
    }

    /**
     * Closes the store
     *
     * @throws  BlockStoreException     Error occurred
     */
    @Override
    public void close() throws BlockStoreException {
        // Nothing to do since the block table will be closed when the database is closed
    }

    /**
     * Get the network parameters of this store
     *
     * @return                          The network parameters
     */
    @Override
    public NetworkParameters getParams() {
        return params;
    }
}
