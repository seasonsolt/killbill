/*
 * Copyright 2014 Groupon, Inc
 *
 * Groupon licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.payment.control;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.joda.time.DateTime;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceApiException;
import org.killbill.billing.invoice.api.InvoiceInternalApi;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoicePayment;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionStatus;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.control.dao.InvoicePaymentControlDao;
import org.killbill.billing.payment.control.dao.PluginAutoPayOffModelDao;
import org.killbill.billing.payment.dao.PaymentDao;
import org.killbill.billing.payment.dao.PaymentModelDao;
import org.killbill.billing.payment.dao.PaymentTransactionModelDao;
import org.killbill.billing.payment.glue.PaymentModule;
import org.killbill.billing.payment.retry.BaseRetryService.RetryServiceScheduler;
import org.killbill.billing.payment.retry.DefaultFailureCallResult;
import org.killbill.billing.payment.retry.DefaultPriorPaymentControlResult;
import org.killbill.billing.retry.plugin.api.FailureCallResult;
import org.killbill.billing.retry.plugin.api.PaymentControlApiException;
import org.killbill.billing.retry.plugin.api.PaymentControlContext;
import org.killbill.billing.retry.plugin.api.PaymentControlPluginApi;
import org.killbill.billing.retry.plugin.api.PriorPaymentControlResult;
import org.killbill.billing.util.api.TagUserApi;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.config.PaymentConfig;
import org.killbill.billing.util.tag.ControlTagType;
import org.killbill.billing.util.tag.Tag;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

public final class InvoicePaymentControlPluginApi implements PaymentControlPluginApi {

    public final static String CREATED_BY = "InvoicePaymentControlPluginApi";

    /* Don't change value String for properties as they are referenced from jaxrs without the constants which are not accessible */
    public final static String PLUGIN_NAME = "__INVOICE_PAYMENT_CONTROL_PLUGIN__";
    public static final String PROP_IPCD_INVOICE_ID = "IPCD_INVOICE_ID";
    public static final String PROP_IPCD_REFUND_IDS_WITH_AMOUNT_KEY = "IPCD_REFUND_IDS_AMOUNTS";
    public static final String PROP_IPCD_REFUND_WITH_ADJUSTMENTS = "IPCD_REFUND_WITH_ADJUSTMENTS";

    private final PaymentConfig paymentConfig;
    private final InvoiceInternalApi invoiceApi;
    private final TagUserApi tagApi;
    private final PaymentDao paymentDao;
    private final InvoicePaymentControlDao controlDao;
    private final RetryServiceScheduler retryServiceScheduler;
    private final InternalCallContextFactory internalCallContextFactory;
    private final Clock clock;

    private final Logger logger = LoggerFactory.getLogger(InvoicePaymentControlPluginApi.class);

    @Inject
    public InvoicePaymentControlPluginApi(final PaymentConfig paymentConfig, final InvoiceInternalApi invoiceApi, final TagUserApi tagApi, final PaymentDao paymentDao,
                                          final InvoicePaymentControlDao invoicePaymentControlDao,
                                          @Named(PaymentModule.RETRYABLE_NAMED) final RetryServiceScheduler retryServiceScheduler,
                                          final InternalCallContextFactory internalCallContextFactory, final Clock clock) {
        this.paymentConfig = paymentConfig;
        this.invoiceApi = invoiceApi;
        this.tagApi = tagApi;
        this.paymentDao = paymentDao;
        this.controlDao = invoicePaymentControlDao;
        this.retryServiceScheduler = retryServiceScheduler;
        this.internalCallContextFactory = internalCallContextFactory;
        this.clock = clock;
    }

    @Override
    public PriorPaymentControlResult priorCall(final PaymentControlContext paymentControlContext) throws PaymentControlApiException {

        final TransactionType transactionType = paymentControlContext.getTransactionType();
        Preconditions.checkArgument(transactionType == TransactionType.PURCHASE ||
                                    transactionType == TransactionType.REFUND ||
                                    transactionType == TransactionType.CHARGEBACK);

        final InternalCallContext internalContext = internalCallContextFactory.createInternalCallContext(paymentControlContext.getAccountId(), paymentControlContext);
        switch (transactionType) {
            case PURCHASE:
                return getPluginPurchaseResult(paymentControlContext, internalContext);
            case REFUND:
                return getPluginRefundResult(paymentControlContext, internalContext);
            case CHARGEBACK:
                return new DefaultPriorPaymentControlResult(false, paymentControlContext.getAmount());
            default:
                throw new IllegalStateException("Unexpected transactionType " + transactionType);
        }
    }

    @Override
    public void onSuccessCall(final PaymentControlContext paymentControlContext) throws PaymentControlApiException {

        final TransactionType transactionType = paymentControlContext.getTransactionType();
        Preconditions.checkArgument(transactionType == TransactionType.PURCHASE ||
                                    transactionType == TransactionType.REFUND ||
                                    transactionType == TransactionType.CHARGEBACK);

        final InternalCallContext internalContext = internalCallContextFactory.createInternalCallContext(paymentControlContext.getAccountId(), paymentControlContext);
        try {
            final InvoicePayment existingInvoicePayment;
            switch (transactionType) {
                case PURCHASE:
                    final UUID invoiceId = getInvoiceId(paymentControlContext);
                    existingInvoicePayment = invoiceApi.getInvoicePaymentForAttempt(paymentControlContext.getPaymentId(), internalContext);
                    if (existingInvoicePayment != null) {
                        logger.info("onSuccessCall was already completed for payment purchase :" + paymentControlContext.getPaymentId());
                    } else {
                        invoiceApi.notifyOfPayment(invoiceId,
                                                   paymentControlContext.getAmount(),
                                                   paymentControlContext.getCurrency(),
                                                   paymentControlContext.getProcessedCurrency(),
                                                   paymentControlContext.getPaymentId(),
                                                   paymentControlContext.getCreatedDate(),
                                                   internalContext);
                    }
                    break;

                case REFUND:
                    existingInvoicePayment = invoiceApi.getInvoicePaymentForRefund(paymentControlContext.getPaymentId(), internalContext);
                    if (existingInvoicePayment != null) {
                        logger.info("onSuccessCall was already completed for payment refund :" + paymentControlContext.getPaymentId());
                    } else {
                        final Map<UUID, BigDecimal> idWithAmount = extractIdsWithAmountFromProperties(paymentControlContext.getPluginProperties());
                        final PluginProperty prop = getPluginProperty(paymentControlContext.getPluginProperties(), PROP_IPCD_REFUND_WITH_ADJUSTMENTS);
                        final boolean isAdjusted = prop != null ? Boolean.valueOf((String) prop.getValue()) : false;
                        invoiceApi.createRefund(paymentControlContext.getPaymentId(), paymentControlContext.getAmount(), isAdjusted, idWithAmount, paymentControlContext.getTransactionExternalKey(), internalContext);
                    }
                    break;

                case CHARGEBACK:
                    existingInvoicePayment = invoiceApi.getInvoicePaymentForChargeback(paymentControlContext.getPaymentId(), internalContext);
                    if (existingInvoicePayment != null) {
                        logger.info("onSuccessCall was already completed for payment chargeback :" + paymentControlContext.getPaymentId());
                    } else {
                        invoiceApi.createChargeback(paymentControlContext.getPaymentId(), paymentControlContext.getProcessedAmount(), paymentControlContext.getProcessedCurrency(), internalContext);
                    }
                    break;

                default:
                    throw new IllegalStateException("Unexpected transactionType " + transactionType);
            }
        } catch (InvoiceApiException e) {
            // STEPH need to add some state machine logic in the plugin itself to handle those cases
            logger.error("Failed to complete call: ", e);
            //throw new PaymentControlApiException(e);
        }
    }

    @Override
    public FailureCallResult onFailureCall(final PaymentControlContext paymentControlContext) throws
                                                                                              PaymentControlApiException {

        final InternalCallContext internalContext = internalCallContextFactory.createInternalCallContext(paymentControlContext.getAccountId(), paymentControlContext);
        final TransactionType transactionType = paymentControlContext.getTransactionType();
        switch (transactionType) {
            case PURCHASE:
                final DateTime nextRetryDate = computeNextRetryDate(paymentControlContext.getPaymentExternalKey(), paymentControlContext.isApiPayment(), internalContext);
                return new DefaultFailureCallResult(nextRetryDate);
            case REFUND:
            case CHARGEBACK:
                // We don't retry  REFUND, CHARGEBACK
                return new DefaultFailureCallResult(null);
            default:
                throw new IllegalStateException("Unexpected transactionType " + transactionType);
        }
    }

    public void process_AUTO_PAY_OFF_removal(final Account account, final InternalCallContext internalCallContext) {
        final List<PluginAutoPayOffModelDao> entries = controlDao.getAutoPayOffEntry(account.getId());
        for (PluginAutoPayOffModelDao cur : entries) {
            retryServiceScheduler.scheduleRetry(ObjectType.ACCOUNT, account.getId(), cur.getAttemptId(), PLUGIN_NAME, clock.getUTCNow());
        }
        controlDao.removeAutoPayOffEntry(account.getId());
    }

    private UUID getInvoiceId(final PaymentControlContext paymentControlContext) throws PaymentControlApiException {
        final PluginProperty invoiceProp = getPluginProperty(paymentControlContext.getPluginProperties(), PROP_IPCD_INVOICE_ID);
        if (invoiceProp == null ||
            !(invoiceProp.getValue() instanceof String)) {
            throw new PaymentControlApiException("Need to specify a valid invoiceId in property " + PROP_IPCD_INVOICE_ID);
        }
        return UUID.fromString((String) invoiceProp.getValue());
    }

    private PriorPaymentControlResult getPluginPurchaseResult(final PaymentControlContext paymentControlPluginContext, final InternalCallContext internalContext) throws PaymentControlApiException {

        try {
            final UUID invoiceId = getInvoiceId(paymentControlPluginContext);
            final Invoice invoice = rebalanceAndGetInvoice(invoiceId, internalContext);
            final BigDecimal requestedAmount = validateAndComputePaymentAmount(invoice, paymentControlPluginContext.getAmount(), paymentControlPluginContext.isApiPayment());

            final boolean isAborted = requestedAmount.compareTo(BigDecimal.ZERO) == 0;
            if (!isAborted && insert_AUTO_PAY_OFF_ifRequired(paymentControlPluginContext, requestedAmount)) {
                return new DefaultPriorPaymentControlResult(true);
            }

            if (paymentControlPluginContext.isApiPayment() && isAborted) {
                throw new PaymentControlApiException("Payment for invoice " + invoice.getId() +
                                                     " aborted : invoice balance is = " + invoice.getBalance() +
                                                     ", requested payment amount is = " + paymentControlPluginContext.getAmount());
            } else {
                return new DefaultPriorPaymentControlResult(isAborted, requestedAmount);
            }
        } catch (InvoiceApiException e) {
            throw new PaymentControlApiException(e);
        } catch (IllegalArgumentException e) {
            throw new PaymentControlApiException(e);
        }
    }

    private PriorPaymentControlResult getPluginRefundResult(final PaymentControlContext paymentControlPluginContext, final InternalCallContext internalContext) throws PaymentControlApiException {

        final Map<UUID, BigDecimal> idWithAmount = extractIdsWithAmountFromProperties(paymentControlPluginContext.getPluginProperties());
        if ((paymentControlPluginContext.getAmount() == null || paymentControlPluginContext.getAmount().compareTo(BigDecimal.ZERO) == 0) &&
            idWithAmount.size() == 0) {
            throw new PaymentControlApiException("Refund for payment, key = " + paymentControlPluginContext.getPaymentExternalKey() +
                                                 " aborted: requested refund amount is = " + paymentControlPluginContext.getAmount());
        }

        final PaymentModelDao directPayment = paymentDao.getDirectPayment(paymentControlPluginContext.getPaymentId(), internalContext);
        if (directPayment == null) {
            throw new PaymentControlApiException();
        }
        // STEPH this check for invoice item but we also need to check that refundAmount is less or equal to paymentAmount - all refund.
        final BigDecimal amountToBeRefunded = computeRefundAmount(directPayment.getId(), paymentControlPluginContext.getAmount(), idWithAmount, internalContext);
        final boolean isAborted = amountToBeRefunded.compareTo(BigDecimal.ZERO) == 0;

        if (paymentControlPluginContext.isApiPayment() && isAborted) {
            throw new PaymentControlApiException("Refund for payment " + directPayment.getId() +
                                                 " aborted : invoice item sum amount is " + amountToBeRefunded +
                                                 ", requested refund amount is = " + paymentControlPluginContext.getAmount());
        } else {
            return new DefaultPriorPaymentControlResult(isAborted, amountToBeRefunded);
        }
    }

    private Map<UUID, BigDecimal> extractIdsWithAmountFromProperties(final Iterable<PluginProperty> properties) {
        final PluginProperty prop = getPluginProperty(properties, PROP_IPCD_REFUND_IDS_WITH_AMOUNT_KEY);
        if (prop == null) {
            return ImmutableMap.<UUID, BigDecimal>of();
        }
        return (Map<UUID, BigDecimal>) prop.getValue();
    }

    private PluginProperty getPluginProperty(final Iterable<PluginProperty> properties, final String propertyName) {
        return Iterables.tryFind(properties, new Predicate<PluginProperty>() {
            @Override
            public boolean apply(final PluginProperty input) {
                return input.getKey().equals(propertyName);
            }
        }).orNull();
    }

    private BigDecimal computeRefundAmount(final UUID paymentId, @Nullable final BigDecimal specifiedRefundAmount,
                                           final Map<UUID, BigDecimal> invoiceItemIdsWithAmounts, final InternalTenantContext context)
            throws PaymentControlApiException {

        if (invoiceItemIdsWithAmounts.size() == 0) {
            if (specifiedRefundAmount == null || specifiedRefundAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new PaymentControlApiException("You need to specify positive a refund amount");
            }
            return specifiedRefundAmount;
        }

        // If we have
        final List<InvoiceItem> items;
        try {
            items = invoiceApi.getInvoiceForPaymentId(paymentId, context).getInvoiceItems();

            BigDecimal amountFromItems = BigDecimal.ZERO;
            for (final UUID itemId : invoiceItemIdsWithAmounts.keySet()) {
                final BigDecimal specifiedItemAmount = invoiceItemIdsWithAmounts.get(itemId);
                final BigDecimal itemAmount = getAmountFromItem(items, itemId);
                if (specifiedItemAmount != null &&
                    (specifiedItemAmount.compareTo(BigDecimal.ZERO) <= 0 || specifiedItemAmount.compareTo(itemAmount) > 0)) {
                    throw new PaymentControlApiException("You need to specify valid invoice item amount ");
                }
                amountFromItems = amountFromItems.add(Objects.firstNonNull(specifiedItemAmount, itemAmount));
            }
            return amountFromItems;
        } catch (InvoiceApiException e) {
            throw new PaymentControlApiException(e);
        }
    }

    private BigDecimal getAmountFromItem(final List<InvoiceItem> items, final UUID itemId) throws PaymentControlApiException {
        for (final InvoiceItem item : items) {
            if (item.getId().equals(itemId)) {
                return item.getAmount();
            }
        }
        throw new PaymentControlApiException("Unable to find invoice item for id " + itemId);
    }

    private DateTime computeNextRetryDate(final String paymentExternalKey, final boolean isApiAPayment, final InternalCallContext internalContext) {

        // Don't retry call that come from API.
        if (isApiAPayment) {
            return null;
        }

        final List<PaymentTransactionModelDao> purchasedTransactions = getPurchasedTransactions(paymentExternalKey, internalContext);
        if (purchasedTransactions.size() == 0) {
            return null;
        }
        final PaymentTransactionModelDao lastTransaction = purchasedTransactions.get(purchasedTransactions.size() - 1);
        switch (lastTransaction.getTransactionStatus()) {
            case PAYMENT_FAILURE:
                return getNextRetryDateForPaymentFailure(purchasedTransactions);

            case UNKNOWN:
            case PLUGIN_FAILURE:
                return getNextRetryDateForPluginFailure(purchasedTransactions);

            default:
                return null;
        }
    }

    private DateTime getNextRetryDateForPaymentFailure(final List<PaymentTransactionModelDao> purchasedTransactions) {

        DateTime result = null;
        final List<Integer> retryDays = paymentConfig.getPaymentRetryDays();
        final int attemptsInState = getNumberAttemptsInState(purchasedTransactions, TransactionStatus.PAYMENT_FAILURE);
        final int retryCount = (attemptsInState - 1) >= 0 ? (attemptsInState - 1) : 0;
        if (retryCount < retryDays.size()) {
            int retryInDays;
            final DateTime nextRetryDate = clock.getUTCNow();
            try {
                retryInDays = retryDays.get(retryCount);
                result = nextRetryDate.plusDays(retryInDays);
            } catch (NumberFormatException ex) {
                logger.error("Could not get retry day for retry count {}", retryCount);
            }
        }
        return result;
    }

    private DateTime getNextRetryDateForPluginFailure(final List<PaymentTransactionModelDao> purchasedTransactions) {

        DateTime result = null;
        final int attemptsInState = getNumberAttemptsInState(purchasedTransactions, TransactionStatus.PLUGIN_FAILURE);
        final int retryAttempt = (attemptsInState - 1) >= 0 ? (attemptsInState - 1) : 0;

        if (retryAttempt < paymentConfig.getPluginFailureRetryMaxAttempts()) {
            int nbSec = paymentConfig.getPluginFailureRetryStart();
            int remainingAttempts = retryAttempt;
            while (--remainingAttempts > 0) {
                nbSec = nbSec * paymentConfig.getPluginFailureRetryMultiplier();
            }
            result = clock.getUTCNow().plusSeconds(nbSec);
        }
        return result;
    }

    private int getNumberAttemptsInState(final Collection<PaymentTransactionModelDao> allTransactions, final TransactionStatus... statuses) {
        if (allTransactions == null || allTransactions.size() == 0) {
            return 0;
        }
        return Collections2.filter(allTransactions, new Predicate<PaymentTransactionModelDao>() {
            @Override
            public boolean apply(final PaymentTransactionModelDao input) {
                for (final TransactionStatus cur : statuses) {
                    if (input.getTransactionStatus() == cur) {
                        return true;
                    }
                }
                return false;
            }
        }).size();
    }

    private List<PaymentTransactionModelDao> getPurchasedTransactions(final String paymentExternalKey, final InternalCallContext internalContext) {
        final PaymentModelDao payment = paymentDao.getDirectPaymentByExternalKey(paymentExternalKey, internalContext);
        if (payment == null) {
            return Collections.emptyList();
        }
        final List<PaymentTransactionModelDao> transactions = paymentDao.getDirectTransactionsForDirectPayment(payment.getId(), internalContext);
        if (transactions == null || transactions.size() == 0) {
            return Collections.emptyList();
        }
        return ImmutableList.copyOf(Iterables.filter(transactions, new Predicate<PaymentTransactionModelDao>() {
            @Override
            public boolean apply(final PaymentTransactionModelDao input) {
                return input.getTransactionType() == TransactionType.PURCHASE;
            }
        }));
    }

    private Invoice rebalanceAndGetInvoice(final UUID invoiceId, final InternalCallContext context) throws InvoiceApiException {
        final Invoice invoicePriorRebalancing = invoiceApi.getInvoiceById(invoiceId, context);
        invoiceApi.consumeExistingCBAOnAccountWithUnpaidInvoices(invoicePriorRebalancing.getAccountId(), context);
        final Invoice invoice = invoiceApi.getInvoiceById(invoiceId, context);
        return invoice;
    }

    private BigDecimal validateAndComputePaymentAmount(final Invoice invoice, @Nullable final BigDecimal inputAmount, final boolean isApiPayment) {

        if (invoice.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            logger.info("Invoice " + invoice.getId() + " has already been paid");
            return BigDecimal.ZERO;
        }
        if (isApiPayment &&
            inputAmount != null &&
            invoice.getBalance().compareTo(inputAmount) < 0) {
            logger.info("Invoice " + invoice.getId() +
                        " has a balance of " + invoice.getBalance().floatValue() +
                        " less than retry payment amount of " + inputAmount.floatValue());
            return BigDecimal.ZERO;
        }
        if (inputAmount == null) {
            return invoice.getBalance();
        } else {
            return invoice.getBalance().compareTo(inputAmount) < 0 ? invoice.getBalance() : inputAmount;
        }
    }

    private boolean insert_AUTO_PAY_OFF_ifRequired(final PaymentControlContext paymentControlContext, final BigDecimal computedAmount) {

        if (paymentControlContext.isApiPayment() || !isAccountAutoPayOff(paymentControlContext.getAccountId(), paymentControlContext)) {
            return false;
        }
        final PluginAutoPayOffModelDao data = new PluginAutoPayOffModelDao(paymentControlContext.getAttemptPaymentId(), paymentControlContext.getPaymentExternalKey(), paymentControlContext.getTransactionExternalKey(),
                                                                           paymentControlContext.getAccountId(), PLUGIN_NAME,
                                                                           paymentControlContext.getPaymentId(), paymentControlContext.getPaymentMethodId(),
                                                                           computedAmount, paymentControlContext.getCurrency(), CREATED_BY, clock.getUTCNow());
        controlDao.insertAutoPayOff(data);
        return true;
    }

    private boolean isAccountAutoPayOff(final UUID accountId, final CallContext callContext) {
        final List<Tag> accountTags = tagApi.getTagsForAccount(accountId, false, callContext);
        return ControlTagType.isAutoPayOff(Collections2.transform(accountTags, new Function<Tag, UUID>() {
            @Override
            public UUID apply(final Tag tag) {
                return tag.getTagDefinitionId();
            }
        }));
    }
}
