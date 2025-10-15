/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.integrationtests.common.BusinessDateHelper;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.GlobalConfigurationHelper;
import org.apache.fineract.integrationtests.common.SchedulerJobHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.accounting.JournalEntryHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsTestLifecycleExtension;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.fineract.integrationtests.common.BusinessDateHelper.runAt;

@ExtendWith({ SavingsTestLifecycleExtension.class })
public class SavingsInterestPostingTest {

    private static final Logger LOG = LoggerFactory.getLogger(SavingsInterestPostingTest.class);
    private static ResponseSpecification responseSpec;
    private static RequestSpecification requestSpec;
    private AccountHelper accountHelper;
    private SavingsAccountHelper savingsAccountHelper;
    private SchedulerJobHelper schedulerJobHelper;
    public static final String MINIMUM_OPENING_BALANCE = "1000.0";
    private GlobalConfigurationHelper globalConfigurationHelper;
    private SavingsProductHelper productHelper;
    private JournalEntryHelper journalEntryHelper;

    private static final String ACCRUALS_JOB_NAME = "Add Accrual Transactions For Savings";
    private static final String POST_INTEREST_JOB_NAME = "Post Interest For Savings";

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.schedulerJobHelper = new SchedulerJobHelper(this.requestSpec);
        this.accountHelper = new AccountHelper(this.requestSpec, this.responseSpec);
        this.savingsAccountHelper = new SavingsAccountHelper(this.requestSpec, this.responseSpec);
        this.journalEntryHelper = new JournalEntryHelper(this.requestSpec, this.responseSpec);
        this.globalConfigurationHelper = new GlobalConfigurationHelper();
    }

    @Test
    public void testPostInterestWithOverdraftProduct() {
        try {
            final String amount = "10000";

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account liabilityAccount = accountHelper.createLiabilityAccount();
            final Account interestReceivableAccount = accountHelper.createAssetAccount("interestReceivableAccount");
            final Account savingsControlAccount = accountHelper.createLiabilityAccount("Savings Control");
            final Account interestPayableAccount = accountHelper.createLiabilityAccount("Interest Payable");

            final Integer productId = createSavingsProductWithAccrualAccountingWithOutOverdraftAllowed(
                    interestPayableAccount.getAccountID().toString(), savingsControlAccount.getAccountID().toString(),
                    interestReceivableAccount.getAccountID().toString(), assetAccount, incomeAccount, expenseAccount, liabilityAccount);

            final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, "01 January 2025");
            final LocalDate startDate = LocalDate.of(2025, 2, 1);
            final String startDateString = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US).format(startDate);

            final Integer accountId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, productId,
                    SavingsAccountHelper.ACCOUNT_TYPE_INDIVIDUAL, startDateString);
            savingsAccountHelper.approveSavingsOnDate(accountId, startDateString);
            savingsAccountHelper.activateSavings(accountId, startDateString);
            savingsAccountHelper.depositToSavingsAccount(accountId, amount, startDateString, CommonConstants.RESPONSE_RESOURCE_ID);

            // Simulate time passing - update business date to March
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));
            LocalDate marchDate = LocalDate.of(2025, 3, 2);
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, marchDate);

            runAccrualsThenPost();

            long days = ChronoUnit.DAYS.between(startDate, marchDate.minusDays(1));
            BigDecimal expected = calcInterestPosting(productHelper, amount, days);

            List<HashMap> txs = getInterestTransactions(accountId);
            Assertions.assertEquals(expected, BigDecimal.valueOf(((Double) txs.get(0).get("amount"))), "ERROR in expected");

            long interestCount = countInterestOnDate(accountId, marchDate.minusDays(1));
            long overdraftCount = countOverdraftOnDate(accountId, marchDate.minusDays(1));
            Assertions.assertEquals(1L, interestCount, "Expected exactly one INTEREST posting on posting date");
            Assertions.assertEquals(0L, overdraftCount, "Expected NO OVERDRAFT posting on posting date");

            assertNoAccrualReversals(accountId);
        } finally {
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(false));
        }
    }

    @Test
    public void testOverdraftInterestWithOverdraftProduct() {
        try {
            final String amount = "10000";

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account liabilityAccount = accountHelper.createLiabilityAccount();
            final Account interestReceivableAccount = accountHelper.createAssetAccount("interestReceivableAccount");
            final Account savingsControlAccount = accountHelper.createLiabilityAccount("Savings Control");
            final Account interestPayableAccount = accountHelper.createLiabilityAccount("Interest Payable");

            final Integer productId = createSavingsProductWithAccrualAccountingWithOutOverdraftAllowed(
                    interestPayableAccount.getAccountID().toString(), savingsControlAccount.getAccountID().toString(),
                    interestReceivableAccount.getAccountID().toString(), assetAccount, incomeAccount, expenseAccount, liabilityAccount);

            final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, "01 January 2025");
            final LocalDate startDate = LocalDate.of(2025, 2, 1);
            final String startDateString = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US).format(startDate);

            final Integer accountId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, productId,
                    SavingsAccountHelper.ACCOUNT_TYPE_INDIVIDUAL, startDateString);
            savingsAccountHelper.approveSavingsOnDate(accountId, startDateString);
            savingsAccountHelper.activateSavings(accountId, startDateString);
            savingsAccountHelper.withdrawalFromSavingsAccount(accountId, amount, startDateString, CommonConstants.RESPONSE_RESOURCE_ID);

            // Simulate time passing - update business date to March
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));
            LocalDate marchDate = LocalDate.of(2025, 3, 2);
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, marchDate);

            runAccrualsThenPost();

            long days = ChronoUnit.DAYS.between(startDate, marchDate.minusDays(1));
            BigDecimal expected = calcOverdraftPosting(productHelper, amount, days);

            List<HashMap> txs = getInterestTransactions(accountId);
            Assertions.assertEquals(expected, BigDecimal.valueOf(((Double) txs.get(0).get("amount"))));

            BigDecimal runningBalance = BigDecimal.valueOf(((Double) txs.get(0).get("runningBalance")));
            Assertions.assertTrue(MathUtil.isLessThanZero(runningBalance), "Running balance is not less than zero");

            long interestCount = countInterestOnDate(accountId, marchDate.minusDays(1));
            long overdraftCount = countOverdraftOnDate(accountId, marchDate.minusDays(1));
            Assertions.assertEquals(0L, interestCount, "Expected NO INTEREST posting on posting date");
            Assertions.assertEquals(1L, overdraftCount, "Expected exactly one OVERDRAFT posting on posting date");

            assertNoAccrualReversals(accountId);
        } finally {
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(false));
        }
    }

    @Test
    public void testOverdraftAndInterestPosting_WithOverdraftProduct_WhitBalanceLessZero() {
        try {
            final String amountDeposit = "10000";
            final String amountWithdrawal = "20000";

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account liabilityAccount = accountHelper.createLiabilityAccount();
            final Account interestReceivableAccount = accountHelper.createAssetAccount("interestReceivableAccount");
            final Account savingsControlAccount = accountHelper.createLiabilityAccount("Savings Control");
            final Account interestPayableAccount = accountHelper.createLiabilityAccount("Interest Payable");

            final Integer productId = createSavingsProductWithAccrualAccountingWithOutOverdraftAllowed(
                    interestPayableAccount.getAccountID().toString(), savingsControlAccount.getAccountID().toString(),
                    interestReceivableAccount.getAccountID().toString(), assetAccount, incomeAccount, expenseAccount, liabilityAccount);

            final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, "01 January 2025");
            final LocalDate startDate = LocalDate.of(2025, 2, 1);
            final String startStr = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US).format(startDate);

            final Integer accountId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, productId,
                    SavingsAccountHelper.ACCOUNT_TYPE_INDIVIDUAL, startStr);
            savingsAccountHelper.approveSavingsOnDate(accountId, startStr);
            savingsAccountHelper.activateSavings(accountId, startStr);
            savingsAccountHelper.depositToSavingsAccount(accountId, amountDeposit, startStr, CommonConstants.RESPONSE_RESOURCE_ID);

            final LocalDate withdrawalDate = LocalDate.of(2025, 2, 16);
            final String withdrawalStr = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US).format(withdrawalDate);
            savingsAccountHelper.withdrawalFromSavingsAccount(accountId, amountWithdrawal, withdrawalStr,
                    CommonConstants.RESPONSE_RESOURCE_ID);

            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));
            LocalDate marchDate = LocalDate.of(2025, 3, 2);
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, marchDate);

            runAccrualsThenPost();

            List<HashMap> txs = getInterestTransactions(accountId);
            for (HashMap tx : txs) {
                BigDecimal amt = BigDecimal.valueOf(((Double) tx.get("amount")));
                @SuppressWarnings("unchecked")
                Map<String, Object> typeMap = (Map<String, Object>) tx.get("transactionType");
                SavingsAccountTransactionType type = SavingsAccountTransactionType.fromInt(((Double) typeMap.get("id")).intValue());

                if (type.isInterestPosting()) {
                    long days = ChronoUnit.DAYS.between(startDate, withdrawalDate);
                    BigDecimal expected = calcInterestPosting(productHelper, amountDeposit, days);
                    Assertions.assertEquals(expected, amt);
                } else {
                    long days = ChronoUnit.DAYS.between(withdrawalDate, marchDate.minusDays(1));
                    BigDecimal overdraftBase = new BigDecimal(amountWithdrawal).subtract(new BigDecimal(amountDeposit));
                    BigDecimal expected = calcOverdraftPosting(productHelper, overdraftBase.toString(), days);
                    Assertions.assertEquals(expected, amt);
                }
            }

            Assertions.assertEquals(1L, countInterestOnDate(accountId, marchDate.minusDays(1)),
                    "Expected exactly one INTEREST posting on posting date");
            Assertions.assertEquals(1L, countOverdraftOnDate(accountId, marchDate.minusDays(1)),
                    "Expected exactly one OVERDRAFT posting on posting date");

            assertNoAccrualReversals(accountId);
        } finally {
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(false));
        }
    }

    @Test
    public void testOverdraftAndInterestPosting_WithOverdraftProduct_WhitBalanceGreaterZero() {
        try {
            final String amountDeposit = "20000";
            final String amountWithdrawal = "10000";

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account liabilityAccount = accountHelper.createLiabilityAccount();
            final Account interestReceivableAccount = accountHelper.createAssetAccount("interestReceivableAccount");
            final Account savingsControlAccount = accountHelper.createLiabilityAccount("Savings Control");
            final Account interestPayableAccount = accountHelper.createLiabilityAccount("Interest Payable");

            final Integer productId = createSavingsProductWithAccrualAccountingWithOutOverdraftAllowed(
                    interestPayableAccount.getAccountID().toString(), savingsControlAccount.getAccountID().toString(),
                    interestReceivableAccount.getAccountID().toString(), assetAccount, incomeAccount, expenseAccount, liabilityAccount);

            final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, "01 January 2025");
            final LocalDate startDate = LocalDate.of(2025, 2, 1);
            final String startStr = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US).format(startDate);

            final Integer accountId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, productId,
                    SavingsAccountHelper.ACCOUNT_TYPE_INDIVIDUAL, startStr);
            savingsAccountHelper.approveSavingsOnDate(accountId, startStr);
            savingsAccountHelper.activateSavings(accountId, startStr);
            savingsAccountHelper.withdrawalFromSavingsAccount(accountId, amountWithdrawal, startStr, CommonConstants.RESPONSE_RESOURCE_ID);

            final LocalDate depositDate = LocalDate.of(2025, 2, 16);
            final String depositStr = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US).format(depositDate);
            savingsAccountHelper.depositToSavingsAccount(accountId, amountDeposit, depositStr, CommonConstants.RESPONSE_RESOURCE_ID);

            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));
            LocalDate marchDate = LocalDate.of(2025, 3, 2);
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, marchDate);

            runAccrualsThenPost();

            List<HashMap> txs = getInterestTransactions(accountId);
            for (HashMap tx : txs) {
                BigDecimal amt = BigDecimal.valueOf(((Double) tx.get("amount")));
                @SuppressWarnings("unchecked")
                Map<String, Object> typeMap = (Map<String, Object>) tx.get("transactionType");
                SavingsAccountTransactionType type = SavingsAccountTransactionType.fromInt(((Double) typeMap.get("id")).intValue());

                if (type.isOverDraftInterestPosting()) {
                    long days = ChronoUnit.DAYS.between(startDate, depositDate);
                    BigDecimal expected = calcOverdraftPosting(productHelper, amountWithdrawal, days);
                    Assertions.assertEquals(expected, amt);
                } else {
                    long days = ChronoUnit.DAYS.between(depositDate, marchDate.minusDays(1));
                    BigDecimal positiveBase = new BigDecimal(amountDeposit).subtract(new BigDecimal(amountWithdrawal));
                    BigDecimal expected = calcInterestPosting(productHelper, positiveBase.toString(), days);
                    Assertions.assertEquals(expected, amt);
                }
            }

            Assertions.assertEquals(1L, countOverdraftOnDate(accountId, marchDate.minusDays(1)),
                    "Expected exactly one OVERDRAFT posting on posting date");
            Assertions.assertEquals(1L, countInterestOnDate(accountId, marchDate.minusDays(1)),
                    "Expected exactly one INTEREST posting on posting date");

            assertNoAccrualReversals(accountId);
        } finally {
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(false));
        }
    }

    @Test
    public void testPostInterestNotZero() {
        try {
            final String amountDeposit = "1000";
            final String amountWithdrawal = "1000";

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account liabilityAccount = accountHelper.createLiabilityAccount();
            final Account interestReceivableAccount = accountHelper.createAssetAccount("interestReceivableAccount");
            final Account savingsControlAccount = accountHelper.createLiabilityAccount("Savings Control");
            final Account interestPayableAccount = accountHelper.createLiabilityAccount("Interest Payable");

            final Integer productId = createSavingsProductWithAccrualAccountingWithOutOverdraftAllowed(
                    interestPayableAccount.getAccountID().toString(), savingsControlAccount.getAccountID().toString(),
                    interestReceivableAccount.getAccountID().toString(), assetAccount, incomeAccount, expenseAccount, liabilityAccount);

            final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, "01 January 2025");
            final LocalDate startDate = LocalDate.of(LocalDate.now(Utils.getZoneIdOfTenant()).getYear(), 1, 1);
            final String startStr = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US).format(startDate);

            final Integer accountId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, productId,
                    SavingsAccountHelper.ACCOUNT_TYPE_INDIVIDUAL, startStr);
            savingsAccountHelper.approveSavingsOnDate(accountId, startStr);
            savingsAccountHelper.activateSavings(accountId, startStr);
            savingsAccountHelper.depositToSavingsAccount(accountId, amountDeposit, startStr, CommonConstants.RESPONSE_RESOURCE_ID);

            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(true));
            LocalDate februaryDate = LocalDate.of(startDate.getYear(), 2, 1);
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, februaryDate);

            schedulerJobHelper.executeAndAwaitJob(POST_INTEREST_JOB_NAME);

            List<HashMap> txsFebruary = getInterestTransactions(accountId); // OBTENER EL POSTEO DEL INTEREST

            long daysFebruary = ChronoUnit.DAYS.between(startDate, februaryDate);
            BigDecimal expectedFebruary = calcInterestPosting(productHelper, amountDeposit, daysFebruary);
            Assertions.assertEquals(expectedFebruary, BigDecimal.valueOf(((Double) txsFebruary.get(0).get("amount"))));

            final LocalDate withdrawalDate = LocalDate.of(startDate.getYear(), 2, 1);
            final String withdrawal = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US).format(withdrawalDate);

            BigDecimal runningBalance = new BigDecimal(txsFebruary.get(0).get("runningBalance").toString());
            String withdrawalRunning = runningBalance.setScale(2, RoundingMode.HALF_UP).toString();

            savingsAccountHelper.withdrawalFromSavingsAccount(accountId, withdrawalRunning, withdrawal,
                    CommonConstants.RESPONSE_RESOURCE_ID);
            savingsAccountHelper.withdrawalFromSavingsAccount(accountId, amountWithdrawal, withdrawal,
                    CommonConstants.RESPONSE_RESOURCE_ID);

            LocalDate marchDate = LocalDate.of(startDate.getYear(), 3, 1);
            BusinessDateHelper.updateBusinessDate(requestSpec, responseSpec, BusinessDateType.BUSINESS_DATE, marchDate);

            schedulerJobHelper.executeAndAwaitJob(POST_INTEREST_JOB_NAME);

            List<HashMap> txs = getInterestTransactions(accountId); // CON ESTE DEBEMOS DE VALIDAR QUE EL DIA DE MARZO
            // NO SE TENGA POSTEO EN CERO
            for (HashMap tx : txs) {
                BigDecimal amt = BigDecimal.valueOf(((Double) tx.get("amount")));
                @SuppressWarnings("unchecked")
                Map<String, Object> typeMap = (Map<String, Object>) tx.get("transactionType");
                SavingsAccountTransactionType type = SavingsAccountTransactionType.fromInt(((Double) typeMap.get("id")).intValue());
                if (type.isOverDraftInterestPosting()) {
                    long days = ChronoUnit.DAYS.between(withdrawalDate, marchDate);
                    BigDecimal decimalsss = new BigDecimal(txsFebruary.get(0).get("runningBalance").toString())
                            .subtract(runningBalance.setScale(2, RoundingMode.HALF_UP));
                    BigDecimal withdraw = new BigDecimal(amountWithdrawal);
                    BigDecimal res = withdraw.subtract(decimalsss);
                    BigDecimal expected = calcOverdraftPosting(productHelper, res.toString(), days);
                    Assertions.assertEquals(expected, amt);
                }
            }

            Assertions.assertEquals(0L, countInterestOnDate(accountId, marchDate), "Expected exactly one INTEREST posting on posting date");
            Assertions.assertEquals(1L, countOverdraftOnDate(accountId, marchDate),
                    "Expected exactly one OVERDRAFT posting on posting date");

            assertNoAccrualReversals(accountId);
        } finally {
            globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                    new PutGlobalConfigurationsRequest().enabled(false));
        }
    }

    @Test
    public void testSavingsAccountInterestPostingNoInterest(){
        runAt("12 August 2025", () -> {
            final String amountDeposit = "10000";

            final Account assetAccount = accountHelper.createAssetAccount();
            final Account incomeAccount = accountHelper.createIncomeAccount();
            final Account expenseAccount = accountHelper.createExpenseAccount();
            final Account liabilityAccount = accountHelper.createLiabilityAccount();
            final Account interestReceivableAccount = accountHelper.createAssetAccount("interestReceivableAccount");
            final Account savingsControlAccount = accountHelper.createLiabilityAccount("Savings Control");
            final Account interestPayableAccount = accountHelper.createLiabilityAccount("Interest Payable");

            DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US);
            LocalDate mayDate = LocalDate.of(2025, 5, 1);

            final String SUBMITTED_ON_DATE = mayDate.format(dateFormat);
            final String APPROVED_ON_DATE = mayDate.format(dateFormat);

            final Integer productId = createSavingsProductWithAccrualAccountingWithNoCompoundingPeriod(
                    interestPayableAccount.getAccountID().toString(), savingsControlAccount.getAccountID().toString(),
                    interestReceivableAccount.getAccountID().toString(), assetAccount, incomeAccount, expenseAccount, liabilityAccount);
            Assertions.assertNotNull(productId);


            final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec, "01 January 2025");
            Assertions.assertNotNull(clientId);


            final Integer accountId = savingsAccountHelper.applyForSavingsApplicationOnDate(clientId, productId,
                    SavingsAccountHelper.ACCOUNT_TYPE_INDIVIDUAL, SUBMITTED_ON_DATE);
            savingsAccountHelper.approveSavingsOnDate(accountId, APPROVED_ON_DATE);
            savingsAccountHelper.activateSavings(accountId, APPROVED_ON_DATE);

            savingsAccountHelper.depositToSavingsAccount(accountId, amountDeposit, APPROVED_ON_DATE, CommonConstants.RESPONSE_RESOURCE_ID);

            this.schedulerJobHelper.executeAndAwaitJob("Post Interest For Savings");

            final BigDecimal initialDeposit = new BigDecimal(amountDeposit);

            BigDecimal expectedInterestMay = calcInterestPosting(this.productHelper, amountDeposit, 31);
            BigDecimal expectedInterestJune = calcInterestPosting(this.productHelper, amountDeposit, 30);
            BigDecimal expectedInterestJuly = calcInterestPosting(this.productHelper, amountDeposit, 31);

            LOG.info("Expected Interest May (31 days): {}", expectedInterestMay);
            LOG.info("Expected Interest June (30 days): {}", expectedInterestJune);
            LOG.info("Expected Interest July (31 days): {}", expectedInterestJuly);

            List<HashMap> interestTransactions = getInterestTransactions(accountId);
            Assertions.assertEquals(3, interestTransactions.size(), "Should find 3 interest postings.");

            LocalDate postDateMay = LocalDate.of(2025, 6, 1);
            LocalDate postDateJune = LocalDate.of(2025, 7, 1);
            LocalDate postDateJuly = LocalDate.of(2025, 8, 1);
            BigDecimal totalInterestPosted = BigDecimal.ZERO;
            int validatedPostings = 0;

            for (HashMap transaction : interestTransactions) {
                BigDecimal txAmount = new BigDecimal(transaction.get("amount").toString());

                if (isDate(transaction, postDateMay)) {
                    LOG.info("Validating MAY Interest (Posted {}): Expected {}, Actual {}", postDateMay, expectedInterestMay, txAmount);
                    Assertions.assertEquals(0, expectedInterestMay.compareTo(txAmount), "May interest (31 days) mismatch");
                    validatedPostings++;
                } else if (isDate(transaction, postDateJune)) {
                    LOG.info("Validating JUNE Interest (Posted {}): Expected {}, Actual {}", postDateJune, expectedInterestJune, txAmount);
                    Assertions.assertEquals(0, expectedInterestJune.compareTo(txAmount), "June interest (30 days) mismatch");
                    validatedPostings++;
                } else if (isDate(transaction, postDateJuly)) {
                    LOG.info("Validating JULY Interest (Posted {}): Expected {}, Actual {}", postDateJuly, expectedInterestJuly, txAmount);
                    Assertions.assertEquals(0, expectedInterestJuly.compareTo(txAmount), "July interest (31 days) mismatch");
                    validatedPostings++;
                } else {
                    LocalDate txDate = coerceToLocalDate(transaction);
                    Assertions.fail("Found an interest posting on an unexpected date: " + txDate);
                }

                totalInterestPosted = totalInterestPosted.add(txAmount);
            }


            Assertions.assertEquals(3, validatedPostings, "Did not find and validate all 3 expected postings.");

            BigDecimal expectedFinalBalance = initialDeposit.add(totalInterestPosted);

            List<HashMap> allTransactions = savingsAccountHelper.getSavingsTransactions(accountId);
            Assertions.assertFalse(allTransactions.isEmpty(), "Transaction list should not be empty");

            HashMap latestTransaction = allTransactions.get(0);

            Assertions.assertTrue(latestTransaction.containsKey("runningBalance"), "Latest transaction map must contain 'runningBalance'");

            BigDecimal actualFinalBalance = new BigDecimal(latestTransaction.get("runningBalance").toString());

            LOG.info("Expected Final Balance: {} (Deposit {}) + (Interest {})", expectedFinalBalance, initialDeposit, totalInterestPosted);
            LOG.info("Actual Final Balance (from last transaction): {}", actualFinalBalance);

            Assertions.assertEquals(0, expectedFinalBalance.compareTo(actualFinalBalance),
                    "The final account balance must be the sum of the initial deposit plus interest.");

            LOG.info("--- Savings account interest posting test completed successfully ---");

        });
    }


    private List<HashMap> getInterestTransactions(Integer savingsAccountId) {
        List<HashMap> all = savingsAccountHelper.getSavingsTransactions(savingsAccountId);
        List<HashMap> filtered = new ArrayList<>();
        for (HashMap tx : all) {
            @SuppressWarnings("unchecked")
            Map<String, Object> txType = (Map<String, Object>) tx.get("transactionType");
            SavingsAccountTransactionType type = SavingsAccountTransactionType.fromInt(((Double) txType.get("id")).intValue());
            if (type.isInterestPosting() || type.isOverDraftInterestPosting()) {
                filtered.add(tx);
            }
        }
        return filtered;
    }

    public Integer createSavingsProductWithAccrualAccountingWithOutOverdraftAllowed(final String interestPayableAccount,
            final String savingsControlAccount, final String interestReceivableAccount, final Account... accounts) {
        LOG.info("------------------------------CREATING NEW SAVINGS PRODUCT WITHOUT OVERDRAFT ---------------------------------------");
        this.productHelper = new SavingsProductHelper().withOverDraftRate("100000", "21")
                .withAccountInterestReceivables(interestReceivableAccount).withSavingsControlAccountId(savingsControlAccount)
                .withInterestPayableAccountId(interestPayableAccount).withInterestCompoundingPeriodTypeAsAnnually()
                .withInterestPostingPeriodTypeAsMonthly().withInterestCalculationPeriodTypeAsDailyBalance()
                .withAccountingRuleAsAccrualBased(accounts);

        final String savingsProductJSON = this.productHelper.build();
        return SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
    }

    public Integer createSavingsProductWithAccrualAccountingWithNoCompoundingPeriod(final String interestPayableAccount,
                                                                                    final String savingsControlAccount, final String interestReceivableAccount, final Account... accounts) {
        LOG.info("------------------------------CREATING NEW SAVINGS PRODUCT  ---------------------------------------");
        this.productHelper = new SavingsProductHelper()
                .withAccountInterestReceivables(interestReceivableAccount).withSavingsControlAccountId(savingsControlAccount)
                .withInterestPayableAccountId(interestPayableAccount).withDigitsAfterDecimal("2")
                .withInterestCompoundingPeriodTypeAsNone().withInterestCalculationDaysInYearType_360()
                .withAccountingRuleAsAccrualBased(accounts);

        final String savingsProductJSON = this.productHelper.build();
        return SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
    }


    private BigDecimal calcInterestPosting(SavingsProductHelper productHelper, String amount, long days) {
        BigDecimal rate = productHelper.getNominalAnnualInterestRate().divide(new BigDecimal("100.00"));
        BigDecimal principal = new BigDecimal(amount);
        BigDecimal dayFactor = BigDecimal.ONE.divide(productHelper.getInterestCalculationDaysInYearType(), MathContext.DECIMAL64);
        BigDecimal dailyRate = rate.multiply(dayFactor, MathContext.DECIMAL64);
        BigDecimal periodRate = dailyRate.multiply(BigDecimal.valueOf(days), MathContext.DECIMAL64);
        return principal.multiply(periodRate, MathContext.DECIMAL64).setScale(productHelper.getDecimalCurrency(), RoundingMode.HALF_EVEN);
    }

    private BigDecimal calcOverdraftPosting(SavingsProductHelper productHelper, String amount, long days) {
        BigDecimal rate = productHelper.getNominalAnnualInterestRateOverdraft().divide(new BigDecimal("100.00"));
        BigDecimal principal = new BigDecimal(amount);
        BigDecimal dayFactor = BigDecimal.ONE.divide(productHelper.getInterestCalculationDaysInYearType(), MathContext.DECIMAL64);
        BigDecimal dailyRate = rate.multiply(dayFactor, MathContext.DECIMAL64);
        BigDecimal periodRate = dailyRate.multiply(BigDecimal.valueOf(days), MathContext.DECIMAL64);
        return principal.multiply(periodRate, MathContext.DECIMAL64).setScale(productHelper.getDecimalCurrency(), RoundingMode.HALF_EVEN);
    }

    @SuppressWarnings("unchecked")
    private LocalDate coerceToLocalDate(HashMap tx) {
        String[] candidateKeys = new String[] { "date", "transactionDate", "submittedOnDate", "createdDate" };

        for (String key : candidateKeys) {
            Object v = tx.get(key);
            if (v == null) {
                continue;
            }

            if (v instanceof List<?>) {
                List<?> arr = (List<?>) v;
                if (arr.size() >= 3 && arr.get(0) instanceof Number && arr.get(1) instanceof Number && arr.get(2) instanceof Number) {
                    int year = ((Number) arr.get(0)).intValue();
                    int month = ((Number) arr.get(1)).intValue();
                    int day = ((Number) arr.get(2)).intValue();
                    return LocalDate.of(year, month, day);
                }
            }

            if (v instanceof String) {
                String s = (String) v;
                DateTimeFormatter[] fmts = new DateTimeFormatter[] { DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US),
                        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US), DateTimeFormatter.ofPattern("yyyy-MM-dd") };
                for (DateTimeFormatter f : fmts) {
                    try {
                        return LocalDate.parse(s, f);
                    } catch (Exception ignore) {
                        // intentionally ignored
                    }
                }
            }
        }
        return null;
    }

    private boolean isDate(HashMap tx, LocalDate expected) {
        LocalDate got = coerceToLocalDate(tx);
        return got != null && got.isEqual(expected);
    }

    @SuppressWarnings("unchecked")
    private SavingsAccountTransactionType txType(HashMap tx) {
        Map<String, Object> typeMap = (Map<String, Object>) tx.get("transactionType");
        return SavingsAccountTransactionType.fromInt(((Double) typeMap.get("id")).intValue());
    }

    private long countInterestOnDate(Integer accountId, LocalDate date) {
        List<HashMap> all = savingsAccountHelper.getSavingsTransactions(accountId);
        return all.stream().filter(tx -> isDate(tx, date)).map(this::txType).filter(SavingsAccountTransactionType::isInterestPosting)
                .count();
    }

    private long countOverdraftOnDate(Integer accountId, LocalDate date) {
        List<HashMap> all = savingsAccountHelper.getSavingsTransactions(accountId);
        return all.stream().filter(tx -> isDate(tx, date)).map(this::txType)
                .filter(SavingsAccountTransactionType::isOverDraftInterestPosting).count();
    }

    private void runAccrualsThenPost() {
        try {
            schedulerJobHelper.executeAndAwaitJob(ACCRUALS_JOB_NAME);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Accruals job not found ({}). Continuing without it.", ACCRUALS_JOB_NAME, ex);
        }
        schedulerJobHelper.executeAndAwaitJob(POST_INTEREST_JOB_NAME);
    }

    @SuppressWarnings({ "rawtypes" })
    private boolean isReversed(HashMap tx) {
        Object v = tx.get("reversed");
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    private void assertNoAccrualReversals(Integer accountId) {
        List<HashMap> all = savingsAccountHelper.getSavingsTransactions(accountId);
        long reversedAccruals = all.stream().filter(tx -> {
            SavingsAccountTransactionType t = txType(tx);
            return t.isAccrual() && isReversed(tx);
        }).count();
        Assertions.assertEquals(0L, reversedAccruals, "Accrual reversals were found in account transactions");
    }
}
