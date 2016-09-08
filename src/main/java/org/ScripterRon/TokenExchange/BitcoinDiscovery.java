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

import org.bitcoinj.core.AddressMessage;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.listeners.PreMessageReceivedEventListener;
import org.bitcoinj.net.discovery.PeerDiscovery;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Peer discovery for the Bitcoin wallet
 */
public class BitcoinDiscovery implements PeerDiscovery, PreMessageReceivedEventListener {

    /** Peer address list */
    private final List<PeerAddress> peerAddresses = new ArrayList<>();

    /** Peer address map */
    private final Map<InetSocketAddress, PeerAddress> peerMap = new HashMap<>();

    /**
     * Process a received message
     *
     * We will look for ADDR messages and add the peers to our list.  If the
     * peer is already in the list, we will update the services and timestamp
     * for the peer.
     *
     * @param   peer            Peer receiving the message
     * @param   message         Received message
     * @return                  Message to be processed or null to discard message
     */
    @Override
    public Message onPreMessageReceived(Peer peer, Message message) {
        if (message instanceof AddressMessage) {
            List<PeerAddress> peerAddrs = ((AddressMessage)message).getAddresses();
            NetworkParameters networkParams = BitcoinWallet.getNetworkParameters();
            synchronized(peerAddresses) {
                peerAddrs.forEach((peerAddr) -> {
                    InetSocketAddress socketAddr = peerAddr.getSocketAddress();
                    if (!socketAddr.getAddress().isLoopbackAddress()) {
                        PeerAddress addr = peerMap.get(socketAddr);
                        if (addr == null) {
                            addr = new PeerAddress(networkParams, peerAddr.getAddr(), peerAddr.getPort());
                            peerAddresses.add(addr);
                            peerMap.put(socketAddr, addr);
                            Logger.logDebugMessage("Added TokenExchange peer "
                                    + addr.getAddr().toString() + ":" + addr.getPort());
                        }
                        addr.setTime(peerAddr.getTime());
                        addr.setServices(peerAddr.getServices());
                    }
                });
            }
        }
        return message;
    }

    /**
     * Load the saved peers
     *
     * @throws  IOException     Unable to load saved peers
     */
    int loadPeers() throws IOException {
        synchronized(peerAddresses) {
            //
            // Read the saved peers
            //
            peerAddresses.clear();
            peerMap.clear();
            File peersFile = new File(BitcoinWallet.getWalletDirectory(), "PeerAddresses.dat");
            NetworkParameters networkParams = BitcoinWallet.getNetworkParameters();
            if (peersFile.exists()) {
                try (FileInputStream stream = new FileInputStream(peersFile)) {
                    byte[] buffer = new byte[(int)peersFile.length()];
                    int length = stream.read(buffer);
                    ByteBuffer buf = ByteBuffer.wrap(buffer);
                    buf.order(ByteOrder.LITTLE_ENDIAN);
                    while (buf.position() < length) {
                        int addrLength = buf.getShort();
                        byte[] addrBytes = new byte[addrLength];
                        buf.get(addrBytes);
                        int port = buf.getInt();
                        BigInteger services = BigInteger.valueOf(buf.getLong());
                        long time = buf.getLong();
                        InetAddress addr = InetAddress.getByAddress(addrBytes);
                        PeerAddress peerAddress = new PeerAddress(networkParams, addr, port);
                        peerAddress.setServices(services);
                        peerAddress.setTime(time);
                        peerAddresses.add(peerAddress);
                        peerMap.put(peerAddress.getSocketAddress(), peerAddress);
                    }
                }
                Logger.logInfoMessage(peerAddresses.size() + " TokenExchange peers loaded");
            }
            //
            // Randomize the address order since BitcoinJ starts with the first
            // entry and works through the list sequentially
            //
            Collections.shuffle(peerAddresses);
        }
        return peerAddresses.size();
    }

    /**
     * Store the saved peers
     *
     * @throws  IOException     Unable to store saved peers
     */
    void storePeers() throws IOException {
        synchronized(peerAddresses) {
            //
            // Sort the peer list in descending order by time so we save the
            // most recent peers and drop inactive peers
            //
            Collections.sort(peerAddresses, (o1, o2) ->
                    (o1.getTime() > o2.getTime() ? -1 : (o1.getTime() < o2.getTime() ? 1 : 0)));
            //
            // Write the first 200 peers to the save file
            //
            File peersFile = new File(BitcoinWallet.getWalletDirectory(), "PeerAddresses.dat");
            try (FileOutputStream stream = new FileOutputStream(peersFile)) {
                byte[] buffer = new byte[2 + 16 + 4 + 8 + 8];
                ByteBuffer buf = ByteBuffer.wrap(buffer);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                int count = 0;
                for (PeerAddress peerAddress : peerAddresses) {
                    if (peerAddress.getAddr().isLoopbackAddress()) {
                        continue;
                    }
                    buf.position(0);
                    byte[] addrBytes = peerAddress.getAddr().getAddress();
                    buf.putShort((short)addrBytes.length);
                    buf.put(addrBytes);
                    buf.putInt(peerAddress.getPort());
                    buf.putLong(peerAddress.getServices().longValue());
                    buf.putLong(peerAddress.getTime());
                    stream.write(buffer, 0, buf.position());
                    if (++count >= 200) {
                        break;
                    }
                }
                Logger.logInfoMessage(count + " TokenExchange peers saved");
            }
        }
    }

    /**
     * Provide a list of acceptable peers (PeerDiscovery interface)
     *
     * @param   services        Bit mask of required services
     * @param   timeout         Discovery timeout
     * @param   timeUnit        Time unit
     * @return                  Array of addresses
     */
    @Override
    public InetSocketAddress[] getPeers(long services, long timeout, TimeUnit timeUnit) {
        InetSocketAddress[] peers;
        synchronized(peerAddresses) {
            peers = peerAddresses.stream()
                    .filter((addr) -> (addr.getServices().longValue() & services) == services)
                    .map((addr) -> addr.getSocketAddress())
                    .toArray(InetSocketAddress[]::new);
        }
        return peers;
    }

    /**
     * Stop peer discovery
     */
    @Override
    public void shutdown() {
        // Nothing to do
    }
}
