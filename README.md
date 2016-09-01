TokenExchange
=============

TokenExchange is a NRS add-on that automates the process of exchanging Nxt currency for Bitcoins and issuing Nxt currency when receiving Bitcoins.  TokenExchange Version 1 uses a local Bitcoin Core server to send and receive Bitcoins.  TokenExchange Version 2 uses an integrated SPV wallet to send and receive Bitcoins and does not require a local Bitcoin Core server.  The databases are not compatible between Version 1 and Version 2.

TokenExchange watches for transfer transactions of the specified currency.  If the transfer is to the redemption Nxt account, a Bitcoin transaction will be initiated to send the equivalent amount of Bitcoins to the Bitcoin address that was specified as a message attached to the transfer transaction.  The attached message must be a plain or an encrypted prunable message.

TokenExchange watches for Bitcoins sent to a Bitcoin address that has been associated with a Nxt account.  The Bitcoin transaction must be P2PKH (pay to public key hash).  A Nxt transaction will be initiated to send the equivalent amount of currency units to the associated Nxt account.

The TokenExchange Bitcoin wallet will need to be funded with Bitcoins in order to process token redemptions.  A Bitcoin address is automatically assigned to the NXT redemption account and can be displayed using the TokenExchange GetStatus API.  You can send Bitcoins to this address whenever the Bitcoin wallet needs to be funded.  The TokenExchange GetBalance API request will return the current wallet balance.  You can use the TokenExchange SendBitcoins API to send Bitcoins to an external Bitcoin address if you want to reduce the current wallet balance.

The token-exchange.properties configuration file controls the operation of TokenExchange.  The configuration file contains the following fields:    

- exchangeRate=nnn.nnn    
    This specifies the initial value of a single currency unit in Bitcoins.  For example, a value of 0.001 indicates that 1 currency unit is equal to 0.001 Bitcoins.  The exchange rate can be changed using the TokenExchange SetExchangeRate API.  A maximum of 8 decimal places can be specified.
 
- currency=xxxxx    
    This specifies the 3-5 character Nxt currency code that will be used for the token exchange.

- secretPhrase=secret-phrase
    This specifies the NXT account used to issue currency and process redemption requests.
    
- nxtConfirmations=n    
    This specifies the number of blocks following the block containing a Nxt transaction before the transaction will be processed.
    
- bitcoinConfirmations=n    
    This specifies the number of blocks following the block containing a Bitcoin transaction before the transaction will be processed.
    
- bitcoinTxFee=nn.nn
    This specifies the Bitcoin transaction fee in BTC/KB.  A send request with one input and two outputs is 226 bytes.
    
- bitcoinServer=host:port    
    This specifies the Bitcoin server that will be used by the integrated SPV wallet.  A random server will be selected if this field is not specified.


TokenExchange API
=================

