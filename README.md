TokenExchange
=============

TokenExchange is a NRS add-on that automates the process of exchanging Nxt currency for bitcoins and issuing Nxt currency when receiving bitcoins.  

The add-on watches for transfer transactions of the specified currency.  If the transfer is to the redemption Nxt address, a Bitcoin transaction will be initiated to send the equivalent amount of bitcoins to the bitcoin address that was specified as a message attached to the transfer transaction.  The attached message must be a prunable plain message.

The bitcoind 'blocknotify' and 'walletnotify' routines must be defined in bitcoin.conf.  The 'blocknotify' routine must issue a TokenExchange 'blockReceived' request.  The 'walletnotify' routine must issue a TokenExchange 'transactionReceived' request.  The bitcoin address must have been associated with a Nxt account using a TokenExchange 'getAddress' request.  These addresses are defined in the bitcoind wallet as well as in the TokenExchange database, so changing either the bitcoind wallet or the NRS server will invalidate the association.  The bitcoin transactions must be P2PKH with a single payee output and an optional change output.

The token-exchange.properties configuration file controls the operation of the TokenExchange add-on.  The access controls for this file should restrict access since it contains passwords used to access bitcoind and send bitcoins.  The configuration file contains the following fields:    

- exchangeRate=nnn.nnn    
    This specifies the value of a single currency unit in bitcoins.  For example, a value of 0.001 indicates that 1 currency unit is equal to 0.001 bitcoins.
 
- currency=xxxxx    
    This specifies the 3-5 character Nxt currency code that will be used for the token exchange.

- secretPhrase=secret-phrase
    This specifies the NXT account used to issue token currency and process redemption requests.
    
- confirmations=n    
    This specifies the number of Nxt confirmations before the bitcoins will be sent to the user.
    
- bitcoindAddress=host-name:port    
    This specifies the host name and port of the bitcoind server that will be used to send bitcoins to the user.  Since bitcoind no longer supports SSL connections, the bitcoind server should be running on the same system as the NRS server to avoid security leaks.
    
- bitcoindUser=user-name   
    This specifies the user for RPC requests to the bitcoind server
    
- bitcoindPassword=password    
    This specifies the password for RPC requests to the bitcoind server
    
- bitcoindTxFee=n.nnnn    
    This specifies the bitcoind transaction fee as BTC/KB.  A SEND transaction with one input and two output is around 226 bytes.  If the bitcoindTxFee=0.0004, then the transaction fee calculated by the current version of bitcoind would be 0.0004 * .226 = 0.0000904.  Note that older versions of bitcoind would round the size up to 1KB, resulting in a transaction fee of 0.0004.
    
- bitcoindWalletPassphrase=passphrase    
    This specifies the passphrase needed to unlock an encrypted bitcoind wallet.  It should be omitted if the wallet is not encrypted.


TokenExchange API
=================

TokenExchange provides an NRS API under the ADDONS tag with 'requestType=tokenExchange'.  The NRS test page (http://localhost:7876/test) can be used to issue requests or an application can issue its own HTTP requests (http://localhost:7876/nxt?requestType=tokenExchange&function=name).  The NRS server administrator password is required for these requests (adminPassword=password).

The following functions are available:

  - Get the current status of the TokenExchange add-on.  Specify 'function=getStatus' in the HTTP request.
  - List currency tokens that have been redeemed.  Specify 'function=getTokens&height=n&includeExchanged=true/false' in the HTTP request.  This will return all tokens redeemed after the specified height.  The height defaults to 0 if it is not specified.  The 'includeExchanged' parameter is 'true' to return exchanged tokens in addition to tokens that have not been exchanged.  Only pending tokens are returned if 'false' is specified or the parameter is omitted.
  - Delete an entry in the token exchange database.  Specify 'function=deleteToken&id=string' in the HTTP request.
  - Get the bitcoin address associated with a Nxt account.  Specify 'function=requestAddress&account=nxt-address&publicKey=hex-string' in the HTTP request.  Bitcoins sent to this address will then result in tokens be issued to the associated Nxt account.  The public key is optional but should be specified for a new account to increase the security of the account.  A new address will be generated if the Nxt account does not already have an address.
  - List bitcoin addresses that are associated with NXT users. Specify 'function=getAccounts&account=n&address=s' in the HTTP request.  Specify 'account' to return the bitcoin address associated with that NXT account or specify 'address' to return the NXT account associated with that bitcoin address.  All addresses are returned if neither parameter is specified.
  - List transactions received by the Bitcoin wallet for addresses associated with NXT accounts.  Specify 'function=getTransactions&address=s&includeExchanged=true/false' in the HTTP request.  Specify the 'address' parameter to limit the list to transactions for that address.  Otherwise, all transactions are returned.  Specify the 'includeExchanged' parameter to return transactions that have been processed as well as pending transactions.
  - Notification that a new bitcoin block has been received.  Specify 'function=blockReceived&id=id-string' in the HTTP request.  This request is issued by the bitcoind 'blocknotify' routine and is not intended for users.
  - Notification that a new wallet transaction has been received.  Specify 'function=transactionReceived&id=id-string' in the HTTP request.  This request is issued by the bitcoind 'walletnotity' routine and is not intended for users.
  - Stop sending bitcoins for redeemed tokens and issuing tokens for received bitcoins.  Specify 'function=suspend' in the HTTP request.  Redeemed tokens and bitcoin deposits will still be added to the database but the requests will not be processed until sending is resumed or the NRS server is restarted.
  - Resume sending bitcoins for redeemed tokens and issuing tokens for received bitcoins.  Specify 'function=resume' in the HTTP request.  Pending requests will be processed and normal processing will resume.

    
Installation
============

- Extract the files to the Nxt installation directory    

- Copy token-exchange.properties to the 'conf' subdirectory of the NRS application data directory.  Fill in the configuration parameters for your installation.  Read access should be restricted to the NRS application.

- Add 'addons/lib/*' to the java classpath when starting NRS    
    - Linux and Mac: -cp classes:lib/*:conf:addons/classes:addons/lib/*    
    - Windows: -cp classes;lib\*conf;addons\classes;addons\lib\*    

- Add 'nxt.addOns=org.ScripterRon.TokenExchange.TokenAddon' to nxt.properties. If you have multiple add-ons, separate each add-on name by a semi-colon

- Add 'blocknotify' and 'walletnotify' to bitcoin.conf.  Sample shell scripts are included in the TokenExchange directory.  Copy them to a directory of your choice and set the administrator password for your NRS server.  Read access should be restricted to the bitcoind application.  For example:    
    - blocknotify=/path-to-script/blocknotify.sh %s    
    - walletnotify=/path-to-script/walletnotify.sh %s    

    
Build
=====

I use the Netbeans IDE but any build environment with Maven and the Java compiler available should work.  The documentation is generated from the source code using javadoc.

Here are the steps for a manual build.  You will need to install Maven 3 and Java SE Development Kit 8 if you don't already have them.

  - Create the executable: mvn clean package    
  - [Optional] Create the documentation: mvn javadoc:javadoc    
