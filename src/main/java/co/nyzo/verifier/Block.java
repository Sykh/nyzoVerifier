package co.nyzo.verifier;

import co.nyzo.verifier.util.*;

import java.nio.ByteBuffer;
import java.util.*;

public class Block implements MessageObject {

    public enum ContinuityState {
        Undetermined,
        Discontinuous,
        Continuous
    }

    private enum SignatureState {
        Undetermined,
        Valid,
        Invalid
    }

    private static final byte[] genesisVerifierTestnet = ByteUtil.byteArrayFromHexString("94d3d58bdcf294bd-" +
            "a4737c8c5a16fa2c-a2b5ac2ceafd75e7-fd725acda40b37d6", FieldByteSize.identifier);
    private static final byte[] genesisVerifierProduction = ByteUtil.byteArrayFromHexString("64afc20a4a4097e8-" +
            "494239f2e7d1b1db-de59a9b157453138-f4716b72a0424fef", FieldByteSize.identifier);
    public static final byte[] genesisVerifier = TestnetUtil.testnet ? genesisVerifierTestnet :
            genesisVerifierProduction;

    public static final byte[] genesisBlockHash = HashUtil.doubleSHA256(new byte[0]);

    public static final long blockDuration = 7000L;
    public static final long minimumVerificationInterval = 1500L;

    // These are the minimum and maximum blockchain versions that this software knows how to process. The version is
    // strictly enforced. Attempting to process an unknown version would seldom lead to correct results and would open
    // possibilities for manipulation.
    private static final int minimumBlockchainVersion = 0;
    public static final int maximumBlockchainVersion = 1;

    private int blockchainVersion;                 // 2 bytes; 16-bit integer of the blockchain version
    private long height;                           // 6 bytes; 48-bit integer block height from the Genesis block,
                                                   // which has a height of 0
    private byte[] previousBlockHash;              // 32 bytes (this is the double-SHA-256 of the previous block
                                                   // signature)
    private long startTimestamp;                   // 8 bytes; 64-bit Unix timestamp of the start of the block, in
                                                   // milliseconds
    private long verificationTimestamp;            // 8 bytes; 64-bit Unix timestamp of when the verifier creates the
                                                   // block, in milliseconds
    private List<Transaction> transactions;        // 4 bytes for number + variable
    private byte[] balanceListHash;                // 32 bytes (this is the double-SHA-256 of the account balance list)
    private byte[] verifierIdentifier;             // 32 bytes
    private byte[] verifierSignature;              // 64 bytes

    private ContinuityState continuityState = ContinuityState.Undetermined;
    private SignatureState signatureState = SignatureState.Undetermined;
    private CycleInformation cycleInformation = null;

    public Block(int blockchainVersion, long height, byte[] previousBlockHash, long startTimestamp,
                 List<Transaction> transactions, byte[] balanceListHash) {

        this.blockchainVersion = limitBlockchainVersion(blockchainVersion);
        this.height = height;
        this.previousBlockHash = previousBlockHash;
        this.startTimestamp = startTimestamp;
        this.verificationTimestamp = System.currentTimeMillis();
        this.transactions = new ArrayList<>(transactions);
        this.balanceListHash = balanceListHash;

        try {
            this.verifierIdentifier = Verifier.getIdentifier();
            this.verifierSignature = Verifier.sign(getBytes(false));
        } catch (Exception e) {
            this.verifierIdentifier = new byte[32];
            this.verifierSignature = new byte[64];
        }
    }

    public Block(int blockchainVersion, long height, byte[] previousBlockHash, long startTimestamp,
                 List<Transaction> transactions, byte[] balanceListHash, byte[] verifierSeed) {

        this.blockchainVersion = limitBlockchainVersion(blockchainVersion);
        this.height = height;
        this.previousBlockHash = previousBlockHash;
        this.startTimestamp = startTimestamp;
        this.verificationTimestamp = System.currentTimeMillis();
        this.transactions = new ArrayList<>(transactions);
        this.balanceListHash = balanceListHash;
        this.verifierIdentifier = KeyUtil.identifierForSeed(verifierSeed);
        this.verifierSignature = SignatureUtil.signBytes(getBytes(false), verifierSeed);
    }

