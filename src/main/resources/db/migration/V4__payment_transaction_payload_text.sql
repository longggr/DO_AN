ALTER TABLE payment_transaction
    MODIFY raw_request_payload LONGTEXT NULL,
    MODIFY raw_response_payload LONGTEXT NULL;
