package com.paymentsimulator.processor_service;

import java.util.Set;

public class Constants {

    public static final String PAYMENT_INITIATED_STATUS = "INITIATED";
    public static final String PAYMENT_SUCCESS_STATUS = "SUCCESS";
    public static final String PAYMENT_FAILURE_STATUS = "FAILED";
    public static final String PAYMENT_RETRYING_STATUS = "RETRYING";

    // Payments in these states should never be reprocessed
    public static final Set<String> TERMINAL_STATUSES = Set.of(
            PAYMENT_SUCCESS_STATUS,
            PAYMENT_FAILURE_STATUS
    );

}