    public Block(int blockchainVersion, long height, byte[] previousBlockHash, long startTimestamp,
                 long verificationTimestamp, List<Transaction> transactions, byte[] balanceListHash,
                 byte[] verifierIdentifier, byte[] verifierSignature, boolean validateTransactions) {

        this.blockchainVersion = limitBlockchainVersion(blockchainVersion);
        this.height = height;
        this.previousBlockHash = previousBlockHash;
        this.startTimestamp = startTimestamp;
        this.verificationTimestamp = verificationTimestamp;
        this.transactions = validateTransactions ? validTransactions(transactions, startTimestamp) : transactions;
        this.balanceListHash = balanceListHash;
        this.verifierIdentifier = verifierIdentifier;
        this.verifierSignature = verifierSignature;
    }

    public static int limitBlockchainVersion(int blockchainVersion) {
        return Math.max(minimumBlockchainVersion, Math.min(maximumBlockchainVersion, blockchainVersion));
    }

    private static List<Transaction> validTransactions(List<Transaction> transactions, long startTimestamp) {

        List<Transaction> validTransactions = new ArrayList<>();
        Set<ByteBuffer> signatures = new HashSet<>();
        long endTimestamp = startTimestamp + Block.blockDuration;
        for (Transaction transaction : transactions) {
            if ((transaction.getType() == Transaction.typeCoinGeneration || transaction.signatureIsValid()) &&
                    transaction.getTimestamp() >= startTimestamp && transaction.getTimestamp() < endTimestamp) {

                ByteBuffer signature = ByteBuffer.wrap(transaction.getType() == Transaction.typeCoinGeneration ?
                        new byte[FieldByteSize.hash] : transaction.getSignature());
                if (!signatures.contains(signature)) {
                    signatures.add(signature);
                    validTransactions.add(transaction);
                }
            }
        }

        return validTransactions;
    }

    public int getBlockchainVersion() {
        return blockchainVersion;
    }

    public long getBlockHeight() {
        return height;
    }