TokenExchange provides an NRS API under the ADDONS tag with 'requestType=tokenExchange'.  The NRS test page (http://localhost:7876/test) can be used to issue requests or an application can issue its own HTTP requests (http://localhost:7876/nxt?requestType=tokenExchange&function=name&adminPassword=administrator-password).

The following functions are available:
  
  - DeleteToken    
    Delete an entry in the token exchange database.  Specify 'function=deleteToken&id=string' in the HTTP request.
  
  - GetAccounts    
    List bitcoin addresses that are associated with NXT users. Specify 'function=getAccounts&account=n&address=s' in the HTTP request.  Specify 'account' to return the bitcoin address associated with that NXT account or specify 'address' to return the NXT account associated with that bitcoin address.  All addresses are returned if neither parameter is specified.

  - GetStatus    
    Get the current TokenExchange status.  Specify 'function=getStatus' in the HTTP request.
  
  - GetTokens    
    List currency tokens that have been redeemed.  Specify 'function=getTokens&height=n&includeExchanged=true/false' in the HTTP request.  This will return all tokens redeemed after the specified Nxt block chain height.  The height defaults to 0 if it is not specified.  The 'includeExchanged' parameter is 'true' to return exchanged tokens in addition to tokens that have not been exchanged.  Only pending tokens are returned if 'false' is specified or the parameter is omitted.
  
  - GetTransactions    
    List transactions received by the Bitcoin wallet for addresses associated with NXT accounts.  Specify 'function=getTransactions&address=s&includeExchanged=true/false' in the HTTP request.  Specify the 'address' parameter to limit the list to transactions for that address.  Otherwise, all transactions are returned.  Specify the 'includeExchanged' parameter to return transactions that have been processed as well as pending transactions.  Otherwise, only pending transactions are returned.
  
  - RequestAddress    
    Get the bitcoin address associated with a Nxt account.  Specify  'function=requestAddress&account=nxt-address&publicKey=hex-string' in the HTTP request.  Bitcoins sent to this address will cause tokens to be issued to the associated Nxt account.  The public key is optional but should be specified for a new Nxt account to increase the security of the account.  A new address will be generated if the Nxt account does not already have an address.
  
  - Resume    
    Resume sending bitcoins for redeemed tokens and issuing tokens for received bitcoins.  Specify 'function=resume' in the HTTP request.  Pending requests will be processed and normal processing will resume.
  
  - SendBitcoins    
    Send Bitcoins from the SPV wallet.  Specify 'function=sendBitcoins&address=xxxxx&amount=nn.nn' in the HTTP request.
  
  - SetExchangeRate     
    Set the token exchange rate.  Specify 'function=setExchangeRate&rate=n.nnn' in the HTTP request.  The initial exchange rate is specified by 'exchangeRate' in token-exchange.properties when the TokenExchange database is created.  The rate set by SetExchangeRate will be used until a new rate is specified and will persist across server restarts.
    
  - Suspend    
    Stop sending bitcoins for redeemed tokens and issuing tokens for received bitcoins.  Specify 'function=suspend' in the HTTP request.  Token redemption requests and bitcoin deposits will still be added to the database but the requests will not be processed until sending is resumed or the NRS server is restarted.


Installation
============

- Extract the files to the Nxt installation directory    
    - cd nxt    
    - tar xf TokenExchange-v.r.m.tar.gz

- Check for duplicates in nxt/addons/lib.  A duplicate is a jar file with the same name as another file but with a different v.r.m.  When duplicates are found, you should keep the latest one and delete the earlier one.  If TokenExchange is the only NRS addon installed, you can just erase all of the files in nxt/addons/lib before unpacking the tar file.

- Copy token-exchange.properties to the 'conf' subdirectory of the NRS application data directory.  Fill in the configuration parameters for your installation.  Read access should be restricted to the NRS application.

- Add 'addons/lib/*' to the java classpath when starting NRS (if not already there)    
    - Linux and Mac: -cp classes:lib/*:conf:addons/classes:addons/lib/*    
    - Windows: -cp classes;lib\*conf;addons\classes;addons\lib\*    

- Add 'nxt.addOns=org.ScripterRon.TokenExchange.TokenAddon' to nxt.properties. If you have multiple addons, separate each addon name by a semicolon.  Create the nxt.properties file in <NRS-application-data>/conf if you don't have one yet.

- Update nxt/nxt.policy and nxt/nxtdesktop.policy and add the following permission:    
    - permission java.lang.RuntimePermission "loadLibrary.secp256k1";    
    - Sample nxt.policy and nxtdesktop.policy files are in the TokenExchange directory.

- You can enable logging for the Bitcoin wallet by adding 'org.bitcoinj.level=INFO' to <NRS-application-directory>/conf/logging.properties.
    
    
Build
=====

I use the Netbeans IDE but any build environment with Maven and the Java compiler available should work.  The documentation is generated from the source code using javadoc.

Here are the steps for a manual build.  You will need to install Maven 3 and Java SE Development Kit 8 if you don't already have them.

  - Create the executable: mvn clean package    
  - [Optional] Create the documentation: mvn javadoc:javadoc    
