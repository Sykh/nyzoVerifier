package co.nyzo.verifier;

import co.nyzo.verifier.util.PrintUtil;
import co.nyzo.verifier.util.SignatureUtil;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Transaction implements MessageObject {

    private enum SignatureState {
        Undetermined,
        Valid,
        Invalid
    }

    // We want this to be a functioning monetary system. The maximum number of coins is 100 million. The fraction used
    // for dividing coins is 1 million (all transactions must be a whole-number multiple of 1/1000000 coins).

    // If we have a coin value of $1 = ∩1, then the transaction increment is one-ten-thousandth of a cent, and the
    // market cap is $100 million. If we have a coin value of $100,000, then the transaction increment is $0.10,
    // and the market cap is $10 trillion.

    public static final long nyzosInSystem = 100000000L;
    public static final long micronyzoMultiplierRatio = 1000000L;
    public static final long micronyzosInSystem = nyzosInSystem * micronyzoMultiplierRatio;
    public static final long maximumCycleTransactionAmount = 100_000L * Transaction.micronyzoMultiplierRatio;

    public static final byte typeCoinGeneration = 0;
    public static final byte typeSeed = 1;
    public static final byte typeStandard = 2;
    public static final byte typeCycle = 3;
    public static final byte typeCycleSignature = 4;

    public static final byte voteYes = (byte) 1;
    public static final byte voteNo = (byte) 0;

    // Included in all transactions.
    private byte type;                   // 1 byte; types enumerated above
    private long timestamp;              // 8 bytes; 64-bit Unix timestamp of the transaction initiation, in milliseconds
    private long amount;                 // 8 bytes; 64-bit amount in micronyzos
    private byte[] receiverIdentifier;   // 32 bytes (256-bit public key of the recipient)

    // Only included in type-1, type-2, and type-3 transactions
    private long previousHashHeight;     // 8 bytes; 64-bit index of the block height of the previous-block hash
    private byte[] previousBlockHash;    // 32 bytes (SHA-256 of a recent block in the chain)
    private byte[] senderIdentifier;     // 32 bytes (256-bit public key of the sender)
    private byte[] senderData;           // up to 32 bytes

    // Included in all types except type-0 (coin generation)
    private byte[] signature;            // 64 bytes (512-bit signature)

    // Only included in type-3 transactions for the v1 blockchain.
    private Map<ByteBuffer, byte[]> cycleSignatures;

    // Only included in type-3 transactions for the balance list for the v2 blockchain.
    private Map<ByteBuffer, Transaction> cycleSignatureTransactions;

    // Only included in type-4 (cycle signature) transactions.
    private byte[] cycleTransactionSignature;
    private byte cycleTransactionVote = voteNo;

    private SignatureState signatureState = SignatureState.Undetermined;

    public static final Comparator<ByteBuffer> identifierComparator = new Comparator<ByteBuffer>() {
        @Override
        public int compare(ByteBuffer buffer1, ByteBuffer buffer2) {
            int result = 0;
            byte[] identifier1 = buffer1.array();
            byte[] identifier2 = buffer2.array();
            for (int i = 0; i < FieldByteSize.identifier && result == 0; i++) {
                int byte1 = identifier1[i] & 0xff;
                int byte2 = identifier2[i] & 0xff;
                if (byte1 < byte2) {
                    result = -1;
                } else if (byte2 < byte1) {
                    result = 1;
                }
            }

            return result;
        }
    };

    private Transaction() {
    }

    public byte getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getAmount() {
        return amount;
    }

    public long getAmountAfterFee() {
        return amount - getFee();
    }

    public byte[] getReceiverIdentifier() {
        return receiverIdentifier;
    }

    public long getPreviousHashHeight() {
        if (previousBlockHash == null) {
            assignPreviousBlockHash();
        }

        return previousHashHeight;
    }

    public byte[] getPreviousBlockHash() {
        if (previousBlockHash == null) {
            assignPreviousBlockHash();
        }

        return previousBlockHash;
    }

    public byte[] getSenderIdentifier() {
        return senderIdentifier;
    }

    public byte[] getSenderData() {
        return senderData;
    }

    public byte[] getSignature() {
        return signature;
    }

    public void addSignatureTransaction(Transaction transaction) {
        cycleSignatureTransactions.put(ByteBuffer.wrap(transaction.getSenderIdentifier()), transaction);
    }

    public void removeOutOfCycleSignatureTransactions() {
        for (ByteBuffer identifier : new HashSet<>(cycleSignatureTransactions.keySet())) {
            if (!BlockManager.verifierInCurrentCycle(identifier)) {
                cycleSignatureTransactions.remove(identifier);
            }
        }
    }

    public byte[] getCycleTransactionSignature() {
        return cycleTransactionSignature;
    }

    public byte getCycleTransactionVote() {
        return cycleTransactionVote;
    }

    public Map<ByteBuffer, Transaction> getCycleSignatureTransactions() {
        return cycleSignatureTransactions;
    }

    private void assignPreviousBlockHash() {

        previousHashHeight = BlockManager.getFrozenEdgeHeight();
        previousBlockHash = BlockManager.frozenBlockForHeight(previousHashHeight).getHash();
    }

    public static Transaction coinGenerationTransaction(long timestamp, long amount, byte[] receiverIdentifier) {

        Transaction transaction = new Transaction();
        transaction.type = typeCoinGeneration;
        transaction.timestamp = timestamp;
        transaction.amount = amount;
        transaction.receiverIdentifier = receiverIdentifier;

        return transaction;
    }

    public static Transaction seedTransaction(long timestamp, long amount, byte[] receiverIdentifier,
                                              long previousHashHeight, byte[] previousBlockHash,
                                              byte[] senderIdentifier, byte[] senderData, byte[] signature) {

        Transaction transaction = new Transaction();
        transaction.type = typeSeed;
        transaction.timestamp = timestamp;
        transaction.amount = amount;
        transaction.receiverIdentifier = Arrays.copyOf(receiverIdentifier, FieldByteSize.identifier);
        transaction.previousHashHeight = previousHashHeight;
        transaction.previousBlockHash = Arrays.copyOf(previousBlockHash, FieldByteSize.hash);
        transaction.senderIdentifier = Arrays.copyOf(senderIdentifier, FieldByteSize.identifier);
        transaction.senderData = Arrays.copyOf(senderData, Math.min(senderData.length, 32));
        transaction.signature = signature;

        return transaction;
    }

    public static Transaction seedTransaction(long timestamp, long amount, byte[] receiverIdentifier,
                                              long previousHashHeight, byte[] previousBlockHash,
                                              byte[] senderData, byte[] signerSeed) {

        Transaction transaction = new Transaction();
        transaction.type = typeSeed;
        transaction.timestamp = timestamp;
        transaction.amount = amount;
        transaction.receiverIdentifier = receiverIdentifier;
        transaction.previousHashHeight = previousHashHeight;
        transaction.previousBlockHash = previousBlockHash;
        transaction.senderIdentifier = KeyUtil.identifierForSeed(signerSeed);
        transaction.senderData = senderData;
        transaction.signature = SignatureUtil.signBytes(transaction.getBytes(true), signerSeed);

        return transaction;
    }

    public static Transaction standardTransaction(long timestamp, long amount, byte[] receiverIdentifier,
                                                  long previousHashHeight, byte[] previousBlockHash,
                                                  byte[] senderIdentifier, byte[] senderData, byte[] signature) {

        Transaction transaction = new Transaction();
        transaction.type = typeStandard;
        transaction.timestamp = timestamp;
        transaction.amount = amount;
        transaction.receiverIdentifier = Arrays.copyOf(receiverIdentifier, FieldByteSize.identifier);
        transaction.previousHashHeight = previousHashHeight;
        transaction.previousBlockHash = Arrays.copyOf(previousBlockHash, FieldByteSize.hash);
        transaction.senderIdentifier = Arrays.copyOf(senderIdentifier, FieldByteSize.identifier);
        transaction.senderData = Arrays.copyOf(senderData, Math.min(senderData.length, 32));
        transaction.signature = signature;

        return transaction;
    }

    public static Transaction standardTransaction(long timestamp, long amount, byte[] receiverIdentifier,
                                                  long previousHashHeight, byte[] previousBlockHash,
                                                  byte[] senderData, byte[] signerSeed) {

        Transaction transaction = new Transaction();
        transaction.type = typeStandard;
        transaction.timestamp = timestamp;
        transaction.amount = amount;
        transaction.receiverIdentifier = receiverIdentifier;
        transaction.previousHashHeight = previousHashHeight;
        transaction.previousBlockHash = previousBlockHash;
        transaction.senderIdentifier = KeyUtil.identifierForSeed(signerSeed);
        transaction.senderData = senderData;
        transaction.signature = SignatureUtil.signBytes(transaction.getBytes(true), signerSeed);

        return transaction;
    }

    public static Transaction cycleTransaction(Transaction cycleTransaction) {

        Transaction transaction = new Transaction();
        transaction.type = typeCycle;
        transaction.timestamp = cycleTransaction.timestamp;
        transaction.amount = cycleTransaction.amount;
        transaction.receiverIdentifier = cycleTransaction.receiverIdentifier;
        transaction.previousHashHeight = cycleTransaction.previousHashHeight;
        transaction.previousBlockHash = cycleTransaction.previousBlockHash;
        transaction.senderIdentifier = cycleTransaction.senderIdentifier;
        transaction.senderData = cycleTransaction.senderData;
        transaction.signature = cycleTransaction.signature;
        transaction.cycleSignatures = new ConcurrentHashMap<>(cycleTransaction.cycleSignatures);
        transaction.cycleSignatureTransactions = new ConcurrentHashMap<>(cycleTransaction.cycleSignatureTransactions);

        return transaction;
    }

    public static Transaction cycleTransaction(long timestamp, long amount, byte[] receiverIdentifier,
                                               long previousHashHeight, byte[] previousBlockHash,
                                               byte[] senderIdentifier, byte[] senderData, byte[] signature,
                                               Map<ByteBuffer, byte[]> cycleSignatures,
                                               Map<ByteBuffer, Transaction> cycleSignatureTransactions) {

        Transaction transaction = new Transaction();
        transaction.type = typeCycle;
        transaction.timestamp = timestamp;
        transaction.amount = amount;
        transaction.receiverIdentifier = receiverIdentifier;
        transaction.previousHashHeight = previousHashHeight;
        transaction.previousBlockHash = previousBlockHash;
        transaction.senderIdentifier = senderIdentifier;
        transaction.senderData = senderData;
        transaction.signature = signature;
        transaction.cycleSignatures = cycleSignatures;
        transaction.cycleSignatureTransactions = cycleSignatureTransactions;

        return transaction;
    }

    public static Transaction cycleTransaction(long timestamp, long amount, byte[] receiverIdentifier,
                                               byte[] senderData, byte[] signerSeed) {

        Transaction transaction = new Transaction();
        transaction.type = typeCycle;
        transaction.timestamp = timestamp;
        transaction.amount = amount;
        transaction.receiverIdentifier = receiverIdentifier;
        transaction.previousHashHeight = 0L;
        transaction.previousBlockHash = BlockManager.frozenBlockForHeight(0L).getHash();
        transaction.senderIdentifier = KeyUtil.identifierForSeed(signerSeed);  // initiator identifier, in this case
        transaction.senderData = senderData;
        transaction.signature = SignatureUtil.signBytes(transaction.getBytes(true), signerSeed);
        transaction.cycleSignatures = new ConcurrentHashMap<>();
        transaction.cycleSignatureTransactions = new ConcurrentHashMap<>();

        return transaction;
    }

    public static Transaction cycleSignatureTransaction(long timestamp, byte[] senderIdentifier,
                                                        byte cycleTransactionVote, byte[] cycleTransactionSignature,
                                                        byte[] signature) {

        Transaction transaction = new Transaction();
        transaction.type = typeCycleSignature;
        transaction.timestamp = timestamp;
        transaction.senderIdentifier = senderIdentifier;
        transaction.cycleTransactionVote = cycleTransactionVote;
        transaction.cycleTransactionSignature = cycleTransactionSignature;
        transaction.signature = signature;

        return transaction;
    }

    public static Transaction cycleSignatureTransaction(long timestamp, byte cycleTransactionVote,
                                                        byte[] cycleTransactionSignature, byte[] signerSeed) {

        Transaction transaction = new Transaction();
        transaction.type = typeCycleSignature;
        transaction.timestamp = timestamp;
        transaction.senderIdentifier = KeyUtil.identifierForSeed(signerSeed);
        transaction.cycleTransactionVote = cycleTransactionVote;
        transaction.cycleTransactionSignature = cycleTransactionSignature;
        transaction.signature = SignatureUtil.signBytes(transaction.getBytes(true), signerSeed);

        return transaction;
    }

    public long getFee() {
        return type == typeCycle || type == typeCycleSignature ? 0 : (getAmount() + 399L) / 400L;
    }

    @Override
    public int getByteSize() {
        return getByteSize(false);
    }

    public int getByteSize(boolean forSigning) {

        // All transactions begin with a type and timestamp.
        int size = FieldByteSize.transactionType +      // type
                FieldByteSize.timestamp;                // timestamp

        if (type == typeCycleSignature) {
            size += FieldByteSize.identifier +          // verifier (signer) identifier
                    FieldByteSize.booleanField +        // yes/no
                    FieldByteSize.signature;            // cycle transaction signature
            if (!forSigning) {
                size += FieldByteSize.signature;        // signature
            }
        } else {
            size += FieldByteSize.transactionAmount +   // amount
                    FieldByteSize.identifier;           // receiver identifier
        }

        if (type == typeSeed || type == typeStandard || type == typeCycle) {

            if (forSigning) {
                size += FieldByteSize.hash;           // previous-block hash for signing
            } else {
                size += FieldByteSize.blockHeight;    // previous-hash height for storage and transmission
            }
            size += FieldByteSize.identifier;         // sender identifier

            if (forSigning) {
                size += FieldByteSize.hash;           // sender data hash for signing
            } else {
                size += 1 + senderData.length +       // length specifier + sender data
                        FieldByteSize.signature;      // transaction signature

                if (type == typeCycle) {
                    // These are stored differently in the v1 and v2 blockchains. The cycleSignatures field is used for
                    // the v1 blockchain, and the cycleSignatureTransactions field is used for the v2 blockchain.
                    if (cycleSignatures != null && !cycleSignatures.isEmpty()) {
                        // The v1 blockchain stores identifier and signature for each.
                        size += FieldByteSize.unnamedInteger + cycleSignatures.size() * (FieldByteSize.identifier +
                                FieldByteSize.signature);
                    } else {
                        // The v2 blockchain stores timestamp, identifier, vote, and signature for each.
                        size += FieldByteSize.unnamedInteger + cycleSignatureTransactions.size() *
                                (FieldByteSize.timestamp + FieldByteSize.identifier + FieldByteSize.booleanField +
                                        FieldByteSize.signature);

                    }
                }
            }
        }

        return size;
    }

    @Override
    public byte[] getBytes() {

        return getBytes(false);
    }

    public byte[] getBytes(boolean forSigning) {

        byte[] array = new byte[getByteSize(forSigning)];

        ByteBuffer buffer = ByteBuffer.wrap(array);
        buffer.put(type);
        buffer.putLong(timestamp);

        if (type == typeCoinGeneration || type == typeSeed || type == typeStandard || type == typeCycle) {
            buffer.putLong(amount);
            buffer.put(receiverIdentifier);
        } else if (type == typeCycleSignature) {
            buffer.put(senderIdentifier);
            buffer.put(cycleTransactionVote);
            buffer.put(cycleTransactionSignature);
            if (!forSigning) {
                buffer.put(signature);
            }
        }

        if (type == typeSeed || type == typeStandard || type == typeCycle) {

            if (forSigning) {
                buffer.put(getPreviousBlockHash());      // may be null initially and need to be determined
            } else {
                buffer.putLong(getPreviousHashHeight()); // may be unspecified initially and need to be determined
            }
            buffer.put(senderIdentifier);

            // For serializing, we use the raw sender data with a length specifier. For signing, we use the double-
            // SHA-256 of the user data. This will allow us to remove inappropriate or illegal metadata from the
            // blockchain at a later date by replacing it with its double-SHA-256 without compromising the signature
            // integrity.
            if (forSigning) {
                buffer.put(HashUtil.doubleSHA256(senderData));
            } else {
                buffer.put((byte) senderData.length);
                buffer.put(senderData);
            }

            if (!forSigning) {
                buffer.put(signature);

                // For cycle transactions, order the signatures by verifier identifier. In the v1 blockchain, the
                // cycleSignatures field is used. In the v2 blockchain, the cycleSignatureTransactions field is used.
                if (type == typeCycle) {
                    if (cycleSignatures != null && !cycleSignatures.isEmpty()) {
                        List<ByteBuffer> signatureIdentifiers = new ArrayList<>(cycleSignatures.keySet());
                        signatureIdentifiers.sort(identifierComparator);

                        buffer.putInt(cycleSignatures.size());
                        for (ByteBuffer identifier : signatureIdentifiers) {
                            buffer.put(identifier.array());
                            buffer.put(cycleSignatures.get(identifier));
                        }
                    } else {
                        List<ByteBuffer> signatureIdentifiers = new ArrayList<>(cycleSignatureTransactions.keySet());
                        signatureIdentifiers.sort(identifierComparator);

                        buffer.putInt(cycleSignatureTransactions.size());
                        for (ByteBuffer identifier : signatureIdentifiers) {
                            Transaction signatureTransaction = cycleSignatureTransactions.get(identifier);
                            buffer.putLong(signatureTransaction.timestamp);
                            buffer.put(signatureTransaction.senderIdentifier);
                            buffer.put(signatureTransaction.cycleTransactionVote);
                            buffer.put(signatureTransaction.signature);
                        }
                    }
                }
            }
        }

        return array;
    }

    public static Transaction fromByteBuffer(ByteBuffer buffer) {

        return fromByteBuffer(buffer, 0, new byte[FieldByteSize.hash], false);
    }

    public static Transaction fromByteBuffer(ByteBuffer buffer, long transactionHeight, byte[] previousHashInChain,
                                             boolean balanceListCycleTransaction) {

        // All transactions start with type and timestamp.
        byte type = buffer.get();
        long timestamp = buffer.getLong();

        // Build the transaction object, getting the appropriate fields for each type.
        Transaction transaction = null;
        if (type == typeCoinGeneration) {
            long amount = buffer.getLong();
            byte[] receiverIdentifier = Message.getByteArray(buffer, FieldByteSize.identifier);
            transaction = coinGenerationTransaction(timestamp, amount, receiverIdentifier);
        } else if (type == typeSeed || type == typeStandard || type == typeCycle) {
            long amount = buffer.getLong();
            byte[] receiverIdentifier = Message.getByteArray(buffer, FieldByteSize.identifier);
            long previousHashHeight = buffer.getLong();
            Block previousHashBlock = previousHashBlockForHeight(previousHashHeight, transactionHeight,
                    previousHashInChain);
            byte[] previousBlockHash = previousHashBlock == null ? new byte[FieldByteSize.hash] :
                    previousHashBlock.getHash();
            byte[] senderIdentifier = Message.getByteArray(buffer, FieldByteSize.identifier);

            int senderDataLength = Math.min(buffer.get(), 32);
            byte[] senderData = Message.getByteArray(buffer, senderDataLength);

            byte[] signature = Message.getByteArray(buffer, FieldByteSize.signature);
            if (type == typeSeed) {
                transaction = seedTransaction(timestamp, amount, receiverIdentifier, previousHashHeight,
                        previousBlockHash, senderIdentifier, senderData, signature);
            } else if (type == typeStandard) {
                transaction = standardTransaction(timestamp, amount, receiverIdentifier, previousHashHeight,
                        previousBlockHash, senderIdentifier, senderData, signature);
            } else {  // type == typeCycle

                Map<ByteBuffer, byte[]> cycleSignatures = new HashMap<>();
                Map<ByteBuffer, Transaction> cycleSignatureTransactions = new HashMap<>();
                int numberOfCycleSignatures = buffer.getInt();

                if (!balanceListCycleTransaction) {
                    // If not explicitly marked as a balance list cycle transaction, read the signatures as simple
                    // identifier/signature pairs.
                    for (int i = 0; i < numberOfCycleSignatures; i++) {
                        ByteBuffer identifier = ByteBuffer.wrap(Message.getByteArray(buffer, FieldByteSize.identifier));
                        byte[] cycleSignature = Message.getByteArray(buffer, FieldByteSize.signature);
                        if (!ByteUtil.arraysAreEqual(identifier.array(), senderIdentifier)) {
                            cycleSignatures.put(identifier, cycleSignature);
                        }
                    }
                } else {
                    // When the explicitly marked as a balance list cycle transaction, read the additional fields for
                    // cycle transaction signatures.
                    for (int i = 0; i < numberOfCycleSignatures; i++) {
                        long childTimestamp = buffer.getLong();
                        byte[] childSenderIdentifier = Message.getByteArray(buffer, FieldByteSize.identifier);
                        byte childCycleTransactionVote = buffer.get() == 1 ? voteYes : voteNo;
                        byte[] childSignature = Message.getByteArray(buffer, FieldByteSize.signature);
                        cycleSignatureTransactions.put(ByteBuffer.wrap(childSenderIdentifier),
                                cycleSignatureTransaction(childTimestamp, childSenderIdentifier,
                                        childCycleTransactionVote, signature, childSignature));
                    }
                }
                transaction = cycleTransaction(timestamp, amount, receiverIdentifier, previousHashHeight,
                        previousBlockHash, senderIdentifier, senderData, signature, cycleSignatures,
                        cycleSignatureTransactions);
            }
        } else if (type == typeCycleSignature) {
            byte[] senderIdentifier = Message.getByteArray(buffer, FieldByteSize.identifier);
            byte cycleTransactionVote = buffer.get() == 1 ? voteYes : voteNo;
            byte[] cycleTransactionSignature = Message.getByteArray(buffer, FieldByteSize.signature);
            byte[] signature = Message.getByteArray(buffer, FieldByteSize.signature);
            transaction = cycleSignatureTransaction(timestamp, senderIdentifier, cycleTransactionVote,
                    cycleTransactionSignature, signature);
        } else {
            System.err.println("Unknown type: " + type);
        }

        return transaction;
    }

    private static Block previousHashBlockForHeight(long hashHeight, long transactionHeight,
                                                    byte[] previousHashInChain) {

        // First, try to get a frozen block. If one is not available, and the height referenced is past the frozen edge,
        // try to get a block on the branch leading to this transaction.
        Block block = BlockManager.frozenBlockForHeight(hashHeight);
        if (block == null && hashHeight > BlockManager.getFrozenEdgeHeight()) {
            Block previousBlock = UnfrozenBlockManager.unverifiedBlockAtHeight(transactionHeight - 1,
                    previousHashInChain);
            while (previousBlock != null && previousBlock.getBlockHeight() > hashHeight) {
                previousBlock = UnfrozenBlockManager.unverifiedBlockAtHeight(previousBlock.getBlockHeight() - 1,
                        previousBlock.getPreviousBlockHash());
            }

            if (previousBlock != null && previousBlock.getBlockHeight() == hashHeight) {
                block = previousBlock;
            }
        }

        return block;
    }

    public boolean performInitialValidation(StringBuilder validationError, StringBuilder validationWarning) {

        // As its name indicates, this method performs initial validation of transactions so users know when a
        // transaction will not be added to the transaction pool. Passing of this validation only adds a transaction
        // to the pool and does not guarantee that the transaction will be incorporated into a black.

        // Additionally, to provide good feedback to users, we warn about transactions that will be added to the pool
        // but appear to have issues that may prevent their incorporation into blocks.

        boolean valid = true;

        try {

            // Check the type (we only validate transactions past block zero, so 1, 2, 3, and 4 are the only valid types
            // right now).
            if (type != typeSeed && type != typeStandard && type != typeCycle && type != typeCycleSignature) {
                valid = false;
                validationError.append("Only seed (type 1), standard (type 2), cycle (type 3), and cycle-signature ")
                        .append("(type 4) transactions are valid after block 0. ");
            }

            // For all types except cycle signature, check that the previous-block hash is contained in the chain.
            Block previousHashBlock = BlockManager.frozenBlockForHeight(previousHashHeight);
            if (type != typeCycleSignature && valid && (previousHashBlock == null ||
                    !ByteUtil.arraysAreEqual(previousHashBlock.getHash(), previousBlockHash))) {
                valid = false;
                validationError.append("The previous-block hash is invalid. ");
            }

            // Check the signature.
            if (valid) {
                if (!signatureIsValid()) {
                    valid = false;
                    validationError.append("The signature is not valid. ");
                }
            }

            // Check that the amount is at least µ1.
            if (valid && amount < 1 && type != typeCycleSignature) {
                valid = false;
                validationError.append("The transaction must be at least µ1. ");
            }

            // Check that the sender and receiver are the same address for seed transactions and different addresses
            // for standard and cycle transactions.
            if (valid && type == typeSeed) {
                if (!ByteUtil.arraysAreEqual(getSenderIdentifier(), getReceiverIdentifier())) {
                    valid = false;
                    validationError.append("The sender and receiver must be the same for seed transactions. ");
                }
            } else if (valid && (type == typeStandard || type == typeCycle)) {
                if (ByteUtil.arraysAreEqual(getSenderIdentifier(), getReceiverIdentifier())) {
                    valid = false;
                    validationError.append("The sender and receiver must be different for standard and cycle ")
                            .append("transactions. ");
                }
            }

            // Check the height. If the block has already been frozen, reject the transaction. If the block is already
            // open for processing, produce a warning.
            if (valid) {
                long blockHeight = BlockManager.heightForTimestamp(timestamp);
                long openEdgeHeight = BlockManager.openEdgeHeight(false);
                if (blockHeight < openEdgeHeight) {
                    if (blockHeight <= BlockManager.getFrozenEdgeHeight()) {
                        valid = false;
                        validationError.append("This transaction's block has already been frozen. ");
                    } else {
                        validationWarning.append("This transaction's block is already open for processing, so this ")
                                .append("transaction may be received too late to be included. ");
                    }
                }
            }

            // Produce a warning for transactions that appear to be balance-list spam.
            if (valid) {
                BalanceList balanceList = BalanceListManager.getFrozenEdgeList();
                if (balanceList != null) {
                    Map<ByteBuffer, Long> balanceMap = BalanceManager.makeBalanceMap(balanceList);
                    if (BalanceManager.transactionSpamsBalanceList(balanceMap, this,
                            Collections.singletonList(this))) {

                        if (getAmount() < BalanceManager.minimumPreferredBalance) {
                            validationWarning.append("This transaction appears to create a new account with a ")
                                    .append("balance less than ")
                                    .append(PrintUtil.printAmount(BalanceManager.minimumPreferredBalance))
                                    .append(", so it may not be approved. ");
                        } else {
                            validationWarning.append("This transaction appears to leave a balance greater than ")
                                    .append("zero but less than ")
                                    .append(PrintUtil.printAmount(BalanceManager.minimumPreferredBalance))
                                    .append(" in the sender account, so it may not be approved. ");
                        }
                    }
                }
            }

            // Trim trailing spaces from the error and warning.
            if (validationError.length() > 0) {
                validationError.deleteCharAt(validationError.length() - 1);
            }
            if (validationWarning.length() > 0) {
                validationWarning.deleteCharAt(validationWarning.length() - 1);
            }

        } catch (Exception ignored) {
            valid = false;
            validationError.append("An unspecified validation error occurred. This typically indicates a malformed " +
                    "transaction. ");
        }

        return valid;
    }

    public boolean signatureIsValid() {

        if (signatureState == SignatureState.Undetermined && (type == typeSeed || type == typeStandard ||
                type == typeCycle || type == typeCycleSignature)) {
            signatureState = SignatureUtil.signatureIsValid(signature, getBytes(true), senderIdentifier) ?
                    SignatureState.Valid : SignatureState.Invalid;
        }

        return signatureState == SignatureState.Valid;
    }

    public boolean signatureIsValid(byte[] identifier, byte[] signature) {
        return SignatureUtil.signatureIsValid(signature, getBytes(true), identifier);
    }

    public boolean previousHashIsValid() {
        return true;
    }

    public boolean addSignature(byte[] identifier, byte[] signature) {

        // If this is a cycle transaction and the signature is valid and from an in-cycle verifier, add the signature to
        // the map.
        boolean addedSignature = false;
        if (type == typeCycle && SignatureUtil.signatureIsValid(signature, getBytes(true), identifier) &&
                !ByteUtil.arraysAreEqual(senderIdentifier, identifier) &&
                BlockManager.verifierInCurrentCycle(ByteBuffer.wrap(identifier))) {
            addedSignature = true;
            cycleSignatures.put(ByteBuffer.wrap(identifier), signature);
        }

        return addedSignature;
    }

    public void filterCycleSignatures() {

        // Remove all invalid and out-of-cycle signatures. Also, remove the initiator signature. These are also filtered
        // in the addSignature method, but cycle changes and alternate loading could cause inappropriate signatures to
        // be in this set.
        byte[] bytesForSigning = getBytes(true);
        Set<ByteBuffer> currentCycle = BlockManager.verifiersInCurrentCycleSet();
        for (ByteBuffer identifier : new HashSet<>(cycleSignatures.keySet())) {
            byte[] signature = cycleSignatures.get(identifier);
            if (!SignatureUtil.signatureIsValid(signature, bytesForSigning, identifier.array()) ||
                    !currentCycle.contains(identifier) ||
                    ByteUtil.arraysAreEqual(identifier.array(), senderIdentifier)) {
                cycleSignatures.remove(identifier);
            }
        }
    }

    public Map<ByteBuffer, byte[]> getCycleSignatures() {
        return cycleSignatures;
    }

    @Override
    public String toString() {
        return "[Transaction:type=" + getType() + ",timestamp=" + getTimestamp() + ",sender=" +
                PrintUtil.compactPrintByteArray(getSenderIdentifier()) + ",receiver=" +
                PrintUtil.compactPrintByteArray(getReceiverIdentifier()) + ",amount=" +
                PrintUtil.printAmount(getAmount()) + "]";
    }
}
