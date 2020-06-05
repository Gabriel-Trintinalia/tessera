package com.quorum.tessera.recover;

import com.quorum.tessera.data.staging.StagingEntityDAO;
import com.quorum.tessera.data.staging.StagingTransaction;
import com.quorum.tessera.enclave.EncodedPayload;
import com.quorum.tessera.enclave.PayloadEncoder;
import com.quorum.tessera.enclave.PrivacyMode;
import com.quorum.tessera.partyinfo.PartyInfoService;
import com.quorum.tessera.partyinfo.model.Party;
import com.quorum.tessera.partyinfo.model.PartyInfo;
import com.quorum.tessera.sync.TransactionRequester;
import com.quorum.tessera.transaction.TransactionManager;
import com.quorum.tessera.transaction.exception.PrivacyViolationException;
import com.quorum.tessera.transaction.exception.StoreEntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

public class RecoveryImpl implements Recovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryImpl.class);

    private static final int BATCH_SIZE = 10000;

    private final StagingEntityDAO stagingEntityDAO;

    private final PartyInfoService partyInfoService;

    private final TransactionRequester transactionRequester;

    private final TransactionManager transactionManager;

    private final PayloadEncoder payloadEncoder;

    public RecoveryImpl(
            StagingEntityDAO stagingEntityDAO,
            PartyInfoService partyInfoService,
            TransactionRequester transactionRequester,
            TransactionManager transactionManager,PayloadEncoder payloadEncoder) {
        this.stagingEntityDAO = Objects.requireNonNull(stagingEntityDAO);
        this.partyInfoService = Objects.requireNonNull(partyInfoService);
        this.transactionRequester = Objects.requireNonNull(transactionRequester);
        this.transactionManager = Objects.requireNonNull(transactionManager);
        this.payloadEncoder = Objects.requireNonNull(payloadEncoder);
    }

    @Override
    public RecoveryResult request() {

        final PartyInfo partyInfo = partyInfoService.getPartyInfo();

        final Set<Party> partiesToRequest =
                partyInfo.getParties().stream()
                        .filter(p -> !p.getUrl().equals(partyInfo.getUrl()))
                        .collect(Collectors.toSet());

        final long failures =
                partiesToRequest.stream()
                        .filter(p -> !transactionRequester.requestAllTransactionsFromNode(p.getUrl()))
                        .peek(p -> LOGGER.warn("Fail resend request to {}", p.getUrl()))
                        .count();

        if (failures > 0) {
            if (failures == partiesToRequest.size()) {
                return RecoveryResult.FAILURE;
            }
            return RecoveryResult.PARTIAL_SUCCESS;
        }
        return RecoveryResult.SUCCESS;
    }

    @Override
    public RecoveryResult stage() {

        final AtomicLong stage = new AtomicLong(0);

        while (stagingEntityDAO.updateStageForBatch(BATCH_SIZE, stage.incrementAndGet()) != 0) {}

        final long totalCount = stagingEntityDAO.countAll();
        final long validatedCount = stagingEntityDAO.countStaged();

        if (validatedCount < totalCount) {
            if (validatedCount == 0) {
                return RecoveryResult.FAILURE;
            }
            return RecoveryResult.PARTIAL_SUCCESS;
        }
        return RecoveryResult.SUCCESS;
    }

    @Override
    public RecoveryResult sync() {

        final AtomicInteger payloadCount = new AtomicInteger(0);
        final AtomicInteger syncFailureCount = new AtomicInteger(0);

        final int maxResult = BATCH_SIZE;

        for (int offset = 0; offset < stagingEntityDAO.countAll(); offset += maxResult) {

            final List<StagingTransaction> transactions =
                    stagingEntityDAO.retrieveTransactionBatchOrderByStageAndHash(offset, maxResult);

            final Map<String, List<StagingTransaction>> grouped =
                    transactions.stream()
                            .collect(Collectors.groupingBy(StagingTransaction::getHash, LinkedHashMap::new, toList()));

            grouped.forEach(
                    (key, value) ->
                            value.stream()
                                    .filter(
                                            t -> {
                                                payloadCount.incrementAndGet();
                                                byte[] payload = t.getPayload();
                                                try {
                                                    EncodedPayload encodedPayload = payloadEncoder.decode(payload);
                                                    transactionManager.storePayload(encodedPayload);
                                                } catch (PrivacyViolationException | StoreEntityException ex) {
                                                    LOGGER.error(
                                                            "An error occurred during batch resend sync stage.", ex);
                                                    syncFailureCount.incrementAndGet();
                                                }
                                                return PrivacyMode.PRIVATE_STATE_VALIDATION == t.getPrivacyMode();
                                            })
                                    .findFirst());
        }

        if (syncFailureCount.get() > 0) {
            LOGGER.warn(
                    "There have been issues during the synchronisation process. "
                            + "Problematic transactions have been ignored.");
            if (syncFailureCount.get() == payloadCount.get()) {
                return RecoveryResult.FAILURE;
            }
            return RecoveryResult.PARTIAL_SUCCESS;
        }
        return RecoveryResult.SUCCESS;
    }
}