    public byte[] getPreviousBlockHash() {
        return previousBlockHash;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public long getVerificationTimestamp() {
        return verificationTimestamp;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public byte[] getHash() {
        return HashUtil.doubleSHA256(verifierSignature);
    }

    public byte[] getBalanceListHash() {
        return balanceListHash;
    }

    public Block getPreviousBlock() {

        Block previousBlock = null;
        if (getBlockHeight() > 0L) {
            if (getBlockHeight() <= BlockManager.getFrozenEdgeHeight() + 1) {
                Block frozenBlock = BlockManager.frozenBlockForHeight(height - 1);
                if (frozenBlock != null && ByteUtil.arraysAreEqual(frozenBlock.getHash(), previousBlockHash)) {
                    previousBlock = frozenBlock;
                }
            } else {
                previousBlock = UnfrozenBlockManager.unfrozenBlockAtHeight(height - 1, previousBlockHash);
            }
        }

        return previousBlock;
    }

    public byte[] getVerifierIdentifier() {
        return verifierIdentifier;
    }

    public byte[] getVerifierSignature() {
        return verifierSignature;
    }

    public ContinuityState getContinuityState() {

        if (continuityState == ContinuityState.Undetermined) {
            determineContinuityState();
        }

        return continuityState;
    }

    public long getTransactionFees() {

        long fees = 0L;
        for (Transaction transaction : transactions) {
            fees += transaction.getFee();
        }

        return fees;
    }

    public int getByteSize() {

        return getByteSize(true);
    }

    public int getByteSize(boolean includeSignature) {

        int size = FieldByteSize.combinedVersionAndHeight +    // version + height
                FieldByteSize.hash +                           // previous-block hash
                FieldByteSize.timestamp +                      // start timestamp
                FieldByteSize.timestamp +                      // verification timestamp
                4 +                                            // number of transactions
                FieldByteSize.hash;                            // balance-list hash
        for (Transaction transaction : transactions) {
            size += transaction.getByteSize();
        }
        if (includeSignature) {
            size += FieldByteSize.identifier + FieldByteSize.signature;
        }

        return size;
    }

    public CycleInformation getCycleInformation() {

        if (cycleInformation == null) {
            calculateCycleInformation();
        }

        return cycleInformation;
    }

    private void calculateCycleInformation() {

        // This is the new method. It finds the maximum cycle length of any block in the previous three cycles.
        Block blockToCheck = this;
        boolean reachedGenesisBlock = false;
        Set<ByteBuffer> identifiers = new HashSet<>();
        List<ByteBuffer> orderedIdentifiers = new ArrayList<>();
        int maximumCycleLength = 0;
        long cycleEndHeight = getBlockHeight();
        long primaryCycleEndHeight = cycleEndHeight;
        int primaryCycleIndex = 0;
        int[] primaryCycleLengths = new int[4];
        boolean inGenesisCycle = false;
        boolean newVerifier = false;
        while (primaryCycleIndex < 4 && blockToCheck != null) {

            ByteBuffer identifier = ByteBuffer.wrap(blockToCheck.getVerifierIdentifier());

            // Each pass of this loop calculated the cycle length for one end block height. If a new verifier was
            // added to the cycle, multiple end blocks may have the same start block.
            while (identifiers.contains(identifier) && primaryCycleIndex < 4) {

                int cycleLength = orderedIdentifiers.size();

                // Verifiers are always in the same order. So, if the verifier does not close its own cycle, then it
                // is a new verifier.
                if (primaryCycleIndex == 0) {
                    newVerifier = !identifier.equals(ByteBuffer.wrap(getVerifierIdentifier()));
                }

                // If this is a primary cycle (one of the cycles stepping back from the current block), step back
                // another cycle.
                if (cycleEndHeight == primaryCycleEndHeight) {
                    primaryCycleLengths[primaryCycleIndex] = cycleLength;
                    primaryCycleEndHeight -= cycleLength;
                    primaryCycleIndex++;
                }

                // If this was not the final cycle outside the area we want to analyze, consider the cycle length.
                if (primaryCycleIndex < 4 && cycleEndHeight != getBlockHeight()) {
                    maximumCycleLength = Math.max(maximumCycleLength, cycleLength);
                }

                // Step back to the previous block.
                cycleEndHeight--;
                ByteBuffer removedIdentifier = orderedIdentifiers.remove(orderedIdentifiers.size() - 1);
                identifiers.remove(removedIdentifier);
            }

            orderedIdentifiers.add(0, identifier);
            identifiers.add(identifier);

            // This is the special case when we reach the Genesis block.
            if (blockToCheck.getBlockHeight() == 0 && primaryCycleIndex < 4) {

                reachedGenesisBlock = true;

                // The cycle length of this cycle is from the Genesis block to the currently marked end.
                int cycleLength = (int) primaryCycleEndHeight + 1;
                primaryCycleLengths[primaryCycleIndex] = cycleLength;
                maximumCycleLength = Math.max(maximumCycleLength, cycleLength - 1);

                // If we have not yet found a cycle, mark as Genesis and a new verifier. Otherwise, consider the
                // ordered identifiers list for a previous maximum, as it is a cycle that has not yet been processed.
                if (primaryCycleIndex == 0) {
                    inGenesisCycle = true;
                    newVerifier = true;
                } else if (primaryCycleIndex < 3 || cycleEndHeight != primaryCycleEndHeight) {
                    maximumCycleLength = Math.max(maximumCycleLength, orderedIdentifiers.size());
                }
            }

            blockToCheck = blockToCheck.getPreviousBlock();
        }

        // If we found four full cycles or if we reached the beginning of the chain, we can build the
        // cycle information.
        if (primaryCycleIndex == 4 || reachedGenesisBlock) {

            // This considers the current cycle length as part of the maximum cycle length. This is inconsequential,
            // but it does make the properties of the maximum cycle length cleaner. Precisely, it means that the
            // maximum cycle length will not increase from one block to the next without an increase in the cycle
            // length.
            maximumCycleLength = Math.max(maximumCycleLength, primaryCycleLengths[0]);

            ByteBuffer verifierIdentifier = ByteBuffer.wrap(getVerifierIdentifier());

            cycleInformation = new CycleInformation(height, maximumCycleLength, primaryCycleLengths, newVerifier,
                    inGenesisCycle);
        }
    }

    private void determineContinuityState() {

        CycleInformation cycleInformation = getCycleInformation();
        if (cycleInformation != null) {

            // Proof-of-diversity rule 1: After the first existing verifier in the block chain, a new verifier is only
            // allowed if none of the other blocks in the cycle, the previous cycle, or the two blocks before the
            // previous cycle were verified by new verifiers.

            boolean rule1Pass;
            boolean sufficientInformation;
            if (cycleInformation.isInGenesisCycle() || !cycleInformation.isNewVerifier()) {
                rule1Pass = true;
                sufficientInformation = true;
            } else {
                long startCheckHeight = getBlockHeight() - cycleInformation.getCycleLength() -
                        cycleInformation.getCycleLength(1) - 1;
                Block blockToCheck = getPreviousBlock();
                sufficientInformation = blockToCheck != null;
                rule1Pass = true;
                while (blockToCheck != null && blockToCheck.getBlockHeight() >= startCheckHeight && rule1Pass &&
                        sufficientInformation) {

                    // If the cycle information is null, the continuity state cannot be calculated. If the block is a
                    // new verifier, this block is discontinuous.
                    if (blockToCheck.getCycleInformation() == null) {
                        sufficientInformation = false;
                    } else if (blockToCheck.getCycleInformation().isNewVerifier()) {
                        rule1Pass = false;
                    }

                    // If we have not reached the start check height and the next block back in the chain is null, the
                    // continuity state cannot be calculated.
                    if (blockToCheck.getBlockHeight() > startCheckHeight && blockToCheck.getPreviousBlock() == null) {
                        sufficientInformation = false;
                    }

                    // Step back one block.
                    blockToCheck = blockToCheck.getPreviousBlock();
                }
            }

            if (sufficientInformation) {

                if (rule1Pass) {

                    // Proof-of-diversity rule 2: Past the Genesis block, the cycle of a block must be longer than half
                    // of one more than the maximum of the all cycle lengths in this cycle and the previous two cycles.

                    long threshold = (cycleInformation.getMaximumCycleLength() + 1L) / 2L;
                    boolean rule2Pass = getBlockHeight() == 0 || cycleInformation.getCycleLength() > threshold;
                    continuityState = rule2Pass ? ContinuityState.Continuous : ContinuityState.Discontinuous;

                } else {
                    continuityState = ContinuityState.Discontinuous;
                }
            }
        }
    }

    public byte[] getBytes() {

        return getBytes(true);
    }

    private byte[] getBytes(boolean includeSignature) {

        int size = getByteSize();

        // Assemble the buffer.
        byte[] array = new byte[size];
        ByteBuffer buffer = ByteBuffer.wrap(array);
        buffer.putLong(ShortLong.combinedValue(blockchainVersion, height));
        buffer.put(previousBlockHash);
        buffer.putLong(startTimestamp);
        buffer.putLong(verificationTimestamp);
        buffer.putInt(transactions.size());
        for (Transaction transaction : transactions) {
            buffer.put(transaction.getBytes());
        }
        buffer.put(balanceListHash);
        if (includeSignature) {
            buffer.put(verifierIdentifier);
            buffer.put(verifierSignature);
        }

        return array;
    }

    public void sign(long verificationTimestamp, byte[] signerSeed) {

        this.verificationTimestamp = verificationTimestamp;
        this.verifierIdentifier = KeyUtil.identifierForSeed(signerSeed);
        this.verifierSignature = SignatureUtil.signBytes(getBytes(false), signerSeed);
    }

    public void sign(byte[] signerSeed) {

        sign(System.currentTimeMillis(), signerSeed);
    }

    public boolean signatureIsValid() {

        if (signatureState == SignatureState.Undetermined) {
            signatureState = SignatureUtil.signatureIsValid(verifierSignature, getBytes(false), verifierIdentifier) ?
                    SignatureState.Valid : SignatureState.Invalid;
        }

        return signatureState == SignatureState.Valid;
    }

    public long getMinimumVoteTimestamp() {

        long timestamp = Long.MAX_VALUE;
        long chainScore = chainScore(BlockManager.getFrozenEdgeHeight());
        if (chainScore < 0) {
            // Allow voting immediately for a new verifier.
            timestamp = Verifier.getLastBlockFrozenTimestamp();
        } else if (chainScore < 100000) {
            // Wait a minimum of two seconds to vote for an existing verifier, adding 20 seconds for each score above
            // zero.
            timestamp = Verifier.getLastBlockFrozenTimestamp() + 2000L + chainScore * 20000L;

            // The new-verifier lottery window changes every 50 blocks. The integrity of the system is reliant on the
            // integrity of the entrance process. Therefore, allowing new verifiers to successfully join in their
            // eligibility windows is imperative. To encourage this, an additional delay is added on blocks 25 and 49
            // of each eligibility window when a new verifier is likely to be accepted. This delay is infrequent enough
            // and small enough that it will not cause significant delays in blockchain processing.
            if (BlockManager.likelyAcceptingNewVerifiers()) {
                if (height % 50 == 25) {
                    System.out.println("10-second wait for new verifier at height " + height);
                    timestamp += 10000L;
                } else if (height % 50 == 49) {
                    // This should be reduced in the future. For now, it is an acceptable compromise to improve the
                    // entrance process.
                    System.out.println("40-second wait for new verifier at height " + height);
                    timestamp += 40000L;
                }
            }
        }

        return timestamp;
    }

    public static Block fromBytes(byte[] bytes) {

        return fromByteBuffer(ByteBuffer.wrap(bytes));
    }

    public static Block fromByteBuffer(ByteBuffer buffer) {

        return fromByteBuffer(buffer, true);
    }

    public static Block fromByteBuffer(ByteBuffer buffer, boolean validateTransactions) {

        ShortLong versionAndHeight = ShortLong.fromByteBuffer(buffer);
        int blockchainVersion = versionAndHeight.getShortValue();
        long blockHeight = versionAndHeight.getLongValue();
        byte[] previousBlockHash = new byte[FieldByteSize.hash];
        buffer.get(previousBlockHash);
        long startTimestamp = buffer.getLong();
        long verificationTimestamp = buffer.getLong();
        int numberOfTransactions = buffer.getInt();
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < numberOfTransactions; i++) {
            transactions.add(Transaction.fromByteBuffer(buffer, blockHeight, previousBlockHash));
        }

        byte[] balanceListHash = new byte[FieldByteSize.hash];
        buffer.get(balanceListHash);
        byte[] verifierIdentifier = new byte[FieldByteSize.identifier];
        buffer.get(verifierIdentifier);
        byte[] verifierSignature = new byte[FieldByteSize.signature];
        buffer.get(verifierSignature);

        // Transaction validation only needs to occur on blocks past the frozen edge.
        validateTransactions &= blockHeight > BlockManager.getFrozenEdgeHeight();

        return new Block(blockchainVersion, blockHeight, previousBlockHash, startTimestamp, verificationTimestamp,
                transactions, balanceListHash, verifierIdentifier, verifierSignature, validateTransactions);
    }

    public static BalanceList balanceListForNextBlock(Block previousBlock, BalanceList previousBalanceList,
                                                      List<Transaction> transactions, byte[] verifierIdentifier,
                                                      int blockchainVersion) {

        BalanceList result = null;
        try {
            // Only continue if the necessary data is available. For all blocks other than the Genesis block, the
            // previous balance list is required.
            if (previousBlock == null || previousBalanceList != null) {

                // For the Genesis block, start with an empty/zero values. For all others, start with the information
                // from the previous block's balance list.
                List<BalanceListItem> previousBalanceItems;
                List<byte[]> previousVerifiers;
                long blockHeight;
                long previousRolloverFees;
                long previousUnlockThreshold;
                long previousUnlockTransferSum;
                if (previousBlock == null) {
                    previousBalanceItems = new ArrayList<>();
                    previousVerifiers = new ArrayList<>();
                    blockHeight = 0L;
                    previousRolloverFees = 0;
                    previousUnlockThreshold = 0L;
                    previousUnlockTransferSum = 0L;
                } else {
                    blockHeight = previousBlock.getBlockHeight() + 1L;
                    previousBalanceItems = previousBalanceList.getItems();
                    previousRolloverFees = previousBalanceList.getRolloverFees();

                    // Get the previous verifiers from the previous block. Add the newest and remove the oldest.
                    previousVerifiers = new ArrayList<>(previousBalanceList.getPreviousVerifiers());
                    previousVerifiers.add(previousBlock.getVerifierIdentifier());
                    if (previousVerifiers.size() > 9) {
                        previousVerifiers.remove(0);
                    }

                    previousUnlockThreshold = previousBalanceList.getUnlockThreshold();
                    previousUnlockTransferSum = previousBalanceList.getUnlockTransferSum();
                }

                // Make a map of the identifiers to balance list items.
                Map<ByteBuffer, BalanceListItem> identifierToItemMap = new HashMap<>();
                for (BalanceListItem item : previousBalanceItems) {
                    identifierToItemMap.put(ByteBuffer.wrap(item.getIdentifier()), item);
                }

                // Remove any invalid transactions. The previous block is only null for the Genesis block. This also
                // only needs to be performed on blocks past the frozen edge, as blocks that have been frozen are no
                // longer subject to scrutiny.
                if (previousBlock != null && blockHeight > BlockManager.getFrozenEdgeHeight()) {
                    transactions = BalanceManager.approvedTransactionsForBlock(transactions, previousBlock);
                }

                // Add/subtract all transactions. While doing this, sum the fees, organic transaction fees, and
                // transaction amounts from locked accounts.
                long feesThisBlock = 0L;
                long organicTransactionFees = 0L;
                long transactionSumFromLockedAccounts = 0L;
                for (Transaction transaction : transactions) {

                    feesThisBlock += transaction.getFee();
                    byte[] senderIdentifier = transaction.getType() == Transaction.typeCycle ?
                            BalanceListItem.cycleAccountIdentifier : transaction.getSenderIdentifier();
                    if (transaction.getType() != Transaction.typeCoinGeneration) {
                        adjustBalance(senderIdentifier, -transaction.getAmount(), identifierToItemMap);
                    }

                    long amountAfterFee = transaction.getAmount() - transaction.getFee();
                    if (amountAfterFee > 0) {
                        adjustBalance(transaction.getReceiverIdentifier(), amountAfterFee, identifierToItemMap);
                    }

                    if (transaction.getType() == Transaction.typeStandard) {
                        organicTransactionFees += transaction.getFee();
                    }

                    if (LockedAccountManager.isSubjectToLock(transaction)) {
                        transactionSumFromLockedAccounts += transaction.getAmount();
                    }
                }

                // For a blockchain versions greater than 0, move 1% of the organic transaction fees to the cycle
                // account.
                if (blockchainVersion > 0 && organicTransactionFees >= 100L) {
                    // Calculate the amount, rounding down to the nearest micronyzo.
                    long cycleTransferAmount = organicTransactionFees / 100L;

                    // Subtract the amount from the fees this block and move the funds to the cycle account.
                    feesThisBlock -= cycleTransferAmount;
                    adjustBalance(BalanceListItem.cycleAccountIdentifier, cycleTransferAmount, identifierToItemMap);
                }

                // Subtract fees for all balance list items that owe fees.
                long periodicAccountFees = 0L;
                for (ByteBuffer identifier : identifierToItemMap.keySet()) {
                    BalanceListItem item = identifierToItemMap.get(identifier);
                    if (item.getBlocksUntilFee() <= 0 &&
                            !ByteUtil.arraysAreEqual(identifier.array(), BalanceListItem.transferIdentifier)) {

                        // In version 0 of the blockchain, charge μ1 every 500 blocks. In version 1 of the blockchain,
                        // charge μ100 every 500 blocks for all accounts less than ∩1. Always reset the fee counter.
                        item = item.resetBlocksUntilFee();
                        if (blockchainVersion == 0 && item.getBalance() > 0L) {
                            item = item.adjustByAmount(-1L);
                            periodicAccountFees++;
                        } else if (blockchainVersion == 1 && item.getBalance() < Transaction.micronyzoMultiplierRatio) {
                            long fee = Math.min(item.getBalance(), 100L);
                            item = item.adjustByAmount(-1L * fee);
                            periodicAccountFees += fee;
                        }
                        identifierToItemMap.put(identifier, item);
                    }
                }

                // Split the transaction fees among the current and previous verifiers.
                List<byte[]> verifiers = new ArrayList<>(previousVerifiers);
                verifiers.add(verifierIdentifier);
                long totalFees = feesThisBlock + previousRolloverFees + periodicAccountFees;
                long feesPerVerifier = totalFees / verifiers.size();
                if (feesPerVerifier > 0L) {
                    for (byte[] verifier : verifiers) {
                        adjustBalance(verifier, feesPerVerifier, identifierToItemMap);
                    }
                }

                // Make the new balance items from the balance map, decrementing the blocks-until-fee counter for each.
                long micronyzosInSystem = 0L;
                List<BalanceListItem> balanceItems = new ArrayList<>();
                for (ByteBuffer identifier : identifierToItemMap.keySet()) {
                    BalanceListItem item = identifierToItemMap.get(identifier);
                    if (item.getBalance() > 0L) {
                        balanceItems.add(item.decrementBlocksUntilFee());
                        micronyzosInSystem += item.getBalance();
                    }
                }

                // Make the balance list. The pairs are sorted in the constructor.
                byte rolloverFees = (byte) (totalFees % verifiers.size());
                micronyzosInSystem += rolloverFees;
                if (micronyzosInSystem == Transaction.micronyzosInSystem) {
                    // Version 0 of the blockchain does not track the unlock threshold and transfer sum.
                    long unlockThreshold = blockchainVersion == 0 ? 0 : previousUnlockThreshold +
                            organicTransactionFees;
                    long unlockTransferSum = blockchainVersion == 0 ? 0 : previousUnlockTransferSum +
                            transactionSumFromLockedAccounts;

                    result = new BalanceList(blockchainVersion, blockHeight, rolloverFees, previousVerifiers,
                            balanceItems, unlockThreshold, unlockTransferSum);
                }
            }
        } catch (Exception e) {
            System.out.println(PrintUtil.printException(e));
        }

        return result;
    }

    private static void adjustBalance(byte[] identifier, long amount,
                                      Map<ByteBuffer, BalanceListItem> identifierToItemMap) {

        ByteBuffer identifierBuffer = ByteBuffer.wrap(identifier);
        BalanceListItem item = identifierToItemMap.get(identifierBuffer);
        if (item == null) {
            item = new BalanceListItem(identifier, 0L);
        }
        item = item.adjustByAmount(amount);
        identifierToItemMap.put(identifierBuffer, item);
    }
    
    public long chainScore(long zeroBlockHeight) {

        // This score is always relative to a provided block height. The zero block height has a score of zero, and
        // each subsequent block affects the score as follows:
        // - the preferred new verifier subtracts 2; all others add 10,000
        // - an existing verifier adds the difference in cycle length between the previous block and this block,
        //   multiplied by 4
        // - an existing verifier that is no longer in the mesh or shares an IP with another verifier adds 5

        long score = 0L;
        Block block = this;
        while (block != null && block.getBlockHeight() > zeroBlockHeight && score < Long.MAX_VALUE - 1) {
            CycleInformation cycleInformation = block.getCycleInformation();
            ContinuityState continuityState = block.getContinuityState();

            if (ByteUtil.arraysAreEqual(Verifier.getIdentifier(), block.getVerifierIdentifier()) &&
                    !Verifier.inCycle() && Verifier.isTopNewVerifier()) {
                // This is likely an incorrect score. If a different verifier has joined recently, then the correct
                // score would be Long.MAX_VALUE to signify a discontinuity. However, the only consequence of the
                // incorrect score would be transmission of an invalid block, which would quickly be rejected by the
                // entire cycle. As this verifier is out-of-cycle, it does not yet have any voting power, so this
                // miscalculation does not weaken the system in any way.
                score = -2L;
            } else if (cycleInformation == null || continuityState == ContinuityState.Undetermined) {
                score = Long.MAX_VALUE - 1;  // unable to compute; might improve with more information
            } else {
                if (continuityState == ContinuityState.Discontinuous) {
                    score = Long.MAX_VALUE;  // invalid
                } else if (cycleInformation.isNewVerifier()) {
                    if (cycleInformation.isInGenesisCycle()) {

                        // This is a special case for the Genesis cycle. We want a deterministic order that can be
                        // calculated locally, but we do not care what that order is.
                        score = (Math.abs(HashUtil.longSHA256(block.getVerifierIdentifier())) % 9000) * -1L - 1000L;

                    } else {
                        // Only allow the top new verifier to join.
                        ByteBuffer topNewVerifier = NewVerifierVoteManager.topVerifier();
                        if (topNewVerifier != null &&
                                ByteUtil.arraysAreEqual(topNewVerifier.array(), block.getVerifierIdentifier())) {
                            score -= 2L;
                        } else {
                            score += 10000L;
                        }

                        // Penalize for each balance-list spam transaction.
                        score += block.spamTransactionCount() * 5L;
                    }
                } else {
                    Block previousBlock = getPreviousBlock();
                    if (previousBlock == null || previousBlock.getCycleInformation() == null) {
                        score = Long.MAX_VALUE - 1;  // unable to compute; might improve with more information
                    } else {
                        score += (previousBlock.getCycleInformation().getCycleLength() -
                                cycleInformation.getCycleLength()) * 4L;

                        // If a verifier needs to be removed, apply a penalty score of 5. This will put it just behind
                        // the next verifier in the cycle.
                        if (score == 0 &&
                                VerifierRemovalManager.shouldPenalizeVerifier(block.getVerifierIdentifier())) {
                            score += 5L;
                        }

                        // Penalize for each balance-list spam transaction.
                        score += block.spamTransactionCount() * 5L;

                        // Account for the blockchain version. If this is a version downgrade, a skip in versions, or
                        // higher than the maximum allowed version, the block is invalid.
                        if (block.getBlockchainVersion() < previousBlock.getBlockchainVersion() ||
                                block.getBlockchainVersion() > previousBlock.getBlockchainVersion() + 1 ||
                                block.getBlockchainVersion() > maximumBlockchainVersion) {
                            score = Long.MAX_VALUE;  // invalid
                        } else if (BlockchainVersionManager.isMissedUpgradeOpportunity(block,
                                previousBlock.getBlockchainVersion())) {
                            // In this case, an upgrade is allowed but this block is not an upgrade. Apply a 1-point
                            // penalty to this block to encourage an upgrade block from the same verifier to be approved
                            // if available.
                            score += 1L;
                            LogUtil.println("applying penalty of 1 to block " + block + ", score=" + score +
                                    ", verifier=" + PrintUtil.compactPrintByteArray(block.getVerifierIdentifier()));
                        } else if (BlockchainVersionManager.isImproperlyTimedUpgrade(block,
                                previousBlock.getBlockchainVersion())) {
                            // In this case, the block is an upgrade when we are not looking to upgrade. This is a valid
                            // block, but it is not preferred. Apply a large penalty.
                            score += 10000L;
                        }
                    }
                }
            }

            // Check the verification timestamp interval.
            Block previousBlock = block.getPreviousBlock();
            if (previousBlock != null && previousBlock.getVerificationTimestamp() >
                    block.getVerificationTimestamp() - minimumVerificationInterval) {
                score = Long.MAX_VALUE;  // invalid
            }

            // Check that the verification timestamp is not unreasonably far into the future.
            if (block.getVerificationTimestamp() > System.currentTimeMillis() + 5000L) {
                score = Long.MAX_VALUE;  // invalid
            }

            block = previousBlock;
        }

        if (block == null) {
            score = Long.MAX_VALUE - 1;  // unable to compute; might improve with more information
        }

        return score;
    }

    private int spamTransactionCount() {

        // This method makes a best effort to calculate the spam transaction count. There are no reasons it should fail,
        // but if it does for an unexpected reason, this should not cause the block score to be invalid, so a count of
        // zero will be returned.
        int count = 0;
        Block previousBlock = getPreviousBlock();
        if (previousBlock != null) {
            BalanceList balanceList = BalanceListManager.balanceListForBlock(previousBlock);
            if (balanceList != null) {
                Map<ByteBuffer, Long> balanceMap = BalanceManager.makeBalanceMap(balanceList);
                count = BalanceManager.numberOfTransactionsSpammingBalanceList(balanceMap, getTransactions());
            }
        }

        return count;
    }

    @Override
    public String toString() {
        return "[Block:v=" + getBlockchainVersion() + ",height=" + getBlockHeight() + ",hash=" +
                PrintUtil.compactPrintByteArray(getHash()) + ",id=" +
                PrintUtil.compactPrintByteArray(getVerifierIdentifier()) + "]";
    }
}
